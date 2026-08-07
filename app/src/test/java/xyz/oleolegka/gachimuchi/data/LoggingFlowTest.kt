package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.bodyweightOf
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.cardioOf
import xyz.oleolegka.gachimuchi.domain.durationOf
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.lastStrengthSet
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.tickOf

/**
 * The logging flow end to end through the real database: create an exercise, log a set,
 * repeat it, undo it, catch a record.
 *
 * These duplicate a little of what the pure reducer tests already cover, on purpose: they
 * go through Room, so they also catch a payload that cannot be read back and a builder
 * that drops the exercise_id — the two failures that would silently split an exercise's
 * history in two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoggingFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    private val day = "2026-08-06"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ActivityRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun ref(name: String, form: ExerciseForm, edge: Double? = null, work: Double? = null, rest: Double? = null) =
        repo.exercise(repo.ensureExercise(name, form, edge, work, rest))!!.toRef()

    @Test
    fun `a set is logged, prefills the card, and repeating it writes an identical second one`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 62.5))

        // what the entry card would show next time: the last set, straight from the journal
        val prefill = lastStrengthSet(repo.allEvents(), ExerciseLink.ofId(bench.id))!!
        assertEquals(62.5, prefill.weightKg!!, 1e-9)
        assertEquals(5, prefill.reps)

        // "repeat set" is that prefill written again — one tap, one more event
        repo.record(strengthSetOf(bench, day, reps = prefill.reps, weightKg = prefill.weightKg))

        val session = buildSession(repo.allEvents(), day)
        assertEquals(1, session.groups.size)
        assertEquals(2, session.setCount)
        val weights = session.groups.single().sets.map { (it.form as StrengthSet).weightKg }
        assertEquals(listOf(62.5, 62.5), weights)
    }

    @Test
    fun `undo removes the set from the session but keeps it in the journal`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))
        val mistake = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 600.0))

        assertEquals(mistake, buildSession(repo.allEvents(), day).lastEventId)
        repo.cancelSet(mistake)

        val session = buildSession(repo.allEvents(), day)
        assertEquals(1, session.setCount)
        assertEquals(60.0, (session.groups.single().sets.single().form as StrengthSet).weightKg!!, 1e-9)
        // append-only: three events on disk, one reversal among them
        assertEquals(3, repo.eventCount())
    }

    @Test
    fun `a hold exercise carries edge and protocol, and every set inherits them`() = runTest {
        val hangs = ref("Hangs 20 mm", ExerciseForm.HOLD, edge = 20.0, work = 7.0, rest = 3.0)
        assertEquals(20.0, hangs.edgeMm!!, 1e-9)

        repo.record(holdSetOf(hangs, day, addedKg = 8.0, reps = 5))
        val set = buildSession(repo.allEvents(), day).groups.single().sets.single().form as HoldSet
        assertEquals(20.0, set.edgeMm!!, 1e-9)
        assertEquals(7.0, set.workSec!!, 1e-9)
        assertEquals(3.0, set.restSec!!, 1e-9)
        assertEquals(hangs.id, set.exerciseId)
    }

    @Test
    fun `all six forms go through the database and land in one session`() = runTest {
        repo.record(strengthSetOf(ref("Bench press", ExerciseForm.STRENGTH), day, reps = 5, weightKg = 60.0))
        repo.record(holdSetOf(ref("Hangs", ExerciseForm.HOLD, 20.0, 7.0, 3.0), day, addedKg = 6.0, reps = 5))
        repo.record(cardioOf(ref("Running", ExerciseForm.CARDIO), day, distanceM = 5000.0, durationSec = 1500))
        repo.record(durationOf(ref("Emil hangs", ExerciseForm.DURATION), day, durationSec = 600))
        repo.record(tickOf(ref("Stretching", ExerciseForm.TICK), day))
        repo.record(bodyweightOf(day, weightKg = 74.2))

        val session = buildSession(repo.allEvents(), day)
        assertEquals(6, session.setCount)
        assertEquals(6, session.groups.size)
        assertEquals(6, repo.eventCount())
        // body weight has no exercise_id by design; everything else keeps one
        assertEquals(1, session.groups.count { it.exerciseId == null })
    }

    @Test
    fun `a new best set is reported as a record in the session feed`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        repo.record(strengthSetOf(bench, "2026-08-01", reps = 5, weightKg = 60.0))
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 57.5))
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 65.0))

        val sets = buildSession(repo.allEvents(), day).groups.single().sets
        assertNull(sets[0].record)
        assertNotNull(sets[1].record)
        assertTrue(sets[1].record!!.text.contains("1RM"))
    }
}
