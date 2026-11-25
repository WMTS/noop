package com.noop.intervals

import android.content.Context
import com.noop.NoopApplication
import com.noop.ble.StandardHrSource
import com.noop.data.InsertCounts
import com.noop.data.PairedDeviceRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fork addition (Rick): a chest strap for workouts, alongside the WHOOP.
 *
 * NOOP runs one live device at a time: making a chest strap the active device disconnects the WHOOP. With
 * this, the WHOOP stays the active device (sleep, recovery, strain keep working) and the chosen strap is
 * connected only during a workout, through NOOP's own standard Bluetooth heart rate reader
 * ([StandardHrSource]), so any Bluetooth chest strap works: Polar H10/H9/H7, Verity Sense, Garmin HRM,
 * Wahoo TICKR and so on. While the strap is sending, the workout's heart rate (live screen, saved samples,
 * HR alert, interval splits) comes from the strap; if it drops out for 5 seconds the WHOOP takes over
 * again. Strap readings are not written to the strap's own history, so day scores are unchanged.
 */
object WorkoutStrap {
    data class State(
        val running: Boolean = false,
        val name: String = "",
        val bpm: Int? = null,
        val lastMs: Long = 0L,
        val samples: Int = 0,
    )

    private const val FRESH_MS = 5_000L
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var source: StandardHrSource? = null
    private var sink: ((Int) -> Unit)? = null

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner_strap", Context.MODE_PRIVATE)

    fun selectedId(ctx: Context): String? = p(ctx).getString("strapId", null)
    fun selectedName(ctx: Context): String = p(ctx).getString("strapName", "") ?: ""
    fun select(ctx: Context, row: PairedDeviceRow?) {
        p(ctx).edit().apply {
            if (row == null) { remove("strapId"); remove("strapName"); remove("strapAddress") }
            else { putString("strapId", row.id); putString("strapName", row.nickname ?: row.model); putString("strapAddress", row.peripheralId) }
        }.apply()
    }

    /** Paired straps that can feed a workout: any standard Bluetooth heart rate strap that is not a WHOOP. */
    fun candidates(rows: List<PairedDeviceRow>): List<PairedDeviceRow> =
        rows.filter { !it.brand.equals("WHOOP", ignoreCase = true) && it.sourceKind == "liveBLE" && !it.peripheralId.isNullOrBlank() && "hr" in it.capabilities }

    /** Connect the chosen strap for the workout starting now; [onBpm] gets each reading on the main thread. */
    @Synchronized
    fun startForWorkout(ctx: Context, onBpm: (Int) -> Unit) {
        val app = ctx.applicationContext
        val id = selectedId(app) ?: return
        val address = p(app).getString("strapAddress", null) ?: return
        // If the strap is already NOOP's active device it is feeding live heart rate anyway.
        if ((app as? NoopApplication)?.activeDeviceId == id) return
        sink = onBpm
        connect(app, id, address)
    }

    /** Connect without a workout (the "Test" button in Settings). */
    @Synchronized
    fun test(ctx: Context) {
        val app = ctx.applicationContext
        val id = selectedId(app) ?: return
        val address = p(app).getString("strapAddress", null) ?: return
        sink = null
        connect(app, id, address)
    }

    private fun connect(app: Context, id: String, address: String) {
        stopSource()
        val src = StandardHrSource(
            context = app,
            deviceId = id,
            liveSink = { hr, _ -> onHr(hr) },
            // Not stored: the strap is a workout-only sensor here, so its history and day scores stay untouched.
            persist = { _, _, done -> done(Result.success(InsertCounts())) },
        )
        source = src
        _state.value = State(running = true, name = selectedName(app))
        runCatching { src.connect(address) }.onFailure { stop() }
    }

    private fun onHr(hr: Int) {
        if (hr !in 25..250) return
        val s = _state.value
        if (!s.running) return
        _state.value = s.copy(bpm = hr, lastMs = System.currentTimeMillis(), samples = s.samples + 1)
        sink?.invoke(hr)
    }

    /** True while the strap has sent a reading in the last 5 seconds. */
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = _state.value
        return s.running && s.bpm != null && nowMs - s.lastMs < FRESH_MS
    }

    /** True while the strap is feeding a workout (fresh and connected for one, not just a test). */
    fun feedingWorkout(): Boolean = sink != null && isFresh()

    /** The strap's heart rate when fresh, else [fallback] (the WHOOP's). */
    fun bpmOr(fallback: Int?): Int? = if (isFresh()) _state.value.bpm else fallback

    /** Stop and report whether the strap supplied any heart rate during this session. */
    @Synchronized
    fun stop(): Boolean {
        val used = _state.value.samples > 0
        stopSource()
        sink = null
        _state.value = State()
        return used
    }

    private fun stopSource() {
        runCatching { source?.stop() }
        source = null
    }
}
