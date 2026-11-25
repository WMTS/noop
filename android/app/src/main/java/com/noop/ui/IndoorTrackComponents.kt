package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.intervals.IndoorLapSession
import com.noop.intervals.IndoorLapStore
import com.noop.intervals.IndoorLaps
import com.noop.intervals.IndoorTrack
import com.noop.intervals.IndoorTrackStore
import kotlinx.coroutines.delay
import java.util.Locale

// Fork addition (Rick): indoor track mode UI. The Outdoor / Indoor choice and track picker on the start
// screen, the Lap panel on the live workout, the lap-count correction on the End prompt, and the Laps
// section on a saved workout.

// MARK: - Start screen

/** "Outdoor (GPS)" or "Indoor track", and which track, for runs and walks. */
@Composable
internal fun IndoorModeRow(indoor: Boolean, onIndoorChange: (Boolean) -> Unit, track: IndoorTrack?, onPickTrack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SegmentedPillControl(
            items = listOf(false, true),
            selection = indoor,
            label = { if (it) "Indoor track" else "Outdoor (GPS)" },
            onSelect = onIndoorChange,
            modifier = Modifier.fillMaxWidth(),
            adaptsToAvailableWidth = true,
        )
        if (indoor) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Track", style = NoopType.body, color = Palette.textPrimary)
                Spacer(Modifier.width(12.dp))
                Text(
                    track?.let { "${it.name} · ${it.lengthLabel()}" } ?: "Add your track",
                    style = NoopType.body,
                    color = if (track != null) Palette.accent else Palette.textTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPickTrack) { Text(if (track != null) "Change" else "Add") }
            }
            Text(
                "GPS stays off. Tap Lap each time you pass your start line; you can fix the count when you end.",
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
    }
}

/** Pick, add, edit or delete saved tracks. */
@Composable
internal fun IndoorTrackPickerDialog(selectedId: String?, onDismiss: () -> Unit, onPick: (IndoorTrack) -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val tracks = remember(revision) { IndoorTrackStore.tracks(context) }
    var editing by remember { mutableStateOf<IndoorTrack?>(null) }
    var adding by remember { mutableStateOf(tracks.isEmpty()) }

    if (adding || editing != null) {
        IndoorTrackEditorDialog(
            track = editing,
            onDismiss = {
                if (tracks.isEmpty()) onDismiss()
                adding = false; editing = null
            },
            onSave = { t ->
                IndoorTrackStore.upsert(context, t)
                adding = false; editing = null; revision++
                onPick(t)
            },
            onDelete = editing?.let { t -> { IndoorTrackStore.delete(context, t.id); revision++; editing = null } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Indoor track") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tracks.forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(t) },
                    ) {
                        RadioButton(selected = t.id == selectedId, onClick = { onPick(t) })
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = NoopType.body, color = Palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.lengthLabel(), style = NoopType.footnote, color = Palette.textTertiary)
                        }
                        TextButton(onClick = { editing = t }) { Text("Edit") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { adding = true }) { Text("Add track") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Name plus either laps per mile or metres per lap. */
@Composable
internal fun IndoorTrackEditorDialog(
    track: IndoorTrack?,
    onDismiss: () -> Unit,
    onSave: (IndoorTrack) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(track?.name ?: "") }
    var byMeters by remember { mutableStateOf(track != null && track.lapsPerMile == null) }
    var value by remember {
        mutableStateOf(
            when {
                track == null -> ""
                track.lapsPerMile != null -> IndoorLaps.trim(track.lapsPerMile)
                else -> IndoorLaps.trim(track.lapMeters)
            },
        )
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val number = value.replace(',', '.').toDoubleOrNull()
    val valid = name.isNotBlank() && if (byMeters) IndoorLaps.validLapMeters(number) else IndoorLaps.validLapsPerMile(number)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (track == null) "Add a track" else "Edit track") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Name (e.g. YMCA)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                SegmentedPillControl(
                    items = listOf(false, true),
                    selection = byMeters,
                    label = { if (it) "Metres per lap" else "Laps per mile" },
                    onSelect = { byMeters = it },
                    modifier = Modifier.fillMaxWidth(),
                    adaptsToAvailableWidth = true,
                )
                OutlinedTextField(
                    value = value, onValueChange = { value = it.take(8) },
                    label = { Text(if (byMeters) "Metres per lap" else "Laps per mile") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (byMeters) "Most indoor tracks post this by the door. 200 m is a common size."
                    else "Usually posted at the track, for example 9 or 12 laps per mile.",
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete this track", color = Palette.statusCritical)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val n = number ?: return@Button
                val lapM = if (byMeters) n else IndoorLaps.lapMetersFromLapsPerMile(n)
                onSave(IndoorTrack(track?.id ?: IndoorTrackStore.newId(), name.trim(), lapM, if (byMeters) null else n))
            }) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this track?") },
            text = { Text("Past workouts keep their laps and distance.") },
            confirmButton = { Button(onClick = { confirmDelete = false }) { Text("Keep it") } },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = Palette.statusCritical) }
            },
        )
    }
}

