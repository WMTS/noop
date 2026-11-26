package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.RestScorer
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import com.noop.intervals.NightStages
import com.noop.intervals.RunnerRest
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

// Fork addition (Rick): last night's sleep in detail. The cards sit on the Sleep tab under the score (which
// the Sleep ring on Today opens); RunnerRestScreen keeps them for any other link to the Rest detail. Last night's score broken into its parts, the
// night's numbers (times, stages with typical ranges, wake-ups, heart rate), plain flags when something
// looks off, and a History tab with NOOP's original Rest trend.

private fun hm(minutes: Double): String {
    val t = minutes.roundToInt()
    return if (t >= 60) "${t / 60} h ${t % 60} m" else "$t m"
}

private fun clock(ts: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts * 1000))

@Composable
internal fun RunnerRestScreen(vm: AppViewModel, onOpenSleep: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            listOf("Last night", "History").forEachIndexed { i, label ->
                if (tab == i) Button(onClick = { tab = i }) { Text(label) }
                else OutlinedButton(onClick = { tab = i }) { Text(label) }
            }
        }
        if (tab == 1) {
            Box(Modifier.weight(1f)) { VitalDetailScreen(vm = vm, key = "rest") }
        } else {
            Box(Modifier.weight(1f)) { RestLastNight(vm, onOpenSleep) }
        }
    }
}

@Composable
private fun RestLastNight(vm: AppViewModel, onOpenSleep: () -> Unit) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val latest = remember(days) { days.filter { (it.totalSleepMin ?: 0.0) > 0 }.maxByOrNull { it.day }?.day }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        RunnerNightCards(vm, latest, showScore = true)
        Button(onClick = onOpenSleep, modifier = Modifier.fillMaxWidth()) { Text("Open the Sleep tab (timeline and edits)") }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The night's cards: score (optional, the Sleep tab has its own), how the score adds up, flags, stages and
 * the night's numbers. [day] is the night's wake day ("yyyy-MM-dd"); null shows nothing.
 */
