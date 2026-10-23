package top.yukonga.mishka.service

import android.content.Context
import kotlinx.serialization.json.Json
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.DnsOverride
import top.yukonga.mishka.domain.model.ProfileOverride
import top.yukonga.mishka.domain.model.TunOverride
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.viewmodel.AppProxyMode
import java.io.File

/**
 * 运行时 override 组装：用户持久化的 ConfigurationOverride 叠加 TUN fd / rootMode / AppProxy
 * 等运行期字段，输出到 `files/mihomo/override.run.json`。
 *
 * mihomo 启动通过 `--override-json <path>` 参数读该文件，
 * 在 yaml.Unmarshal 之后、ParseRawConfig 之前 `json.NewDecoder().Decode(rawCfg)` 注入。
 */
object RuntimeOverrideBuilder {

    internal const val RUNTIME_FILE_NAME = "override.run.json"
    internal const val DEFAULT_TUN_DEVICE = "Mishka"

    // VPN/ROOT TUN 共用 MTU。VpnService.Builder.setMtu 与 sing-tun cfg.Tun.MTU 必须同值：
    // sing-tun 在 fd 模式用 cfg.Tun.MTU 给 gvisor fdbased.New 设 endpoint 缓冲，0 时所有 read 失败。
    internal const val VPN_TUN_MTU = 9000
    internal const val VPN_GATEWAY = "172.19.0.1"
    internal const val VPN_GATEWAY6 = "fdfe:dcba:9876::1"
    internal const val VPN_PREFIX = 30
    internal const val VPN_PREFIX6 = 126
    internal const val VPN_DNS = "172.19.0.2"
    internal const val VPN_DNS6 = "fdfe:dcba:9876::2"

    // ROOT TUN 模式 sing-tun 路由常量，与 RootTetherHijacker 对齐
    // sing-tun 默认值也是 2022 / 9000，此处显式注入避免上游默认值漂移
    internal const val ROOT_TUN_TABLE = 2022
    internal const val ROOT_TUN_RULE_INDEX = 9000

    // 「通过代理更新订阅」开启且用户未显式配置 mixed-port 时的兜底默认值，
    // 确保 mihomo 一定监听 HTTP 代理端口，让 SubscriptionProxyResolver 稳定解析到
    internal const val DEFAULT_MIXED_PORT = 7890

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * 生成运行时 override 并写入 override.run.json，返回该文件绝对路径。
     *
     * secret / external-controller **不**写入 JSON —— 通过 mihomo `--secret` / `--ext-ctl`
     * CLI flag 传入（见 MihomoRunner）。profile 段固定禁用 store-selected（由 SelectionEntity 管理），
     * 启用 store-fake-ip（保留 fake-ip 缓存）。
     *
     * 按 [tunMode] 分支注入策略：
     * - [TunMode.Vpn]        VpnService + sing-tun fd，仅写 `tun.file-descriptor`
     * - [TunMode.RootTun]    sing-tun auto_route + include/exclude-package；可选为 tether 加 tproxy-port
     * - [TunMode.RootTproxy] 关闭 TUN，写 tproxy-port + dns.listen，AppProxy 交 iptables uid-owner
     *
     * mixed-port 决策（决定 mihomo 是否监听 HTTP 代理端口，[SubscriptionProxyResolver] 据此走代理）：
     * 1. 用户 override 显式设置 → 用用户值（覆盖订阅 yaml）
     * 2. 无订阅变换且原 YAML 自带 `mixed-port` → 不注入，mihomo 沿用该值
     * 3. [subscriptionUpdateViaProxy] 启用 → 注入 [DEFAULT_MIXED_PORT] 兜底
     * 4. 有订阅变换时，此处的默认值只是兜底；runtime 在 `hub.Parse` 前按同一次脚本执行结果
     *    用 Option 恢复显式 `mixed-port`，避免默认值盖掉脚本设置。
     */
    fun buildAndWriteForRun(
        context: Context,
        userOverride: ConfigurationOverride,
        tunFd: Int,
        tunMode: TunMode,
        subscriptionUpdateViaProxy: Boolean,
        subscriptionMixedPort: Int?,
        tproxyForTether: Boolean = false,
    ): File {
        val storage = PlatformStorage(context)
        val wifiRuntimeMode = storage.getString(StorageKeys.WIFI_POLICY_RUNTIME_MODE, "")
            .takeIf { it == "rule" || it == "global" || it == "direct" }
        val merged = userOverride.copy(
            externalController = null,
            secret = null,
            mode = wifiRuntimeMode ?: userOverride.mode,
            mixedPort = resolveMixedPort(userOverride, subscriptionUpdateViaProxy, subscriptionMixedPort),
            tproxyPort = resolveTproxyPort(tunMode, userOverride.tproxyPort, tproxyForTether),
            // RootTproxy 下**不**注入 routing-mark：Android Netd 用 fwmark 低 16 位编码 netId，
            // mihomo 若带 SO_MARK 会被解释为不存在的 netId，命中 legacy_system 表（无默认路由）
            // → mihomo 出站全部 `network unreachable`。iptables 改用 `-m owner --uid-owner 0`
            // 放行 mihomo（它以 root 运行），副作用是所有 root 进程都不经 TPROXY，但用户 app 正常代理
            routingMark = if (tunMode == TunMode.Vpn) 0 else userOverride.routingMark,
            // VPN 的按包名规则经平台 UID 查询；不能用分应用接管名单替代规则匹配。
            // ROOT 保持既有的默认注入，VPN 未显式覆写时沿用订阅/核心值。
            tcpConcurrent = if (tunMode == TunMode.Vpn) userOverride.tcpConcurrent else userOverride.tcpConcurrent ?: true,
            findProcessMode = if (tunMode == TunMode.Vpn) userOverride.findProcessMode else userOverride.findProcessMode ?: "off",
            dns = buildDnsOverride(
                tunMode, userOverride.dns,
                storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true",
            ),
            tun = buildTunOverride(context, tunMode, tunFd, userOverride.tun),
            profile = ProfileOverride(storeSelected = false, storeFakeIp = true),
        )
        // 原子写：mihomo 紧接着就以 --override-json 读它，半个 JSON 会让启动失败且难以定位
        val file = File(ConfigGenerator.getWorkDir(context), RUNTIME_FILE_NAME)
        ProfileFileOps.writeAtomically(file, json.encodeToString(merged))
        return file
    }

