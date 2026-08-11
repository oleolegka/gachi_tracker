package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.DraftCard
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.looseWorkout
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * Recording with NO workout around it — "Add - single entry" on a day.
 *
 * ── What this file is guarding, and against what ────────────────────────────────
 * There used to be a second screen for this case, and it asked a different set of questions in
 * a different order: no rest between sets, a prefilled "Repeat set" form the instant an
 * exercise was chosen, and a protocol-led set started from a button on a timer bar rather than
 * from the card. Three of those were reported from the phone as one bug, and the class of the
 * bug — two paths to one action, one of them fixed — has now happened three times in this app.
 *
 * So these tests are deliberately written as "the same as inside a workout": every assertion
 * here has a twin in [WorkoutLogScreenTest], and the point of the file is that both twins pass
 * against ONE screen. What makes that structural rather than a promise is that a single entry
 * no longer has a screen of its own to drift on — it is the same composable over a container
 * built by `looseWorkout`, and the questions live in the composable.
 *
 * The window is 600 dp wide for the reason [WorkoutLogScreenTest] gives: the picker and the
 * rest dialog carry text fields, and at phone width a text field inside a sheet never lets the
 * composition settle. Same gap, stated the same way — nothing here is seen at 411 dp.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class StandaloneEntryTest : ScreenTest() {

    private val iso = "2026-08-07"

    private val stretching = exerciseRef(11, "Evening stretching")
    private val oneArm = exerciseRef(12, "Loose one-arm hang", ExerciseForm.HOLD)

    private val catalog = listOf(
        exerciseEntity(11, "Evening stretching").copy(defaultRestSec = 90),
        exerciseEntity(12, "Loose one-arm hang", ExerciseForm.HOLD)
            .copy(defaultRestSec = 180, oneSided = true),
    )

    private val added = mutableListOf<Triple<Long, Int, HoldSide?>>()
    private val logged = mutableListOf<ActivityForm>()
    private val removedRows = mutableListOf<List<Long>>()
    private val reordered = mutableListOf<Int>()
    private var finishes = 0

    private fun show(journal: Journal = Journal(), staged: List<DraftCard> = emptyList()) {
        val state = UiState(events = journal.events, exercises = catalog, loading = false)
        screen {
            WorkoutLogScreen(
                state = state,
                workoutId = null,
                draftWorkout = looseWorkout(state.events, iso, staged, state::linkOf),
                standalone = true,
                settings = TimerSettings(),
                floors = emptyList(),
                actions = WorkoutLogActions(
                    // the plan is not part of what a standalone card can hold: there is no
                    // workout event to write it on, so this screen drops it (see GachiApp)
                    addExercise = { id, rest, side, _ -> added += Triple(id, rest, side) },
                    createExercise = { _, _ -> },
                    addSet = { form -> logged += form },
                    undoSet = {},
                    removeExercise = { ids, _, _ -> removedRows += ids },
                    reorderExercises = { reordered += it.size },
                    finish = { finishes++ },
                    finishExercise = { _, _ -> },
                    unfinishExercise = {},
                    unfinishWorkout = {},
                    startProtocolSet = { _ -> },
                    openConductor = {},
                    close = {},
                ),
                nowMs = 1_000_000L,
            )
        }
    }

    // --- the questions, which are the whole point -------------------------------------------

    /**
     * THE REPORTED BUG. Choosing an exercise for a single entry used to drop a prefilled form
     * on the screen with "Repeat set" on its button; the rest between sets was never asked at
     * all, and there was nowhere to state it afterwards either.
     */
    @Test
    fun `admitting an exercise asks the rest between sets, exactly as a workout does`() {
        show()

        compose.onNodeWithText("Add exercise").performClick()
        settle()
        settle()
        compose.onNodeWithText("Evening stretching").performClick()
        settle()

        // the same dialog, said with the same words as inside a workout
        compose.onNodeWithText("Rest between sets").assertExists()
        compose.onNodeWithText("Rest, mm:ss").assertExists()
        // prefilled from the catalog's remembered answer, so agreeing costs one tap
        compose.onNodeWithText("1:30").assertExists()

        // "Add", not "Add to workout": there is no workout, and that is the only word that differs
        compose.onNodeWithText("Add").performClick()
        assertEquals(listOf(Triple(11L, 90, null)), added)
    }

    /**
     * An exercise trained one limb at a time gets TWO cards here as well, at one answer to one
     * question. Inside a workout this is what makes the side a fact of the card instead of a
     * chip row on the form — and the standalone screen used to be the one place that asked with
     * a chip row, and asked again on every single set.
     */
    @Test
    fun `a one-sided exercise admitted on its own gets both of its cards`() {
        show()

        compose.onNodeWithText("Add exercise").performClick()
        settle()
        settle()
        compose.onNodeWithText("Loose one-arm hang").performClick()
        settle()
        compose.onNodeWithText("Add").performClick()

        assertEquals(
            listOf(Triple(12L, 180, HoldSide.LEFT), Triple(12L, 180, HoldSide.RIGHT)),
            added,
        )
    }

    /**
     * An exercise admitted and not yet done is a CARD, not a form. The old screen had no such
     * state at all: picking an exercise was the same act as being asked to record a set of it,
     * which is why the owner's report says "мне сразу говорят внести сет".
     */
    @Test
    fun `a staged exercise is an empty card, and the form only comes on tapping it`() {
        show(staged = listOf(DraftCard(11, 90)))

        compose.onNodeWithText("Evening stretching").assertIsDisplayed()
        // the chosen rest is on the card, where it can be changed — see RestDialog's "Save"
        compose.onNodeWithText("Rest 1:30").assertExists()
        // nothing is being asked for yet
        compose.onNodeWithText("Repeat set").assertDoesNotExist()
        assertTrue(logged.isEmpty())

        compose.onNodeWithText("Evening stretching").performClick()
        settle()
        // the same sheet a workout card raises, with the same line above the fields
        compose.onNodeWithText("No earlier set of this one.").assertExists()
    }

    // --- what a container with no workout does not have --------------------------------------

    /**
     * No "Start workout" and no "Finish". The state button is one slot with three labels, and
     * all three are statements about a workout — there is none here to make them about.
     */
    @Test
    fun `there is nothing to start and nothing to finish`() {
        show(staged = listOf(DraftCard(11, 90)))

        compose.onNodeWithText("Start workout").assertDoesNotExist()
        compose.onNodeWithText("Finish").assertDoesNotExist()
        compose.onNodeWithText("Reopen").assertDoesNotExist()
        assertEquals(0, finishes)
        // the screen still says what it is
        compose.onNodeWithText("Single entry").assertIsDisplayed()
    }

    /**
     * The cards cannot be rearranged, and the menu does not pretend otherwise. An order is a row
     * in the journal (`TYPE_WORKOUT_ORDER_SET`) and the row names a workout, so a move here
     * would be a gesture that appears to work and lands nowhere — the failure ItemDrag's own
     * KDoc refuses for a one-card workout, for the same reason.
     */
    @Test
    fun `the cards cannot be reordered, because there is no row to write an order into`() {
        show(staged = listOf(DraftCard(11, 90), DraftCard(12, 180, HoldSide.LEFT)))

        compose.onNodeWithText("Move up").assertDoesNotExist()
        compose.onNodeWithText("Move down").assertDoesNotExist()
        assertTrue(reordered.isEmpty())
    }

    // --- the container itself ----------------------------------------------------------------

    /**
     * Entries recorded inside a workout stay there. The standalone screen folds the day's
     * UNCLAIMED rows, which is what makes it the same day without being the same list — a set
     * of the workout showing up here would be one row on two screens that disagree about what
     * it belongs to.
     */
    @Test
    fun `sets belonging to a workout are not drawn on the standalone screen`() {
        val journal = Journal()
        val workout = journal.startWorkout(iso, at = "18:05")
        journal.strengthSet(stretching, iso, at = "18:10", workoutId = workout)
        journal.holdSet(oneArm, iso, at = "19:00", side = HoldSide.LEFT)

        show(journal)

        // the loose hang is here...
        compose.onNodeWithText("Loose one-arm hang - Left").assertIsDisplayed()
        // ...and the set that belongs to the workout is not
        compose.onNodeWithText("Evening stretching").assertDoesNotExist()
    }

    /**
     * Taking an exercise out names its rows, the same act the workout screen performs — and it
     * says which day it is coming off, because "the workout" is not a thing this screen has.
     */
    @Test
    fun `removing an exercise names its rows and says what it is removing it from`() {
        val journal = Journal()
        val row = journal.holdSet(oneArm, iso, at = "19:00", side = HoldSide.LEFT)

        show(journal)

        compose.onNodeWithText("Loose one-arm hang - Left").performTouchInput { longClick() }
        settle()
        // named for the place it is actually coming off, which is not a workout
        compose.onNodeWithText("Remove from this day").performClick()
        settle()

        compose.onNodeWithText("Remove this exercise from the day?").assertExists()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(listOf(row)), removedRows)
    }
}
