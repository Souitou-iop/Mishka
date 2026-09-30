package top.yukonga.mishka.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

object StorageKeys {
    // 服务状态
    const val SERVICE_WAS_RUNNING = "service_was_running"
    const val TUN_MODE = "tun_mode"
    const val HAS_ROOT = "has_root"

    // 打开应用时自动连接。与开机自启（BootStartManager 的组件位）相互独立：后者只在关机前
    // 代理在运行时恢复，这个不看上次状态，只要有可用订阅就启动
    const val AUTO_CONNECT_ON_LAUNCH = "auto_connect_on_launch"

    // ROOT 模式持久化
    const val ROOT_MIHOMO_PID = "root_mihomo_pid"
    const val ROOT_MIHOMO_SECRET = "root_mihomo_secret"
    const val ROOT_START_TIME = "root_start_time"
    const val ROOT_ACTIVE_SUBSCRIPTION_ID = "root_active_subscription_id"

    // 启动时刻的 boot session 标记，用于 reopen 重连前识别设备是否重启过（重启会杀死 root
    // mihomo，不能把过期 PID 误当"仍存活"）。判定逻辑见 [BootSession]
    const val ROOT_BOOT_COUNT = "root_boot_count"

    // 订阅
    const val ACTIVE_PROFILE_UUID = "active_profile_uuid"
    const val ACTIVE_PROFILE_NAME = "active_profile_name"
    const val SUBSCRIPTION_UPDATE_VIA_PROXY = "subscription_update_via_proxy"

    // 更新 active 订阅后自动重启代理，默认开；理由见 ProxyServiceController.restartAfterProfileUpdate
    const val RESTART_AFTER_PROFILE_UPDATE = "restart_after_profile_update"

    // Wi-Fi 自动切换
    const val WIFI_POLICY_ENABLED = "wifi_policy_enabled"
    const val WIFI_POLICY_SSIDS = "wifi_policy_ssids"
    const val WIFI_POLICY_ACTION = "wifi_policy_action"
    const val WIFI_POLICY_MATCHED = "wifi_policy_matched"
    const val WIFI_POLICY_MATCHED_ACTION = "wifi_policy_matched_action"
    const val WIFI_POLICY_PENDING_RESTART = "wifi_policy_pending_restart"
    const val WIFI_POLICY_RUNTIME_MODE = "wifi_policy_runtime_mode"
    const val WIFI_POLICY_NOTIFY_SWITCH = "wifi_policy_notify_switch"
    const val WIFI_POLICY_HIDE_MONITOR_NOTIFICATION = "wifi_policy_hide_monitor_notification"

    // VPN 设置
    const val VPN_BYPASS_PRIVATE_NETWORK = "vpn_bypass_private_network"
    const val VPN_ALLOW_BYPASS = "vpn_allow_bypass"
    const val VPN_DNS_HIJACKING = "vpn_dns_hijacking"
    const val VPN_SYSTEM_PROXY = "vpn_system_proxy"
    const val VPN_ALLOW_IPV6 = "vpn_allow_ipv6"

    // 分应用代理
    const val APP_PROXY_MODE = "app_proxy_mode"
    const val APP_PROXY_PACKAGES = "app_proxy_packages"

    // ROOT 设置
    const val ROOT_TUN_DEVICE = "root_tun_device"
    const val ROOT_TETHER_MODE = "root_tether_mode"
    const val ROOT_TETHER_IFACES = "root_tether_ifaces"

    // 启动时生效的 tether mode 快照；attach 路径用它判断 app 被杀期间用户是否改过模式
    const val ROOT_TETHER_MODE_ACTIVE = "root_tether_mode_active"

    // 启动时生效的 ROOT submode 快照（"tun"/"tproxy"）；attach 路径按此与当前 TUN_MODE 比对
    const val ROOT_SUBMODE_ACTIVE = "root_submode_active"

    // sing-tun jumbo MTU + GSO 开关；默认 true（mtu=9000 + gso=true）
    // 极端 ROM 下 TUN 到上游分片异常时可关（回退 mtu=1500、gso=false）
    const val ROOT_TUN_JUMBO_MTU = "root_tun_jumbo_mtu"

    // attach 路径强制 re-apply 热点/TPROXY 规则开关：
    // 默认 false → 先 probe anyRulesPresent()，规则齐全则 skip re-apply（避免无谓 teardown+apply）；
    // true → 强制 re-apply，覆盖"第三方模块清掉过规则"的场景，诊断用
    const val ROOT_ATTACH_FORCE_REAPPLY = "root_attach_force_reapply"

    // 上次启动时探测 xt_TPROXY 的结果（"true"/"false"/""），仅在 PROXY 或 ROOT TPROXY
    // 路径下有值。UI 读取此 key 决定是否显示「内核不支持 TPROXY，已降级」告警。
    // 空串表示「不适用」（BYPASS 模式 / 未启动过 PROXY）。
    const val ROOT_TPROXY_KERNEL_CAPABLE = "root_tproxy_kernel_capable"

    // 代理页设置
    const val PROXY_NODE_SORT_OPTION = "proxy_node_sort_option"

    // 节点单列显示，默认 false（每行 2 个）
    const val PROXY_NODE_SINGLE_COLUMN = "proxy_node_single_column"

    // 非全局模式下 GLOBAL 组是否留在列表里；全局模式无视此开关强制显示
    const val PROXY_SHOW_GLOBAL_GROUP = "proxy_show_global_group"

