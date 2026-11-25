package com.noop.intervals

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.noop.analytics.RouteMath
import com.noop.data.HrSample
import com.noop.data.SecurePrefs
import com.noop.data.WorkoutRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fork addition (Rick): Strava upload, set up per person in the app.
 *
 * Strava only lets apps upload through a Strava "API application", and since June 2026 creating one needs a
 * Strava subscription. Nothing is built into the APK: each runner types a Client ID and Client Secret into
 * Settings > Strava (their own API app, or a friend's that allows up to 10 athletes), signs in once, and the
 * tokens stay encrypted on that phone. Updates install over the top, so the connection carries over.
 *
 * Uploads are TCX files ([RunnerTcx]) with heart rate, laps and the route when there is one. Strava's
 * API cannot set an activity private, so uploads follow the athlete's own Strava privacy default; the
 * app can mute them from the home feed (hide_from_home) instead. No backslash escapes in this file on
 * purpose (it travels through a JSON push).
 */
object StravaStore {
    private fun plain(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner_strava", Context.MODE_PRIVATE)
    private fun secure(ctx: Context) = SecurePrefs.of(ctx.applicationContext, "runner_strava_secure")

    private val _version = MutableStateFlow(0)
    /** Bumped on every change so open screens refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()
    internal fun bump() { _version.value = _version.value + 1 }

    fun clientId(ctx: Context): String = plain(ctx).getString("clientId", "") ?: ""
    fun clientSecret(ctx: Context): String = runCatching { secure(ctx).getString("clientSecret", "") ?: "" }.getOrDefault("")
    fun hasKeys(ctx: Context): Boolean = clientId(ctx).isNotBlank() && clientSecret(ctx).isNotBlank()

    fun saveKeys(ctx: Context, id: String, secret: String) {
        plain(ctx).edit().putString("clientId", id.trim()).apply()
        runCatching { secure(ctx).edit().putString("clientSecret", secret.trim()).apply() }
        bump()
    }

    internal fun accessToken(ctx: Context): String = runCatching { secure(ctx).getString("access", "") ?: "" }.getOrDefault("")
    internal fun refreshToken(ctx: Context): String = runCatching { secure(ctx).getString("refresh", "") ?: "" }.getOrDefault("")
    internal fun expiresAt(ctx: Context): Long = plain(ctx).getLong("expiresAt", 0L)

    internal fun saveTokens(ctx: Context, access: String, refresh: String, expiresAt: Long) {
        runCatching { secure(ctx).edit().putString("access", access).putString("refresh", refresh).apply() }
        plain(ctx).edit().putLong("expiresAt", expiresAt).apply()
        bump()
    }

    fun connected(ctx: Context): Boolean = refreshToken(ctx).isNotBlank()
    fun athleteName(ctx: Context): String = plain(ctx).getString("athlete", "") ?: ""
    internal fun setAthleteName(ctx: Context, name: String) = plain(ctx).edit().putString("athlete", name).apply()

    fun autoUpload(ctx: Context): Boolean = plain(ctx).getBoolean("auto", true)
    fun setAutoUpload(ctx: Context, on: Boolean) { plain(ctx).edit().putBoolean("auto", on).apply(); bump() }

    /** Mute uploads from followers' home feeds (Strava's hide_from_home). On by default. */
    fun mute(ctx: Context): Boolean = plain(ctx).getBoolean("mute", true)
    fun setMute(ctx: Context, on: Boolean) { plain(ctx).edit().putBoolean("mute", on).apply(); bump() }

    /** Which workout groups upload automatically: runs, walks (walks + hikes), rides, other. */
    fun sends(ctx: Context, group: String): Boolean = plain(ctx).getBoolean("send_$group", group != "other")
    fun setSends(ctx: Context, group: String, on: Boolean) { plain(ctx).edit().putBoolean("send_$group", on).apply(); bump() }

    /** The last result line shown in Settings. */
    fun lastResult(ctx: Context): String = plain(ctx).getString("last", "") ?: ""
    internal fun setLastResult(ctx: Context, s: String) { plain(ctx).edit().putString("last", s).apply(); bump() }

    /** Per-workout status: "sent:<activityId>", "duplicate", "error:<text>" or "pending". */
    fun status(ctx: Context, startTs: Long): String? = plain(ctx).getString("st_$startTs", null)
    internal fun setStatus(ctx: Context, startTs: Long, s: String?) {
        plain(ctx).edit().apply { if (s == null) remove("st_$startTs") else putString("st_$startTs", s) }.apply()
        bump()
    }

    fun pending(ctx: Context): List<Long> =
        plain(ctx).getString("pending", "")?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()

    internal fun setPending(ctx: Context, list: List<Long>) =
        plain(ctx).edit().putString("pending", list.distinct().takeLast(30).joinToString(",")).apply()

    /** Forget the sign-in (keys stay unless [keys] is true). */
    fun disconnect(ctx: Context, keys: Boolean) {
        runCatching { secure(ctx).edit().remove("access").remove("refresh").apply { if (keys) remove("clientSecret") }.apply() }
        plain(ctx).edit().remove("expiresAt").remove("athlete").remove("pending").apply { if (keys) remove("clientId") }.apply()
        bump()
    }
}

/** Pure helpers; unit-tested. */
object StravaText {
    fun group(sport: String): String {
        val s = sport.lowercase()
        return when {
            "run" in s -> "runs"
            "walk" in s || "hik" in s -> "walks"
            "cycl" in s || "bike" in s || "ride" in s -> "rides"
            else -> "other"
        }
    }

    /** Strava sport_type for an upload. */
    fun sportType(sport: String): String {
        val s = sport.lowercase()
        return when {
            "run" in s -> "Run"
            "hik" in s -> "Hike"
            "walk" in s -> "Walk"
            "cycl" in s || "bike" in s || "ride" in s -> "Ride"
            "swim" in s -> "Swim"
            else -> "Workout"
        }
    }

    fun isIndoor(sport: String, hasTrackLaps: Boolean): Boolean = hasTrackLaps || "treadmill" in sport.lowercase()

    /** The authorization code in a redirect address, or null (also null when Strava sent an error). */
    fun codeFrom(redirect: String): String? {
        val q = redirect.substringAfter('?', "").substringBefore('#')
        val params = q.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=', "")
            if (k.isEmpty()) null else k to java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
        }.toMap()
        if (params.containsKey("error")) return null
        return params["code"]?.takeIf { it.isNotBlank() }
    }
}

/** Sign-in, upload and retry. Network calls are blocking; call them off the main thread. */
object StravaSync {
    const val REDIRECT_APP = "noop-runner://localhost/strava"
    const val REDIRECT_WEB = "http://localhost/noop-runner"
    private const val API = "https://www.strava.com/api/v3"
    private val CRLF = String(charArrayOf(Char(13), Char(10)))
    private const val Q = '"'
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The Strava approve page. [viaApp] opens the Strava app when installed and returns straight to NOOP Runner. */
    fun authorizeUrl(ctx: Context, viaApp: Boolean): String =
        Uri.parse(if (viaApp) "https://www.strava.com/oauth/mobile/authorize" else "https://www.strava.com/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", StravaStore.clientId(ctx))
            .appendQueryParameter("redirect_uri", if (viaApp) REDIRECT_APP else REDIRECT_WEB)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", "activity:write,activity:read")
            .build().toString()

    /** Finish sign-in from a redirect address. Returns a message for the user. */
    fun finishSignIn(ctx: Context, redirect: String): String {
        val code = StravaText.codeFrom(redirect)
            ?: return "Strava did not approve the connection (or the address had no code). Try Connect again.".also { StravaStore.setLastResult(ctx, it) }
        val res = runCatching {
            post(
                "https://www.strava.com/oauth/token",
                mapOf(
                    "client_id" to StravaStore.clientId(ctx), "client_secret" to StravaStore.clientSecret(ctx),
                    "code" to code, "grant_type" to "authorization_code",
                ),
            )
        }.getOrElse { return "Could not reach Strava: ${it.message}".also { m -> StravaStore.setLastResult(ctx, m) } }
        if (res.first !in 200..299) {
            val msg = "Strava refused the sign-in (" + res.first + "). Check the Client ID and Secret, and that the callback domain is localhost."
            StravaStore.setLastResult(ctx, msg)
            return msg
        }
        val o = JSONObject(res.second)
        StravaStore.saveTokens(ctx, o.getString("access_token"), o.getString("refresh_token"), o.getLong("expires_at"))
        o.optJSONObject("athlete")?.let { a ->
            StravaStore.setAthleteName(ctx, (a.optString("firstname") + " " + a.optString("lastname")).trim())
        }
        val msg = "Connected to Strava" + StravaStore.athleteName(ctx).let { if (it.isNotBlank()) " as $it." else "." }
        StravaStore.setLastResult(ctx, msg)
        return msg
    }

    private fun token(ctx: Context): String? {
        if (!StravaStore.connected(ctx)) return null
        val now = System.currentTimeMillis() / 1000
        if (StravaStore.expiresAt(ctx) - 300 > now && StravaStore.accessToken(ctx).isNotBlank()) return StravaStore.accessToken(ctx)
        val res = post(
            "https://www.strava.com/oauth/token",
            mapOf(
                "client_id" to StravaStore.clientId(ctx), "client_secret" to StravaStore.clientSecret(ctx),
                "refresh_token" to StravaStore.refreshToken(ctx), "grant_type" to "refresh_token",
            ),
        )
        if (res.first == 400 || res.first == 401) {
            StravaStore.disconnect(ctx, keys = false)
            StravaStore.setLastResult(ctx, "Strava signed this phone out. Tap Connect to sign in again.")
            return null
        }
        if (res.first !in 200..299) throw java.io.IOException("token refresh " + res.first)
        val o = JSONObject(res.second)
        StravaStore.saveTokens(ctx, o.getString("access_token"), o.getString("refresh_token"), o.getLong("expires_at"))
        return o.getString("access_token")
    }

    /** True when this workout should upload automatically. */
    fun wantsAuto(ctx: Context, row: WorkoutRow): Boolean =
        StravaStore.connected(ctx) && StravaStore.autoUpload(ctx) && StravaStore.sends(ctx, StravaText.group(row.sport)) &&
            StravaStore.status(ctx, row.startTs)?.let { it.startsWith("sent") || it == "duplicate" } != true

    /** Build the workout's TCX: route, laps (indoor track or interval steps) and heart rate. */
    fun buildTcx(ctx: Context, row: WorkoutRow, hr: List<HrSample>): String {
        val route = row.routePolyline?.let { runCatching { RouteMath.decode(it) }.getOrNull() }.orEmpty()
        val track = IndoorLapStore.load(ctx, row.startTs)
        val splits = IntervalSplitStore.load(ctx, row.startTs)?.splits.orEmpty()
        val laps = when {
            track != null -> track.lapStarts.zip(track.lapMs).map { (a, d) -> RunnerTcx.Lap(a, a + d, track.lapMeters) }
            splits.isNotEmpty() -> splits.mapNotNull { s ->
                val a = s.startMs ?: return@mapNotNull null
                val b = s.endMs ?: return@mapNotNull null
                RunnerTcx.Lap(a, b, s.distanceM, rest = !s.kind.isEffort)
            }
            else -> emptyList()
        }
        val anchors = if (track != null && row.distanceM != null) {
            RunnerTcx.lapAnchors(row.startTs * 1000, row.endTs * 1000, track.lapStarts.zip(track.lapMs).map { (a, d) -> a + d }, track.lapMeters, row.distanceM)
        } else null
        return RunnerTcx.build(
            sport = row.sport, startTs = row.startTs, endTs = row.endTs, distanceM = row.distanceM, kcal = row.energyKcal,
            hr = hr.map { it.ts to it.bpm }, route = route, laps = laps, distanceAnchors = anchors,
        )
    }

    /**
     * Upload one workout. Returns a message for the user. A network failure queues it for a retry;
     * a duplicate (already on Strava) counts as sent.
     */
    fun upload(ctx: Context, row: WorkoutRow, hr: List<HrSample>): String {
        if (!StravaStore.connected(ctx)) return "Connect Strava first (Settings > Strava)."
        StravaStore.setStatus(ctx, row.startTs, "pending")
        return try {
            val tok = token(ctx) ?: return StravaStore.lastResult(ctx)
            val track = IndoorLapStore.load(ctx, row.startTs)
            val desc = StringBuilder("Recorded with NOOP Runner.")
            if (track != null) desc.append(" ").append(track.count).append(" laps on ").append(track.trackName.ifBlank { "an indoor track" }).append(".")
            val fields = mapOf(
                "data_type" to "tcx",
                "sport_type" to StravaText.sportType(row.sport),
                "trainer" to if (StravaText.isIndoor(row.sport, track != null)) "1" else "0",
                "external_id" to "noop-runner-" + row.startTs,
                "description" to desc.toString(),
            )
            val res = multipart("$API/uploads", tok, fields, "noop-runner-" + row.startTs + ".tcx", buildTcx(ctx, row, hr).toByteArray())
            if (res.first == 401) {
                StravaStore.setStatus(ctx, row.startTs, "error:signed out")
                return "Strava rejected the sign-in. Tap Connect in Settings > Strava again."
            }
            if (res.first !in 200..299) {
                val m = "error:" + res.first
                StravaStore.setStatus(ctx, row.startTs, m)
                return "Strava refused the upload (" + res.first + ")."
            }
            val upId = JSONObject(res.second).optLong("id")
            val (actId, err) = waitForActivity(tok, upId)
            when {
                actId != null -> {
                    StravaStore.setStatus(ctx, row.startTs, "sent:$actId")
                    if (StravaStore.mute(ctx)) runCatching { put("$API/activities/$actId", tok, mapOf("hide_from_home" to "true")) }
                    dropPending(ctx, row.startTs)
                    "Sent to Strava.".also { StravaStore.setLastResult(ctx, "Last upload: sent " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.startTs * 1000))) }
                }
                err != null && err.contains("duplicate", ignoreCase = true) -> {
                    StravaStore.setStatus(ctx, row.startTs, "duplicate")
                    dropPending(ctx, row.startTs)
                    "Already on Strava (duplicate)."
                }
                err != null -> {
                    StravaStore.setStatus(ctx, row.startTs, "error:" + err.take(120))
                    dropPending(ctx, row.startTs)
                    "Strava could not read it: " + err.take(160)
                }
                else -> {
                    StravaStore.setStatus(ctx, row.startTs, "sent:processing")
                    dropPending(ctx, row.startTs)
                    "Sent. Strava is still processing it; it will appear shortly."
                }
            }
        } catch (t: java.io.IOException) {
            StravaStore.setStatus(ctx, row.startTs, "pending")
            StravaStore.setPending(ctx, StravaStore.pending(ctx) + row.startTs)
            "No connection to Strava. It will retry automatically."
        } catch (t: Throwable) {
            StravaStore.setStatus(ctx, row.startTs, "error:" + (t.message ?: "unknown").take(120))
            "Upload failed: " + (t.message ?: "unknown error")
        }
    }

    private fun dropPending(ctx: Context, startTs: Long) = StravaStore.setPending(ctx, StravaStore.pending(ctx) - startTs)

    /** Poll the upload until Strava reports an activity id or an error (about 20 s at most). */
    private fun waitForActivity(tok: String, uploadId: Long): Pair<Long?, String?> {
        if (uploadId <= 0) return null to null
        repeat(10) {
            Thread.sleep(2000)
            val r = runCatching { get("$API/uploads/$uploadId", tok) }.getOrNull() ?: return@repeat
            if (r.first !in 200..299) return@repeat
            val o = JSONObject(r.second)
            val err = o.optString("error", "").takeIf { it.isNotBlank() && it != "null" }
            if (err != null) return null to err
            val act = if (o.isNull("activity_id")) 0L else o.optLong("activity_id")
            if (act > 0) return act to null
        }
        return null to null
    }

    /** Disconnect: tell Strava (best effort), then forget the sign-in on this phone. */
    fun disconnect(ctx: Context, forgetKeys: Boolean) {
        val tok = StravaStore.accessToken(ctx)
        if (tok.isNotBlank()) runCatching { post("https://www.strava.com/oauth/deauthorize", mapOf("access_token" to tok)) }
        StravaStore.disconnect(ctx, forgetKeys)
        StravaStore.setLastResult(ctx, "Disconnected from Strava.")
    }

    // MARK: - HTTP

    private fun open(url: String, method: String, tok: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (tok != null) setRequestProperty("Authorization", "Bearer $tok")
        }

    private fun read(c: HttpURLConnection): Pair<Int, String> {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to body
    }

    private fun form(fields: Map<String, String>): ByteArray =
        fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }.toByteArray()

    private fun post(url: String, fields: Map<String, String>, tok: String? = null): Pair<Int, String> {
        val c = open(url, "POST", tok)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form(fields)) }
        return read(c)
    }

    private fun put(url: String, tok: String, fields: Map<String, String>): Pair<Int, String> {
        val c = open(url, "PUT", tok)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form(fields)) }
        return read(c)
    }

    private fun get(url: String, tok: String): Pair<Int, String> = read(open(url, "GET", tok))

    private fun multipart(url: String, tok: String, fields: Map<String, String>, fileName: String, file: ByteArray): Pair<Int, String> {
        val boundary = "noopRunner" + System.currentTimeMillis()
        val body = ByteArrayOutputStream()
        fun line(s: String) = body.write((s + CRLF).toByteArray())
        for ((k, v) in fields) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=" + Q + k + Q)
            line("")
            line(v)
        }
        line("--$boundary")
        line("Content-Disposition: form-data; name=" + Q + "file" + Q + "; filename=" + Q + fileName + Q)
        line("Content-Type: application/octet-stream")
        line("")
        body.write(file)
        line("")
        line("--$boundary--")
        val c = open(url, "POST", tok)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { it.write(body.toByteArray()) }
        return read(c)
    }
}

/**
 * Receives Strava's redirect (noop-runner://localhost/strava?code=...) after the athlete approves, finishes
 * the sign-in in the background and returns to the app.
 */
class StravaAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val redirect = intent?.data?.toString().orEmpty()
        val app = applicationContext
        StravaSync.scope.launch {
            val msg = StravaSync.finishSignIn(app, redirect)
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(it)
        }
        finish()
    }
}
