package com.noop.intervals

import com.noop.analytics.DetectedSleep
import com.noop.analytics.SleepStager
import com.noop.analytics.StageSegment
import com.noop.data.DailyMetric
import com.noop.data.HrSample
import com.noop.data.StepSample
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fork addition (Rick): end the night when you get up.
 *
 * NOOP finds sleep mostly from wrist stillness, and a still run only has to keep heart rate under the
 * day's median. Sitting quietly after getting up (coffee, phone, reading) passes both, so a 4:45 get-up
 * could be recorded as sleep until 6:41, often staged as REM because heart rate is up while the wrist is
 * still. This post-pass looks for the first stretch of walking (the strap's step counter, 2 minutes in a
 * row at 10+ steps a minute, or one minute at 40+) after which heart rate never settles back to sleeping
 * levels for 30 minutes in a row. The night then ends at that walk. A bathroom trip followed by more
 * sleep is left alone, because heart rate drops back to the sleeping band afterwards.
 *
 * Needs step records (WHOOP 5.0 / MG). Without them it changes nothing. Pure; unit-tested.
 * No backslash escapes in this file on purpose (it travels through a JSON push).
 */
object GetUpTrim {
    const val WALK_TICKS_PER_MIN = 10
    const val WALK_SUSTAIN_MIN = 2
    const val SINGLE_MINUTE_TICKS = 40
    /** Back-to-sleep proof: this many minutes in a row at sleeping heart rate with no walking. */
    const val BACK_TO_SLEEP_MIN = 30
    /** Sleeping band: the night's lowest 5-minute heart rate x 1.15, and at least +8 bpm. */
    const val BAND_MULT = 1.15
    const val BAND_MIN_BPM = 8
    /** Only walks at least this long after the night started, and this long before its recorded end. */
    const val MIN_SLEEP_BEFORE_MIN = 60
    const val MIN_TAIL_MIN = 10
    /** The tail must have heart rate for at least half its minutes, or it is not judged. */
    const val MIN_TAIL_HR_COVERAGE = 0.5

    /** Steps per minute, counting walk / run records and records whose activity class is unknown. */
    fun ticksPerMinute(steps: List<StepSample>): Map<Long, Int> {
        val sorted = steps.sortedBy { it.ts }
        if (sorted.size < 2) return emptyMap()
        val out = HashMap<Long, Int>()
        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            if (cur.activityClass == 0) continue
            val delta = (cur.counter - sorted[i - 1].counter) and 0xFFFF
            if (delta > 400) continue // a counter reset or gap, not a minute of walking
            val m = cur.ts / 60
            out[m] = (out[m] ?: 0) + delta
        }
        return out
    }

    fun hrPerMinute(hr: List<HrSample>, from: Long, to: Long): Map<Long, Double> {
        val sums = HashMap<Long, Pair<Double, Int>>()
        for (h in hr) {
            if (h.ts < from || h.ts >= to || h.bpm !in 25..250) continue
            val m = h.ts / 60
            val p = sums[m]
            sums[m] = if (p == null) h.bpm.toDouble() to 1 else (p.first + h.bpm) to (p.second + 1)
        }
        return sums.mapValues { it.value.first / it.value.second }
    }

    /**
     * The minute (unix seconds / 60) the night should end at, or null to leave it alone.
     * [startMin] / [endMin] are the session's first and last minute.
     */
    fun getUpMinute(startMin: Long, endMin: Long, restingHr: Int, hrByMin: Map<Long, Double>, ticksByMin: Map<Long, Int>): Long? {
        if (restingHr <= 0) return null
        val band = max(restingHr * BAND_MULT, (restingHr + BAND_MIN_BPM).toDouble())
        fun ticks(m: Long) = ticksByMin[m] ?: 0
        fun walking(m: Long) = ticks(m) >= SINGLE_MINUTE_TICKS ||
            (0 until WALK_SUSTAIN_MIN).all { ticks(m + it) >= WALK_TICKS_PER_MIN }
        var m = startMin + MIN_SLEEP_BEFORE_MIN
        while (m <= endMin - MIN_TAIL_MIN) {
            if (walking(m)) {
                val tail = (m..endMin)
                val covered = tail.count { hrByMin.containsKey(it) }
                if (covered >= (endMin - m + 1) * MIN_TAIL_HR_COVERAGE) {
                    var run = 0
                    var backToSleep = false
                    for (t in tail) {
                        val h = hrByMin[t]
                        run = if (h != null && h <= band && ticks(t) < WALK_TICKS_PER_MIN) run + 1 else 0
                        if (run >= BACK_TO_SLEEP_MIN) { backToSleep = true; break }
                    }
                    if (!backToSleep) return m
                }
            }
            m++
        }
        return null
    }

    /** The session ended at the get-up, or unchanged. */
    fun trim(s: DetectedSleep, hr: List<HrSample>, steps: List<StepSample>): DetectedSleep {
        val rest = s.restingHR ?: return s
        if (steps.isEmpty() || s.end - s.start < (MIN_SLEEP_BEFORE_MIN + MIN_TAIL_MIN) * 60L) return s
        val inWindow = steps.filter { it.ts >= s.start && it.ts < s.end }
        if (inWindow.size < 10) return s
        val startMin = s.start / 60
        val endMin = (s.end - 1) / 60
        val upMin = getUpMinute(startMin, endMin, rest, hrPerMinute(hr, s.start, s.end), ticksPerMinute(inWindow)) ?: return s
        val newEnd = upMin * 60
        if (newEnd <= s.start || newEnd >= s.end) return s
        val stages = s.stages.filter { it.start < newEnd }.map { StageSegment(it.start, min(it.end, newEnd), it.stage) }
            .filter { it.end > it.start }
        if (stages.none { it.stage != "wake" }) return s
        return s.copy(end = newEnd, stages = stages, efficiency = SleepStager.efficiency(s.start, newEnd, stages))
    }
}

