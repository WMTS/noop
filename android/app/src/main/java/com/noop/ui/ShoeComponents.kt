package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.data.WorkoutRow
import com.noop.intervals.Shoe
import com.noop.intervals.ShoeMath
import com.noop.intervals.ShoeStore
import java.util.Locale

// Fork addition (Rick): shoe mileage tracker UI. Settings card (add / edit / retire pairs, set the
// default, adjust miles by hand), the Shoe line on the start screen, and the shoe line on a saved run.

private fun metersPerUnit(unit: UnitSystem) = if (unit == UnitSystem.IMPERIAL) Shoe.METERS_PER_MILE else 1000.0
private fun unitLabel(unit: UnitSystem) = if (unit == UnitSystem.IMPERIAL) "mi" else "km"

internal fun shoeDistance(meters: Double, unit: UnitSystem): String =
    String.format(Locale.US, "%.1f %s", meters / metersPerUnit(unit), unitLabel(unit))

// MARK: - Settings card

@Composable
internal fun ShoesSettingsCard() {
    val context = LocalContext.current
    val unit = UnitPrefs.distanceSystem(context)
    var revision by remember { mutableIntStateOf(0) }
    val shoes = remember(revision) { ShoeStore.shoes(context) }
    val runs = remember(revision) { ShoeStore.runs(context) }
    val defaultId = remember(revision) { ShoeStore.defaultId(context) }
    var editing by remember { mutableStateOf<Shoe?>(null) }
    var showRetired by remember { mutableStateOf(false) }

    SettingsCard(
        icon = Icons.AutoMirrored.Filled.DirectionsRun,
        title = "Shoes",
        blurb = "Track the miles on each pair. Runs you record add to the shoe picked on the start screen. " +
            "Tap a pair to set its starting miles, add runs done without NOOP, or retire it.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val visible = shoes.filter { showRetired || !it.retired }
            if (visible.isEmpty()) {
                Text("No shoes yet.", style = NoopType.body, color = Palette.textTertiary)
            }
            visible.forEach { shoe ->
                val total = ShoeMath.totalMeters(shoe, runs)
                val over = ShoeMath.overLimit(shoe, runs)
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { editing = shoe }.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            shoe.name, style = NoopType.headline,
                            color = if (shoe.retired) Palette.textTertiary else Palette.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (shoe.id == defaultId) {
                            StatePill("Default", tone = StrandTone.Accent, showsDot = false)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (shoe.retired) {
                            StatePill("Retired", tone = StrandTone.Neutral, showsDot = false)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            shoeDistance(total, unit), style = NoopType.bodyNumber,
                            color = if (over) Palette.statusCritical else Palette.textPrimary,
                        )
                    }
                    if (shoe.limitMeters > 0) {
                        IvProgressBar(
                            (total / shoe.limitMeters).toFloat(),
                            if (over) Palette.statusCritical else Palette.accent,
                        )
                        Text(
                            if (over) "Past its ${shoeDistance(shoe.limitMeters, unit)} limit. Time for a new pair?"
                            else "${shoeDistance((shoe.limitMeters - total).coerceAtLeast(0.0), unit)} left of ${shoeDistance(shoe.limitMeters, unit)}",
                            style = NoopType.footnote,
                            color = if (over) Palette.statusCritical else Palette.textTertiary,
                        )
                    }
                }
                IvDivider()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { editing = Shoe(id = ShoeStore.newId(), name = "") },
                    modifier = Modifier.weight(1f),
                ) { Text("Add shoes") }
                if (shoes.any { it.retired }) {
                    OutlinedButton(onClick = { showRetired = !showRetired }, modifier = Modifier.weight(1f)) {
                        Text(if (showRetired) "Hide retired" else "Show retired")
                    }
                }
            }
        }
    }

    editing?.let { shoe ->
        ShoeEditorDialog(
            shoe = shoe,
            isNew = shoes.none { it.id == shoe.id },
            loggedMeters = runs.filter { it.shoeId == shoe.id }.sumOf { it.meters },
            loggedRuns = runs.count { it.shoeId == shoe.id },
            isDefault = shoe.id == defaultId,
            unit = unit,
            onDismiss = { editing = null },
            onSave = { saved, makeDefault ->
                ShoeStore.upsert(context, saved)
                if (makeDefault || ShoeStore.shoes(context).size == 1) ShoeStore.setDefault(context, saved.id)
                editing = null
                revision++
            },
            onDelete = {
                ShoeStore.delete(context, shoe.id)
                editing = null
                revision++
            },
        )
    }
}

