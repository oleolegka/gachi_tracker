package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The order of the exercises inside a workout, and the one event that states it.
 *
 * The default — the order they were added in — is [WorkoutTest]'s business and is only
 * re-checked here where it is the thing an order event is required NOT to disturb. What this
 * class is about is the four ways an order event can be out of date with the workout it
 * describes, because an append-only journal cannot go back and correct one: an exercise removed
 * after it was written, an exercise added after it was written, an entry naming something this
 * workout never held, and the event itself being deleted.
 *
 * Pure reducers, so all of it runs on the JVM with no Room and no device.
 */
class WorkoutOrderTest {

    private var nextId = 1L

    private fun row(type: String, payload: String, ts: String, workoutId: Long? = null) =
        JournalEvent(nextId++, ts, 1, 1, type, payload, workoutId)

    private fun started(opDate: String = today) =
        row(TYPE_WORKOUT_STARTED, payloadJson.encodeToString(WorkoutStarted(opDate)), "${opDate}T09:00:00")

    private fun added(workoutId: Long, exercise: ExerciseRef, at: String = "09:01") =
        row(
            TYPE_WORKOUT_EXERCISE_ADDED,
            payloadJson.encodeToString(WorkoutExerciseAdded(workoutId, exercise.id, restSec = 120)),
            "${today}T$at:00",
            workoutId,
        )

    /** The order stated the way the app states it: the whole list, by exercise number. */
    private fun orderOf(workoutId: Long, vararg exercises: ExerciseRef, at: String = "09:30") =
        row(
            TYPE_WORKOUT_ORDER_SET,
            payloadJson.encodeToString(
                WorkoutOrder(workoutId, exercises.map { OrderedExercise(exerciseId = it.id) })
            ),
            "${today}T$at:00",
            workoutId,
        )

    private fun set(exercise: ExerciseRef, workoutId: Long, at: String = "09:10") =
        strengthSetOf(exercise, today, reps = 5, weightKg = 60.0)
            .let { row(it.type, it.toPayload(), "${today}T$at:00", workoutId) }

    /** Removal the way the app does it: a new row naming the target's identity. */
    private fun deletion(target: JournalEvent) =
        row(
            TYPE_ENTRY_DELETED,
            payloadJson.encodeToString(EntryDeleted(targetUid = target.uid)),
            target.ts,
        )

