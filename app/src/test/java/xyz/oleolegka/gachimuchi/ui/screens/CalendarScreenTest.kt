package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.DayActions
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.exerciseRef
import xyz.oleolegka.gachimuchi.ui.fmtMonth
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.slot
import java.time.LocalDate

/**
 * The calendar: a month navigator over the day picked underneath it.
 *
 * ── Why "today" is the real today ───────────────────────────────────────────────
 * The screen reads `LocalDateTime.now()` itself to decide each slot's verdict (a session at
 * 20:00 is still outstanding at noon and late by midnight). Handing it a pinned date while
 * the clock says something else would make the tests describe a day the app is not in, so
 * they use the real one — the same choice `data/WorkoutFlowTest.kt` makes and for the same
 * reason.
 *
 * Measured on a wide window for the same reason [xyz.oleolegka.gachimuchi.ui.components.SlotEditorTest]
 * is: half of these tests open the slot editor, and a text field inside a dialog does not let
 * the composition settle at a phone width. The assertions are text and callbacks, which the
 * window size does not change.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class CalendarScreenTest : ScreenTest() {

    private val today: LocalDate = LocalDate.now()
    private val bench = exerciseRef(1, "Bench press")

    private var savedDraft: SlotDraft? = null
    private var savedId: Long? = null
    private var deletedSlot: Long? = null
    private var startedFromPlan: Pair<Long, LocalDate>? = null

    private val noActions = DayActions(
        startFromPlan = { id, date -> startedFromPlan = id to date },
        startWorkout = { _, _ -> },
        logSingleEntry = {},
        continueWorkout = {},
        openWorkout = {},
        openExercise = { _, _ -> },
        deleteWorkout = {},
        deleteSingleEntries = { _, _ -> },
        renameWorkout = { _, _ -> },
        resumeDraft = {},
        discardDraft = {},
    )

    private fun calendar(slots: List<Slot> = emptyList(), journal: Journal = Journal()) {
        val state = UiState(
            events = journal.events,
            exercises = listOf(exerciseEntity(1, "Bench press")),
            slots = slots,
            loading = false,
        )
        screen {
            CalendarScreen(
                state = state,
                today = today,
                dayActions = noActions,
                onSaveSlot = { draft, id ->
                    savedDraft = draft
                    savedId = id
                },
                onDeleteSlot = { deletedSlot = it },
            )
        }
    }

    // --- the grid --------------------------------------------------------------------------

    @Test
    fun `the grid opens on this month, with its weekday headers and its legend`() {
        calendar()

        compose.onNodeWithText(fmtMonth(today)).assertIsDisplayed()
        compose.onNodeWithText("MON").assertIsDisplayed()
        compose.onNodeWithText("SUN").assertIsDisplayed()
        // the cell carries no verdict any more (§12-B rework) — only the dots do, and the
        // legend names their three colours in words, because none of them is legible as
        // colour alone
        compose.onNodeWithText("done").assertIsDisplayed()
        compose.onNodeWithText("missed").assertIsDisplayed()
        compose.onNodeWithText("planned").assertIsDisplayed()
        compose.onNodeWithText("A dot is a whole session or entry. Up to six a day; +N is the rest.")
            .assertIsDisplayed()
    }

    // --- the dots (§12-B rework, 2026-08-10) ------------------------------------------------
    //
    // A dot has nothing visible to query a colour by, so each one carries a day-qualified
    // content description (see ui/screens/CalendarScreen.kt's DotRow) — "$day dot: done" and
    // so on. That is what every test below reads.

    @Test
    fun `two sessions on one day with different outcomes give two dots of different colour`() {
        // the case a single per-day wash could never answer: one slot done, the other missed.
        // Three days back so both windows are certainly closed by the real clock either way
        val day = today.minusDays(3)
        val journal = Journal()
        val workout = journal.startWorkout(day.toString(), at = "08:05")
        journal.addExercise(workout, day.toString(), bench, restSec = 150)
        journal.strengthSet(bench, day.toString(), at = "08:10", workoutId = workout)
        journal.finishWorkout(workout, day.toString(), at = "08:20")

        calendar(
            slots = listOf(
                slot(1, "Gym", "08:00", day.toString()),      // closed by the 08:10 set
                slot(2, "Hangboard", "20:00", day.toString()), // nothing recorded near it
            ),
            journal = journal,
        )

        compose.onNodeWithContentDescription("$day dot: done").assertExists()
        compose.onNodeWithContentDescription("$day dot: missed").assertExists()
    }

    @Test
    fun `a seventh activity on one day is counted, not dropped silently`() {
        // seven distinct exercises logged outside any workout is seven instances -- the old
        // rule threw away everything past three dots without saying so; this one counts it
        val day = today.minusDays(2)
        val journal = Journal()
        (1..7).forEach { id -> journal.strengthSet(exerciseRef(id.toLong(), "Exercise $id"), day.toString(), at = "0$id:00") }

        calendar(journal = journal)

        // the day cell is clickable, which merges every descendant's semantics into ONE
        // node by default (its content description becomes a list of all seven strings) --
        // querying by COUNT needs the raw, unmerged tree to see the dots as separate nodes
        compose.onAllNodesWithContentDescription("$day dot: done", useUnmergedTree = true).assertCountEquals(6)
        compose.onNodeWithContentDescription("$day: 1 more", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a planned dot still exists on today's cell, whose selected fill is the same blue`() {
        // today is selected by default, and its fill is `accent` -- numerically the same
        // blue a PLANNED dot draws. An UNTIMED plan stays PLANNED for the whole day whatever
        // the real clock says right now, which is what keeps this test independent of when
        // it happens to run. What this cannot show is the ring actually being drawn on top --
        // nothing here is rasterised (see ScreenTest) -- only that the dot itself is still in
        // the tree rather than having been left out to avoid the collision
        calendar(slots = listOf(slot(1, "Walk", null, today.toString())))

        compose.onNodeWithContentDescription("$today dot: planned").assertExists()
    }

    @Test
    fun `the arrows move the month, and back again`() {
        calendar()

        compose.onNodeWithContentDescription("Next month").performClick()

        settle()
        compose.onNodeWithText(fmtMonth(today.plusMonths(1))).assertIsDisplayed()

        compose.onNodeWithContentDescription("Previous month").performClick()

        settle()
        compose.onNodeWithContentDescription("Previous month").performClick()
        settle()
        compose.onNodeWithText(fmtMonth(today.minusMonths(1))).assertIsDisplayed()
    }

    @Test
    fun `the day underneath opens on today and is the same list the Today tab draws`() {
        calendar()

        compose.onNodeWithText("SELECTED DAY").assertExists()
        compose.onNodeWithText(fmtWeekdayDay(today)).assertExists()
        compose.onNodeWithText(
            "Nothing planned and nothing recorded. Start a workout below, or log a single entry."
        ).assertExists()
    }

    @Test
    fun `a planned session on the selected day is drawn with the calendar's own pencil and bin`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithText("Gym").assertExists()
        // Today leaves these two out; only the calendar passes them in
        compose.onNodeWithContentDescription("Edit \"Gym\"").assertExists()
        compose.onNodeWithContentDescription("Delete \"Gym\"").assertExists()
    }

    // --- planning, editing, deleting ----------------------------------------------------------

    /**
     * Selects a day that is certainly gone, and returns it.
     *
     * The 15th and nothing else: a month grid shows the neighbouring months only as the few
     * days that top and tail it, so the 15th of the month on screen is the one cell in the
     * whole grid carrying that number — every other choice can collide with a day of the
     * month next door and select a day the test did not mean. When today has not reached the
     * 15th yet, the same cell one month back is used instead.
     */
    private fun selectAPastDay(): LocalDate {
        val past = if (today.dayOfMonth > 15) {
            today.withDayOfMonth(15)
        } else {
            compose.onNodeWithContentDescription("Previous month").performClick()
            settle()
            today.minusMonths(1).withDayOfMonth(15)
        }
        compose.onNodeWithText("15").performClick()
        settle()
        // the agenda heading is the proof the intended day is the selected one
        compose.onNodeWithText(fmtWeekdayDay(past)).assertExists()
        return past
    }

    /*
     * Reported from the phone against 0.6.0, in the owner's words: "I am right now on 0.6
     * able to pick 6 August and there are two buttons: add and plan a session. For the
     * future only plan a session, which is right."
     *
     * The gate existed and was in the wrong place — inside the dialog, on Save — so the
     * button opened a form that could be filled in and could not be written. What the owner
     * reads is the SCREEN, and the screen said the past could be planned.
     */
    @Test
    fun `a day already gone is not offered a plan`() {
        calendar()

        selectAPastDay()

        compose.onNodeWithText("Plan a session").assertDoesNotExist()
    }

    @Test
    fun `today and the days ahead are still offered one`() {
        calendar()

        // today, where the screen opens
        compose.onNodeWithText("Plan a session").assertExists()

        // and a day ahead: the 15th of next month is past no reading of the calendar
        compose.onNodeWithContentDescription("Next month").performClick()
        settle()
        compose.onNodeWithText("15").performClick()
        settle()
        compose.onNodeWithText(fmtWeekdayDay(today.plusMonths(1).withDayOfMonth(15))).assertExists()
        compose.onNodeWithText("Plan a session").assertExists()
    }

    @Test
    fun `Plan a session opens the editor on the day that is selected`() {
        calendar()

        compose.onNodeWithText("Plan a session").performScrollTo().performClick()

        settle()

        compose.onNodeWithText("Session name").assertIsDisplayed()
        compose.onNodeWithText("Add to the plan").assertIsDisplayed()
        // the day is printed twice now: once as the agenda's own heading, once in the
        // dialog's date field. The button never means "some other day"
        compose.onAllNodesWithText(fmtWeekdayDay(today)).assertCountEquals(2)
    }

    @Test
    fun `a session planned in the dialog is handed to the caller as a new slot`() {
        calendar()

        compose.onNodeWithText("Plan a session").performScrollTo().performClick()

        settle()
        compose.onNodeWithText("Session name").performTextInput("Hangboard")
        settle()
        compose.onNodeWithText("Add to the plan").performClick()
        settle()

        assertEquals("Hangboard", savedDraft?.name)
        assertNull("a slot being created has no id yet", savedId)
        // and the dialog is gone
        compose.onNodeWithText("Session name").assertDoesNotExist()
    }

    @Test
    fun `the pencil opens the editor on the session it belongs to`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithContentDescription("Edit \"Gym\"").performScrollTo().performClick()

        settle()

        compose.onNodeWithText("Edit this session").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        settle()
        assertEquals(7L, savedId)
        assertEquals("Gym", savedDraft?.name)
    }

    @Test
    fun `the bin asks first, and says what deleting a series does`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithContentDescription("Delete \"Gym\"").performScrollTo().performClick()

        settle()

        compose.onNodeWithText("Delete \"Gym\"?").assertIsDisplayed()
        assertNull("nothing is deleted before the question is answered", deletedSlot)

        compose.onNodeWithText("Delete").performClick()

        settle()
        assertEquals(7L, deletedSlot)
    }

    @Test
    fun `keeping it at the confirmation deletes nothing`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithContentDescription("Delete \"Gym\"").performScrollTo().performClick()

        settle()
        compose.onNodeWithText("Keep it").performClick()
        settle()

        assertNull(deletedSlot)
        compose.onNodeWithText("Delete \"Gym\"?").assertDoesNotExist()
    }

    // --- the day list underneath is wired to the caller's actions --------------------------------

    @Test
    fun `starting a planned session from the calendar carries the day it was planned on`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithText("Start").performScrollTo().performClick()

        settle()

        assertEquals(7L to today, startedFromPlan)
    }

    @Test
    fun `a workout already recorded shows up under the day it happened on`() {
        val journal = Journal()
        val workout = journal.startWorkout(today.toString(), at = "18:05")
        journal.addExercise(workout, today.toString(), bench, restSec = 150)
        journal.strengthSet(bench, today.toString(), at = "18:10", workoutId = workout)

        calendar(journal = journal)

        // started today and never closed, so it is the workout in progress
        compose.onNodeWithText("in progress - 1 exercise, 1 set").assertExists()
    }
}
