package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.intervals.IntervalEngine
import com.noop.intervals.IntervalProgram
import com.noop.intervals.IntervalProgramStore
import com.noop.intervals.IntervalSplit
import com.noop.intervals.IntervalSplitStore
import com.noop.intervals.IntervalStep
import com.noop.intervals.StepKind
import java.util.Locale
import kotlin.math.max

// Fork addition (Rick): shared interval-timer UI pieces used by the Intervals screen, the live
// workout screen (run with intervals) and the workout detail sheet (per-interval splits).

/** m:ss (or h:mm:ss) clock for interval durations. */
internal fun ivClock(seconds: Int): String {
    val s = max(0, seconds)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Planned length of a program, or the infinity sign when it repeats until stopped. */
internal fun ivTotal(p: IntervalProgram): String = if (p.openEnded) "∞" else ivClock(p.totalSeconds())

/** "Round 3/8", "Round 3/8 · Set 1/2", or "Round 3" for an open-ended program; null when not a round step. */
internal fun ivRoundLabel(cur: com.noop.intervals.TimelineStep?, sep: String = " · "): String? {
    if (cur == null || cur.round <= 0) return null
    if (cur.rounds == 0) return "Round ${cur.round}"
    if (cur.rounds <= 1) return null
    return "Round ${cur.round}/${cur.rounds}" + if (cur.sets > 1) "${sep}Set ${cur.set}/${cur.sets}" else ""
}

/** Colour for a step kind, in the app's existing Effort / Rest colour worlds. */
internal fun ivColor(kind: StepKind?): Color = when (kind) {
    StepKind.WORK -> if (IndigoTheme.active) IndigoTheme.work else Palette.effortColor
    StepKind.REST, StepKind.SET_REST -> if (IndigoTheme.active) IndigoTheme.rest else Palette.restColor
    StepKind.WARMUP, StepKind.COOLDOWN -> Palette.statusPositive
    null -> Palette.statusPositive
}

/**
 * One-line description of a program: "5:00 warm-up · 6 x (1:00 Hard / 1:30 Easy) · 5:00 cool-down".
 * With [showReps] false (program lists, where repetitions are chosen at start) the count is left out:
 * "5:00 warm-up · 1:00 Hard / 1:30 Easy · 5:00 cool-down".
 */
internal fun ivSummary(p: IntervalProgram, showReps: Boolean = true): String {
    val parts = mutableListOf<String>()
    if (p.warmupSeconds > 0) parts += "${ivClock(p.warmupSeconds)} warm-up"
    val block = p.block.filter { it.seconds > 0 }.joinToString(" / ") { "${ivClock(it.seconds)} ${it.displayName}" }
    parts += when {
        !showReps -> block
        p.openEnded -> "∞ x ($block)"
        p.rounds > 1 -> "${p.rounds} x ($block)"
        else -> block
    }
    if (p.sets > 1) parts += "${p.sets} sets" + if (p.setRestSeconds > 0) " (${ivClock(p.setRestSeconds)} between)" else ""
    if (p.cooldownSeconds > 0) parts += "${ivClock(p.cooldownSeconds)} cool-down"
    return parts.joinToString(" · ")
}

// MARK: - Live panel shown inside the running workout

/**
 * The interval read-out for a GPS / HR workout that has a program attached: current step, big
 * countdown, what is next, round, and a Skip button. When no program is attached it offers a
 * button to add one mid-run (handy after a free warm-up jog).
 */
@Composable
internal fun IntervalRunPanel(workoutStartMs: Long, workoutPaused: Boolean) {
    val context = LocalContext.current
    val st by IntervalEngine.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val linked = st.active && st.linkedWorkoutStartMs == workoutStartMs

    if (!linked) {
        OutlinedButton(
            onClick = { picking = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
        ) { Text("Add intervals to this workout", style = NoopType.headline) }
        if (picking) {
            IntervalProgramPickerDialog(
                title = "Start intervals",
                onDismiss = { picking = false },
                onPick = { p ->
                    picking = false
                    IntervalEngine.start(context, p, linkedWorkoutStartMs = workoutStartMs)
                    if (workoutPaused) IntervalEngine.pause()
                },
            )
        }
        return
    }

    val cur = st.current
    val color = ivColor(cur?.kind)
    val stepFraction = if (cur != null) 1f - (st.remainingS.toFloat() / cur.seconds.coerceAtLeast(1)) else 1f
    NoopCard(tint = color) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Overline(st.program?.name ?: "Intervals", color = Palette.textSecondary)
                Spacer(Modifier.weight(1f))
                ivRoundLabel(cur)?.let {
                    Text(it, style = NoopType.captionNumber, color = Palette.textSecondary)
                }
            }
            if (st.finished) {
                Text("Intervals complete", style = NoopType.title2, color = Palette.statusPositive)
                Text("Keep going or press End to save the run.", style = NoopType.footnote, color = Palette.textTertiary)
            } else if (cur != null) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        cur.name.uppercase(), style = NoopType.title1, color = color,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(ivClock(st.remainingS), style = NoopType.number(48f), color = Palette.textPrimary)
                }
                IvProgressBar(stepFraction, color)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        st.next?.let { "Next: ${it.name} ${ivClock(it.seconds)}" } ?: "Last step",
                        style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { IntervalEngine.skip() }, enabled = !st.paused) {
                        Text("Skip", style = NoopType.body, color = Palette.accent)
                    }
                }
            }
            LastSplitLine(st.splits)
        }
    }
}

