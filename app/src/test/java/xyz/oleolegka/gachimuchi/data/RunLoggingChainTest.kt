package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.os.Looper
import androidx.room.Room
import android.os.SystemClock
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
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.ProgramStart
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.holdRecord
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.holdSetsFromRun
import xyz.oleolegka.gachimuchi.domain.multiSetProgram
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.flatten
import xyz.oleolegka.gachimuchi.domain.totalSec
import xyz.oleolegka.gachimuchi.domain.workStepCount
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

    /**
     * Runs whatever is handed to it ON THE CALLING THREAD, immediately.
     *
     * This is what takes the wall clock out of this file. Room's suspending DAO methods hop onto
     * its own executors and resume back on the caller's dispatcher, so a `viewModelScope`
     * coroutine that writes a set genuinely leaves the main thread and comes back — which used
     * to be waited out by sleeping and re-checking for up to five seconds. With the executors
     * running inline the whole hop happens inside the call, and [settle] finishes the job with
     * no clock involved at all.
     *
     * It is safe here for the reason `allowMainThreadQueries` is: this is an in-memory database
     * with a handful of rows in it, and nothing in the test is waiting on a query.
     */
    private val inline = java.util.concurrent.Executor { it.run() }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
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

    private suspend fun hangs() = repo.toRef(
        repo.exercise(repo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0))!!
    )

    /** Lets the whole program elapse and delivers the alarm that notices it has. */
    private fun elapse(timer: TimerController, seconds: Int) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds.toLong()))
        timer.onAlarm()
    }

    /**
     * Runs the work the ViewModel launched, to completion.
     *
     * ── Why this is not a wait any more ─────────────────────────────────────────
     * It used to poll: idle the looper, check a condition, sleep five milliseconds, give up
     * after five seconds. That is a race dressed as an assertion — it passed on an idle machine
     * and failed on a busy one (three parallel builds was enough), and a failure meant nothing,
     * because "the coroutine never ran" and "the coroutine had not run YET" produced the same
     * red. A test nobody can read is worse than no test.
     *
     * Now there is nothing to wait for. `viewModelScope` dispatches on the main looper, which
     * Robolectric leaves paused, and Room's executors run inline ([inline]), so every hop the
     * work makes is either on this thread already or is a message in the looper's queue.
     * Draining that queue is the whole of it, and it is DETERMINISTIC: after this call the work
     * has either finished or was never started, and the assertion that follows says which.
     */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    // --- the path the user takes ------------------------------------------------------------

    @Test
    fun `an exercise, a program built from it, a run on the clock, and a set in the journal`() = runTest {
        val exercise = hangs()
        val program = multiSetProgram(
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
        assertEquals(7.0, written.first().workSec!!, 1e-9)
    }

    @Test
    fun `a saved program run from the timer tab reaches the journal the same way`() = runTest {
        val exercise = hangs()
        // saved in the editor and linked to the exercise, which is what the timer tab runs
        val id = programs.save(
            multiSetProgram(exercise, reps = 3, sets = 2, restBetweenSetsSec = 30, prepareSec = 5)!!
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
            multiSetProgram(exercise, reps = 2, sets = 2, restBetweenSetsSec = 30, prepareSec = 0)!!
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
        val program = multiSetProgram(exercise, reps = 2, sets = 2, restBetweenSetsSec = 30, prepareSec = 0)!!

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
        val program = multiSetProgram(exercise, reps = 2, sets = 1, restBetweenSetsSec = 0, prepareSec = 0)!!

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
        viewModel.startProgramForExercise(ProgramStart(exercise, side = null, addedKg = null))
        settle()
        assertNotNull("the one-tap program never started", timer.run.value)
        assertEquals(RunOrigin.EXERCISE, timer.run.value!!.origin)
        assertEquals(exercise.id, timer.run.value!!.exerciseId)

        // 2. a saved program that is linked to an exercise
        val linked = programs.programById(
            programs.save(
                multiSetProgram(exercise, reps = 2, sets = 2, restBetweenSetsSec = 30, prepareSec = 0)!!
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
     * A STRICT schedule (§18.15) is counted down exactly as it was written.
     *
     * The one-tap start used to rebuild every hold run out of the exercise's first block, a rep
     * count guessed from the last logged set and a set count from the settings — right for a
     * plain pair, which carries neither, and silently destructive for anything richer. Nothing
     * said so: the run started, the phone spoke, and the second half of the schedule was simply
     * not in it.
     *
     * Asserted on the flattened step list rather than on "some program started", because the
     * failure this is about produced a perfectly good run of the wrong shape.
     */
    @Test
    fun `a strict schedule is run exactly as it was written`() = runTest {
        val schedule = programs.programById(
            programs.save(
                WorkoutProgram(
                    name = "Repeaters",
                    prepareSec = 15,
                    groups = listOf(
                        ProgramGroup(
                            name = "Repeaters",
                            blocks = listOf(ProgramBlock(name = "Hang", workSec = 7, restSec = 3, repeats = 6)),
                            repeats = 4,
                            restBetweenRepeatsSec = 180,
                        )
                    ),
                )
            )
        )!!
        val exercise = repo.toRef(
            repo.exercise(
                repo.ensureExercise("Hangs 20mm", ExerciseForm.HOLD, protocolProgramId = schedule.id)
            )!!
        )
        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)

        viewModel.startProgramForExercise(ProgramStart(exercise, side = null, addedKg = null))
        settle()

        val run = timer.run.value
        assertNotNull("the schedule never started", run)
        assertEquals(RunOrigin.EXERCISE, run!!.origin)
        assertEquals(exercise.id, run.exerciseId)
        assertEquals(
            "the stored schedule is what runs, step for step",
            schedule.flatten(), run.steps,
        )
        assertEquals("24 hangs, not one pair times a guess", 24, schedule.workStepCount())
    }

    /**
     * The other side of the same rule: a plain pair carries no rep or set count, so those still
     * come from the journal and the settings. Nothing about the simple branch changed, and this
     * is what says so.
     */
    @Test
    fun `a simple pair is still multiplied out from the journal and the settings`() = runTest {
        val exercise = hangs()
        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)

        viewModel.startProgramForExercise(ProgramStart(exercise, side = null, addedKg = null))
        settle()

        val run = timer.run.value
        assertNotNull(run)
        assertTrue(
            "a pair of 7:3 alone is two steps; the run has to be the multiplied-out version",
            run!!.steps.count { it.name == "Hangs" } > 1,
        )
    }

    // --- the two ways a one-tap program can be started must agree on the side ----------------
    //
    // MainViewModel.startProgramForExercise takes a single ProgramStart rather than the
    // exercise, the side and the plate as three loose parameters (two of them used to default
    // to null) precisely so that the standalone screen below cannot start a run with no side
    // for an exercise that needs one. These two tests sit next to each other on purpose: one
    // path answers the question through a dialog because it has no card, the other reads it
    // off the card it was tapped from, and both must end up writing the same thing.

    /**
     * The path that USED to lose the side: a one-tap program started from the standalone entry
     * screen ([xyz.oleolegka.gachimuchi.ui.screens.LogScreen]), which has no workout card and
     * therefore asks in a dialog (`SideDialog`) before building the [ProgramStart] this test
     * hands the ViewModel directly. Before the fix this screen called
     * `startProgramForExercise(exercise)` and the two defaulted parameters made the side vanish
     * silently — the run started, finished and wrote sets that named no hand at all.
     */
    @Test
    fun `a program started outside a workout writes sets that carry the side it was asked for`() = runTest {
        val exercise = hangs()
        repo.setOneSided(exercise.id, true)
        // re-read: `exercise` was resolved to a ref before the flag above was set on its row
        val oneSided = repo.toRef(repo.exercise(exercise.id)!!)

        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)

        // exactly what LogScreen builds once its SideDialog is answered
        viewModel.startProgramForExercise(ProgramStart(oneSided, side = HoldSide.RIGHT, addedKg = null))
        settle()

        assertEquals(
            "the running program itself must carry the side it was started for",
            HoldSide.RIGHT.code,
            timer.run.value?.side,
        )

        val totalSec = timer.run.value!!.steps.sumOf { it.durationSec }
        elapse(timer, totalSec + 1)

        val outcome = viewModel.runOutcome.value!!
        viewModel.logRunSets(oneSided, outcome.sets)
        settle()

        val written = buildSession(repo.allEvents(), outcome.opDate).groups.single().sets
            .map { it.form as HoldSet }
        assertTrue("a run started for the right hand must write sets for it, not for neither", written.isNotEmpty())
        assertTrue(
            "every set of this run must say which hand, and say the right one",
            written.all { it.sideOf == HoldSide.RIGHT },
        )
    }

    /**
     * The path that already worked, kept here so the two are checked side by side: a program
     * started from a workout's own card ([xyz.oleolegka.gachimuchi.ui.screens.WorkoutLogActions.startProtocolSet]),
     * which already knows the side because it IS which of the exercise's two cards was tapped
     * (see [buildWorkout] and [xyz.oleolegka.gachimuchi.domain.WorkoutExercise.side]). This
     * still has to keep working once [startProgramForExercise] stopped accepting the side as an
     * independent, defaultable parameter.
     */
    @Test
    fun `a program started from a workout card carries that card's side`() = runTest {
        val exercise = hangs()
        repo.setOneSided(exercise.id, true)
        val oneSided = repo.toRef(repo.exercise(exercise.id)!!)

        // the one-sided exercise picked into a workout gets both its cards at once, the same
        // way ExercisePickerSheet's answer to RestDialog does it (see WorkoutLogScreen.kt)
        val workoutId = repo.startWorkout()
        repo.addExerciseToWorkout(workoutId, oneSided.id, restSec = 60, side = HoldSide.LEFT)
        repo.addExerciseToWorkout(workoutId, oneSided.id, restSec = 60, side = HoldSide.RIGHT)

        val leftCard = buildWorkout(repo.allEvents(), workoutId)!!.exercises.single { it.side == HoldSide.LEFT }

        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)

        // exactly what GachiApp.kt's `startProtocolSet` does with the tapped card's own side
        viewModel.startProgramForExercise(ProgramStart(oneSided, side = leftCard.side, addedKg = null))
        settle()

        assertEquals(HoldSide.LEFT.code, timer.run.value?.side)

        val totalSec = timer.run.value!!.steps.sumOf { it.durationSec }
        elapse(timer, totalSec + 1)

        val outcome = viewModel.runOutcome.value!!
        viewModel.logRunSets(oneSided, outcome.sets)
        settle()

        val written = buildSession(repo.allEvents(), outcome.opDate).groups
            .flatMap { it.sets }.map { it.form as HoldSet }
        assertTrue(written.isNotEmpty())
        assertTrue(
            "the card that was tapped is a LEFT card, and every set of its run must say so",
            written.all { it.sideOf == HoldSide.LEFT },
        )
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
        settle()

        val first = timer.floors.floors.value.single()
        assertEquals(exercise.id, first.exerciseId)
        assertEquals("Hangs", first.exerciseName)
        // the default, since the journal has only one entry and so no gap to measure
        assertEquals(timer.settings.value.defaultRestSec * 1000L, first.orderedMs)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        viewModel.addSet(
            holdSetOf(exercise, LocalDate.now().toString(), reps = 6, holdSec = 7.0),
            attachToWorkout = false,
        )
        settle()

        assertEquals("one exercise, one floor", 1, timer.floors.floors.value.size)
        /*
         * The restart is asserted as "this floor has its whole length still to run", not as
         * "it ends later than the one before it".
         *
         * The second reading is what stood here, and it failed on the build machine while
         * passing on a fast one. The ordered length is DERIVED from the gaps between journal
         * timestamps: on a fast machine both sets land in the same millisecond, no gap is
         * measurable, and both floors take the default. On a slower one a second passes
         * between the writes, a gap appears, and the second floor is ordered for the rounded
         * fifteen seconds — genuinely SHORTER than the first, so it ends earlier and the
         * comparison broke. The test was measuring the speed of the machine.
         */
        val restarted = timer.floors.floors.value.single()
        assertEquals(
            "the second set must push the rest out rather than leave the first one running",
            restarted.orderedMs,
            restarted.readyAtMs - SystemClock.elapsedRealtime(),
        )
    }

    @Test
    fun `logging through the ViewModel writes the journal, links the program and says so`() = runTest {
        val exercise = hangs()
        val timer = newController()
        val viewModel = MainViewModel(repo, programs, timer)
        val programId = programs.save(
            multiSetProgram(exercise, reps = 2, sets = 2, restBetweenSetsSec = 30, prepareSec = 0)!!
                .copy(name = "Repeaters", exerciseId = null)
        )

        viewModel.runProgram(programs.programById(programId)!!)
        elapse(timer, programs.programById(programId)!!.totalSec() + 1)

        val outcome = viewModel.runOutcome.value
        assertNotNull("the offer must reach the layer the dialog reads", outcome)

        viewModel.logRunSets(exercise, outcome!!.sets, addedKg = 5.0)
        settle()
        assertNotNull("the write never landed", viewModel.logReceipt.value)

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
        assertEquals("Hangs", receipt!!.exerciseName)
        assertEquals(2, receipt.setCount)
        assertEquals(2, receipt.eventIds.size)

        // 5. and can take it straight back
        viewModel.undoRunSets()
        assertNull(viewModel.logReceipt.value)
        settle()
        assertEquals("the undo never landed", 0, buildSessionSetCount(outcome.opDate))
    }

    /**
     * ANSWERING A RUN'S OFFER STARTS THE REST UNDER ITS CARD — §13.3 step 11 and §13.4, which
     * says it in as many words: "the rest between sets of a protocol exercise is a FLOOR, not
     * part of the protocol".
     *
     * It never did. [MainViewModel.logRunSets] avoided [MainViewModel.addSet] on purpose, and
     * the rest went with everything else it was avoiding, so a set done under the conductor left
     * its card with no countdown drawn on it at all. Reported from the phone: "there is no
     * indicator over the left hand's card, and there is no way to tell how much rest is left or
     * whether the timer is even running".
     *
     * The floor's SIDE is the half worth checking rather than assuming: it is what puts the bar
     * under the hand that just worked and leaves the other hand's card alone.
     */
    @Test
    fun `answering a run's offer starts the rest under the card that earned it`() = runTest {
        val exercise = hangs()
        repo.setOneSided(exercise.id, true)
        val oneSided = repo.toRef(repo.exercise(exercise.id)!!)

        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)

        viewModel.startProgramForExercise(ProgramStart(oneSided, HoldSide.LEFT, null))
        settle()
        elapse(timer, timer.run.value!!.steps.sumOf { it.durationSec } + 1)

        val outcome = viewModel.runOutcome.value!!
        assertTrue("nothing to answer, so nothing this test can be about", outcome.offersLogging)
        assertEquals(
            "the rest cannot start before the sets are confirmed",
            emptyList<Any>(),
            timer.floors.floors.value,
        )

        viewModel.logRunSets(oneSided, outcome.sets)
        settle()

        val floor = timer.floors.floors.value.single()
        assertEquals(oneSided.id, floor.exerciseId)
        assertEquals("the rest belongs to the hand that worked", HoldSide.LEFT.code, floor.side)
        // named by hand as well, because the summary line and the shade have only the name to
        // tell one hand's rest from the other's
        assertTrue(floor.exerciseName.contains("Left"))
        assertTrue("and it is actually counting", floor.readyAtMs > SystemClock.elapsedRealtime())
    }

    /**
     * THE POINT OF §18.17, walked end to end: the left hand rests while the right hand works.
     *
     * This is the scenario one-sided exercises exist for and the one the model made impossible.
     * A conducted run used to be every set of the exercise, with the pauses between them
     * expanded into steps — so while the left hand sat out its four minutes, that pause WAS the
     * single conductor: it held the screen, the voice, the service and the wake lock, the floors
     * were silent by construction, and a tap on the right card went into
     * `TimerController.start`, which replaces a run without ceremony and would have taken the
     * left hand's finished sets with it.
     *
     * With a run cut down to one set the conductor is free the moment the holds are over, and
     * the pause is a floor keyed by (exercise, side) — of which any number run at once. So:
     * left set, confirmed, left rest counting; right set STARTS while it counts, and the left
     * rest is still counting when the right set is over.
     */
    @Test
    fun `the left hand rests while the right hand works, and neither wipes out the other`() = runTest {
        val exercise = hangs()
        repo.setOneSided(exercise.id, true)
        val oneSided = repo.toRef(repo.exercise(exercise.id)!!)

        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)

        // --- the left hand -------------------------------------------------------------
        viewModel.startProgramForExercise(ProgramStart(oneSided, HoldSide.LEFT, null))
        settle()
        val leftRun = checkNotNull(timer.run.value)
        // one set, and the rest between sets is not in it: that is the whole change
        assertTrue(leftRun.steps.all { it.groupRepeats == 1 })
        assertTrue(leftRun.steps.none { it.name == "Rest between sets" })

        elapse(timer, leftRun.steps.sumOf { it.durationSec } + 1)
        assertNull("the conductor must let go when the set is over", timer.run.value)

        viewModel.logRunSets(oneSided, timer.outcome.value!!.sets)
        settle()

        val leftFloor = timer.floors.floors.value.single()
        assertEquals(HoldSide.LEFT.code, leftFloor.side)
        val leftReadyAt = leftFloor.readyAtMs
        assertTrue("the left rest must be counting", leftReadyAt > SystemClock.elapsedRealtime())

        // --- the right hand, WHILE the left one is still resting ------------------------
        viewModel.startProgramForExercise(ProgramStart(oneSided, HoldSide.RIGHT, null))
        settle()

        assertNotNull("the right hand must get the conductor while the left one rests", timer.run.value)
        val rightRun = checkNotNull(timer.run.value)
        assertEquals(HoldSide.RIGHT.code, rightRun.side)

        val stillResting = timer.floors.floors.value.single()
        assertEquals(
            "the left hand's rest must survive the right hand starting",
            HoldSide.LEFT.code,
            stillResting.side,
        )
        assertEquals(
            "and it must not be restarted or shortened by it",
            leftReadyAt,
            stillResting.readyAtMs,
        )

        elapse(timer, rightRun.steps.sumOf { it.durationSec } + 1)
        viewModel.logRunSets(oneSided, timer.outcome.value!!.sets)
        settle()

        // both hands are resting now, each under its own card
        assertEquals(
            listOf(HoldSide.LEFT.code, HoldSide.RIGHT.code),
            timer.floors.floors.value.map { it.side }.sortedBy { it },
        )

        // and the journal has one set per hand, neither of them lost to the other's start
        val written = buildSession(repo.allEvents(), LocalDate.now().toString()).groups
            .flatMap { it.sets }.map { it.form as HoldSet }
        assertEquals(2, written.size)
        assertEquals(
            listOf(HoldSide.LEFT, HoldSide.RIGHT),
            written.mapNotNull { it.sideOf }.sortedBy { it.code },
        )
    }

    /**
     * §18.20 all the way into the journal: a hang the Skip button jumped is not a hang, and
     * the row that gets written has to say so.
     *
     * The controller test proves the mark reaches the offer; this one is about the other end,
     * because the offer is not the record. It is confirmed with one tap, and whatever number
     * it arrives holding is what ends up in the only account of what was trained — and what
     * the personal records are computed from.
     */
    @Test
    fun `a hang skipped under the conductor never reaches the journal`() = runTest {
        val exercise = hangs()
        val program = multiSetProgram(exercise, reps = 3, sets = 1, restBetweenSetsSec = 30, prepareSec = 0)!!

        val timer = newController()
        timer.start(program, exercise.id, RunOrigin.EXERCISE)
        // the first hang is jumped rather than held; the rest of the set runs itself out
        timer.skip()
        elapse(timer, program.totalSec() + 1)

        val outcome = timer.outcome.value!!
        assertEquals(listOf(2), outcome.sets.map { it.reps })

        holdSetsFromRun(exercise, outcome.opDate, outcome.sets).forEach { repo.record(it) }
        val written = buildSession(repo.allEvents(), outcome.opDate)
            .groups.single().sets.map { it.form as HoldSet }
        assertEquals("one row, and it counts two hangs rather than three", listOf(2), written.map { it.reps })
    }

    /**
     * The other half of the same failure: an added weight that is negative. A band taking
     * fifteen kilograms off a hang is ordinary fingerboard work, and the run path used to write it as
     * though nothing had been hung at all — lighter on paper than the same hang with nothing on
     * the bar, and indistinguishable from it in every record afterwards.
     *
     * Checked to the end rather than at the dialog, because that is where it stopped being true
     * before: through the ViewModel, into the journal, and out again through the record.
     */
    @Test
    fun `an assisted run keeps its minus all the way into the journal and the record`() = runTest {
        val exercise = hangs()
        val timer = newController()
        val viewModel = MainViewModel(repo, programs, timer)
        val program = multiSetProgram(exercise, reps = 2, sets = 1, restBetweenSetsSec = 30, prepareSec = 0)!!

        timer.start(program, exercise.id, RunOrigin.EXERCISE)
        elapse(timer, program.totalSec() + 1)

        val outcome = timer.outcome.value!!
        viewModel.logRunSets(exercise, outcome.sets, addedKg = -15.0)
        settle()

        val written = buildSession(repo.allEvents(), outcome.opDate)
            .groups.single().sets.map { it.form as HoldSet }
        assertEquals(listOf(-15.0), written.map { it.addedKg })

        // and the record reads it as fifteen kilograms of ASSISTANCE, not as a bare hang
        val record = holdRecord(readActivities(repo.allEvents()), ExerciseLink.ofId(exercise.id)).single()
        assertEquals(-15.0, record.value, 1e-9)
    }

    @Test
    fun `a run interrupted by the user is offered for the part that ran`() = runTest {
        val exercise = hangs()
        val program = multiSetProgram(exercise, reps = 2, sets = 3, restBetweenSetsSec = 30, prepareSec = 0)!!

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
