package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowDialog
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.slot
import java.time.LocalDate

/**
 * The slot editor: the dialog behind "Plan a session" and behind the pencil on a planned
 * card.
 *
 * Two of the things asserted here were argued for in the file's own header and had no way
 * of being checked. The first is the time field, which takes DIGITS ONLY because a phone's
 * number keypad has no colon key, and puts the separator in as they arrive. The second is
 * the exercise picker, which has to come up as a window of ITS OWN, above the dialog:
 * composed inside the dialog's content it would be clipped to the dialog's box, and the
 * author had nothing to confirm the stacking with.
 */
class SlotEditorTest : ScreenTest() {

    private val day = LocalDate.parse("2026-08-07")

    private val catalog = listOf(exerciseEntity(1, "Bench press"), exerciseEntity(2, "Squat"))
    private val state = UiState(exercises = catalog, loading = false)

    private var saved: SlotDraft? = null
    private var deleted = 0
    private var dismissed = 0

    private fun editor(initial: Slot? = null, suggestions: List<String> = emptyList()) {
        screen {
            SlotEditorDialog(
                initial = initial,
                day = day,
                suggestions = suggestions,
                today = day,
                state = state,
                onSave = { saved = it },
                onDelete = { deleted++ },
                onDismiss = { dismissed++ },
            )
        }
    }

    /** Types a name, because everything except the name is optional and Save needs one. */
    private fun nameIt(name: String = "Gym") {
        compose.onNodeWithText("Session name").performTextInput(name)
    }

    // --- the time, typed without a colon --------------------------------------------------

    @Test
    fun `four digits become a time, with the colon put in for the user`() {
        editor()
        nameIt()

        compose.onNodeWithText("Time (optional)").performTextInput("1700")

        compose.onNodeWithText("17:00").assertIsDisplayed()
        compose.onNodeWithText("Add to the plan").performClick()
        assertEquals("17:00", saved?.timeText)
    }

    @Test
    fun `a single-digit hour is understood as one, so 930 is half past nine`() {
        editor()
        nameIt()

        compose.onNodeWithText("Time (optional)").performTextInput("930")

        compose.onNodeWithText("Add to the plan").performClick()
        assertEquals("9:30", saved?.timeText)
    }

    @Test
    fun `half a time is not a time, and Save stays shut until the last digit arrives`() {
        editor()
        nameIt()

        // "17:0" on screen means the last digit is still coming. Reading it as 17:00 would
        // store a time the user did not type whenever they meant 17:05, and do it silently
        compose.onNodeWithText("Time (optional)").performTextInput("170")
        compose.onNodeWithText("Add to the plan").assertIsNotEnabled()

        compose.onNodeWithText("Time (optional)").performTextInput("5")
        compose.onNodeWithText("Add to the plan").assertIsEnabled().performClick()
        assertEquals("17:05", saved?.timeText)
    }

    @Test
    fun `a quick chip fills the time in one tap`() {
        editor()
        nameIt()

        compose.onNodeWithText("18:00").performClick()

        compose.onNodeWithText("Add to the plan").performClick()
        assertEquals("18:00", saved?.timeText)
    }

    // --- a plan with nothing under it ------------------------------------------------------

    @Test
    fun `a session with no time and no exercises is a complete plan and saves`() {
        editor()
        nameIt("Gym")

        compose.onNodeWithText("Add to the plan").assertIsEnabled().performClick()

        assertNotNull("Save must not be a form to fill in", saved)
        val draft = saved!!
        assertEquals("Gym", draft.name)
        assertEquals("", draft.timeText)
        assertTrue("an empty composition is the plan most of the time", draft.exercises.isEmpty())
    }

    @Test
    fun `a session with no name cannot be saved, and the dialog says which thing is missing`() {
        editor()

        compose.onNodeWithText("Add to the plan").assertIsNotEnabled()
        compose.onNodeWithText("Give the session a name, for example Gym or Fingerboard.")
            .assertIsDisplayed()
    }

    @Test
    fun `Cancel leaves without writing anything`() {
        editor()
        nameIt()

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertNull(saved)
    }

    // --- the exercises, which open and close ------------------------------------------------

    @Test
    fun `a new session keeps the composition shut and says there is nothing in it`() {
        editor()

        compose.onNodeWithText("Exercises - none planned").assertIsDisplayed()
        // an empty required-looking control is a standing reproach, so it is not drawn at all
        compose.onNodeWithText("Add an exercise").assertDoesNotExist()
    }

    @Test
    fun `tapping the composition line opens it, and tapping again shuts it`() {
        editor()

        compose.onNodeWithText("Exercises - none planned").performClick()
        compose.onNodeWithText("Add an exercise").assertIsDisplayed()
        compose.onNodeWithText(
            "Optional. A session with nothing listed is a plan just the same - this is " +
                "only here for when you already know what you are going to do."
        ).assertIsDisplayed()

        compose.onNodeWithText("Exercises - none planned").performClick()
        compose.onNodeWithText("Add an exercise").assertDoesNotExist()
    }

