package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.Bodyweight
import xyz.oleolegka.gachimuchi.domain.Cardio
import xyz.oleolegka.gachimuchi.domain.Duration
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TYPE_BODYWEIGHT
import xyz.oleolegka.gachimuchi.domain.TYPE_CARDIO
import xyz.oleolegka.gachimuchi.domain.TYPE_DURATION
import xyz.oleolegka.gachimuchi.domain.TYPE_HOLD_SET
import xyz.oleolegka.gachimuchi.domain.TYPE_STRENGTH_SET
import xyz.oleolegka.gachimuchi.domain.TYPE_TICK
import xyz.oleolegka.gachimuchi.domain.Tick
import xyz.oleolegka.gachimuchi.domain.activeDays
import xyz.oleolegka.gachimuchi.domain.bodyweightOf
import xyz.oleolegka.gachimuchi.domain.bodyweightSeries
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.cardioOf
import xyz.oleolegka.gachimuchi.domain.durationOf
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.holdSetsByExerciseId
import xyz.oleolegka.gachimuchi.domain.lastCardio
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.strengthSetsByExerciseId
import xyz.oleolegka.gachimuchi.domain.tickOf
import java.time.LocalDate

/**
 * Hundreds of events of every form the app has, written to a real database and read back
 * through the domain.
 *
 * ── What this replaces ──────────────────────────────────────────────────────────
 * The demo seed is gone (it existed so no screen would ever be seen empty, and it once
 * wrote invented sets into the user's own exercises). Its test went with it, and that test
 * happened to be the only one that ever put a LOT of every form through Room in one go.
 * Nothing else did: the reducer tests hand-build events and never touch a database, and
 * `WorkoutFlowTest` goes through Room but writes strength sets and nothing else.
 *
 * So this is the replacement, with the data generated here instead of by a seed.
 *
 * ── The property being defended ─────────────────────────────────────────────────
 * A form written into the journal must come back out of it IDENTICAL. Everything in this
 * app that is not the journal is derived, so a payload key that fails to survive a trip
 * through `toPayload`/`formFromEvent` does not produce an error — it produces a plausible
 * lie in a record, a statistic or a chart, months later. That is why the central assertion
 * is form-by-form equality of the whole object rather than a spot check on a field or two,
 * and why the reducers are then run over the result: they are how the screens see it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalRoundTripTest {

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

    /**
     * The days written to, ending yesterday.
     *
     * Not today, and the reason matters: [ActivityRepository.record] files a row under the
     * workout that is open TODAY, and this test opens none. Keeping the whole fixture in the
     * past also keeps it away from the clock — nothing here depends on what time the suite
     * happens to run at.
     */
    private val days: List<String> =
        (1..DAY_COUNT).map { LocalDate.now().minusDays(it.toLong()).toString() }.reversed()

    private suspend fun ref(
        name: String,
        form: ExerciseForm,
        edge: Double? = null,
        work: Double? = null,
        rest: Double? = null,
    ): ExerciseRef =
        repo.exercise(repo.ensureExercise(name, form, edgeMm = edge, workSec = work, restSec = rest))!!.toRef()

    /**
     * A month of training in all six forms, as it would really be logged: several sets of a
     * lift, a hangboard session in repeaters, a run, timed stretching, a check-in at the
     * bouldering gym, and a weigh-in.
     *
     * The numbers move from day to day rather than repeating, so that a reducer which
     * returns the wrong ROW (the first instead of the last, say) is caught by the value
     * rather than passing on a fixture where every row is the same.
     */
    private suspend fun writeAMonth(): List<ActivityForm> {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val squat = ref("Squat", ExerciseForm.STRENGTH)
        val hangs = ref("Hangs 20 mm", ExerciseForm.HOLD, edge = 20.0, work = 7.0, rest = 3.0)
        val running = ref("Running", ExerciseForm.CARDIO)
        val stretching = ref("Stretching", ExerciseForm.DURATION)
        val bouldering = ref("Bouldering gym", ExerciseForm.TICK)

        val written = ArrayList<ActivityForm>()
        suspend fun write(form: ActivityForm) {
            repo.record(form)
            written += form
        }

        days.forEachIndexed { index, day ->
            // strength: five sets of two lifts, creeping up over the month
            repeat(5) { set ->
                write(strengthSetOf(bench, day, reps = 5, weightKg = 60.0 + index + set * 2.5))
                write(strengthSetOf(squat, day, reps = 3, weightKg = 100.0 + index * 2.0))
            }
            // strength, own body weight: the other half of the form's own branching
            write(strengthSetOf(bench, day, reps = 8, ownWeight = true, addedKg = 10.0 + index))

            // holds: added weight is the tracked variable (§12-A), edge and protocol are
            // snapshots of the exercise
            repeat(4) { set ->
                write(holdSetOf(hangs, day, addedKg = 5.0 + index * 0.5, reps = 6 - set % 2, holdSec = 7.0))
            }

            write(cardioOf(running, day, distanceM = 5000.0 + index * 100, durationSec = 1500 + index))
            write(durationOf(stretching, day, durationSec = 600 + index * 10))
            write(tickOf(bouldering, day))
            write(bodyweightOf(day, weightKg = 74.0 + index * 0.1))
        }
        return written
    }

    @Test
    fun `every form written comes back out of the database identical`() = runTest {
        val written = writeAMonth()

        // the fixture is worth the name: hundreds of rows, all six forms
        assertTrue("expected hundreds of events, wrote ${written.size}", written.size > 300)
        assertEquals(written.size, repo.eventCount())

        val readBack = readActivities(repo.allEvents()).map { it.form }
        assertEquals(written.size, readBack.size)
        // in journal order, and equal as whole objects: a key that did not survive the trip
        // through the payload shows up here and nowhere else
        assertEquals(written, readBack)
    }

    @Test
    fun `all six forms are present, and each in the quantity it was written in`() = runTest {
        val written = writeAMonth()
        val events = repo.allEvents()

        val expected = written.groupingBy { it.type }.eachCount()
        val actual = readActivities(events).groupingBy { it.type }.eachCount()

        assertEquals(
            setOf(TYPE_STRENGTH_SET, TYPE_HOLD_SET, TYPE_CARDIO, TYPE_DURATION, TYPE_TICK, TYPE_BODYWEIGHT),
            actual.keys,
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `the reducers the screens read through agree with what was written`() = runTest {
        writeAMonth()
        val events = repo.allEvents()
        val bench = repo.allExercises().single { it.name == "Bench press" }
        val hangs = repo.allExercises().single { it.name == "Hangs 20 mm" }
        val running = repo.allExercises().single { it.name == "Running" }

        // 6 strength sets of the bench a day: five with an implement and one on body weight
        val benchSets = strengthSetsByExerciseId(events, bench.id)
        assertEquals(6 * DAY_COUNT, benchSets.size)
        assertEquals(DAY_COUNT, benchSets.count { it.ownWeight })
        // the heaviest is on the last day, which is what makes "last" and "best" different
        // questions and a reducer that confuses them visible
        assertEquals(60.0 + (DAY_COUNT - 1) + 4 * 2.5, benchSets.maxOf { it.weightKg ?: 0.0 }, 1e-9)

        val holds = holdSetsByExerciseId(events, hangs.id)
        assertEquals(4 * DAY_COUNT, holds.size)
        // the identity snapshot rides along on every set (§12-A)
        assertTrue(holds.all { it.edgeMm == 20.0 && it.workSec == 7.0 && it.restSec == 3.0 })
        assertEquals(5.0 + (DAY_COUNT - 1) * 0.5, lastHoldSet(events, hangs.id)!!.addedKg!!, 1e-9)

        assertEquals(5000.0 + (DAY_COUNT - 1) * 100, lastCardio(events, running.id)!!.distanceM!!, 1e-9)

        // the weigh-in series is a plain time series, in journal order, and carries no
        // exercise at all
        val weights = bodyweightSeries(events)
        assertEquals(DAY_COUNT, weights.size)
        assertEquals(74.0, weights.first().weightKg, 1e-9)
        assertEquals(74.0 + (DAY_COUNT - 1) * 0.1, weights.last().weightKg, 1e-9)

        // stepping on the scales is not training: a day with only a weigh-in on it must not
        // count as trained, which is the rule the calendar's verdicts stand on
        assertEquals(days.toSet(), activeDays(events, days.first(), days.last()))
        val onlyWeighed = LocalDate.now().minusDays((DAY_COUNT + 5).toLong()).toString()
        repo.record(bodyweightOf(onlyWeighed, weightKg = 73.0))
        assertTrue(onlyWeighed !in activeDays(repo.allEvents(), onlyWeighed, onlyWeighed))
    }

    @Test
    fun `a day folds back into the session the logging feed shows`() = runTest {
        writeAMonth()
        val events = repo.allEvents()
        val day = days.last()

        val session = buildSession(events, day)
        // 11 strength + 4 holds + cardio + duration + tick + weigh-in
        assertEquals(19, session.setCount)
        assertEquals(
            listOf("Bench press", "Squat", "Hangs 20 mm", "Running", "Stretching", "Bouldering gym", "Body weight"),
            session.groups.map { it.name }.distinct(),
        )
    }

    @Test
    fun `cancelling sets of any form takes them out of every reading at once`() = runTest {
        writeAMonth()
        val before = repo.allEvents()
        val day = days.last()

        // one of each form, cancelled: the journal is append-only, so this appends
        val victims = readActivities(before)
            .filter { it.opDate == day }
            .groupBy { it.type }
            .mapNotNull { (_, rows) -> rows.lastOrNull()?.id }
        assertEquals(6, victims.size)
        victims.forEach { repo.cancelSet(it) }

        val after = repo.allEvents()
        assertEquals("nothing is ever deleted", before.size + 6, after.size)
        assertEquals(readActivities(before).size - 6, readActivities(after).size)
        assertEquals(19 - 6, buildSession(after, day).setCount)
        // and they are still in the history, for anyone who asks for them
        assertEquals(readActivities(before).size, readActivities(after, includeCancelled = true).size)
    }

    @Test
    fun `the payload of each form survives the trip with the fields that form is about`() = runTest {
        writeAMonth()
        val forms = readActivities(repo.allEvents()).map { it.form }

        val strength = forms.filterIsInstance<StrengthSet>().first { it.ownWeight }
        assertEquals(8, strength.reps)
        assertEquals(10.0, strength.addedKg!!, 1e-9)
        assertNotNull("a set must keep the exercise it belongs to", strength.exerciseId)

        val hold = forms.filterIsInstance<HoldSet>().first()
        assertEquals(7.0, hold.holdSec!!, 1e-9)
        assertEquals(20.0, hold.edgeMm!!, 1e-9)
        assertTrue("a hangboard set is always about added weight", hold.ownWeight)

        val cardio = forms.filterIsInstance<Cardio>().first()
        assertEquals(5000.0, cardio.distanceM!!, 1e-9)
        assertEquals(1500, cardio.durationSec)

        assertEquals(600, forms.filterIsInstance<Duration>().first().durationSec)
        assertEquals("Bouldering gym", forms.filterIsInstance<Tick>().first().activity)
        assertEquals(74.0, forms.filterIsInstance<Bodyweight>().first().weightKg, 1e-9)
    }

    private companion object {
        /** Days of training in the fixture; 30 x 19 entries is the "hundreds" this is for. */
        const val DAY_COUNT = 30
    }
}
