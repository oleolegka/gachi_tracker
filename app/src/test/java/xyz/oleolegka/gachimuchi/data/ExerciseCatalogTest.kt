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
 * §12-A ("an exercise is name + protocol") was obeyed by every reader and by nothing that
 * WROTE. Creating an exercise looked for a matching normalized name and returned it, throwing
 * away the protocol it had been given. Hangs added on a 10:5 protocol while a 7:3 "Hangs"
 * existed became the 7:3 row, and two histories merged permanently and without a word. The
 * rule had no expression in the schema either — no index, no constraint, nothing that could
 * refuse.
 *
 * So these tests come in two halves. One says the lookup now matches on every value. The
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

    private suspend fun hangs(work: Double = 7.0, rest: Double = 3.0): Long =
        repo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = work, restSec = rest)

    // --- the identity, and the constraint under it ---------------------------------------

    /**
     * The exact scenario from the bug report, end to end: two protocols, one name typed by
     * hand, two histories that stay two.
     */
    @Test
    fun `hangs on two protocols under one name are two exercises with two histories`() = runTest {
        val sevenThree = hangs(7.0, 3.0)
        val tenFive = hangs(10.0, 5.0)
        assertNotEquals("the second protocol got the first protocol's row", sevenThree, tenFive)

        val sevenThreeRef = repo.toRef(repo.exercise(sevenThree)!!)
        val tenFiveRef = repo.toRef(repo.exercise(tenFive)!!)
        repeat(3) { repo.record(holdSetOf(sevenThreeRef, "2026-08-01", addedKg = 20.0, holdSec = 7.0)) }
        repo.record(holdSetOf(tenFiveRef, "2026-08-01", addedKg = 5.0, holdSec = 10.0))

        val events = repo.allEvents()
        assertEquals(3, holdSetsOfExercise(events, sevenThreeRef.link).size)
        assertEquals(1, holdSetsOfExercise(events, tenFiveRef.link).size)
        // and the sets carry the protocol they were actually performed under, not one shared pair
        assertTrue(holdSetsOfExercise(events, sevenThreeRef.link).all { it.workSec == 7.0 })
        assertTrue(holdSetsOfExercise(events, tenFiveRef.link).all { it.workSec == 10.0 })
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
        val id = hangs()
        val stored = repo.exercise(id)!!
        val programUid = db.programs().programById(stored.protocolProgramId!!)!!.uid

        assertEquals(
            exerciseIdentityKey("Hangs", ExerciseForm.HOLD.code, programUid),
            stored.identityKey,
        )
        // and the normalized name is what is in it, so spelling cannot split a history
        assertEquals(
            exerciseIdentityKey("  hangs  ", ExerciseForm.HOLD.code, programUid),
            stored.identityKey,
        )
    }

    // --- correcting an exercise ----------------------------------------------------------

    @Test
    fun `renaming an exercise keeps every set it had`() = runTest {
        val id = repo.ensureExercise("Bencj press", ExerciseForm.STRENGTH)
        val ref = repo.toRef(repo.exercise(id)!!)
        repeat(4) { repo.record(strengthSetOf(ref, "2026-08-01", reps = 5, weightKg = 70.0)) }

        assertEquals(ExerciseEdit.Saved, repo.editExercise(id, "Bench press"))

        val stored = repo.exercise(id)!!
        assertEquals("Bench press", stored.name)
        assertEquals(exerciseIdentityKey("Bench press", ExerciseForm.STRENGTH.code), stored.identityKey)
        // the sets never moved: they name the row by uid, and the uid did not change
        assertEquals(4, strengthSetsOfExercise(repo.allEvents(), repo.toRef(stored).link).size)
        // and the corrected name is findable, while the typo is not
        assertEquals(id, repo.ensureExercise("Bench press", ExerciseForm.STRENGTH))
        assertNotEquals(id, repo.ensureExercise("Bencj press", ExerciseForm.STRENGTH))
    }

    /**
     * The owner's rule, as an assertion: "such a thing cannot happen: it breaks the
     * statistics. If yesterday it was one protocol and today another, that is a NEW exercise."
     *
     * [ActivityRepository.editExercise] no longer TAKES a protocol — this is proved twice:
     * the signature itself has no parameter left to pass one through (a compile-time
     * guarantee, not a runtime one), and this test pins the runtime half of it: renaming an
     * exercise leaves `protocolProgramId` and everything the library program under it says
     * exactly as they were, sets included.
     */
    @Test
    fun `renaming an exercise never moves its protocol`() = runTest {
        val id = hangs(7.0, 3.0)
        val before = repo.toRef(repo.exercise(id)!!)
        repo.record(holdSetOf(before, "2026-08-01", addedKg = 10.0, holdSec = 7.0))
        val programIdBefore = repo.exercise(id)!!.protocolProgramId

        assertEquals(ExerciseEdit.Saved, repo.editExercise(id, "Hang"))

        val after = repo.exercise(id)!!
        assertEquals("the row still points at the same library program", programIdBefore, after.protocolProgramId)
        val afterRef = repo.toRef(after)
        assertEquals(7.0, afterRef.workSec!!, 1e-9)
        assertEquals(3.0, afterRef.restSec!!, 1e-9)
        val sets = holdSetsOfExercise(repo.allEvents(), afterRef.link)
        assertEquals("the set has to stay with the exercise", 1, sets.size)
        assertEquals(7.0, sets.single().workSec!!, 1e-9)
    }

    /**
     * The collision path still exists without a protocol parameter to drive it: two exercises
     * created on the IDENTICAL protocol pair share one library program (`resolveOrCreateProtocolProgram`'s
     * "found" rule matches on shape and numbers, not on the exercise's name), so renaming one
     * onto the other's name collides on (name, form, program uid) exactly as it always could.
     */
    @Test
    fun `an edit onto an identity that is taken is refused and names what took it`() = runTest {
        val a = hangs(7.0, 3.0)
        repo.ensureExercise("Hangs deep", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)

        val result = repo.editExercise(a, "Hangs deep")

        assertTrue("expected a refusal, got $result", result is ExerciseEdit.Taken)
        assertEquals("Hangs deep", (result as ExerciseEdit.Taken).name)
        // and nothing moved
        assertEquals("Hangs", repo.exercise(a)!!.name)
        assertEquals(2, repo.allExercises().size)
    }

    @Test
    fun `an edit of an exercise that is gone, and an edit to a blank name, are refused`() = runTest {
        val id = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)

        assertEquals(ExerciseEdit.Blank, repo.editExercise(id, "   "))
        assertEquals(ExerciseEdit.Gone, repo.editExercise(id + 999, "Anything"))
        assertEquals("Bench press", repo.exercise(id)!!.name)
    }

    /** Saving the dialog without changing anything must not trip the constraint on itself. */
    @Test
    fun `an edit that changes nothing is saved rather than refused as a duplicate`() = runTest {
        val id = hangs(7.0, 3.0)

        assertEquals(ExerciseEdit.Saved, repo.editExercise(id, "Hangs"))
        assertEquals(1, repo.allExercises().size)
    }

    // --- hiding --------------------------------------------------------------------------

    @Test
    fun `hiding an exercise keeps the row and everything logged against it`() = runTest {
        val id = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val ref = repo.toRef(repo.exercise(id)!!)
        repeat(3) { repo.record(strengthSetOf(ref, "2026-08-01", reps = 5, weightKg = 70.0)) }

        repo.setHidden(id, true)

        val stored = repo.exercise(id)!!
        assertTrue(stored.hidden)
        assertEquals(1, repo.allExercises().size)
        assertEquals(3, strengthSetsOfExercise(repo.allEvents(), repo.toRef(stored).link).size)

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
        val id = hangs(7.0, 3.0)
        repo.setHidden(id, true)

        assertEquals("the hidden row is the row, not a gap to fill", id, hangs(7.0, 3.0))
        assertEquals(1, repo.allExercises().size)
    }

    // --- what the create form is now allowed to say about a new row ---------------------

    /**
     * "One side at a time" asked at creation reaches the row. Until 2026-08-11 the flag had
     * one writer, the edit dialog of an EXISTING exercise, so a new one could not be declared
     * one-sided at the moment it was described (backlog §23.4).
     */
    @Test
    fun `an exercise can be created one-sided`() = runTest {
        val id = repo.ensureExercise("One-arm row", ExerciseForm.STRENGTH, oneSided = true)

        assertTrue(repo.exercise(id)!!.oneSided)
    }

    /**
     * A row that is FOUND rather than inserted keeps what it says about itself.
     *
     * The create form defaults the switch to off, and a name that already exists is quietly
     * reused — so writing the answer onto a found row would let an unrelated "create" silently
     * un-split the records of an exercise trained one hand at a time. Correcting an existing
     * one is the edit dialog's job.
     */
    @Test
    fun `creating over an existing name does not rewrite what that exercise says about itself`() = runTest {
        val id = repo.ensureExercise("One-arm row", ExerciseForm.STRENGTH, oneSided = true)

        val again = repo.ensureExercise("one-arm row", ExerciseForm.STRENGTH, oneSided = false)

        assertEquals(id, again)
        assertTrue("a found row keeps its own answer", repo.exercise(id)!!.oneSided)
    }

    /**
     * A hold can be pointed at a program that is ALREADY in the library, instead of having a
     * minimal one invented for it — the owner's fourth report of 2026-08-11: "nowhere was I
     * offered to attach a program; the protocol ended up created in the programs, but it is
     * very simple".
     */
    @Test
    fun `a hold can be created led by an existing program`() = runTest {
        val existing = hangs(7.0, 3.0)
        val program = repo.exercise(existing)!!.protocolProgramId!!
        val before = ProgramRepository(db).allPrograms().size

        val id = repo.ensureExercise(
            "Fingerboard", ExerciseForm.HOLD, protocolProgramId = program,
        )

        assertNotEquals(existing, id)
        assertEquals(program, repo.exercise(id)!!.protocolProgramId)
        assertEquals(
            "an existing program is used, not copied",
            before, ProgramRepository(db).allPrograms().size,
        )
    }

    /** The picked program decides the identity, exactly as an invented one would. */
    @Test
    fun `two holds on the same picked program are the same exercise`() = runTest {
        val program = repo.exercise(hangs(7.0, 3.0))!!.protocolProgramId!!

        val first = repo.ensureExercise("Fingerboard", ExerciseForm.HOLD, protocolProgramId = program)
        val second = repo.ensureExercise("fingerboard", ExerciseForm.HOLD, protocolProgramId = program)

        assertEquals(first, second)
    }
}