    /**
     * 本次运行是否注入国内直连规则。注入本身在 mihomo 侧（`--domestic-direct`）执行：
     * RawConfig.Rule 是整体替换语义，在这里写 `rules` 会把订阅自带规则全部丢掉，
     * 必须在内核解析阶段对原始规则做前插。
     *
     * 只有 rule 模式才注入：global 全部走选中出口、direct 全部直连，两者都不存在分流问题。
     * 这两个模式同时就是用户关掉该行为的开关。
     */
    internal fun shouldInjectDomesticDirect(
        domesticDirectEnabled: Boolean,
        userOverride: ConfigurationOverride,
        wifiRuntimeMode: String?,
    ): Boolean {
        if (!domesticDirectEnabled) return false
        return (wifiRuntimeMode ?: userOverride.mode ?: "rule").equals("rule", ignoreCase = true)
    }

    /**
     * 该 mixed-port 是否由本 builder 作为兜底注入（而非用户 / 订阅显式设置）。
     * 诊断页据此把「兜底默认值」与「用户选定值」区分开。
     */
    internal fun isRuntimeMixedPort(port: Int): Boolean = port == DEFAULT_MIXED_PORT

    /**
     * mixed-port 决策（见 [buildAndWriteForRun] 文档）：用户显式值 > 订阅 yaml 自带（返回 null 不注入）
     * > 通过代理更新订阅的兜底默认值 > 不注入。
     */
    internal fun resolveMixedPort(
        userOverride: ConfigurationOverride,
        subscriptionUpdateViaProxy: Boolean,
        subscriptionMixedPort: Int?,
    ): Int? = when {
        userOverride.mixedPort != null -> userOverride.mixedPort
        subscriptionMixedPort != null -> null
        subscriptionUpdateViaProxy -> DEFAULT_MIXED_PORT
        else -> null
    }

    /**
     * tproxy-port 决策：RootTproxy 锁定内核入站端口；RootTun 仅在 tether PROXY 路径占用该端口，
     * 否则透传用户值；Vpn 完全不改写。
     */
    internal fun resolveTproxyPort(
        tunMode: TunMode,
        userTproxyPort: Int?,
        tproxyForTether: Boolean,
    ): Int? = when (tunMode) {
        TunMode.RootTproxy -> RootTproxyApplier.TPROXY_PORT
        TunMode.RootTun -> if (tproxyForTether) RootTetherHijacker.TPROXY_PORT else userTproxyPort
        TunMode.Vpn -> userTproxyPort
    }

