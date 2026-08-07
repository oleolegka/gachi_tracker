package xyz.oleolegka.gachimuchi.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.holdSetsOfExercise
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.strengthSetsOfExercise

/**
 * The catalog as a thing with an identity: what makes two rows one exercise, what happens
 * when that is corrected, and what hiding one does.
 *
 * ── The defect these are written against ────────────────────────────────────────
 * §12-A ("an exercise is name + edge + protocol") was obeyed by every reader and by nothing
 * that WROTE. Creating an exercise looked for a matching normalized name and returned it,
 * throwing away the edge and the protocol it had been given. Hangs on a 15 mm edge added while
 * 20 mm hangs existed became 20 mm hangs, and two histories merged permanently and without a
 * word. The rule had no expression in the schema either — no index, no constraint, nothing
 * that could refuse.
 *
 * So these tests come in two halves. One says the lookup now matches on all four values. The
 * other says the DATABASE says so too, because a rule that lives only in the repository is a
 * rule the next caller can walk around.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExerciseCatalogTest {

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

    private suspend fun hangs(edge: Double, work: Double = 7.0, rest: Double = 3.0): Long =
        repo.ensureExercise("Hangs", ExerciseForm.HOLD, edgeMm = edge, workSec = work, restSec = rest)

    // --- the identity, and the constraint under it ---------------------------------------

    /**
     * The exact scenario from the bug report, end to end: two edges, one name typed by hand,
     * two histories that stay two.
     */
    @Test
    fun `hangs on two edges under one name are two exercises with two histories`() = runTest {
        val twenty = hangs(20.0)
        val fifteen = hangs(15.0)
        assertNotEquals("the second edge got the first edge's row", twenty, fifteen)

        val twentyRef = repo.exercise(twenty)!!.toRef()
        val fifteenRef = repo.exercise(fifteen)!!.toRef()
        repeat(3) { repo.record(holdSetOf(twentyRef, "2026-08-01", addedKg = 20.0, holdSec = 7.0)) }
        repo.record(holdSetOf(fifteenRef, "2026-08-01", addedKg = 5.0, holdSec = 7.0))

        val events = repo.allEvents()
        assertEquals(3, holdSetsOfExercise(events, twentyRef.link).size)
        assertEquals(1, holdSetsOfExercise(events, fifteenRef.link).size)
        // and the sets carry the edge they were actually hung on, not one shared number
        assertTrue(holdSetsOfExercise(events, twentyRef.link).all { it.edgeMm == 20.0 })
        assertTrue(holdSetsOfExercise(events, fifteenRef.link).all { it.edgeMm == 15.0 })
    }

    /**
     * The rule where the repository cannot be the one enforcing it.
     *
     * Every insert in the app goes through [ActivityRepository.ensureExercise], and if that
     * were the whole story this test would be checking a private habit. It goes through the DAO
     * on purpose: the constraint has to be in the database, so that a future caller — a restore,
     * a sync, a screen written in a hurry — cannot create the duplicate by not knowing the rule.
     */
    @Test
    fun `the database itself refuses a second row of one identity`() = runTest {
        repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)

        val failure = runCatching {
            db.exercises().insert(
                ExerciseEntity(
                    name = "bench   PRESS", form = ExerciseForm.STRENGTH.code,
                    createdAt = "2026-08-07T10:00:00",
                )
            )
        }.exceptionOrNull()

        assertTrue(
            "expected the unique index to refuse the row, got $failure",
            failure is SQLiteConstraintException,
        )
        assertEquals(1, repo.allExercises().size)
    }

    /** The key is derived from the row, so an entity built anywhere carries the right one. */
    @Test
    fun `a stored row carries the key its own columns say it should`() = runTest {
        val id = hangs(20.0)
        val stored = repo.exercise(id)!!

        assertEquals(
            exerciseIdentityKey("Hangs", ExerciseForm.HOLD.code, 20.0, 7.0, 3.0),
            stored.identityKey,
        )
        // and the normalized name is what is in it, so spelling cannot split a history
        assertEquals(
            exerciseIdentityKey("  hangs  ", ExerciseForm.HOLD.code, 20.0, 7.0, 3.0),
            stored.identityKey,
        )
    }

    // --- correcting an exercise ----------------------------------------------------------

    @Test
    fun `renaming an exercise keeps every set it had`() = runTest {
        val id = repo.ensureExercise("Bencj press", ExerciseForm.STRENGTH)
        val ref = repo.exercise(id)!!.toRef()
        repeat(4) { repo.record(strengthSetOf(ref, "2026-08-01", reps = 5, weightKg = 70.0)) }

        assertEquals(ExerciseEdit.Saved, repo.editExercise(id, "Bench press", null, null, null))

        val stored = repo.exercise(id)!!
        assertEquals("Bench press", stored.name)
        assertEquals(exerciseIdentityKey("Bench press", ExerciseForm.STRENGTH.code), stored.identityKey)
        // the sets never moved: they name the row by uid, and the uid did not change
        assertEquals(4, strengthSetsOfExercise(repo.allEvents(), stored.toRef().link).size)
        // and the corrected name is findable, while the typo is not
        assertEquals(id, repo.ensureExercise("Bench press", ExerciseForm.STRENGTH))
        assertNotEquals(id, repo.ensureExercise("Bencj press", ExerciseForm.STRENGTH))
    }

    /**
     * Correcting an edge, and the caveat that comes with it stated as an assertion rather than
     * as a sentence in a document: the sets stay, and the sets recorded before the correction
     * still carry the edge they were WRITTEN with.
     *
     * That is the honest record of a typo being fixed — the hangs happened on whatever edge was
     * actually used, and the app was told the wrong number. It is also why this edit is not the
     * way to record having moved to a different edge: that is a different exercise (§12-A) and
     * gets a row and a history of its own.
     */
    @Test
    fun `correcting an edge moves the catalog and leaves the sets saying what they said`() = runTest {
        val id = hangs(20.0)
        val before = repo.exercise(id)!!.toRef()
        repo.record(holdSetOf(before, "2026-08-01", addedKg = 10.0, holdSec = 7.0))

        assertEquals(ExerciseEdit.Saved, repo.editExercise(id, "Hangs", edgeMm = 15.0, workSec = 7.0, restSec = 3.0))

        val after = repo.exercise(id)!!
        assertEquals(15.0, after.edgeMm!!, 1e-9)
        val sets = holdSetsOfExercise(repo.allEvents(), after.toRef().link)
        assertEquals("the set has to stay with the exercise", 1, sets.size)
        assertEquals("the snapshot on the set is not rewritten", 20.0, sets.single().edgeMm!!, 1e-9)
    }

    @Test
    fun `an edit onto an identity that is taken is refused and names what took it`() = runTest {
        val twenty = hangs(20.0)
        hangs(15.0)

        val result = repo.editExercise(twenty, "Hangs", edgeMm = 15.0, workSec = 7.0, restSec = 3.0)

        assertTrue("expected a refusal, got $result", result is ExerciseEdit.Taken)
        assertEquals("Hangs", (result as ExerciseEdit.Taken).name)
        // and nothing moved
        assertEquals(20.0, repo.exercise(twenty)!!.edgeMm!!, 1e-9)
        assertEquals(2, repo.allExercises().size)
    }

    @Test
    fun `an edit of an exercise that is gone, and an edit to a blank name, are refused`() = runTest {
        val id = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)

        assertEquals(ExerciseEdit.Blank, repo.editExercise(id, "   ", null, null, null))
        assertEquals(ExerciseEdit.Gone, repo.editExercise(id + 999, "Anything", null, null, null))
        assertEquals("Bench press", repo.exercise(id)!!.name)
    }

    /** Saving the dialog without changing anything must not trip the constraint on itself. */
    @Test
    fun `an edit that changes nothing is saved rather than refused as a duplicate`() = runTest {
        val id = hangs(20.0)

        assertEquals(
            ExerciseEdit.Saved,
            repo.editExercise(id, "Hangs", edgeMm = 20.0, workSec = 7.0, restSec = 3.0),
        )
        assertEquals(1, repo.allExercises().size)
    }

    // --- hiding --------------------------------------------------------------------------

    @Test
    fun `hiding an exercise keeps the row and everything logged against it`() = runTest {
        val id = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val ref = repo.exercise(id)!!.toRef()
        repeat(3) { repo.record(strengthSetOf(ref, "2026-08-01", reps = 5, weightKg = 70.0)) }

        repo.setHidden(id, true)

        val stored = repo.exercise(id)!!
        assertTrue(stored.hidden)
        assertEquals(1, repo.allExercises().size)
        assertEquals(3, strengthSetsOfExercise(repo.allEvents(), stored.toRef().link).size)

        repo.setHidden(id, false)
        assertFalse(repo.exercise(id)!!.hidden)
    }

    /**
     * Logging a hidden exercise again brings it back. Hiding says "stop offering me this";
     * typing its name and writing a set says the opposite, and the alternative is an exercise
     * being trained that cannot be found in the list you train from.
     */
    @Test
    fun `an exercise that is logged again comes back into the pickers`() = runTest {
        val id = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        repo.setHidden(id, true)

        val again = repo.ensureExercise("bench press", ExerciseForm.STRENGTH)

        assertEquals("a hidden row must be found, not duplicated", id, again)
        assertFalse(repo.exercise(id)!!.hidden)
    }

    /** Hiding is not identity: a hidden row still occupies its own. */
    @Test
    fun `hiding does not make room for a second row of the same exercise`() = runTest {
        val id = hangs(20.0)
        repo.setHidden(id, true)

        assertEquals("the hidden row is the row, not a gap to fill", id, hangs(20.0))
        assertEquals(1, repo.allExercises().size)
    }
}
