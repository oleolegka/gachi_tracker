package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.openWorkout
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import java.time.LocalDateTime

/**
 * Closing the forgotten workout, through the repository — decisions §18.18.
 *
 * The domain rule and its edges are [xyz.oleolegka.gachimuchi.domain.AbandonedWorkoutTest]'s
 * job; this is about what actually reaches the journal: that a real
 * `workout_finished` event is written, that it is marked as the app's own, that the workout it
 * closes still ends when its training did, and that "Reopen" undoes it exactly as it undoes a
 * pressed one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbandonedWorkoutFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ActivityRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun benchSet(day: String, into: Long? = null): Long {
        val id = repo.ensureExercise("Abandoned bench", ExerciseForm.STRENGTH)
        val ref = repo.toRef(repo.exercise(id)!!)
        return repo.record(strengthSetOf(ref, day, reps = 5, weightKg = 60.0), intoWorkoutId = into)
    }

    /** Far enough past anything this test writes that the clock is never the variable. */
    private val muchLater = LocalDateTime.now().plusDays(7)

    @Test
    fun `a workout left alone is closed, and the close is the app's own`() = runTest {
        val workoutId = repo.startWorkout()
        benchSet("2026-08-07")

        val closeId = repo.closeAbandonedWorkout(muchLater)

        assertNotNull("something has to have been written", closeId)
        val workout = buildWorkout(repo.allEvents(), workoutId)!!
        assertTrue(workout.finished)
        assertTrue("the journal has to say the app did this", workout.finishedAutomatically)
        assertNull("and nothing is open any more", openWorkout(repo.allEvents()))
    }

    /**
     * The point of the whole change, in the owner's own words: the workout becomes what it was
     * rather than stretching to the evening. The close was written a week after the training,
     * and the workout still ends when the training did.
     */
    @Test
    fun `the close does not move the workout's end`() = runTest {
        val workoutId = repo.startWorkout()
        benchSet("2026-08-07")
        val endBefore = buildWorkout(repo.allEvents(), workoutId)!!.endTs

        repo.closeAbandonedWorkout(muchLater)

        assertEquals(endBefore, buildWorkout(repo.allEvents(), workoutId)!!.endTs)
    }

    @Test
    fun `a workout still in progress is left alone`() = runTest {
        val workoutId = repo.startWorkout()
        benchSet("2026-08-07")

        assertNull(repo.closeAbandonedWorkout(LocalDateTime.now()))
        assertEquals(workoutId, openWorkout(repo.allEvents())?.id)
    }

    @Test
    fun `nothing open is nothing to close`() = runTest {
        assertNull(repo.closeAbandonedWorkout(muchLater))
    }

    /**
     * THE DEFECT §18.18 IS ABOUT. A set recorded with no workout started used to be filed into
     * whatever was still open, however old — so a set logged next Tuesday joined last
     * Thursday's session and dragged its end time forward a week with it.
     *
     * The clock here is the real one, which is why the workout is made abandoned by writing it
     * against a past date and then closing it before the set: [ActivityRepository.record]
     * cannot be handed a fake now, and the branch under test is the one that runs when
     * `abandonedWorkoutRow` has already answered.
     */
    @Test
    fun `a set logged into an abandoned workout lands outside it instead`() = runTest {
        val workoutId = repo.startWorkout()
        benchSet("2026-08-07")
        repo.closeAbandonedWorkout(muchLater)

        val strayId = benchSet("2026-08-14")

        val stray = repo.allEvents().first { it.id == strayId }
        assertNull("the set must not join a workout that is over", stray.workoutId)
        assertEquals(
            "and the old workout must keep only what was done in it",
            1,
            buildWorkout(repo.allEvents(), workoutId)!!.setCount,
        )
    }

    /**
     * The other half of the same rule: a screen DRAWING a workout writes into that workout
     * whatever the clock says. Finishing is a status and not a lock (§13), and the forgotten
     * set typed up in the changing room is the case — explicit beats the timeout.
     */
    @Test
    fun `a set named into a closed workout still goes into it`() = runTest {
        val workoutId = repo.startWorkout()
        benchSet("2026-08-07")
        repo.closeAbandonedWorkout(muchLater)

        val lateId = benchSet("2026-08-07", into = workoutId)

        assertEquals(workoutId, repo.allEvents().first { it.id == lateId }.workoutId)
        assertEquals(2, buildWorkout(repo.allEvents(), workoutId)!!.setCount)
    }

    /**
     * "We gave a button to resume a workout, so even if we close the wrong one it is not
     * critical" — the owner's own reason for closing automatically rather than asking. The
     * button is [ActivityRepository.unfinishWorkout], and it has to work on an automatic close
     * exactly as on a pressed one, and to KEEP working: nothing was recorded by pressing it, so
     * a rule that only read the clock would shut the workout again a moment later.
     */
    @Test
    fun `reopening an automatically closed workout works and is not undone`() = runTest {
        val workoutId = repo.startWorkout()
        benchSet("2026-08-07")
        repo.closeAbandonedWorkout(muchLater)
        val closeEventId = buildWorkout(repo.allEvents(), workoutId)!!.finishedEventId!!

        repo.unfinishWorkout(closeEventId)

        val back = openWorkout(repo.allEvents())
        assertEquals(workoutId, back?.id)
        assertFalse(back!!.finished)
        assertNull("overruled once is overruled", repo.closeAbandonedWorkout(muchLater))
        assertEquals(workoutId, openWorkout(repo.allEvents())?.id)
    }
}
