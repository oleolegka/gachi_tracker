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

    private fun added(
        workoutId: Long,
        exerciseId: Long,
        restSec: Int,
        ts: String = "2026-08-07T09:01:00",
        side: HoldSide? = null,
        plannedSets: Int? = null,
    ) = row(
        TYPE_WORKOUT_EXERCISE_ADDED,
        payloadJson.encodeToString(
            WorkoutExerciseAdded(
                workoutId, exerciseId, restSec, side = side?.code, plannedSets = plannedSets,
            )
        ),
        ts,
        workoutId,
    )

    /** "That CARD is done" — one exercise, or one hand of it, and never the workout. */
    private fun cardFinished(
        workoutId: Long,
        exerciseId: Long,
        ts: String = "2026-08-07T09:30:00",
        side: HoldSide? = null,
    ) = row(
        TYPE_WORKOUT_EXERCISE_FINISHED,
        payloadJson.encodeToString(
            WorkoutExerciseFinished(workoutId, exerciseId, side = side?.code)
        ),
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

    /**
     * A full-version correction of a workout's own start row (a rename or a date fix), written
     * the way [xyz.oleolegka.gachimuchi.data.ActivityRepository.amendEntry] writes one today: a
     * whole new start row inheriting [target]'s happened-at time, plus the marker that
     * supersedes it — see domain/Amendments.kt's header.
     */
    private fun correctedStart(
        target: JournalEvent,
        opDate: String,
        name: String?,
        writtenTs: String,
    ): Pair<JournalEvent, JournalEvent> {
        val newVersion = JournalEvent(
            nextId++, writtenTs, 1, 1, TYPE_WORKOUT_STARTED,
            payloadJson.encodeToString(WorkoutStarted(opDate, name = name)),
            occurredTs = target.occurredTs ?: target.ts,
        )
        val marker = row(
            TYPE_ENTRY_DELETED,
            payloadJson.encodeToString(EntryDeleted(targetUid = target.uid, successorUid = newVersion.uid)),
            writtenTs,
        )
        return newVersion to marker
    }

    private val bench = ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH)
    private val squat = ExerciseRef(2, "Squat", ExerciseForm.STRENGTH)
    private val hangs = ExerciseRef(3, "Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)

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
        val done = finished(start.id)
        val events = listOf(start, done)

        assertNull(openWorkoutRow(events)?.id)
        // it is not gone, it is over: still findable, still complete
        assertEquals(1, workoutsOn(events, today).size)
        val workout = buildWorkout(events, start.id)!!
        assertTrue(workout.finished)
        // the id of the event that closed it, the same "the mark IS the way to undo it" model
        // WorkoutExercise.finishedEventId already carries for a single card
        assertEquals(done.id, workout.finishedEventId)
    }

    /*
     * NOT covered here: undo. Un-finishing deletes the finish event, and this file's
     * hand-built rows are not run through the amendment funnel's own regression suite — see
     * ActivityRepository.unfinishWorkout and the screen tests that exercise the button. Stating
     * the gap beats a test that quietly checks something easier.
     */

    /**
     * THE regression this pins: correcting a workout writes a whole new start row (a rename or
     * a date fix), appended at the END of the journal whenever the correction happens to be
     * made — which can be long after that workout was finished, and long after a genuinely
     * later workout was started. Reading "the last start ROW" as "the workout started last"
     * would then either hide the workout actually open right now (if the corrected one had
     * been finished) or hijack it (if it had not) — see [openWorkoutRow]'s own KDoc.
     */
    @Test
    fun `correcting an old, already-finished workout does not hide the one genuinely open now`() {
        val old = started("2026-07-01", ts = "2026-07-01T09:00:00")
        val oldDone = finished(old.id, ts = "2026-07-01T10:00:00")
        // nobody pressed finish on this one - it is genuinely still open
        val current = started(today, ts = "${today}T09:00:00")

        // days later: a typo in the OLD, long-finished workout's name is fixed
        val (fixedOld, marker) = correctedStart(
            old, opDate = "2026-07-01", name = "Fingerboard", writtenTs = "${today}T15:00:00",
        )
        val events = listOf(old, oldDone, current, fixedOld, marker)

        assertEquals(
            "the workout genuinely open right now must not vanish because an unrelated, " +
                "already-finished workout was corrected after it was started",
            current.id,
            openWorkoutRow(events)?.id,
        )
    }

    /**
     * The mirror case: correcting an old, STILL-OPEN workout (one nobody ever finished) must
     * not make it read as "started last" ahead of a genuinely later one either.
     */
    @Test
    fun `correcting an old, still-open workout does not make it look like the current one`() {
        val old = started("2026-07-01", ts = "2026-07-01T09:00:00") // never finished
        val current = started(today, ts = "${today}T09:00:00")      // started later, also open

        val (fixedOld, marker) = correctedStart(
            old, opDate = "2026-07-01", name = "Fingerboard", writtenTs = "${today}T15:00:00",
        )
        val events = listOf(old, current, fixedOld, marker)

        assertEquals(current.id, openWorkoutRow(events)?.id)
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

    /**
     * THE regression this pins: correcting the EARLIER of two sets writes a new row with
     * TODAY's id and TODAY's ts, which used to win the "largest id" contest [endTs] picked its
     * answer by — reading a typo fixed a week later as the moment a long-finished workout
     * actually ended.
     */
    @Test
    fun `correcting the earlier of two sets does not move the workout's end time`() {
        val start = started(today, ts = "${today}T18:00:00")
        val early = set(bench, today, workoutId = start.id, ts = "${today}T18:20:00")
        val late = set(squat, today, workoutId = start.id, ts = "${today}T19:10:00")

        // a typo in the earlier set, fixed a week later
        val fixed = JournalEvent(
            nextId++, "2026-08-14T12:00:00", 1, 1, TYPE_STRENGTH_SET,
            strengthSetOf(bench, today, reps = 8, weightKg = 60.0).toPayload(),
            workoutId = start.id, occurredTs = early.happenedAt,
        )
        val marker = row(
            TYPE_ENTRY_DELETED,
            payloadJson.encodeToString(EntryDeleted(targetUid = early.uid, successorUid = fixed.uid)),
            fixed.ts,
        )

        val workout = buildWorkout(listOf(start, early, late, fixed, marker), start.id)!!
        assertEquals(
            "the last set actually done was at 19:10 - fixing a typo a week later must not move it",
            "${today}T19:10:00",
            workout.endTs,
        )
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

    /**
     * The planned set count (§18.17) follows the same "last row wins" rule the rest does, for the
     * same reason: restating a card is how a choice is changed in an append-only journal. A card
     * nobody planned carries null, which is what the card reads as "no target" rather than zero.
     */
    @Test
    fun `the planned set count is folded like the rest, last statement winning`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, restSec = 150, plannedSets = 5),
            added(start.id, squat.id, restSec = 210),
            added(start.id, bench.id, restSec = 150, plannedSets = 3, ts = "${today}T09:40:00"),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(3, workout.exercises.first { it.exerciseId == bench.id }.plannedSets)
        assertNull(
            "an exercise nobody planned has no plan, not a plan of zero",
            workout.exercises.first { it.exerciseId == squat.id }.plannedSets,
        )
    }

    /** A restatement that says nothing about a plan clears one — it is the current statement. */
    @Test
    fun `restating a card without a plan takes the plan away`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, restSec = 150, plannedSets = 5),
            added(start.id, bench.id, restSec = 240, ts = "${today}T09:40:00"),
        )

        val workout = buildWorkout(events, start.id)!!
        assertNull(workout.exercises.single().plannedSets)
    }

    // --- two cards of an exercise trained one limb at a time --------------------------

    private fun holdSet(
        exercise: ExerciseRef,
        opDate: String,
        workoutId: Long? = null,
        ts: String = "${opDate}T09:10:00",
        side: HoldSide? = null,
    ) = holdSetOf(exercise, opDate, reps = 5, side = side)
        .let { row(it.type, it.toPayload(), ts, workoutId) }

    private fun strengthSet(
        exercise: ExerciseRef,
        opDate: String,
        workoutId: Long? = null,
        ts: String = "${opDate}T09:10:00",
        side: HoldSide? = null,
    ) = strengthSetOf(exercise, opDate, reps = 5, weightKg = 60.0, side = side)
        .let { row(it.type, it.toPayload(), ts, workoutId) }

    /**
     * The owner's decision (workspace/tasks): one exercise in the catalog, added to a workout as
     * TWO "exercise added" rows — one per [HoldSide] — is TWO cards, not one that folds into a
     * single block the way every other repeated "add" does.
     */
    @Test
    fun `an exercise added once per side becomes two cards, not one`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, hangs.id, restSec = 180, side = HoldSide.LEFT),
            added(start.id, hangs.id, restSec = 180, side = HoldSide.RIGHT),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(2, workout.exercises.size)
        assertEquals(listOf(HoldSide.LEFT, HoldSide.RIGHT), workout.exercises.map { it.side })
        assertTrue("both cards name the same catalog exercise", workout.exercises.all { it.exerciseId == hangs.id })
        assertTrue(workout.exercises.all { it.isEmpty })
    }

    /** A set carrying a side lands under the card of that side, and only that one. */
    @Test
    fun `a set is filed under the card of its own side`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, hangs.id, restSec = 180, side = HoldSide.LEFT),
            added(start.id, hangs.id, restSec = 180, side = HoldSide.RIGHT),
            holdSet(hangs, today, workoutId = start.id, ts = "${today}T09:10:00", side = HoldSide.RIGHT),
        )

        val workout = buildWorkout(events, start.id)!!
        val left = workout.exercises.single { it.side == HoldSide.LEFT }
        val right = workout.exercises.single { it.side == HoldSide.RIGHT }
        assertTrue("the untouched hand stays empty", left.isEmpty)
        assertEquals(1, right.sets.size)
        assertEquals(1, workout.setCount)
    }

    /**
     * The same bucketing, for a [StrengthSet] instead of a [HoldSet] — a pistol squat rather
     * than a hang. Before [LoadedSet] carried [LoadedSet.side], a strength set's side was
     * always read as null regardless of which card it was logged from, so it fell into
     * neither the left nor the right bucket and landed on a THIRD, sideless one instead.
     */
    @Test
    fun `a strength set is filed under the card of its own side too`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, restSec = 180, side = HoldSide.LEFT),
            added(start.id, bench.id, restSec = 180, side = HoldSide.RIGHT),
            strengthSet(bench, today, workoutId = start.id, ts = "${today}T09:10:00", side = HoldSide.RIGHT),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals("no third, sideless card was created", 2, workout.exercises.size)
        val left = workout.exercises.single { it.side == HoldSide.LEFT }
        val right = workout.exercises.single { it.side == HoldSide.RIGHT }
        assertTrue("the untouched leg stays empty", left.isEmpty)
        assertEquals(1, right.sets.size)
        assertEquals(1, workout.setCount)
    }

    /**
     * Removing one card is removing its OWN "added" rows and its OWN sets — the other card, and
     * the sets belonging to it, are a different block entirely and are left standing. This is
     * what `WorkoutExercise.addedEventIds` + `.sets` already gives a caller for free, once the
     * two cards fold into two separate blocks.
     */
    @Test
    fun `removing one card leaves the other card of the same exercise untouched`() {
        val start = started(today)
        val leftAdded = added(start.id, hangs.id, restSec = 180, side = HoldSide.LEFT)
        val rightAdded = added(start.id, hangs.id, restSec = 180, side = HoldSide.RIGHT, ts = "${today}T09:02:00")
        val rightSet = holdSet(hangs, today, workoutId = start.id, ts = "${today}T09:10:00", side = HoldSide.RIGHT)
        val events = listOf(start, leftAdded, rightAdded, rightSet)

        val right = buildWorkout(events, start.id)!!.exercises.single { it.side == HoldSide.RIGHT }
        assertEquals(listOf(rightAdded.id), right.addedEventIds)
        assertEquals(listOf(rightSet.id), right.sets.map { it.id })

        val deletion = row(
            TYPE_ENTRY_DELETED,
            payloadJson.encodeToString(EntryDeleted(targetUid = rightAdded.uid)),
            rightAdded.ts,
        )
        val deleteRightSet = row(
            TYPE_ENTRY_DELETED,
            payloadJson.encodeToString(EntryDeleted(targetUid = rightSet.uid)),
            rightSet.ts,
        )
        val afterRemoval = buildWorkout(events + deletion + deleteRightSet, start.id)!!

        assertEquals(listOf(HoldSide.LEFT), afterRemoval.exercises.map { it.side })
        assertEquals(0, afterRemoval.setCount)
    }

    /**
     * The append-only journal remembers nothing about a removed card: the same exercise added
     * to another workout arrives with both cards again, whichever one was ever removed from an
     * earlier session. There is no per-exercise "do not offer the right hand" flag anywhere.
     */
    @Test
    fun `a card removed from one workout comes back whole the next time the exercise is added`() {
        val first = started(today, ts = "${today}T08:00:00")
        val firstLeft = added(first.id, hangs.id, restSec = 180, side = HoldSide.LEFT, ts = "${today}T08:01:00")
        val firstRight = added(first.id, hangs.id, restSec = 180, side = HoldSide.RIGHT, ts = "${today}T08:02:00")
        val removeRight = row(
            TYPE_ENTRY_DELETED,
            payloadJson.encodeToString(EntryDeleted(targetUid = firstRight.uid)),
            firstRight.ts,
        )
        val second = started(today, ts = "${today}T19:00:00")
        val secondLeft = added(second.id, hangs.id, restSec = 180, side = HoldSide.LEFT, ts = "${today}T19:01:00")
        val secondRight = added(second.id, hangs.id, restSec = 180, side = HoldSide.RIGHT, ts = "${today}T19:02:00")
        val events = listOf(first, firstLeft, firstRight, removeRight, second, secondLeft, secondRight)

        assertEquals(
            "the earlier workout kept the removal",
            listOf(HoldSide.LEFT),
            buildWorkout(events, first.id)!!.exercises.map { it.side },
        )
        assertEquals(
            "the later one was never told about it",
            listOf(HoldSide.LEFT, HoldSide.RIGHT),
            buildWorkout(events, second.id)!!.exercises.map { it.side },
        )
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
    fun `a weigh-in recorded during a workout is kept but does not count as a set`() {
        val start = started(today)
        val weigh = bodyweightOf(today, weightKg = 74.2)
            .let { row(it.type, it.toPayload(), "${today}T09:05:00", start.id) }
        val events = listOf(start, weigh, set(bench, today, workoutId = start.id))

        val workout = buildWorkout(events, start.id)!!
        assertEquals(1, workout.exercises.size)
        // kept rather than dropped, so a card is still drawn for it
        assertEquals(1, workout.entriesWithoutExercise.size)
        // but not counted: stepping on the scales is not a rep, and the one real set here
        // must not be reported as two
        assertEquals(1, workout.setCount)
    }

    @Test
    fun `a workout holding only a weigh-in has no sets at all`() {
        val start = started(today)
        val weigh = bodyweightOf(today, weightKg = 74.2)
            .let { row(it.type, it.toPayload(), "${today}T09:05:00", start.id) }
        val events = listOf(start, weigh)

        val workout = buildWorkout(events, start.id)!!
        assertEquals(0, workout.exercises.size)
        assertEquals(1, workout.entriesWithoutExercise.size)
        assertEquals(0, workout.setCount)
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

    // --- finishing one card ------------------------------------------------------------

    @Test
    fun `a finished card floats above every active one, whatever order they were added in`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, 150),
            added(start.id, squat.id, 180),
            added(start.id, hangs.id, 120),
            // the middle one is the one that gets done
            cardFinished(start.id, squat.id),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(
            "the finished card comes first, the active ones keep their own order behind it",
            listOf(squat.id, bench.id, hangs.id),
            workout.exercises.map { it.exerciseId },
        )
        assertTrue(workout.exercises.first().finished)
        assertTrue(workout.exercises.drop(1).none { it.finished })
    }

    /**
     * Not "at the top" but "above the active ones", and the owner asked for the difference:
     * who finished first has to stay readable, so the finished group keeps the order the
     * finishing happened in rather than the order the cards were added in.
     */
    @Test
    fun `finished cards are ordered by when they were finished`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, 150),
            added(start.id, squat.id, 180),
            // squat is done first, though bench was added first
            cardFinished(start.id, squat.id, ts = "${today}T09:30:00"),
            cardFinished(start.id, bench.id, ts = "${today}T09:45:00"),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(
            listOf(squat.id, bench.id),
            workout.exercises.map { it.exerciseId },
        )
    }

    /**
     * One hand at a time: the card is the pair of exercise and side, so finishing the right
     * hand has to leave the left one alone and still active.
     */
    @Test
    fun `finishing one hand leaves the other hand active`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, hangs.id, 120, side = HoldSide.LEFT),
            added(start.id, hangs.id, 120, side = HoldSide.RIGHT),
            cardFinished(start.id, hangs.id, side = HoldSide.RIGHT),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(2, workout.exercises.size)
        val done = workout.exercises.filter { it.finished }
        assertEquals("only the right hand is done", 1, done.size)
        assertEquals(HoldSide.RIGHT, done.single().side)
    }

    /*
     * NOT covered here: undo. Un-finishing deletes the finish event, and deletions are
     * addressed by an event's IDENTITY rather than by its row number, which this file's
     * hand-built rows do not carry. It is covered where the plumbing actually lives - see
     * ActivityRepository.unfinishWorkoutExercise - and stating the gap beats a test that
     * quietly checks something easier.
     */

    // --- a deleted exercise's card disappears from the workout, not just its sets --------

    private fun exerciseDeleted(targetId: Long, ts: String = "2026-08-07T21:00:00") =
        row(TYPE_EXERCISE_DELETED, payloadJson.encodeToString(ExerciseDeleted(targetId = targetId)), ts)

    /**
     * The gap a per-row filter would leave: hiding only [bench]'s SETS still leaves the
     * "added" row standing, and `buildWorkout` would then draw an EMPTY card for an exercise
     * that is supposed to be gone entirely. The whole point of the cascade in
     * domain/Amendments.kt is that the "added" row folds dead along with the sets, in the same
     * pass, so no such ghost card is possible.
     */
    @Test
    fun `deleting an exercise removes its whole card from an open workout, not just its sets`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, 90),
            set(bench, today, workoutId = start.id),
            added(start.id, squat.id, 120),
            set(squat, today, workoutId = start.id),
            exerciseDeleted(bench.id),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(listOf(squat.id), workout.exercises.map { it.exerciseId })
    }

    /** A finished card of a deleted exercise does not survive as an empty finished ghost. */
    @Test
    fun `deleting an exercise removes its finished card too`() {
        val start = started(today)
        val events = listOf(
            start,
            added(start.id, bench.id, 90),
            set(bench, today, workoutId = start.id),
            cardFinished(start.id, bench.id),
            exerciseDeleted(bench.id),
        )

        assertTrue(buildWorkout(events, start.id)!!.exercises.isEmpty())
    }

    /** Undoing the exercise deletion brings its card back, same as any other undo in this app. */
    @Test
    fun `undoing the exercise deletion brings the card back`() {
        val start = started(today)
        val gone = exerciseDeleted(bench.id, ts = "2026-08-07T21:00:00")
        val undo = row(
            TYPE_ENTRY_DELETED, payloadJson.encodeToString(EntryDeleted(gone.uid)), "2026-08-07T22:00:00",
        )
        val events = listOf(start, added(start.id, bench.id, 90), set(bench, today, workoutId = start.id), gone, undo)

        assertEquals(listOf(bench.id), buildWorkout(events, start.id)!!.exercises.map { it.exerciseId })
    }

    // --- starting a workout like a past one (§13.9) -----------------------------------

    private val pistol = ExerciseRef(4, "Pistol squat", ExerciseForm.STRENGTH, oneSided = true)

    @Test
    fun `three workouts sharing a name appear once in the past-names list`() {
        val c = started("2026-08-03", ts = "2026-08-03T08:00:00", name = "Push day")
        val b = started("2026-08-05", ts = "2026-08-05T08:00:00", name = "Push day")
        val a = started(today, ts = "${today}T08:00:00", name = "Push day")
        // journal order is not name order, and this must not depend on it
        val events = listOf(b, a, c)

        assertEquals(listOf("Push day"), pastWorkoutNames(events))
    }

    @Test
    fun `a nameless workout contributes nothing to the past-names list`() {
        val named = started(today, ts = "${today}T08:00:00", name = "Push day")
        val nameless = started("2026-08-05", ts = "2026-08-05T08:00:00")

        assertEquals(listOf("Push day"), pastWorkoutNames(listOf(named, nameless)))
    }

    @Test
    fun `the most recently used name sits at the top of the list`() {
        val pull = started("2026-08-01", ts = "2026-08-01T08:00:00", name = "Pull day")
        val push = started("2026-08-05", ts = "2026-08-05T08:00:00", name = "Push day")

        assertEquals(listOf("Push day", "Pull day"), pastWorkoutNames(listOf(pull, push)))
    }

    @Test
    fun `lastWorkoutNamed resolves to the most recently started workout under that name`() {
        val old = started("2026-08-01", ts = "2026-08-01T08:00:00", name = "Push day")
        val mid = started("2026-08-03", ts = "2026-08-03T08:00:00", name = "Push day")
        val recent = started("2026-08-05", ts = "2026-08-05T08:00:00", name = "Push day")
        val events = listOf(
            old, added(old.id, bench.id, restSec = 90, ts = "2026-08-01T08:01:00"),
            mid, added(mid.id, squat.id, restSec = 120, ts = "2026-08-03T08:01:00"),
            recent, added(recent.id, hangs.id, restSec = 45, ts = "2026-08-05T08:01:00"),
        )

        val found = lastWorkoutNamed(events, "Push day")
        assertEquals(recent.id, found!!.id)
        assertEquals(listOf(hangs.id), found.exercises.map { it.exerciseId })
    }

    @Test
    fun `a name never used before resolves to no template`() {
        val events = listOf(started(today, name = "Push day"))
        assertNull(lastWorkoutNamed(events, "Leg day"))
    }

    /**
     * "Removed" here means a card taken OFF this workout — its own "added" row deleted, the
     * same way [xyz.oleolegka.gachimuchi.data.ActivityRepository.deleteEntry] does it for a
     * workout still being edited — and not the catalog exercise itself being deleted.
     */
    @Test
    fun `an exercise removed from the source workout is not among what is copied`() {
        val start = started(today, name = "Push day")
        val benchAdded = added(start.id, bench.id, restSec = 90)
        val squatAdded = added(start.id, squat.id, restSec = 120, ts = "${today}T08:02:00")
        val removeBench = row(
            TYPE_ENTRY_DELETED, payloadJson.encodeToString(EntryDeleted(benchAdded.uid)), "${today}T08:05:00",
        )
        val events = listOf(start, benchAdded, squatAdded, removeBench)

        val workout = lastWorkoutNamed(events, "Push day")!!
        assertEquals(listOf(squat.id), asPlanned(workout).map { it.exerciseId })
    }

    @Test
    fun `the rest recorded on the source card travels with it through resolvedCards`() {
        val start = started(today, name = "Push day")
        val workout = buildWorkout(listOf(start, added(start.id, bench.id, restSec = 137)), start.id)!!

        val cards = resolvedCards(asPlanned(workout), refOf = { bench }, restFallback = { 0 })

        assertEquals(listOf(137), cards.map { it.restSec })
    }

    /**
     * A one-sided exercise's two cards ALREADY exist as two separate entries in a real
     * workout — see [WorkoutExercise.side]. Reading them back through [asPlanned] and
     * [resolvedCards] must hand back exactly two cards, not four: [resolvedCards] must not
     * fan an entry that already names a side, however the catalog's current
     * [ExerciseRef.oneSided] flag reads.
     */
    @Test
    fun `a one-sided exercise's two cards travel as two, not four`() {
        val start = started(today, name = "Hangboard")
        val events = listOf(
            start,
            added(start.id, hangs.id, restSec = 60, side = HoldSide.LEFT),
            added(start.id, hangs.id, restSec = 60, side = HoldSide.RIGHT, ts = "${today}T09:02:00"),
        )
        val workout = lastWorkoutNamed(events, "Hangboard")!!
        val planned = asPlanned(workout)
        assertEquals(listOf(HoldSide.LEFT, HoldSide.RIGHT), planned.map { it.side })

        val oneSided = hangs.copy(oneSided = true)
        val cards = resolvedCards(planned, refOf = { oneSided }, restFallback = { 0 })

        assertEquals(2, cards.size)
        assertEquals(listOf(HoldSide.LEFT, HoldSide.RIGHT), cards.map { it.side })
    }

    /** The plan side of the same funnel: an entry naming no side IS fanned, off the catalog flag. */
    @Test
    fun `resolvedCards fans a one-sided plan entry - no side of its own - into two cards`() {
        val planned = listOf(PlannedExercise(pistol.id, restSec = 60))

        val cards = resolvedCards(planned, refOf = { pistol }, restFallback = { 0 })

        assertEquals(2, cards.size)
        assertEquals(listOf(HoldSide.LEFT, HoldSide.RIGHT), cards.map { it.side })
        assertTrue(cards.all { it.exerciseId == pistol.id })
    }

    @Test
    fun `resolvedCards falls back when the source names no rest of its own`() {
        val planned = listOf(PlannedExercise(bench.id, restSec = null))

        val cards = resolvedCards(planned, refOf = { bench }, restFallback = { 99 })

        assertEquals(listOf(99), cards.map { it.restSec })
    }

}
