package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// Fork addition (Rick): NOOP Runner's home-screen entry and the "show me the run I just finished" hook.

/**
 * Today's "Start workout" card, in the slot the upstream "Start session (beta)" card used. Opens the
 * normal start screen (sport, GPS, intervals, HR alert, shoes).
 */
@Composable
internal fun StartWorkoutEntryCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val shoe = com.noop.intervals.ShoeStore.find(context, com.noop.intervals.ShoeStore.defaultId(context))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    // One-time setup checklist for a new NOOP Runner user (hidden once done or dismissed).
    RunnerSetupChecklist()
    NoopCard(
        tint = Palette.accent,
        modifier = Modifier.clickable(onClick = onOpen),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.DirectionsRun,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Start workout", style = NoopType.headline, color = Palette.textPrimary)
                Text(
                    "Outdoor or indoor track, intervals, HR alert" + (shoe?.let { " and shoes (${it.name})" } ?: " and shoes"),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    }
}

/**
 * Opens the saved workout's detail as soon as a recorded workout ends, wherever End was pressed, instead
 * of leaving you on the Workouts totals. Hosted once in AppRoot.
 */
@Composable
internal fun FinishedWorkoutHost(vm: AppViewModel) {
    val finished by vm.finishedWorkout.collectAsStateWithLifecycle()
    finished?.let { row ->
        WorkoutDetailSheet(vm = vm, row = row, onDismiss = { vm.clearFinishedWorkout() })
    }
}

// Fork addition (Rick): the Today workouts list shows this week, Sunday to Saturday, in local time.

private fun runnerWeekStart(nowMs: Long = System.currentTimeMillis()): java.time.ZonedDateTime {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val sunday = today.minusDays((today.dayOfWeek.value % 7).toLong()) // Sunday = 7 -> 0 days back
    return sunday.atStartOfDay(zone)
}

/** Workouts that started this week (Sunday 00:00 to the next Sunday), newest first, cross-source duplicates removed. */
internal fun runnerThisWeekWorkouts(rows: List<com.noop.data.WorkoutRow>, nowMs: Long = System.currentTimeMillis()): List<com.noop.data.WorkoutRow> {
    val start = runnerWeekStart(nowMs).toEpochSecond()
    val end = start + 7 * 86_400L
    return com.noop.ui.WorkoutEditing.dedupCrossSource(rows).filter { it.startTs in start until end }.sortedByDescending { it.startTs }
}

/** "Nov 22 to 28" (or "Nov 29 to Dec 5") for the week shown. */
internal fun runnerWeekRangeLabel(nowMs: Long = System.currentTimeMillis()): String {
    val start = runnerWeekStart(nowMs).toLocalDate()
    val end = start.plusDays(6)
    val m = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.getDefault())
    val d = java.time.format.DateTimeFormatter.ofPattern(if (end.month == start.month) "d" else "MMM d", java.util.Locale.getDefault())
    return start.format(m) + " to " + end.format(d)
}
