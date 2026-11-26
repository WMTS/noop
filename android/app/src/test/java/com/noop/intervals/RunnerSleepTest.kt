package com.noop.intervals

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerSleepTest {

    // A night from minute 0 to 600 (10 h). Sleeping HR 50 (band = 58), awake HR 70.
    private fun hr(vararg spans: Triple<Int, Int, Double>): Map<Long, Double> {
        val m = HashMap<Long, Double>()
        for ((a, b, v) in spans) for (t in a..b) m[t.toLong()] = v
        return m
    }

    private fun walk(vararg minutes: Int): Map<Long, Int> = minutes.associate { it.toLong() to 25 }

    @Test fun getUpWithoutReturnToSleep_endsTheNight() {
        // Asleep until 470, walks at 470-471, then sits awake at HR 70 until 600.
        val h = hr(Triple(0, 469, 50.0), Triple(470, 600, 70.0))
        assertEquals(470L, GetUpTrim.getUpMinute(0, 600, 50, h, walk(470, 471)))
    }

    @Test fun bathroomTripThenSleep_isKept() {
        // Walk at 200-201, HR back to 50 from 205 for the rest of the night.
        val h = hr(Triple(0, 199, 50.0), Triple(200, 204, 70.0), Triple(205, 600, 50.0))
        assertNull(GetUpTrim.getUpMinute(0, 600, 50, h, walk(200, 201)))
    }

    @Test fun earliestQualifyingWalkWins() {
        // A bathroom trip at 200 (back to sleep), then the real get-up at 470 with more walking at 520.
        val h = hr(Triple(0, 199, 50.0), Triple(200, 204, 70.0), Triple(205, 469, 50.0), Triple(470, 600, 72.0))
        assertEquals(470L, GetUpTrim.getUpMinute(0, 600, 50, h, walk(200, 201, 470, 471, 520, 521)))
    }

    @Test fun singleTurnOverMinute_isNotAWalk() {
        val h = hr(Triple(0, 469, 50.0), Triple(470, 600, 70.0))
        assertNull(GetUpTrim.getUpMinute(0, 600, 50, h, mapOf(470L to 25)))
    }

    @Test fun walkTooEarlyOrTooLate_isIgnored() {
        val h = hr(Triple(0, 600, 70.0))
        assertNull(GetUpTrim.getUpMinute(0, 600, 50, h, walk(30, 31)))
        assertNull(GetUpTrim.getUpMinute(0, 600, 50, h, walk(595, 596)))
    }

    @Test fun noHeartRateInTail_isNotJudged() {
        val h = hr(Triple(0, 469, 50.0))
        assertNull(GetUpTrim.getUpMinute(0, 600, 50, h, walk(470, 471)))
    }

    private fun night(id: String, wake: Int?, totalMin: Double = 480.0) = DailyMetric(
        deviceId = id, day = "2026-11-26", totalSleepMin = totalMin, efficiency = 0.90,
        deepMin = 80.0, remMin = 160.0, lightMin = 240.0, disturbances = wake,
    )

    @Test fun disturbanceScale() {
        assertEquals(100.0, RunnerRest.disturbanceScore(0), 1e-9)
        assertEquals(100.0, RunnerRest.disturbanceScore(2), 1e-9)
        assertEquals(87.5, RunnerRest.disturbanceScore(3), 1e-9)
        assertEquals(50.0, RunnerRest.disturbanceScore(6), 1e-9)
        assertEquals(0.0, RunnerRest.disturbanceScore(10), 1e-9)
        assertEquals(0.0, RunnerRest.disturbanceScore(14), 1e-9)
    }

    @Test fun onlyPhoneScoredNightsUseTheNewFormula() {
        assertNotNull(RunnerRest.score(night("my-whoop-noop", 2)))
        assertNull(RunnerRest.score(night("my-whoop", 2)))
        assertNull(RunnerRest.score(night("my-whoop-noop", null)))
    }

    @Test fun scoreAddsUp() {
        // 8 h -> 40, efficiency 90% -> 18, deep+REM 50% with deep 16.7% -> 20, consistency neutral -> 5,
        // 2 wake-ups -> 10. Total 93.
        assertEquals(93.0, RunnerRest.score(night("my-whoop-noop", 2))!!, 1e-6)
        // 6 wake-ups -> disturbances 5 instead of 10.
        assertEquals(88.0, RunnerRest.score(night("my-whoop-noop", 6))!!, 1e-6)
        val parts = RunnerRest.parts(night("my-whoop-noop", 2))!!
        assertEquals(5, parts.size)
        assertEquals(100.0, parts.sumOf { it.maxPoints }, 1e-9)
    }

    @Test fun flagsHighRem() {
        val f = NightStages.flags(532.0, 86.0, 180.0, 0.90, 3, false)
        assertTrue(f.any { it.startsWith("REM is high (34%") })
    }

    @Test fun parsesSegmentsAndLatency() {
        val json = """[{"start":1000,"end":1600,"stage":"wake"},{"start":1600,"end":5000,"stage":"light"}]"""
        val segs = NightStages.parse(json)
        assertEquals(2, segs.size)
        assertEquals(600L, NightStages.latencySeconds(1000, segs))
        assertEquals(0, NightStages.parse("""{"light":200}""").size)
    }
}
