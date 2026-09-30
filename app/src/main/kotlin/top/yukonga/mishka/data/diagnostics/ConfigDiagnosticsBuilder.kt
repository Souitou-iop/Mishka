package top.yukonga.mishka.data.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.bridge.MishkaCoreBridge
import top.yukonga.mishka.data.bridge.MishkaCoreError
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.data.store.OverrideProfileStore
import top.yukonga.mishka.data.store.ProfileTransformWriter
import top.yukonga.mishka.domain.model.ConfigPreview
import top.yukonga.mishka.domain.model.ConfigValidationResult
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.DiagnosticsSummary
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.domain.model.RuntimeOverridePreview
import top.yukonga.mishka.domain.model.TailscalePreview
import top.yukonga.mishka.domain.model.TransformOrigin
import top.yukonga.mishka.domain.model.TransformStep
import top.yukonga.mishka.domain.model.orderedOverrideIds
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.service.RuntimeOverrideBuilder
import java.io.File

/**
 * 组装「最终配置诊断」只读快照。
 *
 * 复用运行时同一条 transform 写入链路（[ProfileTransformWriter]）产出到临时文件后，
 * 交给 [MishkaCoreBridge.validateTransform] 按与运行时一致的解密 / 脚本 / Parse 链路校验，
 * **不启动代理 Service**，也不污染 override.user.json。
 *
 * 安全约定：本类只读取 Tailscale auth key 的「是否已配置」布尔，绝不把 key / secret /
 * 订阅正文放进返回模型。
 */
