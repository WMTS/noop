package com.noop.intervals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoeMathTest {
    private val mile = Shoe.METERS_PER_MILE

    @Test fun totalIsBasePlusOwnRuns() {
        val shoe = Shoe("a", "Pegasus", baseMeters = 100 * mile)
        val runs = listOf(
            ShoeRun(1L, "a", 3.1 * mile),
            ShoeRun(2L, "a", 5.0 * mile),
            ShoeRun(3L, "b", 10.0 * mile), // another pair
        )
        assertEquals(108.1 * mile, ShoeMath.totalMeters(shoe, runs), 0.01)
    }

    @Test fun limitWarning() {
        val shoe = Shoe("a", "Old", baseMeters = 395 * mile, limitMeters = 400 * mile)
        assertFalse(ShoeMath.overLimit(shoe, emptyList()))
        assertTrue(ShoeMath.overLimit(shoe, listOf(ShoeRun(1L, "a", 6 * mile))))
        assertFalse(ShoeMath.overLimit(shoe.copy(limitMeters = 0.0), listOf(ShoeRun(1L, "a", 600 * mile))))
    }

    @Test fun negativeRunDistancesIgnored() {
        val shoe = Shoe("a", "X")
        assertEquals(0.0, ShoeMath.totalMeters(shoe, listOf(ShoeRun(1L, "a", -5.0))), 0.0)
    }

    @Test fun runningSportsDefaultToShoe() {
        assertTrue(ShoeMath.defaultsToShoe("Running"))
        assertTrue(ShoeMath.defaultsToShoe("Treadmill run"))
        assertFalse(ShoeMath.defaultsToShoe("Cycling"))
        assertFalse(ShoeMath.defaultsToShoe("Walking"))
    }

    @Test fun jsonRoundTrip() {
        val s = Shoe("shoe.x", "Clifton 9", baseMeters = 1234.5, limitMeters = 500 * mile, retired = true)
        assertEquals(s, Shoe.fromJson(s.toJson()))
    }
}
