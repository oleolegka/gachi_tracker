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
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram

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

        assertFalse(programRepo.isReferenced(id))
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

        assertTrue("the exercise's protocol row must read as referenced", programRepo.isReferenced(programId))

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
