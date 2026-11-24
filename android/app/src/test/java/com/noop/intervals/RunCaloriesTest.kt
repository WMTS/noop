package com.noop.intervals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RunCaloriesTest {

    @Test fun steadyRun_isAboutOneKcalPerKgPerKm() {
        val e = RunCalories.estimate("Running", 5000.0, 30 * 60.0, 70.0)
        assertNotNull(e)
        assertEquals(350.0, e!!.activeKcal, 0.01)              // 0.2 x 5000 x 70 / 1000 x 5
        assertEquals(350.0 + 36.75, e.grossKcal, 0.01)          // + 1 MET for 30 min
    }

    @Test fun walkingCostsAboutHalf() {
        val e = RunCalories.estimate("Walking", 3000.0, 36 * 60.0, 70.0)!! // 83 m/min
        assertEquals(105.0, e.activeKcal, 0.01)
    }

    @Test fun runWalkSplits_costWalkBreaksAsWalking() {
        val t = 1_000_000L
        val splits = listOf(
            IntervalSplit(0, StepKind.WORK, "Run", 1, 1, durationS = 240, distanceM = 800.0, avgHr = 150, maxHr = 160),
            IntervalSplit(1, StepKind.REST, "Walk", 1, 1, durationS = 60, distanceM = 100.0, avgHr = 140, maxHr = 150),
        )
        val e = RunCalories.estimate("Running", 900.0, 300.0, 70.0, splits)!!
        assertEquals((0.2 * 800 + 0.1 * 100) * 70 / 1000 * 5, e.activeKcal, 0.01)
        assertEquals("acsm-intervals", e.method)
    }

    @Test fun leftoverDistanceOutsideIntervalsStillCounts() {
        val splits = listOf(
            IntervalSplit(0, StepKind.WORK, "Run", 1, 1, durationS = 600, distanceM = 2000.0, avgHr = null, maxHr = null),
        )
        val e = RunCalories.estimate("Running", 3000.0, 900.0, 80.0, splits)!!
        assertEquals(0.2 * 3000 * 80 / 1000 * 5, e.activeKcal, 0.01)
    }

    @Test fun doesNotApply() {
        assertNull(RunCalories.estimate("Cycling", 10000.0, 1800.0, 70.0))
        assertNull(RunCalories.estimate("Hiking", 5000.0, 3600.0, 70.0))
        assertNull(RunCalories.estimate("Running", null, 1800.0, 70.0))   // indoors, no distance
        assertNull(RunCalories.estimate("Running", 50.0, 1800.0, 70.0))   // GPS barely moved
        assertNotNull(RunCalories.estimate("Treadmill run", 5000.0, 1800.0, 70.0))
    }

    @Test fun activeFromGrossSubtractsResting() {
        assertEquals(100.0 - 36.75, RunCalories.activeFromGross(100.0, 1800.0, 70.0), 0.01)
        assertEquals(0.0, RunCalories.activeFromGross(10.0, 1800.0, 70.0), 0.0)
    }

    @Test fun splitTimesRoundTrip() {
        val sp = IntervalSplit(2, StepKind.WORK, "Run", 2, 1, 240, 800.0, 150, 160, startMs = 1000L, endMs = 241000L)
        assertEquals(sp, IntervalSplit.fromJson(sp.toJson()))
    }
    @Test fun climb_addsAcsmGradeTerm() {
        val flat = RunCalories.estimate("Running", 5000.0, 30 * 60.0, 70.0)!!
        val hilly = RunCalories.estimate("Running", 5000.0, 30 * 60.0, 70.0, climbM = 100.0)!!
        assertEquals(0.9 * 100 * 70 / 1000 * 5, hilly.activeKcal - flat.activeKcal, 0.01) // 31.5 kcal
    }

    @Test fun climb_walkingCostsDouble() {
        val flat = RunCalories.estimate("Walking", 3000.0, 36 * 60.0, 70.0)!!
        val hilly = RunCalories.estimate("Walking", 3000.0, 36 * 60.0, 70.0, climbM = 50.0)!!
        assertEquals(1.8 * 50 * 70 / 1000 * 5, hilly.activeKcal - flat.activeKcal, 0.01)
    }

    @Test fun climb_isCappedAtQuarterOfDistance() {
        val flat = RunCalories.estimate("Running", 1000.0, 6 * 60.0, 70.0)!!
        val bogus = RunCalories.estimate("Running", 1000.0, 6 * 60.0, 70.0, climbM = 900.0)!!
        assertEquals(0.9 * 250 * 70 / 1000 * 5, bogus.activeKcal - flat.activeKcal, 0.01)
    }
}
