package top.yukonga.mishka.domain.model

/**
 * 最终生效配置的只读快照。用于「配置诊断」页面展示 transform 链、
 * Tailscale 注入、runtime override 摘要，以及不启动代理的校验结果。
 *
 * 本模型**绝不含** auth key / secret / 订阅正文——诊断摘要必须可安全贴出。
 */
data class ConfigPreview(
    val activeProfileId: String? = null,
    val activeProfileName: String = "",
    val activeProfilePresent: Boolean = false,
    val profileType: ProfileType? = null,
    val ageEncrypted: Boolean = false,
    val transformSteps: List<TransformStep> = emptyList(),
    val tailscale: TailscalePreview? = null,
    val runtime: RuntimeOverridePreview = RuntimeOverridePreview(),
    val validation: ConfigValidationResult = ConfigValidationResult.NotRun,
    val diagnostics: DiagnosticsSummary = DiagnosticsSummary(),
)

/** transform 链的一步：订阅选择的自定义 override 或内置的 Tailscale 生成脚本。 */
data class TransformStep(
    val name: String,
    val format: OverrideFormat,
    val origin: TransformOrigin,
)

enum class TransformOrigin { SubscriptionOverride, Tailscale }

/** Tailscale 注入摘要，不含 auth key（只暴露是否已配置）。 */
data class TailscalePreview(
    val enabled: Boolean = false,
    val authKeyConfigured: Boolean = false,
    val injected: Boolean = false,
    val controlUrl: String = "",
    val hostname: String = "",
    val exitNode: String = "",
    val acceptRoutes: Boolean = true,
    val udp: Boolean = true,
    val ephemeral: Boolean = false,
    val exitNodeAllowLan: Boolean = false,
)

/** runtime override 摘要，只保留对排查有用的键值，不含 secret / external-controller。 */
data class RuntimeOverridePreview(
    val enabled: Boolean = false,
    val tunMode: String = "",
    val mixedPort: Int? = null,
    val mode: String? = null,
    val allowLan: Boolean? = null,
    val ipv6: Boolean? = null,
    val tcpConcurrent: Boolean? = null,
    val findProcessMode: String? = null,
    val tunEnabled: Boolean? = null,
    val tunMtu: Int? = null,
    val tproxyPort: Int? = null,
    val wifiRuntimeMode: String? = null,
)

sealed interface ConfigValidationResult {
    data object NotRun : ConfigValidationResult

    data object Running : ConfigValidationResult

    data object Valid : ConfigValidationResult

    data class Invalid(val message: String) : ConfigValidationResult
}

/**
 * 脱敏诊断摘要：只含聚合计数与布尔，绝不含密钥、订阅 URL、订阅正文。
 */
data class DiagnosticsSummary(
    val overrideCount: Int = 0,
    val selectedOverrideCount: Int = 0,
    val tailscaleEnabled: Boolean = false,
    val tailscaleKeyConfigured: Boolean = false,
    val rootModeSelected: Boolean = false,
    val backStackRestorable: Boolean = true,
)
