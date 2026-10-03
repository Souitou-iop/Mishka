package top.yukonga.mishka.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyServiceBridgeTest {

    @Test
    fun restoreWindowBlocksProxyStartDispatchUntilReleased() {
        ProxyServiceBridge.markStopped(TunMode.Vpn)
        assertTrue(ProxyServiceBridge.tryAcquireRestoreWindow())
        try {
            var dispatched = false
            assertFalse(ProxyServiceBridge.runIfStartAllowed { dispatched = true })
            assertFalse(dispatched)
        } finally {
            ProxyServiceBridge.releaseRestoreWindow()
            ProxyServiceBridge.markStopped(TunMode.Vpn)
        }

        var dispatched = false
        assertTrue(ProxyServiceBridge.runIfStartAllowed { dispatched = true })
        assertTrue(dispatched)
        ProxyServiceBridge.markStopped(TunMode.Vpn)
    }

    @Test
    fun restoreWindowCannotStartWhileProxyIsNotStopped() {
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Starting, tunMode = TunMode.Vpn))
        assertFalse(ProxyServiceBridge.tryAcquireRestoreWindow())
        ProxyServiceBridge.markStopped(TunMode.Vpn)
    }
}
