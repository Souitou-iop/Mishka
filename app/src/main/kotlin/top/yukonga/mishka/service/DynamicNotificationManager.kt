package top.yukonga.mishka.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.domain.model.TrafficData
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.util.FormatUtils

/**
 * 动态通知管理器。
 * 通过共享的 [MihomoConnectionManager.repository] 拿 traffic 流，更新前台服务通知。
 * 不持有自己的 HttpClient——所有 mihomo 客户端实例由 connectionManager 单点管理。
 *
 * 生命周期：启动协程、通知刷新 collector、stop/restart 分别在 IO 线程上调用
 * [start]/[stop]，故 job 字段必须加锁。否则并发 swap 会把旧 collector 留在后台继续
 * notify，Service 已 stopForeground(REMOVE) 之后它仍会把前台通知 id 复活成普通通知。
 */
class DynamicNotificationManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connectionManager: MihomoConnectionManager,
) {

    private val lock = Any()
    private var trafficJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(profileName: String, tunMode: TunMode) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val job = scope.launch {
            // null 是「没有可用 mihomo」的显式状态，不能在 flatMapLatest 前 filterNotNull，
            // 否则旧 trafficFlow 会继续发射，进程退出后通知可能保留过期速度。
            connectionManager.repository
                .flatMapLatest { repository ->
                    repository?.trafficFlow()?.map { it as TrafficData? }
                        ?: flowOf<TrafficData?>(null)
                }
                .collect { traffic ->
                    // 取消是协作式的：本协程被 cancel 后仍可能有一个 emit 已越过挂起点。
                    // stop() 之后 Service 已 stopForeground(REMOVE)，此时再 notify 会把
                    // 前台通知 id 复活成普通通知且无人回收，故此处显式自查。
                    if (!currentCoroutineContext().isActive) return@collect
                    runCatching {
                        if (traffic == null) {
                            notifyStaticRunning(tunMode)
                        } else {
                            val notification = NotificationHelper.buildDynamicNotification(
                                context = context,
                                profileName = profileName,
                                uploadTotal = FormatUtils.formatBytes(traffic.upTotal),
                                downloadTotal = FormatUtils.formatBytes(traffic.downTotal),
                                uploadSpeed = FormatUtils.formatSpeed(traffic.up),
                                downloadSpeed = FormatUtils.formatSpeed(traffic.down),
                            )
                            notificationManager?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
                        }
                    }.onFailure { Log.w(TAG, "Notify failed: $it") }
                }
        }
        // attach/restart 可能重复调用；同一通知只保留一个 traffic 收集协程。
        val previous = synchronized(lock) {
            val old = trafficJob
            trafficJob = job
            old
        }
        previous?.cancel()
    }

    /** 根据设置启动动态通知或显示静态通知。 */
    fun startOrFallbackStatic(storage: PlatformStorage, tunMode: TunMode = TunMode.Vpn) {
        val isDynamicEnabled = storage.getString(StorageKeys.DYNAMIC_NOTIFICATION, "true") == "true"
        if (isDynamicEnabled) {
            val profileName = storage.getString(StorageKeys.ACTIVE_PROFILE_NAME, "Mishka")
            start(profileName, tunMode)
        } else {
            stop()
            notifyStaticRunning(tunMode)
        }
    }

    private fun notifyStaticRunning(tunMode: TunMode) {
        // 与设置页的隧道模式名同源，避免通知里出现「Root TUN」而设置里写「ROOT TUN」
        val mode = context.getString(
            when (tunMode) {
                TunMode.RootTun -> R.string.settings_tun_mode_root_tun
                TunMode.RootTproxy -> R.string.settings_tun_mode_root_tproxy
                TunMode.Vpn -> R.string.settings_tun_mode_vpn
            }
        )
        val notification = NotificationHelper.buildRunningNotification(context, mode)
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
    }

    fun stop() {
        val job = synchronized(lock) {
            val current = trafficJob
            trafficJob = null
            current
        }
        job?.cancel()
    }

    companion object {
        private const val TAG = "DynamicNotification"
    }
}
