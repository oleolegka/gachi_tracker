package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.CompletedSet
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.RunOutcome
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * What the offer SHOWS for a run that was skipped through, now that completion is judged by
 * fact rather than by position (§18.20).
 *
 * The numbers themselves are settled in domain/RunLogTest; what is checked here is the thing
 * the owner actually sees, because a domain that no screen reads is the failure this project
 * has had three times. Two questions: does a set that lost efforts to the Skip button arrive
 * on the form reading "4 of 6" instead of "6 of 6", and does the form SAY why it is short.
 *
 * The second half is not decoration. A count lower than the schedule looks like the app
 * having lost track, and the one move it invites is the "+" button — putting the overstated
 * number back by hand, one tap at a time, which is exactly the number this whole change
 * removed.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class RunLogDialogSkippedTest : ScreenTest() {

    private val skipHangs = exerciseRef(11, "Skip-run hangs", ExerciseForm.HOLD)

    private fun outcomeOf(vararg reps: Int) = RunOutcome(
        programName = "Skip-run hangs",
        origin = RunOrigin.EXERCISE,
        exerciseId = skipHangs.id,
        interrupted = false,
        sets = reps.mapIndexed { index, held ->
            CompletedSet(
                setNumber = index + 1,
                reps = held,
                plannedReps = 6,
                workSec = 7,
                restAfterSec = if (index < reps.lastIndex) 180 else null,
            )
        },
        opDate = "2026-08-11",
    )

    private var logged: List<CompletedSet>? = null

    private fun dialog(outcome: RunOutcome) {
        logged = null
        screen {
            RunLogDialog(
                outcome = outcome,
                exercise = skipHangs,
                candidates = listOf(skipHangs),
                lastAddedKg = { null },
                nowWallMs = 0L,
                onLog = { _, sets, _ -> logged = sets },
                onDismiss = {},
            )
        }
    }

    @Test
    fun `a set that lost two hangs to the skip button arrives on the form as four of six`() {
        dialog(outcomeOf(6, 4, 6))

        // the middle row is the one that lost efforts; "4" appears nowhere else on the form
        compose.onNodeWithText("4").assertIsDisplayed()
    }

    @Test
    fun `a short set is explained as skipped rather than left looking like a miscount`() {
        dialog(outcomeOf(6, 4, 6))

        compose.onNodeWithText(
            "What was skipped is not counted in them.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `a run that went exactly to plan says nothing about skipping`() {
        dialog(outcomeOf(6, 6, 6))

        compose.onNodeWithText("skipped", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the short count is what gets written, not the plan it fell short of`() {
        dialog(outcomeOf(6, 4, 6))
        compose.onNodeWithText("Write down 3 sets").performClick()
        settle()

        assertEquals(listOf(6, 4, 6), logged?.map { it.reps })
    }
}
