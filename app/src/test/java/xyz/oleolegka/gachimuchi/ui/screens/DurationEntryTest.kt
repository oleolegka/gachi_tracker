package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.durationOf
import xyz.oleolegka.gachimuchi.domain.toPayload
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * The duration entry, typed as mm:ss (§13.9).
 *
 * It used to be a MINUTES field reaching a whole number of seconds only through a decimal
 * point — "0.5" was the only way to record thirty seconds, and there was no way at all to
 * step by less than a minute. This is the regression suite for the field that replaced it.
 */
class DurationEntryTest : ScreenTest() {

    private val plank = exerciseRef(1, "Plank", ExerciseForm.DURATION)
    private val iso = "2026-08-07"

    private val logged = mutableListOf<ActivityForm>()

    private fun show(events: List<JournalEvent> = emptyList()) {
        val state = UiState(events = events, loading = false)
        screen {
            DurationEntry(state = state, exercise = plank, opDate = iso, onAddSet = { logged += it })
        }
    }

    private fun durationEvent(id: Long, durationSec: Int, ts: String = "${iso}T09:00:00"): JournalEvent {
        val form = durationOf(plank, iso, durationSec = durationSec)
        return JournalEvent(id, ts, 1, 1, form.type, form.toPayload())
    }

    @Test
    fun `a fresh exercise offers an empty field, not a fraction of a minute to guess at`() {
        show()

        compose.onNodeWithText("Duration, mm:ss").assertIsDisplayed()
        compose.onNodeWithText("Add entry").assertIsDisplayed()
    }

    /** Thirty seconds is typed as "30", not as the decimal minute this field used to demand. */
    @Test
    fun `typing digits writes mm colon ss, seconds first`() {
        show()

        compose.onNodeWithText("Duration, mm:ss").performTextReplacement("30")
        compose.onNodeWithText("0:30").assertIsDisplayed()

        compose.onNodeWithText("Add entry").performClick()
        assertEquals(listOf(durationOf(plank, iso, durationSec = 30)), logged)
    }

    @Test
    fun `a minute and a half is typed digit by digit, not as a decimal`() {
        show()

        compose.onNodeWithText("Duration, mm:ss").performTextReplacement("130")
        compose.onNodeWithText("1:30").assertIsDisplayed()

        compose.onNodeWithText("Add entry").performClick()
        assertEquals(listOf(durationOf(plank, iso, durationSec = 90)), logged)
    }

    @Test
    fun `the last duration prefills the field, formatted, and offers to repeat it`() {
        show(listOf(durationEvent(1L, durationSec = 45)))

        compose.onNodeWithText("0:45").assertIsDisplayed()
        compose.onNodeWithText("Repeat entry").assertIsDisplayed()
    }

    @Test
    fun `the bump adds ten seconds to whatever is already there`() {
        show(listOf(durationEvent(1L, durationSec = 45)))

        compose.onNodeWithText("+10s").performClick()

        compose.onNodeWithText("0:55").assertIsDisplayed()
        // touched: no longer what was logged last, so this reads as a new entry
        compose.onNodeWithText("Add entry").assertIsDisplayed()
    }
}
