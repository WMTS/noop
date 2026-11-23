package com.noop.intervals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrCapMonitorTest {
    private fun feedRange(m: HrCapMonitor, bpm: Int, fromS: Int, toS: Int): List<HrCapMonitor.Event> =
        (fromS..toS).mapNotNull { m.feed(bpm, it * 1000L) }

    @Test fun briefSpikeIgnored() {
        val m = HrCapMonitor(140)
        assertEquals(emptyList<HrCapMonitor.Event>(), feedRange(m, 150, 0, 10))
        assertNull(m.feed(130, 11_000))
        assertEquals(emptyList<HrCapMonitor.Event>(), feedRange(m, 150, 12, 20))
    }

    @Test fun sustainedHighAlertsThenRepeats() {
        val m = HrCapMonitor(140)
        val ev = feedRange(m, 150, 0, 60)
        // first at 15 s, repeat at 60 s
        assertEquals(listOf(HrCapMonitor.Event.HIGH, HrCapMonitor.Event.HIGH), ev)
    }

    @Test fun backUnderAfterRecovery() {
        val m = HrCapMonitor(140)
        feedRange(m, 150, 0, 20)
        val ev = feedRange(m, 135, 21, 40)
        assertEquals(listOf(HrCapMonitor.Event.BACK_UNDER), ev)
    }

    @Test fun atCapIsFine() {
        val m = HrCapMonitor(140)
        assertEquals(emptyList<HrCapMonitor.Event>(), feedRange(m, 140, 0, 100))
    }
}
