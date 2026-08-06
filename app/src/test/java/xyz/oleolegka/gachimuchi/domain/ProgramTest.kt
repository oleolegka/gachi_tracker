package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expanding a program into the sequence the timer counts down.
 *
 * This is where a nested-repeat mistake would hide: an off-by-one in the group loop
 * produces a program that is one whole set short, which on a hangboard is the difference
 * between a session and a wasted afternoon, and which nothing on screen would reveal.
 */
class ProgramTest {

    private fun program(vararg groups: ProgramGroup, prepare: Int = 0) =
        WorkoutProgram(name = "test", groups = groups.toList(), prepareSec = prepare)

    @Test
    fun `a single block expands to work and rest alternating`() {
        val steps = program(
            ProgramGroup(name = "g", blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 3)))
        ).flatten()

        assertEquals(
            listOf(
                StepKind.WORK, StepKind.REST,
                StepKind.WORK, StepKind.REST,
                StepKind.WORK,
            ),
            steps.map { it.kind },
        )
        assertEquals(listOf(7, 3, 7, 3, 7), steps.map { it.durationSec })
    }

    @Test
    fun `the trailing rest is dropped because nothing follows the last effort`() {
        val steps = program(
            ProgramGroup(name = "g", blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 2)))
        ).flatten()

        assertEquals(StepKind.WORK, steps.last().kind)
        assertEquals(3, steps.size)
    }

    @Test
    fun `group repeats multiply the blocks and insert the pause between sets`() {
        val steps = program(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 6)),
                repeats = 4,
                restBetweenRepeatsSec = 180,
            )
        ).flatten()

        assertEquals(24, steps.count { it.kind == StepKind.WORK })
        // three pauses between four sets, never a fourth one after the last
        assertEquals(3, steps.count { it.name == "Rest between sets" })
        assertEquals(StepKind.WORK, steps.last().kind)
    }

    @Test
    fun `adjacent rests are added up into one step rather than shown twice`() {
        // the block's own 3 s rest lands right before the 180 s between sets
        val steps = program(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 2)),
                repeats = 2,
                restBetweenRepeatsSec = 180,
            )
        ).flatten()

        val merged = steps.single { it.durationSec == 183 }
        assertEquals(StepKind.REST, merged.kind)
        // and nowhere are there two rests back to back
        assertTrue(steps.zipWithNext().none { (a, b) -> a.kind == StepKind.REST && b.kind == StepKind.REST })
    }

    @Test
    fun `a block with no rest configured produces no zero length steps`() {
        val steps = program(
            ProgramGroup(name = "g", blocks = listOf(ProgramBlock("Work", workSec = 30, restSec = 0, repeats = 4)))
        ).flatten()

        assertEquals(4, steps.size)
        assertTrue(steps.all { it.durationSec > 0 })
    }

    @Test
    fun `prepare is one step at the very front and only when it is positive`() {
        val block = ProgramBlock("Work", workSec = 20, restSec = 10, repeats = 2)
        val withPrepare = program(ProgramGroup(name = "g", blocks = listOf(block)), prepare = 15).flatten()
        val without = program(ProgramGroup(name = "g", blocks = listOf(block)), prepare = 0).flatten()

        assertEquals(StepKind.PREPARE, withPrepare.first().kind)
        assertEquals(15, withPrepare.first().durationSec)
        assertEquals(1, withPrepare.count { it.kind == StepKind.PREPARE })
        assertEquals(0, without.count { it.kind == StepKind.PREPARE })
    }

    @Test
    fun `several groups run in order with the pause between them, and not after the last`() {
        val steps = program(
            ProgramGroup(
                name = "A",
                blocks = listOf(ProgramBlock("Pull", workSec = 20)),
                restAfterSec = 60,
            ),
            ProgramGroup(
                name = "B",
                blocks = listOf(ProgramBlock("Push", workSec = 30)),
                restAfterSec = 60,
            ),
        ).flatten()

        assertEquals(listOf("Pull", "Rest", "Push"), steps.map { it.name })
        assertEquals(listOf(20, 60, 30), steps.map { it.durationSec })
    }

    @Test
    fun `steps carry the repeat counters the run screen shows`() {
        val steps = program(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 6)),
                repeats = 4,
                restBetweenRepeatsSec = 180,
            )
        ).flatten()

        val works = steps.filter { it.kind == StepKind.WORK }
        assertEquals("1 of 6", works.first().blockPosition)
        assertEquals("set 1 of 4", works.first().groupPosition)
        assertEquals("6 of 6", works[5].blockPosition)
        assertEquals("set 4 of 4", works.last().groupPosition)
    }

    @Test
    fun `a group with no position to report says nothing rather than saying one of one`() {
        val steps = program(ProgramGroup(name = "g", blocks = listOf(ProgramBlock("Work", workSec = 20)))).flatten()
        assertNull(steps.single().blockPosition)
        assertNull(steps.single().groupPosition)
    }

    @Test
    fun `total duration is the sum of what actually runs, trailing rest excluded`() {
        val p = program(
            ProgramGroup(
                name = "Tabata",
                blocks = listOf(ProgramBlock("Work", workSec = 20, restSec = 10, repeats = 8)),
            ),
            prepare = 10,
        )
        // 10 prepare + 8 work of 20 + 7 rests of 10 (the eighth is trailing and dropped)
        assertEquals(10 + 160 + 70, p.totalSec())
        assertEquals(8, p.workStepCount())
    }

    @Test
    fun `an absurd repeat count truncates instead of hanging or throwing`() {
        val steps = program(
            ProgramGroup(
                name = "typo",
                blocks = listOf(ProgramBlock("Work", workSec = 1, restSec = 1, repeats = 9999)),
                repeats = 9999,
            )
        ).flatten()

        assertTrue(steps.size <= MAX_PROGRAM_STEPS)
        assertTrue(steps.isNotEmpty())
    }

    @Test
    fun `zero and negative repeats are treated as one rather than erasing the block`() {
        val steps = program(
            ProgramGroup(name = "g", blocks = listOf(ProgramBlock("Work", workSec = 20, repeats = 0)), repeats = -3)
        ).flatten()
        assertEquals(1, steps.size)
        assertEquals(20, steps.single().durationSec)
    }

    // --- generated programs -----------------------------------------------------------

    @Test
    fun `a rest between sets is a program of exactly one step`() {
        val steps = restProgram(150).flatten()
        assertEquals(1, steps.size)
        assertEquals(150, steps.single().durationSec)
        // no lead-in: the rest starts the moment the set ends
        assertEquals(0, steps.count { it.kind == StepKind.PREPARE })
    }

    @Test
    fun `a hangboard exercise expands into its own protocol without being asked anything`() {
        val exercise = ExerciseRef(
            id = 1, name = "Hangs 20 mm", form = ExerciseForm.HOLD,
            edgeMm = 20.0, workSec = 7.0, restSec = 3.0,
        )
        val program = programFromExercise(exercise, reps = 6, sets = 4, restBetweenSetsSec = 180, prepareSec = 15)
        assertNotNull(program)
        val steps = program!!.flatten()

        assertEquals("Hangs 20 mm", program.name)
        assertEquals(24, steps.count { it.kind == StepKind.WORK })
        assertTrue(steps.filter { it.kind == StepKind.WORK }.all { it.durationSec == 7 })
        assertEquals(StepKind.PREPARE, steps.first().kind)
        // 15 prepare + 24 hangs of 7 + 20 in-set rests of 3 + 3 between-set pauses of 183
        assertEquals(15 + 168 + 60 + 549, program.totalSec())
    }

    @Test
    fun `an exercise without a protocol yields no program instead of an invented one`() {
        val bench = ExerciseRef(id = 2, name = "Bench press", form = ExerciseForm.STRENGTH)
        assertNull(programFromExercise(bench, reps = 5, sets = 3, restBetweenSetsSec = 120))

        // a half-filled protocol is not a protocol either
        val halfway = ExerciseRef(id = 3, name = "Hangs", form = ExerciseForm.HOLD, workSec = 7.0)
        assertNull(programFromExercise(halfway, reps = 5, sets = 3, restBetweenSetsSec = 120))
    }

    @Test
    fun `the starter programs are real and expand to something runnable`() {
        val starters = starterPrograms()
        assertEquals(2, starters.size)
        starters.forEach { p ->
            assertTrue(p.name.isNotBlank())
            assertTrue(p.flatten().isNotEmpty())
            assertTrue(p.totalSec() > 0)
        }
        assertEquals(24, starters.first { it.name.startsWith("Hangboard") }.workStepCount())
        assertEquals(8, starters.first { it.name.startsWith("Tabata") }.workStepCount())
    }
}
