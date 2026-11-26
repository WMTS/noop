package com.noop.intervals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HrFloorMonitorTest {
    private val E = HrFloorMonitor.Event.LOW
    private val UP = HrFloorMonitor.Event.BACK_UP

    /** Feed one reading a second from [fromS] to [toS]; moving time equals wall time here. */
    private fun feed(m: HrFloorMonitor, bpm: Int, fromS: Int, toS: Int, resting: Boolean = false) =
        (fromS..toS).mapNotNull { m.feed(bpm, it * 1000L, it * 1000L, resting) }

    @Test fun quietDuringWarmup() {
        val m = HrFloorMonitor(120, warmupMs = 300_000)
        assertEquals(emptyList<HrFloorMonitor.Event>(), feed(m, 90, 0, 299))
        assertFalse(m.alerting)
    }

    @Test fun lowAfterThirtySecondsPastWarmup() {
        val m = HrFloorMonitor(120, warmupMs = 300_000)
        feed(m, 90, 0, 299)
        // Watching from 300 s; 30 s below means the first alert at 330 s.
        assertEquals(emptyList<HrFloorMonitor.Event>(), feed(m, 110, 300, 329))
        assertEquals(listOf(E), feed(m, 110, 330, 330))
    }

    @Test fun repeatsAtMostOnceAMinute() {
        val m = HrFloorMonitor(120, warmupMs = 0)
        val ev = feed(m, 110, 0, 150)
        // 30 s, 90 s, 150 s
        assertEquals(listOf(E, E, E), ev)
    }

    @Test fun briefDipIgnored() {
        val m = HrFloorMonitor(120, warmupMs = 0)
        assertEquals(emptyList<HrFloorMonitor.Event>(), feed(m, 110, 0, 25))
        feed(m, 125, 26, 26)
        assertEquals(emptyList<HrFloorMonitor.Event>(), feed(m, 110, 27, 50))
    }

    @Test fun backUpAfterRecovery() {
        val m = HrFloorMonitor(120, warmupMs = 0)
        feed(m, 110, 0, 40)
        assertTrue(m.alerting)
        assertEquals(listOf(UP), feed(m, 130, 41, 60))
        assertFalse(m.alerting)
    }

    @Test fun restStepsAreQuietAndReset() {
        val m = HrFloorMonitor(120, warmupMs = 0)
        assertEquals(emptyList<HrFloorMonitor.Event>(), feed(m, 100, 0, 120, resting = true))
        // After the rest step the 30 s clock starts over.
        assertEquals(emptyList<HrFloorMonitor.Event>(), feed(m, 110, 121, 150))
        assertEquals(listOf(E), feed(m, 110, 151, 151))
    }

    @Test fun restStepClearsAnAlert() {
        val m = HrFloorMonitor(120, warmupMs = 0)
        feed(m, 110, 0, 40)
        assertTrue(m.alerting)
        feed(m, 100, 41, 41, resting = true)
        assertFalse(m.alerting)
    }

    @Test fun atFloorIsFine() {
        val m = HrFloorMonitor(120, warmupMs = 0)
        assertEquals(emptyList<HrFloorMonitor.Event>(), feed(m, 120, 0, 200))
    }

    @Test fun rangeSides() {
        assertEquals(-1, HrRangeStore.side(110, 120, 150))
        assertEquals(0, HrRangeStore.side(120, 120, 150))
        assertEquals(0, HrRangeStore.side(150, 120, 150))
        assertEquals(1, HrRangeStore.side(151, 120, 150))
        assertEquals(0, HrRangeStore.side(90, 0, 150))
        assertEquals(0, HrRangeStore.side(200, 120, 0))
    }
}
