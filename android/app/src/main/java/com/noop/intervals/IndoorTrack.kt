package com.noop.intervals

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.ui.UnitPrefs
import com.noop.ui.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fork addition (Rick): indoor track mode.
 *
 * GPS is useless on an indoor track, so an indoor run or walk turns GPS off and counts laps instead:
 * a big Lap button on the live screen (and in a notification, so it works from the lock screen), and
 * the lap count can be corrected when the run ends. Distance = laps x lap length, where the lap length
 * comes from a saved track ("YMCA, 9 laps per mile", or a length in metres). That distance then feeds
 * pace, calories, shoe miles, interval splits and Health Connect (with each timed lap) like a GPS run.
 *
 * A tap within [IndoorLaps.MIN_LAP_MS] of the previous one is ignored as a double tap. During an
 * interval step that ends mid-lap, the part lap is estimated from time ([IndoorLaps.estimatedMeters]).
 */
data class IndoorTrack(val id: String, val name: String, val lapMeters: Double, val lapsPerMile: Double?) {
    /** "9 laps per mile" or "200 m per lap". */
    fun lengthLabel(): String =
        if (lapsPerMile != null) "${IndoorLaps.trim(lapsPerMile)} laps per mile"
        else "${lapMeters.roundToInt()} m per lap"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("lapMeters", lapMeters).apply {
        if (lapsPerMile != null) put("lapsPerMile", lapsPerMile)
    }

    companion object {
        fun fromJson(o: JSONObject): IndoorTrack? = runCatching {
            IndoorTrack(
                id = o.getString("id"),
                name = o.optString("name"),
                lapMeters = o.getDouble("lapMeters"),
                lapsPerMile = if (o.has("lapsPerMile")) o.getDouble("lapsPerMile") else null,
            )
        }.getOrNull()?.takeIf { it.lapMeters in IndoorLaps.MIN_LAP_M..IndoorLaps.MAX_LAP_M }
    }
}

/** Pure lap maths; unit-tested. */
object IndoorLaps {
    const val METERS_PER_MILE = 1609.344
    const val MIN_LAP_MS = 10_000L
    const val MIN_LAP_M = 50.0
    const val MAX_LAP_M = 2000.0

    fun lapMetersFromLapsPerMile(lapsPerMile: Double): Double = METERS_PER_MILE / lapsPerMile

    /** Plausible inputs only: 1 to 32 laps per mile, or 50 to 2000 m per lap. */
    fun validLapsPerMile(v: Double?): Boolean = v != null && v >= 0.8 && v <= 32.0
    fun validLapMeters(v: Double?): Boolean = v != null && v >= MIN_LAP_M && v <= MAX_LAP_M

    /** True when a tap at [nowMs] counts: at least [MIN_LAP_MS] after the previous tap (or the start). */
    fun accepts(startMs: Long, taps: List<Long>, nowMs: Long): Boolean =
        nowMs - (taps.lastOrNull() ?: startMs) >= MIN_LAP_MS

    /** Each timed lap's duration in ms (first lap runs from the workout start). */
    fun lapDurationsMs(startMs: Long, taps: List<Long>): List<Long> {
        var prev = startMs
        return taps.map { t -> (t - prev).also { prev = t } }
    }

    /**
     * Distance so far including an estimate of the lap in progress: whole laps plus the share of a lap
     * that the time since the last tap suggests (by the last lap's time), capped below one lap. Before
     * the first tap there is nothing to scale by, so the lap in progress counts as 0.
     */
    fun estimatedMeters(lapMeters: Double, startMs: Long, taps: List<Long>, nowMs: Long): Double {
        val whole = taps.size * lapMeters
        val last = taps.lastOrNull() ?: return whole
        val lastLap = lapDurationsMs(startMs, taps).last().coerceAtLeast(1L)
        val frac = ((nowMs - last).toDouble() / lastLap).coerceIn(0.0, 0.95)
        return whole + frac * lapMeters
    }

