package top.yukonga.mishka.data.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.domain.model.orderedOverrideIds
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.platform.StorageKeys

@Serializable
private data class TransformSpec(
    val overrides: List<OverrideSpec>,
)

/** 将订阅选择的覆写清单写成内核 --transform 使用的快照。 */
class ProfileTransformWriter(
    private val fileManager: ProfileFileManager,
    private val overrides: OverrideProfileStore,
    private val storage: PlatformStorage,
) {
    private val json = Json { encodeDefaults = true }

    fun write(
        subscription: Subscription?,
        relativePath: String,
        replacement: OverrideReplacement? = null,
    ): String? {
        val target = java.io.File(fileManager.getMihomoWorkDir(), relativePath)
        val tailscaleGenerated = java.io.File(fileManager.getMihomoWorkDir(), TAILSCALE_PATH)
        val tailscaleEnabled = storage.getString(StorageKeys.TAILSCALE_ENABLED, "false") == "true"
        val tailscaleAuthKey = storage.getSecret(StorageKeys.TAILSCALE_AUTH_KEY).trim()
        val specs = buildList {
            addAll(subscription?.orderedOverrideIds?.let { overrides.specs(it, replacement) }.orEmpty())
            if (tailscaleEnabled) {
                check(tailscaleAuthKey.isNotEmpty()) {
                    "Tailscale transform failed: auth key is not configured"
                }
                add(OverrideSpec("Mishka Tailscale", "js", writeTailscaleOverride(tailscaleAuthKey)))
            } else {
                // Auth key changes must not leave a stale secret in app-private files.
                tailscaleGenerated.delete()
            }
        }
        if (specs.isEmpty()) {
            target.delete()
            return null
        }
        fileManager.writeMihomoFile(relativePath, json.encodeToString(TransformSpec.serializer(), TransformSpec(specs)))
        return target.path
    }

    private fun writeTailscaleOverride(authKey: String): String {
        val workDir = fileManager.getMihomoWorkDir()
        val controlUrl = storage.getString(StorageKeys.TAILSCALE_CONTROL_URL, "").trim()
        check(validateControlUrl(controlUrl)) {
            "Tailscale transform failed: control-url must be empty or use https://"
        }
        val content = buildTailscaleScript(
            authKey = authKey,
            controlUrl = controlUrl,
            hostname = storage.getString(StorageKeys.TAILSCALE_HOSTNAME, "").trim(),
            exitNode = storage.getString(StorageKeys.TAILSCALE_EXIT_NODE, "").trim(),
            acceptRoutes = storage.getString(StorageKeys.TAILSCALE_ACCEPT_ROUTES, "true") != "false",
            udp = storage.getString(StorageKeys.TAILSCALE_UDP, "true") != "false",
            ephemeral = storage.getString(StorageKeys.TAILSCALE_EPHEMERAL, "false") == "true",
            allowLan = storage.getString(StorageKeys.TAILSCALE_EXIT_NODE_ALLOW_LAN, "false") == "true",
        )
        fileManager.writeMihomoFile(TAILSCALE_PATH, content)
        return java.io.File(workDir, TAILSCALE_PATH).path
    }

    companion object {
        const val RUNTIME_PATH = "profile.transform.json"
        internal const val TAILSCALE_PATH = "tailscale.generated.js"
        internal const val TAILSCALE_PROXY_NAME = "Mishka Tailscale"

        /**
         * Tailscale 出站脚本。纯字符串拼接，供 JVM 单测直接覆盖。
         *
         * 每次执行都先按名字剔除同名代理再 push，保证改配置 / 重复启用不会留下副本；
         * 两条 Tailnet CIDR 规则 unshift 到最前，避免被用户规则抢先匹配走直连。
         */
        internal fun buildTailscaleScript(
            authKey: String,
            controlUrl: String,
            hostname: String,
            exitNode: String,
            acceptRoutes: Boolean,
            udp: Boolean,
            ephemeral: Boolean,
            allowLan: Boolean,
        ): String {
            val quote = { value: String -> Json.encodeToString(value) }
            val fields = buildString {
                append("name: ").append(quote(TAILSCALE_PROXY_NAME))
                append(", type: ").append(quote("tailscale"))
                append(", \"auth-key\": ").append(quote(authKey))
                if (controlUrl.isNotEmpty()) append(", \"control-url\": ").append(quote(controlUrl))
                if (hostname.isNotEmpty()) append(", hostname: ").append(quote(hostname))
                if (exitNode.isNotEmpty()) {
                    append(", \"exit-node\": ").append(quote(exitNode))
                    append(", \"exit-node-allow-lan-access\": ").append(allowLan)
                }
                append(", \"accept-routes\": ").append(acceptRoutes)
                append(", udp: ").append(udp)
                append(", ephemeral: ").append(ephemeral)
            }
            return """function main(config) {
  const name = ${quote(TAILSCALE_PROXY_NAME)};
  const proxy = {$fields};
  const existing = Array.isArray(config.proxies)
    ? config.proxies.find(p => p && p.name === name)
    : null;
  if (existing && String(existing.type || "").toLowerCase() !== "tailscale") {
    throw new Error("Mishka Tailscale transform failed: proxy name conflicts with an existing proxy");
  }
  config.proxies = Array.isArray(config.proxies) ? config.proxies.filter(p => p && p.name !== name) : [];
  config.proxies.push(proxy);
  config.rules = Array.isArray(config.rules) ? config.rules : [];
  config.rules.unshift(
    `IP-CIDR,100.64.0.0/10,${TAILSCALE_PROXY_NAME},no-resolve`,
    `IP-CIDR,fd7a:115c:a1e0::/48,${TAILSCALE_PROXY_NAME},no-resolve`
  );
  return config;
}
"""
        }


        /**
         * control-url 的前置校验（UI 与 transform 生成共用同一份判定）。
         * 只接受 https 且必须有主机名——mihomo 侧拿到空 host 的 URL 会在启动后才报错，
         * 用户看到的是「已启用但无出站」。空串合法（表示用默认控制服务器）。
         */
        fun validateControlUrl(raw: String): Boolean {
            val value = raw.trim()
            if (value.isEmpty()) return true
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https") return false
            return !uri.host.isNullOrBlank()
        }
    }
}
