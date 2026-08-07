package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
 */
class CalendarScreenTest : ScreenTest() {

    private val today: LocalDate = LocalDate.now()
    private val bench = exerciseRef(1, "Bench press")

    private var savedDraft: SlotDraft? = null
    private var savedId: Long? = null
    private var deletedSlot: Long? = null
    private var startedFromPlan: Pair<Long, LocalDate>? = null

    private val noActions = DayActions(
        startFromPlan = { id, date -> startedFromPlan = id to date },
        startWorkout = {},
        logSingleEntry = {},
        continueWorkout = {},
        openWorkout = {},
        openExercise = {},
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
        // the two things the cells encode are named in words, because neither is legible
        // as colour alone
        compose.onNodeWithText("done").assertIsDisplayed()
        compose.onNodeWithText("missed").assertIsDisplayed()
        compose.onNodeWithText("planned").assertIsDisplayed()
        compose.onNodeWithText("dots = activities").assertIsDisplayed()
    }

    @Test
    fun `the arrows move the month, and back again`() {
        calendar()

        compose.onNodeWithContentDescription("Next month").performClick()
        compose.onNodeWithText(fmtMonth(today.plusMonths(1))).assertIsDisplayed()

        compose.onNodeWithContentDescription("Previous month").performClick()
        compose.onNodeWithContentDescription("Previous month").performClick()
        compose.onNodeWithText(fmtMonth(today.minusMonths(1))).assertIsDisplayed()
    }

    @Test
    fun `the day underneath opens on today and is the same list the Today tab draws`() {
        calendar()

        compose.onNodeWithText("SELECTED DAY").assertIsDisplayed()
        compose.onNodeWithText(fmtWeekdayDay(today)).assertIsDisplayed()
        compose.onNodeWithText(
            "Nothing planned and nothing recorded. Start a workout below, or log a single entry."
        ).assertIsDisplayed()
    }

    @Test
    fun `a planned session on the selected day is drawn with the calendar's own pencil and bin`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithText("Gym").assertIsDisplayed()
        // Today leaves these two out; only the calendar passes them in
        compose.onNodeWithContentDescription("Edit \"Gym\"").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete \"Gym\"").assertIsDisplayed()
    }

    // --- planning, editing, deleting ----------------------------------------------------------

    @Test
    fun `Plan a session opens the editor on the day that is selected`() {
        calendar()

        compose.onNodeWithText("Plan a session").performClick()

        compose.onNodeWithText("Session name").assertIsDisplayed()
        compose.onNodeWithText("Add to the plan").assertIsDisplayed()
        // the day is printed twice now: once as the agenda's own heading, once in the
        // dialog's date field. The button never means "some other day"
        compose.onAllNodesWithText(fmtWeekdayDay(today)).assertCountEquals(2)
    }

    @Test
    fun `a session planned in the dialog is handed to the caller as a new slot`() {
        calendar()

        compose.onNodeWithText("Plan a session").performClick()
        compose.onNodeWithText("Session name").performTextInput("Hangboard")
        compose.onNodeWithText("Add to the plan").performClick()

        assertEquals("Hangboard", savedDraft?.name)
        assertNull("a slot being created has no id yet", savedId)
        // and the dialog is gone
        compose.onNodeWithText("Session name").assertDoesNotExist()
    }

    @Test
    fun `the pencil opens the editor on the session it belongs to`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithContentDescription("Edit \"Gym\"").performClick()

        compose.onNodeWithText("Edit this session").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals(7L, savedId)
        assertEquals("Gym", savedDraft?.name)
    }

    @Test
    fun `the bin asks first, and says what deleting a series does`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithContentDescription("Delete \"Gym\"").performClick()

        compose.onNodeWithText("Delete \"Gym\"?").assertIsDisplayed()
        assertNull("nothing is deleted before the question is answered", deletedSlot)

        compose.onNodeWithText("Delete").performClick()
        assertEquals(7L, deletedSlot)
    }

    @Test
    fun `keeping it at the confirmation deletes nothing`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithContentDescription("Delete \"Gym\"").performClick()
        compose.onNodeWithText("Keep it").performClick()

        assertNull(deletedSlot)
        compose.onNodeWithText("Delete \"Gym\"?").assertDoesNotExist()
    }

    // --- the day list underneath is wired to the caller's actions --------------------------------

    @Test
    fun `starting a planned session from the calendar carries the day it was planned on`() {
        calendar(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithText("Start").performClick()

        assertEquals(7L to today, startedFromPlan)
    }

    @Test
    fun `a workout already recorded shows up under the day it happened on`() {
        val journal = Journal()
        val workout = journal.startWorkout(today.toString(), at = "18:05")
        journal.addExercise(workout, today.toString(), bench, restSec = 150)
        journal.strengthSet(bench, today.toString(), at = "18:10", workoutId = workout)

        calendar(journal = journal)

        compose.onNodeWithText("1 exercise, 1 set").assertIsDisplayed()
    }
}
