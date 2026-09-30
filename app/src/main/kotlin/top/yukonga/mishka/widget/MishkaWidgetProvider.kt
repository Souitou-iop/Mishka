package top.yukonga.mishka.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.mishka.MainActivity
import top.yukonga.mishka.MishkaApplication
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.GroupsResponse
import top.yukonga.mishka.domain.model.TrafficData
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.service.VpnPermissionActivity
import top.yukonga.mishka.util.FormatUtils

/**
 * Home-screen widget backed by the process-wide service bridge and traffic stream.
 * It does not own a proxy connection or a second copy of service state.
 */
class MishkaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                currentNode(context)?.let { lastNode = it }
                updateWidgets(context, appWidgetIds, lastTraffic, lastNode)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            toggleProxy(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun toggleProxy(context: Context) {
        val controller = ProxyServiceController(context.applicationContext)
        when (val state = ProxyServiceBridge.state.value.state) {
            ProxyState.Running -> controller.stop()
            ProxyState.Starting, ProxyState.Stopping -> return
            ProxyState.Stopped, ProxyState.Error -> {
                if (controller.getTunMode() == TunMode.Vpn && !controller.hasVpnPermission()) {
                    val subscriptionId = controller.resolveStartSubscriptionId() ?: return
                    context.startActivity(
                        Intent(context, VpnPermissionActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra(VpnPermissionActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                        },
                    )
                } else {
                    controller.start()
                }
            }
        }
        // Give the widget immediate feedback; service state changes continue through traffic updates.
        updateState(context)
    }

    companion object {
        private const val ACTION_TOGGLE = "top.yukonga.mishka.widget.TOGGLE"
        private const val REQUEST_TOGGLE = 1001
        private const val REQUEST_OPEN = 1002
        private const val NODE_REFRESH_GROUP = "GLOBAL"

        // 状态刷新（切模式/停止）带的是空 TrafficData，traffic 推送带的是空节点；两者各自只更新
        // 自己那半，另一半点被覆盖就会在 1Hz 推送与事件刷新交错时反复闪成 0 / No node。
        // 这里缓存最后一份值做合并，任一入口只提供它知道的那半。
        private var lastTraffic: TrafficData = TrafficData()
        private var lastNode: String? = null

        fun hasInstances(context: Context): Boolean = widgetIds(context).isNotEmpty()

        /** Refresh a widget after a service state transition without opening another connection. */
        fun updateState(context: Context) {
            val ids = widgetIds(context)
            if (ids.isNotEmpty()) updateWidgets(context, ids, lastTraffic, lastNode)
        }

        /** Update the widget from the traffic snapshot already collected by the service. */
        fun updateFromTraffic(context: Context, traffic: TrafficData, currentNode: String?) {
            lastTraffic = traffic
            currentNode?.takeIf { it.isNotBlank() }?.let { lastNode = it }
            val ids = widgetIds(context)
            if (ids.isNotEmpty()) updateWidgets(context, ids, lastTraffic, lastNode)
        }

        /** Re-read the node once for an explicit widget refresh, then render the bridge snapshot. */
        fun refresh(context: Context) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            // 由 AppWidgetManager 触发的刷新路径不持有 goAsync 结果，无需 finish
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                currentNode(context)?.let { lastNode = it }
                updateWidgets(context, ids, lastTraffic, lastNode)
            }
        }

        private fun updateWidgets(
            context: Context,
            ids: IntArray,
            traffic: TrafficData,
            currentNode: String?,
        ) {
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            val status = ProxyServiceBridge.state.value
            val mode = if (status.state == ProxyState.Stopped || status.state == ProxyState.Error) {
                ProxyServiceController(context).getTunMode()
            } else {
                status.tunMode
            }
            val profileName = PlatformStorage(context).getString(
                StorageKeys.ACTIVE_PROFILE_NAME,
                context.getString(R.string.app_name),
            ).ifBlank { context.getString(R.string.app_name) }
            val views = buildViews(context, status, mode, profileName, traffic, currentNode)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(
            context: Context,
            status: ProxyServiceStatus,
            mode: TunMode,
            profileName: String,
            traffic: TrafficData,
            currentNode: String?,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_mishka)
            val statusText = context.getString(
                when (status.state) {
                    ProxyState.Running -> R.string.widget_status_running
                    ProxyState.Starting -> R.string.widget_status_starting
                    ProxyState.Stopping -> R.string.widget_status_stopping
                    ProxyState.Error -> R.string.widget_status_error
                    ProxyState.Stopped -> R.string.widget_status_stopped
                },
            )
            val modeText = context.getString(
                when (mode) {
                    TunMode.Vpn -> R.string.settings_tun_mode_vpn
                    TunMode.RootTun -> R.string.settings_tun_mode_root_tun
                    TunMode.RootTproxy -> R.string.settings_tun_mode_root_tproxy
                },
            )
            val nodeText = currentNode?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.widget_no_node)
            val toggleText = when (status.state) {
                ProxyState.Running -> context.getString(R.string.widget_stop)
                ProxyState.Starting -> context.getString(R.string.widget_status_starting)
                ProxyState.Stopping -> context.getString(R.string.widget_status_stopping)
                ProxyState.Stopped, ProxyState.Error -> context.getString(R.string.widget_start)
            }
            views.setTextViewText(R.id.widget_title, profileName)
            views.setTextViewText(R.id.widget_status, statusText)
            views.setTextViewText(R.id.widget_mode, context.getString(R.string.widget_mode_value, modeText))
            views.setTextViewText(R.id.widget_node, context.getString(R.string.widget_node_value, nodeText))
            views.setTextViewText(
                R.id.widget_upload,
                context.getString(R.string.widget_upload_value, FormatUtils.formatSpeed(traffic.up)),
            )
            views.setTextViewText(
                R.id.widget_download,
                context.getString(R.string.widget_download_value, FormatUtils.formatSpeed(traffic.down)),
            )
            views.setTextViewText(R.id.widget_toggle, toggleText)
            views.setContentDescription(R.id.widget_toggle, toggleText)
            views.setViewVisibility(R.id.widget_error, if (status.state == ProxyState.Error) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_error, status.errorMessage)
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            views.setOnClickPendingIntent(R.id.widget_toggle, toggleIntent(context))
            return views
        }

        private fun toggleIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_TOGGLE,
            Intent(context, MishkaWidgetProvider::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, MishkaWidgetProvider::class.java))

        private suspend fun currentNode(context: Context): String? {
            val repository = runCatching {
                MishkaApplication.instance.connectionManager.repository.value
            }.getOrNull() ?: return null
            return withTimeoutOrNull(NODE_REQUEST_TIMEOUT_MS) {
                repository.getGroups().getOrNull()?.currentNode()
            }
        }

        private fun GroupsResponse.currentNode(): String? = proxies
            .firstOrNull { it.name == NODE_REFRESH_GROUP }
            ?.now
            ?.takeIf { it.isNotBlank() }
            ?: proxies.firstNotNullOfOrNull { it.now.takeIf(String::isNotBlank) }

        private const val NODE_REQUEST_TIMEOUT_MS = 3_000L
    }
}
