package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * "What did I do last time", which is a different question from "what was my last set" and
 * has to be answered by a different reducer — see domain/LastTime.kt.
 *
 * The cases here are the three ways the obvious implementation goes wrong: it answers with
 * the sets of the workout doing the asking, it answers with a day that has not happened yet
 * for training being typed in late, and it drops sets that were logged outside a workout.
 */
class LastTimeTest {

    private val bench = exerciseRef(1, "Bench press")
    private val squat = exerciseRef(2, "Squat")

    private fun summaries(last: LastTime?): List<String> =
        last?.sets.orEmpty().map { (it.form as StrengthSet).let { s -> "${s.weightKg}x${s.reps}" } }

    @Test
    fun `the answer is the whole of the most recent earlier day`() {
        val journal = Journal()
        journal.strengthSet(bench, "2026-08-01", weightKg = 55.0, reps = 9)
        journal.strengthSet(bench, "2026-08-05", weightKg = 60.0, reps = 9, at = "18:10")
        journal.strengthSet(bench, "2026-08-05", weightKg = 60.0, reps = 8, at = "18:15")
        journal.strengthSet(squat, "2026-08-06", weightKg = 100.0, reps = 5)

        val last = lastTimeOf(journal.events, bench.link)

        assertEquals("2026-08-05", last?.opDate)
        assertEquals(listOf("60.0x9", "60.0x8"), summaries(last))
    }

    /**
     * The line sits directly above a card that already lists this workout's own sets. Letting
     * them through would make it repeat, in the past tense, what the reader can see.
     */
    @Test
    fun `the asking workout's own sets are not its own last time`() {
        val journal = Journal()
        journal.strengthSet(bench, "2026-08-05", weightKg = 60.0, reps = 9)
        val today = journal.startWorkout("2026-08-07")
        journal.addExercise(today, "2026-08-07", bench, restSec = 180)
        journal.strengthSet(bench, "2026-08-07", weightKg = 62.5, reps = 8, workoutId = today)

        val last = lastTimeOf(journal.events, bench.link, excludingWorkoutId = today)

        assertEquals("2026-08-05", last?.opDate)
        assertEquals(listOf("60.0x9"), summaries(last))
    }

    /** A second workout the same day is exactly what the evening one wants to know about. */
    @Test
    fun `an earlier workout on the same day still counts as last time`() {
        val journal = Journal()
        val morning = journal.startWorkout("2026-08-07", at = "08:00")
        journal.strengthSet(bench, "2026-08-07", weightKg = 60.0, reps = 9, at = "08:10", workoutId = morning)
        val evening = journal.startWorkout("2026-08-07", at = "19:00")

        val last = lastTimeOf(journal.events, bench.link, onOrBefore = "2026-08-07", excludingWorkoutId = evening)

        assertEquals("2026-08-07", last?.opDate)
        assertEquals(listOf("60.0x9"), summaries(last))
    }

    /**
     * The reason [lastTimeOf] takes a day at all. Training backfilled into June must not be
     * told that last time was in August — the sets it is being compared against are the ones
     * that came BEFORE it.
     */
    @Test
    fun `training typed in late is not told about days that came after it`() {
        val journal = Journal()
        journal.strengthSet(bench, "2026-05-20", weightKg = 50.0, reps = 9)
        journal.strengthSet(bench, "2026-08-05", weightKg = 60.0, reps = 9)
        val backfilled = journal.startWorkout("2026-06-01")

        val last = lastTimeOf(
            journal.events, bench.link, onOrBefore = "2026-06-01", excludingWorkoutId = backfilled,
        )

        assertEquals("2026-05-20", last?.opDate)
        assertEquals(listOf("50.0x9"), summaries(last))
    }

    /** Stretching in front of the television is training that happened. */
    @Test
    fun `a set logged outside any workout counts`() {
        val journal = Journal()
        journal.strengthSet(bench, "2026-08-05", weightKg = 60.0, reps = 9)
        val today = journal.startWorkout("2026-08-07")

        assertEquals("2026-08-05", lastTimeOf(journal.events, bench.link, excludingWorkoutId = today)?.opDate)
    }

    @Test
    fun `an exercise nothing was ever recorded for has no last time`() {
        val journal = Journal()
        journal.strengthSet(bench, "2026-08-05")

        assertNull(lastTimeOf(journal.events, squat.link))
    }
}
