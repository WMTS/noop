package com.noop.ui

import com.noop.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.intervals.CueSettingsStore
import com.noop.intervals.IntervalEngine
import com.noop.intervals.IntervalProgram
import com.noop.intervals.IntervalProgramStore
import com.noop.intervals.StepKind

// MARK: - IntervalsScreen
//
// Fork version (Rick). The upstream screen was a silent haptic HIIT timer with work / rest / rounds.
// This one is a Tabata-app style timer: warm-up, custom step blocks, rounds, sets, rest between sets,
// cool-down, saved programs, and voice + beep + vibration cues so it works with no strap. The timer
// runs in the process-level IntervalEngine, so it keeps going with the screen off. "Run with GPS"
// starts a recorded run with the selected program attached, which records per-interval splits.

@Composable
fun IntervalsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    val st by IntervalEngine.state.collectAsStateWithLifecycle()
    val activeWorkout by vm.activeWorkout.collectAsStateWithLifecycle()

    // Bumped after saves / deletes so the program list re-reads storage.
    var revision by remember { mutableStateOf(0) }
    val programs = remember(revision) { IntervalProgramStore.all(context) }
    var selectedId by remember {
        mutableStateOf(IntervalProgramStore.lastUsedId(context) ?: IntervalProgram.presets.first().id)
    }
    val selected = programs.firstOrNull { it.id == selectedId } ?: programs.first()

    var editing by remember { mutableStateOf<IntervalProgram?>(null) }
    var confirmDelete by remember { mutableStateOf<IntervalProgram?>(null) }
    var showRunSheet by remember { mutableStateOf(false) }
    var cues by remember { mutableStateOf(CueSettingsStore.load(context)) }

    val running = st.active && !st.finished
    val linkedToRun = st.active && st.linkedWorkoutStartMs != null

    // Same backdrop as the other tabs (custom image > Indigo texture > sky > flat canvas).
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(context) }
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(context) }
    LazyScreenScaffold(
        title = uiString(R.string.l10n_intervals_screen_interval_timer_1d703deb),
        subtitle = "Voice, beep and strap cues. Keeps running with the screen off.",
        topBackground = screenBackdropSlot(showDayCycleBackground, skyBehindCards),
        fullBleedBackground = screenBackdropFullBleed(showDayCycleBackground, skyBehindCards),
    ) {
        // --- Status row ---
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (live.bonded && cues.strapBuzz) StatePill("Strap buzz on", tone = StrandTone.Positive)
                else if (cues.voice) StatePill("Voice cues on", tone = StrandTone.Positive)
                else StatePill("Cues off", tone = StrandTone.Warning)
                Spacer(Modifier.weight(1f))
                when {
                    st.paused -> StatePill("Paused", tone = StrandTone.Neutral, showsDot = false)
                    running -> StatePill(if (linkedToRun) "Running with GPS" else "Running", tone = StrandTone.Accent, pulsing = true)
                    st.finished -> StatePill("Complete", tone = StrandTone.Positive)
                    else -> StatePill("Ready", tone = StrandTone.Neutral, showsDot = false)
                }
            }
        }

        // --- Hero: live timer, or the selected program ready to go ---
        item {
            if (st.active) {
                TimerHero(st, linkedToRun)
            } else {
                ReadyHero(
                    program = selected,
                    canRunWithGps = activeWorkout == null,
                    onStart = { IntervalEngine.start(context, selected) },
                    onRunWithGps = {
                        IntervalProgramStore.setLastUsed(context, selected.id)
                        showRunSheet = true
                    },
                )
            }
        }

        // --- Programs ---
        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Overline("Programs")
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                editing = IntervalProgram(
                                    id = IntervalProgramStore.newId(), name = "My intervals",
                                )
                            },
                            enabled = !st.active,
                        ) { Text("New", color = Palette.accent) }
                    }
                    programs.forEachIndexed { i, p ->
                        ProgramRow(
                            program = p,
                            selected = p.id == selected.id,
                            enabled = !st.active,
                            onSelect = { selectedId = p.id },
                            onEdit = {
                                // Presets are read-only: editing one saves an editable copy.
                                editing = if (p.builtIn) p.copy(id = IntervalProgramStore.newId(), name = p.name + " (copy)", builtIn = false) else p
                            },
                            onDelete = if (p.builtIn) null else ({ confirmDelete = p }),
                        )
                        if (i < programs.size - 1) IvDivider()
                    }
                }
            }
        }

        // --- Cue settings ---
        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Overline("Cues")
                    fun update(c: com.noop.intervals.CueSettings) {
                        cues = c
                        CueSettingsStore.save(context, c)
                    }
                    IvSwitchRow("Voice", "Announces each step, round and your last interval's pace.", cues.voice) { update(cues.copy(voice = it)) }
                    IvSwitchRow("Beeps", "A tone at every step change.", cues.beeps) { update(cues.copy(beeps = it)) }
                    IvSwitchRow("3-2-1 countdown", "Ticks on the last three seconds of each step.", cues.countdown) { update(cues.copy(countdown = it)) }
                    IvSwitchRow("Strap buzz", "Triple buzz into work, single into rest (WHOOP strap).", cues.strapBuzz) { update(cues.copy(strapBuzz = it)) }
                    IvSwitchRow("Phone vibration", null, cues.phoneVibrate) { update(cues.copy(phoneVibrate = it)) }
                    IvSwitchRow("Halfway call", "Says \"halfway\" on steps a minute or longer.", cues.halfwayCall) { update(cues.copy(halfwayCall = it)) }
                    if (st.active) {
                        Text("Changes apply to the next session.", style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
            }
        }
    }

    editing?.let { p ->
        IntervalProgramEditorDialog(
            initial = p,
            onDismiss = { editing = null },
            onSave = { saved ->
                IntervalProgramStore.upsert(context, saved)
                selectedId = saved.id
                editing = null
                revision++
            },
        )
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${p.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    IntervalProgramStore.delete(context, p.id)
                    if (selectedId == p.id) selectedId = IntervalProgram.presets.first().id
                    confirmDelete = null
                    revision++
                }) { Text("Delete", color = Palette.statusCritical) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (showRunSheet) {
        StartWorkoutSheet(vm = vm, onDismiss = { showRunSheet = false }, initialProgramId = selected.id)
    }
}

