package com.noop.intervals

import android.content.Context
import com.noop.data.DailyMetric
import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import com.noop.ui.ProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Fork addition (Rick): runner heart-rate settings.
 *
 * 1. Heart-rate reserve zones (Karvonen). NOOP's zones are fixed fractions of max HR (50/60/70/80/90%),
 *    which ignores how low a trained runner's resting HR is. With this on, each zone starts at
 *    resting + fraction x (max - resting), using the strap's own nightly resting HR (7-day median).
 *    The result is written into NOOP's existing custom-zone thresholds, so every screen that shows or
 *    alerts on zones (live HR, workout detail, zone coaching, the HR alert) uses it with no extra hooks.
 *
 * 2. Max HR from workouts. Instead of the age formula, find the highest heart rate actually HELD for
 *    15 seconds in the past year of workouts (a short average rejects optical spikes) and offer it as
 *    the max HR. Suggestion only: nothing changes until the user taps "Use".
 *
 * Pure logic ([HrReserve], [MaxHrFinder]) is unit-tested; the stores are SharedPreferences JSON.
 */
object HrReserve {
    /** Zone start fractions of heart-rate reserve, zones 1..5 (same bands NOOP uses for % of max). */
    val EDGES = listOf(0.50, 0.60, 0.70, 0.80, 0.90)

    /** Five zone starts (bpm, rounded up), or null when the inputs are not plausible. */
    fun zoneStarts(restingHr: Int, maxHr: Int): List<Int>? {
        if (restingHr !in 30..110 || maxHr !in 100..230 || maxHr - restingHr < 40) return null
        return EDGES.map { ceil(restingHr + it * (maxHr - restingHr) - 1e-9).toInt() }
    }

    /** Median of the most recent [days] plausible resting HR values (input oldest first). */
    fun typicalResting(valuesOldestFirst: List<Int>, days: Int = 7): Int? {
        val recent = valuesOldestFirst.filter { it in 30..110 }.takeLast(days)
        if (recent.isEmpty()) return null
        val s = recent.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else ((s[n / 2 - 1] + s[n / 2]) / 2.0).roundToInt()
    }
}

object MaxHrFinder {
    const val WINDOW_S = 15L
    const val MIN_SAMPLES = 8

    /**
     * Highest average heart rate over any [windowS]-second stretch that holds at least [minSamples]
     * readings (so a gap in the data can't fake a window). Null when no window qualifies.
     */
    fun sustainedPeak(samples: List<Pair<Long, Int>>, windowS: Long = WINDOW_S, minSamples: Int = MIN_SAMPLES): Int? {
        val s = samples.filter { it.second in 40..240 }.sortedBy { it.first }
        if (s.size < minSamples) return null
        var best = -1.0
        var left = 0
        var sum = 0L
        for (right in s.indices) {
            sum += s[right].second
            while (s[right].first - s[left].first >= windowS) {
                sum -= s[left].second
                left++
            }
            val count = right - left + 1
            if (count >= minSamples) best = maxOf(best, sum.toDouble() / count)
        }
        return if (best > 0) best.roundToInt() else null
    }

    data class Peak(val startTs: Long, val bpm: Int, val sport: String)

    /** The best peak among [peaks] that started at or after [sinceTs]. */
    fun best(peaks: List<Peak>, sinceTs: Long): Peak? =
        peaks.filter { it.startTs >= sinceTs && it.bpm > 0 }.maxWithOrNull(compareBy<Peak> { it.bpm }.thenBy { it.startTs })
}

/** Zone-method choice, resting HR and workout peaks (SharedPreferences "runner_hr"). */
object RunnerHrStore {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner_hr", Context.MODE_PRIVATE)

    fun reserveZones(ctx: Context): Boolean = p(ctx).getBoolean("reserveZones", false)
    internal fun setReserveZones(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("reserveZones", on).apply()

    fun restingHr(ctx: Context): Int? = p(ctx).getInt("restingHr", 0).takeIf { it > 0 }
    internal fun setRestingHr(ctx: Context, bpm: Int?) = p(ctx).edit().putInt("restingHr", bpm ?: 0).apply()

    internal fun written(ctx: Context): List<Int>? = ints(p(ctx).getString("written", null))
    internal fun setWritten(ctx: Context, v: List<Int>?) = p(ctx).edit().putString("written", v?.joinToString(",")).apply()

    internal fun previous(ctx: Context): List<Int>? = ints(p(ctx).getString("previous", null))
    internal fun setPrevious(ctx: Context, v: List<Int>?) = p(ctx).edit().putString("previous", v?.joinToString(",")).apply()

    private fun ints(s: String?): List<Int>? = s?.split(",")?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 5 }

    /** Workout peaks by start second. bpm 0 = scanned, no usable heart rate (so it isn't rescanned). */
    fun peaks(ctx: Context): List<MaxHrFinder.Peak> {
        val o = runCatching { JSONObject(p(ctx).getString("peaks", "{}") ?: "{}") }.getOrNull() ?: return emptyList()
        return o.keys().asSequence().mapNotNull { k ->
            val v = o.optJSONObject(k) ?: return@mapNotNull null
            k.toLongOrNull()?.let { MaxHrFinder.Peak(it, v.optInt("bpm"), v.optString("sport")) }
        }.toList()
    }

    internal fun putPeaks(ctx: Context, add: List<MaxHrFinder.Peak>) {
        if (add.isEmpty()) return
        val o = runCatching { JSONObject(p(ctx).getString("peaks", "{}") ?: "{}") }.getOrNull() ?: JSONObject()
        add.forEach { o.put(it.startTs.toString(), JSONObject().put("bpm", it.bpm).put("sport", it.sport)) }
        p(ctx).edit().putString("peaks", o.toString()).apply()
    }
}

