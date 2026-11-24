package com.noop.intervals

import org.json.JSONArray
import org.json.JSONObject

// Fork addition (Rick): a Tabata / HIIT style interval program model.
//
// A program is: optional warm-up, a BLOCK of steps (for a simple timer that is just
// [work, rest]) repeated `rounds` times, the whole thing repeated `sets` times with an
// optional rest between sets, then an optional cool-down. Pure Kotlin, no Android, so the
// expansion and the clock math are unit-tested on the JVM.

enum class StepKind(val label: String) {
    WARMUP("Warm-up"),
    WORK("Work"),
    REST("Rest"),
    SET_REST("Set rest"),
    COOLDOWN("Cool-down");

    /** True for the "hard" steps. Drives colour, cue strength and the split summary. */
    val isEffort: Boolean get() = this == WORK

    /** Rest-like steps that are dropped at the end of a set (the set rest / cool-down replaces them). */
    val isRecovery: Boolean get() = this == REST
}

/** One step in a program's block. [name] is optional (e.g. "Hard", "Easy jog"); blank falls back to the kind. */
data class IntervalStep(
    val kind: StepKind,
    val seconds: Int,
    val name: String = "",
) {
    val displayName: String get() = name.ifBlank { kind.label }
}

/** One expanded, ready-to-run step with its position for display ("Round 3/8, Set 1/2"). */
data class TimelineStep(
    val kind: StepKind,
    val seconds: Int,
    val name: String,
    val round: Int,      // 1-based round within the set, 0 for warm-up / set rest / cool-down
    val rounds: Int,
    val set: Int,        // 1-based set, 0 for warm-up / cool-down
    val sets: Int,
)

