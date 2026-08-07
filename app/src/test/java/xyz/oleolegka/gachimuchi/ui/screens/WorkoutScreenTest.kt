package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.exerciseRef
import xyz.oleolegka.gachimuchi.ui.slot
import java.time.LocalDate

/**
 * One workout, opened to be looked at.
 *
 * The screen folds the workout out of the journal itself ([xyz.oleolegka.gachimuchi.domain.buildWorkout]),
 * so the fixtures here are journals and the assertions are about what a reader of the screen
 * ends up being told: which exercises, in which order, with which sets, and — the part that
 * has bitten this app before — whether a clock time printed beside a set is a time that set
 * actually happened at.
 *
 * The limits of the method are the ones written out in
 * [xyz.oleolegka.gachimuchi.ui.components.DayCardListTest]: this proves the words exist and
 * are right, never that they are visible.
 */
class WorkoutScreenTest : ScreenTest() {

    private val day = LocalDate.parse("2026-08-07")
    private val iso = day.toString()

    private val bench = exerciseRef(1, "Bench press")
    private val squat = exerciseRef(2, "Squat")

    private val catalog = listOf(exerciseEntity(1, "Bench press"), exerciseEntity(2, "Squat"))

    private var continued = 0
    private var closed = 0

    private fun workoutScreen(
        journal: Journal,
        workoutId: Long,
        slots: List<Slot> = emptyList(),
        running: Boolean = false,
    ) {
        val state = UiState(events = journal.events, exercises = catalog, slots = slots, loading = false)
        screen {
            WorkoutScreen(
                state = state,
                workoutId = workoutId,
                onContinue = if (running) ({ continued++ }) else null,
                onClose = { closed++ },
            )
        }
    }

