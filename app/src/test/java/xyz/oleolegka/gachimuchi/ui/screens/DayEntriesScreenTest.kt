package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * What was done of one exercise on one day, opened from its card (§14.2).
 *
 * The screen folds the day out of the journal itself, so the fixtures are journals and the
 * assertions are about what a reader ends up being told: the sets, their numbers, the times,
 * the pauses between them, and the records.
 *
 * The limits are the ones in [xyz.oleolegka.gachimuchi.ui.components.DayCardListTest]: this
 * proves the words exist and are right, never that anybody can see them.
 */
class DayEntriesScreenTest : ScreenTest() {

    private val iso = "2026-08-07"
    private val bench = exerciseRef(1, "Bench press")
    private val abs = exerciseRef(2, "Abs")
    private val catalog = listOf(exerciseEntity(1, "Bench press"), exerciseEntity(2, "Abs"))

    private var closed = 0
    private var history: Long? = null
    private val amended = mutableListOf<Pair<Long, ActivityForm>>()
    private val deleted = mutableListOf<Long>()

    private fun show(journal: Journal, exerciseId: Long = bench.id, date: String = iso) {
        val state = UiState(events = journal.events, exercises = catalog, loading = false)
        screen {
            DayEntriesScreen(
                state = state,
                exerciseId = exerciseId,
                date = date,
                onOpenHistory = { history = it },
                onClose = { closed++ },
                onAmendEntry = { id, form -> amended += id to form },
                onDeleteEntry = { id -> deleted += id },
            )
        }
    }

    /** Three bench sets on their own, four minutes apart, and one set of something else. */
    private fun looseSets(journal: Journal) {
        journal.strengthSet(bench, iso, at = "12:00", weightKg = 60.0, reps = 8)
        journal.strengthSet(bench, iso, at = "12:04", weightKg = 60.0, reps = 7)
        journal.strengthSet(bench, iso, at = "12:09", weightKg = 62.5, reps = 5)
        journal.strengthSet(abs, iso, at = "13:00", weightKg = 20.0, reps = 12)
    }

    @Test
    fun `every set of the day is drawn with its weight, its reps and the time it was done at`() {
        val journal = Journal()
        looseSets(journal)
        show(journal)

        compose.onNodeWithText("60 kg × 8 reps").assertIsDisplayed()
        compose.onNodeWithText("60 kg × 7 reps").assertIsDisplayed()
        compose.onNodeWithText("62.5 kg × 5 reps").assertIsDisplayed()
        compose.onNodeWithText("12:00").assertIsDisplayed()
        compose.onNodeWithText("12:09").assertIsDisplayed()
    }

    /** The heading answers "which exercise, which day, how much of it" without scrolling. */
    @Test
    fun `the heading names the exercise, the day and how many entries there were`() {
        val journal = Journal()
        looseSets(journal)
        show(journal)

        // named once, in the bar; the card heading says how much of it there is
        compose.onNodeWithText("Bench press").assertIsDisplayed()
        compose.onNodeWithText("Fri 7 Aug").assertIsDisplayed()
        compose.onNodeWithText("Outside a workout - 3 entries").assertIsDisplayed()
    }

    /**
     * The pause actually taken, which is the thing an entry logged on its own has instead of a
     * rest that was chosen. Measured from when each row was WRITTEN, and worded as "after" so
     * it cannot be read as a rest that was set.
     */
    @Test
    fun `each set after the first says how long it came after the previous one`() {
        val journal = Journal()
        looseSets(journal)
        show(journal)

        compose.onNodeWithText("after 4 min").assertIsDisplayed()
        compose.onNodeWithText("after 5 min").assertIsDisplayed()
    }

    /** Only this exercise, and only what was done outside a workout. */
    @Test
    fun `sets of another exercise and sets inside a workout are not on this screen`() {
        val journal = Journal()
        looseSets(journal)
        val workout = journal.startWorkout(iso, at = "18:00")
        journal.addExercise(workout, iso, bench, restSec = 150, at = "18:01")
        journal.strengthSet(bench, iso, at = "18:10", weightKg = 80.0, reps = 3, workoutId = workout)
        show(journal)

        // another exercise's set: it has a card of its own on the day
        compose.onNodeWithText("20 kg × 12 reps").assertDoesNotExist()
        // and a set of THIS exercise that belongs to a workout stays with the workout, so the
        // count on this screen and the count on the card that opened it cannot disagree
        compose.onNodeWithText("80 kg × 3 reps").assertDoesNotExist()
        compose.onNodeWithText("Outside a workout - 3 entries").assertIsDisplayed()
    }

    /**
     * The charts are still reachable — they are simply no longer where the tap lands. This is
     * the whole trade of §14.2: the particular first, the summary one tap behind it.
     */
    @Test
    fun `the all-time history of the exercise is one tap on from here`() {
        val journal = Journal()
        looseSets(journal)
        show(journal)

        compose.onNodeWithText("All-time history of Bench press").performClick()
        assertEquals(bench.id, history)
    }

    /** The same gesture as everywhere else, on the same rows. */
    @Test
    fun `a long press on a set offers to correct it and to remove it`() {
        val journal = Journal()
        looseSets(journal)
        show(journal)

        compose.onNodeWithText("60 kg × 8 reps").performTouchInput { longClick() }
        settle()

        compose.onNodeWithText("Correct").assertExists()
        compose.onNodeWithText("Remove entry").performClick()
        settle()

        compose.onNodeWithText("Remove this entry?").assertExists()
        assertTrue("nothing may be written before the question is answered", deleted.isEmpty())

        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(1L), deleted)
    }

    /** A removal already in the journal is gone from here, and the count follows it. */
    @Test
    fun `an entry deleted in the journal is off this screen and out of the count`() {
        val journal = Journal()
        looseSets(journal)
        journal.deleteEntry(1L)
        show(journal)

        compose.onNodeWithText("60 kg × 8 reps").assertDoesNotExist()
        compose.onNodeWithText("Outside a workout - 2 entries").assertIsDisplayed()
    }

    /** Reachable by removing the last entry from this very screen, so it says something. */
    @Test
    fun `a day with nothing left on it says so rather than drawing an empty screen`() {
        show(Journal())

        compose.onNodeWithText(
            "Nothing of this one is recorded outside a workout on this day any more. " +
                "Anything done inside a workout is on the workout's own card."
        ).assertIsDisplayed()
    }
}
