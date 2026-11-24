package com.noop.intervals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunnerHrTest {

    @Test fun reserveZones_startFromRestingHr() {
        // Reserve = 172 - 52 = 120; zones start at 52 + 50/60/70/80/90% of 120.
        assertEquals(listOf(112, 124, 136, 148, 160), HrReserve.zoneStarts(52, 172))
    }

    @Test fun reserveZones_roundUp() {
        // 55 + 0.5 x 128 = 119.0, 55 + 0.6 x 128 = 131.8 -> 132
        assertEquals(listOf(119, 132, 145, 158, 171), HrReserve.zoneStarts(55, 183))
    }

    @Test fun reserveZones_rejectImplausibleInputs() {
        assertNull(HrReserve.zoneStarts(0, 172))
        assertNull(HrReserve.zoneStarts(52, 80))
        assertNull(HrReserve.zoneStarts(100, 130))
    }

    @Test fun typicalResting_isMedianOfLastSeven() {
        assertEquals(53, HrReserve.typicalResting(listOf(60, 55, 50, 52, 53, 51, 54, 70)))
        assertEquals(51, HrReserve.typicalResting(listOf(50, 52)))
        assertNull(HrReserve.typicalResting(listOf(0, 250)))
    }

    @Test fun sustainedPeak_ignoresOneSecondSpike() {
        val s = (0L until 60L).map { it to if (it == 30L) 210 else 150 }
        assertEquals(154, MaxHrFinder.sustainedPeak(s)) // (14 x 150 + 210) / 15
    }

    @Test fun sustainedPeak_findsHeldEffort() {
        val s = (0L until 120L).map { it to if (it in 60L until 80L) 180 else 140 }
        assertEquals(180, MaxHrFinder.sustainedPeak(s))
    }

    @Test fun sustainedPeak_needsEnoughReadingsInWindow() {
        val sparse = (0L until 20L).map { it * 10 to 190 } // one reading every 10 s
        assertNull(MaxHrFinder.sustainedPeak(sparse))
    }

    @Test fun best_picksHighestInRange() {
        val peaks = listOf(
            MaxHrFinder.Peak(100, 181, "Running"),
            MaxHrFinder.Peak(5, 190, "Running"),   // too old
            MaxHrFinder.Peak(200, 0, "Walking"),   // no heart rate
            MaxHrFinder.Peak(300, 176, "Running"),
        )
        assertEquals(181, MaxHrFinder.best(peaks, sinceTs = 50)!!.bpm)
    }
}
