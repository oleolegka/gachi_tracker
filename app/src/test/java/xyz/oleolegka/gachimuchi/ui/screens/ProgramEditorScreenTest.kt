package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Test
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
 */
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
        compose.onNodeWithText("Get ready before the first effort: 15 s").assertIsDisplayed()
        compose.onNodeWithText(
            "Get ready before the first effort, seconds", substring = true,
        ).assertDoesNotExist()
        compose.onNodeWithText("Add a group").assertDoesNotExist()
        // the block itself is read text too
        compose.onNodeWithText("Hangs: 7s work, 3s rest, x6").assertIsDisplayed()

        // the name is still a field, and Save (the top bar's) still writes it
        compose.onNodeWithText("Program name").performTextReplacement("Hangs protocol (renamed)")
        compose.onAllNodesWithText("Save")[0].performClick()

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

        compose.onNodeWithText(
            "Get ready before the first effort, seconds", substring = true,
        ).assertIsDisplayed()
        // below the fold in the lazy list; bring it into existence before asserting on it
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add a group"))
        compose.onNodeWithText("Add a group").assertIsDisplayed()
    }
}
