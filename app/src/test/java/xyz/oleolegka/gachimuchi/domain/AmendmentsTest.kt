package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fold in domain/Amendments.kt: which events are still there, and what they say now.
 *
 * Everything here is the RULE rather than a reader — the readers get their own file. Each test
 * is one clause of the rule written out in the header of the file it tests, in the same order,
 * so that a clause with no test below is visible by being missing.
 */
class AmendmentsTest {

    private var nextId = 1L

    private fun ev(form: ActivityForm, ts: String = "2026-08-06T10:00:00") =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload())

    private fun bench(day: String = "2026-08-06", weight: Double = 60.0, reps: Int = 5) =
        StrengthSet(exercise = "Bench press", reps = reps, weightKg = weight, exerciseId = 1, opDate = day)

    private fun amend(
        targetUid: String,
        ts: String = "2026-08-06T11:00:00",
        vararg fields: Pair<String, JsonElement>,
    ) = JournalEvent(
        nextId++, ts, 1, 1, TYPE_ENTRY_AMENDED,
        payloadJson.encodeToString(EntryAmended(targetUid, JsonObject(fields.toMap()))),
    )

    private fun delete(targetUid: String, ts: String = "2026-08-06T12:00:00") = JournalEvent(
        nextId++, ts, 1, 1, TYPE_ENTRY_DELETED,
        payloadJson.encodeToString(EntryDeleted(targetUid)),
    )

    /** The one strength set of the journal, as the readers see it. */
    private fun theSet(events: List<JournalEvent>): StrengthSet? =
        readActivities(events).map { it.form }.filterIsInstance<StrengthSet>().firstOrNull()

    // --- an amendment corrects values ---

    @Test
    fun `an amendment changes the value a reader sees, and nothing else about the row`() {
        val set = ev(bench(weight = 60.0, reps = 5))
        val events = listOf(set, amend(set.uid, fields = arrayOf("weight_kg" to JsonPrimitive(62.5))))

        val read = readActivities(events).single()
        assertEquals(62.5, (read.form as StrengthSet).weightKg!!, 1e-9)
        // untouched fields keep their values: an amendment is a patch, not a new payload
        assertEquals(5, (read.form as StrengthSet).reps)
        // and the row itself is the row: same id, same identity, same honest write time
        assertEquals(set.id, read.id)
        assertEquals(set.uid, read.uid)
        assertEquals(set.ts, read.ts)
    }

    @Test
    fun `an amendment can move an entry to the day it was actually done on`() {
        val set = ev(bench(day = "2026-08-06"))
        val events = listOf(set, amend(set.uid, fields = arrayOf("op_date" to JsonPrimitive("2026-08-05"))))

        assertEquals("2026-08-05", readActivities(events).single().opDate)
        assertTrue(readActivities(events, dateFrom = "2026-08-06").isEmpty())
    }

    @Test
    fun `an entry nobody touched is reported as unamended, an amended one carries when`() {
        val untouched = ev(bench())
        assertNull(readActivities(listOf(untouched)).single().amendedAt)

        val set = ev(bench())
        val correction = amend(set.uid, ts = "2026-08-06T18:00:00", fields = arrayOf("reps" to JsonPrimitive(8)))
        assertEquals("2026-08-06T18:00:00", readActivities(listOf(set, correction)).single().amendedAt)
    }

    // --- several amendments on one entry ---

    @Test
    fun `two amendments of different fields both survive`() {
        val set = ev(bench(weight = 60.0, reps = 5))
        val events = listOf(
            set,
            amend(set.uid, ts = "2026-08-06T11:00:00", fields = arrayOf("reps" to JsonPrimitive(8))),
            amend(set.uid, ts = "2026-08-06T12:00:00", fields = arrayOf("weight_kg" to JsonPrimitive(65.0))),
        )

        val form = theSet(events)!!
        // the rule is last-wins PER FIELD: the earlier correction of the reps is not undone by
        // a later correction that never mentioned them
        assertEquals(8, form.reps)
        assertEquals(65.0, form.weightKg!!, 1e-9)
    }

    @Test
    fun `two amendments of one field are settled by the later one`() {
        val set = ev(bench(reps = 5))
        val events = listOf(
            set,
            amend(set.uid, ts = "2026-08-06T12:00:00", fields = arrayOf("reps" to JsonPrimitive(9))),
            amend(set.uid, ts = "2026-08-06T11:00:00", fields = arrayOf("reps" to JsonPrimitive(8))),
        )
        // written in the journal out of order on purpose: the clock decides, not the position
        assertEquals(9, theSet(events)!!.reps)
    }

    @Test
    fun `two amendments written in the same second are settled by journal order`() {
        val set = ev(bench(reps = 5))
        val events = listOf(
            set,
            amend(set.uid, ts = "2026-08-06T11:00:00", fields = arrayOf("reps" to JsonPrimitive(8))),
            amend(set.uid, ts = "2026-08-06T11:00:00", fields = arrayOf("reps" to JsonPrimitive(9))),
        )
        assertEquals(9, theSet(events)!!.reps)
    }

    // --- deletion ---

    @Test
    fun `a deletion hides the entry from the readers and leaves it in the journal`() {
        val set = ev(bench())
        val events = listOf(set, delete(set.uid))

        assertTrue(readActivities(events).isEmpty())
        assertEquals(1, readActivities(events, includeDeleted = true).size)
        assertEquals(setOf(set.uid), deletedEventUids(events))
        assertEquals(2, events.size)
    }

    @Test
    fun `a deletion beats an amendment written after it`() {
        val set = ev(bench(reps = 5))
        val events = listOf(
            set,
            delete(set.uid, ts = "2026-08-06T11:00:00"),
            amend(set.uid, ts = "2026-08-06T12:00:00", fields = arrayOf("reps" to JsonPrimitive(8))),
        )
        // correcting something that should not exist is not a reason to keep it
        assertTrue(readActivities(events).isEmpty())
    }

    @Test
    fun `the history keeps the corrections of a deleted entry rather than unwinding them`() {
        val set = ev(bench(reps = 5))
        val events = listOf(
            set,
            amend(set.uid, ts = "2026-08-06T11:00:00", fields = arrayOf("reps" to JsonPrimitive(8))),
            delete(set.uid, ts = "2026-08-06T12:00:00"),
        )
        // includeDeleted looks past whether the entry is there, never past what it says
        val form = readActivities(events, includeDeleted = true).single().form as StrengthSet
        assertEquals(8, form.reps)
    }

    // --- the older spelling still means what it always meant ---

    @Test
    fun `a set_cancel by row number still hides the set it names`() {
        val set = ev(bench())
        val cancel = JournalEvent(
            99, "2026-08-06T11:00:00", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(cancels = set.id)),
        )
        assertTrue(readActivities(listOf(set, cancel)).isEmpty())
    }

    @Test
    fun `a set_cancel by identity still hides the set it names`() {
        val set = ev(bench())
        val cancel = JournalEvent(
            99, "2026-08-06T11:00:00", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(cancelsUid = set.uid)),
        )
        assertTrue(readActivities(listOf(set, cancel)).isEmpty())
    }

    @Test
    fun `an entry_deleted hides a set the same way set_cancel does`() {
        val set = ev(bench())
        assertTrue(readActivities(listOf(set, delete(set.uid))).isEmpty())
    }

    // --- undoing an undo, which used to do nothing at all ---

    @Test
    fun `deleting a deletion brings the entry back`() {
        val set = ev(bench())
        val gone = delete(set.uid, ts = "2026-08-06T11:00:00")
        val undo = delete(gone.uid, ts = "2026-08-06T12:00:00")

        assertEquals(1, readActivities(listOf(set, gone, undo)).size)
        assertEquals(emptySet<String>(), deletedEventUids(listOf(set, gone, undo)) - gone.uid)
    }

    @Test
    fun `the chain is followed to its end, not one step deep`() {
        val set = ev(bench())
        val gone = delete(set.uid, ts = "2026-08-06T11:00:00")
        val undo = delete(gone.uid, ts = "2026-08-06T12:00:00")
        val redo = delete(undo.uid, ts = "2026-08-06T13:00:00")

        // redo kills undo, so gone is live again, so the set is hidden again
        assertTrue(readActivities(listOf(set, gone, undo, redo)).isEmpty())
    }

    @Test
    fun `an amendment that has itself been deleted stops applying`() {
        val set = ev(bench(reps = 5))
        val correction = amend(set.uid, ts = "2026-08-06T11:00:00", fields = arrayOf("reps" to JsonPrimitive(8)))
        val undo = delete(correction.uid, ts = "2026-08-06T12:00:00")

        assertEquals(8, theSet(listOf(set, correction))!!.reps)
        assertEquals(5, theSet(listOf(set, correction, undo))!!.reps)
    }

    @Test
    fun `deleting one of two amendments leaves the other applied`() {
        val set = ev(bench(weight = 60.0, reps = 5))
        val onReps = amend(set.uid, ts = "2026-08-06T11:00:00", fields = arrayOf("reps" to JsonPrimitive(8)))
        val onWeight = amend(set.uid, ts = "2026-08-06T12:00:00", fields = arrayOf("weight_kg" to JsonPrimitive(65.0)))
        val events = listOf(set, onReps, onWeight, delete(onWeight.uid, ts = "2026-08-06T13:00:00"))

        val form = theSet(events)!!
        assertEquals(8, form.reps)
        assertEquals(60.0, form.weightKg!!, 1e-9)
    }

    // --- what an amendment is not allowed to do ---

    @Test
    fun `an amendment cannot move a set to another exercise, even arriving from elsewhere`() {
        // the repository refuses to write this; a journal merged in from another phone can
        // still contain it, and the reader has to ignore it rather than trust it
        val set = ev(bench(weight = 60.0))
        val events = listOf(
            set,
            amend(
                set.uid,
                fields = arrayOf(
                    "exercise_id" to JsonPrimitive(77),
                    "exercise" to JsonPrimitive("Squat"),
                    "weight_kg" to JsonPrimitive(65.0),
                ),
            ),
        )

        val form = theSet(events)!!
        assertEquals(1L, form.exerciseId)
        assertEquals("Bench press", form.exercise)
        // the value in the same patch is applied: the protected keys are dropped one by one,
        // not the whole amendment with them
        assertEquals(65.0, form.weightKg!!, 1e-9)
    }

    // --- rows that name nothing, and rows that cannot be read ---

    @Test
    fun `a correction naming an event this journal does not hold is inert`() {
        val set = ev(bench(reps = 5))
        val events = listOf(
            set,
            amend("no-such-event", fields = arrayOf("reps" to JsonPrimitive(8))),
            delete("no-such-event-either"),
        )
        assertEquals(5, theSet(events)!!.reps)
        assertEquals(1, readActivities(events).size)
    }

    @Test
    fun `a correction whose own payload will not read is skipped, not thrown on`() {
        val set = ev(bench(reps = 5))
        val broken = JournalEvent(98, "2026-08-06T11:00:00", 1, 1, TYPE_ENTRY_AMENDED, "{ nonsense")
        val brokenDelete = JournalEvent(97, "2026-08-06T11:00:00", 1, 1, TYPE_ENTRY_DELETED, "{}")

        assertEquals(5, theSet(listOf(set, broken, brokenDelete))!!.reps)
    }

    @Test
    fun `two deletions naming each other hide nothing`() {
        // impossible to write with this app and possible to merge in; the cycle is broken in
        // favour of the answer that loses no training
        val set = ev(bench())
        val a = JournalEvent(50, "2026-08-06T11:00:00", 1, 1, TYPE_ENTRY_DELETED, "")
        val b = JournalEvent(51, "2026-08-06T11:00:01", 1, 1, TYPE_ENTRY_DELETED, "")
        val first = a.copy(payload = payloadJson.encodeToString(EntryDeleted(b.uid)))
        val second = b.copy(payload = payloadJson.encodeToString(EntryDeleted(a.uid)))

        assertEquals(1, readActivities(listOf(set, first, second)).size)
    }

    // --- the shape of the funnel itself ---

    @Test
    fun `liveEvents drops the deleted, keeps the corrections, and is idempotent`() {
        val set = ev(bench(reps = 5))
        val other = ev(bench(reps = 3))
        val events = listOf(
            set,
            other,
            amend(set.uid, fields = arrayOf("reps" to JsonPrimitive(8))),
            delete(other.uid),
        )

        val once = liveEvents(events)
        // the deleted row is gone, the two control rows stay: they are the record of the edit
        assertEquals(3, once.size)
        assertFalse(once.any { it.uid == other.uid })
        assertEquals(liveEvents(once), once)
        assertEquals(8, theSet(once)!!.reps)
    }

    @Test
    fun `a control event is never mistaken for training`() {
        val set = ev(bench())
        val events = listOf(set, amend(set.uid, fields = arrayOf("reps" to JsonPrimitive(8))), delete("nobody"))

        assertTrue(events.drop(1).all { it.isControlEvent() })
        assertFalse(set.isControlEvent())
        // FACT_TYPES and ACTIVITY_TYPES never contained them, and the fold does not add them
        assertEquals(1, readActivities(events).size)
        assertEquals(setOf("2026-08-06"), activeDays(events, "2026-08-01", "2026-08-31"))
    }
}
