package com.noop.intervals

import android.content.Context
import com.noop.NoopApplication
import com.noop.ui.HapticPrefs
import com.noop.ui.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fork addition (Rick): pure "stay below this heart rate" decision logic. Feed it one reading at a
 * time; it says when to warn and when you are back under. Ignores brief spikes (must stay above for
 * [sustainMs]), repeats the warning every [repeatMs] while you stay high, and only calls "back in
 * zone" once you have been under for [recoverMs]. Unit-tested.
 */
class HrCapMonitor(
    val capBpm: Int,
    private val sustainMs: Long = 15_000,
    private val repeatMs: Long = 45_000,
    private val recoverMs: Long = 10_000,
) {
    enum class Event { HIGH, BACK_UNDER }

    private var aboveSinceMs: Long? = null
    private var belowSinceMs: Long? = null
    private var lastAlertMs: Long? = null
    var alerting = false
        private set

    fun feed(bpm: Int, nowMs: Long): Event? {
        if (bpm <= 0) return null
        if (bpm > capBpm) {
            belowSinceMs = null
            val since = aboveSinceMs ?: nowMs.also { aboveSinceMs = it }
            if (nowMs - since < sustainMs) return null
            val last = lastAlertMs
            if (!alerting || last == null || nowMs - last >= repeatMs) {
                alerting = true
                lastAlertMs = nowMs
                return Event.HIGH
            }
            return null
        }
        aboveSinceMs = null
        if (!alerting) return null
        val since = belowSinceMs ?: nowMs.also { belowSinceMs = it }
        if (nowMs - since >= recoverMs) {
            alerting = false
            lastAlertMs = null
            belowSinceMs = null
            return Event.BACK_UNDER
        }
        return null
    }
}

/**
 * Fork addition (Rick): pure "stay above this heart rate" decision logic, the floor to go with the
 * ceiling. It stays quiet while you warm up (the first [warmupMs] of moving time), during interval
 * rest, walk, set-rest and cool-down steps ([resting]), and for dips shorter than [sustainMs]. Once you
 * have been under the floor that long it says LOW, then waits [repeatMs] before saying it again, and
 * says BACK_UP after [recoverMs] at or above the floor. Unit-tested.
 */
class HrFloorMonitor(
    val floorBpm: Int,
    val warmupMs: Long = 300_000,
    private val sustainMs: Long = 30_000,
    private val repeatMs: Long = 60_000,
    private val recoverMs: Long = 10_000,
) {
    enum class Event { LOW, BACK_UP }

    private var belowSinceMs: Long? = null
    private var aboveSinceMs: Long? = null
    private var lastAlertMs: Long? = null
    var alerting = false
        private set

    /** True while the floor is not being watched (warm-up or a rest step). */
    fun held(activeMs: Long, resting: Boolean): Boolean = resting || activeMs < warmupMs

    /** [activeMs] is the workout's moving time so far (pauses excluded). */
    fun feed(bpm: Int, nowMs: Long, activeMs: Long, resting: Boolean): Event? {
        if (bpm <= 0) return null
        if (held(activeMs, resting)) {
            // Start fresh after the hold, so a rest step's low heart rate never counts toward an alert.
            belowSinceMs = null
            aboveSinceMs = null
            lastAlertMs = null
            alerting = false
            return null
        }
        if (bpm < floorBpm) {
            aboveSinceMs = null
            val since = belowSinceMs ?: nowMs.also { belowSinceMs = it }
            if (nowMs - since < sustainMs) return null
            val last = lastAlertMs
            if (!alerting || last == null || nowMs - last >= repeatMs) {
                alerting = true
                lastAlertMs = nowMs
                return Event.LOW
            }
            return null
        }
        belowSinceMs = null
        if (!alerting) return null
        val since = aboveSinceMs ?: nowMs.also { aboveSinceMs = it }
        if (nowMs - since >= recoverMs) {
            alerting = false
            lastAlertMs = null
            aboveSinceMs = null
            return Event.BACK_UP
        }
        return null
    }
}

/**
 * Fork addition (Rick): time spent below, inside and above the heart-rate range during a workout, kept
 * per workout start (seconds) and shown on the saved workout. SharedPreferences "runner_hr_range".
 */
