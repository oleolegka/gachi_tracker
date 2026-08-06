package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logging session: grouping the feed, prefilling the entry card, catching a record,
 * deriving the rest between sets and ordering the exercise picker.
 *
 * All of it is reducers over a list of events, so the whole logging screen is verified
 * here without Compose and without a device.
 */
class SessionTest {

    private var nextId = 1L

    private fun ev(form: ActivityForm, ts: String = "2026-08-06T10:00:00") =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload())

    private val bench = ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH)
    private val hangs = ExerciseRef(2, "Hangs 20 mm", ExerciseForm.HOLD, edgeMm = 20.0, workSec = 7.0, restSec = 3.0)
    private val run = ExerciseRef(3, "Running", ExerciseForm.CARDIO)
    private val emil = ExerciseRef(4, "Emil hangs", ExerciseForm.DURATION)
    private val stretch = ExerciseRef(5, "Stretching", ExerciseForm.TICK)
    private val scales = ExerciseRef(6, "Body weight", ExerciseForm.BODYWEIGHT)

    private val day = "2026-08-06"

    // --- building forms out of the entry card ----------------------------------------

    @Test
    fun `every form can be recorded and read back through the journal`() {
        val forms = listOf(
            strengthSetOf(bench, day, reps = 5, weightKg = 60.0),
            holdSetOf(hangs, day, addedKg = 8.0, reps = 5),
            cardioOf(run, day, distanceM = 5000.0, durationSec = 1500),
            durationOf(emil, day, durationSec = 600),
            tickOf(stretch, day),
            bodyweightOf(day, weightKg = 74.2),
        )
        val events = forms.map { ev(it) }
        val read = readActivities(events)
        assertEquals(6, read.size)
        // the payload of every form round-trips: what was written is what comes back
        assertEquals(forms.map { it.type }, read.map { it.type })
        assertEquals(60.0, (read[0].form as StrengthSet).weightKg!!, 1e-9)
        assertEquals(74.2, (read[5].form as Bodyweight).weightKg, 1e-9)
    }

    @Test
    fun `a hold set inherits edge and protocol from the exercise and never asks for them`() {
        val set = holdSetOf(hangs, day, addedKg = 8.0, reps = 5)
        assertEquals(20.0, set.edgeMm!!, 1e-9)
        assertEquals(7.0, set.workSec!!, 1e-9)
        assertEquals(3.0, set.restSec!!, 1e-9)
        assertEquals(hangs.id, set.exerciseId)

        // an exercise with no protocol writes neither half of it — the pair is all or nothing
        val plank = ExerciseRef(9, "Plank", ExerciseForm.HOLD)
        val bare = holdSetOf(plank, day, holdSec = 60.0)
        assertNull(bare.workSec)
        assertNull(bare.restSec)
    }

    @Test
    fun `a body weight strength set stores added weight and leaves the absolute weight empty`() {
        val set = strengthSetOf(bench, day, reps = 8, ownWeight = true, addedKg = 10.0)
        assertNull(set.weightKg)
        assertEquals(10.0, set.addedKg!!, 1e-9)
        assertTrue(set.ownWeight)

        // a clean body weight set: zero added weight is no weight at all, not a zero record
        val clean = strengthSetOf(bench, day, reps = 12, ownWeight = true, addedKg = 0.0)
        assertNull(clean.addedKg)
    }

    @Test
    fun `a zero in a field means "not filled in" and never reaches the payload`() {
        // the validators reject zero reps, and a crash mid-workout is the worst possible
        // failure mode for this screen
        val set = holdSetOf(hangs, day, addedKg = 0.0, reps = 0)
        assertNull(set.addedKg)
        assertNull(set.reps)
        assertNull(strengthSetOf(bench, day, reps = 5, weightKg = 0.0).weightKg)
    }

    // --- prefilling ------------------------------------------------------------------

    @Test
    fun `the entry card prefills from the last set of that exercise, across aliases`() {
        val events = listOf(
            ev(StrengthSet(exercise = "bench", reps = 8, weightKg = 55.0, exerciseId = 1, opDate = "2026-08-01")),
            ev(StrengthSet(exercise = "Bench press", reps = 5, weightKg = 62.5, exerciseId = 1, opDate = "2026-08-04")),
            ev(StrengthSet(exercise = "Squat", reps = 5, weightKg = 90.0, exerciseId = 7, opDate = "2026-08-05")),
        )
        val last = lastStrengthSet(events, 1)!!
        assertEquals(62.5, last.weightKg!!, 1e-9) // the newest one, under either word
        assertEquals(5, last.reps)
        assertNull(lastStrengthSet(events, 999))
    }

    @Test
    fun `a cancelled set never comes back as the prefill`() {
        val good = ev(StrengthSet(exercise = "Bench press", reps = 5, weightKg = 60.0, exerciseId = 1, opDate = day))
        val typo = ev(StrengthSet(exercise = "Bench press", reps = 5, weightKg = 600.0, exerciseId = 1, opDate = day))
        val cancel = JournalEvent(
            500, "2026-08-06T10:05:00", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(typo.id)),
        )
        val events = listOf(good, typo, cancel)
        assertEquals(60.0, lastStrengthSet(events, 1)!!.weightKg!!, 1e-9)
    }

    @Test
    fun `duration and body weight prefill from their own last entry`() {
        val events = listOf(
            ev(Duration(activity = "Emil hangs", durationSec = 480, exerciseId = 4, opDate = "2026-08-01")),
            ev(Duration(activity = "Emil hangs", durationSec = 600, exerciseId = 4, opDate = "2026-08-05")),
            ev(Bodyweight(weightKg = 75.0, opDate = "2026-08-01")),
            ev(Bodyweight(weightKg = 74.2, opDate = "2026-08-05")),
        )
        assertEquals(600, lastDuration(events, 4)!!.durationSec)
        assertEquals(74.2, lastBodyweight(events)!!.weightKg, 1e-9)
    }

    // --- the session feed ------------------------------------------------------------

    @Test
    fun `the feed groups by exercise in order of first appearance and keeps sets in order`() {
        val events = listOf(
            ev(strengthSetOf(bench, day, reps = 8, weightKg = 50.0)),
            ev(holdSetOf(hangs, day, addedKg = 6.0, reps = 5)),
            ev(strengthSetOf(bench, day, reps = 5, weightKg = 60.0)),
            ev(strengthSetOf(bench, day, reps = 5, weightKg = 62.5)),
            // yesterday must not leak into today's session
            ev(strengthSetOf(bench, "2026-08-05", reps = 5, weightKg = 57.5)),
        )
        val session = buildSession(events, day)
        assertEquals(2, session.groups.size)
        assertEquals("Bench press", session.groups[0].name)  // it appeared first
        assertEquals("Hangs 20 mm", session.groups[1].name)
        assertEquals(3, session.groups[0].sets.size)
        assertEquals(4, session.setCount)
        assertEquals(
            listOf(50.0, 60.0, 62.5),
            session.groups[0].sets.map { (it.form as StrengthSet).weightKg },
        )
    }

    @Test
    fun `repeating a set adds another identical one and undo targets the newest`() {
        val first = ev(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))
        // "repeat" is the same prefilled values written again — one more event, nothing updated
        val repeat = ev(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))
        val session = buildSession(listOf(first, repeat), day)
        assertEquals(2, session.setCount)
        assertEquals(repeat.id, session.lastEventId)

        val cancel = JournalEvent(
            600, "2026-08-06T10:10:00", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(repeat.id)),
        )
        val after = buildSession(listOf(first, repeat, cancel), day)
        assertEquals(1, after.setCount)
        assertEquals(first.id, after.lastEventId) // undo now points at the one before
    }

    @Test
    fun `the feed marks the set that broke a record and stays quiet about the rest`() {
        val events = listOf(
            ev(strengthSetOf(bench, "2026-08-01", reps = 5, weightKg = 60.0)), // baseline, no note
            ev(strengthSetOf(bench, day, reps = 5, weightKg = 57.5)),          // weaker, no note
            ev(strengthSetOf(bench, day, reps = 5, weightKg = 65.0)),          // a record
        )
        val sets = buildSession(events, day).groups.single().sets
        assertNull(sets[0].record)
        assertNotNull(sets[1].record)
        assertEquals(RecordHit.Axis.EST_1RM, sets[1].record!!.axis)
        assertTrue(sets[1].record!!.text.contains("1RM"))
    }

    @Test
    fun `a hold record is the added weight, per exercise identity`() {
        val events = listOf(
            ev(holdSetOf(hangs, "2026-08-01", addedKg = 6.0, reps = 5)),
            ev(holdSetOf(hangs, day, addedKg = 8.0, reps = 5)),
        )
        val hit = buildSession(events, day).groups.single().sets.single().record
        assertEquals(RecordHit.Axis.HOLD_WEIGHT, hit!!.axis)
        assertEquals(8.0, hit.value, 1e-9)
    }

    @Test
    fun `rest between sets is derived from the write times and implausible gaps are dropped`() {
        val events = listOf(
            ev(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), ts = "2026-08-06T10:00:00"),
            ev(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), ts = "2026-08-06T10:02:30"),
            // a 45 minute gap is a break in the workout, not a rest between sets
            ev(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), ts = "2026-08-06T10:47:30"),
        )
        val sets = buildSession(events, day).groups.single().sets
        assertNull(sets[0].restBeforeSec)          // nothing to measure from
        assertEquals(150.0, sets[1].restBeforeSec!!, 1e-9)
        assertNull(sets[2].restBeforeSec)
    }

    @Test
    fun `an explicit rest in the payload wins over the derived gap`() {
        // the bot and the demo seed do write rest_after_sec; the app derives it instead
        val events = listOf(
            ev(
                StrengthSet(
                    exercise = "Bench press", reps = 5, weightKg = 60.0, exerciseId = 1,
                    restAfterSec = 180.0, opDate = day,
                ),
                ts = "2026-08-06T10:00:00",
            ),
            ev(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), ts = "2026-08-06T10:02:30"),
        )
        val sets = buildSession(events, day).groups.single().sets
        assertEquals(180.0, sets[1].restBeforeSec!!, 1e-9)
    }

    @Test
    fun `entries with no exercise_id still show up in the feed, grouped by name`() {
        val events = listOf(
            ev(StrengthSet(exercise = "Bench press", reps = 5, weightKg = 60.0, opDate = day)),
            ev(Bodyweight(weightKg = 74.2, opDate = day)),
        )
        val session = buildSession(events, day)
        assertEquals(2, session.groups.size)
        assertTrue(session.groups.all { it.exerciseId == null })
        assertEquals("Body weight", session.groups[1].name)
        assertNull(session.groups[0].sets.single().record) // nothing to compare against
    }

    @Test
    fun `an empty day gives an empty session with nothing to undo`() {
        val session = buildSession(emptyList(), day)
        assertTrue(session.isEmpty)
        assertEquals(0, session.setCount)
        assertNull(session.lastEventId)
    }

    // --- the picker ------------------------------------------------------------------

    @Test
    fun `the picker puts the most recently used exercise first, then the most used`() {
        val events = listOf(
            ev(strengthSetOf(bench, "2026-08-01", reps = 5, weightKg = 60.0)),
            ev(strengthSetOf(bench, "2026-08-01", reps = 5, weightKg = 60.0)),
            ev(strengthSetOf(bench, "2026-08-01", reps = 5, weightKg = 60.0)),
            ev(holdSetOf(hangs, "2026-08-05", addedKg = 6.0, reps = 5)),
        )
        val usage = exerciseUsage(events)
        assertEquals(3, usage[bench.id]!!.count)
        assertEquals("2026-08-05", usage[hangs.id]!!.lastDate)

        val order = pickerOrder(usage)
        // recency beats frequency: the hangs were used later, the bench press more often
        assertEquals(listOf(2L, 1L, 42L), listOf(1L, 2L, 42L).sortedWith(order))
    }

    @Test
    fun `search matches names and learned aliases, an empty query matches everything`() {
        assertTrue(matchesExerciseQuery("Bench press", emptyList(), "bench"))
        assertTrue(matchesExerciseQuery("Bench press", emptyList(), ""))
        assertTrue(matchesExerciseQuery("Hangs 20 mm", listOf("hang20", "fingers"), "finger"))
        assertTrue(!matchesExerciseQuery("Bench press", listOf("bench"), "squat"))
    }

    @Test
    fun `a word that matches nothing still offers the catalog, which is where it is taught`() {
        // the whole alias mechanism hangs on this: a word becomes an alias by being in the
        // search box when an exercise is tapped, so a list filtered down to nothing leaves
        // no exercise to teach it to and a duplicate as the only way forward
        assertTrue(offersWholeCatalog(query = "jim", matchCount = 0, catalogSize = 4))
    }

    @Test
    fun `a word that matches something narrows the list as usual`() {
        assertTrue(!offersWholeCatalog(query = "bench", matchCount = 1, catalogSize = 4))
    }

    @Test
    fun `an empty query and an empty catalog have nothing to fall back to`() {
        // an empty query already lists everything, and an empty catalog offers creation
        assertTrue(!offersWholeCatalog(query = "", matchCount = 0, catalogSize = 4))
        assertTrue(!offersWholeCatalog(query = "   ", matchCount = 0, catalogSize = 4))
        assertTrue(!offersWholeCatalog(query = "jim", matchCount = 0, catalogSize = 0))
    }

    // --- a catalog row that carries a zero ---------------------------------------------

    @Test
    fun `a hold exercise with a zero edge and protocol can still be logged`() {
        /*
         * The regression this pins: the create form used to store whatever `parseNumber`
         * returned, so "0" in the edge or protocol field became a 0.0 on the catalog row.
         * `holdSetOf` then handed that straight to the HoldSet validator, which rejects a
         * non-positive edge by throwing — inside the Add button's click handler, i.e. as a
         * crash of the app on its primary action rather than as a message.
         */
        val broken = ExerciseRef(9, "Hangs", ExerciseForm.HOLD, edgeMm = 0.0, workSec = 0.0, restSec = 0.0)
        assertNull(broken.edge)
        assertNull(broken.protocol)

        val set = holdSetOf(broken, day, addedKg = 5.0, reps = 4)
        assertNull(set.edgeMm)
        assertNull(set.workSec)
        assertNull(set.restSec)
        assertEquals(5.0, set.addedKg!!, 0.0)
    }

    @Test
    fun `half a protocol is no protocol, whichever half is missing or zero`() {
        // the validator insists on a pair, so a half-filled one must not reach it
        assertNull(ExerciseRef(9, "Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 0.0).protocol)
        assertNull(ExerciseRef(9, "Hangs", ExerciseForm.HOLD, workSec = 0.0, restSec = 3.0).protocol)
        assertNull(ExerciseRef(9, "Hangs", ExerciseForm.HOLD, workSec = 7.0).protocol)
        assertNull(ExerciseRef(9, "Hangs", ExerciseForm.HOLD, restSec = 3.0).protocol)
        assertNotNull(ExerciseRef(9, "Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0).protocol)
    }

    @Test
    fun `a negative edge is treated as never filled in rather than written`() {
        val negative = ExerciseRef(9, "Hangs", ExerciseForm.HOLD, edgeMm = -5.0, workSec = 7.0, restSec = 3.0)
        assertNull(negative.edge)
        // the protocol beside it is untouched: only the broken value is dropped
        assertNotNull(negative.protocol)
        assertNull(holdSetOf(negative, day, reps = 3).edgeMm)
    }
}