@Composable
private fun ShoeEditorDialog(
    shoe: Shoe,
    isNew: Boolean,
    loggedMeters: Double,
    loggedRuns: Int,
    isDefault: Boolean,
    unit: UnitSystem,
    onDismiss: () -> Unit,
    onSave: (Shoe, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val per = metersPerUnit(unit)
    fun fmt(m: Double) = String.format(Locale.US, "%.1f", m / per)
    var name by remember { mutableStateOf(shoe.name) }
    var baseText by remember { mutableStateOf(fmt(shoe.baseMeters)) }
    var addText by remember { mutableStateOf("") }
    var limitText by remember { mutableStateOf(if (shoe.limitMeters > 0) String.format(Locale.US, "%.0f", shoe.limitMeters / per) else "") }
    var retired by remember { mutableStateOf(shoe.retired) }
    var makeDefault by remember { mutableStateOf(isDefault) }
    var confirmDelete by remember { mutableStateOf(false) }
    val u = unitLabel(unit)

    val base = baseText.replace(',', '.').toDoubleOrNull()
    val add = addText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val limit = limitText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val valid = name.isNotBlank() && base != null && base >= 0 && (base + add) >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add shoes" else "Edit shoes") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (e.g. Pegasus 41)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseText, onValueChange = { baseText = it },
                    label = { Text("Miles before NOOP / manual ($u)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = addText, onValueChange = { addText = it },
                    label = { Text("Add a run done without NOOP ($u)") }, singleLine = true,
                    placeholder = { Text("e.g. 3.1, or -1 to take off") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isNew) {
                    Text(
                        "Logged in NOOP: ${shoeDistance(loggedMeters, unit)} over $loggedRuns run${if (loggedRuns == 1) "" else "s"}",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
                Text(
                    "Total: ${shoeDistance(((base ?: 0.0) + add) * per + loggedMeters, unit)}",
                    style = NoopType.bodyNumber, color = Palette.accent,
                )
                OutlinedTextField(
                    value = limitText, onValueChange = { limitText = it },
                    label = { Text("Replace at ($u, blank for no warning)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                IvSwitchRow("Default for new runs", null, makeDefault && !retired) { makeDefault = it }
                IvSwitchRow("Retired", "Hidden from the start screen; miles are kept.", retired) { retired = it }
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete these shoes", color = Palette.statusCritical)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        shoe.copy(
                            name = name.trim(),
                            baseMeters = (((base ?: 0.0) + add) * per).coerceAtLeast(0.0),
                            limitMeters = (limit * per).coerceAtLeast(0.0),
                            retired = retired,
                        ),
                        makeDefault && !retired,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${shoe.name}\"?") },
            text = { Text("Its mileage is removed. Your runs stay in NOOP. Use Retired instead to keep the miles.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = Palette.statusCritical) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// MARK: - Picker (start screen + saved run)

/** Choose a pair (or none). Shows each active pair with its current total. */
@Composable
internal fun ShoePickerDialog(selectedId: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val context = LocalContext.current
    val unit = UnitPrefs.distanceSystem(context)
    val shoes = remember { ShoeStore.activeShoes(context) }
    val runs = remember { ShoeStore.runs(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shoes for this run") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "None", style = NoopType.body,
                    color = if (selectedId == null) Palette.accent else Palette.textPrimary,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp),
                )
                shoes.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(s.id) }.padding(vertical = 12.dp),
                    ) {
                        Text(
                            s.name, style = NoopType.body,
                            color = if (s.id == selectedId) Palette.accent else Palette.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(shoeDistance(ShoeMath.totalMeters(s, runs), unit), style = NoopType.bodyNumber, color = Palette.textTertiary)
                    }
                }
                if (shoes.isEmpty()) {
                    Text(
                        "Add shoes in Settings > Shoes.", style = NoopType.footnote, color = Palette.textTertiary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Shoes: Pegasus 41 (212.4 mi) · Change" on a saved run, so a wrong pick can be fixed afterwards. */
@Composable
internal fun WorkoutShoeSection(row: WorkoutRow) {
    val context = LocalContext.current
    val unit = UnitPrefs.distanceSystem(context)
    var revision by remember { mutableIntStateOf(0) }
    val link = remember(row.startTs, revision) { ShoeStore.shoeForRun(context, row.startTs) }
    val shoe = remember(link) { ShoeStore.find(context, link?.shoeId) }
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Shoes", style = NoopType.subhead, color = Palette.textPrimary)
        Spacer(Modifier.width(12.dp))
        Text(
            shoe?.let { "${it.name} (${shoeDistance(ShoeStore.totalMeters(context, it), unit)})" } ?: "None",
            style = NoopType.body,
            color = if (shoe != null) Palette.textSecondary else Palette.textTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { picking = true }) { Text("Change") }
    }
    if (picking) {
        ShoePickerDialog(
            selectedId = shoe?.id,
            onDismiss = { picking = false },
            onPick = { id ->
                if (id == null) ShoeStore.unassign(context, row.startTs)
                else ShoeStore.assign(context, row.startTs, id, row.distanceM ?: 0.0)
                picking = false
                revision++
            },
        )
    }
}