object HrRangeStore {
    /** [floor] or [cap] is 0 when that side was not set. */
    data class Range(val floor: Int, val cap: Int, val belowS: Int, val inS: Int, val aboveS: Int) {
        val totalS: Int get() = belowS + inS + aboveS
    }

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner_hr_range", Context.MODE_PRIVATE)

    fun save(ctx: Context, startTs: Long, r: Range) {
        val o = org.json.JSONObject().put("floor", r.floor).put("cap", r.cap)
            .put("below", r.belowS).put("in", r.inS).put("above", r.aboveS)
        p(ctx).edit().putString(startTs.toString(), o.toString()).apply()
    }

    fun load(ctx: Context, startTs: Long): Range? {
        val s = p(ctx).getString(startTs.toString(), null) ?: return null
        return runCatching {
            val o = org.json.JSONObject(s)
            Range(o.optInt("floor"), o.optInt("cap"), o.optInt("below"), o.optInt("in"), o.optInt("above"))
        }.getOrNull()
    }

    /** Which side of the range a reading is on: -1 below, 0 inside, 1 above. */
    fun side(bpm: Int, floor: Int, cap: Int): Int = when {
        floor > 0 && bpm < floor -> -1
        cap > 0 && bpm > cap -> 1
        else -> 0
    }
}

/** Saved choice for the start screen: which zone to stay below (0 = off, [CUSTOM] = a set bpm). */
object HrCapPrefs {
    /** Zone code meaning "stay below [customBpm]" rather than the top of a zone. */
    const val CUSTOM = 9

    private const val NAME = "fork_hr_cap"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun zone(ctx: Context): Int = p(ctx).getInt("zone", 0)
    fun setZone(ctx: Context, zone: Int) = p(ctx).edit().putInt("zone", zone).apply()

    fun customBpm(ctx: Context): Int = p(ctx).getInt("customBpm", 150)
    fun setCustomBpm(ctx: Context, bpm: Int) = p(ctx).edit().putInt("customBpm", bpm.coerceIn(80, 220)).apply()

    /** Target average heart rate for the workout, 0 = off. Read out every 10 minutes. */
    fun targetAvg(ctx: Context): Int = p(ctx).getInt("targetAvg", 0)
    fun setTargetAvg(ctx: Context, bpm: Int) = p(ctx).edit().putInt("targetAvg", bpm.coerceIn(0, 220)).apply()

    /** Floor: 0 = off, [CUSTOM] = stay above [floorCustomBpm], 1..5 = stay above the bottom of that zone. */
    fun floorZone(ctx: Context): Int = p(ctx).getInt("floorZone", 0)
    fun setFloorZone(ctx: Context, zone: Int) = p(ctx).edit().putInt("floorZone", zone).apply()

    fun floorCustomBpm(ctx: Context): Int = p(ctx).getInt("floorBpm", 120)
    fun setFloorCustomBpm(ctx: Context, bpm: Int) = p(ctx).edit().putInt("floorBpm", bpm.coerceIn(60, 200)).apply()

    /** Minutes of moving time before the floor is watched (warm-up). Default 5. */
    fun warmupMin(ctx: Context): Int = p(ctx).getInt("warmupMin", 5)
    fun setWarmupMin(ctx: Context, min: Int) = p(ctx).edit().putInt("warmupMin", min.coerceIn(0, 15)).apply()

    /** True when any part of the alert is on (ceiling [zone], target average or floor). */
    fun anyOn(ctx: Context, zone: Int): Boolean = zone > 0 || targetAvg(ctx) > 0 || floorZone(ctx) > 0

    /** The floor in bpm for [zone] (bottom of the zone, or the custom number), or null when off / unknown. */
    fun floorBpm(ctx: Context, zone: Int): Int? {
        if (zone == CUSTOM) return floorCustomBpm(ctx)
        if (zone !in 1..5) return null
        return ProfileStore.from(ctx.applicationContext).hrZoneSet.zones
            .firstOrNull { it.number == zone }?.lower?.toInt()
    }

