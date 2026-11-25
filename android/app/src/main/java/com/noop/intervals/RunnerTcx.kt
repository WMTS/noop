package com.noop.intervals

import com.noop.analytics.RouteMath
import com.noop.analytics.RouteMath.LatLng
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fork addition (Rick): a workout as a TCX file (Garmin Training Center XML), for Strava upload and
 * "Export workout". TCX is read by Strava, Garmin Connect, TrainingPeaks, Runalyze and most others, and
 * unlike NOOP's route export it works for workouts with no GPS (indoor track, treadmill): every
 * trackpoint carries time, heart rate and distance, and position only when there is a route.
 *
 * NOOP stores a route without per-point times, so route points are spread evenly over the workout
 * (the same convention as NOOP's GPX/FIT route export). Distance along the way comes from "anchors"
 * (time, metres): GPS route progress, indoor lap taps, or a straight line from 0 to the total.
 * Laps are the indoor track laps or the interval steps when there are any, else one lap.
 *
 * Pure and unit-tested. No backslash escapes in this file on purpose (it travels through a JSON push).
 */
object RunnerTcx {
    data class Lap(val startMs: Long, val endMs: Long, val meters: Double?, val rest: Boolean = false)

    private val NL = Char(10).toString()
    private const val MAX_POINTS = 7200

    fun build(
        sport: String,
        startTs: Long,
        endTs: Long,
        distanceM: Double?,
        kcal: Double?,
        hr: List<Pair<Long, Int>>,
        route: List<LatLng> = emptyList(),
        laps: List<Lap> = emptyList(),
        distanceAnchors: List<Pair<Long, Double>>? = null,
    ): String {
        val startMs = startTs * 1000
        val endMs = maxOf(endTs * 1000, startMs + 1000)
        val total = distanceM?.takeIf { it > 0 }
        val anchors = distanceAnchors ?: routeAnchors(route, startMs, endMs, total) ?: total?.let { listOf(startMs to 0.0, endMs to it) }
        val samples = hr.filter { it.second in 25..250 && it.first * 1000 in startMs..endMs }
            .distinctBy { it.first }.sortedBy { it.first }
        val times = pointTimes(samples.map { it.first * 1000 }, startMs, endMs)
        val hrAt = samples.associate { it.first * 1000 to it.second }
        val lapList = laps.filter { it.endMs > it.startMs }.sortedBy { it.startMs }.ifEmpty { listOf(Lap(startMs, endMs, total)) }

        val sb = StringBuilder()
        sb.append("<?xml version='1.0' encoding='UTF-8'?>").append(NL)
        sb.append("<TrainingCenterDatabase xmlns='http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2'>").append(NL)
        sb.append("<Activities><Activity Sport='").append(tcxSport(sport)).append("'>").append(NL)
        sb.append("<Id>").append(iso(startMs)).append("</Id>").append(NL)
        lapList.forEachIndexed { i, lap ->
            val last = i == lapList.lastIndex
            val lapTimes = times.filter { it >= lap.startMs && (if (last) it <= lap.endMs else it < lap.endMs) }
            val lapHr = lapTimes.mapNotNull { hrAt[it] }
            val lapMeters = lap.meters ?: anchors?.let { distanceAt(it, lap.endMs) - distanceAt(it, lap.startMs) }
            val share = (lap.endMs - lap.startMs).toDouble() / (endMs - startMs)
            sb.append("<Lap StartTime='").append(iso(lap.startMs)).append("'>")
            sb.append("<TotalTimeSeconds>").append(num((lap.endMs - lap.startMs) / 1000.0)).append("</TotalTimeSeconds>")
            sb.append("<DistanceMeters>").append(num(lapMeters ?: 0.0)).append("</DistanceMeters>")
            sb.append("<Calories>").append(((kcal ?: 0.0) * share).roundToInt().coerceAtLeast(0)).append("</Calories>")
            if (lapHr.isNotEmpty()) {
                sb.append("<AverageHeartRateBpm><Value>").append(lapHr.average().roundToInt()).append("</Value></AverageHeartRateBpm>")
                sb.append("<MaximumHeartRateBpm><Value>").append(lapHr.max()).append("</Value></MaximumHeartRateBpm>")
            }
            sb.append("<Intensity>").append(if (lap.rest) "Resting" else "Active").append("</Intensity>")
            sb.append("<TriggerMethod>Manual</TriggerMethod>").append(NL)
            sb.append("<Track>").append(NL)
            for (t in lapTimes) {
                sb.append("<Trackpoint><Time>").append(iso(t)).append("</Time>")
                if (route.size >= 2) {
                    val p = route[((t - startMs).toDouble() / (endMs - startMs) * (route.size - 1)).roundToInt().coerceIn(0, route.size - 1)]
                    sb.append("<Position><LatitudeDegrees>").append(coord(p.lat)).append("</LatitudeDegrees><LongitudeDegrees>")
                        .append(coord(p.lon)).append("</LongitudeDegrees></Position>")
                }
                if (anchors != null) sb.append("<DistanceMeters>").append(num(distanceAt(anchors, t))).append("</DistanceMeters>")
                hrAt[t]?.let { sb.append("<HeartRateBpm><Value>").append(it).append("</Value></HeartRateBpm>") }
                sb.append("</Trackpoint>").append(NL)
            }
            sb.append("</Track></Lap>").append(NL)
        }
        sb.append("<Creator><Name>NOOP Runner</Name></Creator>").append(NL)
        sb.append("</Activity></Activities></TrainingCenterDatabase>").append(NL)
        return sb.toString()
    }

    /** One trackpoint per heart-rate second, or every 10 s without heart rate; thinned past [MAX_POINTS]. */
    internal fun pointTimes(hrTimesMs: List<Long>, startMs: Long, endMs: Long): List<Long> {
        val base = if (hrTimesMs.size >= 2) hrTimesMs else (startMs..endMs step 10_000L).toList()
        val all = (base + startMs + endMs).distinct().sorted()
        if (all.size <= MAX_POINTS) return all
        val step = all.size.toDouble() / MAX_POINTS
        return (0 until MAX_POINTS).map { all[(it * step).toInt()] }.plus(endMs).distinct()
    }

    /** Route points spread evenly over time, their cumulative distance scaled to [total]. */
    internal fun routeAnchors(route: List<LatLng>, startMs: Long, endMs: Long, total: Double?): List<Pair<Long, Double>>? {
        if (route.size < 2 || total == null) return null
        var cum = 0.0
        val raw = route.mapIndexed { i, p -> if (i > 0) cum += RouteMath.haversineMeters(route[i - 1], p); cum }
        val scale = if (cum > 0) total / cum else 0.0
        return raw.mapIndexed { i, d -> (startMs + (endMs - startMs) * i / (route.size - 1)) to d * scale }
    }

    /** Indoor laps: whole laps at each tap, straight lines between, ending at [total] at the finish. */
    fun lapAnchors(startMs: Long, endMs: Long, tapEndsMs: List<Long>, lapMeters: Double, total: Double): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        out.add(startMs to 0.0)
        tapEndsMs.sorted().forEachIndexed { i, t -> if (t in (startMs + 1) until endMs) out.add(t to minOf((i + 1) * lapMeters, total)) }
        out.add(endMs to maxOf(total, out.last().second))
        return out
    }

    /** Linear interpolation over anchors sorted by time. */
    fun distanceAt(anchors: List<Pair<Long, Double>>, t: Long): Double {
        if (anchors.isEmpty()) return 0.0
        if (t <= anchors.first().first) return anchors.first().second
        if (t >= anchors.last().first) return anchors.last().second
        for (i in 1 until anchors.size) {
            val (t1, d1) = anchors[i]
            if (t <= t1) {
                val (t0, d0) = anchors[i - 1]
                return if (t1 == t0) d1 else d0 + (d1 - d0) * (t - t0) / (t1 - t0)
            }
        }
        return anchors.last().second
    }

    fun tcxSport(sport: String): String {
        val s = sport.lowercase()
        return when {
            "run" in s -> "Running"
            "cycl" in s || "bike" in s || "ride" in s -> "Biking"
            else -> "Other"
        }
    }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms - ms % 1000).toString()
    private fun num(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun coord(v: Double): String = String.format(Locale.US, "%.6f", v)
}