// MARK: - Live workout

/** Laps, distance, last lap and pace, with a big Lap button. Only for the indoor workout in progress. */
@Composable
internal fun IndoorLapPanel(workoutStartMs: Long, activeElapsedS: Long) {
    val context = LocalContext.current
    LaunchedEffect(workoutStartMs) { IndoorLapSession.restoreIfNeeded(context) }
    val s by IndoorLapSession.state.collectAsState()
    if (!s.active || s.workoutStartMs != workoutStartMs) return
    val unit = UnitPrefs.distanceSystem(context)
    var ignoredAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val lastLapMs = if (s.taps.isNotEmpty()) IndoorLaps.lapDurationsMs(s.workoutStartMs, s.taps).last() else null
    val thisLapMs = now - (s.taps.lastOrNull() ?: s.workoutStartMs)
    val secPerKm = if (s.meters > 0) activeElapsedS / (s.meters / 1000.0) else null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
            StatTile(label = "Laps", value = "${s.laps}", modifier = Modifier.weight(1f), accent = Palette.effortColor)
            StatTile(label = "Distance", value = UnitFormatter.distanceFromMeters(s.meters, unit), modifier = Modifier.weight(1f), accent = Palette.effortColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
            StatTile(label = "This lap", value = IndoorLaps.clock(thisLapMs), modifier = Modifier.weight(1f))
            StatTile(label = "Last lap", value = lastLapMs?.let { IndoorLaps.clock(it) } ?: "-", modifier = Modifier.weight(1f))
            StatTile(label = "Pace", value = UnitFormatter.paceFromSecPerKm(secPerKm, unit), modifier = Modifier.weight(1f))
        }
        Button(
            onClick = { if (!IndoorLapSession.lap(context)) ignoredAt = System.currentTimeMillis() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Color.White),
        ) {
            Text("LAP ${s.laps + 1}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(
            if (now - ignoredAt < 4000) "That tap came within 10 seconds of the last lap, so it was not counted."
            else "${s.track?.name ?: "Track"} · ${s.track?.lengthLabel() ?: ""}. The Lap button is also in your notifications.",
            style = NoopType.footnote,
            color = if (now - ignoredAt < 4000) Palette.statusCritical else Palette.textTertiary,
        )
    }
}

/** On the End prompt: the lap count, with - and + to fix a missed or extra tap. */
@Composable
internal fun IndoorEndAdjust(workoutStartMs: Long) {
    val context = LocalContext.current
    val s by IndoorLapSession.state.collectAsState()
    if (!s.active || s.workoutStartMs != workoutStartMs) return
    val track = s.track ?: return
    val count = s.finalCount ?: s.laps
    val unit = UnitPrefs.distanceSystem(context)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
        Text("Laps", style = NoopType.headline, color = Palette.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { IndoorLapSession.setFinalCount((count - 1).coerceAtLeast(0)) }) {
                Text("-", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "$count",
                fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            OutlinedButton(onClick = { IndoorLapSession.setFinalCount(count + 1) }) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "= " + UnitFormatter.distanceFromMeters(count * track.lapMeters, unit) +
                if (count != s.laps) " (you tapped ${s.laps})" else "",
            style = NoopType.subhead, color = Palette.textSecondary,
        )
    }
}

// MARK: - Saved workout

/** Lap count, track, fastest and average lap, and each lap's time. */
@Composable
internal fun IndoorLapsSection(startTs: Long, divider: @Composable () -> Unit) {
    val context = LocalContext.current
    val r = remember(startTs) { IndoorLapStore.load(context, startTs) } ?: return
    val unit = UnitPrefs.distanceSystem(context)
    divider()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Laps", style = NoopType.subhead, color = Palette.textPrimary)
        val length = if (r.lapsPerMile != null) "${IndoorLaps.trim(r.lapsPerMile)} laps per mile" else "${r.lapMeters.toInt()} m per lap"
        Text(
            "${r.count} laps on ${r.trackName.ifBlank { "the track" }} ($length) = ${UnitFormatter.distanceFromMeters(r.meters, unit)}",
            style = NoopType.body, color = Palette.textSecondary,
        )
        if (r.lapMs.isNotEmpty()) {
            val avg = r.lapMs.average().toLong()
            Text(
                "Fastest ${IndoorLaps.clock(r.lapMs.min())} · average ${IndoorLaps.clock(avg)}",
                style = NoopType.footnote, color = Palette.textTertiary,
            )
            Text(
                r.lapMs.mapIndexed { i, ms -> String.format(Locale.US, "%d. %s", i + 1, IndoorLaps.clock(ms)) }.joinToString("   "),
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}
