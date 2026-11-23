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
    ) {
        val isCustom: Boolean get() = zone == HrCapPrefs.CUSTOM
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var cues: IntervalCues? = null

    fun start(context: Context, zone: Int, linkedWorkoutStartMs: Long) {
        stop()
        val ctx = context.applicationContext
        val cap = HrCapPrefs.capBpm(ctx, zone)
        val target = HrCapPrefs.targetAvg(ctx)
        if (cap == null && target <= 0) return
        val monitor = cap?.let { HrCapMonitor(it) }
        val settings = CueSettingsStore.load(ctx)
        cues = IntervalCues(ctx).also { it.open() }
        _state.value = State(
            active = true, zone = if (cap != null) zone else 0, capBpm = cap ?: 0,
            linkedWorkoutStartMs = linkedWorkoutStartMs, targetAvg = target,
        )
        job = scope.launch {
            delay(1500)
            if (settings.voice && !IntervalEngine.state.value.active) {
                val parts = mutableListOf<String>()
                if (cap != null) parts += "Heart rate alert on. Stay below $cap"
                if (target > 0) parts += "Target average $target"
                cues?.speak(parts.joinToString(". "))
            }
            var sum = 0L
            var count = 0
            var activeSeconds = 0
            while (isActive) {
                delay(1000)
                if (_state.value.paused) continue
                val ble = runCatching { (ctx as NoopApplication).ble }.getOrNull() ?: continue
                val hr = ble.state.value.heartRate ?: continue
                if (hr > 0) { sum += hr; count++ }
                activeSeconds++
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

    fun stop() {
        job?.cancel()
        job = null
        cues?.close()
        cues = null
        _state.value = State()
    }
}
