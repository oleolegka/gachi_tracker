package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The workout nobody finished, closed by inactivity — decisions §18.18.
 *
 * The defect: only the button and "start closes the previous" ever closed a workout, so one
 * left open stayed open, and a set logged a week later with no workout started went INTO it.
 * The journal grew a workout a week long and the week's statistics went with it.
 *
 * Pure reducers, so the clock is a parameter here and the tests are not at the mercy of one.
 */
class AbandonedWorkoutTest {

    private var nextId = 1L

    private fun row(type: String, payload: String, ts: String, workoutId: Long? = null) =
        JournalEvent(nextId++, ts, 1, 1, type, payload, workoutId)

    private fun started(opDate: String, ts: String = "${opDate}T09:00:00") =
        row(TYPE_WORKOUT_STARTED, payloadJson.encodeToString(WorkoutStarted(opDate)), ts)

    private fun finished(workoutId: Long, ts: String, auto: Boolean = false) = row(
        TYPE_WORKOUT_FINISHED,
        payloadJson.encodeToString(WorkoutFinished(workoutId, auto = auto)),
        ts,
        workoutId,
    )

    private fun set(opDate: String, ts: String, workoutId: Long?) =
        strengthSetOf(bench, opDate, reps = 5, weightKg = 60.0)
            .let { row(it.type, it.toPayload(), ts, workoutId) }

