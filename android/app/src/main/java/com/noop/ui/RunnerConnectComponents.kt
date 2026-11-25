package com.noop.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.NoopApplication
import com.noop.data.PairedDeviceRow
import com.noop.intervals.IndoorTrackStore
import com.noop.intervals.RunnerHrStore
import com.noop.intervals.ShoeStore
import com.noop.intervals.StravaStore
import com.noop.intervals.StravaSync
import com.noop.intervals.WorkoutStrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Fork addition (Rick): Strava and chest strap settings, the Strava / export section on a saved workout, and
// the one-time setup checklist on Today. Everything here is per phone, so each runner sets up their own.

@Composable
private fun RunnerSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.surfaceBase,
            checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.textSecondary,
            uncheckedTrackColor = Palette.surfaceInset,
            uncheckedBorderColor = Palette.hairline,
        ),
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// MARK: - Strava

@Composable
internal fun StravaSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by StravaStore.version.collectAsState()
    val connected = remember(version) { StravaStore.connected(context) }
    val hasKeys = remember(version) { StravaStore.hasKeys(context) }
    val athlete = remember(version) { StravaStore.athleteName(context) }
    val last = remember(version) { StravaStore.lastResult(context) }
    var editingKeys by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf(StravaStore.clientId(context)) }
    var secret by remember { mutableStateOf("") }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    SettingsCard(
        icon = Icons.Filled.CloudUpload,
        title = "Strava",
        blurb = "Send workouts to Strava when you press End. Each runner connects their own Strava account; " +
            "the keys and sign-in stay on this phone and carry over through updates.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) {
                Text(
                    "Connected" + if (athlete.isNotBlank()) " as $athlete" else "",
                    style = NoopType.headline, color = Palette.accent,
                )
                SettingsFormRow(label = "Upload when I press End") {
                    RunnerSwitch(StravaStore.autoUpload(context), { StravaStore.setAutoUpload(context, it) })
                }
                SettingsFormRow(label = "Keep off followers' feeds") {
                    RunnerSwitch(StravaStore.mute(context), { StravaStore.setMute(context, it) })
                }
                Text(
                    "Strava does not let apps make an activity private. Uploads use your own Strava privacy setting " +
                        "(Strava > Settings > Privacy Controls). This switch only keeps them out of followers' home feeds.",
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
                SettingsRowDivider()
                Text("Send automatically", style = NoopType.subhead, color = Palette.textPrimary)
                listOf("runs" to "Runs", "walks" to "Walks and hikes", "rides" to "Rides", "other" to "Other workouts").forEach { (g, label) ->
                    SettingsFormRow(label = label) { RunnerSwitch(StravaStore.sends(context, g), { StravaStore.setSends(context, g, it) }) }
                }
                SettingsRowDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { StravaSync.disconnect(context, forgetKeys = false) }
                            busy = false
                        }
                    }, enabled = !busy) { Text("Disconnect") }
                }
            } else if (!hasKeys || editingKeys) {
                TextButton(onClick = { showGuide = !showGuide }) {
                    Text(if (showGuide) "Hide the setup steps" else "How to get your Strava keys (5 minutes)")
                }
                if (showGuide || !hasKeys) {
                    listOf(
                        "1. In a web browser, sign in to Strava and open strava.com/settings/api. Strava requires a subscription for whoever creates this API app. A friend's app works too (step 5).",
                        "2. Fill in: Application Name: NOOP Runner. Category: Training. Website: https://github.com/WMTS/noop. Authorization Callback Domain: localhost. If it asks for an icon, any square picture is fine.",
                        "3. Save, then copy the Client ID and the Client Secret into the boxes below.",
                        "4. Tap Save keys, then Connect, and approve in Strava.",
                        "5. Sharing your app with friends: on the same Strava page, raise the athlete limit to 10 (no review needed) and send them the two codes privately. Never post the Client Secret anywhere public.",
                    ).forEach { Text(it, style = NoopType.footnote, color = Palette.textSecondary) }
                }
                OutlinedTextField(
                    value = id, onValueChange = { id = it.filter(Char::isDigit).take(12) },
                    label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it.trim().take(80) },
                    label = { Text("Client Secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = id.isNotBlank() && secret.length >= 20, onClick = {
                        StravaStore.saveKeys(context, id, secret)
                        secret = ""; editingKeys = false
                    }) { Text("Save keys") }
                    if (hasKeys) OutlinedButton(onClick = { editingKeys = false }) { Text("Cancel") }
                }
            } else {
                Text(
                    "Keys saved. Tap Connect and approve in Strava (in the Strava app if it opens). You come straight back here.",
                    style = NoopType.footnote, color = Palette.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { openUrl(context, StravaSync.authorizeUrl(context, viaApp = true)) }) { Text("Connect") }
                    OutlinedButton(onClick = { editingKeys = true; id = StravaStore.clientId(context) }) { Text("Change keys") }
                }
                TextButton(onClick = {
                    pasting = true
                    openUrl(context, StravaSync.authorizeUrl(context, viaApp = false))
                }) { Text("Didn't come back? Connect another way") }
                if (pasting) {
                    Text(
                        "After you approve, the browser shows a page that won't load. Copy that page's whole address and paste it here.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                    OutlinedTextField(
                        value = pasted, onValueChange = { pasted = it.trim() },
                        label = { Text("Address from the browser") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(enabled = pasted.contains("code=") && !busy, onClick = {
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { StravaSync.finishSignIn(context, pasted) }
                            busy = false; pasting = false; pasted = ""
                        }
                    }) { Text("Finish connecting") }
                }
            }
            if (last.isNotBlank()) Text(last, style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

/** On a saved workout: Strava status, Send to Strava and Export TCX. */
@Composable
internal fun StravaWorkoutSection(vm: AppViewModel, row: com.noop.data.WorkoutRow, divider: @Composable () -> Unit) {
    val context = LocalContext.current
    val version by StravaStore.version.collectAsState()
    val connected = remember(version) { StravaStore.connected(context) }
    val status = remember(version, row.startTs) { StravaStore.status(context, row.startTs) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val sent = status?.startsWith("sent") == true || status == "duplicate"
    divider()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Strava and export", style = NoopType.subhead, color = Palette.textPrimary)
        Text(
            when {
                status == null -> if (connected) "Not sent to Strava yet." else "Connect Strava in Settings > Strava to send workouts."
                status == "pending" -> "Waiting to send to Strava. It retries automatically."
                status == "sent:processing" -> "Sent to Strava (still processing)."
                status.startsWith("sent") -> "Sent to Strava."
                status == "duplicate" -> "Already on Strava."
                else -> "Last try failed: " + status.removePrefix("error:")
            },
            style = NoopType.footnote, color = if (sent) Palette.accent else Palette.textTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = connected && !busy, onClick = {
                busy = true; msg = null
                vm.forkSendToStrava(row) { m -> msg = m; busy = false }
            }) { Text(if (busy) "Sending..." else if (sent) "Send again" else "Send to Strava") }
            OutlinedButton(onClick = { vm.forkExportWorkout(context, row) }) { Text("Export TCX") }
        }
        msg?.let { Text(it, style = NoopType.footnote, color = Palette.textSecondary) }
        Text(
            "Export TCX makes a file with heart rate, laps and the route for Strava's website upload, Garmin Connect and others.",
            style = NoopType.footnote, color = Palette.textTertiary,
        )
    }
}

