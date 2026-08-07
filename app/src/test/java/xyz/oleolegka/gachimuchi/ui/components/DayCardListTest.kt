package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.dayCards
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.exerciseRef
import xyz.oleolegka.gachimuchi.ui.slot
import java.time.LocalDate

/**
 * The list of a day's cards, drawn.
 *
 * ── What this kind of test does catch ───────────────────────────────────────────
 * That every field of a card reaches the screen (a subtitle computed and then not drawn is
 * a real and invisible failure), that the four kinds are told apart in WORDS and not only
 * by the colour of their spine, that the buttons a day is allowed to offer are the ones
 * present, and that a tap runs the callback it is supposed to run with the arguments it is
 * supposed to carry.
 *
 * ── What it does NOT catch, and what only a phone will ──────────────────────────
 * Nothing here is rasterised. A card can be behind another card, clipped by the system bars,
 * scrolled off the bottom, drawn in white on white, or 4 dp tall, and every assertion below
 * still passes: the semantics tree records that a Text exists and what it says, not where or
 * whether a human can read it. Touch targets, contrast, the keyboard, RTL and dark-theme
 * legibility are all outside it.
 *
 * ANIMATION IS NOT EXERCISED AT ALL. The harness holds the frame clock still, because a
 * Material text field otherwise never lets the composition settle (ScreenTest says why), and
 * a test only winds it on far enough for a surface to finish arriving. So a transition that
 * flickers, lands in the wrong place, or never ends is invisible from here — which is a
 * whole class of defect that stays the user's to find.
 */
class DayCardListTest : ScreenTest() {

    private val today = LocalDate.parse("2026-08-07")
    private val yesterday = today.minusDays(1)
    private val tomorrow = today.plusDays(1)

    private val bench = exerciseRef(1, "Bench press")
    private val squat = exerciseRef(2, "Squat")
    private val fingerboard = exerciseRef(3, "Fingerboard 20 mm")

    // what each callback was last called with; null means it was never called
    private var startedFromPlan: Pair<Long, LocalDate>? = null
    private var startedWorkout: LocalDate? = null
    private var loggedSingle: LocalDate? = null
    private var continued: Long? = null
    private var opened: Long? = null
    private var openedExercise: Long? = null
    private var edited: Long? = null
    private var deleted: Long? = null

    private fun actions(withSlotIcons: Boolean = false) = DayActions(
        startFromPlan = { id, date -> startedFromPlan = id to date },
        startWorkout = { date -> startedWorkout = date },
        logSingleEntry = { date -> loggedSingle = date },
        continueWorkout = { id -> continued = id },
        openWorkout = { id -> opened = id },
        openExercise = { id -> openedExercise = id },
        editSlot = if (withSlotIcons) ({ id -> edited = id }) else null,
        deleteSlot = if (withSlotIcons) ({ id -> deleted = id }) else null,
    )

    /** Raises the list for [date], built by the real reducer out of [events] and [slots]. */
    private fun day(
        events: List<JournalEvent> = emptyList(),
        slots: List<Slot> = emptyList(),
        date: LocalDate = today,
        withSlotIcons: Boolean = false,
    ) {
        val cards = dayCards(events, slots, date, today, today.atTime(12, 0))
        screen { DayCardList(day = cards, date = date, actions = actions(withSlotIcons)) }
    }

    // --- the four kinds of card ----------------------------------------------------------

