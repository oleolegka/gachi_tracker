package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.ui.ScreenTest

/**
 * The library editor's half of the freeze: a program some exercise's protocol IS opens with
 * its content shown as text, not fields, and Save still works for the one thing that is not
 * frozen — the name.
 *
 * The refusal itself is enforced in `ProgramRepository.save` (see `ProgramFreezeTest`, `data`
 * package); what this checks is that the screen does not even OFFER the controls that would
 * then silently do nothing — see [ProgramEditorScreen]'s own KDoc on `locked`.
 *
 * ── Why this class gets a window taller than the base one ───────────────────────
 * The unlocked editor is longer than a phone screen, and "Add a group" sits under the fold.
 * Scrolling to it is not an option here: [ScreenTest] holds the animation clock still on
 * purpose, and a scroll is an animation — `performScrollToNode` then waits for a scroll that
 * can never finish and the test hangs forever rather than failing. A window tall enough to
 * hold the whole editor asks the same question without the animation.
 */
@Config(sdk = [34], qualifiers = "w600dp-h1600dp-xhdpi")
class ProgramEditorScreenTest : ScreenTest() {

    private val repeaters = WorkoutProgram(
        id = 1,
        name = "Hangs protocol",
        prepareSec = 15,
        groups = listOf(
            ProgramGroup(
                name = "Hangs",
                blocks = listOf(ProgramBlock(name = "Hangs", workSec = 7, restSec = 3, repeats = 6)),
            )
        ),
    )

    @Test
    fun `a locked program hides the content controls but keeps the name editable`() {
        var saved: WorkoutProgram? = null
        screen {
            ProgramEditorScreen(
                initial = repeaters,
                candidates = emptyList(),
                categories = emptyList(),
                locked = true,
                onSave = { saved = it },
                onClose = {},
            )
        }

        // the lead-in is read text, not the stepper field it is everywhere else
        compose.onNodeWithText("Get ready").assertIsDisplayed()
        compose.onNodeWithText("15 s").assertIsDisplayed()
        compose.onNodeWithText("Get ready, s", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Add a group").assertDoesNotExist()
        // the block itself is read text too, as a table rather than as a sentence
        compose.onNodeWithText("7 s").assertIsDisplayed()
        compose.onNodeWithText("rest after 3 s · x6").assertIsDisplayed()

        // the name is still a field, and the one Save still writes it
        compose.onNodeWithText("Program name").performTextReplacement("Hangs protocol (renamed)")
        compose.onNodeWithText("Save the name and category").performClick()

        assertEquals("Hangs protocol (renamed)", saved?.name)
        // and the content travelled through untouched, because it was never in editable state
        assertEquals(repeaters.groups, saved?.groups)
        assertEquals(repeaters.prepareSec, saved?.prepareSec)
    }

    @Test
    fun `an unlocked program offers the content controls`() {
        screen {
            ProgramEditorScreen(
                initial = repeaters,
                candidates = emptyList(),
                categories = emptyList(),
                locked = false,
                onSave = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("Get ready, s", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Add a group").assertIsDisplayed()
    }

    // --- the redraw against app-next/program-editor.html ---------------------------------

    /**
     * Rule 1, and the defect that produced it: this screen carried a "Save" in the top bar AND
     * a full-width one at the end of the list, called "Use" and "Use this schedule" in the
     * schedule mode — one action, two places, two names.
     */
    @Test
    fun `there is exactly one save on the screen`() {
        screen {
            ProgramEditorScreen(
                initial = repeaters,
                candidates = emptyList(),
                categories = emptyList(),
                onSave = {},
                onClose = {},
            )
        }

        assertEquals(1, compose.onAllNodesWithText("Save").fetchSemanticsNodes().size)
    }

    /** The same, in the mode the create form opens: one button, and it is the schedule's word. */
    @Test
    fun `the schedule mode has one confirm and it is not called Save`() {
        screen {
            ProgramEditorScreen(
                initial = repeaters,
                candidates = emptyList(),
                categories = emptyList(),
                asSchedule = true,
                forExercise = "Hangs 20 mm",
                onSave = {},
                onClose = {},
            )
        }

        assertEquals(
            1,
            compose.onAllNodesWithText("Use this schedule").fetchSemanticsNodes().size,
        )
        compose.onAllNodesWithText("Use").assertCountEquals(0)
        // and the top bar says where leaving lands, three windows deep
        compose.onNodeWithText("6 efforts · 1:12 · for Hangs 20 mm").assertIsDisplayed()
    }

    /**
     * The gate `name.isNotBlank() && totalSec() > 0` used to be silence and a grey button.
     * The condition is unchanged; what is asserted here is that the screen says which half of
     * it is missing.
     */
    @Test
    fun `a program with no name says so beside the button`() {
        screen {
            ProgramEditorScreen(
                initial = repeaters.copy(name = ""),
                candidates = emptyList(),
                categories = emptyList(),
                onSave = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Give it a name.").assertIsDisplayed()
    }

    /**
     * Rule 3: "Remove" was a text button in the same Row as the field the block's name is typed
     * into. It is behind the block's own menu now, and named for what it removes.
     */
    @Test
    fun `removing an effort is not a neighbour of the field it removes`() {
        screen {
            ProgramEditorScreen(
                initial = repeaters.copy(
                    groups = listOf(
                        repeaters.groups[0].copy(
                            blocks = repeaters.groups[0].blocks +
                                ProgramBlock(name = "Rest hang", workSec = 5, restSec = 5),
                        )
                    )
                ),
                candidates = emptyList(),
                categories = emptyList(),
                onSave = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.onNodeWithText("Remove the effort").assertDoesNotExist()
        compose.onNodeWithContentDescription("Actions for effort 2").performClick()
        // the menu is a popup that fades in, and this suite holds the clock still: it is
        // asserted as PRESENT, because "displayed" would be asking about an animation
        settle()
        compose.onNodeWithText("Remove the effort").assertExists()
    }

    /** The word: a line of a program is an EFFORT, an exercise is a catalog row. */
    @Test
    fun `a block of a program is called an effort`() {
        screen {
            ProgramEditorScreen(
                initial = repeaters,
                candidates = emptyList(),
                categories = emptyList(),
                onSave = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("Effort name").assertIsDisplayed()
        compose.onNodeWithText("Add an effort").assertIsDisplayed()
        compose.onNodeWithText("Add an exercise to this group").assertDoesNotExist()
    }

    /** The group carries its own total, computed by the same flatten the timer counts down. */
    @Test
    fun `a group says how long it runs`() {
        screen {
            ProgramEditorScreen(
                initial = repeaters,
                candidates = emptyList(),
                categories = emptyList(),
                onSave = {},
                onClose = {},
            )
        }

        // six hangs of 7 s with 3 s between them, the trailing pause dropped: 42 + 15 = 57
        compose.onNodeWithText("6 efforts · 0:57").assertIsDisplayed()
    }
}
