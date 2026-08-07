package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workout folded out of the journal: which one is open, what is in it, in what order,
 * with which rest — and, just as importantly, what happens to everything recorded WITHOUT
 * one, since the app has to keep working for somebody who never presses "start".
 *
 * Pure reducers, so all of it runs on the JVM with no Room and no device.
 */
class WorkoutTest {

    private var nextId = 1L

    private fun row(type: String, payload: String, ts: String, workoutId: Long? = null) =
        JournalEvent(nextId++, ts, 1, 1, type, payload, workoutId)

    private fun started(
        opDate: String,
        ts: String = "${opDate}T09:00:00",
        slotId: Long? = null,
        slotUid: String? = null,
        name: String? = null,
    ) = row(
        TYPE_WORKOUT_STARTED,
        payloadJson.encodeToString(WorkoutStarted(opDate, slotId, slotUid, name)),
        ts,
    )

    private fun added(workoutId: Long, exerciseId: Long, restSec: Int, ts: String = "2026-08-07T09:01:00") =
        row(
            TYPE_WORKOUT_EXERCISE_ADDED,
            payloadJson.encodeToString(WorkoutExerciseAdded(workoutId, exerciseId, restSec)),
            ts,
            workoutId,
        )

    /** "That workout is over" — written by the button and by the start of the next one. */
    private fun finished(workoutId: Long, ts: String = "2026-08-07T20:00:00") =
        row(
            TYPE_WORKOUT_FINISHED,
            payloadJson.encodeToString(WorkoutFinished(workoutId)),
            ts,
            workoutId,
        )

    private fun set(
        exercise: ExerciseRef,
        opDate: String,
        workoutId: Long? = null,
        ts: String = "${opDate}T09:10:00",
        reps: Int = 5,
        weightKg: Double = 60.0,
    ) = strengthSetOf(exercise, opDate, reps = reps, weightKg = weightKg)
        .let { row(it.type, it.toPayload(), ts, workoutId) }

