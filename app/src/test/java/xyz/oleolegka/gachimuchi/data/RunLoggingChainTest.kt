package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.holdSetsFromRun
import xyz.oleolegka.gachimuchi.domain.programFromExercise
import xyz.oleolegka.gachimuchi.domain.totalSec
import xyz.oleolegka.gachimuchi.timer.TimerController
import xyz.oleolegka.gachimuchi.ui.MainViewModel
import java.time.Duration
import java.time.LocalDate

/**
 * The whole chain, end to end, because reasoning about it was not enough.
 *
 * Two sessions were run on a real phone and neither was offered for logging. The pure tests
 * were green and the wiring tests were green, so this exists to walk the path a user
 * actually walks — create an exercise in the real catalog, build a program from it, let the
 * clock run the program out, take the offer the controller leaves behind, write it, and
 * then read the journal back through the same reducers the session feed uses. If a set is
 * not there at the end, something in the middle is broken, and this test says which half.
 *
 * ── The clock is advanced, not skipped ──────────────────────────────────────────
 * The existing wiring test drives runs with the Skip button, which is convenient and also
 * avoids the only mechanism that matters here: TIME PASSING while nothing is watching. So
 * these advance the monotonic clock and then deliver the backstop alarm, which is exactly
 * what happens on a phone in a pocket — the process frozen, the countdown coroutine never
 * scheduled, and the platform waking the receiver at the end.
 *
 * ── What this still cannot show ─────────────────────────────────────────────────
 * That the alarm is delivered at all in deep Doze, that a foreground service survives
 * GrapheneOS's battery settings, and that the Compose dialog is drawn once the offer
 * exists. The first two need a phone; the third needs a UI test this project does not have,
 * which is why the offer is also written to disk and re-raised on the next launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunLoggingChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var programs: ProgramRepository
    private val controllers = mutableListOf<TimerController>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ActivityRepository(db)
        programs = ProgramRepository(db)
    }

    @After
    fun tearDown() {
        controllers.forEach { it.stop() }
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
        // the floors live in their own preference file and leak into the next test otherwise
        context.getSharedPreferences("floors", Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
    }

    private fun newController(): TimerController =
        TimerController(context).also { controllers += it }

    private suspend fun hangs() = repo.exercise(
        repo.ensureExercise("Hangs 20 mm", ExerciseForm.HOLD, edgeMm = 20.0, workSec = 7.0, restSec = 3.0)
    )!!.toRef()

    /** Lets the whole program elapse and delivers the alarm that notices it has. */
    private fun elapse(timer: TimerController, seconds: Int) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds.toLong()))
        timer.onAlarm()
    }

    /**
     * Waits for work the ViewModel launched to finish.
     *
     * Idling the main looper is not enough on its own: a `viewModelScope` coroutine that
     * touches Room suspends onto Room's own executor and resumes there, so the test has to
     * pump the looper AND give that executor real time. Failing the wait is treated as a
     * test failure rather than a silent pass, because "the coroutine never finished" is one
     * of the failure modes being hunted here.
     */
    private fun waitFor(what: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // --- the path the user takes ------------------------------------------------------------

    @Test
    fun `an exercise, a program built from it, a run on the clock, and a set in the journal`() = runTest {
        val exercise = hangs()
        val program = programFromExercise(
            exercise = exercise, reps = 2, sets = 2, restBetweenSetsSec = 60, prepareSec = 10,
        )!!
        val timer = newController()

        timer.start(program, exercise.id, RunOrigin.EXERCISE)
        assertNotNull("the run started at all", timer.run.value)

        elapse(timer, program.totalSec() + 1)

        // 1. the run is over
        assertNull(timer.run.value)

        // 2. it left an offer, with the sets it counted
        val outcome = timer.outcome.value
        assertNotNull("a finished run must leave something to log", outcome)
        assertTrue(outcome!!.offersLogging)
        assertEquals(listOf(2, 2), outcome.sets.map { it.reps })
        assertEquals(exercise.id, outcome.exerciseId)
        assertEquals(LocalDate.now().toString(), outcome.opDate)

        // 3. confirming it writes sets the journal can read back
        holdSetsFromRun(exercise, outcome.opDate, outcome.sets, addedKg = 8.0)
            .forEach { repo.record(it) }

        val session = buildSession(repo.allEvents(), outcome.opDate)
        assertEquals(1, session.groups.size)
        assertEquals(2, session.setCount)
        val written = session.groups.single().sets.map { it.form as HoldSet }
        assertEquals(listOf(2, 2), written.map { it.reps })
        assertTrue(written.all { it.exerciseId == exercise.id })
        assertEquals(20.0, written.first().edgeMm!!, 1e-9)
    }

    @Test
    fun `a saved program run from the timer tab reaches the journal the same way`() = runTest {
        val exercise = hangs()
        // saved in the editor and linked to the exercise, which is what the timer tab runs
        val id = programs.save(
            programFromExercise(exercise, reps = 3, sets = 2, restBetweenSetsSec = 30, prepareSec = 5)!!
                .copy(name = "Repeaters 7:3", category = "Hangboard")
        )
        val stored = programs.programById(id)!!
        assertEquals("the link survives the round trip through the database", exercise.id, stored.exerciseId)

        val timer = newController()
        // exactly what MainViewModel.runProgram does with a linked program
        timer.start(stored, stored.exerciseId, RunOrigin.EXERCISE)
        elapse(timer, stored.totalSec() + 1)

        val outcome = timer.outcome.value!!
        assertEquals(listOf(3, 3), outcome.sets.map { it.reps })
        assertEquals(id, outcome.programId)

        holdSetsFromRun(exercise, outcome.opDate, outcome.sets).forEach { repo.record(it) }
        assertEquals(2, buildSession(repo.allEvents(), outcome.opDate).setCount)
    }

    @Test
    fun `an unlinked program leaves an offer that names no exercise, and the answer sticks`() = runTest {
        val exercise = hangs()
        val id = programs.save(
            programFromExercise(exercise, reps = 2, sets = 2, restBetweenSetsSec = 30, prepareSec = 0)!!
                .copy(name = "Repeaters", exerciseId = null)
        )
        val stored = programs.programById(id)!!
        assertNull(stored.exerciseId)

        val timer = newController()
        timer.start(stored, stored.exerciseId, RunOrigin.PROGRAM)
        elapse(timer, stored.totalSec() + 1)

        val outcome = timer.outcome.value!!
        assertTrue("silence here is the bug being fixed", outcome.offersLogging)
        assertNull(outcome.exerciseId)

        // answering the offer teaches the program, so it never has to ask again
        programs.linkExercise(outcome.programId, exercise.id)
        assertEquals(exercise.id, programs.programById(id)!!.exerciseId)
        // and does not disturb anything else about it
        assertEquals("Repeaters", programs.programById(id)!!.name)
        assertEquals(stored.groups, programs.programById(id)!!.groups)
    }

    private fun buildSessionSetCount(day: String): Int = kotlinx.coroutines.runBlocking {
        buildSession(repo.allEvents(), day).setCount
    }

    // --- the run that ends while nobody is looking -------------------------------------------

    @Test
    fun `an offer survives the process that produced it`() = runTest {
        val exercise = hangs()
        val program = programFromExercise(exercise, reps = 2, sets = 2, restBetweenSetsSec = 30, prepareSec = 0)!!

        val timer = newController()
        timer.start(program, exercise.id, RunOrigin.EXERCISE)
        elapse(timer, program.totalSec() + 1)
        assertNotNull(timer.outcome.value)

        // as if Android had killed the app before the offer was ever put on screen
        val revived = newController()

        val outcome = revived.outcome.value
        assertNotNull("an offer lost with the process is a session lost outright", outcome)
        assertEquals(listOf(2, 2), outcome!!.sets.map { it.reps })
        assertEquals(exercise.id, outcome.exerciseId)
    }

    @Test
    fun `answering the offer clears the stored copy too, so it cannot come back`() = runTest {
        val exercise = hangs()
        val program = programFromExercise(exercise, reps = 2, sets = 1, restBetweenSetsSec = 0, prepareSec = 0)!!

        val timer = newController()
        timer.start(program, exercise.id, RunOrigin.EXERCISE)
        elapse(timer, program.totalSec() + 1)
        assertNotNull(timer.outcome.value)

        timer.clearOutcome()

        assertNull(timer.outcome.value)
        assertNull(TimerStore(context).loadOutcome())
        assertNull(newController().outcome.value)
    }

    // --- through the ViewModel, which is where the origin is chosen ---------------------------

    /**
     * Every way a program reaches the runner, checked for the field that decides whether
     * finishing it is offered. This is the layer the first fix was missing: the controller
     * had always been able to carry an origin, and the screen had always been able to show
     * the offer, but the call in between passed the defaults.
     */
    @Test
    fun `each way of starting a run records what kind of run it is`() = runTest {
        val exercise = hangs()
        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)

        // 1. the one-tap program from a catalog exercise
        viewModel.startProgramForExercise(exercise)
        waitFor("the one-tap program to start") { timer.run.value != null }
        assertEquals(RunOrigin.EXERCISE, timer.run.value!!.origin)
        assertEquals(exercise.id, timer.run.value!!.exerciseId)

        // 2. a saved program that is linked to an exercise
        val linked = programs.programById(
            programs.save(
                programFromExercise(exercise, reps = 2, sets = 2, restBetweenSetsSec = 30, prepareSec = 0)!!
                    .copy(name = "Linked")
            )
        )!!
        viewModel.runProgram(linked)
        assertEquals(RunOrigin.EXERCISE, timer.run.value!!.origin)
        assertEquals(exercise.id, timer.run.value!!.exerciseId)

        // 3. a saved program that is not linked: still a workout, just an anonymous one
        val plain = programs.programById(
            programs.save(linked.copy(id = 0, name = "Plain", exerciseId = null))
        )!!
        viewModel.runProgram(plain)
        assertEquals(RunOrigin.PROGRAM, timer.run.value!!.origin)
        assertNull(timer.run.value!!.exerciseId)

        /*
         * There used to be a fourth case here: a rest between sets, started through the
         * ViewModel as a run of its own and marked so it would never be offered as a set of
         * one. It is gone because a rest is no longer a run — see the floors test below for
         * what recording a set starts now.
         */
    }

    /**
     * Recording a set starts a REST FLOOR for that exercise, and recording another one
     * restarts it.
     *
     * The path this walks is the reason it is here rather than in FloorControllerTest: the
     * length is resolved from the journal and the name from the catalog, both AFTER the write,
     * so it needs a real database. What it does not cover is the timing — that lives in
     * FloorsTest and FloorControllerTest.
     */
    @Test
    fun `recording a set starts the rest for that exercise, and the next set restarts it`() = runTest {
        val exercise = hangs()
        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)

        viewModel.addSet(
            holdSetOf(exercise, LocalDate.now().toString(), reps = 6, holdSec = 7.0),
            attachToWorkout = false,
        )
        waitFor("the floor to start") { timer.floors.floors.value.isNotEmpty() }

        val first = timer.floors.floors.value.single()
        assertEquals(exercise.id, first.exerciseId)
        assertEquals("Hangs 20 mm", first.exerciseName)
        // the default, since the journal has only one entry and so no gap to measure
        assertEquals(timer.settings.value.defaultRestSec * 1000L, first.orderedMs)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        viewModel.addSet(
            holdSetOf(exercise, LocalDate.now().toString(), reps = 6, holdSec = 7.0),
            attachToWorkout = false,
        )
        waitFor("the floor to be restarted") {
            timer.floors.floors.value.singleOrNull()?.readyAtMs?.let { it > first.readyAtMs } == true
        }

        assertEquals("one exercise, one floor", 1, timer.floors.floors.value.size)
    }

    @Test
    fun `logging through the ViewModel writes the journal, links the program and says so`() = runTest {
        val exercise = hangs()
        val timer = newController()
        val viewModel = MainViewModel(repo, programs, timer)
        val programId = programs.save(
            programFromExercise(exercise, reps = 2, sets = 2, restBetweenSetsSec = 30, prepareSec = 0)!!
                .copy(name = "Repeaters", exerciseId = null)
        )

        viewModel.runProgram(programs.programById(programId)!!)
        elapse(timer, programs.programById(programId)!!.totalSec() + 1)

        val outcome = viewModel.runOutcome.value
        assertNotNull("the offer must reach the layer the dialog reads", outcome)

        viewModel.logRunSets(exercise, outcome!!.sets, addedKg = 5.0)
        waitFor("the write to land") { viewModel.logReceipt.value != null }

        // 1. it is in the journal
        val session = buildSession(repo.allEvents(), outcome.opDate)
        assertEquals(2, session.setCount)

        // 2. the offer is gone, from memory and from disk
        assertNull(viewModel.runOutcome.value)
        assertNull(TimerStore(context).loadOutcome())

        // 3. the program learned which exercise it was
        assertEquals(exercise.id, programs.programById(programId)!!.exerciseId)

        // 4. and the user is told, by name and by count, rather than left to go and look
        val receipt = viewModel.logReceipt.value
        assertNotNull(receipt)
        assertEquals("Hangs 20 mm", receipt!!.exerciseName)
        assertEquals(2, receipt.setCount)
        assertEquals(2, receipt.eventIds.size)

        // 5. and can take it straight back
        viewModel.undoRunSets()
        assertNull(viewModel.logReceipt.value)
        waitFor("the undo to land") {
            buildSessionSetCount(outcome.opDate) == 0
        }
    }

    @Test
    fun `a run interrupted by the user is offered for the part that ran`() = runTest {
        val exercise = hangs()
        val program = programFromExercise(exercise, reps = 2, sets = 3, restBetweenSetsSec = 30, prepareSec = 0)!!

        val timer = newController()
        timer.start(program, exercise.id, RunOrigin.EXERCISE)
        // through both hangs of set 1 and into the pause after it
        ShadowSystemClock.advanceBy(Duration.ofSeconds(21))
        timer.stop()

        val outcome = timer.outcome.value!!
        assertTrue(outcome.interrupted)
        assertEquals(listOf(2), outcome.sets.map { it.reps })
        assertNotNull("and it is on disk as well", TimerStore(context).loadOutcome())
    }
}
