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
        val tailscaleAuthKey = storage.getString(StorageKeys.TAILSCALE_AUTH_KEY, "").trim()
        val specs = buildList {
            addAll(subscription?.orderedOverrideIds?.let { overrides.specs(it, replacement) }.orEmpty())
            if (tailscaleEnabled && tailscaleAuthKey.isNotEmpty()) {
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
        val relativePath = TAILSCALE_PATH
        val proxyName = "Mishka Tailscale"
        val quote = { value: String -> Json.encodeToString(value) }
        val controlUrl = storage.getString(StorageKeys.TAILSCALE_CONTROL_URL, "").trim()
        val hostname = storage.getString(StorageKeys.TAILSCALE_HOSTNAME, "").trim()
        val exitNode = storage.getString(StorageKeys.TAILSCALE_EXIT_NODE, "").trim()
        val acceptRoutes = storage.getString(StorageKeys.TAILSCALE_ACCEPT_ROUTES, "true") != "false"
        val udp = storage.getString(StorageKeys.TAILSCALE_UDP, "true") != "false"
        val ephemeral = storage.getString(StorageKeys.TAILSCALE_EPHEMERAL, "false") == "true"
        val allowLan = storage.getString(StorageKeys.TAILSCALE_EXIT_NODE_ALLOW_LAN, "false") == "true"
        val fields = buildString {
            append("name: ").append(quote(proxyName))
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
        val content = """function main(config) {
  const name = ${quote(proxyName)};
  const proxy = {$fields};
  config.proxies = Array.isArray(config.proxies) ? config.proxies.filter(p => p.name !== name) : [];
  config.proxies.push(proxy);
  config.rules = Array.isArray(config.rules) ? config.rules : [];
  config.rules.unshift(
    `IP-CIDR,100.64.0.0/10,${proxyName},no-resolve`,
    `IP-CIDR,fd7a:115c:a1e0::/48,${proxyName},no-resolve`
  );
  return config;
}
"""
        fileManager.writeMihomoFile(relativePath, content)
        return java.io.File(workDir, relativePath).path
    }

    companion object {
        const val RUNTIME_PATH = "profile.transform.json"
        private const val TAILSCALE_PATH = "tailscale.generated.js"
    }
}
