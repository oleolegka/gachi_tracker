package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.protocolProgram
import xyz.oleolegka.gachimuchi.ui.protocolProgramIdFor
import java.time.LocalDate

/**
 * The PICK half of the exercise picker, redrawn against `app-next/exercise-picker.html`.
 *
 * What is asserted here is the part of that redraw a screenshot cannot check for itself: that
 * the row's four facts are joined by ONE separator rather than three (rule 4), that the count
 * beside the heading exists at all — it is the other half of the signal that the list is
 * clipped and scrolls — and that a search finding nothing offers its two exits as buttons
 * instead of naming them inside a paragraph (rules 2 and 6).
 *
 * Names are unique per test on purpose: this class holds no database, but the suite's habit of
 * reusing "Hangs" across classes is exactly how a Robolectric run starts asserting on somebody
 * else's row.
 */
@Config(sdk = [34], qualifiers = "w600dp-h2400dp-xhdpi")
class ExercisePickerListTest : ScreenTest() {

    private val today = LocalDate.parse("2026-08-11")

    private val catalog = UiState(
        loading = false,
        exercises = listOf(
            exerciseEntity(1, "Deadhang 20 mm", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0),
            exerciseEntity(2, "Back squat", ExerciseForm.STRENGTH),
        ),
        programsById = mapOf(
            protocolProgramIdFor(1) to protocolProgram(1, "Deadhang 20 mm", 7.0, 3.0),
        ),
    )

    private var picked: Long? = null
    private var creating = 0

    private fun sheet(state: UiState = catalog, canCreate: Boolean = true) {
        screen {
            ExercisePickerSheet(
                state = state,
                today = today,
                onPick = { picked = it },
                onCreate = if (canCreate) ({ creating++ }) else null,
                onDismiss = {},
            )
        }
        settle()
        settle()
    }

    /** The count is not in the model; it is here because a clipped list needs one. */
    @Test
    fun `the heading says how many there are`() {
        sheet()

        compose.onNodeWithText("2 exercises").assertIsDisplayed()
    }

    /**
     * The row used to read "Holds - 7:3 - 12 entries, last on 9 Aug": three fields and three
     * different punctuation marks, the last of them a comma that welded two facts into one.
     */
    @Test
    fun `the row joins its facts with one separator`() {
        sheet()

        compose.onNodeWithText("Holds · 7:3 · not logged yet").assertIsDisplayed()
        compose.onNodeWithText("Strength · not logged yet").assertIsDisplayed()
    }

    /**
     * A search that matched nothing is not a dead end, and its two exits are not the same move:
     * one finds an exercise that already has a history, the other starts a second one beside it.
     */
    @Test
    fun `a search that finds nothing offers its two exits as buttons`() {
        sheet()
        compose.onNodeWithText("Search by name").performTextReplacement("frontlever")
        settle()

        compose.onNodeWithText("0 of 2").assertIsDisplayed()
        compose.onNodeWithText("No exercise is called \"frontlever\".").assertIsDisplayed()
        compose.onNodeWithText("Create \"frontlever\"").assertIsDisplayed()

        compose.onNodeWithText("Clear the search").performClick()
        settle()
        compose.onNodeWithText("2 exercises").assertIsDisplayed()
    }

    /**
     * The slot editor cannot create, so no exit that does not exist may be named — and the
     * heading it passes is its own.
     */
    @Test
    fun `a caller that cannot create is offered nothing to create with`() {
        sheet(state = UiState(loading = false), canCreate = false)

        compose.onNodeWithText("Create your first exercise").assertDoesNotExist()
        compose.onNodeWithText(
            "Nothing in the catalog yet. Exercises are created while logging a workout; " +
                "once one exists it can be planned here.",
        ).assertIsDisplayed()
    }

    /** Picking still does the one thing the sheet is for. */
    @Test
    fun `a row hands its id back`() {
        sheet()
        compose.onNodeWithText("Back squat").performClick()

        assertEquals(2L, picked)
    }
}
