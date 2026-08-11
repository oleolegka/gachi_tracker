package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.DraftSummary
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
    private var startedWorkout: Pair<LocalDate, String?>? = null
    private var loggedSingle: LocalDate? = null
    private var continued: Long? = null
    private var opened: Long? = null
    private var openedExercise: Pair<Long, LocalDate>? = null
    private var edited: Long? = null
    private var deleted: Long? = null
    private var deletedWorkout: Long? = null
    private var deletedEntries: Pair<List<Long>, Long?>? = null
    private var renamed: Pair<Long, String?>? = null
    private var resumedDraft = false
    private var discardedDraft = false

    private fun actions(withSlotIcons: Boolean = false) = DayActions(
        startFromPlan = { id, date -> startedFromPlan = id to date },
        startWorkout = { date, name -> startedWorkout = date to name },
        logSingleEntry = { date -> loggedSingle = date },
        continueWorkout = { id -> continued = id },
        openWorkout = { id -> opened = id },
        openExercise = { id, date -> openedExercise = id to date },
        deleteWorkout = { id -> deletedWorkout = id },
        deleteSingleEntries = { ids, exerciseId -> deletedEntries = ids to exerciseId },
        renameWorkout = { id, name -> renamed = id to name },
        resumeDraft = { resumedDraft = true },
        discardDraft = { discardedDraft = true },
        editSlot = if (withSlotIcons) ({ id -> edited = id }) else null,
        deleteSlot = if (withSlotIcons) ({ id -> deleted = id }) else null,
    )

    /** Raises the list for [date], built by the real reducer out of [events] and [slots]. */
    private fun day(
        events: List<JournalEvent> = emptyList(),
        slots: List<Slot> = emptyList(),
        date: LocalDate = today,
        withSlotIcons: Boolean = false,
        draft: DraftSummary? = null,
    ) {
        val cards = dayCards(events, slots, date, today, today.atTime(12, 0), draft)
        screen { DayCardList(day = cards, date = date, actions = actions(withSlotIcons)) }
    }

    // --- the four kinds of card ----------------------------------------------------------

    @Test
    fun `a planned session is drawn with its time and says it has not been started`() {
        day(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithText("Gym").assertIsDisplayed()
        compose.onNodeWithText("plan · 18:00").assertIsDisplayed()
        compose.onNodeWithText("not started").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()

        compose.onNodeWithText("Start").performClick()
        assertEquals(7L to today, startedFromPlan)
    }

    /*
     * A plan is opened by tapping it and started only by the button. It used to start from
     * anywhere on the card, and that is how a session planned for the evening became a
     * workout running an hour early: the user tapped it to look inside and to add exercises.
     * Reported from the phone on 2026-08-08.
     */
    @Test
    fun `tapping the body of a plan opens it and does not start a workout`() {
        day(slots = listOf(slot(7, "Gym", "18:00", today.toString())), withSlotIcons = true)

        compose.onNodeWithText("Gym").performClick()

        assertEquals(null, startedFromPlan)
        assertEquals(7L, edited)
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

        // nobody named it, so the title is its clock reading and the meta line is the state
        compose.onNodeWithText("in progress").assertIsDisplayed()
        compose.onNodeWithText("2 exercises · 3 sets").assertIsDisplayed()
        /*
         * NO BUTTON. It said "Continue" and called the very handler a tap on the card calls —
         * two ways to do one thing, which is the first rule of the redraw. The card is the way
         * in, and the chevron on it is what says so.
         */
        compose.onNodeWithText("Continue").assertDoesNotExist()

        compose.onNodeWithText("18:05 - 18:20").performClick()
        assertEquals(workout, continued)
    }

    @Test
    fun `a workout that is not the open one counts its contents and opens on a tap`() {
        val journal = Journal()
        // yesterday AND closed, which is what makes it not the one in progress: midnight no
        // longer ends a workout (§13), so an unfinished one from yesterday still is
        val workout = journal.startWorkout(yesterday.toString(), at = "18:05")
        journal.addExercise(workout, yesterday.toString(), bench, restSec = 150)
        journal.strengthSet(bench, yesterday.toString(), at = "18:10", workoutId = workout)
        journal.finishWorkout(workout, yesterday.toString(), at = "18:30")

        day(journal.events, date = yesterday)

        compose.onNodeWithText("1 exercise · 1 set").assertIsDisplayed()
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
        compose.onNodeWithText("outside a workout · 12:00 - 12:08").assertIsDisplayed()
        // counted by what they are, not by the generic word: these are sets
        compose.onNodeWithText("3 sets").assertIsDisplayed()

        compose.onNodeWithText("Fingerboard 20 mm").performClick()
        assertEquals("the breakdown is opened for the day on screen", fingerboard.id to today, openedExercise)
    }

    @Test
    fun `two loose exercises are two cards, each counting only its own entries`() {
        val journal = Journal()
        journal.strengthSet(fingerboard, today.toString(), at = "12:00")
        journal.strengthSet(fingerboard, today.toString(), at = "12:04")
        journal.strengthSet(bench, today.toString(), at = "13:00")

        day(journal.events)

        compose.onNodeWithText("2 sets").assertIsDisplayed()
        compose.onNodeWithText("1 set").assertIsDisplayed()
    }

    @Test
    fun `an entry that names no exercise gets a card but nothing to open`() {
        val journal = Journal()
        journal.weighIn(today.toString(), kg = 74.2)

        day(journal.events)

        compose.onNodeWithText("Body weight").assertIsDisplayed()
        // the app knows perfectly well what this is, so the card says it
        compose.onNodeWithText("1 weigh-in").assertIsDisplayed()
        compose.onNodeWithText("Body weight").performClick()
        assertNull("a weigh-in has no exercise history behind it", openedExercise)
    }

    // --- an empty day ---------------------------------------------------------------------

    @Test
    fun `an empty day that can still be trained names the button underneath it`() {
        day()

        compose.onNodeWithText("Nothing planned or recorded for today.").assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test
    fun `a day in the future is not told it recorded nothing, and is offered no way to record`() {
        day(date = tomorrow)

        compose.onNodeWithText(
            "Nothing planned for this day. Add a planned session below and it appears here."
        ).assertIsDisplayed()
        // recording a set that has not been done yet is not an edge case, it is a wrong journal
        compose.onNodeWithText("Add").assertDoesNotExist()
    }

    @Test
    fun `a plan on a future day is drawn but has nothing to start`() {
        day(slots = listOf(slot(7, "Gym", "18:00", tomorrow.toString())), date = tomorrow)

        compose.onNodeWithText("Gym").assertIsDisplayed()
        compose.onNodeWithText("plan · 18:00").assertIsDisplayed()
        // and NOT "not started": a session at six tonight has not failed to start
        compose.onNodeWithText("not started").assertDoesNotExist()
        compose.onNodeWithText("Start").assertDoesNotExist()
        compose.onNodeWithText("Add").assertDoesNotExist()
    }

    // --- adding ---------------------------------------------------------------------------

    /**
     * What the "Workout" answer then does — it asks for a name — is exercised in
     * [DayCardNamingTest] rather than here, because that dialog carries a text field and a
     * text field inside a dialog needs a 600 dp window under Robolectric (see [ScreenTest]).
     * This class stays at the width of a real phone, so it stops at the menu.
     */
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

        assertNull("neither answer has been given yet", startedWorkout)
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

    // --- the long press, and what it is allowed to remove ---------------------------------

    /** A finished workout of yesterday, so it is not the one in progress. */
    private fun finishedWorkout(journal: Journal): Long {
        val workout = journal.startWorkout(yesterday.toString(), at = "18:05")
        journal.addExercise(workout, yesterday.toString(), bench, restSec = 150)
        journal.strengthSet(bench, yesterday.toString(), at = "18:10", workoutId = workout)
        journal.finishWorkout(workout, yesterday.toString(), at = "18:30")
        return workout
    }

    @Test
    fun `a long press on a workout card offers to delete it, and asks before it does`() {
        val journal = Journal()
        val workout = finishedWorkout(journal)
        day(journal.events, date = yesterday)

        // nothing on the card says so until it is asked: the gesture costs no screen space
        compose.onNodeWithText("Delete workout").assertDoesNotExist()

        compose.onNodeWithText("18:05 - 18:10").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Delete workout").performClick()
        settle()

        compose.onNodeWithText("Delete this workout?").assertExists()
        assertNull("nothing may be written before the question is answered", deletedWorkout)

        compose.onNodeWithText("Delete").performClick()
        assertEquals(workout, deletedWorkout)
    }

    /**
     * The confirmation names what disappears, not just the verb: deleting a workout takes its
     * sets out of the volume, the records and the streak, and that is invisible from the card.
     */
    @Test
    fun `the question says what stops counting, and can be answered no`() {
        val journal = Journal()
        finishedWorkout(journal)
        day(journal.events, date = yesterday)

        compose.onNodeWithText("18:05 - 18:10").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Delete workout").performClick()
        settle()

        compose.onNodeWithText("18:05 - 18:10 - 1 exercise · 1 set").assertExists()
        compose.onNodeWithText(
            "Everything recorded in it goes too - its sets stop counting towards volume, " +
                "records and the streak. The journal keeps the original rows - removing is " +
                "itself an entry, so this can be undone later rather than being the end of " +
                "the evidence."
        ).assertExists()

        compose.onNodeWithText("Keep it").performClick()
        assertNull("answering no must write nothing", deletedWorkout)
    }

    /** A plan answers a long press with nothing: it already carries its actions as icons. */
    @Test
    fun `a planned card raises no menu`() {
        day(slots = listOf(slot(7, "Gym", "18:00", today.toString())))

        compose.onNodeWithText("Gym").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Delete workout").assertDoesNotExist()
        compose.onNodeWithText("Delete entry").assertDoesNotExist()
        assertNull(deletedWorkout)
    }

    /*
     * §23.A1, from the phone: a single entry could be emptied out one set at a time until it
     * vanished, and could not be deleted. A card is an object and every object of the log is
     * removed the same way — by the same long press a workout card answers.
     */
    @Test
    fun `a long press on a loose card offers to delete the whole group and names the rows`() {
        val journal = Journal()
        journal.strengthSet(fingerboard, today.toString(), at = "12:00")
        journal.strengthSet(fingerboard, today.toString(), at = "12:04")
        val ids = journal.events.map { it.id }

        day(journal.events)

        compose.onNodeWithText("Delete these entries").assertDoesNotExist()

        compose.onNodeWithText("Fingerboard 20 mm").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Delete these entries").performClick()
        settle()

        compose.onNodeWithText("Delete these entries?").assertExists()
        assertNull("nothing may be written before the question is answered", deletedEntries)

        compose.onNodeWithText("Delete").performClick()
        assertEquals(ids to fingerboard.id, deletedEntries)
    }

    /** A card that names no exercise (a weigh-in) is deleted the same way, with a null id. */
    @Test
    fun `a weigh-in card can be deleted too`() {
        val journal = Journal()
        journal.weighIn(today.toString(), kg = 74.2)

        day(journal.events)

        compose.onNodeWithText("Body weight").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Delete entry").performClick()
        settle()
        compose.onNodeWithText("Delete this entry?").assertExists()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(journal.events.map { it.id } to null, deletedEntries)
    }

    // --- the draft, which is not in the journal at all (§23.A3) ----------------------------

    @Test
    fun `a workout composed and not started is a card that says so and leads back into it`() {
        day(draft = DraftSummary(today.toString(), "Legs", exerciseCount = 3))

        compose.onNodeWithText("Legs").assertIsDisplayed()
        compose.onNodeWithText("draft · not started").assertIsDisplayed()
        compose.onNodeWithText("3 exercises · nothing recorded").assertIsDisplayed()
        // the same removal as on the running card, for the same reason
        compose.onNodeWithText("Continue").assertDoesNotExist()

        compose.onNodeWithText("Legs").performClick()
        assertEquals(true, resumedDraft)
    }

    @Test
    fun `the draft card belongs to its own day and appears on no other`() {
        day(date = yesterday, draft = DraftSummary(today.toString(), "Legs", exerciseCount = 3))

        compose.onNodeWithText("Legs").assertDoesNotExist()
    }

    /*
     * The whole of §23.A3 in one assertion: throwing the draft away is an ANSWER to a
     * question, never a side effect. The cross on the logging screen used to do it silently.
     */
    @Test
    fun `discarding a draft is behind a long press and a question`() {
        day(draft = DraftSummary(today.toString(), null, exerciseCount = 2))

        // an unnamed draft is shown the way an unnamed workout is
        compose.onNodeWithText("Workout").assertIsDisplayed()

        compose.onNodeWithText("Workout").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Discard draft").performClick()
        settle()

        compose.onNodeWithText("Discard this draft?").assertExists()
        assertEquals("the question alone must throw nothing away", false, discardedDraft)

        compose.onNodeWithText("Discard").performClick()
        assertEquals(true, discardedDraft)
    }

    /** One draft at a time, and the menu says why rather than hiding the answer. */
    @Test
    fun `the Add menu refuses a second workout while one is being composed`() {
        day(draft = DraftSummary(today.toString(), "Legs", exerciseCount = 1))

        compose.onNodeWithText("Add").performClick()
        settle()
        compose.onNodeWithText("Workout - one is already open").assertIsDisplayed()
        compose.onNodeWithText("Single entry").assertIsDisplayed()
    }

    // --- the plan's own menu, which belongs to the calendar only ---------------------------

    /**
     * The pair used to be a pencil and a BIN drawn side by side, the bin eight points from
     * "Start" — one mis-tap from a deleted plan (rule 3 of the redraw). They are one kebab
     * now; which screen gets them is unchanged.
     */
    @Test
    fun `a planned card carries edit and delete where the caller passes them`() {
        day(slots = listOf(slot(7, "Gym", "18:00", today.toString())), withSlotIcons = true)

        compose.onNodeWithContentDescription("Actions for \"Gym\"").assertHasClickAction().performClick()
        settle()
        compose.onNodeWithText("Edit plan").performClick()
        settle()
        assertEquals(7L, edited)

        compose.onNodeWithContentDescription("Actions for \"Gym\"").performClick()
        settle()
        compose.onNodeWithText("Delete plan").performClick()
        settle()
        assertEquals(7L, deleted)
    }

    @Test
    fun `the same card carries neither where they were left out`() {
        // Today is the screen you stand in the gym with: rewriting the schedule mid-set is
        // not a thing anyone does, and it is the calendar's job
        day(slots = listOf(slot(7, "Gym", "18:00", today.toString())), withSlotIcons = false)

        compose.onNodeWithContentDescription("Actions for \"Gym\"").assertDoesNotExist()
        compose.onNodeWithText("Start").assertIsDisplayed()
    }

    /**
     * The plan is an item of the SAME menu since the redraw, not a second button under it —
     * and it is there only when the caller offers it. Today never does; the calendar does
     * not on a day already gone.
     */
    @Test
    fun `planning is in the Add menu only where the caller offers it`() {
        day()

        compose.onNodeWithText("Add").performClick()
        settle()

        compose.onNodeWithText("Planned session").assertDoesNotExist()
    }
}
