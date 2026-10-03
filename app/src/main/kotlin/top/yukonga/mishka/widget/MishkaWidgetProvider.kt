package top.yukonga.mishka.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import top.yukonga.mishka.MainActivity
import top.yukonga.mishka.MishkaApplication
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.service.VpnPermissionActivity
import top.yukonga.mishka.ui.theme.readThemeConfig
import top.yukonga.mishka.ui.theme.resolveIsDark
import top.yukonga.mishka.util.FormatUtils

open class MishkaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onEnabled(context: Context) = refresh(context)
    override fun onDisabled(context: Context) {
        if (!hasInstances(context)) app(context).widgetObserver.stop()
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> toggleProxy(context)
            ACTION_REFRESH -> refresh(context)
            ACTION_MODE -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: return
                val application = app(context)
                if (application.proxyController.switchProxyMode(mode, application.overrideStore)) {
                    lastMode = null
                    refresh(context)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun toggleProxy(context: Context) {
        val controller = app(context).proxyController
        when (ProxyServiceBridge.state.value.state) {
            ProxyState.Running -> controller.stop()
            ProxyState.Starting, ProxyState.Stopping -> return
            ProxyState.Stopped, ProxyState.Error -> {
                if (controller.getTunMode() == TunMode.Vpn && !controller.hasVpnPermission()) {
                    val subscriptionId = controller.resolveStartSubscriptionId() ?: return
                    context.startActivity(Intent(context, VpnPermissionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(VpnPermissionActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                    })
                } else controller.start()
            }
        }
        refresh(context)
    }

    companion object {
        private const val ACTION_TOGGLE = "top.yukonga.mishka.widget.TOGGLE"
        private const val ACTION_REFRESH = "top.yukonga.mishka.widget.REFRESH"
        private const val ACTION_MODE = "top.yukonga.mishka.widget.MODE"
        private const val EXTRA_MODE = "mode"
        private var lastNode: String? = null
        private var lastMode: String? = null
        private val providers = listOf(
            MishkaWidgetProvider::class.java,
            MishkaStatsWidgetProvider::class.java,
            MishkaControlWidgetProvider::class.java,
        )
        private fun app(context: Context) = context.applicationContext as MishkaApplication
        fun hasInstances(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return providers.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
        }
        fun refresh(context: Context) = app(context).widgetObserver.refresh()
        internal fun resetRuntime(context: Context) {
            lastNode = null
            lastMode = null
            updateState(context)
        }
        internal fun updateNode(context: Context, node: String?) {
            lastNode = node?.takeIf(String::isNotBlank)
            updateState(context)
        }
        internal fun updateMode(context: Context, mode: String?) {
            lastMode = mode
            updateState(context)
        }

        fun updateState(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for (provider in providers) {
                for (id in manager.getAppWidgetIds(ComponentName(context, provider))) {
                    val views = when (provider) {
                        MishkaStatsWidgetProvider::class.java -> buildViews(context, R.layout.widget_mishka_stats, id, provider)
                        MishkaControlWidgetProvider::class.java -> buildViews(context, R.layout.widget_mishka_control, id, provider)
                        else -> RemoteViews(mapOf(
                            SizeF(110f, 120f) to buildViews(context, R.layout.widget_mishka_control, id, provider),
                            SizeF(180f, 120f) to buildViews(context, R.layout.widget_mishka_compact, id, provider),
                            SizeF(250f, 160f) to buildViews(context, R.layout.widget_mishka, id, provider),
                        ))
                    }
                    manager.updateAppWidget(id, views)
                }
            }
        }

        private fun buildViews(context: Context, layout: Int, id: Int, provider: Class<out MishkaWidgetProvider>): RemoteViews {
            val application = app(context)
            val storage = application.platformStorage
            val theme = readThemeConfig(storage)
            val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val dark = theme.resolveIsDark(systemDark)
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val colors = context.createConfigurationContext(configuration)
            val views = RemoteViews(context.packageName, layout)
            val automatic = theme.colorMode == 0
            views.setInt(R.id.widget_root, "setBackgroundResource", when {
                automatic && theme.pureBlack -> R.drawable.widget_auto_black
                automatic -> R.drawable.widget_background
                dark && theme.pureBlack -> R.drawable.widget_surface_black
                dark -> R.drawable.widget_surface_dark
                else -> R.drawable.widget_surface_light
            })
            val status = ProxyServiceBridge.state.value
            val running = status.state == ProxyState.Running
            val busy = status.state == ProxyState.Starting || status.state == ProxyState.Stopping
            val statusText = context.getString(when (status.state) {
                ProxyState.Running -> R.string.widget_status_running
                ProxyState.Starting -> R.string.widget_status_starting
                ProxyState.Stopping -> R.string.widget_status_stopping
                ProxyState.Error -> R.string.widget_status_error
                ProxyState.Stopped -> R.string.widget_status_stopped
            })
            val statistics = layout != R.layout.widget_mishka_control
            val controls = layout != R.layout.widget_mishka_stats
            val primary = R.color.widget_text_primary
            val secondary = R.color.widget_text_secondary
            fun text(view: Int, value: String, color: Int = primary) {
                views.setTextViewText(view, value)
                if (automatic) views.setColor(view, "setTextColor", color)
                else views.setTextColor(view, colors.getColor(color))
            }
            text(R.id.widget_title, if (controls) storage.getString(StorageKeys.ACTIVE_PROFILE_NAME, context.getString(R.string.app_name))
                .ifBlank { context.getString(R.string.app_name) } else context.getString(R.string.widget_statistics_title))
            text(R.id.widget_status, if (controls) statusText else context.getString(R.string.widget_status_dot), if (status.state == ProxyState.Error)
                R.color.widget_status_error else if (running) R.color.widget_status_running else secondary)
            views.setContentDescription(R.id.widget_status, statusText)
            fun icon(view: Int, drawable: Int, color: Int, white: Boolean = false) {
                fun themedIcon(night: Boolean): Icon {
                    val config = Configuration(configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val tint = if (white) android.graphics.Color.WHITE else context.createConfigurationContext(config).getColor(color)
                    return Icon.createWithResource(context, drawable).setTint(tint)
                }
                if (automatic) views.setIcon(view, "setImageIcon", themedIcon(false), themedIcon(true))
                else views.setImageViewIcon(view, themedIcon(dark))
            }
            icon(R.id.widget_refresh, R.drawable.widget_refresh, secondary)
            views.setOnClickPendingIntent(R.id.widget_refresh, actionIntent(context, id, provider, ACTION_REFRESH))
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 1002,
                Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            if (statistics) {
                val traffic = application.trafficStatistics.traffic.value
                text(R.id.widget_total_upload, FormatUtils.formatBytes(traffic.upTotal))
                text(R.id.widget_total_download, FormatUtils.formatBytes(traffic.downTotal))
                text(R.id.widget_upload, "↑ ${FormatUtils.formatSpeed(if (running) traffic.up else 0)}", secondary)
                text(R.id.widget_download, "↓ ${FormatUtils.formatSpeed(if (running) traffic.down else 0)}", secondary)
                text(R.id.widget_upload_label, context.getString(R.string.widget_total_upload), secondary)
                text(R.id.widget_download_label, context.getString(R.string.widget_total_download), secondary)
            }
            if (controls) {
                val mode = lastMode.takeIf { running } ?: application.overrideStore.load().mode
                for ((view, value, label) in listOf(
                    Triple(R.id.widget_rule, "rule", R.string.widget_rule),
                    Triple(R.id.widget_global, "global", R.string.widget_global),
                    Triple(R.id.widget_direct, "direct", R.string.widget_direct),
                )) {
                    val selected = mode == value
                    val vertical = layout == R.layout.widget_mishka_control
                    text(view, context.getString(label), if (selected) {
                        if (vertical) primary else R.color.widget_selected_text
                    } else secondary)
                    // 只内缩选中态背景，保留模式按钮的完整点击范围。
                    views.setInt(view, "setBackgroundResource", when {
                        vertical || !selected -> android.R.color.transparent
                        automatic -> R.drawable.widget_mode_selected
                        dark -> R.drawable.widget_mode_selected_dark
                        else -> R.drawable.widget_mode_selected_light
                    })
                    val description = context.getString(if (selected) R.string.widget_mode_selected else R.string.widget_mode_select, context.getString(label))
                    val action = actionIntent(context, id, provider, ACTION_MODE, value)
                    views.setBoolean(view, "setEnabled", !busy)
                    views.setContentDescription(view, description)
                    views.setOnClickPendingIntent(view, action)
                    if (vertical) {
                        val (row, indicator) = when (value) {
                            "rule" -> R.id.widget_rule_row to R.id.widget_rule_indicator
                            "global" -> R.id.widget_global_row to R.id.widget_global_indicator
                            else -> R.id.widget_direct_row to R.id.widget_direct_indicator
                        }
                        icon(indicator, R.drawable.widget_mode_indicator, primary)
                        views.setViewVisibility(indicator, if (selected) View.VISIBLE else View.INVISIBLE)
                        views.setBoolean(row, "setEnabled", !busy)
                        views.setOnClickPendingIntent(row, action)
                    }
                }
                val toggleAction = actionIntent(context, id, provider, ACTION_TOGGLE)
                if (layout == R.layout.widget_mishka_control) {
                    val toggleDrawable = when {
                        running -> if (automatic) R.drawable.widget_toggle_on else if (dark) R.drawable.widget_toggle_on_dark else R.drawable.widget_toggle_on_light
                        automatic -> R.drawable.widget_toggle_off
                        dark -> R.drawable.widget_toggle_off_dark
                        else -> R.drawable.widget_toggle_off_light
                    }
                    views.setImageViewResource(R.id.widget_toggle, toggleDrawable)
                    views.setOnClickPendingIntent(R.id.widget_toggle, toggleAction)
                    val tunMode = if (status.state == ProxyState.Stopped || status.state == ProxyState.Error) {
                        application.proxyController.getTunMode()
                    } else status.tunMode
                    text(R.id.widget_tun_mode, context.getString(when (tunMode) {
                        TunMode.Vpn -> R.string.settings_tun_mode_vpn
                        TunMode.RootTun -> R.string.settings_tun_mode_root_tun
                        TunMode.RootTproxy -> R.string.settings_tun_mode_root_tproxy
                    }), secondary)
                } else {
                    views.setInt(R.id.widget_toggle, "setBackgroundResource", when {
                        running -> R.drawable.widget_power_active
                        automatic -> R.drawable.widget_selected
                        dark -> R.drawable.widget_selected_dark
                        else -> R.drawable.widget_selected_light
                    })
                    icon(R.id.widget_toggle, R.drawable.widget_power, R.color.widget_selected_text, white = running)
                    views.setOnClickPendingIntent(R.id.widget_toggle, toggleAction)
                }
                views.setBoolean(R.id.widget_toggle, "setEnabled", !busy)
                views.setFloat(R.id.widget_toggle, "setAlpha", if (busy) 0.45f else 1f)
                views.setContentDescription(R.id.widget_toggle, if (busy) statusText else context.getString(if (running) R.string.widget_stop else R.string.widget_start))
                if (layout == R.layout.widget_mishka) text(R.id.widget_node, if (status.state == ProxyState.Error) status.errorMessage else
                    if (mode == "direct") context.getString(R.string.widget_direct) else lastNode.takeIf { running }
                        ?: context.getString(R.string.widget_no_node), secondary)
            }
            return views
        }

        private fun actionIntent(context: Context, id: Int, provider: Class<out MishkaWidgetProvider>, action: String, mode: String? = null): PendingIntent =
            PendingIntent.getBroadcast(context, 31 * id + (mode?.hashCode() ?: action.hashCode()),
                Intent(context, provider).setAction(action).apply { if (mode != null) putExtra(EXTRA_MODE, mode) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class MishkaStatsWidgetProvider : MishkaWidgetProvider()
class MishkaControlWidgetProvider : MishkaWidgetProvider()
