package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertCountEquals
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.protocolProgram
import xyz.oleolegka.gachimuchi.ui.protocolProgramIdFor
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
 *
 * ── Why this one class is measured on a wide window ─────────────────────────────
 * A Material text field inside a DIALOG never lets the composition settle under Robolectric
 * at any phone width: `setContent` spins until the idling strategy gives up, and it does so
 * for a bare `OutlinedTextField` in an otherwise empty `AlertDialog`. It settles at 600 dp,
 * where the dialog reaches its own maximum width instead of the platform's
 * percentage-of-the-screen default — so the loop is in that measurement, not in this
 * editor.
 *
 * Everything asserted below is text and callbacks, which a window size does not change, so
 * the override buys the whole file at no cost to what it proves. What it does NOT prove is
 * that the dialog fits a phone: this file has nothing to say about the editor's layout at
 * 411 dp, and neither does any other test. That belongs on the list in [ScreenTest].
 */
@Config(sdk = [34], qualifiers = "w600dp-h2400dp-xhdpi")
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

    /**
     * Types into the field carrying [label].
     *
     * The [settle] afterwards is not decoration: with the frame clock held still an edit
     * reaches the field but the recomposition it causes has not been drawn, so the very next
     * assertion reads the state as it was BEFORE the typing — which looks exactly like a
     * field that ignored its input.
     */
    private fun type(label: String, text: String) {
        /*
         * Matched by "can be typed into", not by text alone. A filled Material field puts its
         * label in a node of its OWN, next to the field, and a plain text match lands on that
         * label - which has no focus to request, so the input fails with a message about
         * RequestFocus rather than about the field. Asking for a node that has a set-text
         * action can only ever find the field.
         */
        compose.onNodeWithText(label).performTextInput(text)
        settle()
    }

    /**
     * Types into a [TimeField], which unlike an ordinary field carries its label in a Text of
     * its OWN above the box. Matching by that label would land on the Text, which has no focus
     * to give; the field is addressed by the accessible name it was given for exactly this
     * reason (see TimeField).
     */
    private fun typeTime(label: String, text: String) {
        compose.onNode(hasSetTextAction() and hasContentDescription(label))
            .performTextInput(text)
        settle()
    }

    /** Types a name, because everything except the name is optional and Save needs one. */
    private fun nameIt(name: String = "Gym") = type("Session name", name)

    /**
     * A node in the dialog's own scrolling body, brought into view first.
     *
     * The body scrolls (it is a `Column` with `verticalScroll`), so its lower half — the
     * exercises, the problem line, the delete button — used to sit outside the window.
     *
     * THIS NO LONGER SCROLLS TO IT, and that is not a style choice. `performScrollTo` is an
     * animation, and this suite freezes the animation clock on purpose (see ScreenTest): a
     * scroll that can never finish makes the test HANG rather than fail, and it takes the whole
     * run down with it - which is exactly what it did the first time a text field was typed
     * into here. The window is tall enough to hold the editor instead, so nothing has to move.
     */
    private fun inBody(text: String, substring: Boolean = false) =
        compose.onNodeWithText(text, substring = substring)

    /**
     * The "add an exercise" button, matched loosely because its label is padded with spaces
     * to stand off the icon beside it. Matching that padding exactly would make the test
     * fail the day somebody replaces it with real spacing.
     */
    private fun addExerciseButton() = inBody("Add an exercise", substring = true)

    private fun bodyIcon(description: String) =
        compose.onNodeWithContentDescription(description)

    // --- the time, typed without a colon --------------------------------------------------

    @Test
    fun `four digits become a time, with the colon put in for the user`() {
        editor()
        nameIt()

        type("Time (optional)", "1700")

        inBody("17:00").assertExists()
        compose.onNodeWithText("Add to the plan").performClick()
        settle()
        assertEquals("17:00", saved?.timeText)
    }

    @Test
    fun `a single-digit hour is understood as one, so 930 is half past nine`() {
        editor()
        nameIt()

        type("Time (optional)", "930")

        compose.onNodeWithText("Add to the plan").performClick()

        settle()
        assertEquals("9:30", saved?.timeText)
    }

    @Test
    fun `half a time is not a time, and Save stays shut until the last digit arrives`() {
        editor()
        nameIt()

        // "17:0" on screen means the last digit is still coming. Reading it as 17:00 would
        // store a time the user did not type whenever they meant 17:05, and do it silently
        type("Time (optional)", "170")
        compose.onNodeWithText("Add to the plan").assertIsNotEnabled()

        // what is being asserted here is only the gate: a complete time opens Save and is
        // stored as typed. The keystroke-by-keystroke route in is the test below
        compose.onNodeWithText("Time (optional)").performTextReplacement("1705")
        settle()
        compose.onNodeWithText("Add to the plan").assertIsEnabled().performClick()
        settle()
        assertEquals("17:05", saved?.timeText)
    }

    /**
     * The digits typed ONE AT A TIME give the time that was typed.
     *
     * This is how a phone is actually used, and it is the case the field has to work for: it
     * rewrites its own contents on every keystroke to slot the colon in, and each rewrite moves
     * the caret. After the third digit the text reads "17:0" and the caret has to be behind the
     * zero, at offset 4 — left where the keyboard put it, at 3, the fourth digit lands in front
     * of the zero and 17:05 is stored as 17:50, silently. The four-digit test above types the
     * whole string in one go, so it says nothing about this.
     */
    @Test
    fun `a time typed one digit at a time comes out as it was typed`() {
        editor()
        nameIt()

        listOf("1", "7", "0", "5").forEach { type("Time (optional)", it) }

        compose.onNodeWithText("Add to the plan").performClick()
        settle()
        assertEquals("the caret must follow the colon that was inserted", "17:05", saved?.timeText)
    }

    /**
     * A time put in from OUTSIDE the field — a quick chip, the clock, Clear — takes the caret
     * with it. The field holds its own caret now, so a value written past it would leave that
     * caret pointing into a string that is no longer there, and the next keystroke would be
     * placed by it.
     */
    @Test
    fun `a time cleared after a chip can be typed again from scratch`() {
        editor()
        nameIt()

        inBody("18:00").performClick()
        settle()
        inBody("Clear").performClick()
        settle()

        listOf("1", "7", "0", "5").forEach { type("Time (optional)", it) }

        compose.onNodeWithText("Add to the plan").performClick()
        settle()
        assertEquals("17:05", saved?.timeText)
    }

    @Test
    fun `a quick chip fills the time in one tap`() {
        editor()
        nameIt()

        inBody("18:00").performClick()

        settle()

        compose.onNodeWithText("Add to the plan").performClick()

        settle()
        assertEquals("18:00", saved?.timeText)
    }

    // --- a plan with nothing under it ------------------------------------------------------

    @Test
    fun `a session with no time and no exercises is a complete plan and saves`() {
        editor()
        nameIt("Gym")

        compose.onNodeWithText("Add to the plan").assertIsEnabled().performClick()

        settle()

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
        inBody("Give the session a name, for example Gym or Fingerboard.").assertExists()
    }

    @Test
    fun `Cancel leaves without writing anything`() {
        editor()
        nameIt()

        compose.onNodeWithText("Cancel").performClick()

        settle()

        assertEquals(1, dismissed)
        assertNull(saved)
    }

    // --- the exercises, which open and close ------------------------------------------------

    @Test
    fun `a new session keeps the composition shut and says there is nothing in it`() {
        editor()

        inBody("Exercises - none planned").assertExists()
        // an empty required-looking control is a standing reproach, so it is not drawn at all
        compose.onNodeWithText("Add an exercise", substring = true).assertDoesNotExist()
    }

    @Test
    fun `tapping the composition line opens it, and tapping again shuts it`() {
        editor()

        inBody("Exercises - none planned").performClick()

        settle()
        addExerciseButton().assertExists()
        inBody(
            "Optional. A session with nothing listed is a plan just the same - this is " +
                "only here for when you already know what you are going to do."
        ).assertExists()

        inBody("Exercises - none planned").performClick()

        settle()
        compose.onNodeWithText("Add an exercise", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a session that already has exercises opens showing them`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(1, restSec = 150), PlannedExercise(2, null)))
        )

        inBody("Exercises (2)").assertExists()
        inBody("Bench press").assertExists()
        inBody("Squat").assertExists()
        addExerciseButton().assertExists()
    }

    @Test
    fun `an exercise the catalog no longer has keeps its line rather than vanishing`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(404, restSec = null)))
        )

        // a plan quietly losing a line is worse than one showing a line it cannot name
        inBody("Removed exercise").assertExists()
        inBody("Exercises (1)").assertExists()
    }

    @Test
    fun `taking an exercise out of the plan removes its line and is carried into the save`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(1, restSec = 150), PlannedExercise(2, null)))
        )

        inBody("Squat").assertExists()
        bodyIcon("Take \"Squat\" out of the plan").performClick()
        settle()

        inBody("Exercises (1)").assertExists()
        compose.onNodeWithText("Squat").assertDoesNotExist()

        compose.onNodeWithText("Save").performClick()

        settle()
        assertEquals(listOf(1L), saved?.exercises?.map { it.exerciseId })
    }

    // --- the rest, typed as mm:ss (§13.9) -------------------------------------------------
    //
    // It used to be six chips capped at 4:00 — "not able to choose anything above 4:00" was
    // the complaint that started this. These are the regression for the field that replaced
    // them: xyz.oleolegka.gachimuchi.ui.components.TimeField, shared with the rest dialog
    // inside a live workout.

    @Test
    fun `a rest already chosen is shown as mm colon ss, not on a chip`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(1, restSec = 150)))
        )

        inBody("2:30").assertExists()
        inBody("Usual").assertExists()
    }

    /** THE regression: five minutes is past every preset chip this row used to offer. */
    @Test
    fun `a rest past the old four-minute ceiling can be typed and saved`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(1, restSec = null)))
        )

        typeTime("Rest, mm:ss", "500")
        inBody("5:00").assertExists()

        compose.onNodeWithText("Save").performClick()
        settle()
        assertEquals(listOf(300), saved?.exercises?.map { it.restSec })
    }

    @Test
    fun `Usual clears whatever was typed`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(1, restSec = 150)))
        )

        inBody("Usual").performClick()
        settle()

        inBody("2:30").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        settle()
        assertEquals(listOf<Int?>(null), saved?.exercises?.map { it.restSec })
    }

    @Test
    fun `the bump adds to whatever the field already holds`() {
        editor(
            initial = slot(7, "Gym", "18:00", day.toString())
                .copy(exercises = listOf(PlannedExercise(1, restSec = 150)))
        )

        compose.onNodeWithText("+30s").performClick()
        settle()

        inBody("3:00").assertExists()
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

        inBody("Exercises - none planned").performClick()

        settle()
        val dialogsBefore = ShadowDialog.getShownDialogs().size

        addExerciseButton().performClick()

        settle()
        settle()

        val dialogs = ShadowDialog.getShownDialogs()
        assertEquals("the picker must add a window rather than draw inside the editor's",
            dialogsBefore + 1, dialogs.size)

        /*
         * The sheet is up and searchable, and the editor is still there behind it. Existence
         * rather than "displayed": a bottom sheet slides into place under an animation this
         * harness does not run (see ScreenTest), so where it has got to on screen is not a
         * fact worth asserting. Which WINDOW it is in — the whole point here — is.
         */
        compose.onNodeWithText("Exercise").assertExists()
        compose.onNodeWithText("Search by name").assertExists()
        compose.onNodeWithText("Plan a session").assertExists()

        // and the two really are separate composition roots, not one tree
        val sheetRoot = compose.onNodeWithText("Search by name").fetchSemanticsNode().root
        val editorRoot = compose.onNodeWithText("Plan a session").fetchSemanticsNode().root
        assertNotSame("the sheet must not be clipped to the dialog's box", editorRoot, sheetRoot)
    }

    @Test
    fun `picking an exercise from the sheet puts it into the draft and closes the sheet`() {
        editor()
        nameIt()

        inBody("Exercises - none planned").performClick()

        settle()
        addExerciseButton().performClick()
        settle()
        settle()
        compose.onNodeWithText("Bench press").performClick()
        settle()

        inBody("Exercises (1)").assertExists()
        compose.onNodeWithText("Add to the plan").performClick()
        settle()
        assertEquals(listOf(1L), saved?.exercises?.map { it.exerciseId })
    }

    /**
     * Planning picks from what is already trained. Creating an exercise asks the identity
     * questions (form, protocol) that belong to the moment of the first set, days after a
     * plan is written, so the button is not there to be pressed.
     */
    @Test
    fun `the picker opened from the plan offers no way to invent a new exercise`() {
        editor()

        inBody("Exercises - none planned").performClick()

        settle()
        addExerciseButton().performClick()
        settle()
        settle()

        // substring, because the label is padded with spaces where it does exist
        compose.onNodeWithText("New exercise", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Create your first exercise", substring = true).assertDoesNotExist()
    }

    /**
     * A hidden exercise is not offered, which is the whole of what hiding does.
     *
     * Asserted on the picker rather than on the store, because hiding is a PRESENTATION choice
     * and this is the presentation: the row is still in `state.exercises`, still carries every
     * set it ever had, and still appears on the overview. If it were being filtered anywhere
     * further down than this list, that would be a delete wearing a different word.
     */
    @Test
    fun `an exercise that has been hidden is not offered by the picker`() {
        screen {
            SlotEditorDialog(
                initial = null,
                day = day,
                suggestions = emptyList(),
                today = day,
                state = UiState(
                    exercises = listOf(
                        exerciseEntity(1, "Bench press"),
                        exerciseEntity(2, "Squat", hidden = true),
                    ),
                    loading = false,
                ),
                onSave = { saved = it },
                onDelete = { deleted++ },
                onDismiss = { dismissed++ },
            )
        }

        inBody("Exercises - none planned").performClick()
        settle()
        addExerciseButton().performClick()
        settle()
        settle()

        compose.onNodeWithText("Bench press").assertExists()
        compose.onNodeWithText("Squat").assertDoesNotExist()
    }

    /**
     * Two exercises of one name, told apart by the thing that makes them two.
     *
     * This became possible only when creating an exercise stopped deduplicating by name — up
     * to then a second "Hangs" on another protocol was silently handed the first one's row. A
     * list showing two identical lines would put the same failure back one step later, with
     * the user picking whichever came first and their sets landing in an arbitrary history.
     */
    @Test
    fun `two exercises of one name are told apart by the protocol`() {
        screen {
            SlotEditorDialog(
                initial = null,
                day = day,
                suggestions = emptyList(),
                today = day,
                state = UiState(
                    exercises = listOf(
                        exerciseEntity(1, "Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0),
                        exerciseEntity(2, "Hangs", ExerciseForm.HOLD, workSec = 10.0, restSec = 5.0),
                    ),
                    programsById = mapOf(
                        protocolProgramIdFor(1) to protocolProgram(1, "Hangs", 7.0, 3.0),
                        protocolProgramIdFor(2) to protocolProgram(2, "Hangs", 10.0, 5.0),
                    ),
                    loading = false,
                ),
                onSave = { saved = it },
                onDelete = { deleted++ },
                onDismiss = { dismissed++ },
            )
        }

        inBody("Exercises - none planned").performClick()
        settle()
        addExerciseButton().performClick()
        settle()
        settle()

        compose.onAllNodesWithText("Hangs").assertCountEquals(2)
        compose.onNodeWithText("7:3", substring = true).assertExists()
        compose.onNodeWithText("10:5", substring = true).assertExists()
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
        settle()
        compose.onNodeWithText("Save").performClick()
        settle()

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

        inBody("Delete this session").performClick()

        settle()

        assertEquals(1, deleted)
    }
}
