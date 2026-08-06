package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Journal reducer tests: read filters, reversals, groupings, aggregation across aliases. */
class JournalTest {

    private var nextId = 1L

    private fun ev(form: ActivityForm, ts: String = "2026-08-06T10:00:00") =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload())

    private fun strength(day: String, name: String, weight: Double, reps: Int, id: Long?) =
        ev(StrengthSet(exercise = name, reps = reps, weightKg = weight, exerciseId = id, opDate = day))

    @Test
    fun `reading filters by type, date range and key`() {
        val events = listOf(
            strength("2026-08-01", "Bench press", 60.0, 5, 1),
            strength("2026-08-05", "Squat", 80.0, 5, 2),
            ev(Tick(activity = "Stretching", opDate = "2026-08-05")),
            ev(Bodyweight(weightKg = 74.2, opDate = "2026-08-05")),
        )
        assertEquals(4, readActivities(events).size)
        assertEquals(2, readActivities(events, listOf(TYPE_STRENGTH_SET)).size)
        assertEquals(3, readActivities(events, dateFrom = "2026-08-05").size)
        assertEquals(1, readActivities(events, key = "Bench press").size)
        // body weight has no key, so it drops out whenever a key filter is applied
        assertTrue(readActivities(events, key = "stretching").all { it.type == TYPE_TICK })
    }

    @Test
    fun `a reversal removes a set from the reducers but not from the journal`() {
        val first = strength("2026-08-06", "Bench press", 60.0, 5, 1)
        val cancel = JournalEvent(
            99, "2026-08-06T11:00:00", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(first.id)),
        )
        val events = listOf(first, cancel)
        assertEquals(setOf(first.id), cancelledEventIds(events))
        assertEquals(0, readActivities(events).size)
        assertEquals(1, readActivities(events, includeCancelled = true).size)
        assertEquals(2, events.size) // the journal itself is untouched
    }

    @Test
    fun `strength sets are grouped by exercise and day`() {
        val events = listOf(
            strength("2026-08-01", "Bench press", 60.0, 5, 1),
            strength("2026-08-01", "Bench press", 62.5, 5, 1),
            strength("2026-08-01", "Squat", 80.0, 5, 2),
            strength("2026-08-02", "Bench press", 65.0, 5, 1),
        )
        val groups = strengthSetsByExerciseDay(events)
        assertEquals(3, groups.size)
        assertEquals("2026-08-01", groups[0].opDate)
        assertEquals(2, groups.first { it.opDate == "2026-08-01" && it.exerciseKey == "bench press" }.sets.size)
    }

    @Test
    fun `aggregation by exercise_id merges aliases and skips records without an id`() {
        val events = listOf(
            strength("2026-08-01", "bench", 60.0, 5, 1),
            strength("2026-08-02", "bench press", 62.5, 5, 1),  // different word, same id
            strength("2026-08-03", "bench press", 65.0, 5, null), // pre-catalog — no id
        )
        val sets = strengthSetsByExerciseId(events, 1)
        assertEquals(2, sets.size)
        assertEquals(62.5, sets.last().weightKg!!, 1e-9)
    }

    @Test
    fun `last hold set and last cardio entry`() {
        val events = listOf(
            ev(HoldSet(activity = "Hangs", addedKg = 6.0, exerciseId = 5, opDate = "2026-08-01")),
            ev(HoldSet(activity = "Hangs", addedKg = 8.0, exerciseId = 5, opDate = "2026-08-03")),
            ev(Cardio(activity = "Running", distanceM = 5000.0, exerciseId = 6, opDate = "2026-08-02")),
        )
        assertEquals(8.0, lastHoldSet(events, 5)!!.addedKg!!, 1e-9)
        assertEquals(5000.0, lastCardio(events, 6)!!.distanceM!!, 1e-9)
        assertNull(lastHoldSet(events, 999))
    }

    @Test
    fun `active days do not count a weigh-in as training`() {
        val events = listOf(
            ev(Bodyweight(weightKg = 74.0, opDate = "2026-08-01")),
            ev(Tick(activity = "Stretching", opDate = "2026-08-02")),
        )
        val days = activeDays(events, "2026-08-01", "2026-08-03")
        assertEquals(setOf("2026-08-02"), days)
    }
}
