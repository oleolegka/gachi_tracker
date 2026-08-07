package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import xyz.oleolegka.gachimuchi.domain.ProgramImport
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.completedSets
import xyz.oleolegka.gachimuchi.domain.flatten
import xyz.oleolegka.gachimuchi.domain.holdRecord
import xyz.oleolegka.gachimuchi.domain.holdSetsFromRun
import xyz.oleolegka.gachimuchi.domain.programFromExercise
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.readProgramFile
import xyz.oleolegka.gachimuchi.domain.withUniqueNames
import xyz.oleolegka.gachimuchi.domain.workStepCount
import xyz.oleolegka.gachimuchi.domain.writeProgramFile

/**
 * The two new paths through the real database: a finished run landing in the journal, and
 * a file of programs landing in the program tables.
 *
 * The pure tests already prove the arithmetic. What only a database can show is that the
 * events written this way are indistinguishable from the ones the entry card writes —
 * same exercise_id, same payload shape, and therefore the same session feed and the same
 * personal records. A set written by the timer that the reducers could not read back would
 * be worse than no feature at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunToJournalTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var programs: ProgramRepository

    private val day = "2026-08-06"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ActivityRepository(db)
        programs = ProgramRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun hangs() = repo.exercise(
        repo.ensureExercise("Hangs 20 mm", ExerciseForm.HOLD, edgeMm = 20.0, workSec = 7.0, restSec = 3.0)
    )!!.toRef()

    // --- a run becoming a journal entry ----------------------------------------------------

    @Test
    fun `a run stopped after three sets writes three sets the session feed can read`() = runTest {
        val exercise = hangs()
        val steps = programFromExercise(exercise, reps = 6, sets = 4, restBetweenSetsSec = 180, prepareSec = 15)!!
            .flatten()

        // stopped on the third hang of set 3
        val sets = completedSets(steps, endedAtIndex = 29, finished = false)
        holdSetsFromRun(exercise, day, sets, addedKg = 8.0).forEach { repo.record(it) }

        val session = buildSession(repo.allEvents(), day)
        assertEquals(1, session.groups.size)
        assertEquals(3, session.setCount)

        val written = session.groups.single().sets.map { it.form as HoldSet }
        assertEquals(listOf(6, 6, 2), written.map { it.reps })
        assertTrue(written.all { it.exerciseId == exercise.id })
        assertEquals(20.0, written.first().edgeMm!!, 1e-9)

        // the pause the program counted is believed over any gap derived from write times —
        // the two events were written milliseconds apart, and 183 s is the truth
        val rests = session.groups.single().sets.map { it.restBeforeSec }
        assertNull("nothing precedes the first set", rests.first())
        assertEquals(listOf(183.0, 183.0), rests.drop(1))
    }

    @Test
    fun `sets written from a run take part in personal records like any other set`() = runTest {
        val exercise = hangs()
        val steps = programFromExercise(exercise, reps = 3, sets = 2, restBetweenSetsSec = 120)!!.flatten()
        val sets = completedSets(steps, endedAtIndex = steps.lastIndex, finished = true)

        holdSetsFromRun(exercise, "2026-08-01", sets, addedKg = 6.0).forEach { repo.record(it) }
        holdSetsFromRun(exercise, day, sets, addedKg = 9.0).forEach { repo.record(it) }

        val record = holdRecord(readActivities(repo.allEvents()), ExerciseLink.ofId(exercise.id)).single()
        assertEquals(9.0, record.value, 1e-9)
        assertEquals(day, record.opDate)
    }

    @Test
    fun `a run of one exercise stays one exercise in the journal, not one per set`() = runTest {
        val exercise = hangs()
        val steps = programFromExercise(exercise, reps = 2, sets = 3, restBetweenSetsSec = 60)!!.flatten()

        holdSetsFromRun(exercise, day, completedSets(steps, steps.lastIndex, finished = true))
            .forEach { repo.record(it) }

        assertEquals(3, repo.eventCount())
        assertEquals(1, buildSession(repo.allEvents(), day).groups.size)
    }

    // --- a file becoming stored programs ---------------------------------------------------

    @Test
    fun `programs exported from the database come back out of a file identical`() = runTest {
        programs.seedStartersIfEmpty()
        val before = programs.allPrograms()

        val text = writeProgramFile(before, day)
        val read = (readProgramFile(text) as ProgramImport.Loaded).programs
        withUniqueNames(read, before.map { it.name }).forEach { programs.save(it) }

        val after = programs.allPrograms()
        assertEquals(before.size * 2, after.size)

        // the copies are marked rather than replacing what was there
        assertEquals(
            before.map { "${it.name} (imported)" }.toSet(),
            after.map { it.name }.toSet() - before.map { it.name }.toSet(),
        )
        // and they expand into exactly the same workout
        val original = before.first { it.name.startsWith("Hangboard") }
        val copy = after.first { it.name == "${original.name} (imported)" }
        assertEquals(original.workStepCount(), copy.workStepCount())
        assertEquals(original.groups, copy.groups)
    }

    @Test
    fun `a refused file leaves the stored programs untouched`() = runTest {
        programs.seedStartersIfEmpty()
        val before = programs.allPrograms().map { it.name }

        val result = readProgramFile("""{"format":"gachimuchi.programs","version":99,"programs":[]}""")

        assertTrue(result is ProgramImport.Rejected)
        assertEquals(before, programs.allPrograms().map { it.name })
    }
}
