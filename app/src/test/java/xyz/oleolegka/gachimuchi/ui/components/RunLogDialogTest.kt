package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * The offer after a run: whether a negative added weight - a band taking load off, not a
 * plate hanging on - survives being typed into "Log this run?" and reaches `onLog`, or is
 * silently thrown away the way it used to be.
 *
 * `HoldSet.addedKg` IS SIGNED (domain/Forms.kt): a hang taken down to zero on a band is
 * ordinary training, and it is the negative half of the axis most fingerboard work actually
 * happens on. The dialog's own weight field used to keep only `it > 0`, which is the filter
 * that belongs to a set's REP count (a rep count of zero is not a set at all) and not to a
 * signed weight, where zero is the only value that means "none". A hang on a band that took
 * fifteen kilograms off used to be logged as though nothing had been hung at all - lighter on
 * paper than the same hang with nothing on the bar, and indistinguishable from it in every
 * record and every volume total downstream.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class RunLogDialogTest : ScreenTest() {

    private val hangs = exerciseRef(1, "Hangs", ExerciseForm.HOLD)

    private val outcome = RunOutcome(
        programName = "Hangs",
        origin = RunOrigin.EXERCISE,
        exerciseId = hangs.id,
        interrupted = false,
        sets = listOf(CompletedSet(setNumber = 1, reps = 3, plannedReps = 3, workSec = 7, restAfterSec = null)),
        opDate = "2026-08-10",
    )

    private var logged: Triple<ExerciseRef, List<CompletedSet>, Double?>? = null

    /**
     * [prefill] gives the weight field a starting value through [RunLogDialog.lastAddedKg] -
     * the same route the app itself fills it in from. [typeWeight] needs this: the field's
     * label ("Added weight, kg (empty for none)") sits on a plain `Text` *beside* it
     * (`StepperField`), not on the `OutlinedTextField` itself, so the only node that is
     * actually the editable field is the one carrying its CURRENT VALUE as text - the same
     * workaround `WorkoutEntryEditingTest` already uses for a `StepperField`, of which this
     * is one more.
     */
    private fun dialog(prefill: Double? = 42.0) {
        logged = null
        screen {
            RunLogDialog(
                outcome = outcome,
                exercise = hangs,
                candidates = listOf(hangs),
                lastAddedKg = { prefill },
                nowWallMs = 0L,
                onLog = { ex, sets, kg -> logged = Triple(ex, sets, kg) },
                onDismiss = {},
            )
        }
    }

    private fun typeWeight(text: String) {
        compose.onNodeWithText("42").performTextReplacement(text)
        settle()
    }

    private fun tapLog() {
        compose.onNodeWithText("Write down 1 set").performClick()
        settle()
    }

    @Test
    fun `a negative added weight - a band taking load off - reaches onLog unchanged`() {
        dialog()
        typeWeight("-15")
        tapLog()

        assertEquals(-15.0, logged?.third)
    }

    @Test
    fun `a positive added weight still reaches onLog, as before`() {
        dialog()
        typeWeight("20")
        tapLog()

        assertEquals(20.0, logged?.third)
    }

    @Test
    fun `an empty weight field logs no weight at all`() {
        dialog(prefill = null)
        tapLog()

        assertNull(logged?.third)
    }

    /** Zero is the one value a signed weight cannot mean anything other than "no weight". */
    @Test
    fun `a weight typed as zero is logged as no weight, not as zero kg`() {
        dialog()
        typeWeight("0")
        tapLog()

        assertNull(logged?.third)
    }
}
