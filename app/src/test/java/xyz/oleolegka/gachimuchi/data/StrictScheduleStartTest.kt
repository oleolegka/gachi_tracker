package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.ProgramStart
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.ScheduleKind
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.timer.TimerController
import xyz.oleolegka.gachimuchi.ui.MainViewModel

/**
 * Starting a hold that has a STRICT schedule (§18.15), through the ViewModel — the layer where
 * the flattening actually happened.
 *
 * The schedule fixes every temporal thing there is, so a run of it must be the schedule and not
 * a program rebuilt from its first block plus a rep count off the last logged set and a set
 * count off the settings. This test makes those two numbers WRONG on purpose — a last set of
 * two efforts, a settings default of one set — and then insists the run is still what the
 * schedule says. On the old road the run would have been one set of two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StrictScheduleStartTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var programs: ProgramRepository
    private val controllers = mutableListOf<TimerController>()

    private val inline = java.util.concurrent.Executor { it.run() }

    /** Six hangs, four sets, three minutes between — and a second edge inside every set. */
    private val schedule = WorkoutProgram(
        name = "Repeaters, two edges",
        prepareSec = 15,
        groups = listOf(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(
                    ProgramBlock("Hang 20 mm", workSec = 7, restSec = 3, repeats = 6),
                    ProgramBlock("Hang 15 mm", workSec = 7, restSec = 3, repeats = 6),
                ),
                repeats = 4,
                restBetweenRepeatsSec = 180,
            ),
        ),
    )

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
        context.getSharedPreferences("floors", Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
    }

    private fun newController(): TimerController =
        TimerController(context).also { controllers += it }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a strict schedule runs as written, not rebuilt from its first block`() = runTest {
        val programId = programs.save(schedule)
        val id = repo.ensureExercise("Hang 20 mm", ExerciseForm.HOLD, protocolProgramId = programId)
        val exercise = repo.toRef(repo.exercise(id)!!)
        assertEquals(ScheduleKind.STRICT, exercise.scheduleKind)

        // the two numbers the old road would have used, both made deliberately wrong
        repo.record(holdSetOf(exercise, opDate = "2026-08-10", reps = 2, holdSec = 7.0))
        // written before the controller exists: it reads the settings once, at construction
        TimerStore(context).update(TimerSettings(defaultSets = 1, prepareSec = 3))

        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)
        viewModel.startProgramForExercise(ProgramStart(exercise, side = null, addedKg = null))
        settle()

        val run = timer.run.value
        assertNotNull("the strict schedule never started", run)
        checkNotNull(run)
        assertEquals(RunOrigin.EXERCISE, run.origin)
        assertEquals(exercise.id, run.exerciseId)

        val work = run.steps.filter { it.kind == StepKind.WORK }
        // 6 + 6 efforts, four times over: the schedule's own arithmetic, not 1 set of 2
        assertEquals(48, work.size)
        assertEquals(listOf("Hang 20 mm", "Hang 15 mm"), work.map { it.name }.distinct())
        // the lead-in is the schedule's fifteen seconds and not the settings' three
        assertEquals(15, run.steps.first().durationSec)
        assertEquals(StepKind.PREPARE, run.steps.first().kind)
    }

    @Test
    fun `a plain pair still asks the journal and the settings, exactly as before`() = runTest {
        val id = repo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val exercise = repo.toRef(repo.exercise(id)!!)
        assertEquals(ScheduleKind.SIMPLE_PAIR, exercise.scheduleKind)

        repo.record(holdSetOf(exercise, opDate = "2026-08-10", reps = 2, holdSec = 7.0))
        TimerStore(context).update(TimerSettings(defaultSets = 3, prepareSec = 5))

        val timer = newController()
        timer.setEnabled(true)
        val viewModel = MainViewModel(repo, programs, timer)
        viewModel.startProgramForExercise(ProgramStart(exercise, side = null, addedKg = null))
        settle()

        val run = checkNotNull(timer.run.value)
        // two efforts (the last logged set) times three sets (the setting): the derived
        // numbers still decide here, which is what §18.15 keeps for this branch
        assertEquals(6, run.steps.count { it.kind == StepKind.WORK })
        assertEquals(5, run.steps.first().durationSec)
    }
}
