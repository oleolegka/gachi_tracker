package xyz.oleolegka.gachimuchi.data

import android.content.Context
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
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.flatten
import xyz.oleolegka.gachimuchi.domain.startRun
import xyz.oleolegka.gachimuchi.domain.stepRemainingMs

/**
 * Programs through the real database, and the timer's own two stores.
 *
 * These go through Room and SharedPreferences rather than mocking them, because the two
 * failures worth catching are exactly the ones a mock cannot have: a nested program that
 * comes back with its blocks in a different order, and a run snapshot that does not
 * survive being written and read as JSON.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: ProgramRepository

    private val repeaters = WorkoutProgram(
        name = "Repeaters",
        prepareSec = 15,
        groups = listOf(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 6)),
                repeats = 4,
                restBetweenRepeatsSec = 180,
            )
        ),
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ProgramRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // --- programs ----------------------------------------------------------------------

    @Test
    fun `a nested program survives a round trip through three tables`() = runTest {
        val id = repo.save(repeaters)
        val stored = repo.programById(id)

        assertNotNull(stored)
        assertEquals("Repeaters", stored!!.name)
        assertEquals(15, stored.prepareSec)
        val group = stored.groups.single()
        assertEquals(4, group.repeats)
        assertEquals(180, group.restBetweenRepeatsSec)
        val block = group.blocks.single()
        assertEquals(7, block.workSec)
        assertEquals(3, block.restSec)
        assertEquals(6, block.repeats)
        // and it expands to the same thing the in-memory value did
        assertEquals(repeaters.flatten().map { it.durationSec }, stored.flatten().map { it.durationSec })
    }

    @Test
    fun `the order of groups and blocks is the order they were written in`() = runTest {
        val id = repo.save(
            WorkoutProgram(
                name = "Circuit",
                groups = listOf(
                    ProgramGroup(
                        name = "Push",
                        blocks = listOf(
                            ProgramBlock("Press", workSec = 30),
                            ProgramBlock("Dips", workSec = 40),
                            ProgramBlock("Flyes", workSec = 20),
                        ),
                        restAfterSec = 60,
                    ),
                    ProgramGroup(name = "Pull", blocks = listOf(ProgramBlock("Rows", workSec = 50))),
                ),
            )
        )
        val stored = repo.programById(id)!!

        assertEquals(listOf("Push", "Pull"), stored.groups.map { it.name })
        assertEquals(listOf("Press", "Dips", "Flyes"), stored.groups.first().blocks.map { it.name })
    }

    @Test
    fun `editing replaces the blocks instead of leaving stale ones behind`() = runTest {
        val id = repo.save(repeaters)
        val edited = repo.programById(id)!!.copy(
            name = "Repeaters 10 mm",
            groups = listOf(
                ProgramGroup(
                    name = "Repeaters",
                    blocks = listOf(ProgramBlock("Hang", workSec = 10, restSec = 5, repeats = 5)),
                    repeats = 3,
                    restBetweenRepeatsSec = 240,
                )
            ),
        )
        repo.save(edited)

        val stored = repo.programById(id)!!
        assertEquals(1, repo.count())
        assertEquals("Repeaters 10 mm", stored.name)
        assertEquals(1, stored.groups.single().blocks.size)
        assertEquals(10, stored.groups.single().blocks.single().workSec)
        assertEquals(15, stored.workStepCountForTest())
    }

    private fun WorkoutProgram.workStepCountForTest() =
        flatten().count { it.kind == xyz.oleolegka.gachimuchi.domain.StepKind.WORK }

    @Test
    fun `deleting a program takes its groups and blocks with it`() = runTest {
        val id = repo.save(repeaters)
        assertEquals(1, db.programs().allGroups().size)
        assertEquals(1, db.programs().allBlocks().size)

        repo.delete(id)

        assertEquals(0, repo.count())
        assertEquals(0, db.programs().allGroups().size)
        assertEquals(0, db.programs().allBlocks().size)
        assertNull(repo.programById(id))
    }

    @Test
    fun `the starters are written once and never restored over an edit`() = runTest {
        repo.seedStartersIfEmpty()
        assertEquals(2, repo.count())

        // the user renames one, then the app is launched again
        val first = repo.allPrograms().first()
        repo.save(first.copy(name = "My own thing"))
        repo.seedStartersIfEmpty()

        assertEquals(2, repo.count())
        assertTrue(repo.allPrograms().any { it.name == "My own thing" })
    }

    // --- settings ----------------------------------------------------------------------

    @Test
    fun `settings default to something usable before anything is written`() {
        val store = TimerStore(context)
        assertEquals(120, store.settings.value.defaultRestSec)
        assertTrue(store.settings.value.vibrate)
        // speech is off until an engine has been found, and the timer is off until asked for
        assertFalse(store.settings.value.speak)
        assertFalse(store.enabled.value)
    }

    @Test
    fun `settings round trip and are seen by a store built later in another process`() {
        TimerStore(context).update(
            TimerSettings(
                defaultRestSec = 90, autoStartRest = false, adaptRestToExercise = false,
                prepareSec = 5, sound = false, vibrate = true, countdownTicks = false,
                speak = true, defaultSets = 6,
            )
        )
        TimerStore(context).setEnabled(true)

        val reopened = TimerStore(context)
        assertEquals(90, reopened.settings.value.defaultRestSec)
        assertFalse(reopened.settings.value.autoStartRest)
        assertFalse(reopened.settings.value.sound)
        assertTrue(reopened.settings.value.speak)
        assertEquals(6, reopened.settings.value.defaultSets)
        assertTrue(reopened.enabled.value)
    }

    @Test
    fun `an out of range duration is clamped rather than stored and later used`() {
        val store = TimerStore(context)
        store.update(TimerSettings(defaultRestSec = 0, defaultSets = 0))
        assertTrue(store.settings.value.defaultRestSec >= 1)
        assertTrue(store.settings.value.defaultSets >= 1)
    }

    // --- the surviving run --------------------------------------------------------------

    @Test
    fun `a run written by one process is read back by the next with its end moment intact`() {
        val steps = repeaters.flatten()
        val t0 = 5_000_000L
        val snapshot = RunSnapshot(
            programId = 1, programName = "Repeaters", steps = steps,
            state = startRun(steps, t0), bootRef = 987_654, exerciseId = 42,
        )

        TimerStore(context).saveRun(snapshot)

        val restored = TimerStore(context).loadRun()
        assertNotNull(restored)
        assertEquals(snapshot, restored)
        assertEquals(15_000, stepRemainingMs(restored!!.steps, restored.state, t0))
        assertEquals(42L, restored.exerciseId)
    }

    @Test
    fun `clearing the run leaves nothing for the next launch to resume`() {
        val steps = repeaters.flatten()
        val store = TimerStore(context)
        store.saveRun(
            RunSnapshot(0, "Repeaters", steps, startRun(steps, 1_000), bootRef = 1)
        )
        assertNotNull(store.loadRun())

        store.clearRun()
        assertNull(store.loadRun())
        assertNull(TimerStore(context).loadRun())
    }

    @Test
    fun `an unreadable snapshot is dropped instead of taking the timer down on every launch`() {
        context.getSharedPreferences("timer", Context.MODE_PRIVATE)
            .edit().putString("run_snapshot", "{ this is not a run }").commit()

        val store = TimerStore(context)
        assertNull(store.loadRun())
        // and it is gone, so the next launch does not try again
        assertNull(context.getSharedPreferences("timer", Context.MODE_PRIVATE).getString("run_snapshot", null))
    }
}