/**
 * Fork addition (Rick): the Rest score with a Disturbances part, and its breakdown for the Rest screen.
 *
 * NOOP's Rest is 50% duration + 20% efficiency + 20% deep and REM + 10% consistency, so a long night maxes
 * half the score however broken it was. NOOP Runner scores nights it computed itself as 40% duration
 * (against 8 hours, as before) + 20% efficiency + 20% deep and REM + 10% consistency + 10% disturbances,
 * where disturbances is full marks for up to 2 wake-ups and loses 12.5 points for each one after that
 * (none left at 10). Imported nights (WHOOP CSV, Health Connect, Oura) keep NOOP's formula, because their
 * "disturbances" field means something else. Pure; unit-tested.
 */
object RunnerRest {
    data class Part(val name: String, val points: Double, val maxPoints: Double, val detail: String)

    const val W_DURATION = 0.40
    const val W_EFFICIENCY = 0.20
    const val W_RESTORATIVE = 0.20
    const val W_CONSISTENCY = 0.10
    const val W_DISTURBANCES = 0.10
    const val NEED_HOURS = 8.0
    const val FREE_WAKEUPS = 2
    const val POINTS_PER_WAKEUP = 12.5

    /** True for nights NOOP computed on the phone (device id ending in -noop) with a wake-up count. */
    fun applies(d: DailyMetric): Boolean =
        d.deviceId.endsWith("-noop") && d.disturbances != null && (d.totalSleepMin ?: 0.0) > 0 && d.efficiency != null

    fun disturbanceScore(wakeUps: Int): Double = (100.0 - POINTS_PER_WAKEUP * max(0, wakeUps - FREE_WAKEUPS)).coerceIn(0.0, 100.0)

