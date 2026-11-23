package com.noop.intervals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalProgramTest {

    private fun simple(work: Int, rest: Int, rounds: Int, sets: Int = 1, setRest: Int = 0, warm: Int = 0, cool: Int = 0) =
        IntervalProgram(
            id = "t", name = "t", warmupSeconds = warm, rounds = rounds, sets = sets,
            setRestSeconds = setRest, cooldownSeconds = cool,
            block = listOf(IntervalStep(StepKind.WORK, work), IntervalStep(StepKind.REST, rest)),
        )

    @Test fun tabata_skipsFinalRest_matchesOriginalTimerTotal() {
        val p = simple(20, 10, 8)
        val steps = p.expand()
        assertEquals(15, steps.size)                 // 8 work + 7 rest
        assertEquals(StepKind.WORK, steps.last().kind)
        assertEquals(20 * 8 + 10 * 7, p.totalSeconds())
    }

    @Test fun warmupSetsAndCooldown_inOrder() {
        val p = simple(30, 15, 2, sets = 2, setRest = 60, warm = 300, cool = 120)
        val kinds = p.expand().map { it.kind }
        assertEquals(
            listOf(
                StepKind.WARMUP,
                StepKind.WORK, StepKind.REST, StepKind.WORK,
                StepKind.SET_REST,
                StepKind.WORK, StepKind.REST, StepKind.WORK,
                StepKind.COOLDOWN,
            ),
            kinds,
        )
        assertEquals(300 + (30 + 15 + 30) * 2 + 60 + 120, p.totalSeconds())
    }

    @Test fun roundAndSetNumbers() {
        val steps = simple(10, 10, 3, sets = 2).expand().filter { it.kind == StepKind.WORK }
        assertEquals(listOf(1, 2, 3, 1, 2, 3), steps.map { it.round })
        assertEquals(listOf(1, 1, 1, 2, 2, 2), steps.map { it.set })
    }

    @Test fun zeroLengthStepsSkipped() {
        val p = simple(30, 0, 3)
        assertEquals(3, p.expand().size)
    }

    @Test fun customBlockKeepsNames() {
        val p = IntervalProgram(
            id = "c", name = "c", rounds = 2,
            block = listOf(
                IntervalStep(StepKind.WORK, 90, "Hard"),
                IntervalStep(StepKind.REST, 60, "Easy"),
                IntervalStep(StepKind.WORK, 30, "Sprint"),
                IntervalStep(StepKind.REST, 120, "Walk"),
            ),
        )
        val names = p.expand().map { it.name }
        assertEquals(listOf("Hard", "Easy", "Sprint", "Walk", "Hard", "Easy", "Sprint"), names)
        assertFalse(p.isSimple)
    }

    @Test fun clockPositions() {
        val steps = simple(20, 10, 2).expand() // 20 work, 10 rest, 20 work = 50 s
        val p0 = IntervalClock.position(steps, 0)
        assertEquals(0, p0.index); assertEquals(20_000L, p0.remainingMs)
        val p1 = IntervalClock.position(steps, 20_000)
        assertEquals(1, p1.index); assertEquals(10_000L, p1.remainingMs)
        val p2 = IntervalClock.position(steps, 45_500)
        assertEquals(2, p2.index); assertEquals(4_500L, p2.remainingMs)
        val done = IntervalClock.position(steps, 50_000)
        assertTrue(done.finished); assertEquals(3, done.index)
    }

    @Test fun jsonRoundTrip() {
        val p = simple(45, 15, 6, sets = 3, setRest = 90, warm = 240, cool = 180).copy(id = "user.x", name = "Hills")
        val back = IntervalProgram.fromJson(p.toJson())
        assertNotNull(back)
        assertEquals(p, back)
    }

    @Test fun presetsAllExpand() {
        IntervalProgram.presets.forEach { assertTrue(it.name, it.expand().isNotEmpty()) }
        assertEquals(IntervalProgram.presets.size, IntervalProgram.presets.map { it.id }.toSet().size)
    }

    @Test fun splitPace() {
        val sp = IntervalSplit(0, StepKind.WORK, "Hard", 1, 1, durationS = 60, distanceM = 250.0, avgHr = 170, maxHr = 178)
        assertEquals(240.0, sp.paceSecPerKm!!, 0.001)
        val tiny = sp.copy(distanceM = 5.0)
        assertEquals(null, tiny.paceSecPerKm)
        assertEquals(sp, IntervalSplit.fromJson(sp.toJson()))
    }

    @Test fun runWalkRatios() {
        val byId = IntervalProgram.presets.associateBy { it.id }
        listOf("preset.walkrun" to 4, "preset.walkrun6" to 6, "preset.walkrun8" to 8).forEach { (id, run) ->
            val p = byId.getValue(id)
            assertEquals(run * 60, p.block[0].seconds)
            assertEquals(60, p.block[1].seconds)
            assertEquals("Run", p.expand()[1].name)
        }
    }
}
