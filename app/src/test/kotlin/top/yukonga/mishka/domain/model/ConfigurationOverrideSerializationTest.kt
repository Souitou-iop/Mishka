package top.yukonga.mishka.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.mishka.data.store.ProfileTransformWriter

/**
 * override 序列化契约 + control-url 校验。
 *
 * 这两块是「用户设置没生效」的高发区：
 * - null 字段一旦被序列化成 `"mixed-port": null`，mihomo 会把端口显式置空；
 * - control-url 校验放行非法值，用户看到「已启用」但内核启动后才失败。
 */
class ConfigurationOverrideSerializationTest {

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    // === 序列化 ===

    @Test
    fun `null fields are omitted from json`() {
        val encoded = json.encodeToString(ConfigurationOverride())
        assertEquals("{}", encoded)
    }

    @Test
    fun `only explicitly set fields are emitted`() {
        val encoded = json.encodeToString(ConfigurationOverride(mixedPort = 7890, mode = "rule"))
        assertTrue(encoded.contains(""""mixed-port":7890"""))
        assertTrue(encoded.contains(""""mode":"rule""""))
        assertFalse(encoded.contains("http-port"))
        assertFalse(encoded.contains("allow-lan"))
    }

    @Test
    fun `nested tun override uses kebab-case mihomo tags`() {
        val encoded = json.encodeToString(
            ConfigurationOverride(tun = TunOverride(enable = true, autoRoute = true, gsoMaxSize = 65535)),
        )
        assertTrue(encoded.contains(""""auto-route":true"""))
        assertTrue(encoded.contains(""""gso-max-size":65535"""))
    }

    @Test
    fun `explicit false is emitted rather than dropped`() {
        // encodeDefaults=false 只砍默认值，显式 false 必须留下，否则清不掉内核的旧值
        val encoded = json.encodeToString(ConfigurationOverride(allowLan = false, tcpConcurrent = false))
        assertTrue(encoded.contains(""""allow-lan":false"""))
        assertTrue(encoded.contains(""""tcp-concurrent":false"""))
    }

    @Test
    fun `false values survive a round trip`() {
        val original = ConfigurationOverride(ipv6 = false, dns = DnsOverride(enable = false))
        val decoded = json.decodeFromString<ConfigurationOverride>(json.encodeToString(original))
        assertEquals(false, decoded.ipv6)
        assertEquals(false, decoded.dns?.enable)
    }

    // === copy 语义（全树只读不变式）===

    @Test
    fun `copy changes one field without touching the rest`() {
        val base = ConfigurationOverride(mixedPort = 7890, mode = "rule", secret = "old")
        val next = base.copy(mode = "global")
        assertEquals(7890, next.mixedPort)
        assertEquals("global", next.mode)
        // 原实例必须保持不动：它可能仍被 StateFlow 作为当前值持有
        assertEquals("rule", base.mode)
    }

    @Test
    fun `distinct copies are not equal so stateflow dedup does not swallow updates`() {
        val base = ConfigurationOverride(mode = "rule")
        assertFalse(base == base.copy(mode = "direct"))
        assertTrue(base == base.copy(mode = "rule"))
    }

    // === 解析辅助 ===

    @Test
    fun `external controller trims and rewrites wildcard bind`() {
        assertEquals("127.0.0.1:9090", ConfigurationOverride().resolveExternalController())
        assertEquals("127.0.0.1:9090", ConfigurationOverride(externalController = "  ").resolveExternalController())
        assertEquals("127.0.0.1:9090", ConfigurationOverride(externalController = "0.0.0.0:9090").resolveExternalController())
        assertEquals("127.0.0.1:9999", ConfigurationOverride(externalController = "0.0.0.0:9999").resolveExternalController())
    }

    @Test
    fun `secret resolves to null when blank and trims otherwise`() {
        assertNull(ConfigurationOverride().resolveSecretOrNull())
        assertNull(ConfigurationOverride(secret = "   ").resolveSecretOrNull())
        assertEquals("abc", ConfigurationOverride(secret = "  abc  ").resolveSecretOrNull())
    }

    // === control-url 校验 ===

    @Test
    fun `blank control url is valid and means default server`() {
        assertTrue(ProfileTransformWriter.validateControlUrl(""))
        assertTrue(ProfileTransformWriter.validateControlUrl("   "))
    }

    @Test
    fun `https control urls with a host are valid`() {
        assertTrue(ProfileTransformWriter.validateControlUrl("https://controlplane.tailscale.com"))
        assertTrue(ProfileTransformWriter.validateControlUrl("  https://example.com/path  "))
    }

    @Test
    fun `http control urls are rejected`() {
        assertFalse(ProfileTransformWriter.validateControlUrl("http://127.0.0.1:8080"))
    }

    @Test
    fun `non http schemes are rejected`() {
        assertFalse(ProfileTransformWriter.validateControlUrl("ftp://example.com"))
        assertFalse(ProfileTransformWriter.validateControlUrl("file:///etc/passwd"))
        assertFalse(ProfileTransformWriter.validateControlUrl("socks5://example.com"))
    }

    @Test
    fun `urls without a host are rejected`() {
        // 之前放行会让内核拿到空 host 的 control-url，表现为「已启用但无出站」
        assertFalse(ProfileTransformWriter.validateControlUrl("https://"))
        assertFalse(ProfileTransformWriter.validateControlUrl("http:///path"))
        assertFalse(ProfileTransformWriter.validateControlUrl("not-a-url"))
    }

    @Test
    fun `malformed urls do not throw`() {
        // 校验入口必须容错，不能把 URI 解析异常抛给 UI
        assertFalse(ProfileTransformWriter.validateControlUrl("http://[::1"))
        assertFalse(ProfileTransformWriter.validateControlUrl("https:// space.com"))
    }
}
