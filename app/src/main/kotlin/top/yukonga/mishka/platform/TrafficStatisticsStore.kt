package top.yukonga.mishka.platform

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.domain.model.TrafficData

internal data class TrafficTotals(
    val upload: Long = 0,
    val download: Long = 0,
    val since: Long = 0,
    val session: String = "",
    val sessionUpload: Long = 0,
    val sessionDownload: Long = 0,
)

internal fun accumulateTraffic(
    previous: TrafficTotals, sample: TrafficData, session: String, sessionStartTime: Long = Long.MAX_VALUE,
): TrafficTotals {
    val upload = sample.upTotal.coerceAtLeast(0)
    val download = sample.downTotal.coerceAtLeast(0)
    // 首次接入早已在跑的内核，先建立基线；不能把统计起点之前的流量也算进来。
    if (previous.session.isEmpty() && sessionStartTime > 0 && sessionStartTime < previous.since) {
        return previous.copy(session = session, sessionUpload = upload, sessionDownload = download)
    }
    fun delta(value: Long, baseline: Long): Long =
        if (session == previous.session && value >= baseline) value - baseline else value
    fun add(total: Long, delta: Long): Long = total + delta.coerceAtMost(Long.MAX_VALUE - total)
    return previous.copy(
        upload = add(previous.upload, delta(upload, previous.sessionUpload)),
        download = add(previous.download, delta(download, previous.sessionDownload)),
        session = session,
        sessionUpload = upload,
        sessionDownload = download,
    )
}

internal fun clearTrafficTotals(previous: TrafficTotals, since: Long, activeSession: String): TrafficTotals {
    val sameSession = previous.session == activeSession
    return previous.copy(
        upload = 0, download = 0, since = since,
        session = if (sameSession) previous.session else "",
        sessionUpload = if (sameSession) previous.sessionUpload else 0,
        sessionDownload = if (sameSession) previous.sessionDownload else 0,
    )
}

class TrafficStatisticsStore(
    context: Context,
    private val scope: CoroutineScope,
    private val connectionManager: MihomoConnectionManager,
) {
    // 本机内核计数的基线不能随订阅备份搬到另一台设备。
    private val prefs = context.getSharedPreferences("mishka_traffic_statistics", Context.MODE_PRIVATE)
    private var totals = TrafficTotals(
        upload = prefs.getLong("upload", 0).coerceAtLeast(0),
        download = prefs.getLong("download", 0).coerceAtLeast(0),
        since = prefs.getLong("since", System.currentTimeMillis()),
        session = prefs.getString("session", "").orEmpty(),
        sessionUpload = prefs.getLong("session_upload", 0).coerceAtLeast(0),
        sessionDownload = prefs.getLong("session_download", 0).coerceAtLeast(0),
    )
    private val _traffic = MutableStateFlow(TrafficData(upTotal = totals.upload, downTotal = totals.download))
    val traffic = _traffic.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Main.immediate) {
            combine(ProxyServiceBridge.state, connectionManager.repository) { status, repo ->
                repo.takeIf { status.state == ProxyState.Running }
            }.distinctUntilChanged().collectLatest { repository ->
                _traffic.value = _traffic.value.copy(up = 0, down = 0)
                if (repository == null) return@collectLatest
                val status = ProxyServiceBridge.state.value
                val session = "${status.mihomoPid}:${status.startTime}"
                repository.trafficFlow().collect { sample ->
                    currentCoroutineContext().ensureActive()
                    val current = ProxyServiceBridge.state.value
                    if (connectionManager.repository.value !== repository || current.state != ProxyState.Running ||
                        current.mihomoPid != status.mihomoPid || current.startTime != status.startTime
                    ) return@collect
                    val next = accumulateTraffic(totals, sample, session, status.startTime)
                    if (next != totals) {
                        totals = next
                        persist()
                    }
                    _traffic.value = sample.copy(upTotal = totals.upload, downTotal = totals.download)
                }
            }
        }
    }

    fun reset() {
        scope.launch(Dispatchers.Main.immediate) {
            // 保留当前内核基线；下一帧不能把清零前的全部流量重新加回来。
            val status = ProxyServiceBridge.state.value
            val activeSession = if (status.state == ProxyState.Running) "${status.mihomoPid}:${status.startTime}" else ""
            totals = clearTrafficTotals(totals, System.currentTimeMillis(), activeSession)
            persist()
            _traffic.value = _traffic.value.copy(upTotal = 0, downTotal = 0)
        }
    }

    private fun persist() {
        runCatching {
            prefs.edit {
                putLong("upload", totals.upload)
                putLong("download", totals.download)
                putLong("since", totals.since)
                putString("session", totals.session)
                putLong("session_upload", totals.sessionUpload)
                putLong("session_download", totals.sessionDownload)
            }
        }.onFailure { Log.w("TrafficStatistics", "Failed to save traffic counters", it) }
    }
}
