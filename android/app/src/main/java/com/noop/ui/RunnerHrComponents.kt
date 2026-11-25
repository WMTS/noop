package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.intervals.ElevationStore
import com.noop.intervals.HrReserve
import com.noop.intervals.MaxHrScan
import com.noop.intervals.RunnerHrStore
import com.noop.intervals.RunnerHrSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Fork addition (Rick): the "Runner heart rate" settings card (heart-rate reserve zones, max HR from
// workouts) and the elevation line on a saved workout.

/**
 * @param rev the Settings screen's profile revision, so this card re-reads after the max HR stepper moves.
 * @param onProfileChanged bump that revision after this card writes the profile (zones or max HR).
 */
@Composable
internal fun RunnerHrSettingsCard(vm: AppViewModel, rev: Int, onProfileChanged: () -> Unit) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var local by remember { mutableIntStateOf(0) }

    // Keep the zones in step with the latest resting HR and the max HR the user just set.
    LaunchedEffect(days, rev) {
        val changed = if (days.isNotEmpty()) RunnerHrSync.onDays(context, profile, days)
        else RunnerHrSync.sync(context, profile)
        if (changed) { local++; onProfileChanged() }
    }

    val key = rev + local
    val reserveOn = remember(key) { RunnerHrStore.reserveZones(context) }
    val resting = remember(key, days) { RunnerHrStore.restingHr(context) }
    val maxHr = remember(key) { profile.hrMax }
    val starts = remember(key, resting) { resting?.let { HrReserve.zoneStarts(it, maxHr) } }
    val best = remember(key) { MaxHrScan.best(context) }

    SettingsCard(
        icon = Icons.Filled.Favorite,
        title = "Runner heart rate",
        blurb = "Zones based on your resting heart rate, and a max heart rate taken from your own workouts " +
            "instead of the age formula.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsFormRow(label = "Zones from resting HR") {
                Switch(
                    checked = reserveOn,
                    enabled = reserveOn || starts != null,
                    onCheckedChange = { on ->
                        if (RunnerHrSync.setEnabled(context, profile, on)) { local++; onProfileChanged() }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                )
            }
            val zoneText = when {
                resting == null -> "Needs a few nights of sleep data from the strap for a resting heart rate."
                starts == null -> "Resting $resting bpm and max $maxHr bpm are too close together to build zones."
                reserveOn -> "On. Resting $resting bpm (7-day median), max $maxHr bpm. Zones 1-5 start at " +
                    starts.joinToString(" / ") + " bpm. Updates as your resting heart rate changes. " +
                    "Editing a zone by hand switches this off."
                else -> {
                    val pctZ2 = kotlin.math.ceil(0.6 * maxHr).roundToInt()
                    "Off: zones are % of max heart rate (Zone 2 starts at $pctZ2 bpm). On would start Zone 2 at " +
                        "${starts[1]} bpm, using your resting $resting bpm. Usually a better fit for runners."
                }
            }
            Text(zoneText, style = NoopType.footnote, color = if (reserveOn) Palette.accent else Palette.textTertiary)

            SettingsRowDivider()

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Highest held in workouts", style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(best?.let { "${it.bpm} bpm" } ?: "None yet", style = NoopType.bodyNumber,
                    color = if (best != null) Palette.textPrimary else Palette.textTertiary)
            }
            val bestText = when {
                best == null -> "Your best 15-second heart rate from the past year of workouts shows here once " +
                    "workouts with heart rate are found."
                best.bpm > maxHr -> "15-second average, ${shortDate(best.startTs)} ${best.sport}. That is above your " +
                    "current max of $maxHr bpm, so your real max is at least this."
                else -> "15-second average, ${shortDate(best.startTs)} ${best.sport}. Your max of $maxHr bpm is " +
                    "already higher, so nothing to change. A hard finish or hill effort would show more."
            }
            Text(bestText, style = NoopType.footnote, color = Palette.textTertiary)
            if (best != null && best.bpm > maxHr) {
                OutlinedButton(
                    onClick = {
                        profile.hrMaxOverride = best.bpm
                        RunnerHrSync.sync(context, profile)
                        local++
                        onProfileChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use ${best.bpm} bpm as max heart rate") }
            }
        }
    }
}

