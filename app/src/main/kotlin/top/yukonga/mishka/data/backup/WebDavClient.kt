package top.yukonga.mishka.data.backup

import android.util.Base64
import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

open class WebDavException(message: String) : Exception(message)

class WebDavStaleException(val remoteVersion: Long) : WebDavException(
    "Remote snapshot is newer (version $remoteVersion); restore it before backing up",
)

data class RemoteBackup(
    val name: String,
    val version: Long,
    val size: Long?,
)

/**
 * WebDAV client for immutable Mishka snapshots.
 *
 * The legacy fixed file is read-only compatibility input. New writes go to
 * `Mishka/snapshots/` and never replace `Mishka/mishka-backup.zip`.
 */
class WebDavClient(
    baseUrl: String,
    username: String,
    password: String,
) {
    private val root = baseUrl.trim().trimEnd('/')
    private val legacyDirUrl = "$root/$LEGACY_DIR/"
    private val legacyFileUrl = "$legacyDirUrl$LEGACY_FILE"
    private val snapshotsDirUrl = "$legacyDirUrl$SNAPSHOTS_DIR/"
    private val authorization = "Basic " + Base64.encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
    )

    private fun buildClient() = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 300_000
        }
    }

    /** Creates the new snapshot directory; it never touches the legacy file. */
    suspend fun testConnection() {
        buildClient().use { client ->
            client.ensureCollection(legacyDirUrl)
            client.ensureCollection(snapshotsDirUrl)
        }
    }

    suspend fun listSnapshots(): List<RemoteBackup> {
        buildClient().use { client ->
            val response = client.davRequest(
                snapshotsDirUrl,
                HttpMethod("PROPFIND"),
                headers = mapOf("Depth" to "1"),
            )
            if (response.status == HttpStatusCode.NotFound) return emptyList()
            if (!response.status.isSuccess()) {
                throw WebDavException("PROPFIND failed: HTTP ${response.status.value}")
            }
            return parseSnapshots(response.bodyAsText())
        }
    }

    /** Uploads one immutable snapshot. Existing files are never overwritten. */
    suspend fun uploadSnapshot(file: File, name: String) {
        require(isSnapshotName(name)) { "invalid snapshot name" }
        buildClient().use { client ->
            client.ensureCollection(legacyDirUrl)
            client.ensureCollection(snapshotsDirUrl)
            val response = client.davRequest(snapshotUrl(name), HttpMethod.Put, payload = file)
            if (!response.status.isSuccess()) {
                throw WebDavException("PUT failed: HTTP ${response.status.value}")
            }
        }
    }

    /** Creates the next snapshot after checking the remote version. */
    suspend fun uploadNextSnapshot(
        file: File,
        localVersion: Long,
        force: Boolean = false,
    ): RemoteBackup {
        val remoteMax = listSnapshots().maxOfOrNull { it.version } ?: 0L
        if (!force && remoteMax > localVersion) throw WebDavStaleException(remoteMax)
        val next = maxOf(localVersion, remoteMax) + 1L
        val name = newSnapshotName(next)
        uploadSnapshot(file, name)
        return RemoteBackup(name, next, file.length())
    }

    suspend fun downloadSnapshot(name: String, target: File): Boolean {
        require(isSnapshotName(name)) { "invalid snapshot name" }
        return download(snapshotUrl(name), target, "snapshot")
    }

    /** Compatibility import only; the new backup flow never writes this file. */
    suspend fun downloadLegacy(target: File): Boolean =
        buildClient().use { client -> client.download(legacyFileUrl, target, "legacy backup") }

    /** Explicit compatibility export for the original app. */
    suspend fun uploadLegacy(file: File) {
        buildClient().use { client ->
            client.ensureCollection(legacyDirUrl)
            val response = client.davRequest(legacyFileUrl, HttpMethod.Put, payload = file)
            if (!response.status.isSuccess()) {
                throw WebDavException("legacy PUT failed: HTTP ${response.status.value}")
            }
        }
    }

    suspend fun pruneSnapshots(keep: Int = DEFAULT_RETENTION) {
        require(keep >= 1) { "keep must be positive" }
        val snapshots = runCatching { listSnapshots() }
            .getOrElse { return }
            .sortedWith(compareByDescending<RemoteBackup> { it.version }.thenByDescending { it.name })
        val stale = snapshots.drop(keep)
        if (stale.isEmpty()) return
        runCatching {
            buildClient().use { client ->
                stale.forEach { snapshot ->
                    runCatching {
                        val response = client.davRequest(snapshotUrl(snapshot.name), HttpMethod.Delete)
                        if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) {
                            throw WebDavException("DELETE failed: HTTP ${response.status.value}")
                        }
                    }
                }
            }
        }
        // The new snapshot is already safe; retention cleanup is best effort.
    }

    private suspend fun download(url: String, target: File, label: String): Boolean =
        buildClient().use { client -> client.download(url, target, label) }

    private suspend fun HttpClient.download(
        url: String,
        target: File,
        label: String,
    ): Boolean {
        val response = davRequest(url, HttpMethod.Get)
        return when {
            response.status == HttpStatusCode.NotFound -> false
            response.status.isSuccess() -> withContext(Dispatchers.IO) {
                response.bodyAsChannel().toInputStream().use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                true
            }
            else -> throw WebDavException("GET $label failed: HTTP ${response.status.value}")
        }
    }

    private suspend fun HttpClient.ensureCollection(url: String) {
        val response = davRequest(url, HttpMethod("MKCOL"))
        val ok = response.status.isSuccess() || response.status == HttpStatusCode.MethodNotAllowed
        if (!ok) {
            val hint = when (response.status) {
                HttpStatusCode.Unauthorized -> "unauthorized"
                HttpStatusCode.Conflict -> "parent directory missing"
                else -> "HTTP ${response.status.value}"
            }
            throw WebDavException("MKCOL failed: $hint")
        }
    }

    /** Follows only same-host redirects while retaining Basic credentials. */
    private suspend fun HttpClient.davRequest(
        url: String,
        httpMethod: HttpMethod,
        payload: File? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        var current = url
        var hops = 0
        while (true) {
            val response = request(current) {
                method = httpMethod
                header(HttpHeaders.Authorization, authorization)
                headers.forEach { (name, value) -> header(name, value) }
                if (payload != null) setBody(payload.zipContent())
            }
            if (response.status.value !in REDIRECT_CODES || hops >= MAX_REDIRECTS) return response
            val location = response.headers[HttpHeaders.Location] ?: return response
            val next = runCatching { URI(current).resolve(location) }.getOrNull() ?: return response
            if (!next.carriesCredentialsSafelyFrom(URI(current))) return response
            current = next.toString()
            hops++
        }
    }

    private fun File.zipContent(): OutgoingContent = object : OutgoingContent.ReadChannelContent() {
        override val contentType = ContentType.Application.Zip
        override val contentLength = length()
        override fun readFrom(): ByteReadChannel = readChannel()
    }

    private fun URI.carriesCredentialsSafelyFrom(from: URI): Boolean {
        if (host == null || !host.equals(from.host, ignoreCase = true)) return false
        val fromHttps = from.scheme.equals("https", ignoreCase = true)
        val toHttps = scheme.equals("https", ignoreCase = true)
        return toHttps || !fromHttps
    }

    private fun snapshotUrl(name: String): String = "$snapshotsDirUrl$name"

    private fun parseSnapshots(xml: String): List<RemoteBackup> {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val result = mutableListOf<RemoteBackup>()
        var event = parser.eventType
        var inResponse = false
        var href: String? = null
        var size: Long? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "response" -> {
                        inResponse = true
                        href = null
                        size = null
                    }
                    "href" -> if (inResponse) href = parser.nextText()
                    "getcontentlength" -> if (inResponse) size = parser.nextText().toLongOrNull()
                }
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "response" && inResponse) {
                    href?.let { hrefValue ->
                        val path = runCatching { URI(hrefValue).path ?: hrefValue }.getOrDefault(hrefValue)
                        val encodedName = path.substringAfterLast('/')
                        val name = runCatching { URLDecoder.decode(encodedName, Charsets.UTF_8.name()) }
                            .getOrDefault(encodedName)
                        parseSnapshotVersion(name)?.let { result += RemoteBackup(name, it, size) }
                    }
                    inResponse = false
                }
            }
            event = parser.next()
        }
        return result.distinctBy { it.name }
    }

    companion object {
        const val LEGACY_DIR = "Mishka"
        const val LEGACY_FILE = "mishka-backup.zip"
        const val SNAPSHOTS_DIR = "snapshots"
        const val DEFAULT_RETENTION = 5
        private val REDIRECT_CODES = setOf(301, 302, 307, 308)
        private const val MAX_REDIRECTS = 3
        private val SNAPSHOT_PATTERN = Regex("^mishka-(\\d+)-[^/]+\\.mishka$")

        fun newSnapshotName(version: Long): String =
            "mishka-${version.toString().padStart(6, '0')}-${UUID.randomUUID().toString().take(8)}.android.mishka"

        fun parseSnapshotVersion(name: String): Long? =
            SNAPSHOT_PATTERN.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

        fun isSnapshotName(name: String): Boolean = parseSnapshotVersion(name) != null
    }
}
