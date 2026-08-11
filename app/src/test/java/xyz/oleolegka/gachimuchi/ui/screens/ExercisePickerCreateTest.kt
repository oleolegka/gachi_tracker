package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
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
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.ScheduleKind
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.totalSec
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
        tap(ScheduleKind.FREE.title)
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

    // --- the three branches of a hold (§18.15) ------------------------------------------

    /**
     * A schedule that is genuinely STRICT: six hangs of 7:3, four sets, three minutes between
     * them. It has to be this rather than a bare pair, because the strict branch only offers
     * what the classifier calls strict — see `protocolCandidates`.
     */
    private val repeaters = WorkoutProgram(
        id = protocolProgramIdFor(7),
        name = "Fingerboard repeaters",
        groups = listOf(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock(name = "Hang", workSec = 7, restSec = 3, repeats = 6)),
                repeats = 4,
                restBetweenRepeatsSec = 180,
            )
        ),
    )

    /**
     * The library as the strict branch sees it: one real schedule, and beside it a program of
     * the plain-pair shape that must never be offered as a strict one.
     */
    private fun library() = UiState(
        loading = false,
        programsById = mapOf(
            repeaters.id to repeaters,
            protocolProgramIdFor(9) to protocolProgram(9, "Hand-written pair", 7.0, 3.0),
        ),
    )

    /** The question is put at all — three cards, each with a sentence, and none preselected. */
    @Test
    fun `a hold is offered the three branches and starts on none of them`() {
        sheet()
        name("Hangs")
        tap(ExerciseForm.HOLD.title)

        ScheduleKind.entries.forEach {
            compose.onNodeWithText(it.title).assertIsNotSelected()
        }
    }

    /**
     * The branch is what says whether the numbers are asked, so it has to be answered before
     * the exercise can be made. Unanswered it is not a message on Save — the button that
     * starts the thing is the thing that is off.
     */
    @Test
    fun `a hold cannot be created until it says which of the three it is`() {
        sheet()
        name("Hangs")
        tap(ExerciseForm.HOLD.title)

        compose.onNodeWithText("Create and use").assertIsNotEnabled()
        tap(ScheduleKind.FREE.title)
        compose.onNodeWithText("Create and use").assertIsEnabled()
    }

    /** Free: no schedule, nothing asked, nothing invented. */
    @Test
    fun `the free branch creates a hold with no schedule at all`() {
        sheet(library())
        name("Hangs")
        tap(ExerciseForm.HOLD.title)
        tap(ScheduleKind.FREE.title)

        // the branch chosen decides what else is on screen
        compose.onNodeWithText("Work, s").assertDoesNotExist()
        tap("Create and use")

        assertNull(created?.workSec)
        assertNull(created?.restSec)
        assertNull(created?.protocolProgramId)
        assertNull(created?.newProgram)
    }

    /** The simple pair is exactly what it always was: two numbers, and nothing beside them. */
    @Test
    fun `the simple pair branch still asks for the two numbers`() {
        sheet(library())
        name("Hangs")
        tap(ExerciseForm.HOLD.title)
        tap(ScheduleKind.SIMPLE_PAIR.title)

        compose.onNodeWithText("Work, s").performTextReplacement("10")
        compose.onNodeWithText("Rest, s").performTextReplacement("5")
        settle()
        tap("Create and use")

        assertEquals(10.0, created?.workSec)
        assertEquals(5.0, created?.restSec)
        assertNull(created?.protocolProgramId)
        assertNull(created?.newProgram)
    }

    /**
     * Half a pair is not a pair, and an empty one is not a free hold: the branch was named on
     * purpose, so the form has to be finished rather than silently downgraded to the branch
     * beside it. §18.9 makes that downgrade permanent, which is why it is refused at the
     * button rather than tidied up afterwards.
     */
    @Test
    fun `the simple pair branch refuses to be created half filled`() {
        sheet()
        name("Hangs")
        tap(ExerciseForm.HOLD.title)
        tap(ScheduleKind.SIMPLE_PAIR.title)

        compose.onNodeWithText("Create and use").assertIsNotEnabled()
        compose.onNodeWithText("Work, s").performTextReplacement("10")
        settle()
        compose.onNodeWithText("Create and use").assertIsNotEnabled()
        compose.onNodeWithText("Rest, s").performTextReplacement("5")
        settle()
        compose.onNodeWithText("Create and use").assertIsEnabled()
    }

    /**
     * The twins case, and the reason library picking exists at all: "hang 20 mm" and
     * "hang 15 mm" share one schedule deliberately (§18.15).
     */
    @Test
    fun `the strict branch can take a schedule already in the library`() {
        sheet(library())
        name("Hangs 20 mm")
        tap(ExerciseForm.HOLD.title)
        tap(ScheduleKind.STRICT.title)

        // the row names the schedule and says what is in it
        tap("Fingerboard repeaters")
        tap("Create and use")

        assertEquals(protocolProgramIdFor(7), created?.protocolProgramId)
        assertNull("a chosen schedule IS the schedule - nothing is invented beside it", created?.workSec)
        assertNull(created?.restSec)
        assertNull(created?.newProgram)
    }

    /**
     * The strict card promises that nothing is asked before a run but the weight. A library
     * program shaped like a plain pair — one group, one block, no repeats — does not keep that
     * promise: an exercise pointed at one classifies as [ScheduleKind.SIMPLE_PAIR] and is asked
     * how many holds and how many sets before every run. Offering it here would create an
     * exercise in a branch it was not created in, permanently (§18.9), so it is not offered.
     */
    @Test
    fun `a plain pair in the library is not offered as a strict schedule`() {
        sheet(library())
        name("Hangs")
        tap(ExerciseForm.HOLD.title)
        tap(ScheduleKind.STRICT.title)

        compose.onNodeWithText("Fingerboard repeaters").assertExists()
        compose.onNodeWithText("Hand-written pair protocol").assertDoesNotExist()
    }

    /** Nothing chosen in the strict branch is nothing to create with. */
    @Test
    fun `the strict branch refuses to be created with no schedule chosen`() {
        sheet(library())
        name("Hangs")
        tap(ExerciseForm.HOLD.title)
        tap(ScheduleKind.STRICT.title)

        compose.onNodeWithText("Create and use").assertIsNotEnabled()
    }

    /**
     * The whole of the strict branch: the library editor, opened in a dialog over the sheet,
     * hands a complete schedule back as a value. Asserted on what the FORM reports, because
     * a schedule built and then dropped on the way to the caller is precisely the failure
     * this project keeps producing.
     */
    @Test
    fun `a schedule built in the editor travels to the caller`() {
        sheet()
        name("Repeaters")
        tap(ExerciseForm.HOLD.title)
        tap(ScheduleKind.STRICT.title)

        tap("Build a schedule")
        // the editor arrived, wearing the create form's vocabulary rather than the library's
        compose.onNodeWithText("Schedule name").assertExists()
        tap("Use this schedule")

        tap("Create and use")

        val built = created?.newProgram
        assertNotNull("the built schedule has to reach the caller", built)
        assertEquals("Repeaters schedule", built!!.name)
        assertTrue("it has to be a real schedule, not an empty one", built.totalSec() > 0)
        assertNull("a built schedule is not one of the library's yet", created?.protocolProgramId)
    }

    /** With an empty library there is nothing to take, so only building is offered. */
    @Test
    fun `an empty library offers nothing to take off the shelf`() {
        sheet()
        name("Hangs")
        tap(ExerciseForm.HOLD.title)
        tap(ScheduleKind.STRICT.title)

        compose.onNodeWithText("Build a schedule").assertExists()
        compose.onNodeWithText("Fingerboard repeaters").assertDoesNotExist()
    }

    /**
     * Switching branches must not leave the previous branch's answer travelling underneath:
     * a pair typed and then abandoned for the free branch would otherwise create a hold that
     * counts time, permanently, having been told not to.
     */
    @Test
    fun `numbers typed in one branch do not travel out of another`() {
        sheet()
        name("Hangs")
        tap(ExerciseForm.HOLD.title)
        tap(ScheduleKind.SIMPLE_PAIR.title)
        compose.onNodeWithText("Work, s").performTextReplacement("10")
        compose.onNodeWithText("Rest, s").performTextReplacement("5")
        settle()

        tap(ScheduleKind.FREE.title)
        tap("Create and use")

        assertNull(created?.workSec)
        assertNull(created?.restSec)
    }

    /** A form with no schedule at all is never asked about one. */
    @Test
    fun `a strength exercise is not asked which schedule it is`() {
        sheet(library())
        name("Bench press")

        ScheduleKind.entries.forEach {
            compose.onNodeWithText(it.title).assertDoesNotExist()
        }
    }
}
