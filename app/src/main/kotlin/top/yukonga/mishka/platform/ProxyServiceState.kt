package top.yukonga.mishka.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 全局服务状态桥接。
 * Android 端的 TunService 写入此状态，shared 层的 ViewModel 读取。
 */
object ProxyServiceBridge {
    private val _state = MutableStateFlow(ProxyServiceStatus())
    val state: StateFlow<ProxyServiceStatus> = _state.asStateFlow()

    /**
     * 恢复是进程级维护窗口。启动命令必须经过同一个监视器，停止态检查与启动准入不能被
     * 拆到不同调用方，否则下载期间仍可能有启动请求穿过检查。
     */
    private val maintenanceMonitor = Any()
    private var restoreInProgress = false
    private var startRequestPending = false

    // 运行时显示刷新事件：动态通知设置及节点选择变动，通知与小组件共用
    private val _notificationRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notificationRefresh: SharedFlow<Unit> = _notificationRefresh.asSharedFlow()

    fun updateState(status: ProxyServiceStatus) {
        synchronized(maintenanceMonitor) {
            // start()/restart() dispatch an Android Service intent asynchronously. Any subsequent
            // service state publication means that the request has been observed and the
            // dispatch-only reservation can be released.
            startRequestPending = false
            _state.value = status
        }
    }

    /**
     * 在代理停止时原子地占用恢复窗口。调用方必须在 finally 中释放；这里故意使用同步监视器
     * 而不是挂起 Mutex，因为 Service 启动 API 是同步的 fire-and-forget 调用。
     */
    fun tryAcquireRestoreWindow(): Boolean = synchronized(maintenanceMonitor) {
        if (restoreInProgress || startRequestPending || _state.value.state != ProxyState.Stopped) return false
        restoreInProgress = true
        true
    }

    fun releaseRestoreWindow() = synchronized(maintenanceMonitor) {
        restoreInProgress = false
    }

    /**
     * 在与恢复准入相同的监视器下派发一次启动/重启请求。当前所有代理启动入口都经过
     * [ProxyServiceController]，因此门控落在共享命令边界，不会串行化无关 CLI 命令或停止请求。
     */
    fun runIfStartAllowed(block: () -> Unit): Boolean = synchronized(maintenanceMonitor) {
        if (restoreInProgress || startRequestPending) return false
        startRequestPending = true
        try {
            block()
            true
        } catch (error: Throwable) {
            startRequestPending = false
            throw error
        }
    }

    /**
     * 置停止态。**tunMode 必须带上**：消费方在 Stopped 时只能回读 storage，而 storage 是
     * 「用户当前选择」，不是「刚才在跑的那个」，两者在用户改了模式还没重启时并不相等。
     */
    fun markStopped(tunMode: TunMode) {
        synchronized(maintenanceMonitor) {
            startRequestPending = false
            _state.value = ProxyServiceStatus(ProxyState.Stopped, tunMode = tunMode)
        }
    }

    /**
     * onDestroy 专用。失败路径是 `updateState(Error)` + `stopSelf()`，紧接着就走到 onDestroy，
     * 无条件写 Stopped 会抹掉刚写入的 Error 与 errorMessage，用户只看到「启动中 → 未运行」，
     * 失败原因只剩 logcat。Error 是终态，只有非 Error 时才落 Stopped。
     */
    fun markStoppedUnlessError(tunMode: TunMode) {
        synchronized(maintenanceMonitor) {
            startRequestPending = false
            _state.update { current ->
                if (current.state == ProxyState.Error) current
                else ProxyServiceStatus(ProxyState.Stopped, tunMode = tunMode)
            }
        }
    }

    fun requestNotificationRefresh() {
        _notificationRefresh.tryEmit(Unit)
    }
}