    @Test
    fun `a planned session is drawn with its time and says it has not been started`() {
        day(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithText("Gym").assertIsDisplayed()
        compose.onNodeWithText("18:00").assertIsDisplayed()
        compose.onNodeWithText("not started yet").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()

        compose.onNodeWithText("Start").performClick()
        assertEquals(7L to today, startedFromPlan)
    }

    @Test
    fun `the workout in progress says so, counts what is in it, and offers to continue`() {
        val journal = Journal()
        val workout = journal.startWorkout(today.toString(), at = "18:05")
        journal.addExercise(workout, today.toString(), bench, restSec = 150)
        journal.addExercise(workout, today.toString(), squat, restSec = 180)
        journal.strengthSet(bench, today.toString(), at = "18:10", workoutId = workout)
        journal.strengthSet(bench, today.toString(), at = "18:14", workoutId = workout)
        journal.strengthSet(squat, today.toString(), at = "18:20", workoutId = workout)

        day(journal.events)

        compose.onNodeWithText("in progress - 2 exercises, 3 sets").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()

        compose.onNodeWithText("Continue").performClick()
        assertEquals(workout, continued)
    }

    @Test
    fun `a workout that is not the open one counts its contents and opens on a tap`() {
        val journal = Journal()
        // yesterday, so nothing about it is the workout in progress
        val workout = journal.startWorkout(yesterday.toString(), at = "18:05")
        journal.addExercise(workout, yesterday.toString(), bench, restSec = 150)
        journal.strengthSet(bench, yesterday.toString(), at = "18:10", workoutId = workout)

        day(journal.events, date = yesterday)

        compose.onNodeWithText("1 exercise, 1 set").assertIsDisplayed()
        // the two words that begin something are the only ones that get a button of their own
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.onNodeWithText("Start").assertDoesNotExist()

        // the card itself is the way in
        compose.onNodeWithText("18:05 - 18:10").performClick()
        assertEquals(workout, opened)
    }

    @Test
    fun `three loose sets of one exercise are one card, not three`() {
        val journal = Journal()
        journal.strengthSet(fingerboard, today.toString(), at = "12:00")
        journal.strengthSet(fingerboard, today.toString(), at = "12:04")
        journal.strengthSet(fingerboard, today.toString(), at = "12:08")

        day(journal.events)

        compose.onNodeWithText("Fingerboard 20 mm").assertIsDisplayed()
        compose.onNodeWithText("outside a workout - 3 entries").assertIsDisplayed()
        compose.onNodeWithText("12:00 - 12:08").assertIsDisplayed()

        compose.onNodeWithText("Fingerboard 20 mm").performClick()
        assertEquals(fingerboard.id, openedExercise)
    }

    @Test
    fun `two loose exercises are two cards, each counting only its own entries`() {
        val journal = Journal()
        journal.strengthSet(fingerboard, today.toString(), at = "12:00")
        journal.strengthSet(fingerboard, today.toString(), at = "12:04")
        journal.strengthSet(bench, today.toString(), at = "13:00")

        day(journal.events)

        compose.onNodeWithText("outside a workout - 2 entries").assertIsDisplayed()
        compose.onNodeWithText("outside a workout - 1 entry").assertIsDisplayed()
    }

    @Test
    fun `an entry that names no exercise gets a card but nothing to open`() {
        val journal = Journal()
        journal.weighIn(today.toString(), kg = 74.2)

        day(journal.events)

        compose.onNodeWithText("Body weight").assertIsDisplayed()
        compose.onNodeWithText("outside a workout - 1 entry").assertIsDisplayed()
        compose.onNodeWithText("Body weight").performClick()
        assertNull("a weigh-in has no exercise history behind it", openedExercise)
    }

    // --- an empty day ---------------------------------------------------------------------

    @Test
    fun `an empty day that can still be trained names the button underneath it`() {
        day()

        compose.onNodeWithText(
            "Nothing planned and nothing recorded. Start a workout below, or log a single entry."
        ).assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test
    fun `a day in the future is not told it recorded nothing, and is offered no way to record`() {
        day(date = tomorrow)

        compose.onNodeWithText(
            "Nothing planned for this day. Plan a session below and it appears here."
        ).assertIsDisplayed()
        // recording a set that has not been done yet is not an edge case, it is a wrong journal
        compose.onNodeWithText("Add").assertDoesNotExist()
    }

    @Test
    fun `a plan on a future day is drawn but has nothing to start`() {
        day(slots = listOf(slot(7, "Gym", "18:00", tomorrow.toString())), date = tomorrow)

        compose.onNodeWithText("Gym").assertIsDisplayed()
        compose.onNodeWithText("planned").assertIsDisplayed()
        compose.onNodeWithText("Start").assertDoesNotExist()
        compose.onNodeWithText("Add").assertDoesNotExist()
    }

    // --- adding ---------------------------------------------------------------------------

    @Test
    fun `the Add button asks which of the two, and each answer calls its own action`() {
        day()

        // the menu is not on the screen until it is asked for
        compose.onNodeWithText("Workout").assertDoesNotExist()
        compose.onNodeWithText("Single entry").assertDoesNotExist()

        compose.onNodeWithText("Add").performClick()
        settle()
        compose.onNodeWithText("Workout").assertIsDisplayed()
        compose.onNodeWithText("Single entry").assertIsDisplayed()

        compose.onNodeWithText("Workout").performClick()
        assertEquals(today, startedWorkout)
        assertNull(loggedSingle)
    }

    @Test
    fun `the second answer of the Add menu logs a single entry on the day being shown`() {
        day(date = yesterday)

        compose.onNodeWithText("Add").performClick()
        settle()
        compose.onNodeWithText("Single entry").performClick()

        assertEquals("the entry belongs to the day on screen, not to today", yesterday, loggedSingle)
        assertNull(startedWorkout)
    }

    // --- the pencil and the bin, which belong to the calendar only -------------------------

    @Test
    fun `a planned card carries edit and delete where the caller passes them`() {
        day(slots = listOf(slot(7, "Gym", "18:00", today.toString())), withSlotIcons = true)

        compose.onNodeWithContentDescription("Edit \"Gym\"").assertHasClickAction().performClick()
        assertEquals(7L, edited)

        compose.onNodeWithContentDescription("Delete \"Gym\"").performClick()
        assertEquals(7L, deleted)
    }

    @Test
    fun `the same card carries neither where they were left out`() {
        // Today is the screen you stand in the gym with: a bin one mis-tap from Start is a
        // bad trade, and rewriting the schedule is the calendar's job
        day(slots = listOf(slot(7, "Gym", "18:00", today.toString())), withSlotIcons = false)

        compose.onNodeWithContentDescription("Edit \"Gym\"").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete \"Gym\"").assertDoesNotExist()
        compose.onNodeWithText("Start").assertIsDisplayed()
    }
}
