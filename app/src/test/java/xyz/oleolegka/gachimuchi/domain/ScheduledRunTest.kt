package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a hold's schedule turns into when it is RUN, and what an [ExerciseRef] carrying one says
 * about itself.
 *
 * The branch rule itself lives one file over ([HoldScheduleTest] over domain/HoldSchedule.kt) and
 * is not re-tested here — one classifier, one place that pins it down. What is here is the
 * consequence: [scheduledRun] for the strict branch, and the two derived answers on the ref
 * ([ExerciseRef.scheduleKind], [ExerciseRef.canBeConducted]) that decide whether a tap reaches
 * the conductor at all.
 *
 * The defect being pinned down: every protocol-led run went through [programFromExercise], which
 * reads [WorkoutProgram.firstBlock] and rebuilds a program around it. For a plain pair that is
 * the whole schedule. For anything richer — a second block, a group repeat, a pause between
 * groups — everything past the first block was thrown away, and the run that played was not the
 * schedule the exercise says it has.
 */
class ScheduledRunTest {

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
    fun `a hold with no schedule offers no conducted run`() {
        val exercise = hold("Free hang", null)
        assertEquals(ScheduleKind.FREE, exercise.scheduleKind)
        assertFalse(exercise.canBeConducted)
        assertNull(scheduledRun(exercise))
    }

    @Test
    fun `a plain pair is conducted by being rebuilt, so it plays no schedule of its own`() {
        val exercise = hold("Hangs", pair)
        assertEquals(ScheduleKind.SIMPLE_PAIR, exercise.scheduleKind)
        assertTrue(exercise.canBeConducted)
        assertNull(scheduledRun(exercise))
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

    /**
     * The same for a caller holding the program rather than a ref that carries it — the
     * database backstop in `MainViewModel.startProgramForExercise`. It must agree with the
     * road above on both questions: whether this is strict at all, and what the run is called.
     */
    @Test
    fun `the ref road and the stored-program road build the same run`() {
        val fromRef = scheduledRun(hold("Hang 20 mm", repeaters))
        val fromStore = scheduledRunOf(repeaters, "Hang 20 mm", exerciseId = 1)
        assertEquals(fromRef, fromStore)
        assertNull(scheduledRunOf(pair, "Hangs", exerciseId = 1))
        assertNull(scheduledRunOf(null, "Hangs", exerciseId = 1))
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

    /**
     * A schedule that counts nothing must not draw the conductor's button: the run it would
     * start has no steps and is dropped, so the tap would do nothing and say nothing.
     */
    @Test
    fun `a schedule with nothing to count offers no conducted run`() {
        val empty = WorkoutProgram(name = "Empty", groups = emptyList())
        val exercise = hold("Empty", empty)
        assertEquals(ScheduleKind.FREE, exercise.scheduleKind)
        assertFalse(exercise.canBeConducted)
        assertNull(scheduledRun(exercise))
    }

    /**
     * A ref built with the two numbers and no resolved schedule behind them — what the screens
     * that only ever spoke the pair still hand around. It reads as the pair it is, rather than
     * losing the conductor it has had since the app had a timer.
     */
    @Test
    fun `a ref carrying only the two numbers still reads as a pair`() {
        val exercise = ExerciseRef(
            id = 2, name = "Hangs", form = ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0,
        )
        assertEquals(ScheduleKind.SIMPLE_PAIR, exercise.scheduleKind)
        assertTrue(exercise.canBeConducted)
        assertTrue(ledByProtocol(exercise))
    }
}
