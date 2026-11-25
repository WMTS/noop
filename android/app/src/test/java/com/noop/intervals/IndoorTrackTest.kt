package com.noop.intervals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndoorTrackTest {

    @Test fun lapLength_fromLapsPerMile() {
        assertEquals(178.816, IndoorLaps.lapMetersFromLapsPerMile(9.0), 0.001)
        assertEquals(134.112, IndoorLaps.lapMetersFromLapsPerMile(12.0), 0.001)
    }

    @Test fun inputs_mustBePlausible() {
        assertTrue(IndoorLaps.validLapsPerMile(9.0))
        assertFalse(IndoorLaps.validLapsPerMile(0.0))
        assertFalse(IndoorLaps.validLapsPerMile(50.0))
        assertTrue(IndoorLaps.validLapMeters(200.0))
        assertFalse(IndoorLaps.validLapMeters(10.0))
        assertFalse(IndoorLaps.validLapMeters(null))
    }

    @Test fun doubleTap_withinTenSeconds_isIgnored() {
        val start = 1_000_000L
        assertFalse(IndoorLaps.accepts(start, emptyList(), start + 5_000))
        assertTrue(IndoorLaps.accepts(start, emptyList(), start + 60_000))
        assertFalse(IndoorLaps.accepts(start, listOf(start + 60_000), start + 64_000))
        assertTrue(IndoorLaps.accepts(start, listOf(start + 60_000), start + 75_000))
    }

    @Test fun lapDurations_startFromWorkoutStart() {
        assertEquals(listOf(60_000L, 55_000L), IndoorLaps.lapDurationsMs(0L, listOf(60_000L, 115_000L)))
    }

    @Test fun estimatedDistance_addsPartLapByTime() {
        val taps = listOf(60_000L, 120_000L) // two 60 s laps of 200 m
        assertEquals(400.0, IndoorLaps.estimatedMeters(200.0, 0L, taps, 120_000L), 0.001)
        assertEquals(500.0, IndoorLaps.estimatedMeters(200.0, 0L, taps, 150_000L), 0.001) // half a lap more
        assertEquals(590.0, IndoorLaps.estimatedMeters(200.0, 0L, taps, 400_000L), 0.001)  // capped below a full lap
        assertEquals(0.0, IndoorLaps.estimatedMeters(200.0, 0L, emptyList(), 30_000L), 0.001)
    }

    @Test fun fewerLaps_dropsTheShortestTaps() {
        // Laps: 60 s, 12 s (stray double tap), 60 s -> correcting to 2 drops the 12 s one.
        val taps = listOf(60_000L, 72_000L, 132_000L)
        assertEquals(listOf(60_000L, 132_000L), IndoorLaps.keptTapsForCount(0L, taps, 2))
        assertEquals(taps, IndoorLaps.keptTapsForCount(0L, taps, 5))
        assertEquals(emptyList<Long>(), IndoorLaps.keptTapsForCount(0L, taps, 0))
    }

    @Test fun labels() {
        assertEquals("9", IndoorLaps.trim(9.0))
        assertEquals("8.5", IndoorLaps.trim(8.5))
        assertEquals("2:04", IndoorLaps.clock(124_000L))
        assertEquals("9 laps per mile", IndoorTrack("a", "YMCA", 178.816, 9.0).lengthLabel())
        assertEquals("200 m per lap", IndoorTrack("b", "Gym", 200.0, null).lengthLabel())
    }

    @Test fun runnerBuildNumber_isReadFromVersion() {
        assertEquals(9, RunnerUpdate.buildOf("12.1.0-runner.9"))
        assertEquals(12, RunnerUpdate.buildOf("12.1.0-runner.12"))
        assertNull(RunnerUpdate.buildOf("12.1.0-intervals"))
    }
}
