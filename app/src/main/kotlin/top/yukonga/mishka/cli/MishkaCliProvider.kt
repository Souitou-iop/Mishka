package top.yukonga.mishka.cli

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Base64
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject

/**
 * Synchronous JSON-RPC bridge for `adb shell content call`.
 * The provider is intentionally not a network listener: no TCP port is opened on the device.
 */
class MishkaCliProvider : ContentProvider() {

    private val handler: MishkaCliCommandHandler by inject()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceShellCaller()
        require(method == METHOD_RPC) { "unsupported method: $method" }
        val request = decodeRequest(arg ?: error("missing request"))
        val response = runBlocking { handler.handle(request) }
        val encoded = Base64.encodeToString(json.encodeToString(MishkaCliResponse.serializer(), response).toByteArray(), Base64.NO_WRAP)
        return Bundle().apply { putString(RESULT_KEY, encoded) }
    }

    private fun decodeRequest(encoded: String): MishkaCliRequest {
        val raw = Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
        return json.decodeFromString(MishkaCliRequest.serializer(), raw)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceShellCaller()
        val appContext = requireNotNull(context)
        val file = when (uri.path) {
            "/backup" -> MishkaCliTransfer.backupFile(appContext)
            "/response" -> MishkaCliTransfer.responseFile(appContext)
            else -> throw IllegalArgumentException("unsupported file: $uri")
        }
        return when {
            mode.startsWith("r") -> {
                if (!file.isFile) throw FileNotFoundException(file.absolutePath)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            mode.startsWith("w") -> {
                file.parentFile?.mkdirs()
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY,
                )
            }
            else -> throw IllegalArgumentException("unsupported file mode: $mode")
        }
    }

    private fun enforceShellCaller() {
        val uid = Binder.getCallingUid()
        check(uid == Process.SHELL_UID || uid == Process.ROOT_UID || uid == Process.SYSTEM_UID || uid == Process.myUid()) {
            "Mishka CLI is available to adb shell only"
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? =
        when (uri.path) {
            "/backup" -> "application/zip"
            "/response" -> "application/json"
            else -> null
        }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_RPC = "rpc"
        const val RESULT_KEY = "result"
    }
}
