package top.yukonga.mishka.cli

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.data.backup.BackupManager
import top.yukonga.mishka.data.backup.RemoteBackup
import top.yukonga.mishka.data.backup.WebDavClient
import top.yukonga.mishka.data.diagnostics.ConfigDiagnosticsBuilder
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.store.OverrideProfileStore
import top.yukonga.mishka.domain.model.ConfigValidationResult
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.domain.model.OverrideProfile
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.domain.repository.OverrideProfileRepository
import top.yukonga.mishka.domain.repository.SubscriptionRepository
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.WifiPolicyController
import kotlin.time.Duration.Companion.seconds

/**
 * ADB CLI control surface. It deliberately calls the same repositories and controller as the UI;
 * it is not a second service-start implementation. The provider only accepts the ADB shell UID.
 */
class MishkaCliCommandHandler(
    private val context: Context,
    private val serviceController: ProxyServiceController,
    private val subscriptions: SubscriptionRepository,
    private val processor: ProfileProcessor,
    private val overrideStore: OverrideJsonStore,
    private val overrideProfiles: OverrideProfileRepository,
    private val overrideProfileStore: OverrideProfileStore,
    private val storage: PlatformStorage,
    private val fileManager: ProfileFileManager,
    private val connectionManager: MihomoConnectionManager,
    private val diagnostics: ConfigDiagnosticsBuilder,
    private val backupManager: BackupManager,
    private val bootStartManager: BootStartManager,
    private val wifiPolicyController: WifiPolicyController,
) {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    suspend fun handle(request: MishkaCliRequest): MishkaCliResponse = try {
        MishkaCliResponse(ok = true, data = dispatch(request.command.trim().lowercase(), request.args))
    } catch (e: TimeoutCancellationException) {
        MishkaCliResponse(ok = false, error = MishkaCliError("timeout", e.message.orEmpty().ifBlank { "command timed out" }))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        MishkaCliResponse(
            ok = false,
            error = MishkaCliError(
                code = errorCode(e),
                message = e.message.orEmpty().ifBlank { e::class.simpleName ?: "command failed" },
            ),
        )
    }

    private suspend fun dispatch(command: String, args: JsonObject): JsonElement = when (command) {
        "capabilities" -> capabilities()
        "status", "proxy.status" -> status()
        "proxy.start" -> start(args)
        "proxy.stop" -> stop()
        "proxy.restart" -> restart(args)
        "proxy.toggle" -> toggle()

        "profiles.list" -> {
            subscriptions.refresh()
            subscriptionArray(subscriptions.subscriptions.value)
        }
        "profiles.active" -> {
            subscriptions.refresh()
            subscriptions.getActive()?.let(::encodeSubscription) ?: JsonNull
        }
        "profiles.use" -> useProfile(args)
        "profiles.create" -> createProfile(args)
        "profiles.patch" -> patchProfile(args)
        "profiles.apply" -> applyProfile(args)
        "profiles.update" -> updateProfile(args)
        "profiles.update-all" -> updateAllProfiles()
        "profiles.release" -> releaseProfile(args)
        "profiles.delete" -> deleteProfile(args)

        "overrides.list" -> overrideProfileArray(overrideProfileStore.all())
        "overrides.get" -> getOverride(args)
        "overrides.create" -> createOverride(args)
        "overrides.edit" -> editOverride(args)
        "overrides.save" -> saveOverride(args)
        "overrides.update" -> updateOverride(args)
        "overrides.delete" -> deleteOverride(args)
        "overrides.select" -> selectOverrides(args)

        "override.get" -> encode(overrideStore.load().redacted(args.boolean("includeSecrets")))
        "override.replace" -> replaceOverride(args)
        "override.reset" -> {
            overrideStore.save(ConfigurationOverride())
            JsonObject(emptyMap())
        }

        "settings.dump" -> dumpSettings()
        "settings.get" -> getSetting(args)
        "settings.set" -> setSetting(args)
        "boot.status" -> bootStatus()
        "boot.set" -> setBoot(args)
        "wifi.status" -> wifiStatus()
        "wifi.start" -> startWifiPolicy()
        "wifi.stop" -> stopWifiPolicy()
        "wifi.evaluate" -> evaluateWifiPolicy()
        "backup.export" -> exportBackup()
        "backup.import" -> importBackup()
        "backup.webdav-test" -> webDavTest()
        "backup.webdav-list" -> webDavList()
        "backup.webdav-upload" -> webDavUpload(args.boolean("force"))
        "backup.webdav-download" -> webDavDownload(args.boolean("restore"))
        "backup.webdav-export-legacy" -> webDavExportLegacy()
        "backup.clear" -> clearBackup()

        "runtime.version" -> runtime { encode(it.getVersion().getOrThrow()) }
        "runtime.tailscale" -> runtime { encode(it.getTailscaleStatus().getOrThrow()) }
        "runtime.config" -> runtime { encode(it.getConfig().getOrThrow()) }
        "runtime.proxies" -> runtime { encode(it.getProxies().getOrThrow()) }
        "runtime.groups" -> runtime { encode(it.getGroups().getOrThrow()) }
        "runtime.select" -> runtime { it.selectProxy(args.requiredString("group"), args.requiredString("name")).getOrThrow(); JsonObject(emptyMap()) }
        "runtime.unfix" -> runtime { it.unfixProxy(args.requiredString("group")).getOrThrow(); JsonObject(emptyMap()) }
        "runtime.delay" -> runtime { encode(it.getProxyDelay(args.requiredString("name"), args.string("url") ?: "http://www.gstatic.com/generate_204", args.int("timeout", 5000)).getOrThrow()) }
        "runtime.provider-delay" -> runtime { encode(it.getProviderProxyDelay(args.requiredString("provider"), args.requiredString("name"), args.string("url") ?: "http://www.gstatic.com/generate_204", args.int("timeout", 5000)).getOrThrow()) }
        "runtime.rules" -> runtime { encode(it.getRules().getOrThrow()) }
        "runtime.connections" -> runtime { encode(it.getConnections().getOrThrow()) }
        "runtime.close-all" -> runtime { it.closeAllConnections().getOrThrow(); JsonObject(emptyMap()) }
        "runtime.close" -> runtime { it.closeConnection(args.requiredString("id")).getOrThrow(); JsonObject(emptyMap()) }
        "runtime.providers" -> runtime { encode(it.getProviders().getOrThrow()) }
        "runtime.provider-update" -> runtime { it.updateProvider(args.requiredString("name")).getOrThrow(); JsonObject(emptyMap()) }
        "runtime.rule-providers" -> runtime { encode(it.getRuleProviders().getOrThrow()) }
        "runtime.rule-provider-update" -> runtime { it.updateRuleProvider(args.requiredString("name")).getOrThrow(); JsonObject(emptyMap()) }
        "runtime.dns" -> runtime { encode(it.queryDns(args.requiredString("name"), args.string("type") ?: "A").getOrThrow()) }
        "runtime.fakeip-flush" -> runtime { it.flushFakeIp().getOrThrow(); JsonObject(emptyMap()) }
        "runtime.dns-flush" -> runtime { it.flushDnsCache().getOrThrow(); JsonObject(emptyMap()) }
        "runtime.traffic" -> runtime { encode(withTimeout(5.seconds) { it.trafficFlow().first() }) }
        "runtime.memory" -> runtime { encode(withTimeout(5.seconds) { it.memoryFlow().first() }) }
        "runtime.log" -> runtime { encode(withTimeout(5.seconds) { it.logsFlow(args.string("level") ?: "info").first() }) }

        "diagnostics.preview" -> preview(false)
        "diagnostics.validate" -> preview(true)
        else -> throw IllegalArgumentException("unknown command: $command")
    }

    private fun capabilities(): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        putJsonArray("commands") {
            COMMANDS.forEach { add(JsonPrimitive(it)) }
        }
    }

    private fun status(): JsonObject {
        val status = serviceController.status.value
        return buildJsonObject {
            put("state", status.state.name.lowercase())
            put("tunMode", status.tunMode.name.lowercase())
            put("running", status.state == ProxyState.Running)
            put("starting", status.state == ProxyState.Starting)
            put("stopping", status.state == ProxyState.Stopping)
            put("error", status.errorMessage)
            put("startTime", status.startTime)
            put("mihomoPid", status.mihomoPid)
            put("externalController", status.externalController)
            put("vpnPermission", serviceController.hasVpnPermission())
            put("rootPermission", serviceController.hasRootPermission())
            put("activeProfileId", subscriptions.getActiveId())
            put("runtimeConnected", connectionManager.repository.value != null)
            put("bootStartEnabled", bootStartManager.isEnabled())
            put("wifiPolicyEnabled", storage.getString(StorageKeys.WIFI_POLICY_ENABLED, "false") == "true")
            put("wifiPermission", wifiPolicyController.hasRequiredPermission())
        }
    }

    private fun start(args: JsonObject): JsonObject {
        val id = serviceController.resolveStartSubscriptionId(args.string("profileId"))
            ?: throw IllegalStateException("no startable profile")
        requireVpnPermission()
        serviceController.start(id)
        return buildJsonObject { put("accepted", true); put("profileId", id) }
    }

    private fun requireVpnPermission() {
        if (!serviceController.hasVpnPermission()) {
            throw SecurityException("VPN permission required; open Mishka and approve Android VPN consent first")
        }
    }

    private fun stop(): JsonObject {
        serviceController.stop()
        return buildJsonObject { put("accepted", true) }
    }

    private fun restart(args: JsonObject): JsonObject {
        val id = serviceController.resolveStartSubscriptionId(args.string("profileId"))
            ?: throw IllegalStateException("no startable profile")
        requireVpnPermission()
        serviceController.restart(id)
        return buildJsonObject { put("accepted", true); put("profileId", id) }
    }

    private fun toggle(): JsonObject {
        when (serviceController.status.value.state) {
            ProxyState.Running, ProxyState.Starting, ProxyState.Stopping -> serviceController.stop()
            ProxyState.Stopped, ProxyState.Error -> start(JsonObject(emptyMap()))
        }
        return buildJsonObject { put("accepted", true) }
    }

    private fun useProfile(args: JsonObject): JsonObject {
        val id = args.requiredString("id")
        subscriptions.setActive(id)
        if (args.boolean("restart")) serviceController.restart(id)
        return buildJsonObject { put("id", id); put("restarted", args.boolean("restart")) }
    }

    private suspend fun createProfile(args: JsonObject): JsonElement {
        val type = parseProfileType(args.string("type") ?: "url")
        val profile = subscriptions.create(
            type = type,
            name = args.string("name").orEmpty(),
            source = args.string("source").orEmpty(),
            interval = args.long("interval"),
            userAgent = args.string("userAgent").orEmpty(),
            ageSecretKey = args.string("ageSecretKey").orEmpty(),
        )
        if (type == ProfileType.File && args.string("content") != null) {
            fileManager.savePendingConfig(profile.id, args.string("content").orEmpty())
        }
        return encodeSubscription(profile)
    }

    private suspend fun patchProfile(args: JsonObject): JsonObject {
        val id = args.requiredString("id")
        subscriptions.patch(
            uuid = id,
            name = args.string("name").orEmpty(),
            source = args.string("source").orEmpty(),
            interval = args.long("interval"),
            userAgent = args.string("userAgent").orEmpty(),
            ageSecretKey = args.string("ageSecretKey").orEmpty(),
        )
        return buildJsonObject { put("id", id); put("pending", true) }
    }

    private suspend fun applyProfile(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        // 新 File 订阅只在 pending/ 有配置，直接提交 DB 会留下无法启动的订阅；
        // 已导入 File 的元数据编辑才可以复用 imported/ 配置直接提交。
        val hasImportedConfig = fileManager.readImportedFile(id, "config.yaml") != null
        if (subscriptions.validatePendingForCommit(id) || !hasImportedConfig) {
            processor.apply(id)
        } else {
            subscriptions.commitPendingProfile(id)
        }
        if (args.boolean("restart")) serviceController.restartAfterProfileUpdate(id)
        subscriptions.refresh()
        return subscriptions.subscriptions.value.firstOrNull { it.id == id }?.let(::encodeSubscription) ?: JsonNull
    }

    private suspend fun updateProfile(args: JsonObject): JsonObject {
        val id = args.requiredString("id")
        processor.update(id)
        serviceController.restartAfterProfileUpdate(id)
        return buildJsonObject { put("id", id); put("updated", true) }
    }

    private suspend fun updateAllProfiles(): JsonArray {
        val updated = buildList {
            subscriptions.subscriptions.value.filter { it.url.isNotBlank() }.forEach { profile ->
                processor.update(profile.id)
                add(profile.id)
            }
        }
        updated.forEach(serviceController::restartAfterProfileUpdate)
        return JsonArray(updated.map(::JsonPrimitive))
    }

    private suspend fun releaseProfile(args: JsonObject): JsonObject {
        val id = args.requiredString("id")
        subscriptions.release(id)
        fileManager.releasePending(id)
        return buildJsonObject { put("id", id) }
    }

    private suspend fun deleteProfile(args: JsonObject): JsonObject {
        val id = args.requiredString("id")
        subscriptions.delete(id)
        fileManager.deleteDirs(id)
        return buildJsonObject { put("id", id) }
    }

    private suspend fun getOverride(args: JsonObject): JsonObject {
        val profile = overrideProfileStore.find(args.requiredString("id")) ?: throw IllegalArgumentException("override not found")
        val base = json.encodeToJsonElement(profile).jsonObject.toMutableMap()
        if (args.boolean("includeContent")) base["content"] = JsonPrimitive(overrideProfiles.readContent(profile.id))
        return JsonObject(base)
    }

    private suspend fun createOverride(args: JsonObject): JsonElement {
        val format = parseFormat(args.string("format") ?: "yaml")
        val sourceType = args.string("sourceType")?.lowercase()
        val profile = when {
            sourceType == "remote" -> overrideProfiles.addRemote(args.requiredString("name"), args.requiredString("url"), format)
            sourceType == "local" -> overrideProfiles.addLocal(args.requiredString("name"), args.requiredString("fileName"), format, args.string("content").orEmpty())
            else -> overrideProfiles.addBlank(args.requiredString("name"), format)
        }
        return encode(profile)
    }

    private suspend fun editOverride(args: JsonObject): JsonObject {
        overrideProfiles.edit(
            id = args.requiredString("id"),
            name = args.requiredString("name"),
            url = args.string("url").orEmpty(),
            format = parseFormat(args.string("format") ?: "yaml"),
        )
        return buildJsonObject { put("id", args.requiredString("id")) }
    }

    private suspend fun saveOverride(args: JsonObject): JsonObject {
        val id = args.requiredString("id")
        overrideProfiles.saveContent(id, args.string("content").orEmpty())
        return buildJsonObject { put("id", id) }
    }

    private suspend fun updateOverride(args: JsonObject): JsonObject {
        val id = args.requiredString("id")
        overrideProfiles.update(id)
        return buildJsonObject { put("id", id); put("updated", true) }
    }

    private suspend fun deleteOverride(args: JsonObject): JsonObject {
        val id = args.requiredString("id")
        overrideProfiles.delete(id)
        return buildJsonObject { put("id", id) }
    }

    private suspend fun selectOverrides(args: JsonObject): JsonObject {
        val ids = (args["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val order = (args.element("sortPreference") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val subscriptionId = args.string("subscriptionId") ?: subscriptions.getActiveId()
            ?: throw IllegalArgumentException("no active profile")
        overrideProfiles.setSelection(subscriptionId, ids, order)
        return buildJsonObject { put("subscriptionId", subscriptionId); put("count", ids.size) }
    }

    private fun replaceOverride(args: JsonObject): JsonElement {
        val value = args["value"] ?: throw IllegalArgumentException("missing argument: value")
        overrideStore.save(json.decodeFromJsonElement(ConfigurationOverride.serializer(), value))
        return encode(overrideStore.load().redacted(args.boolean("includeSecrets")))
    }

    private fun dumpSettings(): JsonObject = buildJsonObject {
        storage.dumpAll().forEach { (key, value) ->
            if (key in SENSITIVE_KEYS) return@forEach
            put(key, value.toJsonElement())
        }
        put("boot_start_enabled", bootStartManager.isEnabled())
    }

    private fun getSetting(args: JsonObject): JsonObject {
        val key = canonicalSettingKey(args.requiredString("key"))
        if (key in SENSITIVE_KEYS) {
            val configured = if (key == StorageKeys.TAILSCALE_AUTH_KEY) {
                storage.hasSecret(key)
            } else {
                storage.getString(key, "").isNotBlank()
            }
            return buildJsonObject { put("key", key); put("configured", configured) }
        }
        return buildJsonObject { put("key", key); put("value", storage.getString(key, "")) }
    }

    private fun setSetting(args: JsonObject): JsonObject {
        val key = canonicalSettingKey(args.requiredString("key"))
        val value = args["value"] ?: throw IllegalArgumentException("missing argument: value")
        if (key in BLOCKED_KEYS) throw IllegalArgumentException("setting is runtime-managed: $key")
        if (key !in KNOWN_SETTINGS) throw IllegalArgumentException("unknown setting: $key")
        if (key in SENSITIVE_KEYS && key == StorageKeys.TAILSCALE_AUTH_KEY) {
            storage.putSecret(key, (value as? JsonPrimitive)?.contentOrNull.orEmpty())
        } else if (value is JsonArray) {
            storage.putStringSet(key, value.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet())
        } else {
            storage.putString(key, (value as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        if (key == StorageKeys.WIFI_POLICY_ENABLED) {
            if ((value as? JsonPrimitive)?.contentOrNull == "true") wifiPolicyController.startMonitor()
            else wifiPolicyController.stopMonitor()
        }
        return buildJsonObject { put("key", key); put("changed", true) }
    }

    private fun canonicalSettingKey(input: String): String {
        val normalized = input.lowercase().replace('-', '_')
        return STORAGE_KEY_ALIASES[normalized] ?: input
    }

    private fun bootStatus(): JsonObject = buildJsonObject {
        put("enabled", bootStartManager.isEnabled())
    }

    private fun setBoot(args: JsonObject): JsonObject {
        val enabled = args.boolean("enabled")
        bootStartManager.setEnabled(enabled)
        return buildJsonObject { put("enabled", bootStartManager.isEnabled()) }
    }

    private fun wifiStatus(): JsonObject = buildJsonObject {
        put("enabled", storage.getString(StorageKeys.WIFI_POLICY_ENABLED, "false") == "true")
        put("permission", wifiPolicyController.hasRequiredPermission())
        put("ssid", wifiPolicyController.currentSsid())
        put("action", storage.getString(StorageKeys.WIFI_POLICY_ACTION, "stop_service"))
    }

    private fun startWifiPolicy(): JsonObject {
        storage.putString(StorageKeys.WIFI_POLICY_ENABLED, "true")
        wifiPolicyController.startMonitor()
        return wifiStatus()
    }

    private fun stopWifiPolicy(): JsonObject {
        storage.putString(StorageKeys.WIFI_POLICY_ENABLED, "false")
        wifiPolicyController.stopMonitor()
        return wifiStatus()
    }

    private fun evaluateWifiPolicy(): JsonObject {
        wifiPolicyController.evaluateNow()
        return buildJsonObject { put("accepted", true) }
    }

    private suspend fun exportBackup(): JsonObject {
        val file = MishkaCliTransfer.backupFile(context)
        backupManager.writeBackupTo(file)
        return buildJsonObject { put("uri", "content://${context.packageName}.cli/backup"); put("size", file.length()) }
    }

    private suspend fun importBackup(): JsonObject {
        val file = MishkaCliTransfer.backupFile(context)
        require(file.isFile && file.length() > 0) { "no uploaded backup; use content write first" }
        backupManager.restoreBackupFrom(file)
        return buildJsonObject { put("restored", true); put("restartRequired", true) }
    }

    private fun webDavClient(): WebDavClient {
        val url = storage.getString(StorageKeys.WEBDAV_URL, "").trim()
        val username = storage.getString(StorageKeys.WEBDAV_USERNAME, "")
        val password = storage.getString(StorageKeys.WEBDAV_PASSWORD, "")
        require(url.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            "WebDAV credentials are not configured"
        }
        return WebDavClient(url, username, password)
    }

    private suspend fun webDavTest(): JsonObject {
        webDavClient().testConnection()
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun webDavList(): JsonArray =
        JsonArray(
            webDavClient().listSnapshots()
                .sortedWith(compareByDescending<RemoteBackup> { it.version }.thenByDescending { it.name })
                .map { snapshot ->
                    buildJsonObject {
                        put("name", snapshot.name)
                        put("version", snapshot.version)
                        snapshot.size?.let { put("size", it) }
                    }
                },
        )

    private suspend fun webDavUpload(force: Boolean): JsonObject {
        val client = webDavClient()
        val localVersion = storage.getString(StorageKeys.WEBDAV_SYNC_VERSION, "0").toLongOrNull()?.coerceAtLeast(0) ?: 0
        val snapshot = backupManager.withTransferFile { file ->
            backupManager.writeBackupTo(file)
            client.uploadNextSnapshot(file, localVersion, force)
        }
        storage.putString(StorageKeys.WEBDAV_SYNC_VERSION, snapshot.version.toString())
        client.pruneSnapshots()
        return buildJsonObject {
            put("uploaded", true)
            put("name", snapshot.name)
            put("version", snapshot.version)
            put("forced", force)
        }
    }

    private suspend fun webDavDownload(restore: Boolean): JsonObject {
        val client = webDavClient()
        val snapshots = client.listSnapshots()
            .sortedWith(compareByDescending<RemoteBackup> { it.version }.thenByDescending { it.name })
        var found = false
        var name: String? = null
        var version: Long? = null
        backupManager.withTransferFile { file ->
            val latest = snapshots.firstOrNull()
            if (latest != null) {
                found = client.downloadSnapshot(latest.name, file)
                name = latest.name
                version = latest.version
            } else {
                found = client.downloadLegacy(file)
                name = if (found) WebDavClient.LEGACY_FILE else null
            }
            if (found && restore) {
                backupManager.restoreBackupFrom(file)
                if (version != null) {
                    val local = storage.getString(StorageKeys.WEBDAV_SYNC_VERSION, "0").toLongOrNull() ?: 0
                    storage.putString(StorageKeys.WEBDAV_SYNC_VERSION, maxOf(local, version).toString())
                }
            }
        }
        return buildJsonObject {
            put("found", found)
            put("name", name)
            version?.let { put("version", it) }
            put("restored", found && restore)
            put("restartRequired", found && restore)
        }
    }

    private suspend fun webDavExportLegacy(): JsonObject {
        val client = webDavClient()
        backupManager.withTransferFile { file ->
            backupManager.writeBackupTo(file)
            client.uploadLegacy(file)
        }
        return buildJsonObject { put("uploaded", true); put("name", WebDavClient.LEGACY_FILE) }
    }

    private fun clearBackup(): JsonObject {
        MishkaCliTransfer.backupFile(context).delete()
        return buildJsonObject { put("cleared", true) }
    }

    private suspend fun preview(validate: Boolean): JsonObject =
        (if (validate) diagnostics.validate() else diagnostics.buildPreview()).toJsonObject()

    private suspend fun <T> runtime(block: suspend (top.yukonga.mishka.domain.repository.MihomoRepository) -> T): T =
        connectionManager.repository.value?.let { repository -> block(repository) } ?: throw IllegalStateException("mihomo is not running")

    private inline fun <reified T> encode(value: T): JsonElement = json.encodeToJsonElement(value)

    private fun encodeSubscription(value: Subscription): JsonObject = buildJsonObject {
        put("id", value.id)
        put("name", value.name)
        put("type", value.type.name)
        put("url", value.url)
        put("userAgent", value.userAgent)
        put("ageSecretKeyConfigured", value.ageSecretKey.isNotBlank())
        putJsonArray("overrideIds") { value.overrideIds.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("overrideSortPreference") { value.overrideSortPreference.forEach { add(JsonPrimitive(it)) } }
        put("interval", value.interval)
        put("upload", value.upload)
        put("download", value.download)
        put("total", value.total)
        put("expire", value.expire)
        put("updatedAt", value.updatedAt)
        put("isActive", value.isActive)
        put("imported", value.imported)
        put("pending", value.pending)
    }

    private fun subscriptionArray(values: Iterable<Subscription>): JsonArray = JsonArray(
        values.map(::encodeSubscription),
    )

    private fun overrideProfileArray(values: Iterable<OverrideProfile>): JsonArray = JsonArray(
        values.map { json.encodeToJsonElement(OverrideProfile.serializer(), it) },
    )

    private fun parseProfileType(value: String): ProfileType = when (value.lowercase()) {
        "file" -> ProfileType.File
        "external" -> ProfileType.External
        else -> ProfileType.Url
    }

    private fun parseFormat(value: String): OverrideFormat = when (value.lowercase()) {
        "javascript", "js" -> OverrideFormat.JavaScript
        else -> OverrideFormat.Yaml
    }

    private fun errorCode(error: Throwable): String = when (error) {
        is TimeoutCancellationException -> "timeout"
        is IllegalArgumentException -> "invalid_argument"
        else -> "failed"
    }

    private fun ConfigurationOverride.redacted(includeSecrets: Boolean): ConfigurationOverride =
        if (includeSecrets) this else copy(secret = null)

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this.toString())
        is String -> JsonPrimitive(this)
        is Set<*> -> JsonArray(filterIsInstance<String>().map(::JsonPrimitive))
        else -> JsonPrimitive(toString())
    }

    private fun top.yukonga.mishka.domain.model.ConfigPreview.toJsonObject(): JsonObject = buildJsonObject {
        put("activeProfileId", activeProfileId)
        put("activeProfileName", activeProfileName)
        put("activeProfilePresent", activeProfilePresent)
        put("profileType", profileType?.name)
        put("ageEncrypted", ageEncrypted)
        putJsonArray("transformSteps") {
            transformSteps.forEach { step ->
                add(buildJsonObject {
                    put("name", step.name)
                    put("format", step.format.name)
                    put("origin", step.origin.name)
                })
            }
        }
        tailscale?.let { value ->
            putJsonObject("tailscale") {
                put("enabled", value.enabled)
                put("authKeyConfigured", value.authKeyConfigured)
                put("injected", value.injected)
                put("controlUrl", value.controlUrl)
                put("hostname", value.hostname)
                put("exitNode", value.exitNode)
                put("acceptRoutes", value.acceptRoutes)
                put("udp", value.udp)
                put("ephemeral", value.ephemeral)
                put("exitNodeAllowLan", value.exitNodeAllowLan)
            }
        }
        putJsonObject("runtime") {
            put("enabled", runtime.enabled)
            put("tunMode", runtime.tunMode)
            put("mixedPort", runtime.mixedPort)
            put("mode", runtime.mode)
            put("allowLan", runtime.allowLan)
            put("ipv6", runtime.ipv6)
            put("tcpConcurrent", runtime.tcpConcurrent)
            put("findProcessMode", runtime.findProcessMode)
            put("tunEnabled", runtime.tunEnabled)
            put("tunMtu", runtime.tunMtu)
            put("tproxyPort", runtime.tproxyPort)
            put("wifiRuntimeMode", runtime.wifiRuntimeMode)
        }
        put("validation", when (val value = validation) {
            ConfigValidationResult.NotRun -> JsonPrimitive("not_run")
            ConfigValidationResult.Running -> JsonPrimitive("running")
            ConfigValidationResult.Valid -> JsonPrimitive("valid")
            is ConfigValidationResult.Invalid -> buildJsonObject { put("state", "invalid"); put("message", value.message) }
        })
        putJsonObject("diagnostics") {
            put("overrideCount", diagnostics.overrideCount)
            put("selectedOverrideCount", diagnostics.selectedOverrideCount)
            put("tailscaleEnabled", diagnostics.tailscaleEnabled)
            put("tailscaleKeyConfigured", diagnostics.tailscaleKeyConfigured)
            put("rootModeSelected", diagnostics.rootModeSelected)
            put("backStackRestorable", diagnostics.backStackRestorable)
        }
    }

    private companion object {
        val SENSITIVE_KEYS = setOf(
            StorageKeys.TAILSCALE_AUTH_KEY,
            StorageKeys.ROOT_MIHOMO_SECRET,
            StorageKeys.WEBDAV_PASSWORD,
        )
        val BLOCKED_KEYS = setOf(
            StorageKeys.SERVICE_WAS_RUNNING,
            StorageKeys.ROOT_MIHOMO_PID,
            StorageKeys.ROOT_START_TIME,
            StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID,
            StorageKeys.ROOT_BOOT_COUNT,
            StorageKeys.WIFI_POLICY_MATCHED,
            StorageKeys.WIFI_POLICY_MATCHED_ACTION,
            StorageKeys.WIFI_POLICY_PENDING_RESTART,
            StorageKeys.WIFI_POLICY_RUNTIME_MODE,
        )
        val STORAGE_KEY_ALIASES = StorageKeys::class.java.fields
            .filter { it.type == String::class.java }
            .mapNotNull { field ->
                runCatching { field.name.lowercase() to (field.get(null) as? String ?: return@runCatching null) }
                    .getOrNull()
            }
            .toMap()
        val KNOWN_SETTINGS = STORAGE_KEY_ALIASES.values.toSet()
        val COMMANDS = listOf(
            "capabilities", "status", "proxy.start", "proxy.stop", "proxy.restart", "proxy.toggle",
            "profiles.list", "profiles.active", "profiles.use", "profiles.create", "profiles.patch",
            "profiles.apply", "profiles.update", "profiles.update-all", "profiles.release", "profiles.delete",
            "overrides.list", "overrides.get", "overrides.create", "overrides.edit", "overrides.save",
            "overrides.update", "overrides.delete", "overrides.select", "override.get", "override.replace",
            "override.reset", "settings.dump", "settings.get", "settings.set", "boot.status", "boot.set",
            "wifi.status", "wifi.start", "wifi.stop", "wifi.evaluate", "backup.export", "backup.import",
            "backup.webdav-test", "backup.webdav-list", "backup.webdav-upload", "backup.webdav-download",
            "backup.webdav-export-legacy", "backup.clear", "runtime.version",
            "runtime.tailscale", "runtime.config", "runtime.proxies", "runtime.groups", "runtime.select",
            "runtime.unfix", "runtime.delay", "runtime.provider-delay", "runtime.rules", "runtime.connections",
            "runtime.close-all", "runtime.close", "runtime.providers", "runtime.provider-update",
            "runtime.rule-providers", "runtime.rule-provider-update", "runtime.dns", "runtime.fakeip-flush",
            "runtime.dns-flush", "runtime.traffic", "runtime.memory", "runtime.log", "diagnostics.preview",
            "diagnostics.validate",
        )
    }
}
