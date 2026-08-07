package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * Correcting and removing an entry from the workout review screen (§13.6).
 *
 * ── A class of its own, and the window is why ───────────────────────────────────
 * These raise a DIALOG CARRYING TEXT FIELDS, which under Robolectric never lets the
 * composition settle at phone width (the trap is written out in [ScreenTest]). That needs a
 * 600 dp window, and putting the override on [WorkoutScreenTest] would silently move every
 * assertion in that class to a screen size the app is not built for. So the editing tests sit
 * here with the wide window and the read-only ones keep theirs.
 *
 * The gap that leaves is the usual one, said out loud: nothing exercises this dialog at 411
 * dp. The assertions are text and callbacks, which a window size does not change; a dialog
 * clipped or scrolled wrong on a real phone would pass every one of them.
 *
 * ── What is asserted here, and what belongs elsewhere ───────────────────────────
 * This screen's job is to hand the right event id and the right corrected form to its caller,
 * and that is what most of these check. Whether a correction then reaches every reader is the
 * amendment funnel's job and is tested where the funnel lives. The one thing checked here is
 * that this screen READS through it — a different failure, and the one that actually happened:
 * `buildWorkout` used to read the raw journal, so a cancelled set stayed visible inside its
 * workout while the day's feed had already dropped it.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class WorkoutEntryEditingTest : ScreenTest() {

    private val iso = "2026-08-07"
    private val bench = exerciseRef(1, "Bench press")
    private val catalog = listOf(exerciseEntity(1, "Bench press"))

    private val amended = mutableListOf<Pair<Long, ActivityForm>>()
    private val deleted = mutableListOf<Long>()

    private fun show(journal: Journal, workoutId: Long) {
        val state = UiState(events = journal.events, exercises = catalog, loading = false)
        screen {
            WorkoutScreen(
                state = state,
                workoutId = workoutId,
                onContinue = null,
                onClose = {},
                onAmendEntry = { id, form -> amended += id to form },
                onDeleteEntry = { id -> deleted += id },
            )
        }
    }

    /**
     * Two bench sets, so "any entry, not only the last" has something to mean.
     *
     * Rows 1 and 2 open the workout and put the exercise in it, so the first bench set is
     * event 3 and the second is event 4. Event 3 is the one "Undo last" could never reach.
     */
    private fun workout(journal: Journal): Long {
        val id = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(id, iso, bench, restSec = 150)
        journal.strengthSet(bench, iso, at = "18:10", weightKg = 60.0, reps = 5, workoutId = id)
        journal.strengthSet(bench, iso, at = "18:14", weightKg = 62.5, reps = 5, workoutId = id)
        return id
    }

    /** Raises the action menu of the OLDER of the two sets. */
    private fun pressAndHoldFirstSet(journal: Journal, workoutId: Long) {
        show(journal, workoutId)
        compose.onNodeWithText("60 kg × 5 reps").performTouchInput { longClick() }
        settle()
    }

    /** Opens the editor on the OLDER of the two sets, through the gesture that offers it. */
    private fun openEditorOnFirstSet(journal: Journal, workoutId: Long) {
        pressAndHoldFirstSet(journal, workoutId)
        compose.onNodeWithText("Correct").performClick()
        settle()
        settle()
    }

    /**
     * The gesture is the whole of the affordance now: there is no per-row button, and the
     * older set is reached exactly as the newer one is.
     */
    @Test
    fun `a long press on any set offers to correct it and to remove it`() {
        val journal = Journal()
        val id = workout(journal)

        show(journal, id)
        // nothing on the row itself: a list of finished sets carries no controls
        assertEquals(0, compose.onAllNodesWithText("Edit").fetchSemanticsNodes().size)
        compose.onNodeWithText("Correct").assertDoesNotExist()

        compose.onNodeWithText("60 kg × 5 reps").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Correct").assertExists()
        compose.onNodeWithText("Remove entry").assertExists()
    }

    /**
     * The correction people actually make: it was typed as 60 and it was 65. Done on the FIRST
     * of the two sets, so this pins that editing is not restricted to the last entry either.
     *
     * The weight is changed with the field's own +5 button rather than by typing, because the
     * step buttons are unambiguous nodes and the arithmetic is the field's own.
     */
    @Test
    fun `correcting a set hands back its own event id and the new value`() {
        val journal = Journal()
        openEditorOnFirstSet(journal, workout(journal))

        compose.onNodeWithText("+5").performClick()
        settle()
        compose.onNodeWithText("Save").performClick()

        val (eventId, form) = amended.single()
        assertEquals(3L, eventId)
        assertEquals(65.0, (form as StrengthSet).weightKg!!, 1e-9)
        // everything nobody touched survives the correction
        assertEquals(5, form.reps)
        assertEquals(iso, form.opDate)
    }

    /**
     * The day is a value like any other, and "I logged this on Tuesday but did it on Monday" is
     * the correction this funnel was worth building for — it moves the entry on the calendar,
     * the heatmap and the streak.
     */
    @Test
    fun `the day an entry is filed under can be corrected`() {
        val journal = Journal()
        openEditorOnFirstSet(journal, workout(journal))

        // the date field is addressed by its value, which is the only text a StepperField has
        compose.onNodeWithText(iso).performTextReplacement("2026-08-03")
        settle()
        compose.onNodeWithText("Save").performClick()

        assertEquals("2026-08-03", amended.single().second.opDate)
    }

    /**
     * A value the journal would refuse must not be writable, and the reason it is refused has
     * to be on screen. The form validators are the judge, so this covers every field at once:
     * an empty day cannot be parsed, so nothing is offered to write.
     */
    @Test
    fun `a value the journal would refuse cannot be saved, and says why`() {
        val journal = Journal()
        openEditorOnFirstSet(journal, workout(journal))

        compose.onNodeWithText(iso).performTextClearance()
        settle()

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText(
            "Something here is not a value this entry can take - check the day and the numbers."
        ).assertExists()
        assertTrue("nothing may be written while the entry would be illegal", amended.isEmpty())
    }

    /** Moving a set to another exercise is not offered, because the repository refuses it. */
    @Test
    fun `the editor never offers to change which exercise the entry belongs to`() {
        val journal = Journal()
        openEditorOnFirstSet(journal, workout(journal))

        compose.onNodeWithText(
            "Which exercise this belongs to cannot be changed here. Moving a set is removing " +
                "it and recording it again, so that an exercise's history stays the entries " +
                "that always were its own."
        ).assertExists()
    }

    /** Removing asks once more first: a list of finished sets is a bad place for a mis-tap. */
    @Test
    fun `removing an entry asks before it writes, and then removes that entry`() {
        val journal = Journal()
        pressAndHoldFirstSet(journal, workout(journal))

        compose.onNodeWithText("Remove entry").performClick()
        settle()

        compose.onNodeWithText("Remove this entry?").assertExists()
        assertTrue("nothing may be written before the question is answered", deleted.isEmpty())

        // the older set, not the newest: "Undo last" could never have reached this one
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(3L), deleted)
    }

    /** The question can be answered no, and answering no writes nothing at all. */
    @Test
    fun `keeping an entry at the question leaves the journal alone`() {
        val journal = Journal()
        pressAndHoldFirstSet(journal, workout(journal))

        compose.onNodeWithText("Remove entry").performClick()
        settle()
        compose.onNodeWithText("Keep it").performClick()
        settle()

        assertTrue("answering no must write nothing", deleted.isEmpty())
        compose.onNodeWithText("Remove this entry?").assertDoesNotExist()
        compose.onNodeWithText("60 kg × 5 reps").assertExists()
    }

    /**
     * ONE path to a deletion, which is the point of moving it out of the editor: the dialog
     * that corrects an entry no longer removes one.
     */
    @Test
    fun `the editor offers no second way to remove the entry it is correcting`() {
        val journal = Journal()
        openEditorOnFirstSet(journal, workout(journal))

        compose.onNodeWithText("Correct this entry").assertExists()
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }

    /**
     * The half a callback assertion cannot reach: once a deletion is IN the journal, this
     * screen must stop showing the entry and must count what is left rather than what was once
     * written.
     */
    @Test
    fun `an entry deleted in the journal is gone from the workout it was in`() {
        val journal = Journal()
        val id = workout(journal)
        journal.deleteEntry(3L)
        show(journal, id)

        compose.onNodeWithText("60 kg × 5 reps").assertDoesNotExist()
        compose.onNodeWithText("62.5 kg × 5 reps").assertExists()
        compose.onNodeWithText("Fri 7 Aug - 1 exercise, 1 set").assertExists()
    }
}