    /**
     * The timed laps to keep when the user corrects the count to [count]: with fewer laps than taps the
     * shortest laps go first (they are the likely stray taps); with more, every tap is kept and the
     * extra laps simply have no time.
     */
    fun keptTapsForCount(startMs: Long, taps: List<Long>, count: Int): List<Long> {
        if (count >= taps.size) return taps
        if (count <= 0) return emptyList()
        val durations = lapDurationsMs(startMs, taps)
        val drop = durations.indices.sortedBy { durations[it] }.take(taps.size - count).toSet()
        return taps.filterIndexed { i, _ -> i !in drop }
    }

    fun trim(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}

/** Saved tracks, the default track and the last outdoor / indoor choice (SharedPreferences "runner_tracks"). */
object IndoorTrackStore {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner_tracks", Context.MODE_PRIVATE)

    /** Sports that offer indoor track mode. */
    fun supports(sport: String): Boolean = sport.trim().lowercase() in setOf("running", "walking")

    fun indoorMode(ctx: Context): Boolean = p(ctx).getBoolean("indoorMode", false)
    fun setIndoorMode(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("indoorMode", on).apply()

    fun tracks(ctx: Context): List<IndoorTrack> {
        val a = runCatching { JSONArray(p(ctx).getString("tracks", "[]") ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(IndoorTrack::fromJson) }
    }

    private fun saveAll(ctx: Context, list: List<IndoorTrack>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        p(ctx).edit().putString("tracks", a.toString()).apply()
    }

    fun upsert(ctx: Context, t: IndoorTrack) {
        val list = tracks(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list.add(t)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        saveAll(ctx, tracks(ctx).filterNot { it.id == id })
        if (p(ctx).getString("defaultId", null) == id) p(ctx).edit().remove("defaultId").apply()
    }

    fun setDefault(ctx: Context, id: String) = p(ctx).edit().putString("defaultId", id).apply()

    /** The track used last, else the first saved one. */
    fun defaultTrack(ctx: Context): IndoorTrack? {
        val all = tracks(ctx)
        val id = p(ctx).getString("defaultId", null)
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    fun newId(): String = "t" + System.currentTimeMillis().toString(36)
}

/** A finished indoor workout's laps, by workout start second (SharedPreferences "runner_laps"). */
object IndoorLapStore {
    data class Record(val trackName: String, val lapMeters: Double, val lapsPerMile: Double?, val count: Int, val lapMs: List<Long>, val lapStarts: List<Long>) {
        val meters: Double get() = count * lapMeters
    }

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner_laps", Context.MODE_PRIVATE)

    fun save(ctx: Context, startTs: Long, r: Record) {
        val o = JSONObject().put("track", r.trackName).put("lapMeters", r.lapMeters).put("count", r.count)
            .put("lapMs", JSONArray(r.lapMs)).put("lapStarts", JSONArray(r.lapStarts))
        if (r.lapsPerMile != null) o.put("lapsPerMile", r.lapsPerMile)
        p(ctx).edit().putString(startTs.toString(), o.toString()).apply()
    }

    fun load(ctx: Context, startTs: Long): Record? {
        val s = p(ctx).getString(startTs.toString(), null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            val ms = o.getJSONArray("lapMs")
            val st = o.optJSONArray("lapStarts") ?: JSONArray()
            Record(
                trackName = o.optString("track"),
                lapMeters = o.getDouble("lapMeters"),
                lapsPerMile = if (o.has("lapsPerMile")) o.getDouble("lapsPerMile") else null,
                count = o.getInt("count"),
                lapMs = (0 until ms.length()).map { ms.getLong(it) },
                lapStarts = (0 until st.length()).map { st.getLong(it) },
            )
        }.getOrNull()
    }

    /** Timed laps as (startMs, endMs, metres) for Health Connect. */
    fun timedLaps(ctx: Context, startTs: Long): List<Triple<Long, Long, Double>> {
        val r = load(ctx, startTs) ?: return emptyList()
        return r.lapStarts.zip(r.lapMs).map { (a, d) -> Triple(a, a + d, r.lapMeters) }
    }
}

/** The lap counter for the indoor workout in progress (process level, saved on every tap). */
object IndoorLapSession {
    data class State(
        val active: Boolean = false,
        val workoutStartMs: Long = 0L,
        val track: IndoorTrack? = null,
        val taps: List<Long> = emptyList(),
        val finalCount: Int? = null,
    ) {
        val laps: Int get() = taps.size
        val meters: Double get() = laps * (track?.lapMeters ?: 0.0)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var cues: IntervalCues? = null

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner_lap_session", Context.MODE_PRIVATE)

    @Synchronized
    fun start(ctx: Context, workoutStartMs: Long, track: IndoorTrack) {
        _state.value = State(active = true, workoutStartMs = workoutStartMs, track = track)
        persist(ctx)
        cues = IntervalCues(ctx).also { it.open() }
        IndoorLapNotifier.show(ctx, _state.value)
    }

    /** Bring the session back after Android killed the app mid-run (the workout itself is restored too). */
    @Synchronized
    fun restoreIfNeeded(ctx: Context) {
        if (_state.value.active) return
        val p = p(ctx)
        if (!p.getBoolean("active", false)) return
        val track = runCatching { IndoorTrack.fromJson(JSONObject(p.getString("track", "{}") ?: "{}")) }.getOrNull() ?: return
        val taps = p.getString("taps", "")?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()
        _state.value = State(active = true, workoutStartMs = p.getLong("startMs", 0L), track = track, taps = taps)
    }

    private fun persist(ctx: Context) {
        val s = _state.value
        p(ctx).edit()
            .putBoolean("active", s.active)
            .putLong("startMs", s.workoutStartMs)
            .putString("track", s.track?.toJson()?.toString())
            .putString("taps", s.taps.joinToString(","))
            .apply()
    }

    /** Count a lap now. Returns false when there is no indoor workout or it was a double tap. */
    @Synchronized
    fun lap(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        restoreIfNeeded(ctx)
        val s = _state.value
        if (!s.active || !IndoorLaps.accepts(s.workoutStartMs, s.taps, nowMs)) return false
        val taps = s.taps + nowMs
        _state.value = s.copy(taps = taps, finalCount = null)
        persist(ctx)
        val lapMs = IndoorLaps.lapDurationsMs(s.workoutStartMs, taps).last()
        val c = cues ?: IntervalCues(ctx).also { it.open(); cues = it }
        c.vibrate(longArrayOf(0, 60))
        if (CueSettingsStore.load(ctx).voice) c.speak("Lap ${taps.size}, ${IndoorLaps.clock(lapMs)}")
        IndoorLapNotifier.show(ctx, _state.value)
        return true
    }

    /** The lap count chosen on the End screen (null = the number of taps). */
    fun setFinalCount(count: Int?) {
        val s = _state.value
        if (s.active) _state.value = s.copy(finalCount = count?.coerceIn(0, 999))
    }

    /** Distance including the lap in progress, for interval steps. */
    fun estimatedMeters(nowMs: Long = System.currentTimeMillis()): Double {
        val s = _state.value
        val track = s.track ?: return 0.0
        return IndoorLaps.estimatedMeters(track.lapMeters, s.workoutStartMs, s.taps, nowMs)
    }

    /**
     * End the session for the workout that started at [workoutStartMs]: save its laps and return the
     * distance in metres, or null when this workout had no indoor session.
     */
    @Synchronized
    fun finish(ctx: Context, workoutStartMs: Long): Double? {
        restoreIfNeeded(ctx)
        val s = _state.value
        val track = s.track
        if (!s.active || s.workoutStartMs != workoutStartMs || track == null) return null
        val count = s.finalCount ?: s.taps.size
        val kept = IndoorLaps.keptTapsForCount(s.workoutStartMs, s.taps, count)
        val durations = IndoorLaps.lapDurationsMs(s.workoutStartMs, kept)
        val starts = listOf(s.workoutStartMs) + kept.dropLast(1)
        IndoorLapStore.save(
            ctx, workoutStartMs / 1000,
            IndoorLapStore.Record(track.name, track.lapMeters, track.lapsPerMile, count, durations, starts),
        )
        clear(ctx)
        return count * track.lapMeters
    }

    @Synchronized
    fun discard(ctx: Context) = clear(ctx)

    private fun clear(ctx: Context) {
        _state.value = State()
        p(ctx).edit().clear().apply()
        runCatching { cues?.close() }
        cues = null
        IndoorLapNotifier.cancel(ctx)
    }
}

/** The ongoing "Indoor track" notification with a Lap button, usable from the lock screen. */
object IndoorLapNotifier {
    private const val CHANNEL = "runner_laps"
    private const val ID = 77_101
    const val ACTION_LAP = "com.noop.rick.INDOOR_LAP"

    fun show(ctx: Context, s: IndoorLapSession.State) {
        val app = ctx.applicationContext
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Indoor track laps", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Lap button for indoor track runs"
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    },
                )
            }
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
            val lapIntent = PendingIntent.getBroadcast(
                app, 0, Intent(app, LapActionReceiver::class.java).setAction(ACTION_LAP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                PendingIntent.getActivity(app, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val lastLap = if (s.taps.isNotEmpty()) " · last lap " + IndoorLaps.clock(IndoorLaps.lapDurationsMs(s.workoutStartMs, s.taps).last()) else ""
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(com.noop.R.drawable.ic_stat_heart)
                .setContentTitle("Indoor track · ${s.laps} lap${if (s.laps == 1) "" else "s"}")
                .setContentText(distanceLabel(app, s.meters) + lastLap)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "Lap", lapIntent)
                .apply { if (open != null) setContentIntent(open) }
                .build()
            NotificationManagerCompat.from(app).notify(ID, n)
        }
    }

    fun cancel(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx.applicationContext).cancel(ID) }
    }

    fun distanceLabel(ctx: Context, meters: Double): String =
        if (UnitPrefs.distanceSystem(ctx) == UnitSystem.IMPERIAL) String.format(Locale.US, "%.2f mi", meters / IndoorLaps.METERS_PER_MILE)
        else String.format(Locale.US, "%.2f km", meters / 1000.0)
}

/** Receives the notification's Lap button. */
class LapActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == IndoorLapNotifier.ACTION_LAP) IndoorLapSession.lap(context)
    }
}