// MARK: - Chest strap

@Composable
internal fun ChestStrapSettingsCard(vm: AppViewModel) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<PairedDeviceRow>?>(null) }
    LaunchedEffect(Unit) {
        rows = runCatching {
            withContext(Dispatchers.IO) { (context.applicationContext as NoopApplication).deviceRegistry.all() }
        }.getOrNull().orEmpty()
    }
    var selected by remember { mutableStateOf(WorkoutStrap.selectedId(context)) }
    val st by WorkoutStrap.state.collectAsState()
    val workout by vm.activeWorkout.collectAsStateWithLifecycle()
    var testing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (testing && vm.activeWorkout.value == null) WorkoutStrap.stop() }
    }

    SettingsCard(
        icon = Icons.Filled.Bluetooth,
        title = "Chest strap for workouts",
        blurb = "Use a Bluetooth chest strap (Polar, Garmin, Wahoo and others) for heart rate during workouts, while " +
            "your WHOOP stays connected for sleep and recovery. Chest straps react faster during intervals.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val list = WorkoutStrap.candidates(rows.orEmpty())
            if (rows != null && list.isEmpty()) {
                Text(
                    "No chest strap paired yet. Put the strap on (wet the pads so it wakes up), then pair it in Devices > " +
                        "Add device. Keep your WHOOP as the active device: this setting connects the strap only during workouts.",
                    style = NoopType.footnote, color = Palette.textSecondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                selected = null; WorkoutStrap.select(context, null)
            }) {
                RadioButton(selected = selected == null, onClick = { selected = null; WorkoutStrap.select(context, null) })
                Text("None (WHOOP only)", style = NoopType.body, color = Palette.textPrimary)
            }
            list.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                    selected = r.id; WorkoutStrap.select(context, r)
                }) {
                    RadioButton(selected = selected == r.id, onClick = { selected = r.id; WorkoutStrap.select(context, r) })
                    Column {
                        Text(r.nickname ?: r.model, style = NoopType.body, color = Palette.textPrimary)
                        Text(r.brand, style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
            }
            if (selected != null) {
                SettingsRowDivider()
                Text(
                    when {
                        st.running && st.bpm != null && WorkoutStrap.isFresh() -> "Connected: ${st.bpm} bpm"
                        st.running -> "Connecting... make sure the strap is on and the pads are wet."
                        workout != null -> "Not connected. It connects when you start a workout."
                        else -> "Connects automatically when you start a workout."
                    },
                    style = NoopType.subhead,
                    color = if (st.running && st.bpm != null) Palette.accent else Palette.textSecondary,
                )
                if (workout == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!st.running) {
                            OutlinedButton(onClick = { testing = true; WorkoutStrap.test(context) }) { Text("Test connection") }
                        } else {
                            OutlinedButton(onClick = { testing = false; WorkoutStrap.stop() }) { Text("Stop test") }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Setup checklist

/** The one-time "Set up NOOP Runner" list on Today, until everything is done or it is hidden. */
@Composable
internal fun RunnerSetupChecklist() {
    val context = LocalContext.current
    val prefs = remember { context.applicationContext.getSharedPreferences("runner_setup", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getBoolean("hidden", false)) }
    if (hidden) return
    val version by StravaStore.version.collectAsState()
    val items = remember(version) {
        val profile = ProfileStore.from(context)
        val snap = profile.backupSnapshot()
        listOf(
            Triple("Your profile (age and weight)", snap.containsKey("profile.weightKg") && snap.containsKey("profile.age"), "Settings > Profile"),
            Triple("Your shoes", ShoeStore.shoes(context).isNotEmpty(), "Settings > Shoes"),
            Triple("Max heart rate or resting-HR zones", profile.hrMaxOverride > 0 || RunnerHrStore.reserveZones(context), "Settings > Runner heart rate"),
            Triple("Indoor track, if you use one", IndoorTrackStore.tracks(context).isNotEmpty(), "Start a workout > Indoor track"),
            Triple("Strava, if you use it", StravaStore.connected(context), "Settings > Strava"),
            Triple("Chest strap, if you have one", WorkoutStrap.selectedId(context) != null, "Settings > Chest strap for workouts"),
        )
    }
    if (items.all { it.second }) return
    NoopCard(tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Set up NOOP Runner", style = NoopType.headline, color = Palette.textPrimary)
            Text("${items.count { it.second }} of ${items.size} done. Skip anything you don't use.", style = NoopType.footnote, color = Palette.textTertiary)
            items.forEach { (label, done, where) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (done) "Done" else "Not done",
                        tint = if (done) Palette.accent else Palette.textTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = NoopType.body, color = if (done) Palette.textSecondary else Palette.textPrimary)
                        if (!done) Text(where, style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
            }
            TextButton(onClick = { prefs.edit().putBoolean("hidden", true).apply(); hidden = true }) { Text("Hide this list") }
        }
    }
}
