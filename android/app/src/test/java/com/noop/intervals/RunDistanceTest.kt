package com.noop.intervals

import com.noop.analytics.RouteMath
import com.noop.analytics.RouteMath.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RunDistanceTest {
    private val lat0 = 42.96
    private val lon0 = -85.67
    private val mLat = 1 / 111195.0
    private val mLon = 1 / (111195.0 * cos(lat0 * PI / 180))

    /** Deterministic noise in [-1, 1). */
    private class Lcg(private var x: Long) {
        fun next(): Double { x = (x * 1103515245L + 12345L) % 2147483648L; return x / 1073741824.0 - 1.0 }
    }

    private fun at(northM: Double, eastM: Double) = LatLng(lat0 + northM * mLat, lon0 + eastM * mLon)

    @Test fun noisyStraightRun_staysCloseToTruth() {
        val r = Lcg(42)
        val track = (0 until 167).map { at(it * 6.0 + 3 * r.next(), 3 * r.next()) } // 996 m, +/-3 m noise
        val raw = RouteMath.totalMeters(track)
        val smooth = RunDistance.meters(track)
        assertTrue("raw GPS sum reads high ($raw)", raw > 996 * 1.05)
        assertEquals(996.0, smooth, 996 * 0.02)
    }

    @Test fun standingStill_addsAlmostNothing() {
        val r = Lcg(7)
        val track = (0 until 60).map { at(4 * r.next(), 4 * r.next()) }
        assertTrue(RouteMath.totalMeters(track) > 150)
        assertTrue(RunDistance.meters(track) < 25)
    }

    @Test fun cleanLine_losesAtMostOneStep() {
        val track = (0..100).map { at(it * 6.0, 0.0) }
        assertEquals(597.0, RunDistance.meters(track), 3.5)
    }

    @Test fun trackBends_shortenUnderOnePointFivePercent() {
        val radius = 400 / (2 * PI)
        val n = (400 / 6) * 3
        val track = (0..n).map { at(radius * sin(it * 6 / radius), radius * cos(it * 6 / radius)) }
        assertEquals(n * 6.0, RunDistance.meters(track), n * 6.0 * 0.015)
    }

    @Test fun tinyTracks_areZero() {
        assertEquals(0.0, RunDistance.meters(emptyList()), 0.0)
        assertEquals(0.0, RunDistance.meters(listOf(at(0.0, 0.0))), 0.0)
    }
}