    private val bench = ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH)
    private val squat = ExerciseRef(2, "Squat", ExerciseForm.STRENGTH)
    private val hangs = ExerciseRef(3, "Hangs 20 mm", ExerciseForm.HOLD, edgeMm = 20.0, workSec = 7.0, restSec = 3.0)

    private val today = "2026-08-07"

    // --- which workout is open -------------------------------------------------------

    @Test
    fun `there is no open workout until one is started`() {
        val events = listOf(set(bench, today))
        assertNull(openWorkout(events))
        assertNull(openWorkoutRow(events)?.id)
    }

    @Test
    fun `the open workout is the one started today, and its id is the event's own id`() {
        val start = started(today)
        val events = listOf(start)

        assertEquals(start.id, openWorkoutRow(events)?.id)
        assertEquals(start.id, openWorkout(events)!!.id)
    }

    /**
     * MIDNIGHT DOES NOT CLOSE A WORKOUT. It used to, and that rule split the training this
     * app is actually used for: an evening session that runs to three in the morning is one
     * session, and closing it at midnight left its last sets in the wrong half.
     */
    @Test
    fun `a workout that ran past midnight is still the open one`() {
        val evening = started("2026-08-06", ts = "2026-08-06T22:00:00")
        val events = listOf(evening)

        assertEquals(evening.id, openWorkoutRow(events)?.id)
        assertEquals(1, workoutsOn(events, "2026-08-06").size)
    }

    @Test
    fun `finishing closes it, and nothing else is open afterwards`() {
        val start = started(today)
        val events = listOf(start, finished(start.id))

        assertNull(openWorkoutRow(events)?.id)
        // it is not gone, it is over: still findable, still complete
        assertEquals(1, workoutsOn(events, today).size)
        assertTrue(buildWorkout(events, start.id)!!.finished)
    }

    /**
     * The forgotten workout, closed on the way past. Without this, "the one in progress" has
     * two candidates and has to guess — which is what the midnight rule used to do.
     */
    @Test
    fun `a workout is finished by whoever starts the next one`() {
        val morning = started(today, ts = "${today}T08:00:00")
        val evening = started(today, ts = "${today}T19:00:00")
        val events = listOf(morning, finished(morning.id), evening)

        assertEquals(evening.id, openWorkoutRow(events)?.id)
        assertTrue(buildWorkout(events, morning.id)!!.finished)
        assertFalse(buildWorkout(events, evening.id)!!.finished)
    }

    /**
     * "Finished" is a STATUS AND NOT A LOCK: the set remembered on the way to the car goes
     * into the workout it belongs to, and the end time follows it there.
     */
    @Test
    fun `a set added after finishing still lands in the workout, and moves its end`() {
        val start = started(today, ts = "${today}T18:00:00")
        val first = set(bench, today, workoutId = start.id, ts = "${today}T18:30:00")
        val done = finished(start.id)
        val afterwards = set(bench, today, workoutId = start.id, ts = "${today}T19:05:00")

        val workout = buildWorkout(listOf(start, first, done, afterwards), start.id)!!
        assertTrue("it does not re-open", workout.finished)
        assertEquals(2, workout.setCount)
        assertEquals("${today}T19:05:00", workout.endTs)
    }

    // --- when a workout ended ---------------------------------------------------------

    /**
     * The end is READ OFF THE LAST SET rather than stamped when the button was pressed. The
     * button is pressed in the changing room; the training stopped at the last set.
     */
    @Test
    fun `the end time is the last set recorded, whenever the button was pressed`() {
        val start = started(today, ts = "${today}T18:00:00")
        val early = set(bench, today, workoutId = start.id, ts = "${today}T18:20:00")
        val late = set(squat, today, workoutId = start.id, ts = "${today}T19:10:00")

        assertEquals("${today}T19:10:00", buildWorkout(listOf(start, early, late), start.id)!!.endTs)
    }

    /** A workout that recorded nothing has only its own start to be dated by. */
    @Test
    fun `an empty workout ends when it started`() {
        val start = started(today, ts = "${today}T18:00:00")
        assertEquals("${today}T18:00:00", buildWorkout(listOf(start), start.id)!!.endTs)
    }

    @Test
    fun `starting a second workout on the same day makes the later one the open one`() {
        val morning = started(today, ts = "${today}T08:00:00")
        val evening = started(today, ts = "${today}T19:00:00")
        val events = listOf(morning, evening)

        assertEquals(evening.id, openWorkoutRow(events)?.id)
        // both are on the day: two separate workouts is the thing a Session could not express
        assertEquals(listOf(morning.id, evening.id), workoutsOn(events, today).map { it.id })
    }

    /**
     * The one that makes typing up old training possible: a workout dated to last month is
     * still the one being worked on right now, because openness is measured against the
     * WRITE time and the day comes from op_date.
     */
    @Test
    fun `a backdated workout started today is the open one, and keeps its own date`() {
        val start = started("2026-06-01", ts = "${today}T21:00:00")
        val events = listOf(start)

        assertEquals(start.id, openWorkoutRow(events)?.id)
        val workout = openWorkout(events)!!
        assertEquals("2026-06-01", workout.opDate)
        assertTrue("nothing in a workout from June may count anything down", workout.isBackdated(today))
        assertEquals(emptyList<Workout>(), workoutsOn(events, today))
        assertEquals(1, workoutsOn(events, "2026-06-01").size)
    }

    @Test
    fun `a workout of today is not backdated`() {
        val events = listOf(started(today))
        assertFalse(openWorkout(events)!!.isBackdated(today))
    }

    // --- the plan a workout was started from ------------------------------------------

    private val gymUid = "01930000-0000-7000-8000-0000000091a7"

    private val gym = Slot(
        id = 5, name = "Gym", atTime = "19:00", repeatRule = REPEAT_WEEKLY,
        anchorDate = "2026-08-01", uid = gymUid,
    )

    @Test
    fun `the slot a workout was started from is carried through, and null when there was none`() {
        val fromPlan = started(today, ts = "${today}T08:00:00", slotId = 42L)
        val offPlan = started(today, ts = "${today}T19:00:00")

        assertEquals(42L, buildWorkout(listOf(fromPlan), fromPlan.id)!!.slotId)
        assertNull(buildWorkout(listOf(offPlan), offPlan.id)!!.slot)
        assertNull(buildWorkout(listOf(offPlan), offPlan.id)!!.slotId)
    }

    @Test
    fun `a workout finds its plan whether it names a number, an identity, or both`() {
        // three shapes of the same link: written by this build, arrived from a journal with
        // no numbers of its own, and written before schema version 11
        for ((withNumber, withUid) in listOf(true to true, false to true, true to false)) {
            val start = started(
                today,
                slotId = if (withNumber) gym.id else null,
                slotUid = if (withUid) gymUid else null,
            )
            val workout = buildWorkout(listOf(start), start.id)!!

            assertNotNull("started with number=$withNumber uid=$withUid", workout.slot)
            assertTrue(workout.slot!!.matches(gym.link))
        }
    }

    /**
     * The plan-side half of the hazard [ExerciseLink] describes: two phones number their plans
     * independently, so a workout naming somebody else's Tuesday by identity must not fall
     * back to its number and claim this phone's.
     */
    @Test
    fun `a plan identity is not overruled by a number that happens to match another plan`() {
        val other = Slot(
            id = gym.id, name = "Hangboard", atTime = "07:00", repeatRule = REPEAT_WEEKLY,
            anchorDate = "2026-08-01", uid = "01930000-0000-7000-8000-00000000a7b0",
        )
        val start = started(today, slotId = gym.id, slotUid = gymUid)
        val link = buildWorkout(listOf(start), start.id)!!.slot!!

        assertTrue(link.matches(gym.link))
        assertFalse(link.matches(other.link))
    }

    // --- the name a workout was started under -----------------------------------------

    @Test
    fun `the name is carried through from the start event, and null when nobody gave one`() {
        val named = started(today, ts = "${today}T08:00:00", name = "Gym")
        val nameless = started(today, ts = "${today}T19:00:00")

        assertEquals("Gym", buildWorkout(listOf(named), named.id)!!.name)
        assertNull(buildWorkout(listOf(nameless), nameless.id)!!.name)
    }

    @Test
    fun `a name of nothing but spaces counts as no name at all`() {
        // otherwise every screen has to remember to check, and one of them will not
        val start = started(today, name = "   ")
        assertNull(buildWorkout(listOf(start), start.id)!!.name)
    }

    @Test
    fun `a workout started from a plan is named by its own snapshot and not by the plan`() {
        // the snapshot and the plan disagree, which is what a plan renamed after the fact
        // looks like. Nothing here consults [gym], and that is the point.
        val start = started(today, slotId = gym.id, slotUid = gymUid, name = "Deadlift day")
        val workout = buildWorkout(listOf(start), start.id)!!

        assertEquals("Deadlift day", workout.name)
        assertTrue(workout.slot!!.matches(gym.link))
    }

    @Test
    fun `a plan that has no identity of its own is still matched by number`() {
        // a slot built by hand rather than read out of the database, which is what every
        // fixture older than schema version 11 looks like
        val handMade = gym.copy(uid = null)
        val start = started(today, slotId = gym.id, slotUid = gymUid)

        assertTrue(buildWorkout(listOf(start), start.id)!!.slot!!.matches(handMade.link))
    }

    @Test
    fun `an unreadable start event still opens a workout rather than orphaning its sets`() {
        val broken = row(TYPE_WORKOUT_STARTED, "{ this is not json", "${today}T09:00:00")
        val events = listOf(broken, set(bench, today, workoutId = broken.id))

        val workout = openWorkout(events)
        assertNotNull("losing the start event must not lose the training", workout)
        // it degrades to the write day and to "no slot", which is what an older row meant
        assertEquals(today, workout!!.opDate)
        assertNull(workout.slotId)
        assertEquals(1, workout.exercises.single().sets.size)
    }

    // --- what is in a workout --------------------------------------------------------

    @Test
    fun `an exercise added before any set is in the workout, empty and with its rest`() {
        val start = started(today)
        val events = listOf(start, added(start.id, bench.id, restSec = 150))

        val workout = buildWorkout(events, start.id)!!
        assertTrue("the whole point: three empty blocks when you walk in", workout.isEmpty.not())
        val only = workout.exercises.single()
        assertEquals(bench.id, only.exerciseId)
        assertEquals(150, only.restSec)
        assertTrue(only.isEmpty)
        assertEquals(0, workout.setCount)
    }

    @Test
    fun `exercises keep the order they were added in, not the order they were trained in`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, restSec = 150),
            added(start.id, squat.id, restSec = 210),
            added(start.id, hangs.id, restSec = 180),
            // trained out of order: squat first
            set(squat, today, workoutId = start.id, ts = "${today}T09:20:00"),
            set(bench, today, workoutId = start.id, ts = "${today}T09:30:00"),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(listOf(bench.id, squat.id, hangs.id), workout.exercises.map { it.exerciseId })
        assertEquals(listOf(150, 210, 180), workout.exercises.map { it.restSec })
        assertEquals(listOf(1, 1, 0), workout.exercises.map { it.sets.size })
        assertEquals(2, workout.setCount)
    }

    @Test
    fun `an exercise that was never added appears anyway, on its first set`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, restSec = 150),
            set(squat, today, workoutId = start.id, ts = "${today}T09:20:00"),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(listOf(bench.id, squat.id), workout.exercises.map { it.exerciseId })
        // nobody chose a rest for it, and null says exactly that rather than inventing one
        assertNull(workout.exercises.last().restSec)
    }

    @Test
    fun `adding an exercise again changes its rest without moving it in the list`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, restSec = 150),
            added(start.id, squat.id, restSec = 210),
            // changed your mind about the bench: in an append-only journal you say it again
            added(start.id, bench.id, restSec = 240, ts = "${today}T09:40:00"),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(listOf(bench.id, squat.id), workout.exercises.map { it.exerciseId })
        assertEquals(240, workout.exercises.first().restSec)
    }

    @Test
    fun `sets are collected in the order recorded, and a cancelled one is gone`() {
        val start = started(today)
        val first = set(bench, today, workoutId = start.id, ts = "${today}T09:10:00", weightKg = 60.0)
        val mistake = set(bench, today, workoutId = start.id, ts = "${today}T09:14:00", weightKg = 600.0)
        val third = set(bench, today, workoutId = start.id, ts = "${today}T09:18:00", weightKg = 62.5)
        val cancel = row(
            TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(mistake.id)),
            "${today}T09:15:00",
            start.id,
        )
        val events = listOf(start, first, mistake, third, cancel)

        val sets = buildWorkout(events, start.id)!!.exercises.single().sets
        assertEquals(listOf(60.0, 62.5), sets.map { (it.form as StrengthSet).weightKg })
    }

    @Test
    fun `a weigh-in recorded during a workout is kept rather than dropped for having no exercise`() {
        val start = started(today)
        val weigh = bodyweightOf(today, weightKg = 74.2)
            .let { row(it.type, it.toPayload(), "${today}T09:05:00", start.id) }
        val events = listOf(start, weigh, set(bench, today, workoutId = start.id))

        val workout = buildWorkout(events, start.id)!!
        assertEquals(1, workout.exercises.size)
        assertEquals(1, workout.entriesWithoutExercise.size)
        assertEquals(2, workout.setCount)
    }

    @Test
    fun `sets of another workout on the same day stay in their own`() {
        val morning = started(today, ts = "${today}T08:00:00")
        val evening = started(today, ts = "${today}T19:00:00")
        val events = listOf(
            morning,
            set(bench, today, workoutId = morning.id, ts = "${today}T08:10:00"),
            evening,
            set(squat, today, workoutId = evening.id, ts = "${today}T19:10:00"),
            set(squat, today, workoutId = evening.id, ts = "${today}T19:14:00"),
        )

        assertEquals(1, buildWorkout(events, morning.id)!!.setCount)
        assertEquals(2, buildWorkout(events, evening.id)!!.setCount)
        assertEquals(listOf(bench.id), buildWorkout(events, morning.id)!!.exercises.map { it.exerciseId })
    }

    @Test
    fun `an id that names no start event is not a workout`() {
        val start = started(today)
        assertNull(buildWorkout(listOf(start), 999L))
        // and a set's id is not a workout id either, however tempting the type is
        val aSet = set(bench, today)
        assertNull(buildWorkout(listOf(start, aSet), aSet.id))
    }

    // --- which day a set being logged belongs to ---------------------------------------

    @Test
    fun `a set logged with no workout open belongs to today`() {
        assertEquals(today, loggingDay(null, today))
    }

    @Test
    fun `a set logged into a workout belongs to the WORKOUT's day, not to today`() {
        /*
         * The bug this exists to make impossible. [buildWorkout] files a set under the
         * workout's op_date whatever the payload says, so a form built with today's date
         * while a June workout is open produces a row the workout shows and the calendar
         * files under August. The journal is append-only: once written, the two views of
         * that row disagree forever.
         */
        val start = started("2026-06-01", ts = "${today}T21:00:00")
        val june = openWorkout(listOf(start))!!

        assertEquals("2026-06-01", loggingDay(june, today))
    }

    @Test
    fun `a workout of today logs under today, so nothing changes for the ordinary case`() {
        val workout = openWorkout(listOf(started(today)))!!
        assertEquals(today, loggingDay(workout, today))
    }

    // --- sets recorded outside any workout -------------------------------------------

    @Test
    fun `sets logged without a workout are still there and are not claimed by one`() {
        val start = started(today)
        val events = listOf(
            set(bench, today, ts = "${today}T07:00:00"),
            start,
            set(squat, today, workoutId = start.id, ts = "${today}T09:10:00"),
        )

        val loose = setsOutsideWorkouts(events, today)
        assertEquals(1, loose.size)
        assertEquals(bench.id, loose.single().form.exerciseId)
        assertEquals(1, buildWorkout(events, start.id)!!.setCount)
    }

    @Test
    fun `a set pointing at a workout this journal does not have is shown, not lost`() {
        // a dangling link: a journal merged from elsewhere, or a start event that never came
        val events = listOf(set(bench, today, workoutId = 777L))
        assertEquals(1, setsOutsideWorkouts(events, today).size)
    }

    @Test
    fun `only the asked-for day is returned, and cancelled sets are not`() {
        val yesterday = "2026-08-06"
        val mistake = set(bench, today, ts = "${today}T09:14:00")
        val events = listOf(
            set(bench, yesterday),
            mistake,
            row(TYPE_SET_CANCEL, payloadJson.encodeToString(SetCancel(mistake.id)), "${today}T09:15:00"),
            set(squat, today, ts = "${today}T09:20:00"),
        )

        val loose = setsOutsideWorkouts(events, today)
        assertEquals(listOf(squat.id), loose.map { it.form.exerciseId })
    }

    // --- the rest offered for an exercise --------------------------------------------

    private val settings = TimerSettings(defaultRestSec = 120)

    @Test
    fun `with nothing known at all the offer is the configured default`() {
        assertEquals(120, restHintSec(settings, emptyList(), bench))
        assertEquals(120, restHintSec(settings, emptyList(), null))
    }

    @Test
    fun `what the user chose for the exercise beats what the journal measured`() {
        // a day with two sets four minutes apart: the journal would derive 240
        val events = listOf(
            set(bench, today, ts = "${today}T09:00:00"),
            set(bench, today, ts = "${today}T09:04:00"),
        )
        assertEquals(240, resolveRestSec(settings, events, bench.id))

        val chosen = bench.copy(defaultRestSec = 150)
        assertEquals(150, restHintSec(settings, events, chosen))
    }

    @Test
    fun `with nothing chosen the offer falls back to what the journal says was done`() {
        val events = listOf(
            set(bench, today, ts = "${today}T09:00:00"),
            set(bench, today, ts = "${today}T09:04:00"),
        )
        assertEquals(240, restHintSec(settings, events, bench))
    }

    @Test
    fun `a stored zero means nothing was chosen, not a rest of zero seconds`() {
        // nothing can count down for zero seconds, so honouring it would hand the timer a
        // duration it cannot run
        assertEquals(120, restHintSec(settings, emptyList(), bench.copy(defaultRestSec = 0)))
    }

    // --- whether the exercise is run by its protocol ----------------------------------

    @Test
    fun `with nothing said, having a work rest protocol is what decides`() {
        assertTrue(ledByProtocol(hangs))
        assertFalse(ledByProtocol(bench))
    }

    @Test
    fun `the flag overrides the protocol in both directions`() {
        // the case this column exists for: a maximum-weight hang carries a protocol because
        // §12-A makes it part of hangboard identity, and is still trained like a lift
        assertFalse(ledByProtocol(hangs.copy(ledByProtocolFlag = false)))
        assertTrue(ledByProtocol(bench.copy(ledByProtocolFlag = true)))
    }

    @Test
    fun `a protocol of zero is no protocol, and does not lead anything`() {
        assertFalse(ledByProtocol(hangs.copy(workSec = 0.0, restSec = 0.0)))
    }

    // --- the new events stay out of the way of everything else ------------------------

    @Test
    fun `workout events are not activities and no reducer counts them`() {
        val start = started(today)
        val events = listOf(start, added(start.id, bench.id, 150), set(bench, today, workoutId = start.id))

        assertEquals(1, readActivities(events).size)
        assertEquals(setOf(today), activeDays(events, today, today))
        // a workout with no sets is not a training day, whatever the journal has in it
        assertEquals(emptySet<String>(), activeDays(listOf(started(today), added(1L, bench.id, 150)), today, today))
    }

    @Test
    fun `the workout link travels from the journal row through to the parsed activity`() {
        val start = started(today)
        val events = listOf(start, set(bench, today, workoutId = start.id))
        assertTrue(readActivities(events).single().workout!!.matches(start))
    }

    // --- the workout link said in uids -----------------------------------------------
    //
    // The link used to be a local autoincrement, which two devices hand out to different
    // training, so a union of two journals welded unrelated rows together. It is a uid now,
    // with the number kept alongside for rows written before schema version 9. What these
    // check is that the fold does not care which of the two a fixture happens to carry.

    /** The same workout, three ways of saying which rows belong to it. */
    private fun workoutWrittenWith(
        withNumber: Boolean,
        withUid: Boolean,
    ): Pair<JournalEvent, List<JournalEvent>> {
        val start = started(today)
        fun link(row: JournalEvent) = row.copy(
            workoutId = if (withNumber) start.id else null,
            workoutUid = if (withUid) start.uid else null,
        )
        val events = listOf(
            start,
            link(added(start.id, bench.id, 150)),
            link(set(bench, today, ts = "${today}T09:10:00")),
            link(set(bench, today, ts = "${today}T09:14:00", weightKg = 65.0)),
            link(set(squat, today, ts = "${today}T09:30:00")),
        )
        return start to events
    }

    /** Everything about a folded workout that a reader can actually see. */
    private fun Workout.shape() = listOf(
        opDate,
        setCount.toString(),
        exercises.joinToString(";") { "${it.exerciseId}:${it.restSec}:${it.sets.size}" },
        entriesWithoutExercise.size.toString(),
    )

    @Test
    fun `a workout folds the same whether its rows carry both links or only the uid`() {
        val (bothStart, both) = workoutWrittenWith(withNumber = true, withUid = true)
        val (uidStart, uidOnly) = workoutWrittenWith(withNumber = false, withUid = true)

        val fromBoth = buildWorkout(both, bothStart.id)!!
        val fromUid = buildWorkout(uidOnly, uidStart.id)!!

        assertEquals(fromBoth.shape(), fromUid.shape())
        // four rows landed in it either way, and none of them leaked out into "loose entries"
        assertEquals(3, fromUid.setCount)
        assertTrue(setsOutsideWorkouts(uidOnly, today).isEmpty())
    }

    @Test
    fun `a row written before uids existed still lands in its workout`() {
        val (start, numbersOnly) = workoutWrittenWith(withNumber = true, withUid = false)

        val workout = buildWorkout(numbersOnly, start.id)!!
        assertEquals(3, workout.setCount)
        assertEquals(listOf(bench.id, squat.id), workout.exercises.map { it.exerciseId })
        assertTrue(setsOutsideWorkouts(numbersOnly, today).isEmpty())
    }

    /**
     * THE FAILURE THE UID EXISTS TO PREVENT, written down as a test.
     *
     * Two journals merged by union bring two `workout_started` events that were both row
     * number 1 on their own phone. A row carrying a uid must be judged by it and never fall
     * back to its stale number, or every set of one workout lands in the other as well.
     */
    @Test
    fun `a uid that names another workout is not overridden by a matching stale number`() {
        val mine = started(today, ts = "${today}T08:00:00")
        val theirs = started(today, ts = "${today}T18:00:00")
        // the number says "mine", the identity says "theirs" — the identity wins
        val confused = set(bench, today, ts = "${today}T18:10:00")
            .copy(workoutId = mine.id, workoutUid = theirs.uid)
        val events = listOf(mine, theirs, confused)

        assertEquals(0, buildWorkout(events, mine.id)!!.setCount)
        assertEquals(1, buildWorkout(events, theirs.id)!!.setCount)
    }

    @Test
    fun `a workout knows its own identity, and it is the start event's`() {
        val start = started(today)
        assertEquals(start.uid, buildWorkout(listOf(start), start.id)!!.uid)
        assertEquals(start.uid, openWorkoutRow(listOf(start))?.uid)
    }

    /**
     * The "exercise added" event states its workout in its own payload as well as in the
     * columns, so that it survives being exported and read back somewhere with no columns at
     * all. This is that path: the columns are stripped and only the payload is left talking.
     */
    @Test
    fun `an exercise-added event finds its workout from its payload alone`() {
        val start = started(today)
        val addedByUid = row(
            TYPE_WORKOUT_EXERCISE_ADDED,
            payloadJson.encodeToString(
                WorkoutExerciseAdded(start.id, bench.id, 150, workoutUid = start.uid)
            ),
            "${today}T09:01:00",
        )
        val events = listOf(start, addedByUid)

        val workout = buildWorkout(events, start.id)!!
        assertEquals(listOf(bench.id), workout.exercises.map { it.exerciseId })
        assertEquals(listOf(150), workout.exercises.map { it.restSec })
    }

    @Test
    fun `an exercise-added event written before uids still finds its workout`() {
        val start = started(today)
        // no uid anywhere: the payload's number is all there is, which is every such row
        // written before schema version 9
        val addedByNumber = row(
            TYPE_WORKOUT_EXERCISE_ADDED,
            payloadJson.encodeToString(WorkoutExerciseAdded(start.id, bench.id, 150)),
            "${today}T09:01:00",
        )

        assertEquals(1, buildWorkout(listOf(start, addedByNumber), start.id)!!.exercises.size)
    }

    @Test
    fun `an exercise-added payload naming another workout is not pulled in by a stale number`() {
        val mine = started(today, ts = "${today}T08:00:00")
        val theirs = started(today, ts = "${today}T18:00:00")
        val confused = row(
            TYPE_WORKOUT_EXERCISE_ADDED,
            // the number says "mine", the identity says "theirs"
            payloadJson.encodeToString(
                WorkoutExerciseAdded(mine.id, bench.id, 150, workoutUid = theirs.uid)
            ),
            "${today}T18:01:00",
        )
        val events = listOf(mine, theirs, confused)

        assertTrue(buildWorkout(events, mine.id)!!.exercises.isEmpty())
        assertEquals(1, buildWorkout(events, theirs.id)!!.exercises.size)
    }

    @Test
    fun `a row pointing at a workout this journal does not hold counts as loose`() {
        // a dangling link, which is what a half-merged journal looks like. The only
        // alternative to showing these entries here is not showing them anywhere.
        val stray = set(bench, today).copy(workoutUid = "0198f000-0000-7000-8000-00000000dead")
        assertEquals(1, setsOutsideWorkouts(listOf(started(today), stray), today).size)
    }

    // --- the rows a workout and an exercise are made of (14.1) -----------------------------

    /**
     * Removing an exercise from a workout has to name its "added" rows as well as its sets, so
     * the block carries them.
     *
     * SEVERAL rows, because adding an exercise again is how the rest is changed in an
     * append-only journal. Leaving the earlier one alive would put the card straight back.
     */
    @Test
    fun `a workout exercise carries every row that put it in the workout`() {
        val start = started(today)
        val first = added(start.id, bench.id, 150)
        val reconsidered = added(start.id, bench.id, 180, ts = "${today}T09:05:00")
        val one = set(bench, today, workoutId = start.id)

        val exercise = buildWorkout(listOf(start, first, reconsidered, one), start.id)!!
            .exercises.single()

        assertEquals(listOf(first.id, reconsidered.id), exercise.addedEventIds)
        assertEquals(listOf(one.id), exercise.sets.map { it.id })
        // the last rest wins, unchanged by any of this
        assertEquals(180, exercise.restSec)
    }

    /** An exercise present only because a set named it has no "added" row to remove. */
    @Test
    fun `an exercise nobody added explicitly carries no added rows`() {
        val start = started(today)
        val one = set(bench, today, workoutId = start.id)

        assertEquals(
            emptyList<Long>(),
            buildWorkout(listOf(start, one), start.id)!!.exercises.single().addedEventIds,
        )
    }

    /**
     * Deleting a workout is deleting everything in it, and this is the list.
     *
     * The start alone would take the workout off every screen and leave its sets counting: a
     * row pointing at a workout the journal no longer holds is treated as recorded OUTSIDE any
     * workout, deliberately, so the sets would come back as loose entries on the same day.
     */
    @Test
    fun `every row of a workout is named together, and rows of another workout are not`() {
        val mine = started(today, ts = "${today}T08:00:00")
        val mineAdded = added(mine.id, bench.id, 150, ts = "${today}T08:01:00")
        val mineSet = set(bench, today, workoutId = mine.id, ts = "${today}T08:10:00")
        val mineDone = finished(mine.id, ts = "${today}T09:00:00")
        val theirs = started(today, ts = "${today}T18:00:00")
        val theirSet = set(bench, today, workoutId = theirs.id, ts = "${today}T18:10:00")
        val loose = set(bench, today, ts = "${today}T12:00:00")
        val events = listOf(mine, mineAdded, mineSet, mineDone, theirs, theirSet, loose)

        assertEquals(
            listOf(mine.id, mineAdded.id, mineSet.id, mineDone.id),
            workoutEventIds(events, mine.id),
        )
        assertEquals(listOf(theirs.id, theirSet.id), workoutEventIds(events, theirs.id))
    }

    /** A workout that is not in this journal names nothing, rather than throwing at a screen. */
    @Test
    fun `a workout the journal does not hold names no rows`() {
        assertEquals(emptyList<Long>(), workoutEventIds(listOf(started(today)), 999L))
    }
}
