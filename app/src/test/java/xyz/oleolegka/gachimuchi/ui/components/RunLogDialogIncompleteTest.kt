package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The mark this dialog used to be missing entirely (see the type's own KDoc): falling off the
 * fourth of six hangs is a fact about the fourth, not about the session, so it has to be
 * settable ROW BY ROW — the same way the reps beside it already are — rather than once for the
 * whole offer. A separate class from [RunLogDialogTest] rather than one more test in it, purely
 * because this is the one scenario in the file that needs more than one live set on the offer.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class RunLogDialogIncompleteTest : ScreenTest() {

    private val hangs = exerciseRef(1, "Hangs", ExerciseForm.HOLD)

    private val outcome = RunOutcome(
        programName = "Hangs",
        origin = RunOrigin.EXERCISE,
        exerciseId = hangs.id,
        interrupted = false,
        sets = listOf(
            CompletedSet(setNumber = 1, reps = 6, plannedReps = 6, workSec = 7, restAfterSec = 183),
            CompletedSet(setNumber = 2, reps = 6, plannedReps = 6, workSec = 7, restAfterSec = 183),
            CompletedSet(setNumber = 3, reps = 6, plannedReps = 6, workSec = 7, restAfterSec = null),
        ),
        opDate = "2026-08-10",
    )

    private var logged: List<CompletedSet>? = null

    @Test
    fun `marking one row not completed writes only that set as incomplete`() {
        logged = null
        screen {
            RunLogDialog(
                outcome = outcome,
                exercise = hangs,
                candidates = listOf(hangs),
                lastAddedKg = { null },
                nowWallMs = 0L,
                onLog = { _, sets, _ -> logged = sets },
                onDismiss = {},
            )
        }

        // three rows, three checkboxes under one heading — the middle row's own one
        compose.onNodeWithContentDescription("Set 2 not completed").performClick()
        settle()
        compose.onNodeWithText("Write down 3 sets").performClick()
        settle()

        val written = logged
        checkNotNull(written)
        assertEquals(3, written.size)
        assertEquals(listOf(false, true, false), written.map { it.incomplete })
        // the mark did not disturb what it sits beside
        assertEquals(listOf(6, 6, 6), written.map { it.reps })
    }
}
