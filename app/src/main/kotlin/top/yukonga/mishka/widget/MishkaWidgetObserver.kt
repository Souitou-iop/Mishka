package top.yukonga.mishka.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.platform.TrafficStatisticsStore
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys

internal class MishkaWidgetObserver(
    context: Context,
    private val scope: CoroutineScope,
    private val connectionManager: MihomoConnectionManager,
    private val trafficStatistics: TrafficStatisticsStore,
    private val overrideStore: OverrideJsonStore,
) {
    private val context = context.applicationContext
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var observerJob: Job? = null

    fun refresh() {
        // 和 receiver 回调共用主线程，缓存/observer 的换代不需要额外的锁。
        scope.launch(Dispatchers.Main.immediate) {
            if (!MishkaWidgetProvider.hasInstances(context)) {
                stopObserving()
                return@launch
            }
            MishkaWidgetProvider.updateState(context)
            if (observerJob?.isActive == true) {
                refreshRequests.tryEmit(Unit)
            } else {
                observerJob = scope.launch(Dispatchers.Main.immediate) { observe() }
            }
        }
    }

    fun stop() {
        scope.launch(Dispatchers.Main.immediate) { stopObserving() }
    }

    private fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
        MishkaWidgetProvider.resetRuntime(context)
    }

    private suspend fun observe() = coroutineScope {
        val prefs = context.getSharedPreferences("mishka_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == StorageKeys.ACTIVE_PROFILE_UUID ||
                key == StorageKeys.ACTIVE_PROFILE_NAME || key == StorageKeys.TUN_MODE ||
                key == StorageKeys.DARK_MODE || key == StorageKeys.THEME_PURE_BLACK
            ) {
                refresh()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            launch {
                ProxyServiceBridge.state.collect {
                    render { MishkaWidgetProvider.updateState(context) }
                }
            }
            launch {
                trafficStatistics.traffic.collect {
                    render { MishkaWidgetProvider.updateState(context) }
                }
            }
            launch {
                ProxyServiceBridge.notificationRefresh.collect { refreshRequests.tryEmit(Unit) }
            }
            launch {
                overrideStore.state.collect { render { MishkaWidgetProvider.updateState(context) } }
            }
            widgetRepositoryFlow(ProxyServiceBridge.state, connectionManager.repository).collectLatest { repository ->
                render { MishkaWidgetProvider.resetRuntime(context) }
                if (repository == null) return@collectLatest
                refreshRequests.onStart { emit(Unit) }.collectLatest {
                    val mode = withTimeoutOrNull(3_000L) { repository.getConfig().getOrNull()?.mode }
                    currentCoroutineContext().ensureActive()
                    if (isCurrent(repository)) render { MishkaWidgetProvider.updateMode(context, mode) }
                    val node = withTimeoutOrNull(3_000L) {
                        repository.getGroups().getOrNull()?.proxies?.let { groups ->
                            val primary = if (mode == "global") groups.firstOrNull { it.name == "GLOBAL" }
                                else groups.firstOrNull { it.name != "GLOBAL" && it.now.isNotBlank() }
                            primary?.now?.takeIf(String::isNotBlank)
                                ?: groups.firstNotNullOfOrNull { it.now.takeIf(String::isNotBlank) }
                        }
                    }
                    // REST 的 Result 可能吞掉取消；旧订阅响应不能写回新 repository 的快照。
                    currentCoroutineContext().ensureActive()
                    if (isCurrent(repository)) render { MishkaWidgetProvider.updateNode(context, node) }
                }
            }
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun isCurrent(repository: MihomoRepository): Boolean =
        ProxyServiceBridge.state.value.state == ProxyState.Running &&
            connectionManager.repository.value === repository

    private inline fun render(update: () -> Unit) {
        runCatching(update).onFailure { Log.w("MishkaWidget", "Widget update failed", it) }
    }
}

// manager 对 bridge 状态的处理是异步的：停止时不能等它发 null 才取消旧 traffic / 节点请求。
internal fun widgetRepositoryFlow(
    statuses: StateFlow<ProxyServiceStatus>,
    repositories: StateFlow<MihomoRepository?>,
): Flow<MihomoRepository?> = combine(statuses, repositories) { status, repository ->
    repository.takeIf { status.state == ProxyState.Running }
}.distinctUntilChanged()
