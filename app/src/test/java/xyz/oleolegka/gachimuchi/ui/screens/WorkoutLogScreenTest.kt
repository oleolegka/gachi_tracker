package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.RestFloor
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * Logging inside a workout: the cards, the quick form, and the rests running under them.
 *
 * The screen folds the workout out of the journal itself, so the fixtures are journals and
 * the assertions are about what a reader ends up being told — which exercises are on screen,
 * what the form arrives prefilled with, what the line above it says the previous session did,
 * and whether a rest bar reports the state its floor is actually in.
 *
 * ── The window is 600 dp wide, and that is a gap ────────────────────────────────
 * For the reason written out in [ScreenTest]: this screen raises a bottom sheet and a dialog,
 * both of them carrying text fields, and at phone width a text field inside one of those never
 * lets the composition settle. So nothing here is exercised at the width of the phone the app
 * is built for. The assertions are text and callbacks, which a window size does not change; a
 * sheet that is clipped or scrolled wrong at 411 dp would pass every one of them.
 *
 * The rest of the limits are the ones in
 * [xyz.oleolegka.gachimuchi.ui.components.DayCardListTest]: this proves the words exist and
 * are right, never that they are visible to a person.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class WorkoutLogScreenTest : ScreenTest() {

    private val iso = "2026-08-07"

    private val bench = exerciseRef(1, "Bench press")
    private val abs = exerciseRef(2, "Abs")

    /**
     * A hangboard exercise, which is what a protocol-led one is in practice: the 7:3 protocol
     * is part of its identity (§12-A), so it is on the catalog row rather than asked per set.
     */
    private val hangs = ExerciseRef(
        id = 3, name = "Hangs 20 mm", form = ExerciseForm.HOLD,
        edgeMm = 20.0, workSec = 7.0, restSec = 3.0,
    )

    /**
     * The catalog carries a chosen rest for each, which is what the "add an exercise" question
     * is prefilled from. Set here rather than left to be measured out of the journal so the
     * number under test is a fact of the fixture and not of the gaps between its timestamps.
     */
    private val catalog = listOf(
        exerciseEntity(1, "Bench press").copy(defaultRestSec = 150),
        exerciseEntity(2, "Abs").copy(defaultRestSec = 90),
        exerciseEntity(3, "Hangs 20 mm", ExerciseForm.HOLD)
            .copy(defaultRestSec = 240, edgeMm = 20.0, protocolWorkSec = 7.0, protocolRestSec = 3.0),
    )

    private val added = mutableListOf<Pair<Long, Int>>()
    private val logged = mutableListOf<ActivityForm>()
    private val undone = mutableListOf<Long>()
    private val started = mutableListOf<Pair<String, Double?>>()
    private var conductorOpened = 0
    private var summaryDismissed = 0
    private var finishes = 0
    private var closed = 0

    /** A monotonic instant the floors are placed around, so no test races a real clock. */
    private val now = 1_000_000L

    private fun show(
        journal: Journal,
        workoutId: Long,
        floors: List<RestFloor> = emptyList(),
        liveExerciseId: Long? = null,
        readySummary: String? = null,
    ) {
        val state = UiState(events = journal.events, exercises = catalog, loading = false)
        screen {
            WorkoutLogScreen(
                state = state,
                workoutId = workoutId,
                settings = TimerSettings(),
                floors = floors,
                actions = WorkoutLogActions(
                    addExercise = { id, rest -> added += id to rest },
                    createExercise = { _, _, _, _, _, _ -> },
                    addSet = { form -> logged += form },
                    undoSet = { id -> undone += id },
                    finish = { finishes++ },
                    startProtocolSet = { exercise, kg -> started += exercise.name to kg },
                    openConductor = { conductorOpened++ },
                    close = { closed++ },
                ),
                liveExerciseId = liveExerciseId,
                readySummary = readySummary,
                onDismissSummary = { summaryDismissed++ },
                nowMs = now,
            )
        }
    }

    /** Bench with two sets in it, abs added and not yet started — the ordinary early state. */
    private fun supersetWorkout(journal: Journal): Long {
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, bench, restSec = 150)
        journal.addExercise(workout, iso, abs, restSec = 90, at = "18:06")
        journal.strengthSet(bench, iso, at = "18:10", weightKg = 60.0, reps = 5, workoutId = workout)
        journal.strengthSet(bench, iso, at = "18:14", weightKg = 62.5, reps = 5, workoutId = workout)
        return workout
    }

    private fun countingFloor(exerciseId: Long, name: String, leftMs: Long, orderedMs: Long) =
        RestFloor(
            exerciseId = exerciseId, exerciseName = name, readyAtMs = now + leftMs,
            bootRef = 0, orderedMs = orderedMs, startedAtWallMs = 0,
        )

    // --- the cards ------------------------------------------------------------------------

    @Test
    fun `every exercise of the workout gets a card, the one with no sets included`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").assertIsDisplayed()
        compose.onNodeWithText("60 kg × 5 reps, 62.5 kg × 5 reps").assertIsDisplayed()
        /*
         * The empty card is the point of the whole model, not an edge case: exercises are put
         * into the workout on the way in, before a single set of them exists.
         */
        compose.onNodeWithText("Abs").assertIsDisplayed()
        compose.onNodeWithText("no sets yet").assertIsDisplayed()
    }

    @Test
    fun `each card names the rest chosen for it, and that is the control that changes it`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("rest 2:30").assertIsDisplayed()
        compose.onNodeWithText("rest 1:30").assertIsDisplayed()
    }

    @Test
    fun `the header counts what is in the workout`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Fri 7 Aug - 2 exercises, 2 sets").assertIsDisplayed()
    }

    /**
     * The name is the SNAPSHOT taken when start was pressed, not the plan's name as it reads
     * today: a plan is editable and what a session was called on the day is not. Asserted here
     * because the obvious implementation — look the slot up and print its name — compiles
     * perfectly and renames every workout in the history the first time a plan is renamed.
     */
    @Test
    fun `the header shows the name the workout was started under`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05", slotId = 7L, name = "Push day")
        journal.addExercise(workout, iso, bench, restSec = 150)
        show(journal, workout)

        compose.onNodeWithText("Push day").assertIsDisplayed()
    }

    @Test
    fun `a workout with nothing in it says what to do rather than showing an empty list`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")
        show(journal, workout)

        compose.onNodeWithText(
            "Nothing in this workout yet. Add the exercises you are about to do - " +
                "a card with no sets on it is fine, it is the plan for the next hour."
        ).assertIsDisplayed()
    }

    @Test
    fun `a workout the journal no longer has says so rather than drawing nothing`() {
        show(Journal(), workoutId = 404L)

        compose.onNodeWithText("This workout is no longer in the journal.").assertIsDisplayed()
    }

    // --- the rest bars --------------------------------------------------------------------

    @Test
    fun `a rest still counting says how much of it is left`() {
        val journal = Journal()
        val workout = supersetWorkout(journal)
        show(journal, workout, floors = listOf(countingFloor(1, "Bench press", 74_000, 150_000)))

        compose.onNodeWithText("rest 1:14 left").assertIsDisplayed()
    }

    /**
     * "Ready" on its own is the least useful thing a rest timer can say. Between sets the
     * moment is missed constantly, and the number that matters is how badly.
     */
    @Test
    fun `a rest that is over says how long it has been over`() {
        val journal = Journal()
        val workout = supersetWorkout(journal)
        show(journal, workout, floors = listOf(countingFloor(1, "Bench press", -150_000, 150_000)))

        compose.onNodeWithText("ready, +2:30").assertIsDisplayed()
    }

    /** Two exercises resting at once is the case the whole model was rebuilt for. */
    @Test
    fun `two rests run side by side, one per card`() {
        val journal = Journal()
        val workout = supersetWorkout(journal)
        show(
            journal, workout,
            floors = listOf(
                countingFloor(1, "Bench press", 74_000, 150_000),
                countingFloor(2, "Abs", -30_000, 90_000),
            ),
        )

        compose.onNodeWithText("rest 1:14 left").assertIsDisplayed()
        compose.onNodeWithText("ready, +0:30").assertIsDisplayed()
    }

    // --- the quick form -------------------------------------------------------------------

    @Test
    fun `tapping a card raises the form, prefilled from the last set of that exercise`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("60 kg × 5 reps, 62.5 kg × 5 reps").performClick()
        settle()
        settle()

        compose.onNodeWithText("Weight, kg").assertExists()
        compose.onNodeWithText("62.5").assertExists()
        // untouched values, so the button says what one tap will do
        compose.onNodeWithText("Repeat set").assertExists()
    }

    /**
     * The line the decision at the bar is actually made on: sixty for nine again, or
     * sixty-two and a half for eight. It is the PREVIOUS session, not this workout's own last
     * set, which the card two centimetres above already shows.
     */
    @Test
    fun `the form says what the previous session did, and not what this workout just did`() {
        val journal = Journal()
        journal.strengthSet(bench, "2026-08-05", at = "18:10", weightKg = 60.0, reps = 9)
        journal.strengthSet(bench, "2026-08-05", at = "18:15", weightKg = 60.0, reps = 8)
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("60 kg × 5 reps, 62.5 kg × 5 reps").performClick()
        settle()
        settle()

        compose.onNodeWithText("Last time (5 Aug): 60 kg × 9 reps, 60 kg × 8 reps").assertExists()
    }

    @Test
    fun `an exercise with no history at all says so instead of leaving the line blank`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("no sets yet").performClick()
        settle()
        settle()

        compose.onNodeWithText("No earlier set of this one.").assertExists()
    }

    /**
     * The bug the whole model exists to prevent: a set filed under today while the workout it
     * belongs to happened a fortnight ago. The journal is append-only, so there is no
     * correcting it afterwards.
     */
    @Test
    fun `a set is recorded under the workout's day, not under today`() {
        val journal = Journal()
        val past = "2026-06-01"
        val workout = journal.startWorkout(past, at = "18:05")
        journal.addExercise(workout, past, bench, restSec = 150)
        journal.strengthSet(bench, past, at = "18:10", weightKg = 60.0, reps = 5, workoutId = workout)
        show(journal, workout)

        compose.onNodeWithText("60 kg × 5 reps").performClick()
        settle()
        settle()
        compose.onNodeWithText("Repeat set").performClick()

        val set = logged.single() as StrengthSet
        assertEquals(past, set.opDate)
        assertEquals(5, set.reps)
        assertEquals(60.0, set.weightKg!!, 0.0001)
    }

    // --- putting an exercise into the workout ---------------------------------------------

    @Test
    fun `adding an exercise asks about the rest with last time's answer already in it`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")
        show(journal, workout)

        compose.onNodeWithText("Add exercise").performClick()
        settle()
        settle()
        compose.onNodeWithText("Abs").performClick()
        settle()

        compose.onNodeWithText("Rest between sets").assertExists()
        // the catalog remembers 90 s for this one, so agreeing costs exactly one tap
        compose.onNodeWithText("90").assertExists()
        compose.onNodeWithText("That is 1:30.").assertExists()

        compose.onNodeWithText("Add to workout").performClick()
        assertEquals(listOf(2L to 90), added)
    }

    /**
     * Changing the rest is the same write as adding the exercise — in an append-only journal
     * they are one event, and the last rest wins.
     */
    @Test
    fun `the rest is changed from the card, offering the one the workout already has`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("rest 2:30").performClick()
        settle()

        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithText("150").assertExists()
        assertTrue(
            "the dialog must name the exercise whose rest is being changed",
            compose.onAllNodesWithText("Bench press").fetchSemanticsNodes().size >= 2,
        )

        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(1L to 150), added)
    }

    // --- finishing --------------------------------------------------------------------------

    @Test
    fun `finishing is a button, and it is not the way out of the screen`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Finish").performClick()

        assertEquals(1, finishes)
        // leaving and finishing are two different things and two different controls
        assertEquals(0, closed)
    }

    /**
     * The end is READ OFF THE LAST SET, not stamped when the button was pressed — the button
     * is pressed in the changing room, the training stopped at the last set. So a finished
     * workout can be opened again and written into, and the end moves by itself.
     */
    @Test
    fun `a finished workout says when it ended, counted from its last set`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, bench, restSec = 150)
        journal.strengthSet(bench, iso, at = "18:10", workoutId = workout)
        journal.strengthSet(bench, iso, at = "19:42", weightKg = 62.5, workoutId = workout)
        journal.finishWorkout(workout, iso, at = "19:55")
        show(journal, workout)

        compose.onNodeWithText("Fri 7 Aug - 1 exercise, 2 sets - finished 19:42").assertIsDisplayed()
        // the button says the state rather than offering it again
        compose.onNodeWithText("Finished").assertIsDisplayed()
    }

    /** And an unfinished one says nothing about an end it has not reached. */
    @Test
    fun `an unfinished workout has no end time in its heading`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Fri 7 Aug - 2 exercises, 2 sets").assertIsDisplayed()
        compose.onNodeWithText("Finish").assertIsDisplayed()
    }

    // --- what matured while the set was running ---------------------------------------------

    /**
     * The half of §13.4 that was missing: the summary was computed and spoken, and appeared
     * on no screen. Every rest is silenced while a protocol runs — a beep in the middle of a
     * seven-second hang is exactly what must not happen — so a set that took two minutes can
     * end with several rests having come due unannounced, and this line is the only thing
     * that says which.
     */
    @Test
    fun `the readiness summary from a finished set is shown`() {
        val journal = Journal()
        show(
            journal, supersetWorkout(journal),
            readySummary = "Bench press has been ready for 1:20, Abs for 0:40",
        )

        compose.onNodeWithText("Bench press has been ready for 1:20, Abs for 0:40")
            .assertIsDisplayed()

        compose.onNodeWithText("Got it").performClick()
        assertEquals(1, summaryDismissed)
    }

    @Test
    fun `no summary means no banner, rather than an empty one`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onAllNodesWithText("Got it").assertCountEquals(0)
    }

    // --- the protocol-led set ---------------------------------------------------------------

    /** Bench, plus a hangboard exercise that is run by its protocol. */
    private fun hangWorkout(journal: Journal): Long {
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, bench, restSec = 150)
        journal.addExercise(workout, iso, hangs, restSec = 240, at = "18:06")
        return workout
    }

    /**
     * The whole point of the card being the tap target for two different things: for a hang
     * the app is not taking a report, it is about to call out seven seconds on and three off,
     * and a form asking for numbers that do not exist yet is in the way.
     */
    @Test
    fun `tapping a protocol-led card starts the set instead of raising the form`() {
        val journal = Journal()
        show(journal, hangWorkout(journal))

        compose.onNodeWithText("Hangs 20 mm").performClick()
        settle()

        assertEquals(listOf("Hangs 20 mm" to null), started)
        // no form and no question: a bodyweight protocol used to start with one tap and
        // still does
        compose.onAllNodesWithText("Added weight").assertCountEquals(0)
        compose.onAllNodesWithText("Repeat set").assertCountEquals(0)
    }

    /** And the ordinary exercise on the same screen still gets the form it always had. */
    @Test
    fun `a card that is not protocol-led still raises the entry form`() {
        val journal = Journal()
        show(journal, hangWorkout(journal))

        compose.onNodeWithText("Bench press").performClick()
        settle()
        settle()

        compose.onNodeWithText("Weight, kg").assertExists()
        assertEquals(emptyList<Pair<String, Double?>>(), started)
    }

    /**
     * The plate goes on before you get under the cable, so before the set is when the app can
     * ask (§13.5). Prefilled with what was hung last time, so agreeing is one tap.
     */
    @Test
    fun `the weight is asked on the way in when the last set carried one`() {
        val journal = Journal()
        journal.holdSet(hangs, "2026-08-05", addedKg = 15.0)
        show(journal, hangWorkout(journal))

        compose.onNodeWithText("Hangs 20 mm").performClick()
        settle()

        compose.onNodeWithText("Added weight").assertExists()
        compose.onNodeWithText("15").assertExists()
        // nothing has started yet: the question is in front of the set, not beside it
        assertEquals(emptyList<Pair<String, Double?>>(), started)

        compose.onNodeWithText("Start the set").performClick()
        assertEquals(listOf("Hangs 20 mm" to 15.0), started)
    }

    /**
     * The oversight §13.5 names explicitly: asking unconditionally would put a screen in
     * front of every bodyweight protocol, where there used to be none at all.
     */
    @Test
    fun `no weight is asked when the last set carried none`() {
        val journal = Journal()
        journal.holdSet(hangs, "2026-08-05", addedKg = null)
        show(journal, hangWorkout(journal))

        compose.onNodeWithText("Hangs 20 mm").performClick()
        settle()

        compose.onAllNodesWithText("Added weight").assertCountEquals(0)
        assertEquals(listOf("Hangs 20 mm" to null), started)
    }

    /**
     * Leaving the conductor does not stop the set, so the card has to be both the sign that
     * it is still running and the way back to it — otherwise a set left behind is a set with
     * no route to its own screen.
     */
    @Test
    fun `a card whose set is running says so and leads back to it`() {
        val journal = Journal()
        show(journal, hangWorkout(journal), liveExerciseId = 3L)

        compose.onNodeWithText("Set running - tap to go back to it").assertIsDisplayed()

        compose.onNodeWithText("Hangs 20 mm").performClick()
        settle()

        assertEquals(1, conductorOpened)
        // and nothing was started a second time on top of the set already running
        assertEquals(emptyList<Pair<String, Double?>>(), started)
    }

    // --- taking a set back ------------------------------------------------------------------

    @Test
    fun `undo reaches the last set of this workout`() {
        val journal = Journal()
        val workout = supersetWorkout(journal)
        show(journal, workout)

        compose.onNodeWithText("Undo last").performClick()

        // rows 1..3 opened the workout and put its two exercises in it, so 5 is the second
        // bench set and the newest thing the workout wrote
        assertEquals(listOf(5L), undone)
        assertEquals(0, closed)
    }
}