    private val bench = ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH)
    private val squat = ExerciseRef(2, "Squat", ExerciseForm.STRENGTH)
    private val rows = ExerciseRef(3, "Barbell row", ExerciseForm.STRENGTH)
    private val curls = ExerciseRef(4, "Curls", ExerciseForm.STRENGTH)

    private val today = "2026-08-07"

    /** The names on the cards, in the order the screen would draw them. */
    private fun orderIn(events: List<JournalEvent>, workoutId: Long): List<Long?> =
        buildWorkout(events, workoutId)!!.exercises.map { it.exerciseId }

    // --- the ordinary case ------------------------------------------------------------

    @Test
    fun `with no order event the exercises stay in the order they were added`() {
        val start = started()
        val events = listOf(start, added(start.id, bench), added(start.id, squat), added(start.id, rows))

        assertEquals(listOf(bench.id, squat.id, rows.id), orderIn(events, start.id))
    }

    @Test
    fun `an order event puts them in the order it states`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat), added(start.id, rows),
            orderOf(start.id, rows, bench, squat),
        )

        assertEquals(listOf(rows.id, bench.id, squat.id), orderIn(events, start.id))
    }

    /**
     * The rest of the block travels with it. The order event names exercises and nothing else,
     * so a card that moves has to arrive with its sets and its chosen rest intact — otherwise
     * reordering would be a way to lose training.
     */
    @Test
    fun `moving an exercise carries its sets and its rest with it`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat),
            set(bench, start.id), set(bench, start.id, at = "09:15"),
            orderOf(start.id, squat, bench),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(listOf(squat.id, bench.id), workout.exercises.map { it.exerciseId })
        assertEquals(2, workout.exercises[1].sets.size)
        assertEquals(120, workout.exercises[1].restSec)
        assertEquals(0, workout.exercises[0].sets.size)
    }

    @Test
    fun `the last order event wins`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat), added(start.id, rows),
            orderOf(start.id, rows, squat, bench, at = "09:30"),
            orderOf(start.id, squat, bench, rows, at = "09:40"),
        )

        assertEquals(listOf(squat.id, bench.id, rows.id), orderIn(events, start.id))
    }

    // --- the four ways an order event goes out of date --------------------------------

    /**
     * The requirement this event exists to satisfy without surprising anybody: the exercise you
     * add halfway through the session appears at the BOTTOM, where "add" has always put things,
     * rather than vanishing from a list that does not name it or jumping to the top of one.
     */
    @Test
    fun `an exercise added after the order event goes to the end`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat),
            orderOf(start.id, squat, bench, at = "09:30"),
            added(start.id, rows, at = "09:45"),
        )

        assertEquals(listOf(squat.id, bench.id, rows.id), orderIn(events, start.id))
    }

    /**
     * The same rule for the other way an exercise can arrive: nobody added it, a set simply
     * named it. That set is training and the workout has to show it — at the end, because it
     * postdates everything the order event could have meant.
     */
    @Test
    fun `an exercise that only a later set named goes to the end`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat),
            orderOf(start.id, squat, bench, at = "09:30"),
            set(curls, start.id, at = "09:50"),
        )

        assertEquals(listOf(squat.id, bench.id, curls.id), orderIn(events, start.id))
    }

    @Test
    fun `an exercise removed from the workout drops out of the order silently`() {
        val start = started()
        val benchRow = added(start.id, bench)
        val events = listOf(
            start,
            benchRow, added(start.id, squat), added(start.id, rows),
            orderOf(start.id, rows, bench, squat),
            deletion(benchRow),
        )

        assertEquals(listOf(rows.id, squat.id), orderIn(events, start.id))
    }

    @Test
    fun `an order event naming an exercise this workout never held is ignored`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat),
            // curls is a stranger here; the two that are named must still be arranged
            orderOf(start.id, curls, squat, bench),
        )

        assertEquals(listOf(squat.id, bench.id), orderIn(events, start.id))
    }

    /**
     * DELETING THE ORDER EVENT RETURNS THE PREVIOUS ONE, because the fold runs on the live
     * journal like every other reader (domain/Amendments.kt). There is no "undo the reordering"
     * event and there must not be one: the way back from any row in this journal is deleting it.
     */
    @Test
    fun `deleting the last order event brings the one before it back`() {
        val start = started()
        // built in journal order, because the row NUMBERS decide which exercises an order
        // event could have meant and a fixture that hands them out backwards tests nothing
        val adds = listOf(added(start.id, bench), added(start.id, squat), added(start.id, rows))
        val first = orderOf(start.id, rows, squat, bench, at = "09:30")
        val second = orderOf(start.id, bench, rows, squat, at = "09:40")
        val events = listOf(start) + adds + listOf(first, second, deletion(second))

        assertEquals(listOf(rows.id, squat.id, bench.id), orderIn(events, start.id))
    }

    @Test
    fun `deleting every order event brings the order they were added in back`() {
        val start = started()
        val adds = listOf(added(start.id, bench), added(start.id, squat), added(start.id, rows))
        val stated = orderOf(start.id, rows, squat, bench)
        val events = listOf(start) + adds + listOf(stated, deletion(stated))

        // it did take effect before the deletion, or this would prove nothing
        assertEquals(listOf(rows.id, squat.id, bench.id), orderIn(listOf(start) + adds + stated, start.id))
        assertEquals(listOf(bench.id, squat.id, rows.id), orderIn(events, start.id))
    }

    /**
     * A deletion that is itself deleted puts the reordering back — the recursion domain/
     * Amendments.kt is built on, checked here because an order event is the newest kind of row
     * to go through that funnel and nothing else proves it does.
     */
    @Test
    fun `undoing the deletion of an order event restores it`() {
        val start = started()
        val adds = listOf(added(start.id, bench), added(start.id, squat), added(start.id, rows))
        val stated = orderOf(start.id, rows, squat, bench)
        val undone = deletion(stated)
        val events = listOf(start) + adds + listOf(stated, undone, deletion(undone))

        assertEquals(listOf(rows.id, squat.id, bench.id), orderIn(events, start.id))
    }

    /**
     * An exercise taken out and later put back is a NEW arrival, not the old one returning to
     * its slot. Its first row postdates the order event, so the stale entry naming it does not
     * apply — the same rule that sends every other late arrival to the bottom.
     */
    @Test
    fun `an exercise removed and added again goes to the end rather than to its old place`() {
        val start = started()
        val benchRow = added(start.id, bench)
        val events = listOf(
            start,
            benchRow, added(start.id, squat), added(start.id, rows),
            orderOf(start.id, bench, rows, squat, at = "09:30"),
            deletion(benchRow),
            added(start.id, bench, at = "09:50"),
        )

        assertEquals(listOf(rows.id, squat.id, bench.id), orderIn(events, start.id))
    }

    /**
     * Adding an exercise AGAIN is how the rest is changed in an append-only journal, and it must
     * not be read as an arrival: the card would drop to the bottom of a list the user has just
     * arranged, on a gesture that was about the pause.
     */
    @Test
    fun `changing the rest of an exercise does not move it out of the stated order`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat), added(start.id, rows),
            orderOf(start.id, rows, bench, squat, at = "09:30"),
            row(
                TYPE_WORKOUT_EXERCISE_ADDED,
                payloadJson.encodeToString(WorkoutExerciseAdded(start.id, rows.id, restSec = 240)),
                "${today}T09:50:00",
                start.id,
            ),
        )

        assertEquals(listOf(rows.id, bench.id, squat.id), orderIn(events, start.id))
        assertEquals(240, buildWorkout(events, start.id)!!.exercises[0].restSec)
    }

    // --- rows that cannot be read at all ----------------------------------------------

    /**
     * A damaged order row leaves the workout in added order rather than taking the screen down.
     * Same rule as every other reader here: one bad row costs its own effect and nothing else.
     */
    @Test
    fun `an unreadable order payload leaves the added order alone`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat),
            row(TYPE_WORKOUT_ORDER_SET, "{not json at all", "${today}T09:30:00", start.id),
        )

        assertEquals(listOf(bench.id, squat.id), orderIn(events, start.id))
    }

    /**
     * ... and a damaged row is not "the last order event" either: the readable one before it
     * still decides, which is the difference between losing one row and losing the feature.
     */
    @Test
    fun `an unreadable order payload does not override a readable one`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat),
            orderOf(start.id, squat, bench, at = "09:30"),
            row(TYPE_WORKOUT_ORDER_SET, "{not json at all", "${today}T09:40:00", start.id),
        )

        assertEquals(listOf(squat.id, bench.id), orderIn(events, start.id))
    }

    /** An entry naming no exercise at all identifies nothing and is refused on the way in. */
    @Test
    fun `an order entry naming neither a uid nor an id is refused`() {
        assertThrows(IllegalArgumentException::class.java) { OrderedExercise() }
    }

    // --- identity, and journals from elsewhere -----------------------------------------

    /**
     * The order is matched through [ExerciseLink] like every other "is this the same exercise"
     * question, so an event that names exercises by IDENTITY arranges blocks that were recorded
     * with numbers beside them. Two phones number their exercises independently; an order that
     * only understood numbers would rearrange the wrong cards in a merged journal.
     */
    @Test
    fun `an order stated by identity arranges blocks recorded with both`() {
        val start = started()
        val benchUid = "ex-bench"
        val squatUid = "ex-squat"
        val events = listOf(
            start,
            row(
                TYPE_WORKOUT_EXERCISE_ADDED,
                payloadJson.encodeToString(
                    WorkoutExerciseAdded(start.id, bench.id, 120, exerciseUid = benchUid)
                ),
                "${today}T09:01:00",
                start.id,
            ),
            row(
                TYPE_WORKOUT_EXERCISE_ADDED,
                payloadJson.encodeToString(
                    WorkoutExerciseAdded(start.id, squat.id, 120, exerciseUid = squatUid)
                ),
                "${today}T09:02:00",
                start.id,
            ),
            row(
                TYPE_WORKOUT_ORDER_SET,
                payloadJson.encodeToString(
                    WorkoutOrder(
                        start.id,
                        listOf(
                            OrderedExercise(exerciseUid = squatUid),
                            OrderedExercise(exerciseUid = benchUid),
                        ),
                    )
                ),
                "${today}T09:30:00",
                start.id,
            ),
        )

        assertEquals(listOf(squat.id, bench.id), orderIn(events, start.id))
    }

    /**
     * The workout is named in the payload as well as in the column, so a row that arrives from a
     * journal with none of this app's columns still lands in the right workout — the same
     * argument [WorkoutExerciseAdded] makes, checked because [workoutRef] had to learn this type.
     */
    @Test
    fun `an order row with no workout column is still claimed by its payload`() {
        val start = started()
        val events = listOf(
            start,
            added(start.id, bench), added(start.id, squat),
            row(
                TYPE_WORKOUT_ORDER_SET,
                payloadJson.encodeToString(
                    WorkoutOrder(
                        start.id,
                        listOf(OrderedExercise(exerciseId = squat.id), OrderedExercise(exerciseId = bench.id)),
                    )
                ),
                "${today}T09:30:00",
                // no column: the payload is all there is to go on
                workoutId = null,
            ),
        )

        assertEquals(listOf(squat.id, bench.id), orderIn(events, start.id))
    }

    /**
     * An order event belongs to ONE workout. On a day with two, reordering the morning's must
     * leave the evening's exactly as it was — the rows are told apart the same way every other
     * row of a workout is.
     */
    @Test
    fun `an order event does not reach into another workout`() {
        val morning = started()
        val morningRows = listOf(added(morning.id, bench), added(morning.id, squat))
        val evening = row(
            TYPE_WORKOUT_STARTED,
            payloadJson.encodeToString(WorkoutStarted(today)),
            "${today}T18:00:00",
        )
        val eveningRows = listOf(added(evening.id, bench, at = "18:01"), added(evening.id, squat, at = "18:02"))
        val events = listOf(morning) + morningRows + listOf(evening) + eveningRows +
            listOf(orderOf(morning.id, squat, bench))

        assertEquals(listOf(squat.id, bench.id), orderIn(events, morning.id))
        assertEquals(listOf(bench.id, squat.id), orderIn(events, evening.id))
    }

    /**
     * Removing the workout takes its order rows with it. [workoutEventIds] names every row of a
     * workout so that deleting one cannot leave orphans behind; an order row that survived would
     * be inert, but it would also be a row in the journal nothing on any screen accounts for.
     */
    @Test
    fun `the rows of a workout include its order events`() {
        val start = started()
        val stated = orderOf(start.id, squat, bench)
        val events = listOf(start, added(start.id, bench), added(start.id, squat), stated)

        assertEquals(true, workoutEventIds(events, start.id).contains(stated.id))
    }

    /**
     * The weigh-in stays where it is. It names no exercise, so it is not an exercise of the
     * workout and no order event can speak about it — see [Workout.entriesWithoutExercise].
     */
    @Test
    fun `reordering leaves the entries that name no exercise alone`() {
        val start = started()
        val weight = bodyweightOf(today, 74.2)
            .let { row(it.type, it.toPayload(), "${today}T09:05:00", start.id) }
        val events = listOf(
            start, added(start.id, bench), added(start.id, squat), weight,
            orderOf(start.id, squat, bench),
        )

        val workout = buildWorkout(events, start.id)!!
        assertEquals(listOf(squat.id, bench.id), workout.exercises.map { it.exerciseId })
        assertEquals(1, workout.entriesWithoutExercise.size)
    }
}
