package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.CompletedSet
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.RunOutcome
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * The two things wrong with the offer's set row, as the owner met them (§18.15).
 *
 * THE CEILING. "+" was capped at a global constant of 99 rather than at what the schedule had
 * planned. His words: the schedule is strict, he could have done fewer, but more he could not
 * — the run counted down a fixed number of efforts and ended. So the ceiling is
 * [CompletedSet.plannedReps], and it is visible: at the top the button is disabled rather than
 * quietly eating the tap.
 *
 * THE NAME. The row said "Set 1", a bare number and "of 6", and nowhere did it say that the
 * number counts EFFORTS HELD inside that set. The heading above the rows now does.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class RunLogDialogCeilingTest : ScreenTest() {

    private val hangs = exerciseRef(1, "Hangs", ExerciseForm.HOLD)

    private fun outcomeOf(reps: Int, planned: Int) = RunOutcome(
        programName = "Hangs",
        origin = RunOrigin.EXERCISE,
        exerciseId = hangs.id,
        interrupted = true,
        sets = listOf(
            CompletedSet(setNumber = 1, reps = reps, plannedReps = planned, workSec = 7, restAfterSec = null),
        ),
        opDate = "2026-08-10",
    )

    private var logged: List<CompletedSet>? = null

    private fun dialog(reps: Int, planned: Int) {
        logged = null
        screen {
            RunLogDialog(
                outcome = outcomeOf(reps, planned),
                exercise = hangs,
                candidates = listOf(hangs),
                lastAddedKg = { null },
                nowWallMs = 0L,
                onLog = { _, sets, _ -> logged = sets },
                onDismiss = {},
            )
        }
    }

    @Test
    fun `plus stops at what the schedule planned and cannot go past it`() {
        dialog(reps = 4, planned = 6)

        // two taps reach the planned six; the next three must change nothing at all
        repeat(5) {
            val plus = compose.onAllNodesWithText("+")
            if (plus.fetchSemanticsNodes().isNotEmpty()) plus[0].performClick()
            settle()
        }

        compose.onNodeWithText("Log 1 set").performClick()
        settle()
        assertEquals(listOf(6), logged?.map { it.reps })
    }

    @Test
    fun `at the planned count the plus button is visibly disabled`() {
        dialog(reps = 6, planned = 6)
        compose.onNodeWithText("+").assertIsNotEnabled()
    }

    @Test
    fun `the row's number is named as efforts held, and the planned count says so too`() {
        dialog(reps = 4, planned = 6)
        compose.onNodeWithText("Efforts held in each set").assertExists()
        compose.onNodeWithText("of 6 planned").assertExists()
    }
}