    // 系统缓存跨应用共享，ROOT 的真实地址映射由 runtime Option 在解析后强制设置。
    // 不能在解析前清掉 fake-IP 模式，否则旧 VPN DNS 答案所需的反查池不会被加载。
    internal fun buildDnsOverride(tunMode: TunMode, userDns: DnsOverride?, ipv6Enabled: Boolean): DnsOverride? {
        if (tunMode == TunMode.Vpn) return userDns
        val base = userDns ?: DnsOverride()
        return base.copy(
            listen = "",
            ipv6 = ipv6Enabled,
        )
    }

    private fun buildTunOverride(
        context: Context,
        tunMode: TunMode,
        tunFd: Int,
        userTun: TunOverride?,
    ): TunOverride {
        // RootTproxy：TUN 完全关闭，sing-tun 不初始化
        if (tunMode == TunMode.RootTproxy) {
            return TunOverride(enable = false)
        }

        val storage = PlatformStorage(context)
        if (tunMode == TunMode.Vpn) {
            val ipv6 = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
            val hijack = storage.getString(StorageKeys.VPN_DNS_HIJACKING, "true") == "true"
            return TunOverride(
                enable = true,
                stack = userTun?.stack,
                fileDescriptor = tunFd,
                autoRoute = false,
                autoDetectInterface = false,
                inet6Address = if (ipv6) listOf("$VPN_GATEWAY6/$VPN_PREFIX6") else emptyList(),
                dnsHijack = buildList {
                    add(if (hijack) "0.0.0.0:53" else "$VPN_DNS:53")
                    if (ipv6) add(if (hijack) "[::]:53" else "[$VPN_DNS6]:53")
                },
                mtu = VPN_TUN_MTU,
            )
        }
        val selfPkg = context.packageName
        val proxyMode = parseAppProxyMode(storage.getString(StorageKeys.APP_PROXY_MODE, AppProxyMode.AllowAll.name))
        val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())
        val include: List<String>?
        val exclude: List<String>?
        when (proxyMode) {
            AppProxyMode.AllowSelected -> {
                val filtered = packages.filter { it != selfPkg }
                include = if (filtered.isNotEmpty()) filtered else listOf("-")
                exclude = null
            }

            AppProxyMode.DenySelected -> {
                include = null
                exclude = (packages + selfPkg).distinct()
            }

            AppProxyMode.AllowAll -> {
                include = null
                exclude = listOf(selfPkg)
            }
        }

        val ipv6Enabled = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
        val inet6 = if (ipv6Enabled) listOf("fdfe:dcba:9876::1/126") else emptyList()

        // ROOT TUN 的 auto_route 缺省铺满 0.0.0.0/0，会把 LAN 单播 + 224/4 组播吸进 mihomo，
        // 破坏同 LAN 设备发现 / P2P 直连（如妙享桌面）。注入私网 + 组播 route-exclude 留在物理网卡，
        // 与 VPN / TPROXY 的 LAN 放行对齐；仅 ROOT TUN 需要，用户显式设置优先。
        val routeExclude = userTun?.routeExcludeAddress ?: buildList {
            addAll(IptablesIntranet.V4)
            if (ipv6Enabled) addAll(IptablesIntranet.V6)
        }

        val device = userTun?.device ?: storage.getString(StorageKeys.ROOT_TUN_DEVICE, DEFAULT_TUN_DEVICE)

        // ROOT 保留用户显式 MTU/GSO 与 jumbo 开关；VPN 固定走上方的 Builder 同源参数。
        val jumbo = storage.getString(StorageKeys.ROOT_TUN_JUMBO_MTU, "true") == "true"
        val defaultMtu = if (jumbo) VPN_TUN_MTU else 1500

        return TunOverride(
            enable = true,
            device = device,
            stack = userTun?.stack,
            autoRoute = true,
            autoDetectInterface = true,
            routeExcludeAddress = routeExclude,
            inet6Address = inet6,
            dnsHijack = if (ipv6Enabled) listOf("0.0.0.0:53", "[::]:53") else listOf("0.0.0.0:53"),
            includePackage = include,
            excludePackage = exclude,
            iproute2TableIndex = ROOT_TUN_TABLE,
            iproute2RuleIndex = ROOT_TUN_RULE_INDEX,
            mtu = userTun?.mtu ?: defaultMtu,
            gso = userTun?.gso ?: jumbo,
            gsoMaxSize = userTun?.gsoMaxSize ?: if (jumbo) 65535 else null,
        )
    }

    private fun parseAppProxyMode(name: String): AppProxyMode =
        runCatching { AppProxyMode.valueOf(name) }.getOrDefault(AppProxyMode.AllowAll)
}