/**
 * Fork addition (Rick): "Check for updates" for NOOP Runner. Upstream compares against NOOP's own
 * GitHub releases, which says nothing about this fork. NOOP Runner's version ends in "-runner.N"
 * (from fork-version.txt), so this compares N with the fork-version.txt on the rick-intervals branch.
 */
object RunnerUpdate {
    const val VERSION_URL = "https://raw.githubusercontent.com/WMTS/noop/rick-intervals/fork-version.txt"
    const val RELEASE_URL = "https://github.com/WMTS/noop/releases/tag/intervals-latest"

    /** The build number in a version like "12.1.0-runner.9", or null for a non-Runner version. */
    fun buildOf(versionName: String): Int? {
        if (!versionName.contains("-runner.")) return null
        return versionName.substringAfter("-runner.").takeWhile { it.isDigit() }.toIntOrNull()
    }

    /** Blocking; call off the main thread. Null when this is not a Runner build (use upstream's check). */
    fun check(currentVersion: String): com.noop.update.UpdateCheck.Result? {
        val current = buildOf(currentVersion) ?: return null
        val base = currentVersion.substringBefore("-runner.")
        return runCatching {
            val conn = (java.net.URL(VERSION_URL).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            try {
                if (conn.responseCode != 200) return@runCatching com.noop.update.UpdateCheck.Result.Failed
                val latest = conn.inputStream.bufferedReader().use { it.readText() }.trim().takeWhile { it.isDigit() }.toIntOrNull()
                    ?: return@runCatching com.noop.update.UpdateCheck.Result.Failed
                if (latest > current) {
                    com.noop.update.UpdateCheck.Result.Available(
                        "$base-runner.$latest", RELEASE_URL,
                        "NOOP Runner build $latest is on GitHub (you have build $current). Download NOOP-Runner.apk " +
                            "from the release page and tap Update. A build takes about 10 minutes to appear after a change.",
                    )
                } else {
                    com.noop.update.UpdateCheck.Result.UpToDate("$base-runner.$current")
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(com.noop.update.UpdateCheck.Result.Failed)
    }
}