    /** The ceiling in bpm for [zone] (top of the zone, or the custom number), or null when off / unknown. */
    fun capBpm(ctx: Context, zone: Int): Int? {
        if (zone == CUSTOM) return customBpm(ctx)
        if (zone !in 1..4) return null
        return ProfileStore.from(ctx.applicationContext).hrZoneSet.zones
            .firstOrNull { it.number == zone }?.upper?.toInt()
    }
}

/**
 * Fork addition (Rick): process-level heart-rate ceiling alert for a running workout. Reads the
 * strap's live heart rate once a second and, when you sit above the top of the chosen zone, says
 * "Heart rate 156, slow down", buzzes the strap and beeps. Says "Back in zone 2" once you drop under.
 * Lives outside the UI so it works with the screen off, like [IntervalEngine].
 */
object HrZoneGuard {
    data class State(
        val active: Boolean = false,
        val zone: Int = 0,
        val capBpm: Int = 0,
        val linkedWorkoutStartMs: Long? = null,
        val alerting: Boolean = false,
        val paused: Boolean = false,
        /** Running average of the workout's heart rate so far (0 until the first reading). */
        val avgBpm: Int = 0,
        /** Target average, 0 = none. */
        val targetAvg: Int = 0,
        /** Floor in bpm, 0 = none, and its zone code (as in [HrCapPrefs.floorZone]). */
        val floorBpm: Int = 0,
        val floorZone: Int = 0,
        /** True while below the floor long enough to warn. */
        val lowAlerting: Boolean = false,
        /** Seconds of warm-up left before the floor is watched (0 once it is). */
        val warmupLeftS: Int = 0,
        /** True while an interval rest or walk step holds the floor. */
        val floorResting: Boolean = false,
    ) {
        val isCustom: Boolean get() = zone == HrCapPrefs.CUSTOM
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var cues: IntervalCues? = null
    private var appCtx: Context? = null

    fun start(context: Context, zone: Int, linkedWorkoutStartMs: Long) {
        stop()
        val ctx = context.applicationContext
        appCtx = ctx
        val cap = HrCapPrefs.capBpm(ctx, zone)
        val target = HrCapPrefs.targetAvg(ctx)
        val floorZone = HrCapPrefs.floorZone(ctx)
        // A floor at or above the ceiling cannot be met; the start screen says so, and it is ignored here.
        val floor = HrCapPrefs.floorBpm(ctx, floorZone)?.takeIf { f -> cap == null || f < cap }
        if (cap == null && target <= 0 && floor == null) return
        val monitor = cap?.let { HrCapMonitor(it) }
        val warmupS = HrCapPrefs.warmupMin(ctx) * 60
        val floorMonitor = floor?.let { HrFloorMonitor(it, warmupMs = warmupS * 1000L) }
        val settings = CueSettingsStore.load(ctx)
        cues = IntervalCues(ctx).also { it.open() }
        _state.value = State(
            active = true, zone = if (cap != null) zone else 0, capBpm = cap ?: 0,
            linkedWorkoutStartMs = linkedWorkoutStartMs, targetAvg = target,
            floorBpm = floor ?: 0, floorZone = if (floor != null) floorZone else 0,
            warmupLeftS = if (floor != null) warmupS else 0,
        )
        val startTs = linkedWorkoutStartMs / 1000
        rangeKey = startTs
        job = scope.launch {
            delay(1500)
            if (settings.voice && !IntervalEngine.state.value.active) {
                val parts = mutableListOf<String>()
                when {
                    cap != null && floor != null -> parts += "Heart rate alert on. Stay between $floor and $cap"
                    cap != null -> parts += "Heart rate alert on. Stay below $cap"
                    floor != null -> parts += "Heart rate alert on. Stay above $floor"
                }
                if (floor != null && warmupS > 0) parts += "The low alert starts after ${warmupS / 60} minutes"
                if (target > 0) parts += "Target average $target"
                cues?.speak(parts.joinToString(". "))
            }
            var sum = 0L
            var count = 0
            var activeSeconds = 0
            var below = 0
            var inside = 0
            var above = 0
            while (isActive) {
                delay(1000)
                if (_state.value.paused) continue
                val ble = runCatching { (ctx as NoopApplication).ble }.getOrNull() ?: continue
                val hr = WorkoutStrap.bpmOr(ble.state.value.heartRate) ?: continue // chest strap first when connected
                if (hr > 0) { sum += hr; count++ }
                activeSeconds++
                // Time in range, saved every 15 s so it survives the app being closed.
                if (hr > 0 && (cap != null || floor != null)) {
                    when (HrRangeStore.side(hr, floor ?: 0, cap ?: 0)) {
                        -1 -> below++
                        1 -> above++
                        else -> inside++
                    }
                    val r = HrRangeStore.Range(floor ?: 0, cap ?: 0, below, inside, above)
                    lastRange = r
                    if (activeSeconds % 15 == 0) HrRangeStore.save(ctx, startTs, r)
                }
                // Floor: quiet during the warm-up and interval rest / walk steps.
                if (floorMonitor != null) {
                    val iv = IntervalEngine.state.value
                    val resting = iv.active && iv.current?.kind?.let { it != StepKind.WORK } == true
                    val activeMs = activeSeconds * 1000L
                    when (floorMonitor.feed(hr, System.currentTimeMillis(), activeMs, resting)) {
                        HrFloorMonitor.Event.LOW -> {
                            if (settings.beeps) cues?.tick()
                            if (settings.voice) cues?.speak("Heart rate $hr. Pick it up")
                            // One long buzz, unlike the ceiling's two short ones.
                            if (settings.phoneVibrate) cues?.vibrate(longArrayOf(0, 700))
                            if (settings.strapBuzz && ble.state.value.bonded &&
                                HapticPrefs.enabled(ctx, HapticPrefs.INTERVALS)
                            ) ble.buzz(1)
                        }
                        HrFloorMonitor.Event.BACK_UP -> if (settings.voice) cues?.speak("Back above $floor")
                        null -> {}
                    }
                    val left = (warmupS - activeSeconds).coerceAtLeast(0)
                    val st = _state.value
                    if (st.lowAlerting != floorMonitor.alerting || st.warmupLeftS != left || st.floorResting != resting) {
                        _state.value = st.copy(lowAlerting = floorMonitor.alerting, warmupLeftS = left, floorResting = resting)
                    }
                }
                val avg = if (count > 0) (sum / count).toInt() else 0
                if (avg != _state.value.avgBpm) _state.value = _state.value.copy(avgBpm = avg)
                // Every 10 minutes, read the running average against the target.
                if (target > 0 && settings.voice && avg > 0 && activeSeconds % 600 == 0) {
                    cues?.speak("Average heart rate $avg, target $target")
                }
                if (monitor == null) continue
                when (monitor.feed(hr, System.currentTimeMillis())) {
                    HrCapMonitor.Event.HIGH -> {
                        if (settings.beeps) cues?.tick()
                        if (settings.voice) cues?.speak("Heart rate $hr. Slow down")
                        if (settings.phoneVibrate) cues?.vibrate(longArrayOf(0, 250, 120, 250))
                        if (settings.strapBuzz && ble.state.value.bonded &&
                            HapticPrefs.enabled(ctx, HapticPrefs.INTERVALS)
                        ) ble.buzz(2)
                    }
                    HrCapMonitor.Event.BACK_UNDER -> {
                        if (settings.voice) {
                            cues?.speak(if (zone == HrCapPrefs.CUSTOM) "Back under $cap" else "Back in zone $zone")
                        }
                    }
                    null -> {}
                }
                if (monitor.alerting != _state.value.alerting) {
                    _state.value = _state.value.copy(alerting = monitor.alerting)
                }
            }
        }
    }

    fun setPaused(paused: Boolean) {
        if (_state.value.active) _state.value = _state.value.copy(paused = paused)
    }

    private var rangeKey: Long? = null
    private var lastRange: HrRangeStore.Range? = null

    fun stop() {
        job?.cancel()
        job = null
        // Keep the final time-in-range for the saved workout.
        val key = rangeKey
        val r = lastRange
        if (key != null && r != null && appCtx != null) runCatching { HrRangeStore.save(appCtx!!, key, r) }
        rangeKey = null
        lastRange = null
        cues?.close()
        cues = null
        _state.value = State()
    }
}
