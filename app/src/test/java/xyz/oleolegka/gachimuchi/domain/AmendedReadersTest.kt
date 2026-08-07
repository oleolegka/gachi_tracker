package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * One test per reader of the journal: a corrected entry reads corrected EVERYWHERE, and a
 * deleted one is nowhere.
 *
 * ── Why a file organised by reader rather than by rule ──────────────────────────
 * The rule itself is covered next door in AmendmentsTest. This file exists because of the
 * failure that made the funnel necessary in the first place: the reversal event worked in
 * `readActivities` and silently did not work in the two reducers that read the raw list, so a
 * cancelled row was gone from the feed and still inside its workout. That bug is not a bug in
 * a rule — it is a reader nobody checked. Adding a reader and forgetting it here is the same
 * mistake again, so the readers are listed by name and each gets both halves of the question.
 *
 * The readers, and where each is asked below: records (domain/Records.kt), the day feed and
 * sessions (domain/Session.kt), the day's cards (domain/DayCards.kt), plan against fact
 * (domain/Schedule.kt), the heatmap and volume (domain/Analytics.kt), workouts and "last time"
 * (domain/Workout.kt, domain/LastTime.kt).
 */
class AmendedReadersTest {

    private val day = "2026-08-06"
    private val date: LocalDate = LocalDate.parse(day)
    private var nextId = 1L

    private fun ev(
        form: ActivityForm,
        ts: String = "${day}T10:00:00",
        workout: JournalEvent? = null,
    ) = JournalEvent(
        nextId++, ts, 1, 1, form.type, form.toPayload(),
        workoutId = workout?.id, workoutUid = workout?.uid,
    )

    private fun bench(weight: Double, reps: Int = 5, on: String = day) =
        StrengthSet(exercise = "Bench press", reps = reps, weightKg = weight, exerciseId = 1, opDate = on)

    private val benchLink = ExerciseLink(null, 1L)

    private fun amend(target: JournalEvent, vararg fields: Pair<String, JsonElement>) = JournalEvent(
        nextId++, "${day}T23:00:00", 1, 1, TYPE_ENTRY_AMENDED,
        payloadJson.encodeToString(EntryAmended(target.uid, JsonObject(fields.toMap()))),
    )

    private fun delete(target: JournalEvent) = JournalEvent(
        nextId++, "${day}T23:30:00", 1, 1, TYPE_ENTRY_DELETED,
        payloadJson.encodeToString(EntryDeleted(target.uid)),
    )

    // --- domain/Records.kt ---

    @Test
    fun `records read the corrected weight, not the one that was typed`() {
        val light = ev(bench(60.0))
        val heavy = ev(bench(100.0))

        // a strength record is an ESTIMATED 1RM and not the bare weight, so it is stated here
        // through the same function the reducer uses rather than as a number copied out of it
        val before = strengthRecord(readActivities(listOf(light, heavy)), benchLink)!!
        assertEquals(est1rm(100.0, 5), before.value, 1e-9)
        assertTrue("the record should quote the set it came from", before.text.contains("100"))

        // the 100 was a slip of the thumb for 10.0 and gets corrected: the record has to fall
        // back to the set that is now the heaviest
        val fixed = readActivities(listOf(light, heavy, amend(heavy, "weight_kg" to JsonPrimitive(10.0))))
        assertEquals(est1rm(60.0, 5), strengthRecord(fixed, benchLink)!!.value, 1e-9)
    }

    @Test
    fun `a deleted set cannot hold a record`() {
        val light = ev(bench(60.0))
        val heavy = ev(bench(100.0))
        val events = listOf(light, heavy, delete(heavy))

        assertEquals(est1rm(60.0, 5), strengthRecord(readActivities(events), benchLink)!!.value, 1e-9)
    }

    // --- domain/Session.kt ---

    @Test
    fun `the day's session shows the corrected value and drops the deleted entry`() {
        val first = ev(bench(60.0))
        val second = ev(bench(80.0))

        val corrected = buildSession(listOf(first, second, amend(second, "reps" to JsonPrimitive(9))), day)
        assertEquals(2, corrected.setCount)
        assertEquals(9, (corrected.groups.single().sets.last().form as StrengthSet).reps)

        val pruned = buildSession(listOf(first, second, delete(second)), day)
        assertEquals(1, pruned.setCount)
        assertEquals(60.0, (pruned.groups.single().sets.single().form as StrengthSet).weightKg!!, 1e-9)
        // and "undo the last set" now points at the one that is actually last
        assertEquals(first.id, pruned.lastEventId)
    }

