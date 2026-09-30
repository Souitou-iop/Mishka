package top.yukonga.mishka.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tailscale 出站脚本生成。
 *
 * 脚本内容直接决定内核能否建出 Tailscale 出站与 Tailnet 分流规则，是「启用后没出站」这类
 * 静默故障的第一现场，因此按字面断言而不是只查非空。
 */
class ProfileTransformWriterTest {

    private fun script(
        authKey: String = "tskey-auth-abc",
        controlUrl: String = "",
        hostname: String = "",
        exitNode: String = "",
        acceptRoutes: Boolean = true,
        udp: Boolean = true,
        ephemeral: Boolean = false,
        allowLan: Boolean = false,
    ) = ProfileTransformWriter.buildTailscaleScript(
        authKey, controlUrl, hostname, exitNode, acceptRoutes, udp, ephemeral, allowLan,
    )

    @Test
    fun `emits tailscale proxy with auth key and type`() {
        val out = script()
        assertTrue(out.contains("""name: "${ProfileTransformWriter.TAILSCALE_PROXY_NAME}""""))
        assertTrue(out.contains("""type: "tailscale""""))
        assertTrue(out.contains(""""auth-key": "tskey-auth-abc""""))
    }

    @Test
    fun `auth key is injected verbatim and never truncated`() {
        val key = "tskey-auth-k1234567890CNTRL-0123456789abcdefghijklmnop"
        assertTrue(script(authKey = key).contains(key))
    }

    @Test
    fun `deduplicates same-named proxy before push`() {
        val out = script()
        assertTrue(
            "must filter existing proxy by name to avoid duplicates",
            out.contains("p.name !== name"),
        )
        // filter 必须在 push 之前，否则会出现两份同名代理
        assertTrue(out.indexOf("filter") < out.indexOf("push"))
    }

    @Test
    fun `unshifts both tailnet cidr rules ahead of user rules`() {
        val out = script()
        assertTrue(out.contains("IP-CIDR,100.64.0.0/10,${ProfileTransformWriter.TAILSCALE_PROXY_NAME},no-resolve"))
        assertTrue(out.contains("IP-CIDR,fd7a:115c:a1e0::/48,${ProfileTransformWriter.TAILSCALE_PROXY_NAME},no-resolve"))
        // unshift 而非 push：Tailnet 流量必须先于用户规则被判定
        assertTrue(out.contains("config.rules.unshift("))
    }

    @Test
    fun `optional fields are omitted when blank`() {
        val out = script(controlUrl = "", hostname = "", exitNode = "")
        assertFalse(out.contains("control-url"))
        assertFalse(out.contains("hostname"))
        assertFalse(out.contains("exit-node"))
    }

    @Test
    fun `optional fields are emitted when set`() {
        val out = script(
            controlUrl = "https://controlplane.tailscale.com",
            hostname = "mishka-phone",
            exitNode = "100.64.0.9",
        )
        assertTrue(out.contains(""""control-url": "https://controlplane.tailscale.com""""))
        assertTrue(out.contains("hostname: \"mishka-phone\""))
        assertTrue(out.contains(""""exit-node": "100.64.0.9"""))
    }

    @Test
    fun `allow lan only appears alongside exit node`() {
        val withoutExitNode = script(exitNode = "", allowLan = true)
        assertFalse(withoutExitNode.contains("exit-node-allow-lan-access"))

        val withExitNode = script(exitNode = "100.64.0.9", allowLan = true)
        assertTrue(withExitNode.contains(""""exit-node-allow-lan-access": true"""))
    }

    @Test
    fun `boolean flags follow their values`() {
        val allOff = script(acceptRoutes = false, udp = false, ephemeral = false)
        assertTrue(allOff.contains(""""accept-routes": false"""))
        assertTrue(allOff.contains("udp: false"))
        assertTrue(allOff.contains("ephemeral: false"))

        val ephemeralOn = script(ephemeral = true)
        assertTrue(ephemeralOn.contains("ephemeral: true"))
    }

    @Test
    fun `values containing quotes stay syntactically valid JS`() {
        val out = script(hostname = """a"b""")
        // Json.encodeToString 必须转义内层引号，否则生成非法 JS
        assertTrue(out.contains("hostname: \"a\\\"b\""))
    }

    @Test
    fun `script is a complete main function`() {
        val out = script()
        assertTrue(out.startsWith("function main(config)"))
        assertTrue(out.trimEnd().endsWith("}"))
        assertTrue(out.contains("return config;"))
    }

    @Test
    fun `proxy literal is comma-separated for every field combination`() {
        // 字段拼接靠手写逗号，缺一个逗号就会生成 `{a: 1b: 2}` 这类非法 JS 对象字面量。
        // 校验每个 "键: 值" 之间确实有逗号或该键是首键。
        val combos = listOf(
            script(controlUrl = "", hostname = "", exitNode = ""),
            script(controlUrl = "https://x.com", hostname = "h", exitNode = "100.64.0.9"),
            script(controlUrl = "https://x.com", hostname = "", exitNode = "100.64.0.9"),
            script(controlUrl = "", hostname = "h", exitNode = ""),
        )
        combos.forEach { out ->
            val literal = Regex("""const proxy = \{(.*)};""").find(out)?.groupValues?.get(1)
                ?: error("proxy literal not found in:\n$out")
            assertFalse("key/value must be comma separated: $literal", Regex("""["a-z][^,]*?\s+[a-z]+:""").containsMatchIn(literal))
            assertFalse("trailing comma before }: $literal", literal.trimEnd().endsWith(","))
            assertTrue("must start with a name key: $literal", literal.trimStart().startsWith("name:"))
            assertTrue("must include type tailscale: $literal", literal.contains("type: "))
        }
    }
}
