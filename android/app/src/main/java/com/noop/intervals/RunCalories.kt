package com.noop.intervals

/**
 * Fork addition (Rick): calories for runs and walks from DISTANCE and TIME, using the ACSM
 * metabolic equations, instead of heart rate alone.
 *
 * Heart-rate formulas (NOOP's default, Keytel 2005) are commonly 20-30% off for an individual and
 * count a run/walk session's walk breaks at running cost because heart rate lags. On foot the
 * energy cost is mostly set by how far you moved, so when there is a distance we use:
 *
 *   running  VO2 = 0.2 x speed + 3.5   (ml/kg/min, speed in m/min)
 *   walking  VO2 = 0.1 x speed + 3.5
 *   kcal     = VO2 x weightKg x minutes / 1000 x 5   (about 5 kcal per litre of O2)
 *
 * "Active" is that minus the resting 3.5 (1 MET), which for running works out to about
 * 1 kcal per kg per km whatever the pace, and about 0.5 for walking.
 *
 * Hills: when the workout recorded an elevation gain ([ElevationTracker]), ACSM's grade term is added:
 * 0.9 ml/kg per metre climbed running, 1.8 walking (split by how much of the distance was run or
 * walked). Descents are not credited, as in ACSM. A climb over 25% of the distance is treated as bad
 * sensor data and capped.
 *
 * With interval splits, each step is costed on its own (run steps as running, walk breaks as
 * walking), which is where run/walk sessions gain the most. Pure; unit-tested.
 */
object RunCalories {
    data class Estimate(val activeKcal: Double, val grossKcal: Double, val method: String)

    /** Sports this applies to. Hiking is left on heart rate (terrain and climbing dominate). */
    private val footSports = setOf("running", "walking", "treadmill run", "treadmill walk")
    private val walkingSports = setOf("walking", "treadmill walk")

    fun applies(sport: String): Boolean = sport.trim().lowercase() in footSports

    /** Below this speed even a "run" is costed as walking; above [RUN_MIN_M_PER_MIN] always running. */
    private const val WALK_MAX_M_PER_MIN = 100.0   // 6 km/h, ~16:05 per mile
    private const val RUN_MIN_M_PER_MIN = 134.0    // 8 km/h, ~12:00 per mile

    /**
     * @param distanceM whole-session distance (GPS, or typed in for a treadmill), null when unknown
     * @param durationS active (un-paused) seconds
     * @param splits interval splits with their own distance, or empty
     * @return null when it does not apply (not a foot sport, no usable distance, no weight)
     */
    fun estimate(
        sport: String,
        distanceM: Double?,
        durationS: Double,
        weightKg: Double,
        splits: List<IntervalSplit> = emptyList(),
        climbM: Double = 0.0,
    ): Estimate? {
        if (!applies(sport) || weightKg <= 0 || durationS < 60) return null
        val total = distanceM ?: return null
        if (total < 200) return null
        val sportIsWalk = sport.trim().lowercase() in walkingSports

        var activeMlPerKg = 0.0 // sum of (VO2 - 3.5) x minutes, in ml/kg
        var coveredM = 0.0
        var coveredS = 0.0
        var runM = 0.0
        var walkM = 0.0
        val usable = splits.filter { (it.distanceM ?: -1.0) >= 0 && it.durationS > 0 }
        var segmented = false
        for (sp in usable) {
            val d = sp.distanceM ?: continue
            val min = sp.durationS / 60.0
            val running = isRunning(d / min, sportIsWalk, effortHint = sp.kind.isEffort)
            activeMlPerKg += (if (running) 0.2 else 0.1) * d
            if (running) runM += d else walkM += d
            coveredM += d
            coveredS += sp.durationS
            segmented = true
        }
        // Time and distance outside the interval program (e.g. jogging after it finished).
        val restM = (total - coveredM).coerceAtLeast(0.0)
        val restS = (durationS - coveredS).coerceAtLeast(0.0)
        if (restM > 0 && restS > 0) {
            val running = isRunning(restM / (restS / 60.0), sportIsWalk, effortHint = !sportIsWalk)
            activeMlPerKg += (if (running) 0.2 else 0.1) * restM
            if (running) runM += restM else walkM += restM
        }
        val climb = climbM.coerceIn(0.0, 0.25 * total)
        if (climb > 0 && runM + walkM > 0) {
            activeMlPerKg += climb * (RUN_CLIMB * runM + WALK_CLIMB * walkM) / (runM + walkM)
        }
        val activeKcal = activeMlPerKg * weightKg / 1000.0 * 5.0
        val restingKcal = 3.5 * weightKg * (durationS / 60.0) / 1000.0 * 5.0
        return Estimate(
            activeKcal = activeKcal,
            grossKcal = activeKcal + restingKcal,
            method = if (segmented) "acsm-intervals" else "acsm",
        )
    }

    /** ACSM grade term per metre climbed (ml/kg): 0.9 x speed x grade running, 1.8 walking. */
    private const val RUN_CLIMB = 0.9
    private const val WALK_CLIMB = 1.8

    private fun isRunning(speedMPerMin: Double, sportIsWalk: Boolean, effortHint: Boolean): Boolean = when {
        speedMPerMin >= RUN_MIN_M_PER_MIN -> true
        speedMPerMin < WALK_MAX_M_PER_MIN -> false
        else -> !sportIsWalk && effortHint // 6-8 km/h: a slow jog or a brisk walk, go by what the step was
    }

    /** Active kcal from a gross (resting-included) heart-rate estimate, for workouts this can't cost. */
    fun activeFromGross(grossKcal: Double, durationS: Double, weightKg: Double): Double {
        val resting = 3.5 * weightKg * (durationS / 60.0) / 1000.0 * 5.0
        return (grossKcal - resting).coerceAtLeast(0.0)
    }
}
