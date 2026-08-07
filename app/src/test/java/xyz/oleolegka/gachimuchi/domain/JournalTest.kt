package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Journal reducer tests: read filters, reversals, groupings, aggregation by exercise_id. */
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
        assertEquals(setOf(first.uid), cancelledEventUids(events))
        assertEquals(0, readActivities(events).size)
        assertEquals(1, readActivities(events, includeCancelled = true).size)
        assertEquals(2, events.size) // the journal itself is untouched
    }

    // --- the reversal link said in uids -----------------------------------------------

    /** The same reversal, said with a number, with a uid, or with both. */
    private fun reversalOf(
        target: JournalEvent,
        withNumber: Boolean,
        withUid: Boolean,
    ) = JournalEvent(
        99, "2026-08-06T11:00:00", 1, 1, TYPE_SET_CANCEL,
        payloadJson.encodeToString(
            SetCancel(
                cancels = if (withNumber) target.id else null,
                cancelsUid = if (withUid) target.uid else null,
            )
        ),
    )

    @Test
    fun `a reversal folds the same whether it names a number, a uid, or both`() {
        val set = strength("2026-08-06", "Bench press", 60.0, 5, 1)

        for (shape in listOf(true to true, false to true, true to false)) {
            val (withNumber, withUid) = shape
            val events = listOf(set, reversalOf(set, withNumber, withUid))
            assertEquals(
                "reversal written with number=$withNumber uid=$withUid",
                setOf(set.uid),
                cancelledEventUids(events),
            )
            assertEquals(0, readActivities(events).size)
        }
    }

    /**
     * THE FAILURE THE UID EXISTS TO PREVENT. Two journals merged by union both hold a row
     * numbered 1, and a reversal that came from one of them would otherwise cancel the other's
     * set — somebody's training quietly disappearing with nothing on screen to show for it.
     */
    @Test
    fun `a reversal carrying a uid never falls back to its stale number`() {
        val mine = strength("2026-08-06", "Bench press", 60.0, 5, 1)
        val theirs = strength("2026-08-06", "Squat", 90.0, 5, 2)
        val confused = JournalEvent(
            99, "2026-08-06T11:00:00", 1, 1, TYPE_SET_CANCEL,
            // the number says "mine", the identity says "theirs"
            payloadJson.encodeToString(SetCancel(cancels = mine.id, cancelsUid = theirs.uid)),
        )

        assertEquals(setOf(theirs.uid), cancelledEventUids(listOf(mine, theirs, confused)))
    }

    @Test
    fun `a reversal that names nothing at all cancels nothing`() {
        val set = strength("2026-08-06", "Bench press", 60.0, 5, 1)
        val empty = JournalEvent(
            99, "2026-08-06T11:00:00", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel()),
        )
        // and a number that names no row here resolves to nothing rather than to whatever
        // happens to hold that number
        val stray = JournalEvent(
            100, "2026-08-06T11:01:00", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(cancels = 4242)),
        )

        assertEquals(emptySet<String>(), cancelledEventUids(listOf(set, empty, stray)))
        assertEquals(1, readActivities(listOf(set, empty, stray)).size)
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
    fun `aggregation by exercise_id merges spellings and skips records without an id`() {
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

    // --- one bad row must not cost the whole journal -----------------------------------------
    //
    // Every screen in the app is a fold over this list, so anything that throws in here throws
    // on four screens at once, on the device holding the only copy of the history. The journal
    // is validated on the way in and is about to stop being the only writer: entries are meant
    // to be exchanged with the bot, and a file can arrive truncated, hand-edited or written by
    // a schema this build has never seen.

    @Test
    fun `a row whose payload will not parse is skipped, not thrown on`() {
        val good = strength("2026-08-01", "Bench press", 60.0, 5, 1)
        val truncated = JournalEvent(50, "2026-08-02T10:00:00", 1, 1, TYPE_STRENGTH_SET, """{"exercise":"Squ""")
        val nonsense = JournalEvent(51, "2026-08-02T11:00:00", 1, 1, TYPE_HOLD_SET, """{"activity":"Hangs","reps":-4,"op_date":"2026-08-02"}""")
        val alien = JournalEvent(52, "2026-08-02T12:00:00", 1, 1, "sleep_log", """{"hours":8}""")
        val later = strength("2026-08-03", "Bench press", 62.5, 5, 1)

        val read = readActivities(listOf(good, truncated, nonsense, alien, later))

        // the two readable sets come through, including the one AFTER the damage - a throw
        // here would have hidden every entry in the journal, not just the broken one
        assertEquals(listOf("2026-08-01", "2026-08-03"), read.map { it.opDate })
        assertEquals(2, strengthSetsByExerciseId(listOf(good, truncated, later), 1).size)
    }

    @Test
    fun `an unreadable reversal does not take the reducers down with it`() {
        val first = strength("2026-08-06", "Bench press", 60.0, 5, 1)
        val broken = JournalEvent(98, "2026-08-06T11:00:00", 1, 1, TYPE_SET_CANCEL, "{}")
        val real = JournalEvent(
            99, "2026-08-06T11:30:00", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(first.id)),
        )

        // the readable reversal still counts; the unreadable one is simply not evidence
        assertEquals(setOf(first.uid), cancelledEventUids(listOf(first, broken, real)))
        assertEquals(0, readActivities(listOf(first, broken, real)).size)
    }
}