    @Test
    fun `a session that already has exercises opens showing them`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(1, restSec = 150), PlannedExercise(2, null)))
        )

        compose.onNodeWithText("Exercises (2)").assertIsDisplayed()
        compose.onNodeWithText("Bench press").assertIsDisplayed()
        compose.onNodeWithText("Squat").assertIsDisplayed()
        compose.onNodeWithText("Add an exercise").assertIsDisplayed()
    }

    @Test
    fun `an exercise the catalog no longer has keeps its line rather than vanishing`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(404, restSec = null)))
        )

        // a plan quietly losing a line is worse than one showing a line it cannot name
        compose.onNodeWithText("Removed exercise").assertIsDisplayed()
        compose.onNodeWithText("Exercises (1)").assertIsDisplayed()
    }

    @Test
    fun `taking an exercise out of the plan removes its line and is carried into the save`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(1, restSec = 150), PlannedExercise(2, null)))
        )

        compose.onNodeWithText("Squat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Take \"Squat\" out of the plan").performClick()

        compose.onNodeWithText("Exercises (1)").assertIsDisplayed()
        compose.onNodeWithText("Squat").assertDoesNotExist()

        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(1L), saved?.exercises?.map { it.exerciseId })
    }

    // --- the picker, which has to come up ABOVE the dialog -------------------------------------

    /**
     * The stacking the editor's author could not confirm.
     *
     * Both the editor and the picker are windows of their own; the picker's is added second,
     * which is what puts it above. The check is deliberately about WINDOWS rather than about
     * pixels: composed inside the dialog's content the sheet would share the dialog's root,
     * be clipped to the dialog's box, and the assertions below would fail — which is the
     * failure that was feared. What this still cannot say is whether the sheet is legible
     * once it is up: nothing here is rasterised (see ScreenTest).
     */
    @Test
    fun `the exercise picker comes up as a window of its own, above the editor`() {
        editor()

        compose.onNodeWithText("Exercises - none planned").performClick()
        val dialogsBefore = ShadowDialog.getShownDialogs().size

        compose.onNodeWithText("Add an exercise").performClick()
        settle()

        val dialogs = ShadowDialog.getShownDialogs()
        assertEquals("the picker must add a window rather than draw inside the editor's",
            dialogsBefore + 1, dialogs.size)

        // the sheet is up and searchable, and the editor is still behind it
        compose.onNodeWithText("Exercise").assertIsDisplayed()
        compose.onNodeWithText("Search by name").assertIsDisplayed()
        compose.onNodeWithText("Plan a session").assertIsDisplayed()

        // and the two really are separate composition roots, not one tree
        val sheetRoot = compose.onNodeWithText("Search by name").fetchSemanticsNode().root
        val editorRoot = compose.onNodeWithText("Plan a session").fetchSemanticsNode().root
        assertNotSame("the sheet must not be clipped to the dialog's box", editorRoot, sheetRoot)
    }

    @Test
    fun `picking an exercise from the sheet puts it into the draft and closes the sheet`() {
        editor()
        nameIt()

        compose.onNodeWithText("Exercises - none planned").performClick()
        compose.onNodeWithText("Add an exercise").performClick()
        settle()
        compose.onNodeWithText("Bench press").performClick()

        compose.onNodeWithText("Exercises (1)").assertIsDisplayed()
        compose.onNodeWithText("Add to the plan").performClick()
        assertEquals(listOf(1L), saved?.exercises?.map { it.exerciseId })
    }

    /**
     * Planning picks from what is already trained. Creating an exercise asks the identity
     * questions (form, edge, protocol) that belong to the moment of the first set, days
     * after a plan is written, so the button is not there to be pressed.
     */
    @Test
    fun `the picker opened from the plan offers no way to invent a new exercise`() {
        editor()

        compose.onNodeWithText("Exercises - none planned").performClick()
        compose.onNodeWithText("Add an exercise").performClick()
        settle()

        // substring, because the label is padded with spaces where it does exist
        compose.onNodeWithText("New exercise", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Create your first exercise", substring = true).assertDoesNotExist()
    }

    // --- editing an existing session -----------------------------------------------------------

    @Test
    fun `editing opens on the session as it stands and saves what was changed`() {
        editor(initial = slot(7, "Gym", "18:00", day.toString()))

        compose.onNodeWithText("Edit this session").assertIsDisplayed()
        compose.onNodeWithText("Gym").assertIsDisplayed()
        // twice: the field carries the time, and the quick chip for it shows as chosen
        compose.onAllNodesWithText("18:00").assertCountEquals(2)

        compose.onNodeWithText("Session name").performTextReplacement("Hangboard")
        compose.onNodeWithText("Save").performClick()

        assertEquals("Hangboard", saved?.name)
        assertEquals("18:00", saved?.timeText)
    }

    @Test
    fun `a session that does not exist yet has nothing to delete`() {
        editor()

        compose.onNodeWithText("Delete this session").assertDoesNotExist()
    }

    @Test
    fun `an existing session offers to be deleted, and the offer is wired up`() {
        editor(initial = slot(7, "Gym", "18:00", day.toString()))

        compose.onNodeWithText("Delete this session").performClick()

        assertEquals(1, deleted)
    }
}
