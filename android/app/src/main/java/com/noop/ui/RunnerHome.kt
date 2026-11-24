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
                    "Pick a sport, intervals, HR alert" + (shoe?.let { " and shoes (${it.name})" } ?: " and shoes"),
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
