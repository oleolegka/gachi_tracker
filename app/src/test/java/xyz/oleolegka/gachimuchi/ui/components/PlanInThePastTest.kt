package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import java.time.LocalDate

/**
 * "A session can still be planned in the past", reported from the phone 2026-08-11 against a
 * build that already carried the fix for it.
 *
 * The gate is `SlotDraft.isBackdated`, consulted by this dialog and by nothing else, so what
 * these pin is the dialog: which day it is opened on, which day the draft ends up anchored to,
 * and whether the button that writes it is reachable.
 */
@Config(sdk = [34], qualifiers = "w600dp-h2400dp-xhdpi")
class PlanInThePastTest : ScreenTest() {

    private val today = LocalDate.parse("2026-08-11")
    private val state = UiState(exercises = listOf(exerciseEntity(1, "Bench press")), loading = false)

    private var saved: SlotDraft? = null

    private fun editor(initial: Slot? = null, day: LocalDate) {
        screen {
            SlotEditorDialog(
                initial = initial,
                day = day,
                suggestions = emptyList(),
                today = today,
                state = state,
                onSave = { saved = it },
                onDelete = {},
                onDismiss = {},
            )
        }
    }

    private fun name(text: String) {
        compose.onNodeWithText("Session name").performTextInput(text)
        settle()
    }

    @Test
    fun `a new plan on a day already gone cannot be written`() {
        editor(day = today.minusDays(3))
        name("Gym")

        compose.onNodeWithText("Add to the plan").assertIsNotEnabled()
        assertNull(saved)
    }

    @Test
    fun `a new plan on today can be written`() {
        editor(day = today)
        name("Gym")

        compose.onNodeWithText("Add to the plan").assertIsEnabled()
    }

    /**
     * The other half of the same gate, and the one that is not obviously wanted: a repeating
     * session set up weeks ago is anchored weeks ago, so opening it to change its NAME finds
     * the editor refusing to save a draft that was never backdated by the user at all.
     */
    @Test
    fun `an existing weekly session anchored before today can still be edited`() {
        val gym = Slot(
            id = 1,
            name = "Gym",
            atTime = "18:00",
            repeatRule = REPEAT_WEEKLY,
            anchorDate = today.minusDays(21).toString(),
        )
        editor(initial = gym, day = today)

        compose.onNodeWithText("Save").assertIsEnabled()
    }

    /**
     * The floor is on the act, not on the value: an anchor that has not moved is history, but
     * stepping it further back is putting the plan somewhere new and is refused just as a
     * fresh plan on that day would be.
     */
    @Test
    fun `an existing session cannot have its date stepped back before today`() {
        val gym = Slot(
            id = 1,
            name = "Gym",
            atTime = null,
            repeatRule = REPEAT_WEEKLY,
            anchorDate = today.minusDays(21).toString(),
        )
        editor(initial = gym, day = today)

        compose.onNodeWithContentDescription("A day earlier").performClick()
        settle()

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Plans can only be made for today or later", substring = true)
            .assertExists()
    }
}