    private val bench = ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH)
    private val day = "2026-08-07"

    private fun at(ts: String): LocalDateTime = LocalDateTime.parse(ts)

    // --- the threshold ------------------------------------------------------------------

    @Test
    fun `a workout still inside the window is not abandoned`() {
        val start = started(day)
        val events = listOf(start, set(day, "${day}T19:30:00", start.id))

        // three and a half hours after the last set: a long session with a cool-down in it
        assertNull(abandonedWorkoutRow(events, at("${day}T23:00:00")))
    }

    @Test
    fun `a workout untouched for the threshold is abandoned`() {
        val start = started(day)
        val events = listOf(start, set(day, "${day}T19:30:00", start.id))

        assertEquals(start.id, abandonedWorkoutRow(events, at("${day}T23:30:00"))?.id)
    }

    /**
     * The boundary is stated so it cannot drift: four hours EXACTLY closes it. Which side the
     * boundary falls on does not matter to the owner; that it is a decided number and not an
     * accident of an operator does.
     */
    @Test
    fun `the threshold is four hours, inclusive`() {
        assertEquals(4L, WORKOUT_IDLE_HOURS)
        val start = started(day)
        val events = listOf(start, set(day, "${day}T19:00:00", start.id))

        assertNull(abandonedWorkoutRow(events, at("${day}T22:59:59")))
        assertEquals(start.id, abandonedWorkoutRow(events, at("${day}T23:00:00"))?.id)
    }

    /** Started and forgotten with nothing recorded: measured from the start itself. */
    @Test
    fun `an empty workout is measured from its own start`() {
        val start = started(day, ts = "${day}T09:00:00")

        assertNull(abandonedWorkoutRow(listOf(start), at("${day}T12:00:00")))
        assertEquals(start.id, abandonedWorkoutRow(listOf(start), at("${day}T13:30:00"))?.id)
    }

    /** Every set pushes the deadline out — the workout is idle, not old. */
    @Test
    fun `a set recorded into it starts the clock again`() {
        val start = started(day)
        val events = listOf(
            start,
            set(day, "${day}T09:10:00", start.id),
            set(day, "${day}T21:00:00", start.id),
        )

        // ten hours after the FIRST set and one after the last: still training
        assertNull(abandonedWorkoutRow(events, at("${day}T22:00:00")))
    }

    // --- what it does not do ------------------------------------------------------------

    @Test
    fun `a workout already finished is not abandoned again`() {
        val start = started(day)
        val events = listOf(
            start,
            set(day, "${day}T19:30:00", start.id),
            finished(start.id, "${day}T19:40:00"),
        )

        assertNull(abandonedWorkoutRow(events, at("2026-08-14T10:00:00")))
    }

    @Test
    fun `no workout at all is nothing to close`() {
        assertNull(abandonedWorkoutRow(listOf(set(day, "${day}T19:30:00", null)), at("2026-08-14T10:00:00")))
    }

    /** A clock that has gone backwards closes nothing, which is the safe direction. */
    @Test
    fun `a clock reading earlier than the last set closes nothing`() {
        val start = started(day)
        val events = listOf(start, set(day, "${day}T19:30:00", start.id))

        assertNull(abandonedWorkoutRow(events, at("${day}T08:00:00")))
    }

    // --- the end time, which is the point of the whole thing ----------------------------

    /**
     * §18.18's own words: the finish is dated by the LAST ENTRY, not by the moment it fires,
     * so "the workout becomes what it was rather than stretching to the evening".
     *
     * Nothing has to arrange that — [Workout.endTs] is read off the last set and the finish
     * event carries no time at all — and this is what pins it down, because an "ended at"
     * field on the event is the obvious thing a later change would add.
     */
    @Test
    fun `closing it a week later still ends it at its last set`() {
        val start = started(day)
        val events = listOf(
            start,
            set(day, "${day}T19:30:00", start.id),
            // noticed the following Tuesday, and written then
            finished(start.id, "2026-08-14T10:05:00", auto = true),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals("${day}T19:30:00", workout.endTs)
        assertTrue(workout.finished)
        assertTrue(workout.finishedAutomatically)
    }

    /** And a workout closed by the button says so, so the screen can tell them apart. */
    @Test
    fun `a workout closed by the button is not marked as the app's doing`() {
        val start = started(day)
        val events = listOf(start, set(day, "${day}T19:30:00", start.id), finished(start.id, "${day}T19:40:00"))

        val workout = buildWorkout(events, start.id)!!
        assertTrue(workout.finished)
        assertFalse(workout.finishedAutomatically)
    }

    /**
     * A row written before the flag existed reads as the user's own doing, which is what it
     * was: nothing but the button and "start closes the previous" wrote one until now.
     */
    @Test
    fun `a finish event with no auto field reads as the user's own`() {
        val start = started(day)
        val legacy = row(
            TYPE_WORKOUT_FINISHED,
            """{"workout_id":${start.id}}""",
            "${day}T19:40:00",
            start.id,
        )

        val workout = buildWorkout(listOf(start, set(day, "${day}T19:30:00", start.id), legacy), start.id)!!
        assertTrue(workout.finished)
        assertFalse(workout.finishedAutomatically)
    }

    // --- being overruled ----------------------------------------------------------------

    private fun reopened(target: JournalEvent, ts: String) = row(
        TYPE_ENTRY_DELETED,
        payloadJson.encodeToString(EntryDeleted(targetUid = target.uid)),
        ts,
    )

    /**
     * Reopening beats the clock, and KEEPS beating it. The automatic close is an ordinary
     * event, so deleting it puts the workout back — but reopening records no training, so the
     * last set is exactly as old as it was and a rule that only looked at the clock would shut
     * the workout again on the very next look. The owner would press a button and watch it
     * undone, and "even if we close the wrong one it is not critical" would stop being true.
     */
    @Test
    fun `reopening an automatically closed workout puts it back and keeps it back`() {
        val start = started(day)
        val autoClose = finished(start.id, "2026-08-14T10:05:00", auto = true)
        val events = listOf(
            start,
            set(day, "${day}T19:30:00", start.id),
            autoClose,
            reopened(autoClose, "2026-08-14T10:06:00"),
        )

        val back = openWorkout(events)
        assertEquals(start.id, back?.id)
        assertFalse(back!!.finished)
        assertNull("overruled once is overruled", abandonedWorkoutRow(events, at("2026-08-14T10:07:00")))
        assertNull("and still, days later", abandonedWorkoutRow(events, at("2026-08-20T10:00:00")))
    }

    /**
     * Overruled once is about THAT workout, not about the rule. A workout started afterwards
     * gets the timeout afresh — otherwise one reopen would switch the whole thing off.
     */
    @Test
    fun `a later workout is still closed after being overruled on an earlier one`() {
        val first = started(day)
        val autoClose = finished(first.id, "${day}T23:30:00", auto = true)
        val second = started("2026-08-09", ts = "2026-08-09T09:00:00")
        val events = listOf(
            first,
            set(day, "${day}T19:30:00", first.id),
            autoClose,
            reopened(autoClose, "${day}T23:31:00"),
            second,
            set("2026-08-09", "2026-08-09T09:30:00", second.id),
        )

        assertEquals(second.id, abandonedWorkoutRow(events, at("2026-08-09T14:00:00"))?.id)
    }

    /**
     * A close the USER pressed and then took back does not buy immunity: the rule has not said
     * anything about that workout yet, so it still gets to.
     */
    @Test
    fun `undoing a hand-pressed finish does not switch the timeout off`() {
        val start = started(day)
        val byHand = finished(start.id, "${day}T19:40:00")
        val events = listOf(
            start,
            set(day, "${day}T19:30:00", start.id),
            byHand,
            reopened(byHand, "${day}T19:41:00"),
        )

        assertEquals(start.id, abandonedWorkoutRow(events, at("2026-08-08T10:00:00"))?.id)
    }
}
