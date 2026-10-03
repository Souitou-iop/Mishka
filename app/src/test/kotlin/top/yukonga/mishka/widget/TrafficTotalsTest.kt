package top.yukonga.mishka.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yukonga.mishka.domain.model.TrafficData
import top.yukonga.mishka.platform.TrafficTotals
import top.yukonga.mishka.platform.accumulateTraffic
import top.yukonga.mishka.platform.clearTrafficTotals

class TrafficTotalsTest {
    @Test fun repeatedSampleAndRootReattachDoNotDoubleCount() {
        val sample = TrafficData(upTotal = 120, downTotal = 800)
        val first = accumulateTraffic(TrafficTotals(since = 10), sample, "pid:start")
        assertEquals(first, accumulateTraffic(first, sample, "pid:start"))
        val reattached = accumulateTraffic(first.copy(), sample.copy(upTotal = 140, downTotal = 900), "pid:start")
        assertEquals(140, reattached.upload)
        assertEquals(900, reattached.download)
        assertEquals(10, reattached.since)
    }
    @Test fun newCoreRetainsPreviousTotalAndAddsNewCounters() {
        val first = accumulateTraffic(TrafficTotals(), TrafficData(upTotal = 100, downTotal = 200), "first")
        val second = accumulateTraffic(first, TrafficData(upTotal = 20, downTotal = 30), "second")
        assertEquals(120, second.upload)
        assertEquals(230, second.download)
    }
    @Test fun resetKeepsBaselineInsteadOfReaddingOldBytes() {
        val before = accumulateTraffic(TrafficTotals(since = 1), TrafficData(upTotal = 100, downTotal = 200), "first")
        val reset = clearTrafficTotals(before, 2, "first")
        val next = accumulateTraffic(reset, TrafficData(upTotal = 125, downTotal = 220), "first")
        assertEquals(25, next.upload)
        assertEquals(20, next.download)
        assertEquals(2, next.since)
    }
    @Test fun nativeCounterResetAndIdleSamplesStayMonotonic() {
        val before = accumulateTraffic(TrafficTotals(), TrafficData(upTotal = 100, downTotal = 200), "first")
        val after = accumulateTraffic(before, TrafficData(upTotal = 5, downTotal = 0), "first")
        assertEquals(105, after.upload)
        assertEquals(200, after.download)
        assertEquals(after, accumulateTraffic(after, TrafficData(upTotal = 5, downTotal = 0), "first"))
    }
    @Test fun totalsSaturateRatherThanOverflow() {
        val before = TrafficTotals(upload = Long.MAX_VALUE - 2, download = Long.MAX_VALUE - 1)
        val after = accumulateTraffic(before, TrafficData(upTotal = 10, downTotal = 20), "first")
        assertEquals(Long.MAX_VALUE, after.upload)
        assertEquals(Long.MAX_VALUE, after.download)
    }
    @Test fun firstAttachDoesNotCountTrafficFromBeforeTheStatisticsWindow() {
        val first = accumulateTraffic(TrafficTotals(since = 200), TrafficData(upTotal = 1000, downTotal = 2000), "old", 100)
        assertEquals(0, first.upload)
        assertEquals(0, first.download)
        val next = accumulateTraffic(first, TrafficData(upTotal = 1010, downTotal = 2040), "old", 100)
        assertEquals(10, next.upload)
        assertEquals(40, next.download)
    }
    @Test fun resetBeforeRootReattachDropsUnobservedOldBytesButCountsFreshCore() {
        val previous = TrafficTotals(upload = 50, download = 100, since = 100, session = "old", sessionUpload = 50, sessionDownload = 100)
        val reset = clearTrafficTotals(previous, 300, "")
        val old = accumulateTraffic(reset, TrafficData(upTotal = 500, downTotal = 1000), "old", 100)
        assertEquals(0, old.upload)
        assertEquals(0, old.download)
        val fresh = accumulateTraffic(reset, TrafficData(upTotal = 5, downTotal = 10), "new", 400)
        assertEquals(5, fresh.upload)
        assertEquals(10, fresh.download)
    }

}