private fun shortDate(ts: Long): String = SimpleDateFormat("MMM d", Locale.US).format(Date(ts * 1000))

/** Elevation gain and loss for a saved workout, when it was recorded. */
@Composable
internal fun WorkoutElevationSection(startTs: Long, divider: @Composable () -> Unit) {
    val context = LocalContext.current
    val climb = remember(startTs) { ElevationStore.load(context, startTs) } ?: return
    val imperial = UnitPrefs.distanceSystem(context) == UnitSystem.IMPERIAL
    fun fmt(m: Double) = if (imperial) "${(m * 3.28084).roundToInt()} ft" else "${m.roundToInt()} m"
    divider()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Elevation", style = NoopType.subhead, color = Palette.textPrimary)
        Spacer(Modifier.width(12.dp))
        Text(
            "+${fmt(climb.gainM)} / -${fmt(climb.lossM)}",
            style = NoopType.body, color = Palette.textSecondary, modifier = Modifier.weight(1f),
        )
        Text(if (climb.source == "barometer") "barometer" else "GPS", style = NoopType.footnote, color = Palette.textTertiary)
    }
}

/**
 * "How these numbers were worked out" for a workout NOOP Runner recorded itself, so the saved workout
 * says where its distance, calories and zones came from. Hidden once the workout has been edited by hand.
 */
@Composable
internal fun RunnerMethodNote(row: com.noop.data.WorkoutRow, divider: @Composable () -> Unit) {
    val context = LocalContext.current
    val method = remember(row.startTs) { com.noop.intervals.RunMethodStore.load(context, row.startTs) } ?: return
    val reserve = remember(row.startTs) { RunnerHrStore.reserveZones(context) }
    val customZones = remember(row.startTs) { ProfileStore.from(context).hasCustomHrZones }
    val climb = remember(row.startTs) { ElevationStore.load(context, row.startTs) }
    val laps = remember(row.startTs) { com.noop.intervals.IndoorLapStore.load(context, row.startTs) }
    val lines = buildList {
        if (laps != null) {
            add(
                "Distance: ${laps.count} laps on ${laps.trackName.ifBlank { "the track" }} times its lap length " +
                    "(${laps.lapMeters.toInt()} m), counted with the Lap button and confirmed when you ended. GPS was off.",
            )
        }
        if (method.gpsDistance) {
            add("Distance: the GPS route, with standing-still jitter smoothed out. The map shows the route as recorded.")
        }
        when (method.calories) {
            "acsm", "acsm-intervals" -> add(
                "Calories: from distance and pace, using the ACSM running and walking equations (about 1 kcal per kg " +
                    "per km running, half that walking)" +
                    (if (method.calories == "acsm-intervals") ", with each interval step costed as a run or a walk" else "") +
                    (if (climb != null && climb.gainM > 0) ", plus the climb (ACSM grade term)." else "."),
            )
            "hr" -> add("Calories: estimated from heart rate (Keytel equation), because this workout had no distance to go on.")
        }
        add(runnerZoneSentence(reserve, customZones))
    }
    divider()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("How these numbers were worked out", style = NoopType.subhead, color = Palette.textPrimary)
        lines.forEach { Text(it, style = NoopType.footnote, color = Palette.textTertiary) }
    }
}

/** One sentence naming the zone model in use, for the notes under zone charts. */
internal fun runnerZoneSentence(reserve: Boolean, customZones: Boolean): String = when {
    reserve -> "Zones: heart-rate reserve, built from your resting and max heart rate (Settings > Runner heart rate)."
    customZones -> "Zones: your own zone starts (Settings > Profile > Custom HR zones)."
    else -> "Zones: percentage of max heart rate. Zones from your resting heart rate are in Settings > Runner heart rate."
}