data class IntervalProgram(
    val id: String,
    val name: String,
    val warmupSeconds: Int = 0,
    val block: List<IntervalStep> = listOf(
        IntervalStep(StepKind.WORK, 30),
        IntervalStep(StepKind.REST, 15),
    ),
    val rounds: Int = 8,
    val sets: Int = 1,
    val setRestSeconds: Int = 0,
    val cooldownSeconds: Int = 0,
    val builtIn: Boolean = false,
) {
    /** Fork: rounds <= 0 means "repeat until you stop" (shown as ∞). */
    val openEnded: Boolean get() = rounds <= 0

    /** True when the block is the plain work/rest pair, so the simple editor can show it. */
    val isSimple: Boolean
        get() = block.size == 2 && block[0].kind == StepKind.WORK && block[1].kind == StepKind.REST

    /** Expand into the flat list of steps that actually run. Zero-length steps are skipped. */
    fun expand(): List<TimelineStep> {
        val out = mutableListOf<TimelineStep>()
        // Open-ended: enough rounds that nobody runs out (the run's End stops it), one set, no cool-down.
        // Steps carry rounds = 0 so the UI and voice say "round 7", not "round 7 of 400".
        val r = if (openEnded) OPEN_ENDED_ROUNDS else rounds.coerceAtLeast(1)
        val s = if (openEnded) 1 else sets.coerceAtLeast(1)
        val shownRounds = if (openEnded) 0 else r
        if (warmupSeconds > 0) out += TimelineStep(StepKind.WARMUP, warmupSeconds, "Warm-up", 0, shownRounds, 0, s)
        val blockSteps = block.filter { it.seconds > 0 }
        for (set in 1..s) {
            for (round in 1..r) {
                // On the last round of a set, trailing rest steps are dropped: the set rest or the
                // cool-down (or simply the finish) follows instead. Same as the original timer, which
                // never ran a rest after the final work block.
                val steps = if (round == r) blockSteps.dropLastWhile { it.kind.isRecovery } else blockSteps
                for (st in steps) out += TimelineStep(st.kind, st.seconds, st.displayName, round, shownRounds, set, s)
            }
            if (set < s && setRestSeconds > 0) {
                out += TimelineStep(StepKind.SET_REST, setRestSeconds, "Set rest", 0, shownRounds, set, s)
            }
        }
        if (cooldownSeconds > 0 && !openEnded) {
            out += TimelineStep(StepKind.COOLDOWN, cooldownSeconds, "Cool-down", 0, shownRounds, 0, s)
        }
        return out
    }

    /** Planned length in seconds; 0 for an open-ended program. */
    fun totalSeconds(): Int = if (openEnded) 0 else expand().sumOf { it.seconds }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("warmup", warmupSeconds)
        put("rounds", rounds)
        put("sets", sets)
        put("setRest", setRestSeconds)
        put("cooldown", cooldownSeconds)
        put("block", JSONArray().apply {
            block.forEach { st ->
                put(JSONObject().apply {
                    put("kind", st.kind.name)
                    put("seconds", st.seconds)
                    put("name", st.name)
                })
            }
        })
    }

    companion object {
        /** Rounds generated for an open-ended program (hours of running for any realistic block). */
        const val OPEN_ENDED_ROUNDS = 400

        /** Most repetitions offered by the picker. */
        const val MAX_PICK_ROUNDS = 25

        fun fromJson(o: JSONObject): IntervalProgram? = runCatching {
            val arr = o.getJSONArray("block")
            val block = (0 until arr.length()).mapNotNull { i ->
                val so = arr.getJSONObject(i)
                val kind = runCatching { StepKind.valueOf(so.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                IntervalStep(kind, so.optInt("seconds", 0), so.optString("name", ""))
            }
            IntervalProgram(
                id = o.getString("id"),
                name = o.optString("name", "Intervals"),
                warmupSeconds = o.optInt("warmup", 0),
                block = block,
                rounds = o.optInt("rounds", 1),
                sets = o.optInt("sets", 1),
                setRestSeconds = o.optInt("setRest", 0),
                cooldownSeconds = o.optInt("cooldown", 0),
            )
        }.getOrNull()

        /** A run:walk preset, e.g. 4:1 = 4 min run, 1 min walk, with a 5 min walking warm-up and cool-down. */
        private fun runWalk(id: String, runMin: Int, rounds: Int) = IntervalProgram(
            id = id, name = "Run/walk $runMin:1 ($runMin min run / 1 min walk x $rounds)",
            warmupSeconds = 300, rounds = rounds, cooldownSeconds = 300,
            block = listOf(
                IntervalStep(StepKind.WORK, runMin * 60, "Run"),
                IntervalStep(StepKind.REST, 60, "Walk"),
            ),
            builtIn = true,
        )

        /** Built-in presets. Ids are stable so a saved "last used" keeps pointing at the right one. */
        val presets: List<IntervalProgram> = listOf(
            IntervalProgram(
                id = "preset.tabata", name = "Tabata (20/10 x 8)",
                warmupSeconds = 0, rounds = 8,
                block = listOf(IntervalStep(StepKind.WORK, 20), IntervalStep(StepKind.REST, 10)),
                builtIn = true,
            ),
            IntervalProgram(
                id = "preset.hiit3030", name = "HIIT 30/30 x 10",
                warmupSeconds = 300, rounds = 10, cooldownSeconds = 300,
                block = listOf(IntervalStep(StepKind.WORK, 30), IntervalStep(StepKind.REST, 30)),
                builtIn = true,
            ),
            IntervalProgram(
                id = "preset.run1min", name = "Run: 6 x 1 min hard / 90 s easy",
                warmupSeconds = 300, rounds = 6, cooldownSeconds = 300,
                block = listOf(
                    IntervalStep(StepKind.WORK, 60, "Hard"),
                    IntervalStep(StepKind.REST, 90, "Easy"),
                ),
                builtIn = true,
            ),
            // Run/walk ratios (minutes run : minutes walk). Rounds set for roughly 30-40 min of running;
            // change rounds in the editor (Copy) to fit the day's distance.
            runWalk("preset.walkrun", 4, 6),
            runWalk("preset.walkrun6", 6, 5),
            runWalk("preset.walkrun8", 8, 4),
            IntervalProgram(
                id = "preset.pyramid", name = "Run: pyramid 1-2-3-2-1 min",
                warmupSeconds = 600, rounds = 1, cooldownSeconds = 300,
                block = listOf(
                    IntervalStep(StepKind.WORK, 60, "Hard 1 min"), IntervalStep(StepKind.REST, 60, "Easy"),
                    IntervalStep(StepKind.WORK, 120, "Hard 2 min"), IntervalStep(StepKind.REST, 90, "Easy"),
                    IntervalStep(StepKind.WORK, 180, "Hard 3 min"), IntervalStep(StepKind.REST, 120, "Easy"),
                    IntervalStep(StepKind.WORK, 120, "Hard 2 min"), IntervalStep(StepKind.REST, 90, "Easy"),
                    IntervalStep(StepKind.WORK, 60, "Hard 1 min"),
                ),
                builtIn = true,
            ),
            IntervalProgram(
                id = "preset.tabata2", name = "Double Tabata (2 sets)",
                rounds = 8, sets = 2, setRestSeconds = 60,
                block = listOf(IntervalStep(StepKind.WORK, 20), IntervalStep(StepKind.REST, 10)),
                builtIn = true,
            ),
        )
    }
}

/** Where a session is, given the active (un-paused) time elapsed since it started. Pure. */
data class TimelinePosition(
    val index: Int,          // index into the expanded steps; == steps.size when finished
    val remainingMs: Long,   // ms left in the current step (0 when finished)
    val finished: Boolean,
)

object IntervalClock {
    fun position(steps: List<TimelineStep>, activeElapsedMs: Long): TimelinePosition {
        var t = activeElapsedMs.coerceAtLeast(0L)
        steps.forEachIndexed { i, st ->
            val len = st.seconds * 1000L
            if (t < len) return TimelinePosition(i, len - t, false)
            t -= len
        }
        return TimelinePosition(steps.size, 0L, true)
    }
}