    @Test
    fun `an entry corrected onto another day leaves the session it was in`() {
        val set = ev(bench(60.0))
        val events = listOf(set, amend(set, "op_date" to JsonPrimitive("2026-08-05")))

        assertEquals(0, buildSession(events, day).setCount)
        assertEquals(1, buildSession(events, "2026-08-05").setCount)
    }

    // --- domain/DayCards.kt ---

    @Test
    fun `the day's cards drop a deleted entry and follow a corrected date`() {
        val first = ev(bench(60.0), ts = "${day}T10:00:00")
        val second = ev(bench(80.0), ts = "${day}T10:20:00")
        val late = date.atTime(23, 0)

        // both entries are outside any workout, so they share one card and the count on it is
        // what the reader has to get right
        val plain = dayCards(listOf(first, second), emptyList(), date, date, late)
        assertTrue(plain.cards.single().subtitle.contains("2 entries"))

        val pruned = dayCards(listOf(first, second, delete(second)), emptyList(), date, date, late)
        assertTrue(pruned.cards.single().subtitle.contains("1 entry"))

        // corrected onto the day before: this day has nothing to show at all
        val moved = dayCards(
            listOf(
                first, second,
                amend(first, "op_date" to JsonPrimitive("2026-08-05")),
                amend(second, "op_date" to JsonPrimitive("2026-08-05")),
            ),
            emptyList(), date, date, late,
        )
        assertTrue("a day whose entries were moved off it has no cards", moved.isEmpty)
    }

    // --- domain/Schedule.kt ---

    @Test
    fun `plan against fact follows a corrected date and forgets a deleted entry`() {
        val slots = listOf(Slot(1, "Gym", "18:00", REPEAT_NONE, day))
        val set = ev(bench(60.0), ts = "${day}T18:20:00")
        val late = date.atTime(23, 0)

        fun stateOn(events: List<JournalEvent>) =
            planVsFact(slots, activityStamps(events, day, day), date, date, late).single().slots.single().state

        assertEquals(SlotState.DONE, stateOn(listOf(set)))
        // deleted: nothing closed the slot, so it goes back to being missed
        assertEquals(SlotState.MISS, stateOn(listOf(set, delete(set))))
        // corrected onto the day before: it is no longer a fact of this day at all
        assertEquals(SlotState.MISS, stateOn(listOf(set, amend(set, "op_date" to JsonPrimitive("2026-08-05")))))
    }

    // --- domain/Analytics.kt ---

    @Test
    fun `the heatmap and the hero stats stop counting a deleted entry`() {
        val set = ev(bench(60.0))
        val events = listOf(set, delete(set))

        assertEquals(1, activityHeatmap(listOf(set), date, date).totalActivities)
        assertEquals(0, activityHeatmap(events, date, date).totalActivities)
        assertEquals(0, heroStats(events, date).entries)
        assertTrue(activeDays(events, day, day).isEmpty())
    }

    @Test
    fun `the volume series is computed from the corrected weight`() {
        val set = ev(bench(60.0, reps = 5))
        val plain = volumeSeries(readActivities(listOf(set)), benchLink, ExerciseForm.STRENGTH)!!
        assertEquals(300.0, plain.points.single().value, 1e-9)

        val corrected = readActivities(listOf(set, amend(set, "weight_kg" to JsonPrimitive(70.0))))
        assertEquals(
            350.0,
            volumeSeries(corrected, benchLink, ExerciseForm.STRENGTH)!!.points.single().value,
            1e-9,
        )
    }

    @Test
    fun `a corrected date moves the day the heatmap counts it on`() {
        val set = ev(bench(60.0))
        val events = listOf(set, amend(set, "op_date" to JsonPrimitive("2026-08-05")))
        val heat = activityHeatmap(events, LocalDate.parse("2026-08-05"), date)

        assertEquals(1, heat.days.first { it.opDate == "2026-08-05" }.count)
        assertEquals(0, heat.days.first { it.opDate == day }.count)
    }

    // --- domain/Workout.kt: the reducers that used to read the raw list ---