    // Tailscale 出站
    const val TAILSCALE_ENABLED = "tailscale_enabled"

    // 敏感值：经 SecretStore（Android Keystore 加密）落地，不进 WebDAV/本地备份。
    // 读取一律走 PlatformStorage.getSecret，不要直接 getString（旧明文值只在迁移里读一次）
    const val TAILSCALE_AUTH_KEY = "tailscale_auth_key"
    const val TAILSCALE_CONTROL_URL = "tailscale_control_url"
    const val TAILSCALE_HOSTNAME = "tailscale_hostname"
    const val TAILSCALE_EXIT_NODE = "tailscale_exit_node"
    const val TAILSCALE_ACCEPT_ROUTES = "tailscale_accept_routes"
    const val TAILSCALE_UDP = "tailscale_udp"
    const val TAILSCALE_EPHEMERAL = "tailscale_ephemeral"
    const val TAILSCALE_EXIT_NODE_ALLOW_LAN = "tailscale_exit_node_allow_lan"

    // 通用设置
    const val DARK_MODE = "dark_mode"
    const val THEME_PURE_BLACK = "theme_pure_black"
    const val THEME_MONET = "theme_monet"
    const val THEME_PALETTE_STYLE = "theme_palette_style"
    const val THEME_ACCENT_COLOR = "theme_accent_color"
    const val THEME_BLUR = "theme_blur"
    const val THEME_BLUR_STYLE = "theme_blur_style"
    const val THEME_FLOATING_BOTTOM_BAR = "theme_floating_bottom_bar"
    const val THEME_FLOATING_BOTTOM_BAR_STYLE = "theme_floating_bottom_bar_style"
    const val THEME_BOTTOM_BAR_MODE = "theme_bottom_bar_mode"
    const val THEME_PAGER_GESTURE_MODE = "theme_pager_gesture_mode"
    const val THEME_DENSITY_SCALE = "theme_density_scale"
    const val NAV_RAIL_EXPANDED = "nav_rail_expanded"
    const val DYNAMIC_NOTIFICATION = "dynamic_notification"
    const val PREDICTIVE_BACK = "predictive_back"
    // 横移返回手势，默认启用；由 AppNavigation 持有状态实时生效，这里只作持久化
    const val SWIPE_DISMISS = "swipe_dismiss"
    const val HIDE_TASK_CARD = "hide_task_card"

    // 一次性迁移标记
    const val MIGRATION_ROOT_RECLAIM_DONE = "migration_root_reclaim_done"

    // WebDAV 备份
    const val WEBDAV_URL = "webdav_url"
    const val WEBDAV_USERNAME = "webdav_username"
    const val WEBDAV_PASSWORD = "webdav_password"
    // Highest immutable snapshot version published by this installation.
    const val WEBDAV_SYNC_VERSION = "webdav_sync_version"
}

class PlatformStorage(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("mishka_prefs", Context.MODE_PRIVATE)

    // 敏感值单独一路：Keystore 加密后落 mishka_secrets，与明文 prefs 完全隔离
    // （因此也不需要进 WebDAV 的明文 dumpAll，见 BackupManager.EXCLUDED_PREF_KEYS）
    private val secrets = SecretStore(appContext)

    fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    /** 敏感值读取；Keystore 不可用时按未配置处理，不读取旧明文 prefs。 */
    fun getSecret(key: String, default: String = ""): String {
        if (!secrets.available) return default
        val stored = secrets.get(key)
        if (stored.isNotEmpty()) return stored
        migratePlaintextSecret(key)
        return secrets.get(key).ifEmpty { default }
    }

    /** 敏感值写入；空串表示清除。同时清掉同名的旧明文值（幂等迁移）。 */
    fun putSecret(key: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            secrets.remove(key)
            prefs.edit { remove(key) }
            if (key == StorageKeys.TAILSCALE_AUTH_KEY) {
                File(appContext.filesDir, TAILSCALE_GENERATED_PATH).delete()
            }
            return
        }
        if (!secrets.available) throw SecretStorageUnavailableException()
        secrets.put(key, trimmed)
        prefs.edit { remove(key) }
    }

    fun hasSecret(key: String): Boolean = getSecret(key).isNotBlank()

    /**
     * 一次性迁移：旧版本把 Tailscale auth key 明文存在 mishka_prefs。
     * 迁到 Keystore 后清掉明文；Keystore 不可用时保留旧值但不再读取，等待下次可迁移。
     */
    fun migratePlaintextSecret(key: String) {
        val legacy = prefs.getString(key, null) ?: return
        if (!secrets.available) return
        if (legacy.isNotBlank()) secrets.put(key, legacy.trim())
        prefs.edit { remove(key) }
        if (key == StorageKeys.TAILSCALE_AUTH_KEY && legacy.isBlank()) {
            File(appContext.filesDir, TAILSCALE_GENERATED_PATH).delete()
        }
    }

    fun getStringSet(key: String, default: Set<String>): Set<String> =
        prefs.getStringSet(key, default) ?: default

    fun putStringSet(key: String, value: Set<String>) {
        prefs.edit { putStringSet(key, value) }
    }

    /** 全量导出（WebDAV 备份用）；Mishka 只写 String / Set<String> 两种类型。 */
    fun dumpAll(): Map<String, Any?> = prefs.all

    private companion object {
        const val TAILSCALE_GENERATED_PATH = "mihomo/tailscale.generated.js"
    }
}
