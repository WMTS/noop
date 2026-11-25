package com.noop.intervals

import com.noop.analytics.RouteMath.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerTcxTest {

    private fun count(s: String, sub: String) = s.split(sub).size - 1

    @Test fun distanceAt_interpolates() {
        val a = listOf(0L to 0.0, 100L to 1000.0, 200L to 1500.0)
        assertEquals(500.0, RunnerTcx.distanceAt(a, 50L), 1e-9)
        assertEquals(1250.0, RunnerTcx.distanceAt(a, 150L), 1e-9)
        assertEquals(0.0, RunnerTcx.distanceAt(a, -5L), 1e-9)
        assertEquals(1500.0, RunnerTcx.distanceAt(a, 999L), 1e-9)
    }

    @Test fun lapAnchors_wholeLapsAtTaps() {
        val a = RunnerTcx.lapAnchors(0L, 300_000L, listOf(100_000L, 200_000L), 200.0, 600.0)
        assertEquals(listOf(0L to 0.0, 100_000L to 200.0, 200_000L to 400.0, 300_000L to 600.0), a)
    }

    @Test fun indoorRun_hasLapsHeartRateAndNoPosition() {
        val start = 1_800_000_000L
        val hr = (0L until 300L).map { start + it to 140 }
        val laps = listOf(
            RunnerTcx.Lap(start * 1000, start * 1000 + 100_000, 200.0),
            RunnerTcx.Lap(start * 1000 + 100_000, start * 1000 + 200_000, 200.0),
            RunnerTcx.Lap(start * 1000 + 200_000, start * 1000 + 300_000, 200.0),
        )
        val anchors = RunnerTcx.lapAnchors(start * 1000, (start + 300) * 1000, listOf(100_000L, 200_000L, 300_000L).map { start * 1000 + it }, 200.0, 600.0)
        val x = RunnerTcx.build("Running", start, start + 300, 600.0, 60.0, hr, laps = laps, distanceAnchors = anchors)
        assertTrue(x.contains("<Activity Sport='Running'>"))
        assertEquals(3, count(x, "<Lap StartTime="))
        assertFalse(x.contains("<Position>"))
        assertTrue(x.contains("<HeartRateBpm><Value>140</Value></HeartRateBpm>"))
        assertTrue(x.contains("<DistanceMeters>600.0</DistanceMeters></Trackpoint>") || x.contains("<DistanceMeters>600.0</DistanceMeters><HeartRateBpm>"))
        assertTrue(x.contains("<Calories>20</Calories>"))
    }

    @Test fun gpsRun_hasPositionsAndOneLap() {
        val start = 1_800_000_000L
        val route = (0..10).map { LatLng(42.96 + it * 0.0001, -85.67) }
        val x = RunnerTcx.build("Walking", start, start + 600, 1100.0, null, emptyList(), route = route)
        assertTrue(x.contains("<Activity Sport='Other'>"))
        assertEquals(1, count(x, "<Lap StartTime="))
        assertTrue(x.contains("<Position><LatitudeDegrees>42.960000</LatitudeDegrees>"))
        assertTrue(x.contains("<DistanceMeters>1100.0</DistanceMeters>"))
        assertFalse(x.contains("<HeartRateBpm>"))
    }

    @Test fun longWorkouts_areThinned() {
        val times = (0L until 20_000L).map { it * 1000 }
        val pts = RunnerTcx.pointTimes(times, 0L, 19_999_000L)
        assertTrue(pts.size <= 7201)
        assertEquals(19_999_000L, pts.last())
    }

    @Test fun stravaCode_isReadFromRedirect() {
        assertEquals("abc123", StravaText.codeFrom("noop-runner://localhost/strava?state=&code=abc123&scope=read,activity:write"))
        assertEquals("xyz", StravaText.codeFrom("http://localhost/noop-runner?state=&code=xyz&scope=read"))
        assertNull(StravaText.codeFrom("noop-runner://localhost/strava?state=&error=access_denied"))
        assertNull(StravaText.codeFrom("noop-runner://localhost/strava"))
    }

    @Test fun stravaSportsAndGroups() {
        assertEquals("Run", StravaText.sportType("Treadmill run"))
        assertEquals("Walk", StravaText.sportType("Walking"))
        assertEquals("Hike", StravaText.sportType("Hiking"))
        assertEquals("Ride", StravaText.sportType("Cycling"))
        assertEquals("Workout", StravaText.sportType("Yoga"))
        assertEquals("walks", StravaText.group("Hiking"))
        assertEquals("other", StravaText.group("Strength training"))
        assertTrue(StravaText.isIndoor("Running", hasTrackLaps = true))
        assertTrue(StravaText.isIndoor("Treadmill run", hasTrackLaps = false))
        assertFalse(StravaText.isIndoor("Running", hasTrackLaps = false))
    }
}
