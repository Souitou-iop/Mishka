package top.yukonga.mishka

import android.content.Intent

/** App Shortcuts 只携带固定 action；导航请求在主 Activity 内转成既有页面路由。 */
sealed interface ShortcutDestination {
    data object Proxy : ShortcutDestination
    data object DnsQuery : ShortcutDestination
    data object Settings : ShortcutDestination
    data class Subscription(val id: String) : ShortcutDestination
}

object AppShortcuts {
    const val ACTION_START_PROXY = "top.yukonga.mishka.action.SHORTCUT_START_PROXY"
    const val ACTION_STOP_PROXY = "top.yukonga.mishka.action.SHORTCUT_STOP_PROXY"
    const val ACTION_TOGGLE_PROXY = "top.yukonga.mishka.action.SHORTCUT_TOGGLE_PROXY"
    const val ACTION_OPEN_PROXY = "top.yukonga.mishka.action.SHORTCUT_OPEN_PROXY"
    const val ACTION_OPEN_DNS = "top.yukonga.mishka.action.SHORTCUT_OPEN_DNS"
    const val ACTION_OPEN_SETTINGS = "top.yukonga.mishka.action.SHORTCUT_OPEN_SETTINGS"
    const val ACTION_OPEN_SUBSCRIPTION = "top.yukonga.mishka.action.SHORTCUT_OPEN_SUBSCRIPTION"
    const val EXTRA_SUBSCRIPTION_ID = "shortcut_subscription_id"

    private val OPEN_ACTIONS = setOf(
        ACTION_OPEN_PROXY,
        ACTION_OPEN_DNS,
        ACTION_OPEN_SETTINGS,
        ACTION_OPEN_SUBSCRIPTION,
    )

    private val TOGGLE_ACTIONS = setOf(ACTION_START_PROXY, ACTION_STOP_PROXY, ACTION_TOGGLE_PROXY)

    // MainActivity 必须是 exported 的 LAUNCHER 入口，因此任意外部应用都能用显式 Intent 把任意
    // action 塞进来。不做白名单就等于把「启动/停止代理」开放成一个零权限的跨应用控制面。
    fun isShortcutAction(action: String?): Boolean = action in OPEN_ACTIONS || action in TOGGLE_ACTIONS

    /** 把快捷方式 action 解析成目标页；不产生导航的 action（启停）返回 null。 */
    fun openDestination(action: String?): ShortcutDestination? = when (action) {
        ACTION_OPEN_PROXY -> ShortcutDestination.Proxy
        ACTION_OPEN_DNS -> ShortcutDestination.DnsQuery
        ACTION_OPEN_SETTINGS -> ShortcutDestination.Settings
        ACTION_OPEN_SUBSCRIPTION -> null
        else -> null
    }

    fun isToggleAction(action: String?): Boolean = action == ACTION_TOGGLE_PROXY

    fun subscriptionId(id: String): String = "subscription_$id"
}
