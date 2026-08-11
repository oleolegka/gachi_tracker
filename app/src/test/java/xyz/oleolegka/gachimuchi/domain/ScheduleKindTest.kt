package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three shapes a hold's schedule can have (§18.15), and the one that used to be quietly
 * flattened.
 *
 * The defect being pinned down here: every protocol-led run went through
 * [programFromExercise], which reads [WorkoutProgram.firstBlock] and rebuilds a program around
 * it. For a plain pair that is the whole schedule. For anything richer — a second block, a
 * group repeat, a pause between groups — everything past the first block was thrown away, and
 * the run that played was not the schedule the exercise says it has.
 */
class ScheduleKindTest {

    private fun hold(name: String, schedule: WorkoutProgram?) = ExerciseRef(
        id = 1,
        name = name,
        form = ExerciseForm.HOLD,
        workSec = schedule?.firstBlock()?.workSec?.toDouble(),
        restSec = schedule?.firstBlock()?.restSec?.toDouble(),
        schedule = schedule,
    )

    private val pair = WorkoutProgram(
        id = 5,
        name = "Hangs 7:3",
        groups = listOf(
            ProgramGroup(name = "Hangs", blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3))),
        ),
    )

    /** Repeaters: six hangs, four sets, three minutes between — the owner's actual session. */
    private val repeaters = WorkoutProgram(
        id = 6,
        name = "Repeaters",
        prepareSec = 15,
        groups = listOf(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 6)),
                repeats = 4,
                restBetweenRepeatsSec = 180,
            ),
        ),
    )

    /** Two different edges in one set, in a fixed order — a shape a pair cannot express at all. */
    private val twoBlocks = WorkoutProgram(
        id = 7,
        name = "20 mm then 15 mm",
        groups = listOf(
            ProgramGroup(
                name = "Edges",
                blocks = listOf(
                    ProgramBlock("Hang 20 mm", workSec = 10, restSec = 0),
                    ProgramBlock("Hang 15 mm", workSec = 7, restSec = 120),
                ),
                repeats = 2,
            ),
        ),
    )

    @Test
    fun `no schedule at all is a free hold`() {
        assertEquals(ScheduleKind.NONE, scheduleKindOf(null))
        assertFalse(hold("Free hang", null).canBeConducted)
    }

    @Test
    fun `one group, one block, no repeats is the plain pair`() {
        assertEquals(ScheduleKind.PAIR, scheduleKindOf(pair))
        assertTrue(hold("Hangs", pair).canBeConducted)
        // and the pair road is the one that builds a program rather than playing one
        assertNull(scheduledRun(hold("Hangs", pair)))
    }

    @Test
    fun `repeats make it strict, and more than one block makes it strict`() {
        assertEquals(ScheduleKind.STRICT, scheduleKindOf(repeaters))
        assertEquals(ScheduleKind.STRICT, scheduleKindOf(twoBlocks))
    }

    @Test
    fun `a strict schedule runs exactly as written, first block and all`() {
        val run = scheduledRun(hold("Hang 20 mm", repeaters))
        checkNotNull(run)
        // named after the exercise, linked to it, and otherwise untouched
        assertEquals("Hang 20 mm", run.name)
        assertEquals(1L, run.exerciseId)
        assertEquals(repeaters.groups, run.groups)
        assertEquals(15, run.prepareSec)
        // 6 hangs x 4 sets, which is what the schedule says and nothing else decides
        assertEquals(24, run.workStepCount())
    }

    @Test
    fun `rebuilding from the first block is what used to be lost`() {
        val exercise = hold("Edges", twoBlocks)
        val played = scheduledRun(exercise)!!.flatten().filter { it.kind == StepKind.WORK }
        assertEquals(
            listOf("Hang 20 mm", "Hang 15 mm", "Hang 20 mm", "Hang 15 mm"),
            played.map { it.name },
        )

        /*
         * The old road, for contrast, and it is worse than a collapse here: the pair it reads
         * off the first block has a rest of zero, so it builds NOTHING and the card fell
         * through to the manual entry form. The second edge was never the only casualty.
         */
        assertNull(programFromExercise(exercise, reps = 4, sets = 2, restBetweenSetsSec = 60))

        // where it does build something, it builds the first block and repeats supplied from
        // outside: four sets of six become two sets of four, and the schedule is not consulted
        val collapsed = programFromExercise(
            hold("Hang 20 mm", repeaters), reps = 4, sets = 2, restBetweenSetsSec = 60,
        )!!
        assertEquals(8, collapsed.workStepCount())
        assertEquals(24, scheduledRun(hold("Hang 20 mm", repeaters))!!.workStepCount())
    }

    @Test
    fun `a strict schedule whose first block has no rest is still conductable`() {
        // the pair reads as null here (rest of 0), which is exactly what used to demote this
        // exercise back to the manual entry form
        val exercise = hold("Edges", twoBlocks)
        assertNull(exercise.protocol)
        assertTrue(exercise.canBeConducted)
    }

    @Test
    fun `a schedule with nothing to count is not strict, it is nothing`() {
        val empty = WorkoutProgram(name = "Empty", groups = emptyList())
        assertEquals(ScheduleKind.NONE, scheduleKindOf(empty))
        assertFalse(hold("Empty", empty).canBeConducted)
    }
}
