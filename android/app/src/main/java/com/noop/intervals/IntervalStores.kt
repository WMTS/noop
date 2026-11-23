package com.noop.intervals

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Fork addition (Rick): on-device storage for interval programs, cue settings and the per-interval
// splits of recorded runs. Plain SharedPreferences + JSON so nothing touches the Room schema, which
// keeps this fork easy to rebase onto upstream NOOP.

object IntervalProgramStore {
    private const val NAME = "fork_interval_programs"
    private const val KEY_PROGRAMS = "programs"
    private const val KEY_LAST = "lastProgramId"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** The user's saved programs (built-in presets are not stored here). */
    fun saved(ctx: Context): List<IntervalProgram> {
        val raw = p(ctx).getString(KEY_PROGRAMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { IntervalProgram.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Saved programs first (most relevant), then the presets. */
    fun all(ctx: Context): List<IntervalProgram> = saved(ctx) + IntervalProgram.presets

    fun find(ctx: Context, id: String?): IntervalProgram? = id?.let { i -> all(ctx).firstOrNull { it.id == i } }

    fun upsert(ctx: Context, program: IntervalProgram) {
        val list = saved(ctx).filterNot { it.id == program.id } + program.copy(builtIn = false)
        write(ctx, list)
    }

    fun delete(ctx: Context, id: String) = write(ctx, saved(ctx).filterNot { it.id == id })

    private fun write(ctx: Context, list: List<IntervalProgram>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        p(ctx).edit().putString(KEY_PROGRAMS, arr.toString()).apply()
    }

    fun lastUsedId(ctx: Context): String? = p(ctx).getString(KEY_LAST, null)
    fun setLastUsed(ctx: Context, id: String) = p(ctx).edit().putString(KEY_LAST, id).apply()

    fun newId(): String = "user." + System.currentTimeMillis().toString(36)
}

/** How the timer talks to you. Voice and beeps work with no strap, under music (it ducks it). */
data class CueSettings(
    val voice: Boolean = true,
    val beeps: Boolean = true,
    val countdown: Boolean = true,
    val phoneVibrate: Boolean = false,
    val strapBuzz: Boolean = true,
    val halfwayCall: Boolean = false,
)

object CueSettingsStore {
    private const val NAME = "fork_interval_cues"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): CueSettings {
        val s = p(ctx)
        return CueSettings(
            voice = s.getBoolean("voice", true),
            beeps = s.getBoolean("beeps", true),
            countdown = s.getBoolean("countdown", true),
            phoneVibrate = s.getBoolean("phoneVibrate", false),
            strapBuzz = s.getBoolean("strapBuzz", true),
            halfwayCall = s.getBoolean("halfway", false),
        )
    }

    fun save(ctx: Context, c: CueSettings) {
        p(ctx).edit()
            .putBoolean("voice", c.voice)
            .putBoolean("beeps", c.beeps)
            .putBoolean("countdown", c.countdown)
            .putBoolean("phoneVibrate", c.phoneVibrate)
            .putBoolean("strapBuzz", c.strapBuzz)
            .putBoolean("halfway", c.halfwayCall)
            .apply()
    }
}

/** One completed step of a recorded session: how long, how far, how fast, how hard. */
data class IntervalSplit(
    val index: Int,
    val kind: StepKind,
    val name: String,
    val round: Int,
    val set: Int,
    val durationS: Int,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
) {
    /** Seconds per km, or null when there was no usable distance. */
    val paceSecPerKm: Double?
        get() = distanceM?.takeIf { it >= 20.0 && durationS > 0 }?.let { durationS / (it / 1000.0) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("i", index); put("k", kind.name); put("n", name); put("r", round); put("s", set)
        put("d", durationS)
        distanceM?.let { put("m", it) }
        avgHr?.let { put("hr", it) }
        maxHr?.let { put("mx", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): IntervalSplit? = runCatching {
            IntervalSplit(
                index = o.getInt("i"),
                kind = StepKind.valueOf(o.getString("k")),
                name = o.optString("n", ""),
                round = o.optInt("r", 0),
                set = o.optInt("s", 0),
                durationS = o.optInt("d", 0),
                distanceM = if (o.has("m")) o.getDouble("m") else null,
                avgHr = if (o.has("hr")) o.getInt("hr") else null,
                maxHr = if (o.has("mx")) o.getInt("mx") else null,
            )
        }.getOrNull()
    }
}

/** A recorded session's splits, saved against the workout's start second (WorkoutRow.startTs). */
data class IntervalSessionRecord(
    val workoutStartTs: Long,
    val programName: String,
    val splits: List<IntervalSplit>,
)

object IntervalSplitStore {
    private const val NAME = "fork_interval_splits"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun save(ctx: Context, rec: IntervalSessionRecord) {
        val o = JSONObject().apply {
            put("program", rec.programName)
            put("splits", JSONArray().apply { rec.splits.forEach { put(it.toJson()) } })
        }
        p(ctx).edit().putString(rec.workoutStartTs.toString(), o.toString()).apply()
    }

    fun load(ctx: Context, workoutStartTs: Long): IntervalSessionRecord? {
        val raw = p(ctx).getString(workoutStartTs.toString(), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("splits")
            IntervalSessionRecord(
                workoutStartTs = workoutStartTs,
                programName = o.optString("program", "Intervals"),
                splits = (0 until arr.length()).mapNotNull { IntervalSplit.fromJson(arr.getJSONObject(it)) },
            )
        }.getOrNull()
    }
}
