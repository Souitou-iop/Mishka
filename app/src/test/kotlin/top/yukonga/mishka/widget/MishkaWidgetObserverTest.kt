package top.yukonga.mishka.widget

import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.mishka.domain.model.TrafficData
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState

class MishkaWidgetObserverTest {
    @Test
    fun nonRunningStateRejectsStaleRepository() = runBlocking {
        val repository = TrafficProbe()
        val repositories = MutableStateFlow<MihomoRepository?>(repository.repository)
        for (state in listOf(ProxyState.Stopped, ProxyState.Starting, ProxyState.Stopping, ProxyState.Error)) {
            assertNull(widgetRepositoryFlow(MutableStateFlow(ProxyServiceStatus(state)), repositories).first())
        }
        assertFalse(repository.started.isCompleted)
    }

    @Test
    fun stoppingCancelsTrafficBeforeManagerClearsRepository() = runBlocking {
        withTimeout(2_000L) {
            val probe = TrafficProbe()
            val statuses = MutableStateFlow(ProxyServiceStatus(ProxyState.Running))
            val repositories = MutableStateFlow<MihomoRepository?>(probe.repository)
            val inactive = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Unconfined) {
                widgetRepositoryFlow(statuses, repositories).collectLatest { repository ->
                    if (repository == null) inactive.complete(Unit)
                    repository?.trafficFlow()?.collect {}
                }
            }
            try {
                probe.started.await()
                statuses.value = ProxyServiceStatus(ProxyState.Stopping)
                probe.cancelled.await()
                inactive.await()
                assertSame(probe.repository, repositories.value)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun replacingRepositoryCancelsOldTrafficBeforeCollectingNewTraffic() = runBlocking {
        withTimeout(2_000L) {
            val first = TrafficProbe()
            val second = TrafficProbe()
            val statuses = MutableStateFlow(ProxyServiceStatus(ProxyState.Running))
            val repositories = MutableStateFlow<MihomoRepository?>(first.repository)
            val job = launch(Dispatchers.Unconfined) {
                widgetRepositoryFlow(statuses, repositories).collectLatest { repository ->
                    repository?.trafficFlow()?.collect {}
                }
            }
            try {
                first.started.await()
                repositories.value = second.repository
                second.started.await()
                assertTrue(first.cancelled.isCompleted)
                assertFalse(second.cancelled.isCompleted)
            } finally {
                job.cancelAndJoin()
            }
            assertTrue(second.cancelled.isCompleted)
        }
    }

    @Test
    fun cancellingObserverReleasesTrafficSubscription() = runBlocking {
        withTimeout(2_000L) {
            val probe = TrafficProbe()
            val statuses = MutableStateFlow(ProxyServiceStatus(ProxyState.Running))
            val repositories = MutableStateFlow<MihomoRepository?>(probe.repository)
            val job = launch(Dispatchers.Unconfined) {
                widgetRepositoryFlow(statuses, repositories).collectLatest { repository ->
                    repository?.trafficFlow()?.collect {}
                }
            }
            try {
                probe.started.await()
            } finally {
                job.cancelAndJoin()
            }
            assertTrue(probe.cancelled.isCompleted)
        }
    }

    private class TrafficProbe {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        private val traffic: Flow<TrafficData> = flow {
            started.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val repository: MihomoRepository = Proxy.newProxyInstance(
            MihomoRepository::class.java.classLoader,
            arrayOf(MihomoRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "trafficFlow" -> traffic
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "WidgetTestRepository"
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as MihomoRepository
    }
}
