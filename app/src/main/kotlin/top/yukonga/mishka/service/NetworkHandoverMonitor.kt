package top.yukonga.mishka.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 默认网络（Wi-Fi ⇄ 蜂窝）切换观测器。
 *
 * 只观测与去抖，**不**在切换时重启 mihomo，也**不**重装 netfilter 规则：
 * - Wi-Fi 策略（停服务 / Direct 热重载）已独占 restart 入口，这里再插一手会与它竞态；
 * - 三种隧道都不依赖物理网卡名——VPN 走 VpnService fd、ROOT TUN 由 sing-tun
 *   `auto-detect-interface` 自行重探、ROOT TPROXY 劫持的是本机/热点入站，
 *   普通切换本就不需要重建，照搬「检测到变化就 restart」只会放大 FlClash #1713 那类问题。
 * 故这里只把最近一次切换事件与计数暴露出来，供启动/重连路径与诊断消费。
 */
object NetworkHandoverMonitor {

    /** 切换事件。`from`/`to` 是 transport 的稳定描述串，仅用于日志与诊断展示。 */
    data class Handover(
        val from: String,
        val to: String,
        val atMillis: Long,
    )

    private val _lastHandover = MutableStateFlow<Handover?>(null)
    val lastHandover: StateFlow<Handover?> = _lastHandover.asStateFlow()

    private val _count = MutableStateFlow(0L)

    /** 进程生命周期内累计的切换事件数（去抖吸收抖动后）。 */
    val count: StateFlow<Long> = _count.asStateFlow()

    // 去抖窗口：Wi-Fi⇄蜂窝切换会连续触发 onAvailable/onLost/onCapabilitiesChanged，
    // 3s 内的后续变化并入前一次事件，避免把抖动报成多次切换。
    const val SETTLE_MS = 3_000L

    private val lock = Any()
    private var refCount = 0
    private var connectivity: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var currentTransport: String? = null
    private var lastEventAt = 0L

    /** 服务进入运行态时调用。可重入：多个服务同时运行时按引用计数只注册一份回调。 */
    fun start(context: Context) {
        synchronized(lock) {
            refCount++
            if (refCount > 1) return
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            if (cm == null) {
                refCount--
                return
            }
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleEvaluate(cm)
                override fun onLost(network: Network) = scheduleEvaluate(cm)
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = scheduleEvaluate(cm)
            }
            connectivity = cm
            callback = cb
            // 初始快照：null → 首个 transport 只是建立基线，不算切换。
            currentTransport = defaultTransport(cm)
            lastEventAt = 0L
            runCatching { cm.registerDefaultNetworkCallback(cb) }
                .onFailure {
                    Log.w(TAG, "registerDefaultNetworkCallback failed", it)
                    connectivity = null
                    callback = null
                    refCount--
                }
        }
    }

    /** 服务离开运行态时调用；与 [start] 成对，引用计数归零才注销。 */
    fun stop() {
        synchronized(lock) {
            if (refCount == 0) return
            refCount--
            if (refCount > 0) return
            val cm = connectivity
            val cb = callback
            connectivity = null
            callback = null
            currentTransport = null
            if (cm != null && cb != null) {
                runCatching { cm.unregisterNetworkCallback(cb) }
            }
        }
    }

    /**
     * 回调线程调用。只做同步快照比对，不引入延迟任务/协程——这样服务注销后不会残留
     * 悬挂的延时回调。去抖通过 [SETTLE_MS] 内合并实现；同一 transport 的重复回调直接忽略。
     */
    private fun scheduleEvaluate(cm: ConnectivityManager) {
        val next = defaultTransport(cm)
        val event = synchronized(lock) { recordChange(next) } ?: return
        _lastHandover.value = event
        _count.value = _count.value + 1
        Log.i(TAG, "Default network handover: ${event.from} -> ${event.to}")
    }

    /** 在 [lock] 内比对并推进基线；返回 null 表示不是一次需要上报的切换。 */
    private fun recordChange(next: String?): Handover? {
        val previous = currentTransport
        if (next == previous) return null
        currentTransport = next
        // 基线（任一为 null）不算切换，只更新基线
        if (previous == null || next == null) return null
        val now = System.currentTimeMillis()
        if (lastEventAt != 0L && now - lastEventAt < SETTLE_MS) return null
        lastEventAt = now
        return Handover(from = previous, to = next, atMillis = now)
    }

    private fun defaultTransport(cm: ConnectivityManager): String? =
        cm.getNetworkCapabilities(cm.activeNetwork)?.let(::describe)

    private fun describe(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }

    private const val TAG = "NetworkHandover"
}