    @Test
    fun `a workout started by mistake can be deleted, and its sets come back out of it`() {
        val start = ev(WorkoutStarted(opDate = day), ts = "${day}T09:00:00")
        val set = ev(bench(60.0), ts = "${day}T09:10:00", workout = start)
        val events = listOf(start, set)

        assertEquals(1, workoutsOn(events, day).size)
        assertNotNull(openWorkoutRow(events))
        assertTrue(setsOutsideWorkouts(events, day).isEmpty())

        val without = listOf(start, set, delete(start))
        // this is the one that used to be impossible: workoutStarts read the raw list
        assertTrue(workoutsOn(without, day).isEmpty())
        assertNull(openWorkoutRow(without))
        assertNull(buildWorkout(without, start.id))
        // the training itself is untouched - the set was not deleted, only the workout was
        assertEquals(1, buildSession(without, day).setCount)
        assertEquals(1, setsOutsideWorkouts(without, day).size)
    }

    @Test
    fun `an exercise added to a workout by mistake can be deleted out of it`() {
        val start = ev(WorkoutStarted(opDate = day), ts = "${day}T09:00:00")
        val added = ev0(
            TYPE_WORKOUT_EXERCISE_ADDED,
            payloadJson.encodeToString(WorkoutExerciseAdded(workoutId = start.id, exerciseId = 7, restSec = 90)),
            ts = "${day}T09:01:00", workout = start,
        )
        val events = listOf(start, added)

        // an exercise with no sets is exactly what the "added" event is for, so it is the
        // whole content of the block and the only thing that can be checked
        assertEquals(1, buildWorkout(events, start.id)!!.exercises.size)
        // this used to be impossible too: buildWorkout walked the raw list for these rows
        assertTrue(buildWorkout(listOf(start, added, delete(added)), start.id)!!.exercises.isEmpty())
    }

    @Test
    fun `the rest chosen for an exercise in a workout can be corrected`() {
        val start = ev(WorkoutStarted(opDate = day), ts = "${day}T09:00:00")
        val added = ev0(
            TYPE_WORKOUT_EXERCISE_ADDED,
            payloadJson.encodeToString(WorkoutExerciseAdded(workoutId = start.id, exerciseId = 7, restSec = 90)),
            ts = "${day}T09:01:00", workout = start,
        )
        val fixed = listOf(start, added, amend(added, "rest_sec" to JsonPrimitive(150)))

        assertEquals(90, buildWorkout(listOf(start, added), start.id)!!.exercises.single().restSec)
        assertEquals(150, buildWorkout(fixed, start.id)!!.exercises.single().restSec)
    }

    @Test
    fun `a workout started on the wrong day can be corrected onto the right one`() {
        val start = ev(WorkoutStarted(opDate = day), ts = "${day}T09:00:00")
        val events = listOf(start, amend(start, "op_date" to JsonPrimitive("2026-08-05")))

        assertTrue(workoutsOn(events, day).isEmpty())
        assertEquals(1, workoutsOn(events, "2026-08-05").size)
        assertEquals("2026-08-05", buildWorkout(events, start.id)!!.opDate)
    }

    @Test
    fun `deleting the finish event re-opens the workout it closed`() {
        val start = ev(WorkoutStarted(opDate = day), ts = "${day}T09:00:00")
        val finish = ev0(
            TYPE_WORKOUT_FINISHED,
            payloadJson.encodeToString(WorkoutFinished(workoutId = start.id, workoutUid = start.uid)),
            ts = "${day}T10:30:00", workout = start,
        )

        assertNotNull(openWorkoutRow(listOf(start)))
        assertNull("a finished workout is not the open one", openWorkoutRow(listOf(start, finish)))
        // pressed by mistake: there is no re-open event, deleting the finish IS the way back
        assertNotNull(openWorkoutRow(listOf(start, finish, delete(finish))))
    }

    @Test
    fun `a deleted set is not in the workout it was recorded in`() {
        val start = ev(WorkoutStarted(opDate = day), ts = "${day}T09:00:00")
        val kept = ev(bench(60.0), ts = "${day}T09:10:00", workout = start)
        val gone = ev(bench(80.0), ts = "${day}T09:20:00", workout = start)
        val events = listOf(start, kept, gone, delete(gone))

        val workout = buildWorkout(events, start.id)!!
        assertEquals(1, workout.setCount)
        assertEquals(60.0, (workout.exercises.single().sets.single().form as StrengthSet).weightKg!!, 1e-9)
    }

    // --- domain/LastTime.kt ---

