package top.yukonga.mishka.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import top.yukonga.mishka.domain.model.ConnectionsResponse
import top.yukonga.mishka.domain.model.LogMessage
import top.yukonga.mishka.domain.model.MemoryData
import top.yukonga.mishka.domain.model.TrafficData
import java.net.Proxy

class MihomoWebSocket(
    private val apiClient: MihomoApiClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val wsClient = HttpClient(OkHttp) {
        engine {
            // 控制面 WebSocket 与 REST 一样必须绕过 Android 的全局 HTTP 代理。
            config { proxy(Proxy.NO_PROXY) }
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }

    private val _connectionState = MutableStateFlow(false)

    /**
     * 是否有**任意**一条 WS 连着。四条流共用一个布尔量时，取消其中一条（如关闭速度详情）
     * 会在 finally 里把它写 false，日志页据此显示「未连接」——尽管日志 WS 一直活着。
     * 故按引用计数发布；计数与发布必须一起原子，否则并发增减会留下与实际相反的终值。
     */
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private var liveConnections = 0

    @Synchronized
    private fun addLiveConnection(delta: Int) {
        liveConnections = (liveConnections + delta).coerceAtLeast(0)
        _connectionState.value = liveConnections > 0
    }

    fun trafficFlow(): Flow<TrafficData> = webSocketFlow(
        apiClient.getWebSocketUrl("/traffic"),
    ) { text -> json.decodeFromString<TrafficData>(text) }

    fun logsFlow(level: String = "info"): Flow<LogMessage> = webSocketFlow(
        apiClient.getWebSocketUrl("/logs?level=$level"),
    ) { text -> json.decodeFromString<LogMessage>(text) }

    fun memoryFlow(): Flow<MemoryData> = webSocketFlow(
        apiClient.getWebSocketUrl("/memory"),
    ) { text -> json.decodeFromString<MemoryData>(text) }

    fun connectionsFlow(): Flow<ConnectionsResponse> = webSocketFlow(
        apiClient.getWebSocketUrl("/connections"),
    ) { text -> json.decodeFromString<ConnectionsResponse>(text) }

    /**
     * 无限重连的帧流：连上 → 逐帧 emit → 断开 → 退避重连，除取消外不终结。
     *
     * **[close] 不会让本流结束**：client 关闭后 `webSocket()` 抛的引擎异常同样被下面的
     * catch 吞掉，只是进入退避循环（封顶 30s）。消费方必须显式 cancel 收集协程，
     * 否则留下的是一条按 30s 周期重试的僵尸流——这条是 Ktor 的行为，看调用点推不出来。
     */
    private fun <T> webSocketFlow(url: String, parser: (String) -> T): Flow<T> = flow {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            var counted = false
            try {
                wsClient.webSocket(url) {
                    counted = true
                    addLiveConnection(1)
                    backoffMs = INITIAL_BACKOFF_MS
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        // 只兜解析：emit 留在 try 外，否则下游抛出的异常会被当成坏帧吞掉，
                        // 下一次 emit 撞上 flow 的异常透明性检查，症状与「服务端断了」难以区分
                        val parsed = runCatching { parser(frame.readText()) }.getOrNull() ?: continue
                        emit(parsed)
                    }
                }
            } catch (ce: CancellationException) {
                // 必须排在通用 catch 之前：取消一旦被下面那条吞掉，重连循环就再也停不下来
                throw ce
            } catch (_: Exception) {
                // 连接异常，走重连路径
            } finally {
                // 握手失败时压根没计过数，无条件减会把别人的连接算没
                if (counted) addLiveConnection(-1)
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        // 收集方都在 Main，不切走则每帧反序列化都占着主线程
    }.flowOn(Dispatchers.Default)

    fun close() {
        wsClient.close()
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
