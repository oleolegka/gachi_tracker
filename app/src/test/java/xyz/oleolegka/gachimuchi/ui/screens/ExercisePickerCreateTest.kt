package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.protocolProgram
import xyz.oleolegka.gachimuchi.ui.protocolProgramIdFor
import java.time.LocalDate

/**
 * The CREATE half of the exercise picker — the sheet's other face, and the one the owner
 * reached for from the phone on 2026-08-11.
 *
 * Two of the four bugs reported that day were the same failure seen twice: a question that
 * exists in the app and is never put at the moment an exercise is described. "One side at a
 * time" lived only in the correction dialog of an exercise that already existed, so
 * one-sidedness — and the two workout cards, the rest floor per side and the record per side
 * hanging off it — was unreachable as an exercise was created. And a hold could only ever
 * invent its own protocol, never be pointed at a program already in the library.
 *
 * So every test here drives the real sheet and asserts on what it REPORTS, not on what it
 * stores: the gap both times was between the form and the callback, and a test that reached
 * past the form to the repository would have passed throughout.
 */
@Config(sdk = [34], qualifiers = "w600dp-h2400dp-xhdpi")
class ExercisePickerCreateTest : ScreenTest() {

    private val today = LocalDate.parse("2026-08-11")
    private var created: NewExercise? = null

    /** The sheet, opened straight onto the create form (as it is with an empty catalog). */
    private fun sheet(state: UiState = UiState(loading = false)) {
        screen {
            ExercisePickerSheet(
                state = state,
                today = today,
                onPick = {},
                onCreate = { created = it },
                onDismiss = {},
                startInCreate = true,
            )
        }
        settle()
        settle()
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performClick()
        settle()
    }

    private fun name(it: String) {
        compose.onNodeWithText("Name").performTextReplacement(it)
        settle()
    }

    @Test
    fun `creating a strength exercise asks whether it is one side at a time`() {
        sheet()
        name("One-arm row")

        tap("One side at a time")
        compose.onNodeWithText("One side at a time").assertIsSelected()
        tap("Create and use")

        assertEquals("One-arm row", created?.name)
        assertTrue("the answer given on the create form has to reach the caller", created!!.oneSided)
    }

    /** Left alone, the question answers itself the ordinary way. */
    @Test
    fun `an exercise created without touching the switch is two-sided`() {
        sheet()
        name("Bench press")
        tap("Create and use")

        assertEquals(false, created?.oneSided)
    }

    @Test
    fun `a hold is asked the same question`() {
        sheet()
        name("One-arm hang")
        tap(ExerciseForm.HOLD.title)

        tap("One side at a time")
        tap("Create and use")

        assertEquals(ExerciseForm.HOLD, created?.form)
        assertTrue(created!!.oneSided)
    }

    /**
     * A run and a weigh-in have no sides, and the same gate the edit dialog uses applies
     * here: the switch is absent rather than present and meaningless.
     */
    @Test
    fun `a form with no sides is not asked about them`() {
        sheet()
        name("Treadmill")
        tap(ExerciseForm.CARDIO.title)

        compose.onNodeWithText("One side at a time").assertDoesNotExist()
    }

    /** Switching away from a side-having form drops an answer that no longer means anything. */
    @Test
    fun `an answer given and then made meaningless does not travel`() {
        sheet()
        name("Treadmill")
        tap("One side at a time")
        tap(ExerciseForm.CARDIO.title)
        tap("Create and use")

        assertEquals(false, created?.oneSided)
    }

    // --- the protocol, and where it comes from ------------------------------------------

    private fun library() = UiState(
        loading = false,
        programsById = mapOf(
            protocolProgramIdFor(7) to protocolProgram(7, "Fingerboard", 7.0, 3.0),
        ),
    )

    @Test
    fun `a hold can be led by a program already in the library`() {
        sheet(library())
        name("Hangs")
        tap(ExerciseForm.HOLD.title)

        // the chip names the program and the protocol it carries
        tap("Fingerboard protocol - 7:3")
        tap("Create and use")

        assertEquals(protocolProgramIdFor(7), created?.protocolProgramId)
        assertNull("a picked program IS the protocol - nothing is invented beside it", created?.workSec)
        assertNull(created?.restSec)
    }

    /**
     * The library is an ALTERNATIVE to typing, not a replacement: "New" is the default and
     * the two numbers still describe a protocol of their own.
     */
    @Test
    fun `typing the two numbers still works with a library present`() {
        sheet(library())
        name("Hangs")
        tap(ExerciseForm.HOLD.title)

        compose.onNodeWithText("Work, s").performTextReplacement("10")
        compose.onNodeWithText("Rest, s").performTextReplacement("5")
        settle()
        tap("Create and use")

        assertNull(created?.protocolProgramId)
        assertEquals(10.0, created?.workSec)
        assertEquals(5.0, created?.restSec)
    }

    /** With nothing to choose from, the question is not put at all — just the two numbers. */
    @Test
    fun `an empty library offers no programs to be led by`() {
        sheet()
        name("Hangs")
        tap(ExerciseForm.HOLD.title)

        compose.onNodeWithText("Protocol").assertDoesNotExist()
        assertNotNull(compose.onNodeWithText("Work, s").fetchSemanticsNode())
    }
}
