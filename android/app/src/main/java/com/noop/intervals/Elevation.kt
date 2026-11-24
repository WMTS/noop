package com.noop.intervals

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.noop.location.GpsSession
import org.json.JSONObject

/**
 * Fork addition (Rick): elevation gain for GPS workouts, used for hill calories and shown on the
 * saved workout.
 *
 * Source: the phone's barometer when it has one (most current flagships and foldables do; it resolves
 * about half a metre), otherwise GPS altitude (noisy, roughly +/- 10 m, so it gets a bigger threshold).
 * Readings are smoothed and then counted with hysteresis: a climb only counts once the altitude has
 * risen [ClimbCounter.thresholdM] above the last turning point, so sensor noise on flat ground adds
 * nothing. Paused time is ignored. If Android kills the app mid-run the climb so far is lost (the
 * workout still saves, just without elevation).
 */
class ClimbCounter(val thresholdM: Double) {
    var gainM = 0.0
        private set
    var lossM = 0.0
        private set
    private var ref: Double? = null

    /** Feed one smoothed altitude (metres). */
    fun add(altM: Double) {
        if (!altM.isFinite()) return
        val r = ref ?: run { ref = altM; return }
        if (altM - r >= thresholdM) { gainM += altM - r; ref = altM }
        else if (r - altM >= thresholdM) { lossM += r - altM; ref = altM }
    }
}

/** Exponential smoothing for altitude readings. */
class AltitudeSmoother(private val alpha: Double) {
    private var value: Double? = null
    fun add(x: Double): Double { val v = value?.let { it + alpha * (x - it) } ?: x; value = v; return v }
}

data class Climb(val gainM: Double, val lossM: Double, val source: String)

/** Process-level recorder, started with a GPS workout and stopped when it ends or is discarded. */
object ElevationTracker {
    const val BARO_THRESHOLD_M = 2.0
    const val GPS_THRESHOLD_M = 5.0
    const val BARO_ALPHA = 0.25
    const val GPS_ALPHA = 0.15

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var sensorListener: SensorEventListener? = null
    private var locationListener: LocationListener? = null
    private var counter: ClimbCounter? = null
    private var smoother: AltitudeSmoother? = null
    private var source = ""
    private var pressureSum = 0.0
    private var pressureCount = 0
    private var bucketStartMs = 0L

    @Synchronized
    fun start(context: Context) {
        stop()
        val ctx = context.applicationContext
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val baro = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (sm != null && baro != null) {
            counter = ClimbCounter(BARO_THRESHOLD_M)
            smoother = AltitudeSmoother(BARO_ALPHA)
            source = "barometer"
            bucketStartMs = 0L
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    e.values.firstOrNull()?.let { onPressure(it) }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            val ok = runCatching { sm.registerListener(l, baro, SensorManager.SENSOR_DELAY_NORMAL, 2_000_000) }.getOrDefault(false)
            if (ok) { sensorManager = sm; sensorListener = l; return }
        }
        startGps(ctx)
    }

    @SuppressLint("MissingPermission")
    private fun startGps(ctx: Context) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        counter = ClimbCounter(GPS_THRESHOLD_M)
        smoother = AltitudeSmoother(GPS_ALPHA)
        source = "gps"
        val l = LocationListener { loc: Location -> onGpsFix(loc) }
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, l, Looper.getMainLooper())
            locationManager = lm
            locationListener = l
        }.onFailure { counter = null; smoother = null; source = "" }
    }

    @Synchronized
    private fun onPressure(hPa: Float) {
        if (GpsSession.state.value.paused || hPa <= 0f) return
        val now = System.currentTimeMillis()
        if (bucketStartMs == 0L) bucketStartMs = now
        pressureSum += hPa; pressureCount++
        if (now - bucketStartMs < 1000 || pressureCount == 0) return
        val mean = (pressureSum / pressureCount).toFloat()
        pressureSum = 0.0; pressureCount = 0; bucketStartMs = now
        val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, mean).toDouble()
        val sm = smoother ?: return
        counter?.add(sm.add(alt))
    }

    @Synchronized
    private fun onGpsFix(loc: Location) {
        if (GpsSession.state.value.paused || !loc.hasAltitude()) return
        if (loc.hasAccuracy() && loc.accuracy > 30f) return
        if (loc.hasVerticalAccuracy() && loc.verticalAccuracyMeters > 15f) return
        val sm = smoother ?: return
        counter?.add(sm.add(loc.altitude))
    }

    /** Stop recording and return the climb, or null when nothing was recorded. */
    @Synchronized
    fun stop(): Climb? {
        sensorListener?.let { l -> runCatching { sensorManager?.unregisterListener(l) } }
        locationListener?.let { l -> runCatching { locationManager?.removeUpdates(l) } }
        sensorListener = null; sensorManager = null
        locationListener = null; locationManager = null
        val c = counter
        val src = source
        counter = null; smoother = null; source = ""
        pressureSum = 0.0; pressureCount = 0
        return c?.let { Climb(it.gainM, it.lossM, src) }
    }
}

/** Saved climbs by workout start second (SharedPreferences "runner_elevation"). */
object ElevationStore {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner_elevation", Context.MODE_PRIVATE)

    fun save(ctx: Context, startTs: Long, c: Climb) {
        val v = JSONObject().put("gain", c.gainM).put("loss", c.lossM).put("source", c.source)
        p(ctx).edit().putString(startTs.toString(), v.toString()).apply()
    }

    fun load(ctx: Context, startTs: Long): Climb? {
        val s = p(ctx).getString(startTs.toString(), null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            Climb(o.getDouble("gain"), o.getDouble("loss"), o.optString("source"))
        }.getOrNull()
    }
}