    @Test
    fun `last time reads the corrected value and skips a deleted session`() {
        val monday = ev(bench(60.0, on = "2026-08-03"), ts = "2026-08-03T10:00:00")
        val thursday = ev(bench(80.0, on = day), ts = "${day}T10:00:00")

        val corrected = lastTimeOf(listOf(monday, thursday, amend(thursday, "reps" to JsonPrimitive(3))), benchLink)!!
        assertEquals(day, corrected.opDate)
        assertEquals(3, (corrected.sets.single().form as StrengthSet).reps)

        val pruned = lastTimeOf(listOf(monday, thursday, delete(thursday)), benchLink)!!
        assertEquals("2026-08-03", pruned.opDate)
    }

    @Test
    fun `last time stops holding back the sets of a workout that has been deleted`() {
        val start = ev(WorkoutStarted(opDate = day), ts = "${day}T09:00:00")
        val inside = ev(bench(60.0), ts = "${day}T09:10:00", workout = start)

        // asked BY that workout, its own sets are held back - they are already on the card
        assertNull(lastTimeOf(listOf(start, inside), benchLink, excludingWorkoutId = start.id))
        // once the workout is gone there is nothing to exclude, and the set is training that
        // happened: it must not stay invisible because of a workout that no longer exists
        val without = listOf(start, inside, delete(start))
        assertEquals(day, lastTimeOf(without, benchLink, excludingWorkoutId = start.id)!!.opDate)
    }

    /** A journal row of a service type, which has no [ActivityForm] to build it from. */
    private fun ev0(type: String, payload: String, ts: String, workout: JournalEvent? = null) =
        JournalEvent(nextId++, ts, 1, 1, type, payload, workoutId = workout?.id, workoutUid = workout?.uid)

    private fun ev(started: WorkoutStarted, ts: String) =
        ev0(TYPE_WORKOUT_STARTED, payloadJson.encodeToString(started), ts)

    @Test
    fun `no reader was left reading the raw journal`() {
        // a canary rather than a rule: every reducer named in this file is asked the same
        // question in one place, so a new one that forgets the funnel fails here as well as
        // wherever its own test would have been
        val set = ev(bench(60.0))
        val events = listOf(set, delete(set))

        assertTrue(readActivities(events).isEmpty())
        assertTrue(liveEvents(events).none { it.uid == set.uid })
        assertEquals(0, buildSession(events, day).setCount)
        assertTrue(dayCards(events, emptyList(), date, date, date.atTime(23, 0)).isEmpty)
        assertTrue(activityStamps(events, day, day).isEmpty())
        assertEquals(0, activityHeatmap(events, date, date).totalActivities)
        assertNull(strengthRecord(readActivities(events), benchLink))
        assertNull(lastTimeOf(events, benchLink))
        assertTrue(workoutsOn(events, day).isEmpty())
        assertFalse(events.first().isControlEvent())
    }

    // --- the op_date column (schema version 16) ---

    /**
     * The trap the column brings with it: the journal is append-only, so a correction that moves
     * an entry to another day does NOT rewrite the column on the row it corrects. The column goes
     * on saying the 6th while the entry belongs to the 1st, and only the amended payload knows.
     */
    @Test
    fun `an entry moved by an amendment is found under its new day and not the column's`() {
        val logged = ev(bench(80.0)).copy(opDate = day)
        val moved = amend(logged, "op_date" to JsonPrimitive("2026-08-01"))
        val events = listOf(logged, moved)

        assertEquals(day, logged.opDate)
        assertEquals(listOf("2026-08-01"), readActivities(events).map { it.opDate })
        assertEquals(1, readActivities(events, dateFrom = "2026-08-01", dateTo = "2026-08-01").size)
        assertTrue(
            "the window the row's own column names must not find it any more",
            readActivities(events, dateFrom = day, dateTo = day).isEmpty(),
        )
    }

    /** A row written before the column existed is filtered exactly like one that has it. */
    @Test
    fun `a row with no column is read by its payload, as the whole journal used to be`() {
        val withColumn = ev(bench(80.0)).copy(opDate = day)
        val withoutColumn = ev(bench(70.0))

        val events = listOf(withColumn, withoutColumn)
        assertEquals(2, readActivities(events, dateFrom = day, dateTo = day).size)
        assertTrue(readActivities(events, dateFrom = "2026-08-07").isEmpty())
        assertTrue(readActivities(events, dateTo = "2026-08-05").isEmpty())
    }
}