/** Keeps NOOP's zone thresholds in step with resting and max HR while reserve zones are on. */
object RunnerHrSync {

    /** Turn heart-rate reserve zones on or off. Returns false when there is no resting HR yet. */
    fun setEnabled(ctx: Context, profile: ProfileStore, on: Boolean): Boolean {
        if (on) {
            val rest = RunnerHrStore.restingHr(ctx) ?: return false
            val starts = HrReserve.zoneStarts(rest, profile.hrMax) ?: return false
            if (!RunnerHrStore.reserveZones(ctx)) RunnerHrStore.setPrevious(ctx, profile.hrZoneThresholds)
            profile.hrZoneThresholds = starts
            RunnerHrStore.setWritten(ctx, starts)
            RunnerHrStore.setReserveZones(ctx, true)
        } else {
            if (RunnerHrStore.reserveZones(ctx)) profile.hrZoneThresholds = RunnerHrStore.previous(ctx)
            RunnerHrStore.setPrevious(ctx, null)
            RunnerHrStore.setWritten(ctx, null)
            RunnerHrStore.setReserveZones(ctx, false)
        }
        return true
    }

    /**
     * Rewrite the thresholds when resting or max HR moved. If the user changed the zones by hand (or
     * switched NOOP's custom zones off), their edit wins and reserve zones turn off. Returns true when
     * anything changed, so a screen can refresh.
     */
    fun sync(ctx: Context, profile: ProfileStore): Boolean {
        if (!RunnerHrStore.reserveZones(ctx)) return false
        val current = profile.hrZoneThresholds
        val written = RunnerHrStore.written(ctx)
        if (written != null && current != written) {
            RunnerHrStore.setPrevious(ctx, null)
            RunnerHrStore.setWritten(ctx, null)
            RunnerHrStore.setReserveZones(ctx, false)
            return true
        }
        val rest = RunnerHrStore.restingHr(ctx) ?: return false
        val starts = HrReserve.zoneStarts(rest, profile.hrMax) ?: return false
        if (starts == current) return false
        profile.hrZoneThresholds = starts
        RunnerHrStore.setWritten(ctx, starts)
        return true
    }

    /** Record the typical resting HR from the daily rows (any order) and re-sync. */
    fun onDays(ctx: Context, profile: ProfileStore, days: List<DailyMetric>): Boolean {
        val rest = HrReserve.typicalResting(days.sortedBy { it.day }.mapNotNull { it.restingHr }) ?: return false
        if (rest != RunnerHrStore.restingHr(ctx)) RunnerHrStore.setRestingHr(ctx, rest)
        return sync(ctx, profile)
    }

    /** App start: wait for the first daily rows, then sync. */
    suspend fun startup(ctx: Context, profile: ProfileStore, days: Flow<List<DailyMetric>>) {
        runCatching { onDays(ctx, profile, days.first { list -> list.any { it.restingHr != null } }) }
    }
}

/** Scans workouts for their sustained peak heart rate (once each), for the max HR suggestion. */
object MaxHrScan {
    private const val YEAR_S = 365L * 86_400L

    suspend fun run(
        ctx: Context,
        workouts: suspend (fromTs: Long, toTs: Long) -> List<WorkoutRow>,
        hr: suspend (row: WorkoutRow) -> List<HrSample>,
    ) {
        val now = System.currentTimeMillis() / 1000
        val known = RunnerHrStore.peaks(ctx).map { it.startTs }.toHashSet()
        val todo = workouts(now - YEAR_S, now)
            .filter { it.startTs !in known && it.endTs > it.startTs && it.endTs - it.startTs < 6 * 3600 }
            .distinctBy { it.startTs }
        val found = ArrayList<MaxHrFinder.Peak>()
        for (row in todo) {
            val samples = runCatching { hr(row) }.getOrDefault(emptyList())
            val peak = MaxHrFinder.sustainedPeak(samples.map { it.ts to it.bpm }) ?: 0
            found.add(MaxHrFinder.Peak(row.startTs, peak, row.sport))
            if (found.size >= 25) { RunnerHrStore.putPeaks(ctx, found); found.clear() }
        }
        RunnerHrStore.putPeaks(ctx, found)
    }

    /** The best sustained peak in the past year, or null. */
    fun best(ctx: Context): MaxHrFinder.Peak? =
        MaxHrFinder.best(RunnerHrStore.peaks(ctx), System.currentTimeMillis() / 1000 - YEAR_S)
}

/**
 * How a workout NOOP Runner recorded got its numbers (calorie method, GPS distance), by start second,
 * for the "How these numbers were worked out" note. Removed when the workout is edited by hand, since
 * the note would no longer be true. SharedPreferences "runner_method".
 */
object RunMethodStore {
    data class Method(val calories: String?, val gpsDistance: Boolean)

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner_method", Context.MODE_PRIVATE)

    fun save(ctx: Context, startTs: Long, calories: String?, gpsDistance: Boolean) {
        val o = JSONObject().put("gps", gpsDistance)
        if (calories != null) o.put("calories", calories)
        p(ctx).edit().putString(startTs.toString(), o.toString()).apply()
    }

    fun load(ctx: Context, startTs: Long): Method? {
        val s = p(ctx).getString(startTs.toString(), null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            Method(if (o.has("calories")) o.getString("calories") else null, o.optBoolean("gps"))
        }.getOrNull()
    }

    fun remove(ctx: Context, startTs: Long) = p(ctx).edit().remove(startTs.toString()).apply()
}
