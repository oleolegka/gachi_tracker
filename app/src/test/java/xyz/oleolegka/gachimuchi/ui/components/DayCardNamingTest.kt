package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.dayCards
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.exerciseRef
import java.time.LocalDate

/**
 * Giving a workout a name, on the way in and afterwards (§14.3).
 *
 * ── A class of its own, and the window is why ───────────────────────────────────
 * These raise a DIALOG CARRYING A TEXT FIELD, which under Robolectric never lets the
 * composition settle at phone width — the trap is written out in [ScreenTest]. That needs a
 * 600 dp window, and putting the override on [DayCardListTest] would silently move every
 * assertion in that class to a screen size the app is not built for.
 *
 * The gap it leaves is the usual one, said out loud: nothing exercises this dialog at 411 dp.
 * The assertions are text and callbacks, which a window size does not change; a dialog clipped
 * or scrolled wrong on a real phone would pass every one of them.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class DayCardNamingTest : ScreenTest() {

    private val today = LocalDate.parse("2026-08-07")
    private val yesterday = today.minusDays(1)
    private val bench = exerciseRef(1, "Bench press")

    private var startedWorkout: Pair<LocalDate, String?>? = null
    private var renamed: Pair<Long, String?>? = null

    private fun day(
        events: List<JournalEvent> = emptyList(),
        date: LocalDate = today,
        pastWorkoutNames: List<String> = emptyList(),
    ) {
        val cards = dayCards(events, emptyList(), date, today, today.atTime(12, 0))
        screen {
            DayCardList(
                day = cards,
                date = date,
                actions = DayActions(
                    startFromPlan = { _, _ -> },
                    startWorkout = { d, name -> startedWorkout = d to name },
                    logSingleEntry = {},
                    continueWorkout = {},
                    openWorkout = {},
                    openExercise = { _, _ -> },
                    deleteWorkout = {},
                    deleteSingleEntries = { _, _ -> },
                    renameWorkout = { id, name -> renamed = id to name },
                    resumeDraft = {},
                    discardDraft = {},
                ),
                pastWorkoutNames = pastWorkoutNames,
            )
        }
    }

    /** A workout nobody named, on yesterday so it is not the one in progress. */
    private fun unnamedWorkout(journal: Journal): Long {
        val workout = journal.startWorkout(yesterday.toString(), at = "18:05")
        journal.addExercise(workout, yesterday.toString(), bench, restSec = 150)
        journal.strengthSet(bench, yesterday.toString(), at = "18:10", workoutId = workout)
        journal.finishWorkout(workout, yesterday.toString(), at = "18:30")
        return workout
    }

    private fun openAddMenuOnWorkout() {
        compose.onNodeWithText("Add").performClick()
        settle()
        compose.onNodeWithText("Workout").performClick()
        settle()
        settle()
    }

    // --- naming on the way in ---------------------------------------------------------

    /**
     * The whole trade of §14.3: the option exists and it costs one tap to decline. The button
     * is enabled on arrival with the field empty, and pressing it starts a nameless workout.
     */
    @Test
    fun `starting a workout asks for a name and takes silence for an answer`() {
        day()
        openAddMenuOnWorkout()

        compose.onNodeWithText("Start a workout").assertIsDisplayed()
        compose.onNodeWithText(
            "Leave it empty and the card shows the time of day instead. It can be named " +
                "later from the card."
        ).assertIsDisplayed()

        compose.onNodeWithText("Start").performClick()
        assertEquals(today to null, startedWorkout)
    }

    @Test
    fun `a name typed in travels with the day the workout is started on`() {
        day(date = yesterday)
        openAddMenuOnWorkout()

        compose.onNodeWithText("Name (optional)").performTextReplacement("Push day")
        settle()
        compose.onNodeWithText("Start").performClick()

        assertEquals(yesterday to "Push day", startedWorkout)
    }

    /** Nothing but spaces is nobody having named it, settled here and not in the journal. */
    @Test
    fun `a name of nothing but spaces is no name at all`() {
        day()
        openAddMenuOnWorkout()

        compose.onNodeWithText("Name (optional)").performTextReplacement("   ")
        settle()
        compose.onNodeWithText("Start").performClick()

        assertEquals(today to null, startedWorkout)
    }

    // --- starting like a past workout (§13.9) -------------------------------------------

    /**
     * Nothing to pick from is the ordinary state (nobody has named a workout yet) and must not
     * grow a control that offers an empty list.
     */
    @Test
    fun `no past names means no dropdown on the field`() {
        day()
        openAddMenuOnWorkout()

        compose.onNodeWithContentDescription("Start like a past workout").assertDoesNotExist()
    }

    @Test
    fun `picking a past name from the dropdown fills the field, and starting under it goes through as typed text`() {
        day(pastWorkoutNames = listOf("Push day", "Pull day"))
        openAddMenuOnWorkout()

        compose.onNodeWithContentDescription("Start like a past workout").performClick()
        settle()
        compose.onNodeWithText("Push day").performClick()
        settle()
        compose.onNodeWithText("Start").performClick()

        // the dialog itself knows nothing about templates - it only ever hands back a string,
        // exactly as if the same word had been typed by hand
        assertEquals(today to "Push day", startedWorkout)
    }

    @Test
    fun `backing out of the question starts nothing`() {
        day()
        openAddMenuOnWorkout()

        compose.onNodeWithText("Cancel").performClick()
        assertNull(startedWorkout)
    }

    // --- naming one that is already going ----------------------------------------------

    @Test
    fun `a workout nobody named offers to be named, from its own long press`() {
        val journal = Journal()
        val workout = unnamedWorkout(journal)
        day(journal.events, date = yesterday)

        compose.onNodeWithText("18:05 - 18:10").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Name it").performClick()
        settle()
        settle()

        compose.onNodeWithText("Name this workout").assertIsDisplayed()
        compose.onNodeWithText("Name (optional)").performTextReplacement("Evening gym")
        settle()
        compose.onNodeWithText("Save").performClick()

        assertEquals(workout to "Evening gym", renamed)
    }

    /**
     * A named one says "Rename", and the field starts on the name rather than on the time
     * range it would otherwise be titled by — the range is a fact about the day, never a name.
     */
    @Test
    fun `a named workout is renamed from the name it already has`() {
        val journal = Journal()
        val workout = journal.startWorkout(yesterday.toString(), at = "18:05", name = "Push day")
        journal.strengthSet(bench, yesterday.toString(), at = "18:10", workoutId = workout)
        journal.finishWorkout(workout, yesterday.toString(), at = "18:30")
        day(journal.events, date = yesterday)

        compose.onNodeWithText("Push day").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Rename").performClick()
        settle()
        settle()

        compose.onNodeWithText("Rename this workout").assertIsDisplayed()
        // the field is on the current name, so correcting a typo is not retyping the word.
        // Addressed by its LABEL: "Push day" is also the title of the card behind the dialog
        compose.onNodeWithText("Name (optional)").assertTextContains("Push day")

        compose.onNodeWithText("Save").performClick()
        assertEquals(workout to "Push day", renamed)
    }

    /** Emptying the field is how a workout goes back to being shown by its time. */
    @Test
    fun `clearing the name is a legal answer and hands back nothing`() {
        val journal = Journal()
        val workout = journal.startWorkout(yesterday.toString(), at = "18:05", name = "Push day")
        journal.strengthSet(bench, yesterday.toString(), at = "18:10", workoutId = workout)
        journal.finishWorkout(workout, yesterday.toString(), at = "18:30")
        day(journal.events, date = yesterday)

        compose.onNodeWithText("Push day").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Rename").performClick()
        settle()
        settle()

        compose.onNodeWithText("Name (optional)").performTextReplacement("")
        settle()
        compose.onNodeWithText("Save").performClick()

        assertEquals(workout to null, renamed)
    }
}
