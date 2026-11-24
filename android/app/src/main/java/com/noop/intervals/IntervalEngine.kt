package com.noop.intervals

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import com.noop.NoopApplication
import com.noop.ble.WhoopConnectionService
import com.noop.location.GpsSession
import com.noop.ui.HapticPrefs
import com.noop.ui.NoopPrefs
import com.noop.ui.UnitPrefs
import com.noop.ui.UnitSystem
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
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Fork addition (Rick): the interval timer engine, held at the PROCESS level (like [GpsSession]) so
 * it keeps running with the screen off or the app in the background. The old timer lived inside a
 * Compose LaunchedEffect and stopped the moment you left the screen, which is no good mid-run.
 *
 * Timing is wall-clock based (start time minus paused time), never "count 1000 ms delays", so a
 * late tick can never drift the schedule. A partial wake lock keeps the CPU awake while a session
 * runs, and the foreground service is started so Android does not kill the process.
 *
 * When [start] is given a linked workout, every completed step records a split: duration, GPS
 * distance (from [GpsSession]) and average / max heart rate (from the strap's live state).
 */
object IntervalEngine {

    data class State(
        val active: Boolean = false,
        val paused: Boolean = false,
        val finished: Boolean = false,
        val program: IntervalProgram? = null,
        val steps: List<TimelineStep> = emptyList(),
        val index: Int = 0,
        val remainingS: Int = 0,
        val elapsedS: Int = 0,
        val totalS: Int = 0,
        /** Start ms of the GPS / HR workout this session belongs to, or null for a standalone timer. */
        val linkedWorkoutStartMs: Long? = null,
        val splits: List<IntervalSplit> = emptyList(),
    ) {
        val current: TimelineStep? get() = steps.getOrNull(index)
        val next: TimelineStep? get() = steps.getOrNull(index + 1)
        val currentStepSeconds: Int get() = current?.seconds ?: 1
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null

    private var appCtx: Context? = null
    private var cues: IntervalCues? = null
    private var settings = CueSettings()
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedService = false

    // Session clock.
    private var startMs = 0L
    private var pausedAtMs: Long? = null
    private var pausedDurationMs = 0L

    // Per-step split accumulators.
    private var stepStartDistanceM = 0.0
    private var stepStartWallMs = 0L
    private var hrSum = 0L
    private var hrCount = 0
    private var hrMax = 0
    private var lastCountdownSecond = -1
    /** Seconds actually run in a step the user skipped, so its split is not credited the full length. */
    private var skippedRanS: Int? = null
    private var halfwayAnnounced = false

    /** Begin [program]. Re-starting while one is active replaces it. */
    fun start(context: Context, program: IntervalProgram, linkedWorkoutStartMs: Long? = null) {
        stop(saveSplits = false)
        val ctx = context.applicationContext
        appCtx = ctx
        settings = CueSettingsStore.load(ctx)
        IntervalProgramStore.setLastUsed(ctx, program.id)
        val steps = program.expand()
        if (steps.isEmpty()) return

        cues = IntervalCues(ctx).also { it.open() }
        // Open-ended sessions have no planned end; hold the CPU for up to 4 hours.
        acquireWakeLock(ctx, if (program.openEnded) 4 * 3600 else program.totalSeconds())
        // Keep the process alive with the screen off. Idempotent if a GPS run already started it.
        WhoopConnectionService.start(ctx)
        startedService = true

        startMs = System.currentTimeMillis()
        pausedAtMs = null
        pausedDurationMs = 0L
        resetStepAccumulators()
        _state.value = State(
            active = true,
            program = program,
            steps = steps,
            index = 0,
            remainingS = steps[0].seconds,
            totalS = if (program.openEnded) 0 else steps.sumOf { it.seconds },
            linkedWorkoutStartMs = linkedWorkoutStartMs,
        )
        // TTS needs a moment to initialise the first time; give the opening call a short head start.
        scope.launch {
            delay(600)
            announceStep(0, previous = null)
        }
        tickJob = scope.launch {
            while (isActive) {
                tick()
                delay(200)
            }
        }
    }

    fun pause() {
        val s = _state.value
        if (!s.active || s.paused || s.finished) return
        pausedAtMs = System.currentTimeMillis()
        _state.value = s.copy(paused = true)
        cues?.speak("Paused")
    }

    fun resume() {
        val s = _state.value
        if (!s.active || !s.paused) return
        pausedAtMs?.let { pausedDurationMs += System.currentTimeMillis() - it }
        pausedAtMs = null
        _state.value = s.copy(paused = false)
        cues?.speak("Resuming")
    }

    fun togglePause() = if (_state.value.paused) resume() else pause()

    /** Jump to the next step (e.g. you finished a rep early). Records the split for the skipped step. */
    fun skip() {
        val s = _state.value
        if (!s.active || s.finished) return
        if (s.paused) return
        val pos = IntervalClock.position(s.steps, activeElapsedMs())
        skippedRanS = s.currentStepSeconds - ceil(pos.remainingMs / 1000.0).toInt()
        // Shift the clock so the next step starts now.
        startMs -= pos.remainingMs
        tick()
    }

    /**
     * End the session. Returns the splits so far (including the step in progress if it ran at least
     * 5 s), and when [saveSplits] is true and the session was linked to a workout, stores them against
     * that workout's start second.
     */
    fun stop(saveSplits: Boolean = true, workoutStartTs: Long? = null): List<IntervalSplit> {
        val s = _state.value
        tickJob?.cancel()
        tickJob = null
        if (!s.active) return emptyList()
        var splits = s.splits
        if (!s.finished) {
            val cur = s.current
            val ranS = (s.currentStepSeconds - s.remainingS)
            if (cur != null && ranS >= 5) splits = splits + buildSplit(s.index, cur, ranS)
        }
        val ctx = appCtx
        val ts = workoutStartTs ?: s.linkedWorkoutStartMs?.div(1000)
        if (saveSplits && ctx != null && ts != null && splits.isNotEmpty()) {
            IntervalSplitStore.save(ctx, IntervalSessionRecord(ts, s.program?.name ?: "Intervals", splits))
        }
        teardown()
        _state.value = State()
        return splits
    }

    // MARK: - internals

    private fun activeElapsedMs(now: Long = System.currentTimeMillis()): Long {
        val pausedNow = pausedAtMs?.let { now - it } ?: 0L
        return now - startMs - pausedDurationMs - pausedNow
    }

    private fun tick() {
        val s = _state.value
        if (!s.active || s.finished) return
        sampleHeartRate()
        if (s.paused) return

        val elapsed = activeElapsedMs()
        val pos = IntervalClock.position(s.steps, elapsed)
        var splits = s.splits

        if (pos.index != s.index) {
            // One or more steps completed since the last tick (normally exactly one).
            for (i in s.index until minOf(pos.index, s.steps.size)) {
                val ran = if (i == s.index) (skippedRanS ?: s.steps[i].seconds) else s.steps[i].seconds
                splits = splits + buildSplit(i, s.steps[i], ran.coerceAtLeast(0))
                resetStepAccumulators()
            }
            skippedRanS = null
            lastCountdownSecond = -1
            halfwayAnnounced = false
            if (pos.finished) {
                _state.value = s.copy(
                    finished = true, index = s.steps.size, remainingS = 0,
                    elapsedS = s.totalS, splits = splits,
                )
                announceFinish(splits)
                // A standalone timer is done; a linked run keeps recording until you press End.
                if (s.linkedWorkoutStartMs == null) releaseResourcesKeepState()
                return
            }
            announceStep(pos.index, previous = splits.lastOrNull())
        }

        val remainingS = ceil(pos.remainingMs / 1000.0).toInt()
        val step = s.steps[pos.index]

        // 3-2-1 countdown on the last seconds of each step (only steps long enough to need it).
        if (settings.countdown && step.seconds >= 6 && remainingS in 1..3 && remainingS != lastCountdownSecond) {
            lastCountdownSecond = remainingS
            if (settings.beeps) cues?.tick()
            strapBuzz(1)
        }
        // Optional halfway call on longer steps.
        if (settings.halfwayCall && settings.voice && !halfwayAnnounced && step.seconds >= 60 &&
            remainingS <= step.seconds / 2
        ) {
            halfwayAnnounced = true
            cues?.speak("Halfway")
        }

        val elapsedS = (elapsed / 1000L).toInt().let { if (s.totalS > 0) it.coerceIn(0, s.totalS) else it.coerceAtLeast(0) }
        if (pos.index != s.index || remainingS != s.remainingS || elapsedS != s.elapsedS || splits !== s.splits) {
            _state.value = s.copy(index = pos.index, remainingS = remainingS, elapsedS = elapsedS, splits = splits)
        }
    }

    private fun sampleHeartRate() {
        val ctx = appCtx ?: return
        val hr = runCatching { (ctx as NoopApplication).ble.state.value.heartRate }.getOrNull() ?: return
        if (hr <= 0 || _state.value.paused) return
        hrSum += hr
        hrCount += 1
        if (hr > hrMax) hrMax = hr
    }

    private fun resetStepAccumulators() {
        stepStartDistanceM = GpsSession.state.value.distanceM
        stepStartWallMs = System.currentTimeMillis()
        hrSum = 0L
        hrCount = 0
        hrMax = 0
    }

    private fun buildSplit(index: Int, step: TimelineStep, durationS: Int): IntervalSplit {
        val gps = GpsSession.state.value
        val dist = if (gps.active && _state.value.linkedWorkoutStartMs != null) {
            (gps.distanceM - stepStartDistanceM).coerceAtLeast(0.0)
        } else {
            null
        }
        return IntervalSplit(
            index = index,
            kind = step.kind,
            name = step.name,
            round = step.round,
            set = step.set,
            durationS = durationS,
            distanceM = dist,
            avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else null,
            maxHr = hrMax.takeIf { it > 0 },
            startMs = stepStartWallMs,
            endMs = System.currentTimeMillis(),
        )
    }

    private fun announceStep(index: Int, previous: IntervalSplit?) {
        val s = _state.value
        val step = s.steps.getOrNull(index) ?: return
        if (settings.beeps) cues?.go()
        strapBuzz(if (step.kind.isEffort) 3 else 1)
        if (settings.phoneVibrate) {
            cues?.vibrate(if (step.kind.isEffort) longArrayOf(0, 300, 150, 300, 150, 300) else longArrayOf(0, 400))
        }
        if (settings.voice) {
            val parts = mutableListOf<String>()
            parts += "${step.name}, ${spokenDuration(step.seconds)}"
            if (step.kind.isEffort && step.rounds == 0 && step.round > 0) {
                parts += "round ${step.round}"
            } else if (step.kind.isEffort && step.rounds > 1) {
                parts += if (step.sets > 1) "round ${step.round} of ${step.rounds}, set ${step.set}"
                else "round ${step.round} of ${step.rounds}"
            }
            // Coming off a hard rep on a GPS run: read back how it went.
            if (previous != null && previous.kind.isEffort) {
                previous.paceSecPerKm?.let { parts += "last interval ${spokenPace(it)}" }
                previous.avgHr?.let { parts += "average heart rate $it" }
            }
            cues?.speak(parts.joinToString(". "))
        }
    }

    private fun announceFinish(splits: List<IntervalSplit>) {
        if (settings.beeps) cues?.finish()
        strapBuzz(5)
        if (settings.phoneVibrate) cues?.vibrate(longArrayOf(0, 800, 200, 800))
        if (settings.voice) {
            val work = splits.filter { it.kind.isEffort }
            val dist = work.mapNotNull { it.distanceM }.sum()
            val msg = StringBuilder("Intervals complete. Nice work.")
            if (dist >= 50 && work.isNotEmpty()) {
                val secs = work.sumOf { it.durationS }
                msg.append(" Average work pace ${spokenPace(secs / (dist / 1000.0))}.")
            }
            cues?.speak(msg.toString())
        }
    }

    private fun strapBuzz(loops: Int) {
        if (!settings.strapBuzz) return
        val ctx = appCtx ?: return
        runCatching {
            val ble = (ctx as NoopApplication).ble
            if (ble.state.value.bonded && HapticPrefs.enabled(ctx, HapticPrefs.INTERVALS)) ble.buzz(loops)
        }
    }

    private fun spokenDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m == 0 -> "$s seconds"
            s == 0 -> if (m == 1) "1 minute" else "$m minutes"
            else -> "${if (m == 1) "1 minute" else "$m minutes"} $s"
        }
    }

    private fun spokenPace(secPerKm: Double): String {
        val ctx = appCtx
        val imperial = ctx != null && UnitPrefs.distanceSystem(ctx) == UnitSystem.IMPERIAL
        val secs = if (imperial) secPerKm * 1.609344 else secPerKm
        val total = secs.roundToInt()
        val unit = if (imperial) "per mile" else "per kilometer"
        val sec = total % 60
        return if (sec == 0) "${total / 60} flat $unit" else "${total / 60} ${String.format("%02d", sec)} $unit"
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(ctx: Context, totalSeconds: Int) {
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noop:intervals").apply {
                setReferenceCounted(false)
                // Generous ceiling so a forgotten session can never hold the CPU forever.
                acquire((totalSeconds + 30 * 60) * 1000L)
            }
        }
    }

    /** Finished standalone session: free audio / CPU / service but keep the DONE state on screen. */
    private fun releaseResourcesKeepState() {
        tickJob?.cancel()
        tickJob = null
        scope.launch {
            delay(6000) // let the finish announcement play out
            val s = _state.value
            if (s.finished && s.linkedWorkoutStartMs == null) teardown()
        }
    }

    private fun teardown() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        cues?.close()
        cues = null
        val ctx = appCtx
        if (startedService && ctx != null && !GpsSession.state.value.active && !NoopPrefs.backgroundConnection(ctx)) {
            WhoopConnectionService.stop(ctx)
        }
        startedService = false
    }

    /** Clear a finished standalone session from the screen. */
    fun dismissFinished() {
        val s = _state.value
        if (s.finished && s.linkedWorkoutStartMs == null) {
            teardown()
            _state.value = State()
        }
    }
}
