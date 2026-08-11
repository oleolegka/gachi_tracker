package xyz.oleolegka.gachimuchi.ui.components

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
 * The offer after a run of a schedule whose efforts are NOT all the same length — "10 s on the
 * 20 mm, then 7 s on the 15 mm", which §18.15's strict branch made reachable.
 *
 * The journal keeps one length per row (`domain/RunLog.kt` explains the three ways out of that
 * and why a row per length won), so such a set arrives here already cut in two. This class is
 * the other half of that decision: the cut has to be VISIBLE before it is confirmed. Eight rows
 * for four sets of the schedule, with nothing on screen saying what makes them eight, is the
 * same silence the split was made to end — the user would meet it in the history a week later
 * instead of in the dialog.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class RunLogDialogMixedLengthsTest : ScreenTest() {

    private val edges = exerciseRef(4, "Two edges", ExerciseForm.HOLD)

    /** What `completedSets` hands over for two sets of "three of 10 s, then three of 7 s". */
    private val outcome = RunOutcome(
        programName = "Two edges",
        origin = RunOrigin.EXERCISE,
        exerciseId = edges.id,
        interrupted = false,
        sets = listOf(
            CompletedSet(setNumber = 1, reps = 3, plannedReps = 3, workSec = 10, restAfterSec = 5),
            CompletedSet(setNumber = 2, reps = 3, plannedReps = 3, workSec = 7, restAfterSec = 125),
            CompletedSet(setNumber = 3, reps = 3, plannedReps = 3, workSec = 10, restAfterSec = 5),
            CompletedSet(setNumber = 4, reps = 3, plannedReps = 3, workSec = 7, restAfterSec = null),
        ),
        opDate = "2026-08-11",
    )

    private var logged: Triple<ExerciseRef, List<CompletedSet>, Double?>? = null

    private fun dialog() {
        logged = null
        screen {
            RunLogDialog(
                outcome = outcome,
                exercise = edges,
                candidates = listOf(edges),
                lastAddedKg = { null },
                nowWallMs = 0L,
                onLog = { ex, sets, kg -> logged = Triple(ex, sets, kg) },
                onDismiss = {},
            )
        }
    }

    /** Every row says how long its efforts were, because the row beside it says otherwise. */
    @Test
    fun `each row states its own length when the rows disagree`() {
        dialog()

        compose.onNodeWithText("Set 1 - 10 s").assertExists()
        compose.onNodeWithText("Set 2 - 7 s").assertExists()
        compose.onNodeWithText("Set 3 - 10 s").assertExists()
        compose.onNodeWithText("Set 4 - 7 s").assertExists()
    }

    /** And the summary line does not state the first length for all of them either. */
    @Test
    fun `the summary names both lengths`() {
        dialog()

        compose.onNodeWithText("3 x 10 s + 3 x 7 s + 3 x 10 s + 3 x 7 s").assertExists()
    }

    /**
     * The pause block used to name the FIRST pause it found, which here is the five seconds
     * inside a set — the smallest gap in the run, presented as the rest between sets while the
     * two-minute one went unmentioned.
     */
    @Test
    fun `both pauses the program counted are named`() {
        dialog()

        compose.onNodeWithText("Rests between sets 0:05, 2:05, counted by the program").assertExists()
    }

    /** What is written is what the rows say: four sets, at the two lengths, not one. */
    @Test
    fun `the write carries both lengths through`() {
        dialog()

        compose.onNodeWithText("Write down 4 sets").performClick()
        settle()

        assertEquals(listOf(10, 7, 10, 7), logged?.second?.map { it.workSec })
    }
}
