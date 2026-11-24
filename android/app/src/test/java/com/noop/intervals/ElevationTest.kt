package com.noop.intervals

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevationTest {
    private class Lcg(private var x: Long) {
        fun next(): Double { x = (x * 1103515245L + 12345L) % 2147483648L; return x / 1073741824.0 - 1.0 }
    }

    private fun run(alts: List<Double>, alpha: Double, threshold: Double): ClimbCounter {
        val c = ClimbCounter(threshold)
        val s = AltitudeSmoother(alpha)
        alts.forEach { c.add(s.add(it)) }
        return c
    }

    @Test fun flatGround_barometerNoise_addsNoClimb() {
        val r = Lcg(11)
        val c = run((0 until 1800).map { 200 + r.next() }, ElevationTracker.BARO_ALPHA, ElevationTracker.BARO_THRESHOLD_M)
        assertEquals(0.0, c.gainM, 0.0)
        assertEquals(0.0, c.lossM, 0.0)
    }

    @Test fun flatGround_gpsNoise_addsNoClimb() {
        val r = Lcg(9)
        val c = run((0 until 900).map { 200 + 6 * r.next() }, ElevationTracker.GPS_ALPHA, ElevationTracker.GPS_THRESHOLD_M)
        assertEquals(0.0, c.gainM, 0.0)
    }

    @Test fun thirtyMetreClimb_isCounted() {
        val r = Lcg(5)
        val alts = (0..300).map { 200 + 30.0 * it / 300 + r.next() } + (0 until 60).map { 230 + r.next() }
        val c = run(alts, ElevationTracker.BARO_ALPHA, ElevationTracker.BARO_THRESHOLD_M)
        assertEquals(30.0, c.gainM, 2.5)
        assertEquals(0.0, c.lossM, 0.0)
    }

    @Test fun upAndDown_countsBoth() {
        val c = ClimbCounter(2.0)
        listOf(100.0, 103.0, 110.0, 109.0, 104.0, 100.0).forEach { c.add(it) }
        assertEquals(10.0, c.gainM, 1e-9)
        assertEquals(10.0, c.lossM, 1e-9)
    }
}
