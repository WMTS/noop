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

/** Saved choice for the start screen: which zone to stay below (0 = off). */
object HrCapPrefs {
    private const val NAME = "fork_hr_cap"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun zone(ctx: Context): Int = p(ctx).getInt("zone", 0)
    fun setZone(ctx: Context, zone: Int) = p(ctx).edit().putInt("zone", zone).apply()

    /** Top of [zone] in bpm from the user's own zone settings, or null when off / unknown. */
    fun capBpm(ctx: Context, zone: Int): Int? {
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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var cues: IntervalCues? = null

    fun start(context: Context, zone: Int, linkedWorkoutStartMs: Long) {
        stop()
        val ctx = context.applicationContext
        val cap = HrCapPrefs.capBpm(ctx, zone) ?: return
        val monitor = HrCapMonitor(cap)
        val settings = CueSettingsStore.load(ctx)
        cues = IntervalCues(ctx).also { it.open() }
        _state.value = State(active = true, zone = zone, capBpm = cap, linkedWorkoutStartMs = linkedWorkoutStartMs)
        job = scope.launch {
            delay(1500)
            if (settings.voice && !IntervalEngine.state.value.active) {
                cues?.speak("Heart rate alert on. Stay below $cap")
            }
            while (isActive) {
                delay(1000)
                if (_state.value.paused) continue
                val ble = runCatching { (ctx as NoopApplication).ble }.getOrNull() ?: continue
                val hr = ble.state.value.heartRate ?: continue
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
                        if (settings.voice) cues?.speak("Back in zone $zone")
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
