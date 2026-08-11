package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.SCHEDULE_CATEGORY
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.holdSetOf

/**
 * A library program that is somebody's protocol does not move by content — the second door
 * onto the mistake §12-A exists to close (see [ExerciseCatalogTest] for the first, the
 * exercise editor).
 *
 * ── What used to happen ──────────────────────────────────────────────────────────
 * Editing a program's blocks through the library rewrote it in place regardless of who
 * pointed at it, and identical protocols collapse onto one shared row — [ProgramEntity.uid]
 * is what several exercises' identities can be keyed on at once. So a library edit changed
 * training for every exercise sharing that row, silently, with `identity_key` never moving to
 * say so: yesterday's history sat under today's different protocol.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgramFreezeTest {

    private lateinit var db: AppDatabase
    private lateinit var activityRepo: ActivityRepository
    private lateinit var programRepo: ProgramRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        activityRepo = ActivityRepository(db)
        programRepo = ProgramRepository(db)
    }

    @After
    fun tearDown() = db.close()

    /**
     * One recorded hang for [exerciseId] — what turns a schedule from a draft into history
     * (§18.19). Everything below that wants a FROZEN schedule goes through this, so the tests
     * say out loud what the freeze is now caused by.
     */
    private suspend fun logASet(exerciseId: Long) {
        val ref = activityRepo.toRef(activityRepo.exercise(exerciseId)!!)
        activityRepo.record(holdSetOf(ref, opDate = "2026-08-11", reps = 5, holdSec = 7.0))
    }

    private fun minimal(workSec: Int, restSec: Int) = WorkoutProgram(
        name = "Hangs protocol",
        groups = listOf(
            ProgramGroup(
                name = "Hangs",
                blocks = listOf(ProgramBlock(name = "Hangs", workSec = workSec, restSec = restSec)),
            )
        ),
    )

    // --- a program nobody's protocol IS: full content edits go through -------------------

    @Test
    fun `a program no exercise references is edited freely by content`() = runTest {
        val id = programRepo.save(minimal(30, 30))
        val stored = programRepo.programById(id)!!

        assertFalse(programRepo.isFrozen(id))
        programRepo.save(
            stored.copy(
                name = "Renamed",
                prepareSec = 20,
                groups = listOf(
                    ProgramGroup(
                        name = "Different",
                        blocks = listOf(ProgramBlock(name = "Different", workSec = 45, restSec = 15)),
                    )
                ),
            )
        )

        val after = programRepo.programById(id)!!
        assertEquals("Renamed", after.name)
        assertEquals(20, after.prepareSec)
        assertEquals(45, after.groups.single().blocks.single().workSec)
        assertEquals(15, after.groups.single().blocks.single().restSec)
    }

    // --- a program an exercise's protocol IS: content is unreachable via the library -----

    /**
     * The checklist item verbatim: changing a protocol via the library editor is unreachable
     * for an exercise that already has one — proved here by calling
     * [ProgramRepository.save] directly with different content and asserting nothing about
     * the stored blocks, groups or lead-in moved.
     */
    @Test
    fun `a program that is an exercise's protocol keeps its content across a library save`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!
        val stored = programRepo.programById(programId)!!

        logASet(exerciseId)
        assertTrue("a schedule with a set against it must read as frozen", programRepo.isFrozen(programId))

        programRepo.save(
            stored.copy(
                prepareSec = 999,
                groups = listOf(
                    ProgramGroup(
                        name = "Rewritten",
                        blocks = listOf(ProgramBlock(name = "Rewritten", workSec = 99, restSec = 99)),
                    )
                ),
            )
        )

        val after = programRepo.programById(programId)!!
        assertEquals("the lead-in must not move", stored.prepareSec, after.prepareSec)
        assertEquals("the group must not move", stored.groups, after.groups)
        // and the exercise's own view of its protocol is unaffected
        val ref = activityRepo.toRef(activityRepo.exercise(exerciseId)!!)
        assertEquals(7.0, ref.workSec!!, 1e-9)
        assertEquals(3.0, ref.restSec!!, 1e-9)
    }

    /**
     * Renaming IS allowed on a referenced program, and — the part that is easy to get wrong —
     * it must not disturb `identity_key`, which is keyed on the program's `uid`, never on its
     * name.
     */
    @Test
    fun `renaming a referenced program is allowed and touches no exercise's identity`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val before = activityRepo.exercise(exerciseId)!!
        val programId = before.protocolProgramId!!
        val stored = programRepo.programById(programId)!!
        logASet(exerciseId)

        programRepo.save(stored.copy(name = "Hangs protocol (renamed)", category = "Fingers"))

        val after = activityRepo.exercise(exerciseId)!!
        assertEquals("renaming the program must not touch the exercise's own name", before.name, after.name)
        assertEquals(
            "renaming the program must not move the exercise's identity",
            before.identityKey,
            after.identityKey,
        )
        assertEquals(programId, after.protocolProgramId)
        assertEquals("Hangs protocol (renamed)", programRepo.programById(programId)!!.name)
        assertEquals("Fingers", programRepo.programById(programId)!!.category)
    }

    // --- the freeze is MILD: no sets yet, still a draft (§18.19) --------------------------

    /**
     * The change §18.19 made, in one test. A schedule that IS somebody's protocol but has no
     * set recorded against it is still editable by content: the owner assembled it, mistyped
     * one number and has not trained on it yet, so there is no history under it to put out of
     * step. Under the rule this replaces (§18.9) the only repair was to abandon the exercise
     * and build another, which the owner called a punishment for a typo.
     */
    @Test
    fun `a schedule with no sets against it is still edited by content`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Mild hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!
        val stored = programRepo.programById(programId)!!

        assertFalse("nothing recorded yet, so nothing to protect", programRepo.isFrozen(programId))

        programRepo.save(
            stored.copy(
                prepareSec = 12,
                groups = listOf(
                    ProgramGroup(
                        name = "Fixed",
                        blocks = listOf(ProgramBlock(name = "Fixed", workSec = 3, restSec = 7)),
                    )
                ),
            )
        )

        val after = programRepo.programById(programId)!!
        assertEquals("the typo must be correctable", 3, after.groups.single().blocks.single().workSec)
        assertEquals(7, after.groups.single().blocks.single().restSec)
        assertEquals(12, after.prepareSec)
    }

    /** The same mildness on the other door: an untrained schedule can be deleted. */
    @Test
    fun `a schedule with no sets against it can be deleted`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Spare hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!

        assertTrue(programRepo.delete(programId))
        assertEquals(null, programRepo.programById(programId))
    }

    /** And the first recorded set shuts it, in the same session, with nothing else changed. */
    @Test
    fun `the first recorded set freezes the schedule`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Turning hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!
        assertFalse(programRepo.isFrozen(programId))

        logASet(exerciseId)

        assertTrue(programRepo.isFrozen(programId))
        assertFalse(programRepo.delete(programId))
    }

    /**
     * THE CAVEAT §18.19 calls mandatory, and the reason the question is asked of all the
     * owners at once: twins share one schedule on purpose (20 mm and 15 mm hangs). The 15 mm
     * hang has no sets of its own, so asking only about it would answer "editable" — and the
     * edit would land under the 20 mm hang's history.
     */
    @Test
    fun `a set on one twin freezes the schedule both twins share`() = runTest {
        val trained = activityRepo.ensureExercise("Twin hangs 20 mm", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val untouched = activityRepo.ensureExercise("Twin hangs 15 mm", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(trained)!!.protocolProgramId!!
        assertEquals(
            "the twins must actually be sharing one row for this test to mean anything",
            programId,
            activityRepo.exercise(untouched)!!.protocolProgramId,
        )
        val stored = programRepo.programById(programId)!!

        logASet(trained)

        assertTrue("the untouched twin must not unfreeze the shared row", programRepo.isFrozen(programId))
        programRepo.save(
            stored.copy(
                groups = listOf(
                    ProgramGroup(
                        name = "Rewritten",
                        blocks = listOf(ProgramBlock(name = "Rewritten", workSec = 99, restSec = 99)),
                    )
                ),
            )
        )
        assertEquals("the trained twin's history must not move", stored.groups, programRepo.programById(programId)!!.groups)
        assertFalse(programRepo.delete(programId))
    }

    /**
     * The consequence of reading LIVE entries, named rather than left to be discovered:
     * deleting every set of an exercise thaws its schedule again. That is the same reversal
     * every other deletion in this journal gives — a deleted set is one that "should not be
     * there" — and the alternative would be a freeze with no way back and nothing on screen
     * to explain it.
     */
    @Test
    fun `deleting the only set thaws the schedule again`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Undone hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!
        val ref = activityRepo.toRef(activityRepo.exercise(exerciseId)!!)
        val setId = activityRepo.record(holdSetOf(ref, opDate = "2026-08-11", reps = 5, holdSec = 7.0))
        assertTrue(programRepo.isFrozen(programId))

        activityRepo.deleteEntry(setId)

        assertFalse(programRepo.isFrozen(programId))
    }

    // --- deleting: the hole beside the freeze --------------------------------------------

    @Test
    fun `a schedule that has been trained on is not deleted`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!
        logASet(exerciseId)

        assertFalse("the refusal is the answer, not an exception", programRepo.delete(programId))

        // nothing cascades from `programs` to `exercises`, so a delete here would have left the
        // exercise pointing at a row that is gone while its identity_key still named its uid
        assertEquals(programId, activityRepo.exercise(exerciseId)!!.protocolProgramId)
        val after = programRepo.programById(programId)
        assertEquals(7, after!!.groups.single().blocks.single().workSec)
        val ref = activityRepo.toRef(activityRepo.exercise(exerciseId)!!)
        assertEquals(7.0, ref.workSec!!, 1e-9)
    }

    /**
     * The name the app writes for itself. "Schedule", not "protocol" — the library files these
     * under "Exercise schedules", and the two words for one thing met there. Names already in
     * the database are NOT rewritten (identity is keyed on uid, so the caption is not worth a
     * data migration), which is why this asserts on a row created now.
     */
    @Test
    fun `a schedule the app generates is named a schedule`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!

        assertEquals("Hangs schedule", programRepo.programById(programId)!!.name)
    }

    /**
     * The category the app writes for itself, on the same "new rows only" footing as the name
     * above. Invisible in the library (the schedules section covers categories whole) and
     * visible in the editor's chip and in an exported program file, which is where "Protocols"
     * — a word §18.15 retired — kept surfacing.
     */
    @Test
    fun `a schedule the app generates is filed under Schedules`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!

        assertEquals(SCHEDULE_CATEGORY, programRepo.programById(programId)!!.category)
    }

    // --- whose program may become a schedule ---------------------------------------------

    /**
     * The trap: a program the owner wrote by hand, of the same shape and the same two numbers
     * a generated schedule has, used to be ADOPTED by the next exercise created on those
     * numbers. It became that exercise's protocol, left its own category for the schedules
     * section, and froze for good — §18.9 makes a referenced program uneditable by content and
     * undeletable, and the catalog row that references it is never deleted either.
     *
     * Nothing about the two programs' CONTENT tells them apart, so the test is origin: only a
     * program that is already somebody's schedule is reused.
     */
    @Test
    fun `a hand-written program is not captured as somebody's schedule by matching numbers`() = runTest {
        val mine = programRepo.save(minimal(7, 3))

        val exerciseId = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)

        val schedule = activityRepo.exercise(exerciseId)!!.protocolProgramId!!
        assertTrue("the exercise must get a schedule of its own", schedule != mine)
        assertFalse("the hand-written program stays nobody's", programRepo.isFrozen(mine))
        // and therefore stays fully editable and deletable, which is what was lost before
        assertTrue(programRepo.delete(mine))
    }

    /**
     * The other half of the same rule, and the reason it is "already a schedule" rather than
     * "never reuse anything": twins deliberately share one schedule (§18.15) — 20 mm and 15 mm
     * hangs are the same protocol on a different edge.
     */
    @Test
    fun `two exercises on the same numbers still share one schedule`() = runTest {
        val first = activityRepo.ensureExercise("Hangs 20 mm", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val second = activityRepo.ensureExercise("Hangs 15 mm", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)

        assertEquals(
            activityRepo.exercise(first)!!.protocolProgramId,
            activityRepo.exercise(second)!!.protocolProgramId,
        )
    }

    /**
     * And the case that would break identity if reuse stopped working: asking for the SAME
     * exercise twice has to resolve to the same program, or the identity key changes and a
     * second row appears beside the first with the same name.
     */
    @Test
    fun `asking for the same exercise twice finds the same row and the same schedule`() = runTest {
        val first = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val again = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)

        assertEquals(first, again)
        assertEquals(
            activityRepo.exercise(first)!!.protocolProgramId,
            activityRepo.exercise(again)!!.protocolProgramId,
        )
    }

    @Test
    fun `a program nobody's schedule it is goes as it always did`() = runTest {
        val id = programRepo.save(minimal(30, 30))

        assertTrue(programRepo.delete(id))
        assertEquals(null, programRepo.programById(id))
    }

    /**
     * Deleting the EXERCISE now DOES release its schedule, and that is a change of behaviour
     * worth pinning down rather than discovering later.
     *
     * `deleteExercise` writes an event and leaves the catalog row where it is, so the
     * REFERENCE survives forever — which under §18.9 meant the schedule was undeletable for
     * good, listed in decisions §18.15-a as a defect ("the schedule of a deleted exercise can
     * never be deleted"). Under §18.19 the question is about SETS, and `journalView` folds
     * every set of a deleted exercise dead along with it, so there is nothing left to protect
     * and the row can go. The defect closes as a side effect of the mild freeze.
     *
     * THE SEAM, stated rather than hidden: deleting an exercise is reversible, and deleting its
     * schedule is not undone with it. Undo the exercise after the schedule has been edited or
     * removed and the restored sets sit under times that have moved. It needs both actions in
     * that order to happen at all, and the alternative — counting sets the app is showing
     * nowhere — would keep a schedule frozen by history the owner has just thrown away.
     */
    @Test
    fun `deleting the exercise releases its schedule`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!

        logASet(exerciseId)
        assertTrue("frozen while the exercise is still there", programRepo.isFrozen(programId))

        activityRepo.deleteExercise(activityRepo.exercise(exerciseId)!!)

        assertFalse("its sets fold dead with it, so nothing is left to protect", programRepo.isFrozen(programId))
        assertTrue(programRepo.delete(programId))
    }

    // --- hiding: presentation only, the protocol keeps working ---------------------------

    @Test
    fun `a hidden program still resolves as its exercise's protocol`() = runTest {
        val exerciseId = activityRepo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val programId = activityRepo.exercise(exerciseId)!!.protocolProgramId!!

        programRepo.setHidden(programId, true)

        assertTrue(programRepo.programById(programId)!!.hidden)
        // the identity chip, a run and a recorded snapshot all read through this same path
        val ref = activityRepo.toRef(activityRepo.exercise(exerciseId)!!)
        assertEquals(7.0, ref.workSec!!, 1e-9)
        assertEquals(3.0, ref.restSec!!, 1e-9)
        assertEquals(1, programRepo.programById(programId)!!.groups.single().blocks.size)

        programRepo.setHidden(programId, false)
        assertFalse(programRepo.programById(programId)!!.hidden)
    }
}