    fun parts(d: DailyMetric, consistency: Double? = null): List<Part>? {
        if (!applies(d)) return null
        val tstMin = d.totalSleepMin ?: return null
        val eff = d.efficiency ?: return null
        val asleepS = tstMin * 60.0
        val deepS = (d.deepMin ?: 0.0) * 60.0
        val remS = (d.remMin ?: 0.0) * 60.0
        val hours = tstMin / 60.0
        val dur = min(100.0, hours / NEED_HOURS * 100.0)
        val effS = (eff * 100.0).coerceIn(0.0, 100.0)
        val share = (deepS + remS) / asleepS
        val deepAdequacy = ((deepS / asleepS) / 0.13).coerceIn(0.0, 1.0)
        val deepFactor = 0.5 + 0.5 * deepAdequacy
        val restor = min(100.0, share / 0.50 * 100.0) * deepFactor
        val cons = ((consistency ?: 0.5) * 100.0).coerceIn(0.0, 100.0)
        val wake = d.disturbances ?: 0
        val dist = disturbanceScore(wake)
        fun hm(h: Double): String { val t = (h * 60).roundToInt(); return "${t / 60} h ${t % 60} m" }
        return listOf(
            Part("Duration", W_DURATION * dur, W_DURATION * 100, "${hm(hours)} asleep against an 8 h target"),
            Part("Efficiency", W_EFFICIENCY * effS, W_EFFICIENCY * 100, "${effS.roundToInt()}% of your time in bed was asleep"),
            Part(
                "Deep and REM", W_RESTORATIVE * restor, W_RESTORATIVE * 100,
                "${(share * 100).roundToInt()}% of sleep (full marks at 50%, with deep at least 13%)",
            ),
            Part(
                "Consistency", W_CONSISTENCY * cons, W_CONSISTENCY * 100,
                if (consistency == null) "Not measured yet, so it counts as half" else "How regular your bed and wake times are",
            ),
            Part(
                "Disturbances", W_DISTURBANCES * dist, W_DISTURBANCES * 100,
                "$wake wake-up${if (wake == 1) "" else "s"} (up to $FREE_WAKEUPS cost nothing)",
            ),
        )
    }

    /** The score, rounded like NOOP's (2 decimals), or null when this formula does not apply. */
    fun score(d: DailyMetric, consistency: Double? = null): Double? {
        val p = parts(d, consistency) ?: return null
        return (p.sumOf { it.points } * 100.0).roundToInt() / 100.0
    }
}

/** Fork addition (Rick): small helpers for the Rest screen, read from a night's stored stage timeline. */
object NightStages {
    data class Seg(val start: Long, val end: Long, val stage: String)

    /** Segments from a computed night's stagesJSON (segment array); empty for the minute-total format. */
    fun parse(stagesJson: String?): List<Seg> {
        val s = stagesJson?.trim() ?: return emptyList()
        if (!s.startsWith("[")) return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val st = o.optLong("start")
                val en = o.optLong("end")
                if (en > st) Seg(st, en, o.optString("stage")) else null
            }.sortedBy { it.start }
        }.getOrDefault(emptyList())
    }

    /** Seconds from getting into bed to the first sleep segment, or null without a timeline. */
    fun latencySeconds(bedTs: Long, segs: List<Seg>): Long? {
        val first = segs.firstOrNull { it.stage != "wake" && it.stage != "awake" } ?: return null
        return max(0L, first.start - bedTs)
    }

    /** Things worth flagging about a night, in plain words. */
    fun flags(totalMin: Double, deepMin: Double?, remMin: Double?, efficiency: Double?, wakeUps: Int?, hrOnly: Boolean?): List<String> {
        val out = ArrayList<String>()
        if (totalMin <= 0) return out
        val rem = (remMin ?: 0.0) / totalMin
        val deep = (deepMin ?: 0.0) / totalMin
        if (rem > 0.30) {
            out += "REM is high (${(rem * 100).roundToInt()}%; most adults get 20 to 25%). Quiet time awake, like " +
                "sitting still after getting up, can be scored as REM. If your wake time looks late, fix it in the Sleep tab."
        }
        if (deep < 0.10 && (deepMin ?: 0.0) > 0) out += "Deep sleep is low (${(deep * 100).roundToInt()}%; most adults get 13 to 23%)."
        if (efficiency != null && efficiency < 0.85) out += "You were awake for more than 15% of your time in bed."
        if (wakeUps != null && wakeUps >= 6) out += "$wakeUps wake-ups is a broken night, even if the total time looks fine."
        if (hrOnly == true) out += "This night was staged from heart rate alone (no movement data), so the stages are rougher."
        return out
    }
}