@Composable
private fun LastSplitLine(splits: List<IntervalSplit>) {
    val last = splits.lastOrNull { it.kind == StepKind.WORK } ?: return
    val unit = UnitPrefs.distanceSystem(LocalContext.current)
    val bits = mutableListOf("Last ${last.name}: ${ivClock(last.durationS)}")
    last.distanceM?.takeIf { it > 0 }?.let { bits += UnitFormatter.distanceFromMeters(it, unit) }
    last.paceSecPerKm?.let { bits += UnitFormatter.paceFromSecPerKm(it, unit) }
    last.avgHr?.let { bits += "$it bpm" }
    Text(bits.joinToString(" · "), style = NoopType.footnote, color = Palette.textTertiary)
}

@Composable
internal fun IvProgressBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.surfaceInset),
    ) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}

/** Fork: shows the active "stay in Zone N or below" alert, turning red while you are over. */
@Composable
internal fun HrCapStatusLine(workoutStartMs: Long) {
    val g by com.noop.intervals.HrZoneGuard.state.collectAsStateWithLifecycle()
    if (!g.active || g.linkedWorkoutStartMs != workoutStartMs) return
    val color = if (g.alerting) Palette.statusCritical else Palette.statusPositive
    val limit = when {
        g.capBpm <= 0 -> null
        g.isCustom -> "below ${g.capBpm} bpm"
        else -> "Zone ${g.zone} or below (max ${g.capBpm} bpm)"
    }
    val avg = if (g.targetAvg > 0) {
        " · avg ${if (g.avgBpm > 0) g.avgBpm.toString() else "—"} / target ${g.targetAvg}"
    } else ""
    Text(
        if (g.alerting) "Over ${g.capBpm} bpm. Ease off.$avg"
        else (limit?.let { "HR alert: $it" } ?: "HR") + avg,
        style = NoopType.subhead,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// MARK: - Splits table on the workout detail sheet

/** Per-interval splits for a saved workout, if it was recorded with intervals. Renders nothing otherwise. */
@Composable
internal fun IntervalSplitsSection(workoutStartTs: Long) {
    val context = LocalContext.current
    val rec = remember(workoutStartTs) { IntervalSplitStore.load(context, workoutStartTs) } ?: return
    if (rec.splits.isEmpty()) return
    val unit = UnitPrefs.distanceSystem(context)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Intervals", style = NoopType.subhead, color = Palette.textPrimary)
        Text(rec.programName, style = NoopType.footnote, color = Palette.textTertiary)
        Row(modifier = Modifier.fillMaxWidth()) {
            SplitCell("Step", Modifier.weight(1.6f), Palette.textTertiary)
            SplitCell("Time", Modifier.weight(1f), Palette.textTertiary)
            SplitCell("Dist", Modifier.weight(1f), Palette.textTertiary)
            SplitCell("Pace", Modifier.weight(1.2f), Palette.textTertiary)
            SplitCell("HR", Modifier.weight(0.7f), Palette.textTertiary)
        }
        rec.splits.forEach { sp ->
            val c = ivColor(sp.kind)
            Row(modifier = Modifier.fillMaxWidth()) {
                SplitCell(
                    if (sp.round > 0 && sp.kind == StepKind.WORK) "${sp.name} ${sp.round}" else sp.name,
                    Modifier.weight(1.6f), c,
                )
                SplitCell(ivClock(sp.durationS), Modifier.weight(1f))
                SplitCell(sp.distanceM?.let { UnitFormatter.distanceFromMeters(it, unit) } ?: "—", Modifier.weight(1f))
                SplitCell(UnitFormatter.paceFromSecPerKm(sp.paceSecPerKm, unit), Modifier.weight(1.2f))
                SplitCell(sp.avgHr?.toString() ?: "—", Modifier.weight(0.7f))
            }
        }
        // Work-interval averages, the number you actually compare run to run.
        val work = rec.splits.filter { it.kind == StepKind.WORK }
        val dist = work.mapNotNull { it.distanceM }.sum()
        val secs = work.sumOf { it.durationS }
        val hrs = work.mapNotNull { it.avgHr }
        if (work.isNotEmpty()) {
            val bits = mutableListOf("${work.size} work intervals, ${ivClock(secs)}")
            if (dist >= 20) {
                bits += UnitFormatter.distanceFromMeters(dist, unit)
                bits += "avg " + UnitFormatter.paceFromSecPerKm(secs / (dist / 1000.0), unit)
            }
            if (hrs.isNotEmpty()) bits += "avg ${hrs.sum() / hrs.size} bpm"
            Text(bits.joinToString(" · "), style = NoopType.footnote, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun SplitCell(text: String, modifier: Modifier, color: Color = Palette.textPrimary) {
    Text(text, style = NoopType.captionNumber, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// MARK: - Program picker

/**
 * Repetitions control: starts at ∞ (repeat until you stop); + steps 1, 2 ... up to 25; − from 1 goes
 * back to ∞. [reps] 0 means ∞.
 */
@Composable
internal fun RepsStepper(reps: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Repetitions", style = NoopType.headline, color = Palette.textPrimary)
            Text(
                if (reps == 0) "Repeats until you stop" else "Then the cool-down",
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
        IvStepButton(Icons.Filled.Remove, "Fewer repetitions", reps > 0, Palette.textPrimary) {
            onChange(if (reps <= 1) 0 else reps - 1)
        }
        Text(
            if (reps == 0) "∞" else reps.toString(),
            style = NoopType.number(26f), color = Palette.accent,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(52.dp),
        )
        IvStepButton(Icons.Filled.Add, "More repetitions", reps < IntervalProgram.MAX_PICK_ROUNDS, Palette.textPrimary) {
            onChange((reps + 1).coerceAtMost(IntervalProgram.MAX_PICK_ROUNDS))
        }
    }
}

/** Pick a program, then how many repetitions (default ∞). Returns the program with those rounds. */
@Composable
internal fun IntervalProgramPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onPick: (IntervalProgram) -> Unit,
) {
    val context = LocalContext.current
    val programs = remember { IntervalProgramStore.all(context) }
    var chosen by remember { mutableStateOf<IntervalProgram?>(null) }
    var reps by remember { mutableIntStateOf(0) }
    val pick = chosen
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pick?.name ?: title) },
        text = {
            if (pick == null) {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    programs.forEach { p ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { chosen = p; reps = 0 }.padding(vertical = 10.dp),
                        ) {
                            Text(p.name, style = NoopType.body, color = Palette.textPrimary)
                            Text(
                                ivSummary(p, showReps = false),
                                style = NoopType.footnote, color = Palette.textTertiary, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                val withReps = pick.copy(rounds = reps)
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(ivSummary(withReps), style = NoopType.subhead, color = Palette.textSecondary)
                    RepsStepper(reps) { reps = it }
                    Text(
                        if (withReps.openEnded) "Length: until you stop" else "Length: ${ivClock(withReps.totalSeconds())}",
                        style = NoopType.bodyNumber, color = Palette.textPrimary,
                    )
                }
            }
        },
        confirmButton = {
            if (pick != null) {
                Button(onClick = { onPick(pick.copy(rounds = reps)) }) { Text("Use") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (pick != null) chosen = null else onDismiss() }) {
                Text(if (pick != null) "Back" else "Cancel")
            }
        },
    )
}

// MARK: - Program editor

/**
 * Full-screen editor for a program. Simple programs are just Work + Rest, but any number of steps
 * can be added to the block (e.g. 400 hard / 200 easy / 800 hard / 200 easy), then repeated for
 * rounds and sets, with optional warm-up, rest between sets and cool-down.
 */
@Composable
internal fun IntervalProgramEditorDialog(
    initial: IntervalProgram,
    onDismiss: () -> Unit,
    onSave: (IntervalProgram) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var warmup by remember { mutableIntStateOf(initial.warmupSeconds) }
    var rounds by remember { mutableIntStateOf(initial.rounds) }
    var sets by remember { mutableIntStateOf(initial.sets) }
    var setRest by remember { mutableIntStateOf(initial.setRestSeconds) }
    var cooldown by remember { mutableIntStateOf(initial.cooldownSeconds) }
    val block = remember { mutableStateListOf<IntervalStep>().apply { addAll(initial.block) } }

    fun current() = initial.copy(
        name = name.ifBlank { "My intervals" },
        warmupSeconds = warmup, rounds = rounds, sets = sets,
        setRestSeconds = setRest, cooldownSeconds = cooldown, block = block.toList(),
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Palette.surfaceBase)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit intervals", style = NoopType.title2, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Palette.textSecondary) }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Total ${ivClock(current().totalSeconds())}",
                    style = NoopType.bodyNumber, color = Palette.accent,
                )

                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DurationStepper("Warm-up", warmup, Palette.statusPositive, allowZero = true) { warmup = it }
                    }
                }

                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Overline("Each round")
                        block.forEachIndexed { i, step ->
                            StepEditorRow(
                                step = step,
                                canMoveUp = i > 0,
                                canMoveDown = i < block.size - 1,
                                canRemove = block.size > 1,
                                onChange = { block[i] = it },
                                onMoveUp = { val s = block.removeAt(i); block.add(i - 1, s) },
                                onMoveDown = { val s = block.removeAt(i); block.add(i + 1, s) },
                                onRemove = { block.removeAt(i) },
                            )
                            if (i < block.size - 1) IvDivider()
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { block.add(IntervalStep(StepKind.WORK, 60)) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp)); Text("Work step")
                            }
                            OutlinedButton(onClick = { block.add(IntervalStep(StepKind.REST, 60)) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp)); Text("Rest step")
                            }
                        }
                    }
                }

                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CountStepper("Rounds", rounds, 1..50) { rounds = it }
                        IvDivider()
                        CountStepper("Sets", sets, 1..10) { sets = it }
                        if (sets > 1) {
                            IvDivider()
                            DurationStepper("Rest between sets", setRest, Palette.restColor, allowZero = true) { setRest = it }
                        }
                        IvDivider()
                        DurationStepper("Cool-down", cooldown, Palette.statusPositive, allowZero = true) { cooldown = it }
                        Text(
                            "The rest after the last round of each set is skipped.",
                            style = NoopType.footnote, color = Palette.textTertiary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = { onSave(current()) },
                        enabled = block.any { it.seconds > 0 },
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun StepEditorRow(
    step: IntervalStep,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onChange: (IntervalStep) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val color = ivColor(step.kind)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Tap the chip to flip Work <-> Rest.
            val shape = RoundedCornerShape(50)
            Text(
                step.kind.label.uppercase(),
                style = NoopType.captionNumber, color = color,
                modifier = Modifier
                    .clip(shape)
                    .background(color.copy(alpha = 0.16f))
                    .border(1.dp, color.copy(alpha = 0.35f), shape)
                    .clickable {
                        onChange(step.copy(kind = if (step.kind == StepKind.WORK) StepKind.REST else StepKind.WORK))
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = step.name, onValueChange = { onChange(step.copy(name = it)) },
                placeholder = { Text(step.kind.label) }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
            IconButton(onClick = onRemove, enabled = canRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove step") }
        }
        DurationStepper("Duration", step.seconds, color, allowZero = false) { onChange(step.copy(seconds = it)) }
    }
}

/** Seconds stepper that moves in sensible jumps: 5 s under a minute, 15 s under 5 min, then 30 s. */
@Composable
internal fun DurationStepper(
    title: String,
    seconds: Int,
    tint: Color,
    allowZero: Boolean,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    val min = if (allowZero) 0 else 5
    fun stepUp(v: Int) = when { v < 60 -> 5; v < 300 -> 15; else -> 30 }
    fun stepDown(v: Int) = when { v <= 60 -> 5; v <= 300 -> 15; else -> 30 }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Text(
            if (seconds == 0) "Off" else ivClock(seconds),
            style = NoopType.number(22f), color = if (seconds == 0) Palette.textTertiary else tint,
        )
        Spacer(Modifier.width(10.dp))
        IvStepButton(Icons.Filled.Remove, "Decrease $title", enabled && seconds > min, tint) {
            onChange((seconds - stepDown(seconds)).coerceAtLeast(min))
        }
        Spacer(Modifier.width(6.dp))
        IvStepButton(Icons.Filled.Add, "Increase $title", enabled && seconds < 3600, tint) {
            onChange((seconds + stepUp(seconds)).coerceAtMost(3600))
        }
    }
}

@Composable
internal fun CountStepper(title: String, value: Int, range: IntRange, enabled: Boolean = true, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Text(value.toString(), style = NoopType.number(22f), color = Palette.textPrimary)
        Spacer(Modifier.width(10.dp))
        IvStepButton(Icons.Filled.Remove, "Decrease $title", enabled && value > range.first, Palette.textPrimary) {
            onChange((value - 1).coerceIn(range.first, range.last))
        }
        Spacer(Modifier.width(6.dp))
        IvStepButton(Icons.Filled.Add, "Increase $title", enabled && value < range.last, Palette.textPrimary) {
            onChange((value + 1).coerceIn(range.first, range.last))
        }
    }
}

@Composable
private fun IvStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
        modifier = Modifier.size(40.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun IvDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
}

@Composable
internal fun IvSwitchRow(label: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = NoopType.body, color = Palette.textPrimary)
            if (help != null) Text(help, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
