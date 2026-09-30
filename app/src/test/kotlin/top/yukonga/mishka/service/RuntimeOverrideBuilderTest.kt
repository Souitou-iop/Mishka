package top.yukonga.mishka.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.DnsOverride
import top.yukonga.mishka.platform.TunMode

/**
 * 运行时 override 的三处关键决策。
 *
 * 这些分支都会静默改变内核监听端口 / DNS 入口：
 * - mixed-port 决定 [SubscriptionProxyResolver] 能否解析到自代理端口；
 * - tproxy-port 决定 ROOT 模式 iptables 重定向是否打到正确端口；
 * - dns.listen 决定 ROOT TPROXY 下系统 DNS 是否被 1053 劫持。
 * 之前只能靠真机日志反推，这里固定住每条优先级。
 */
class RuntimeOverrideBuilderTest {

    // === mixed-port ===

    @Test
    fun `user mixed port wins over everything`() {
        val override = ConfigurationOverride(mixedPort = 7897)
        assertEquals(
            7897,
            RuntimeOverrideBuilder.resolveMixedPort(override, subscriptionUpdateViaProxy = true, subscriptionMixedPort = 7891),
        )
    }

    @Test
    fun `subscription yaml port suppresses injection`() {
        // 订阅自带 mixed-port 时必须返回 null 不注入，否则会盖掉订阅值
        assertNull(
            RuntimeOverrideBuilder.resolveMixedPort(
                ConfigurationOverride(),
                subscriptionUpdateViaProxy = true,
                subscriptionMixedPort = 7891,
            ),
        )
    }

    @Test
    fun `subscription update via proxy injects default fallback`() {
        assertEquals(
            RuntimeOverrideBuilder.DEFAULT_MIXED_PORT,
            RuntimeOverrideBuilder.resolveMixedPort(
                ConfigurationOverride(),
                subscriptionUpdateViaProxy = true,
                subscriptionMixedPort = null,
            ),
        )
    }

    @Test
    fun `no user value and no proxy toggle leaves port untouched`() {
        assertNull(
            RuntimeOverrideBuilder.resolveMixedPort(
                ConfigurationOverride(),
                subscriptionUpdateViaProxy = false,
                subscriptionMixedPort = null,
            ),
        )
    }

    @Test
    fun `user port wins even when subscription yaml has a value`() {
        assertEquals(
            7897,
            RuntimeOverrideBuilder.resolveMixedPort(
                ConfigurationOverride(mixedPort = 7897),
                subscriptionUpdateViaProxy = false,
                subscriptionMixedPort = 7891,
            ),
        )
    }

    // === tproxy-port ===

    @Test
    fun `root tproxy locks the netfilter inbound port`() {
        assertEquals(
            RootTproxyApplier.TPROXY_PORT,
            RuntimeOverrideBuilder.resolveTproxyPort(TunMode.RootTproxy, userTproxyPort = 9999, tproxyForTether = false),
        )
    }

    @Test
    fun `root tproxy ignores tether flag`() {
        assertEquals(
            RootTproxyApplier.TPROXY_PORT,
            RuntimeOverrideBuilder.resolveTproxyPort(TunMode.RootTproxy, userTproxyPort = null, tproxyForTether = true),
        )
    }

    @Test
    fun `root tun uses tether port only when tether proxying is on`() {
        assertEquals(
            RootTetherHijacker.TPROXY_PORT,
            RuntimeOverrideBuilder.resolveTproxyPort(TunMode.RootTun, userTproxyPort = 1234, tproxyForTether = true),
        )
        assertEquals(
            1234,
            RuntimeOverrideBuilder.resolveTproxyPort(TunMode.RootTun, userTproxyPort = 1234, tproxyForTether = false),
        )
    }

    @Test
    fun `vpn passes the user port through unchanged`() {
        assertEquals(
            1234,
            RuntimeOverrideBuilder.resolveTproxyPort(TunMode.Vpn, userTproxyPort = 1234, tproxyForTether = true),
        )
        assertNull(
            RuntimeOverrideBuilder.resolveTproxyPort(TunMode.Vpn, userTproxyPort = null, tproxyForTether = false),
        )
    }

    // === DNS ===

    @Test
    fun `root tproxy forces dns listener on the redirect port`() {
        val dns = RuntimeOverrideBuilder.buildDnsOverride(TunMode.RootTproxy, null)
        assertNotNull(dns)
        assertEquals(true, dns!!.enable)
        assertEquals("0.0.0.0:${RootTproxyApplier.DNS_PORT}", dns.listen)
    }

    @Test
    fun `root tproxy preserves user dns fields while overriding listen`() {
        val userDns = DnsOverride(enable = false, enhancedMode = "fake-ip", nameserver = listOf("https://doh.pub/dns-query"))
        val dns = RuntimeOverrideBuilder.buildDnsOverride(TunMode.RootTproxy, userDns)!!
        assertEquals(true, dns.enable)
        assertEquals("0.0.0.0:${RootTproxyApplier.DNS_PORT}", dns.listen)
        // 用户的解析策略必须保留，只有监听地址被接管
        assertEquals("fake-ip", dns.enhancedMode)
        assertEquals(listOf("https://doh.pub/dns-query"), dns.nameserver)
    }

    @Test
    fun `non tproxy modes leave dns untouched`() {
        val userDns = DnsOverride(enable = true, listen = "127.0.0.1:5353")
        assertEquals(userDns, RuntimeOverrideBuilder.buildDnsOverride(TunMode.Vpn, userDns))
        assertEquals(userDns, RuntimeOverrideBuilder.buildDnsOverride(TunMode.RootTun, userDns))
        assertNull(RuntimeOverrideBuilder.buildDnsOverride(TunMode.Vpn, null))
    }

    // === 常量契约 ===

    @Test
    fun `tproxy and tether ports stay aligned`() {
        // RootTproxyApplier.TPROXY_PORT 直接引用 RootTetherHijacker.TPROXY_PORT，
        // 这里锁住它们相等，避免有人只改一处导致两条 iptables 路径错位
        assertEquals(RootTetherHijacker.TPROXY_PORT, RootTproxyApplier.TPROXY_PORT)
        assertTrue(RuntimeOverrideBuilder.DEFAULT_MIXED_PORT > 0)
    }

    @Test
    fun `dns port does not collide with the tproxy inbound port`() {
        // 两个都是 nat/mangle 重定向目标，撞端口会让 DNS 查询被当普通流量处理
        assertTrue(RootTproxyApplier.DNS_PORT != RootTproxyApplier.TPROXY_PORT)
    }
}