    /** Two exercises, three sets, the shape most of these tests are about. */
    private fun twoExerciseWorkout(journal: Journal): Long {
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, bench, restSec = 150)
        journal.addExercise(workout, iso, squat, restSec = 210)
        journal.strengthSet(bench, iso, at = "18:10", weightKg = 60.0, reps = 5, workoutId = workout)
        journal.strengthSet(bench, iso, at = "18:14", weightKg = 62.5, reps = 5, workoutId = workout)
        journal.strengthSet(squat, iso, at = "18:25", weightKg = 100.0, reps = 3, workoutId = workout)
        return workout
    }

    // --- what is in the workout -----------------------------------------------------------

    @Test
    fun `every exercise of the workout gets a block, with the rest chosen for it`() {
        val journal = Journal()
        workoutScreen(journal, twoExerciseWorkout(journal))

        compose.onNodeWithText("Bench press").assertIsDisplayed()
        compose.onNodeWithText("Squat").assertIsDisplayed()
        compose.onNodeWithText("rest 2 min 30 s").assertIsDisplayed()
        compose.onNodeWithText("rest 3 min 30 s").assertIsDisplayed()
    }

    @Test
    fun `each set is drawn with its numbers, its position and the time it was done at`() {
        val journal = Journal()
        workoutScreen(journal, twoExerciseWorkout(journal))

        compose.onNodeWithText("60 kg × 5 reps").assertIsDisplayed()
        compose.onNodeWithText("62.5 kg × 5 reps").assertIsDisplayed()
        compose.onNodeWithText("100 kg × 3 reps").assertIsDisplayed()
        compose.onNodeWithText("18:10").assertIsDisplayed()
        compose.onNodeWithText("18:14").assertIsDisplayed()
        compose.onNodeWithText("18:25").assertIsDisplayed()
    }

    @Test
    fun `the header names the plan the workout came from and counts what is in it`() {
        val journal = Journal()
        // the name is the snapshot taken from the plan at the moment of starting, which is
        // what the header reads; the plan itself is only linked
        val workout = journal.startWorkout(iso, at = "18:05", slotId = 7L, name = "Gym")
        journal.addExercise(workout, iso, bench, restSec = 150)
        journal.strengthSet(bench, iso, at = "18:10", workoutId = workout)

        workoutScreen(journal, workout, slots = listOf(slot(7, "Gym", "18:00", iso)))

        compose.onNodeWithText("Gym").assertIsDisplayed()
        compose.onNodeWithText("Fri 7 Aug - 1 exercise, 1 set").assertIsDisplayed()
    }

    @Test
    fun `a workout nobody planned is headed by the plain word and still counted`() {
        val journal = Journal()
        workoutScreen(journal, twoExerciseWorkout(journal))

        compose.onNodeWithText("Workout").assertIsDisplayed()
        compose.onNodeWithText("Fri 7 Aug - 2 exercises, 3 sets").assertIsDisplayed()
    }

    @Test
    fun `an entry belonging to no exercise is shown rather than dropped`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, bench, restSec = 150)
        journal.strengthSet(bench, iso, at = "18:10", workoutId = workout)
        // a weigh-in carries no exercise_id by design, so it belongs to the workout without
        // belonging to any of its exercises
        journal.weighIn(iso, kg = 74.2, at = "18:30", workoutId = workout)

        workoutScreen(journal, workout)

        compose.onNodeWithText("Other entries").assertIsDisplayed()
        compose.onNodeWithText("74.2 kg").assertIsDisplayed()
    }

    /**
     * The lie this screen is written to avoid: old training typed up on the sofa carries the
     * time it was TYPED, and printing that beside a set done last Tuesday morning would read
     * as fact.
     */
    @Test
    fun `a set backfilled on another day is drawn without a clock time`() {
        val journal = Journal()
        val past = "2026-06-01"
        val workout = journal.startWorkout(past, at = "20:00")
        journal.addExercise(workout, past, bench, restSec = 150)
        // done in June and TYPED on the sofa in August: 23:40 is when it was written down,
        // and printing it beside the set would be a plausible-looking lie
        journal.strengthSet(bench, past, at = "23:40", workoutId = workout, writtenOn = iso)
        // and one from the same workout that was written on the day it happened
        journal.strengthSet(bench, past, at = "20:15", weightKg = 62.5, workoutId = workout)

        workoutScreen(journal, workout)

        compose.onNodeWithText("60 kg × 5 reps").assertIsDisplayed()
        compose.onNodeWithText("23:40").assertDoesNotExist()
        compose.onNodeWithText("20:15").assertIsDisplayed()
    }

    // --- an empty workout, running and finished ---------------------------------------------

    @Test
    fun `a running workout with nothing in it says the next set will land here`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")

        workoutScreen(journal, workout, running = true)

        compose.onNodeWithText("Fri 7 Aug - nothing recorded yet").assertIsDisplayed()
        compose.onNodeWithText(
            "Nothing recorded yet. Continue the workout and the first set lands here."
        ).assertIsDisplayed()
        compose.onNodeWithText("Continue this workout").assertIsDisplayed()
    }

    @Test
    fun `a finished workout with nothing in it offers no way to carry on`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")

        workoutScreen(journal, workout, running = false)

        compose.onNodeWithText("Nothing was recorded in this workout.").assertIsDisplayed()
        compose.onNodeWithText("Continue this workout").assertDoesNotExist()
    }

    @Test
    fun `an exercise added and not yet done says so instead of being left blank`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, bench, restSec = 150)

        workoutScreen(journal, workout, running = true)

        compose.onNodeWithText("Bench press").assertIsDisplayed()
        compose.onNodeWithText("no sets yet").assertIsDisplayed()
    }

    // --- the two ways out --------------------------------------------------------------------

    @Test
    fun `the buttons out of the screen are wired to their own callbacks`() {
        val journal = Journal()
        val workout = twoExerciseWorkout(journal)
        workoutScreen(journal, workout, running = true)

        compose.onNodeWithContentDescription("Back to the day").performClick()
        assertEquals(1, closed)
        assertEquals(0, continued)

        compose.onNodeWithText("Continue this workout").performClick()
        assertEquals(1, continued)
    }

    @Test
    fun `a workout the journal no longer has says so rather than drawing nothing`() {
        workoutScreen(Journal(), workoutId = 404L)

        compose.onNodeWithText("This workout is no longer in the journal.").assertIsDisplayed()
    }
}
