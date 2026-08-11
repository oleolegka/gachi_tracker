package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.DraftCard
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.OrderedCard
import xyz.oleolegka.gachimuchi.domain.draftWorkout
import xyz.oleolegka.gachimuchi.domain.RestFloor
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.exerciseRef
import xyz.oleolegka.gachimuchi.ui.protocolProgram
import xyz.oleolegka.gachimuchi.ui.protocolProgramIdFor

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

    /** A fingerboard hang: one hand at a time, so every set of it owes a side. */
    private val oneArm = exerciseRef(4, "One-arm hang 20 mm", ExerciseForm.HOLD)

    /** The other kind of hold, hung off both hands — the control for the side question. */
    private val twoArm = exerciseRef(5, "Both-arm hang 20 mm", ExerciseForm.HOLD)

    /**
     * A hangboard exercise, which is what a protocol-led one is in practice: the 7:3 protocol
     * is part of its identity (§12-A), so it is on the catalog row rather than asked per set.
     */
    private val hangs = ExerciseRef(
        id = 3, name = "Hangs", form = ExerciseForm.HOLD,
        workSec = 7.0, restSec = 3.0,
    )

    /**
     * The case this file's protocol-and-side tests exist for: a hangboard hang trained one arm
     * at a time. Both flags at once, which is the owner's actual routine — see the note on
     * `holdSetsFromRun` in domain/RunLog.kt.
     */
    private val oneArmHangs = ExerciseRef(
        id = 6, name = "One-arm hangs", form = ExerciseForm.HOLD,
        workSec = 7.0, restSec = 3.0, oneSided = true,
    )

    /**
     * The catalog carries a chosen rest for each, which is what the "add an exercise" question
     * is prefilled from. Set here rather than left to be measured out of the journal so the
     * number under test is a fact of the fixture and not of the gaps between its timestamps.
     */
    private val catalog = listOf(
        exerciseEntity(1, "Bench press").copy(defaultRestSec = 150),
        exerciseEntity(2, "Abs").copy(defaultRestSec = 90),
        exerciseEntity(3, "Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
            .copy(defaultRestSec = 240),
        // the flag lives on the catalog row; the side it makes the card ask for lives on the set
        exerciseEntity(4, "One-arm hang 20 mm", ExerciseForm.HOLD)
            .copy(defaultRestSec = 180, oneSided = true),
        exerciseEntity(5, "Both-arm hang 20 mm", ExerciseForm.HOLD).copy(defaultRestSec = 180),
        exerciseEntity(6, "One-arm hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
            .copy(defaultRestSec = 180, oneSided = true),
    )

    /** Which exercise, at which rest, for which card — the third element is the side asked for. */
    private val added = mutableListOf<Triple<Long, Int, HoldSide?>>()
    private val logged = mutableListOf<ActivityForm>()
    private val undone = mutableListOf<Long>()
    /** The rows an exercise removed from the workout took with it. */
    private val removedRows = mutableListOf<List<Long>>()
    /** Which exercise and side each removal named, alongside [removedRows]. */
    private val removedFor = mutableListOf<Pair<Long?, HoldSide?>>()
    /** Every order this screen has stated, whole, in the order it stated them. */
    private val reordered = mutableListOf<List<OrderedCard>>()
    /** Which exercise, at which plate, for which hand — the third element is the card's own side. */
    private val started = mutableListOf<Triple<String, Double?, HoldSide?>>()
    private var conductorOpened = 0
    private var summaryDismissed = 0
    private var finishes = 0
    private var closed = 0

    /** Cards marked done, and the finish events undone, so tests can assert on either. */
    private val finishedCards = mutableListOf<Pair<String?, HoldSide?>>()
    private val unfinished = mutableListOf<Long>()
    private val unfinishedWorkout = mutableListOf<Long>()

    /** A monotonic instant the floors are placed around, so no test races a real clock. */
    private val now = 1_000_000L

    private fun show(
        journal: Journal,
        workoutId: Long,
        floors: List<RestFloor> = emptyList(),
        liveExerciseId: Long? = null,
        readySummary: String? = null,
    ) {
        val state = UiState(
            events = journal.events,
            exercises = catalog,
            programsById = mapOf(
                protocolProgramIdFor(3) to protocolProgram(3, "Hangs", 7.0, 3.0),
                protocolProgramIdFor(6) to protocolProgram(6, "One-arm hangs", 7.0, 3.0),
            ),
            loading = false,
        )
        screen {
            WorkoutLogScreen(
                state = state,
                workoutId = workoutId,
                settings = TimerSettings(),
                floors = floors,
                actions = WorkoutLogActions(
                    addExercise = { id, rest, side -> added += Triple(id, rest, side) },
                    createExercise = { _, _ -> },
                    addSet = { form -> logged += form },
                    undoSet = { id -> undone += id },
                    removeExercise = { ids, exerciseId, side -> removedRows += ids; removedFor += exerciseId to side },
                    reorderExercises = { order -> reordered += order },
                    finish = { finishes++ },
                    finishExercise = { exercise, side -> finishedCards += exercise.uid to side },
                    unfinishExercise = { eventId -> unfinished += eventId },
                    unfinishWorkout = { eventId -> unfinishedWorkout += eventId },
                    startProtocolSet = { exercise, kg, side -> started += Triple(exercise.name, kg, side) },
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

    /**
     * The draft path (§13.1): no workout in the journal at all, only staged cards the screen
     * is handed straight — there is no [Journal] behind this one on purpose, since a draft by
     * definition has written nothing yet.
     */
    private fun showDraft(cards: List<DraftCard> = emptyList()) {
        val state = UiState(exercises = catalog, loading = false)
        screen {
            WorkoutLogScreen(
                state = state,
                workoutId = null,
                draftWorkout = draftWorkout(iso, null, cards, state::linkOf),
                settings = TimerSettings(),
                floors = emptyList(),
                actions = WorkoutLogActions(
                    addExercise = { id, rest, side -> added += Triple(id, rest, side) },
                    createExercise = { _, _ -> },
                    addSet = { form -> logged += form },
                    undoSet = { id -> undone += id },
                    removeExercise = { ids, exerciseId, side -> removedRows += ids; removedFor += exerciseId to side },
                    reorderExercises = { order -> reordered += order },
                    finish = { finishes++ },
                    finishExercise = { exercise, side -> finishedCards += exercise.uid to side },
                    unfinishExercise = { eventId -> unfinished += eventId },
                    unfinishWorkout = { eventId -> unfinishedWorkout += eventId },
                    startProtocolSet = { exercise, kg, side -> started += Triple(exercise.name, kg, side) },
                    openConductor = { conductorOpened++ },
                    close = { closed++ },
                ),
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
        // a table now, one line per set, with the loads under each other
        compose.onNodeWithText("2 sets").assertIsDisplayed()
        compose.onNodeWithText("60 kg").assertIsDisplayed()
        compose.onNodeWithText("62.5 kg").assertIsDisplayed()
        /*
         * The empty card is the point of the whole model, not an edge case: exercises are put
         * into the workout on the way in, before a single set of them exists.
         */
        compose.onNodeWithText("Abs").assertIsDisplayed()
        compose.onNodeWithText("0 sets").assertIsDisplayed()
    }

    /**
     * The three things the set block does that the joined line could not: count the sets (asked
     * for outright — "нет общего какого-то счётчика 'сделано 5 сетов', а мы хотели"), say the
     * protocol ONCE instead of once per set, and mark the sets that are not ordinary working
     * ones. The warm-up is inside the count, because it is a set.
     */
    @Test
    fun `the card counts its sets, says the protocol once, and marks the two that are not ordinary`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, hangs, restSec = 240)
        journal.holdSet(hangs, iso, at = "18:10", addedKg = 5.0, warmup = true, workoutId = workout)
        journal.holdSet(hangs, iso, at = "18:14", addedKg = 7.5, workoutId = workout)
        journal.holdSet(hangs, iso, at = "18:18", addedKg = 7.5, incomplete = true, workoutId = workout)
        show(journal, workout)

        compose.onNodeWithText("3 sets \u00b7 7:3 protocol").assertIsDisplayed()
        compose.onNodeWithText("Warm-up").assertIsDisplayed()
        compose.onNodeWithText("Not completed").assertIsDisplayed()
        // and the load stands in a column of its own: the ramp-up hang at +5, the working
        // ones at +7.5 (two rows, not collapsed into one — the second fell short)
        compose.onNodeWithText("+5 kg").assertIsDisplayed()
        compose.onNodeWithText("+7.5 kg").assertIsDisplayed()
    }

    @Test
    fun `each card names the rest chosen for it, and that is the control that changes it`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Rest 2:30").assertIsDisplayed()
        compose.onNodeWithText("Rest 1:30").assertIsDisplayed()
    }

    @Test
    fun `the header counts what is in the workout`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Fri 7 Aug · 2 exercises, 2 sets").assertIsDisplayed()
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

        compose.onNodeWithText("No exercises in this workout yet.").assertIsDisplayed()
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

        compose.onNodeWithText("Rest left").assertIsDisplayed()
        compose.onNodeWithText("1:14").assertIsDisplayed()
        // the denominator is on the line too, in the same m:ss as the button above it
        compose.onNodeWithText("of 2:30").assertIsDisplayed()
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

        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("+2:30").assertIsDisplayed()
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

        compose.onNodeWithText("1:14").assertIsDisplayed()
        compose.onNodeWithText("+0:30").assertIsDisplayed()
    }

    // --- the quick form -------------------------------------------------------------------

    @Test
    fun `tapping a card raises the form, prefilled from the last set of that exercise`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performClick()
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

        compose.onNodeWithText("Bench press").performClick()
        settle()
        settle()

        compose.onNodeWithText("Last time (5 Aug): 60 kg × 9 reps, 60 kg × 8 reps").assertExists()
    }

    @Test
    fun `an exercise with no history at all says so instead of leaving the line blank`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Abs").performClick()
        settle()
        settle()

        compose.onNodeWithText("No earlier set of this one.").assertExists()
    }

    /**
     * The whole of what "finished" is for, in one assertion: the card stops being a way to log
     * anything. The owner asked for it so a card "gets in the way less and cannot be tapped
     * again by accident", and a card that still opened the entry form while looking done would
     * be worse than one that never collapsed at all.
     */
    @Test
    fun `a finished card is collapsed and cannot be logged into`() {
        val journal = Journal()
        val workout = supersetWorkout(journal)
        journal.finishCard(workout, iso, bench, at = "18:30")

        show(journal, workout)

        // the set line is what "collapsed" takes away, and bench had two sets on it
        compose.onNodeWithText("60 kg").assertDoesNotExist()
        compose.onNodeWithText("2 sets").assertDoesNotExist()
        // the other card is untouched: one card finishing is not the workout finishing
        compose.onNodeWithText("0 sets").assertExists()
        // and the name is still there to be found, and to be put back from
        compose.onNodeWithText("Bench press").assertExists()
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

        compose.onNodeWithText("Bench press").performClick()
        settle()
        settle()
        compose.onNodeWithText("Repeat set").performClick()

        val set = logged.single() as StrengthSet
        assertEquals(past, set.opDate)
        assertEquals(5, set.reps)
        assertEquals(60.0, set.weightKg!!, 0.0001)
    }

    // --- the warm-up flag -------------------------------------------------------------------

    /**
     * A warm-up counts towards neither volume nor records, so the flag has to reach the payload
     * — a chip that looks ticked and writes nothing is worse than no chip, because the set then
     * inflates the tonnage of a week that did not earn it.
     */
    @Test
    fun `ticking warm-up writes the flag onto the set`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performClick()
        settle()
        settle()

        compose.onNodeWithText("Warm-up").performClick()
        // the clock is held still (see ScreenTest), so the tick needs a frame to reach the tree
        settle()

        // no longer the same set as the last one, and the button stops claiming it is
        compose.onNodeWithText("Add set").performClick()

        assertTrue("the set must be marked as a warm-up", (logged.single() as StrengthSet).warmup)
    }

    /**
     * The other half, and the one that matters more: the ordinary working set is still two taps
     * and is still NOT a warm-up. A card arriving pre-ticked would quietly file working sets as
     * ramp-ups, which is how a set ends up in the journal and missing from every number.
     */
    @Test
    fun `a working set stays two taps and is not marked as a warm-up`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performClick()
        settle()
        settle()

        compose.onNodeWithText("Warm-up").assertExists()
        compose.onNodeWithText("Repeat set").performClick()

        assertFalse("an untouched card must record a working set", (logged.single() as StrengthSet).warmup)
    }

    // --- the "not completed" flag ------------------------------------------------------------

    /**
     * The owner's own example: the weight did not change, but the lifter did not carry the
     * set through. The flag has to reach the payload for the same reason the warm-up flag
     * does — a chip that looks ticked and writes nothing is worse than no chip.
     */
    @Test
    fun `ticking not completed writes the flag onto the set`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performClick()
        settle()
        settle()

        compose.onNodeWithText("Not completed").performClick()
        settle()

        compose.onNodeWithText("Add set").performClick()

        assertTrue(
            "the set must be marked as not completed",
            (logged.single() as StrengthSet).incomplete,
        )
    }

    /** The other half: an untouched card still records a set that WAS carried through. */
    @Test
    fun `a working set stays two taps and is not marked as not completed`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performClick()
        settle()
        settle()

        compose.onNodeWithText("Not completed").assertExists()
        compose.onNodeWithText("Repeat set").performClick()

        assertFalse(
            "an untouched card must record a set that was carried through",
            (logged.single() as StrengthSet).incomplete,
        )
    }

    /**
     * The whole point of the flag, from the reading side: the "Last time" line the decision at
     * the bar is actually made on has to say which of the previous sets fell short, right next
     * to the numbers it fell short at — not just that SOMETHING that day did not go well.
     */
    @Test
    fun `the last-time line marks the set that was not completed`() {
        val journal = Journal()
        journal.strengthSet(bench, "2026-08-05", at = "18:10", weightKg = 60.0, reps = 9)
        journal.strengthSet(
            bench, "2026-08-05", at = "18:15", weightKg = 60.0, reps = 3, incomplete = true,
        )
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performClick()
        settle()
        settle()

        compose.onNodeWithText(
            "Last time (5 Aug): 60 kg × 9 reps, 60 kg × 3 reps (not completed)",
        ).assertExists()
    }

    /** A completed history says nothing extra — the marker earns its place, it is not decoration. */
    @Test
    fun `the last-time line stays plain when nothing was marked`() {
        val journal = Journal()
        journal.strengthSet(bench, "2026-08-05", at = "18:10", weightKg = 60.0, reps = 9)
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performClick()
        settle()
        settle()

        compose.onNodeWithText("Last time (5 Aug): 60 kg × 9 reps").assertExists()
        compose.onNodeWithText("not completed", substring = true).assertDoesNotExist()
    }

    // --- the two cards of a one-sided exercise ------------------------------------------------

    /**
     * Both cards of the one-sided exercise, exactly the shape the app itself produces the
     * moment such an exercise is added to a workout — see `ActivityRepository.addExerciseToWorkout`
     * called twice, once per [HoldSide]. An earlier LEFT-hand set is there too, so the numeric
     * fields prefill and the two cards can be told apart by whether the prefill still counts as
     * untouched (the left one, which matches) or not (the right one, which does not).
     */
    private fun twoCardWorkout(journal: Journal, exercise: ExerciseRef): Long {
        journal.holdSet(exercise, "2026-08-05", reps = 5, addedKg = 10.0, side = HoldSide.LEFT)
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, exercise, restSec = 180, side = HoldSide.LEFT)
        journal.addExercise(workout, iso, exercise, restSec = 180, side = HoldSide.RIGHT)
        return workout
    }

    /**
     * The owner's decision, written down: the catalog holds ONE exercise, and a workout that
     * carries it shows TWO cards — because the left hand's rest and the right hand's are two
     * different countdowns (domain/Floors.kt), and the card is what tells the two apart on
     * screen as well as in the timer.
     */
    @Test
    fun `an exercise trained one hand at a time gets two cards, not a side question on one`() {
        val journal = Journal()
        show(journal, twoCardWorkout(journal, oneArm))

        compose.onNodeWithText("One-arm hang 20 mm - Left").assertIsDisplayed()
        compose.onNodeWithText("One-arm hang 20 mm - Right").assertIsDisplayed()
    }

    /**
     * The card already answered which hand a set is about, so the form does not ask again — the
     * chip row this screen used to raise for a one-sided exercise is gone from here, because
     * there is nowhere left for the answer to come from but the card that was tapped.
     */
    @Test
    fun `tapping the left card writes a left-hand set without asking which hand`() {
        val journal = Journal()
        show(journal, twoCardWorkout(journal, oneArm))

        compose.onNodeWithText("One-arm hang 20 mm - Left").performClick()
        settle()
        settle()

        compose.onNodeWithText("Left").assertDoesNotExist()
        compose.onNodeWithText("Right").assertDoesNotExist()

        // the left-hand history prefills this card as untouched
        compose.onNodeWithText("Repeat set").performClick()
        assertEquals("left", (logged.single() as HoldSet).side)
    }

    /** The other card of the same exercise, writing the other hand — no chip here either. */
    @Test
    fun `tapping the right card writes a right-hand set without asking which hand`() {
        val journal = Journal()
        show(journal, twoCardWorkout(journal, oneArm))

        compose.onNodeWithText("One-arm hang 20 mm - Right").performClick()
        settle()
        settle()

        compose.onNodeWithText("Left").assertDoesNotExist()
        compose.onNodeWithText("Right").assertDoesNotExist()

        // the history on file is the LEFT hand's, so this card is not "the same set again"
        compose.onNodeWithText("Add set").performClick()
        assertEquals("right", (logged.single() as HoldSet).side)
    }

    /**
     * The gap this closes: a one-sided exercise that is ALSO run by its protocol still draws
     * two cards, and a tap on either used to start the identical run — one that could not say
     * which hand it had just counted six hangs for. Both taps go through the weight question
     * here (there is a LEFT-hand plate on file, and `lastAddedKg` is not side-aware — the same
     * one prefill limitation the reps count has, see [twoCardWorkout]), which is the more
     * involved of the two branches [WorkoutLogScreen] can start a protocol-led run from and
     * therefore the one most likely to have dropped the side on the way.
     */
    @Test
    fun `each card of a protocol-led one-sided exercise starts a run that knows its own hand`() {
        val journal = Journal()
        show(journal, twoCardWorkout(journal, oneArmHangs))

        compose.onNodeWithText("One-arm hangs - Left").performClick()
        settle()
        compose.onNodeWithText("Start the set").performClick()
        settle()

        compose.onNodeWithText("One-arm hangs - Right").performClick()
        settle()
        compose.onNodeWithText("Start the set").performClick()
        settle()

        assertEquals(
            listOf(
                Triple("One-arm hangs", 10.0, HoldSide.LEFT),
                Triple("One-arm hangs", 10.0, HoldSide.RIGHT),
            ),
            started,
        )
    }

    /**
     * The control, and it is the half that would be quietly broken by an over-eager fix: a hold
     * hung off both hands must stay a single card and must not be made to answer a question
     * that does not apply to it. A null side there is what "both hands" has always meant.
     */
    @Test
    fun `a two-handed hold still gets one card and is never asked which hand`() {
        val journal = Journal()
        journal.holdSet(twoArm, "2026-08-05", reps = 5, addedKg = 10.0)
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, twoArm, restSec = 180)
        show(journal, workout)

        compose.onNodeWithText("Both-arm hang 20 mm").performClick()
        settle()
        settle()

        compose.onNodeWithText("Left").assertDoesNotExist()
        compose.onNodeWithText("Right").assertDoesNotExist()

        compose.onNodeWithText("Repeat set").performClick()
        assertNull((logged.single() as HoldSet).side)
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
        // the catalog remembers 90 s for this one, so agreeing costs exactly one tap — typed
        // as mm:ss now, not bare seconds (§13.9)
        compose.onNodeWithText("Rest, mm:ss").assertExists()
        /*
         * ONCE, in the field. It used to be twice — a headline above repeating what the field
         * held, in another size — and this count was what pinned that down. Reported from the
         * phone, 2026-08-11: "the time is written twice, in two different fonts".
         */
        compose.onAllNodesWithText("1:30").assertCountEquals(1)

        compose.onNodeWithText("Add to workout").performClick()
        assertEquals(listOf(Triple(2L, 90, null)), added)
    }

    /**
     * Changing the rest is the same write as adding the exercise — in an append-only journal
     * they are one event, and the last rest wins.
     */
    @Test
    fun `the rest is changed from the card, offering the one the workout already has`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Rest 2:30").performClick()
        settle()

        compose.onNodeWithText("Save").assertExists()
        // once, in the field — see the note on the sibling test above
        compose.onAllNodesWithText("2:30").assertCountEquals(1)
        assertTrue(
            "the dialog must name the exercise whose rest is being changed",
            compose.onAllNodesWithText("Bench press").fetchSemanticsNodes().size >= 2,
        )

        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(Triple(1L, 150, null)), added)
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

        compose.onNodeWithText("Fri 7 Aug · 1 exercise, 2 sets · finished 19:42").assertIsDisplayed()
        // the button offers the way back rather than a dead label — see "Reopen" below (§13)
        compose.onNodeWithText("Reopen").assertIsDisplayed()
    }

    /** And an unfinished one says nothing about an end it has not reached. */
    @Test
    fun `an unfinished workout has no end time in its heading`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Fri 7 Aug · 2 exercises, 2 sets").assertIsDisplayed()
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

        compose.onNodeWithText("Hangs").performClick()
        settle()

        assertEquals(listOf(Triple("Hangs", null, null)), started)
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
        assertEquals(emptyList<Triple<String, Double?, HoldSide?>>(), started)
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

        compose.onNodeWithText("Hangs").performClick()
        settle()

        compose.onNodeWithText("Added weight").assertExists()
        compose.onNodeWithText("15").assertExists()
        // nothing has started yet: the question is in front of the set, not beside it
        assertEquals(emptyList<Triple<String, Double?, HoldSide?>>(), started)

        compose.onNodeWithText("Start the set").performClick()
        assertEquals(listOf(Triple("Hangs", 15.0, null)), started)
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

        compose.onNodeWithText("Hangs").performClick()
        settle()

        compose.onAllNodesWithText("Added weight").assertCountEquals(0)
        assertEquals(listOf(Triple("Hangs", null, null)), started)
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

        compose.onNodeWithText("Set running · tap to go back to it").assertIsDisplayed()

        compose.onNodeWithText("Hangs").performClick()
        settle()

        assertEquals(1, conductorOpened)
        // and nothing was started a second time on top of the set already running
        assertEquals(emptyList<Triple<String, Double?, HoldSide?>>(), started)
    }

    // --- taking a set back ------------------------------------------------------------------

    @Test
    fun `undo reaches the last set of this workout`() {
        val journal = Journal()
        val workout = supersetWorkout(journal)
        show(journal, workout)

        // it lives in the bar's menu now: pressed once a session, and a mis-tap cancels a
        // set that really happened
        compose.onNodeWithContentDescription("Actions for this workout").performClick()
        settle()
        compose.onNodeWithText("Undo last set").performClick()

        // rows 1..3 opened the workout and put its two exercises in it, so 5 is the second
        // bench set and the newest thing the workout wrote
        assertEquals(listOf(5L), undone)
        assertEquals(0, closed)
    }

    // --- taking an exercise out of the workout (14.1) --------------------------------------

    /**
     * The rows a removal has to name: the "added" event AND every set of it.
     *
     * Removing only the "added" row would put the card straight back - `buildWorkout` admits an
     * exercise on the first set of it as readily as on an explicit add, because a set logged
     * under an exercise nobody added is still training that happened.
     *
     * In [supersetWorkout] the journal is: 1 start, 2 bench added, 3 abs added, 4 and 5 the two
     * bench sets. So bench is rows 2, 4 and 5.
     */
    @Test
    fun `a long press on an exercise card removes it with every set under it`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Remove from this workout").assertDoesNotExist()

        compose.onNodeWithText("Bench press").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Remove from this workout").performClick()
        settle()

        compose.onNodeWithText("Remove this exercise from the workout?").assertExists()
        compose.onNodeWithText(
            "Its 2 sets go with it and stop counting towards volume, records and the streak. " +
                "The journal keeps the original rows - removing is itself an entry, so this " +
                "can be undone later rather than being the end of the evidence."
        ).assertExists()
        assertTrue("nothing may be written before the question is answered", removedRows.isEmpty())

        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(listOf(2L, 4L, 5L)), removedRows)
        // named by exercise and side too — see the next test for what that is for
        assertEquals(listOf(bench.id to null), removedFor)
    }

    /**
     * A second type of row a card can own was added ([TYPE_WORKOUT_EXERCISE_FINISHED]) and it
     * was left out of the removal here for a day before being caught — the exact class of bug
     * this pins: a new kind of event a card can carry has to be added to the removal by hand,
     * and nothing stops a THIRD one from being forgotten the same way. Its own row, [addedRows]
     * and the SETS are the three sources today; this test fails the moment any one of the three
     * is dropped from the sum.
     */
    @Test
    fun `removing a finished card takes its own finish row with it, or a ghost is left behind`() {
        val journal = Journal()
        val workout = supersetWorkout(journal)
        val done = journal.finishCard(workout, iso, bench, at = "18:20")
        show(journal, workout)

        compose.onNodeWithText("Bench press").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Remove from this workout").performClick()
        settle()
        compose.onNodeWithText("Remove").performClick()

        // 2 = added, 4 and 5 = the two sets, and `done` = the "card finished" row itself
        assertEquals(listOf(listOf(2L, 4L, 5L, done)), removedRows)
    }

    /** An exercise added and never done takes only its own row, and says so. */
    @Test
    fun `removing an exercise with no sets says nothing stops counting`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Abs").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Remove from this workout").performClick()
        settle()

        compose.onNodeWithText(
            "Nothing has been recorded under it yet, so nothing stops counting. The journal " +
                "keeps the original rows - removing is itself an entry, so this can be undone " +
                "later rather than being the end of the evidence."
        ).assertExists()

        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(listOf(3L)), removedRows)
    }

    /** The tap still logs: the gesture was added beside the card's action, not over it. */
    @Test
    fun `a tap on a card still raises the entry form after the gesture was added`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performClick()
        settle()

        compose.onNodeWithText("Remove from this workout").assertDoesNotExist()
        compose.onNodeWithText("Repeat set").assertExists()
    }

    // --- rearranging the cards ------------------------------------------------------------

    /** Which card is drawn above which, read off the layout rather than off the fixture. */
    private fun cardsTopDown(vararg names: String): List<String> =
        names.sortedBy { compose.onNodeWithText(it).getBoundsInRoot().top.value }

    /**
     * The screen draws the order the JOURNAL folds to, not the order the exercises were added
     * in. The fold is [xyz.oleolegka.gachimuchi.domain.buildWorkout]'s business and is tested
     * there; what this proves is that the screen goes through it rather than arranging for
     * itself — the failure it would catch is a screen that sorts, and then disagrees with the
     * workout review screen next door about the same workout.
     */
    @Test
    fun `the cards are drawn in the order the journal states`() {
        val journal = Journal()
        val workout = supersetWorkout(journal)
        journal.setExerciseOrder(workout, iso, abs, bench, at = "18:20")
        show(journal, workout)

        assertEquals(listOf("Abs", "Bench press"), cardsTopDown("Bench press", "Abs"))
    }

    /**
     * The written way to move a card, which is also the only way that works with a screen
     * reader. What it states is the WHOLE order — see `TYPE_WORKOUT_ORDER_SET` — so the
     * assertion is an arrangement and not a move.
     */
    @Test
    fun `the menu moves a card one place and states the whole order`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Abs").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Move up").performClick()
        settle()

        assertEquals(listOf(listOf(OrderedCard(abs.link), OrderedCard(bench.link))), reordered)
    }

    @Test
    fun `moving the other card the other way states the same arrangement`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Move down").performClick()
        settle()

        assertEquals(listOf(listOf(OrderedCard(abs.link), OrderedCard(bench.link))), reordered)
    }

    /** No move that does not exist is offered: the top card cannot go up. */
    @Test
    fun `the ends of the list offer only the move they have`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Move down").assertExists()
        compose.onNodeWithText("Move up").assertDoesNotExist()
    }

    /**
     * A workout with one exercise has no order to state, so nothing about moving appears — and
     * removal still does, which is the action that was on this menu before any of this.
     */
    @Test
    fun `an only exercise is offered no move and is still removable`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.addExercise(workout, iso, bench, restSec = 150)
        show(journal, workout)

        compose.onNodeWithText("Bench press").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Move up").assertDoesNotExist()
        compose.onNodeWithText("Move down").assertDoesNotExist()
        compose.onNodeWithText("Remove from this workout").assertExists()
    }

    /**
     * THE DRAG ITSELF, as far as it can honestly be driven here: a long press, then a finger
     * travelling down past the card below it, then a release.
     *
     * ── What this does not prove ────────────────────────────────────────────────
     * The frame clock is frozen for the reason in [ScreenTest], so NO FRAME IS DRAWN between the
     * injected pointer events: the list never re-lays out under the finger, and the card in hand
     * is never actually seen to move. One swap is therefore the most that can be exercised, and
     * the arithmetic that decides the second and later swaps of a long drag — which reads
     * positions only a real frame updates — is not covered by anything in this suite. Neither is
     * the shadow under a lifted card, nor the gap opening behind it, nor autoscrolling (which is
     * not implemented at all — see `ReorderState`).
     */
    @Test
    fun `dragging a card past the one below it states the new order`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performTouchInput {
            down(center)
            // past the long press, which is what turns this into a carry rather than a tap
            advanceEventTime(1_000)
            moveBy(Offset(0f, 120f))
            moveBy(Offset(0f, 120f))
            moveBy(Offset(0f, 120f))
            moveBy(Offset(0f, 120f))
            up()
        }
        settle()

        assertEquals(listOf(listOf(OrderedCard(abs.link), OrderedCard(bench.link))), reordered)
        // and the cards follow, because the screen redraws off the order it has just stated
        assertEquals(listOf("Abs", "Bench press"), cardsTopDown("Bench press", "Abs"))
    }

    /**
     * The other half of the gesture split, stated rather than left to the removal tests: a press
     * that goes nowhere is the menu and is not a reordering.
     */
    @Test
    fun `a long press that does not move raises the menu and writes no order`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Bench press").performTouchInput { longClick() }
        settle()

        compose.onNodeWithText("Remove from this workout").assertExists()
        assertTrue("a press that never moved is not a reordering", reordered.isEmpty())
    }

    // --- reopening a workout that was finished too soon (§13) -------------------------------

    @Test
    fun `finishing offers to undo it, right where the button was`() {
        val journal = Journal()
        show(journal, supersetWorkout(journal))

        compose.onNodeWithText("Finish").performClick()
        assertEquals(1, finishes)
    }

    @Test
    fun `a finished workout offers Reopen instead of Finish, and names the finish event`() {
        val journal = Journal()
        val workout = supersetWorkout(journal)
        val done = journal.finishWorkout(workout, iso, at = "19:00")
        show(journal, workout)

        compose.onNodeWithText("Finish").assertDoesNotExist()
        compose.onNodeWithText("Reopen").performClick()

        assertEquals(listOf(done), unfinishedWorkout)
    }

    // --- the workout that has not started yet (§13.1) ----------------------------------------
    //
    // No [Journal] behind any of these: a draft is exactly the state of having written
    // nothing at all, which is the whole point of it existing.

    @Test
    fun `a draft offers to start the workout, not to finish it`() {
        showDraft()

        compose.onNodeWithText("Start workout").assertIsDisplayed()
        compose.onNodeWithText("Finish").assertDoesNotExist()
        compose.onNodeWithText("Reopen").assertDoesNotExist()
    }

    /**
     * A staged card draws exactly like a real one — this is [draftWorkout] doing its job of
     * shaping the draft as a [xyz.oleolegka.gachimuchi.domain.Workout] the screen already knows
     * how to draw, rather than a second layout this file would have to keep in step with the
     * first one.
     */
    @Test
    fun `staged cards are drawn with the rest they were given`() {
        showDraft(listOf(DraftCard(bench.id, 150), DraftCard(abs.id, 90)))

        compose.onNodeWithText("Bench press").assertIsDisplayed()
        compose.onNodeWithText("Rest 2:30").assertIsDisplayed()
        compose.onNodeWithText("Abs").assertIsDisplayed()
        compose.onNodeWithText("Rest 1:30").assertIsDisplayed()
    }

    /**
     * THE explicit button §13.1 asks for. It sits where "Finish" always has — see
     * [WorkoutLogActions.finish] — because turning a draft into a real workout and closing a
     * real one are exactly the two things this one slot on the top bar has ever done, one at a
     * time, never both at once.
     */
    @Test
    fun `the button that starts a draft is the same slot Finish always was`() {
        showDraft(listOf(DraftCard(bench.id, 150)))

        compose.onNodeWithText("Start workout").performClick()
        assertEquals(1, finishes)
    }

    @Test
    fun `a staged card cannot be marked done - there is no workout yet to finish it in`() {
        showDraft(listOf(DraftCard(bench.id, 150)))

        compose.onNodeWithText("Bench press").performTouchInput { longClick() }
        settle()

        compose.onNodeWithText("Mark as done").assertDoesNotExist()
        compose.onNodeWithText("Remove from this workout").assertExists()
    }

    /** Removing a staged card has no rows to name — there is nothing in the journal yet. */
    @Test
    fun `removing a staged card names it by exercise, not by rows it never got`() {
        showDraft(listOf(DraftCard(bench.id, 150), DraftCard(abs.id, 90)))

        compose.onNodeWithText("Bench press").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Remove from this workout").performClick()
        settle()
        compose.onNodeWithText("Remove").performClick()

        assertEquals(listOf(emptyList<Long>()), removedRows)
        assertEquals(listOf(bench.id to null), removedFor)
    }
}