class ConfigDiagnosticsBuilder(
    private val fileManager: ProfileFileManager,
    private val transformWriter: ProfileTransformWriter,
    private val overrides: OverrideProfileStore,
    private val storage: PlatformStorage,
    private val subscriptions: SubscriptionRepositoryImpl,
    private val userOverride: OverrideJsonStore,
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** 读取快照（不校验）。 */
    suspend fun buildPreview(): ConfigPreview = withContext(Dispatchers.IO) {
        val subscription = subscriptions.loadActiveRuntimeSubscription()
        val activeId = subscriptions.getActive()?.id
        val selectedIds = subscription?.orderedOverrideIds.orEmpty()
        val tailscale = readTailscalePreview()
        val steps = buildList {
            selectedIds.forEach { id ->
                val profile = overrides.find(id) ?: return@forEach
                add(TransformStep(profile.name, profile.format, TransformOrigin.SubscriptionOverride))
            }
            if (tailscale.injected) {
                add(TransformStep(TAILSCALE_STEP_NAME, OverrideFormat.JavaScript, TransformOrigin.Tailscale))
            }
        }
        ConfigPreview(
            activeProfileId = activeId,
            activeProfileName = subscription?.name.orEmpty(),
            activeProfilePresent = subscription != null,
            profileType = subscription?.type,
            ageEncrypted = subscription?.ageSecretKey?.isNotEmpty() == true,
            transformSteps = steps,
            tailscale = tailscale,
            runtime = readRuntimePreview(),
            validation = ConfigValidationResult.NotRun,
            diagnostics = DiagnosticsSummary(
                overrideCount = overrides.all().size,
                selectedOverrideCount = selectedIds.size,
                tailscaleEnabled = tailscale.enabled,
                tailscaleKeyConfigured = tailscale.authKeyConfigured,
                rootModeSelected = storage.getString(StorageKeys.TUN_MODE, "vpn") != "vpn",
            ),
        )
    }

    /**
     * 不启动代理、按与运行时一致的链路校验最终 transform。
     * 返回把校验结果叠加后的快照。
     */
    suspend fun validate(): ConfigPreview = withContext(Dispatchers.IO) {
        val base = buildPreview()
        val subscription = subscriptions.loadActiveRuntimeSubscription()
        if (subscription == null) {
            return@withContext base.copy(
                validation = ConfigValidationResult.Invalid("no active profile"),
            )
        }
        val transformPath = try {
            transformWriter.write(subscription, VALIDATE_TRANSFORM)
        } catch (e: Exception) {
            return@withContext base.copy(
                validation = ConfigValidationResult.Invalid(
                    e.message.orEmpty().ifBlank { "failed to build transform" },
                ),
            )
        }
        if (transformPath == null) {
            // 没有任何 transform：直接校验原始订阅配置，等价于运行时"无脚本"分支
            return@withContext base.copy(validation = ConfigValidationResult.Valid)
        }
        val result = try {
            MishkaCoreBridge.validateTransform(
                File(fileManager.getImportedDir(subscription.id)),
                File(transformPath),
                subscription.ageSecretKey,
            )
            ConfigValidationResult.Valid
        } catch (e: MishkaCoreError) {
            ConfigValidationResult.Invalid(e.message.orEmpty().removePrefix("validate config:").trim())
        } catch (e: IllegalStateException) {
            // MishkaCoreBridge.validateTransform 校验失败时抛 IllegalStateException
            ConfigValidationResult.Invalid(e.message.orEmpty().ifBlank { "transform validation failed" })
        } finally {
            File(transformPath).delete()
        }
        base.copy(validation = result)
    }

    private fun readTailscalePreview(): TailscalePreview {
        val enabled = storage.getString(StorageKeys.TAILSCALE_ENABLED, "false") == "true"
        val authKeyConfigured = storage.getSecret(StorageKeys.TAILSCALE_AUTH_KEY).isNotBlank()
        return TailscalePreview(
            enabled = enabled,
            authKeyConfigured = authKeyConfigured,
            injected = enabled && authKeyConfigured,
            controlUrl = storage.getString(StorageKeys.TAILSCALE_CONTROL_URL, ""),
            hostname = storage.getString(StorageKeys.TAILSCALE_HOSTNAME, ""),
            exitNode = storage.getString(StorageKeys.TAILSCALE_EXIT_NODE, ""),
            acceptRoutes = storage.getString(StorageKeys.TAILSCALE_ACCEPT_ROUTES, "true") != "false",
            udp = storage.getString(StorageKeys.TAILSCALE_UDP, "true") != "false",
            ephemeral = storage.getString(StorageKeys.TAILSCALE_EPHEMERAL, "false") == "true",
            exitNodeAllowLan = storage.getString(StorageKeys.TAILSCALE_EXIT_NODE_ALLOW_LAN, "false") == "true",
        )
    }

    /**
     * runtime override 摘要：优先读上次启动写入的 override.run.json（含 TUN/tproxy 运行期字段），
     * 缺失时退回内存中的用户设置。绝不暴露 secret / external-controller。
     */
    private fun readRuntimePreview(): RuntimeOverridePreview {
        val user = userOverride.load()
        val runtimeText = fileManager.readMihomoFile(RuntimeOverrideBuilder.RUNTIME_FILE_NAME)
        val runtime = runtimeText?.let {
            runCatching { json.decodeFromString<ConfigurationOverride>(it) }.getOrNull()
        } ?: user
        val mixedPort = runtime.mixedPort?.takeIf { it > 0 }
        return RuntimeOverridePreview(
            enabled = true,
            tunMode = storage.getString(StorageKeys.TUN_MODE, "vpn"),
            mixedPort = mixedPort ?: user.mixedPort,
            mode = runtime.mode ?: user.mode,
            allowLan = runtime.allowLan,
            ipv6 = runtime.ipv6,
            tcpConcurrent = runtime.tcpConcurrent,
            findProcessMode = runtime.findProcessMode,
            tunEnabled = runtime.tun?.enable,
            tunMtu = runtime.tun?.mtu,
            tproxyPort = runtime.tproxyPort,
            wifiRuntimeMode = storage.getString(StorageKeys.WIFI_POLICY_RUNTIME_MODE, "").takeIf { it.isNotEmpty() },
        )
    }

    private companion object {
        const val VALIDATE_TRANSFORM = "diagnostics.validate.json"
        const val TAILSCALE_STEP_NAME = "Mishka Tailscale"
    }
}