@Composable
private fun ReadyHero(
    program: IntervalProgram,
    canRunWithGps: Boolean,
    onStart: () -> Unit,
    onRunWithGps: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.cardRadius))) {
        if (!IndigoTheme.active) ScenicHeroBackground(modifier = Modifier.matchParentSize(), domain = DomainTheme.Effort)
        NoopCard(padding = 24.dp, tint = if (IndigoTheme.active) IndigoTheme.work else Palette.effortColor) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(program.name, style = NoopType.title2, color = Palette.textPrimary)
                Text(ivSummary(program), style = NoopType.subhead, color = Palette.textSecondary)
                Text(ivClock(program.totalSeconds()), style = NoopType.number(48f), color = Palette.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.surfaceBase),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Timer only", style = NoopType.headline)
                    }
                    Button(
                        onClick = onRunWithGps,
                        enabled = canRunWithGps,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (IndigoTheme.active) IndigoTheme.work else Palette.effortColor,
                            contentColor = Palette.surfaceBase,
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Run with GPS", style = NoopType.headline)
                    }
                }
                if (!canRunWithGps) {
                    Text(
                        "A workout is already recording. Open it to add intervals.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerHero(st: IntervalEngine.State, linkedToRun: Boolean) {
    val cur = st.current
    val color = if (st.finished) Palette.statusPositive else ivColor(cur?.kind)
    val stops = when {
        st.finished -> listOf(0f to Palette.statusPositive.copy(alpha = 0.6f), 1f to Palette.statusPositive)
        cur?.kind == StepKind.WORK -> if (IndigoTheme.active) IndigoTheme.workStops else Palette.effortGradientStops
        cur?.kind == StepKind.REST || cur?.kind == StepKind.SET_REST ->
            if (IndigoTheme.active) IndigoTheme.restStops else Palette.restGradientStops
        else -> listOf(0f to Palette.statusPositive.copy(alpha = 0.6f), 1f to Palette.statusPositive)
    }
    val fraction = if (st.finished || cur == null) 1.0
    else ((cur.seconds - st.remainingS).toDouble() / cur.seconds.coerceAtLeast(1)).coerceIn(0.0, 1.0)

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.cardRadius))) {
        if (!IndigoTheme.active) ScenicHeroBackground(modifier = Modifier.matchParentSize(), domain = DomainTheme.Effort)
        NoopCard(padding = 24.dp, tint = color) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IvChip(if (st.finished) "DONE" else (cur?.name ?: "").uppercase(), color)
                    Spacer(Modifier.weight(1f))
                    if (cur != null && cur.round > 0 && cur.rounds > 1) {
                        Text(
                            "Round ${cur.round}/${cur.rounds}" + if (cur.sets > 1) "  Set ${cur.set}/${cur.sets}" else "",
                            style = NoopType.number(16f), color = Palette.textSecondary,
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BevelGauge(
                        fraction = fraction,
                        stops = stops,
                        tipColor = color,
                        numberText = if (st.finished) "✓" else ivClock(st.remainingS),
                        captionText = if (st.finished) "SESSION DONE" else st.next?.let { "NEXT ${it.name.uppercase()}" } ?: "LAST STEP",
                        diameter = 240.dp,
                        lineWidth = 18.dp,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("${ivClock(st.elapsedS)} / ${ivClock(st.totalS)}", style = NoopType.bodyNumber, color = Palette.textPrimary)
                    Spacer(Modifier.weight(1f))
                    Text("${ivClock(st.totalS - st.elapsedS)} left", style = NoopType.bodyNumber, color = Palette.textSecondary)
                }
                IvProgressBar(
                    if (st.totalS > 0) st.elapsedS.toFloat() / st.totalS else 0f,
                    if (IndigoTheme.active) IndigoTheme.work else Palette.effortColor,
                )

                if (linkedToRun) {
                    Text(
                        "Attached to your recording run. Pause and End it from the workout screen.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                } else if (st.finished) {
                    Button(
                        onClick = { IntervalEngine.dismissFinished() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.surfaceBase),
                    ) { Text("Done", style = NoopType.headline) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { IntervalEngine.togglePause() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.surfaceBase),
                        ) {
                            Icon(if (st.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text(if (st.paused) "Resume" else "Pause", style = NoopType.headline)
                        }
                        OutlinedButton(onClick = { IntervalEngine.skip() }, enabled = !st.paused, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text("Skip", style = NoopType.headline)
                        }
                        OutlinedButton(
                            onClick = { IntervalEngine.stop(saveSplits = false) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.statusCritical),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text("Stop", style = NoopType.headline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramRow(
    program: IntervalProgram,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                program.name, style = NoopType.body,
                color = if (selected) Palette.accent else Palette.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${ivClock(program.totalSeconds())} · ${ivSummary(program)}",
                style = NoopType.footnote, color = Palette.textTertiary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onEdit, enabled = enabled) {
            Text(if (program.builtIn) "Copy" else "Edit", color = Palette.accent)
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete, enabled = enabled) { Text("Delete", color = Palette.statusCritical) }
        }
    }
}

@Composable
private fun IvChip(label: String, color: Color) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = NoopType.number(15f, weight = FontWeight.Bold).copy(letterSpacing = 2.sp),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
