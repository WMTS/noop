package com.noop.intervals

import com.noop.analytics.RouteMath
import com.noop.analytics.RouteMath.LatLng

/**
 * Fork addition (Rick): GPS distance with the wobble taken out.
 *
 * NOOP adds up the straight-line distance between every accepted GPS fix. Consumer GPS wanders a few
 * metres from fix to fix, and every wander is counted as distance: most of all while standing still
 * (a walk break, a traffic light), where a minute of jitter can add 50-100 m. Distance feeds pace,
 * interval splits, calories and shoe miles, so all of them read high.
 *
 * Two light steps, both causal (only past fixes), so the live total never jumps backwards:
 *  1. Each fix is replaced by the mean of it and the previous [SMOOTH_POINTS] - 1 fixes.
 *  2. Distance is only banked once the smoothed position is [MIN_STEP_M] from the last banked point,
 *     so jitter around one spot adds nothing while real movement is counted in full.
 *
 * On a straight line this loses about one fix of distance per workout; on a 400 m track's bends it
 * shortens by well under 1%. The saved route (the map) is left as recorded. Pure; unit-tested.
 */
object RunDistance {
    const val SMOOTH_POINTS = 3
    const val MIN_STEP_M = 4.0

    fun meters(track: List<LatLng>): Double {
        if (track.size < 2) return 0.0
        var anchor: LatLng? = null
        var sum = 0.0
        for (i in track.indices) {
            val from = maxOf(0, i - SMOOTH_POINTS + 1)
            var lat = 0.0
            var lon = 0.0
            for (j in from..i) { lat += track[j].lat; lon += track[j].lon }
            val n = i - from + 1
            val p = LatLng(lat / n, lon / n)
            val a = anchor
            if (a == null) { anchor = p; continue }
            val d = RouteMath.haversineMeters(a, p)
            if (d >= MIN_STEP_M) { sum += d; anchor = p }
        }
        return sum
    }
}