@Composable
internal fun RunnerNightCards(vm: AppViewModel, day: String?, showScore: Boolean) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val night = remember(days, day) { days.firstOrNull { it.day == day && (it.totalSleepMin ?: 0.0) > 0 } }
    var sessions by remember { mutableStateOf<List<SleepSession>>(emptyList()) }
    LaunchedEffect(days, night?.day) {
        val nightDay = night?.day ?: return@LaunchedEffect
        sessions = runCatching {
            val strap = vm.activeStrapId
            val dayStart = runCatching {
                java.time.LocalDate.parse(nightDay).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
            }.getOrDefault(System.currentTimeMillis() / 1000L)
            val now = dayStart + 2 * 86_400L
            val from = dayStart - 2 * 86_400L
            fun localEndDay(s: SleepSession): String {
                val off = (java.util.TimeZone.getDefault().getOffset(s.endTs * 1000) / 1000).toLong()
                return AnalyticsEngine.dayString(s.endTs, off)
            }
            WhoopRepository.mergeSleepRichness(
                vm.repo.sleepSessionsUnion(strap, from, now),
                vm.repo.computedSleepSessionsUnion(strap, from, now),
            ) { localEndDay(it) }.filter { localEndDay(it) == nightDay }.sortedBy { it.effectiveStartTs }
        }.getOrDefault(emptyList())
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (night == null) {
            if (showScore) NoopCard { Text("No scored night yet. Wear your strap to bed and your sleep score appears here in the morning.", style = NoopType.body, color = Palette.textSecondary) }
            return@Column
        }
        val total = night.totalSleepMin ?: 0.0
        val score = RestScorer.restFromDaily(night)
        val parts = RunnerRest.parts(night)
        // The night chosen as main sleep: the longest block that day (naps are shown in the Sleep tab).
        val main = sessions.maxByOrNull { it.endTs - it.effectiveStartTs }
        val bed = sessions.filter { main == null || it.endTs > main.effectiveStartTs - 3 * 3600 }.minOfOrNull { it.effectiveStartTs }
        val wake = main?.endTs
        val inBedMin = if (bed != null && wake != null && wake > bed) (wake - bed) / 60.0 else null
        val segs = main?.let { NightStages.parse(it.stagesJSON) }.orEmpty()

        // Score
        if (showScore) NoopCard(tint = Palette.recoveryColor(score ?: 0.0)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sleep score, night ending ${night.day}", style = NoopType.subhead, color = Palette.textSecondary)
                Text(score?.roundToInt()?.toString() ?: "-", style = NoopType.headline, color = Palette.textPrimary)
                if (bed != null && wake != null) {
                    Text("${clock(bed)} to ${clock(wake)}", style = NoopType.body, color = Palette.textPrimary)
                }
                Text(
                    "Wrong bed or wake time? In the Sleep tab, tap the times to fix them; the stages and score follow.",
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            }
        }

        // Breakdown
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How the score adds up", style = NoopType.subhead, color = Palette.textPrimary)
                if (parts == null) {
                    Text(
                        "This night came from an import, so it is scored with NOOP's original formula: duration 50, " +
                            "efficiency 20, deep and REM 20, consistency 10.",
                        style = NoopType.footnote, color = Palette.textSecondary,
                    )
                } else {
                    parts.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = NoopType.body, color = Palette.textPrimary)
                                Text(p.detail, style = NoopType.footnote, color = Palette.textTertiary)
                            }
                            Text(
                                "${p.points.roundToInt()} / ${p.maxPoints.roundToInt()}",
                                style = NoopType.bodyNumber,
                                color = if (p.points >= p.maxPoints * 0.9) Palette.statusPositive else Palette.textPrimary,
                            )
                        }
                    }
                    Text(
                        "These are NOOP Runner's own estimates from your strap, not WHOOP's Sleep Performance.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
            }
        }

        // Flags
        val flags = NightStages.flags(total, night.deepMin, night.remMin, night.efficiency, night.disturbances, night.sleepHrOnly)
        if (flags.isNotEmpty()) {
            NoopCard(tint = Palette.statusWarning) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Worth a look", style = NoopType.subhead, color = Palette.statusWarning)
                    flags.forEach { Text(it, style = NoopType.footnote, color = Palette.textPrimary) }
                }
            }
        }

        // Stages
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sleep stages", style = NoopType.subhead, color = Palette.textPrimary)
                StageRow("Light", night.lightMin, total, "45 to 55%", Palette.textSecondary)
                StageRow("Deep", night.deepMin, total, "13 to 23%", Palette.accent)
                StageRow("REM", night.remMin, total, "20 to 25%", Palette.statusPositive)
                Text("Typical ranges are for healthy adults; they shift with age.", style = NoopType.footnote, color = Palette.textTertiary)
            }
        }

        // The night's numbers
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Last night", style = NoopType.subhead, color = Palette.textPrimary)
                if (inBedMin != null) InfoRow("In bed", hm(inBedMin))
                InfoRow("Asleep", hm(total))
                if (inBedMin != null && inBedMin > total) InfoRow("Awake in bed", hm(inBedMin - total))
                if (main != null) NightStages.latencySeconds(main.effectiveStartTs, segs)?.let { InfoRow("Time to fall asleep", hm(it / 60.0)) }
                night.disturbances?.let { InfoRow("Wake-ups", it.toString()) }
                night.restingHr?.let { InfoRow("Lowest heart rate", "$it bpm") }
                night.avgHrv?.let { InfoRow("HRV (average)", "${it.roundToInt()} ms") }
                night.respRateBpm?.let { InfoRow("Breathing rate", "${"%.1f".format(it)} per min") }
                night.skinTempDevC?.let { InfoRow("Skin temperature", "${if (it >= 0) "+" else ""}${"%.1f".format(it)} C from your normal") }
            }
        }
    }
}

@Composable
private fun StageRow(name: String, minutes: Double?, total: Double, typical: String, color: androidx.compose.ui.graphics.Color) {
    val m = minutes ?: 0.0
    val pct = if (total > 0) m / total else 0.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(name, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.width(64.dp))
            Text("${hm(m)}  ${(pct * 100).roundToInt()}%", style = NoopType.bodyNumber, color = Palette.textPrimary, modifier = Modifier.weight(1f))
            Text("typical $typical", style = NoopType.footnote, color = Palette.textTertiary)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(Palette.surfaceInset, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(pct.toFloat().coerceIn(0f, 1f)).height(6.dp).background(color, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = NoopType.body, color = Palette.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = NoopType.bodyNumber, color = Palette.textPrimary)
    }
}
