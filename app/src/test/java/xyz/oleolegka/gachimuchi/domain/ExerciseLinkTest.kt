package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "The same exercise" — the one comparison the whole catalog (§11) exists to make, now said
 * in one place instead of in nine reducers.
 *
 * An entry names its exercise twice: by identity (`exercise_uid`, schema version 10) and by
 * the local row number that came before it. These check that the fold does not care which of
 * the two a journal happens to carry, and that an identity is never quietly overruled by a
 * number — which is the failure the identity was introduced to prevent.
 */
class ExerciseLinkTest {

    private var nextId = 1L

    private fun row(form: ActivityForm, ts: String) =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload())

    private val benchUid = "01930000-0000-7000-8000-00000000be01"
    private val squatUid = "01930000-0000-7000-8000-00000000c0a7"

    /** The catalog row as a screen holds it, with or without the identity filled in. */
    private fun bench(withUid: Boolean) =
        ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH, uid = if (withUid) benchUid else null)

    private fun squat(withUid: Boolean) =
        ExerciseRef(2, "Squat", ExerciseForm.STRENGTH, uid = if (withUid) squatUid else null)

    /**
     * Three days of two exercises, written by a build that could say identities or by one that
     * could not. Same training either way — the only difference is how each entry names what
     * it is about.
     */
    private fun journal(withUid: Boolean): List<JournalEvent> {
        nextId = 1L
        val events = ArrayList<JournalEvent>()
        for ((index, day) in listOf("2026-08-01", "2026-08-03", "2026-08-05").withIndex()) {
            events += row(
                strengthSetOf(bench(withUid), day, reps = 5, weightKg = 60.0 + index * 5),
                "${day}T10:00:00",
            )
            events += row(
                strengthSetOf(bench(withUid), day, reps = 3, weightKg = 70.0 + index * 5),
                "${day}T10:05:00",
            )
            events += row(
                strengthSetOf(squat(withUid), day, reps = 8, weightKg = 90.0),
                "${day}T10:20:00",
            )
        }
        return events
    }

    // --- the comparison itself ---------------------------------------------------------

    @Test
    fun `two identities decide between themselves and ignore the numbers`() {
        val a = ExerciseLink(benchUid, 1)
        // the same exercise arriving from another device, where it happens to be row 7
        val sameFromElsewhere = ExerciseLink(benchUid, 7)
        assertTrue(a.matches(sameFromElsewhere))

        // and a different exercise that happens to be row 1 over there is NOT this one,
        // which is the whole reason the identity exists
        assertFalse(a.matches(ExerciseLink(squatUid, 1)))
    }

    @Test
    fun `a number is consulted only when one of the two sides cannot speak identities`() {
        val withUid = ExerciseLink(benchUid, 1)
        val numberOnly = ExerciseLink(null, 1)

        assertTrue(withUid.matches(numberOnly))
        assertTrue(numberOnly.matches(withUid))
        assertFalse(withUid.matches(ExerciseLink(null, 2)))
    }

    @Test
    fun `an entry naming no exercise has no link at all`() {
        assertNull(bodyweightOf("2026-08-01", 74.5).exerciseLink())
        assertEquals(ExerciseLink(benchUid, 1), strengthSetOf(bench(true), "2026-08-01", 5, 60.0).exerciseLink())
    }

    // --- the fold does not care which link a journal carries ----------------------------

    /** Everything a reader can see about a day's session, flattened for comparison. */
    private fun Session.shape() = groups.map { group ->
        listOf(group.name, group.sets.size.toString(), group.sets.map { it.form.activityName() }.toString())
    }

    @Test
    fun `a session folds the same whether entries carry both links or only the identity`() {
        val both = journal(withUid = true)
        val numbersOnly = journal(withUid = false)

        assertEquals(
            buildSession(numbersOnly, "2026-08-03").shape(),
            buildSession(both, "2026-08-03").shape(),
        )
        // two exercises, two blocks, and no exercise split across two of them
        assertEquals(2, buildSession(both, "2026-08-03").groups.size)
        assertEquals(3, buildSession(both, "2026-08-03").setCount)
    }

    @Test
    fun `records and series come out the same on a journal that only speaks identities`() {
        val both = readActivities(journal(withUid = true))
        val numbersOnly = readActivities(journal(withUid = false))

        val byUid = ExerciseLink(benchUid, null)
        val byNumber = ExerciseLink.ofId(1)

        // asked by identity of a journal that has identities, and by number of one that has
        // only numbers: the same six sets, the same record, the same line
        assertEquals(
            trendSeries(numbersOnly, byNumber, ExerciseForm.STRENGTH)!!.points,
            trendSeries(both, byUid, ExerciseForm.STRENGTH)!!.points,
        )
        assertEquals(
            strengthRecord(numbersOnly, byNumber)!!.value,
            strengthRecord(both, byUid)!!.value,
            1e-9,
        )
        assertEquals(
            heaviestSet(numbersOnly, byNumber)!!.value,
            heaviestSet(both, byUid)!!.value,
            1e-9,
        )
    }

    @Test
    fun `a journal that only speaks identities still answers a screen holding a number`() {
        // the screen navigates by row number and the entries carry both links, so the number
        // side of each entry is what answers — this is the everyday case after the upgrade
        val both = readActivities(journal(withUid = true))
        assertEquals(6, formsOfExercise<StrengthSet>(journal(true), ExerciseLink.ofId(1), TYPE_STRENGTH_SET).size)
        assertEquals(3, volumeSeries(both, ExerciseLink.ofId(2), ExerciseForm.STRENGTH)!!.points.size)
    }

    /**
     * THE FAILURE THAT WOULD NOT LOOK LIKE ONE. Two devices number their exercises
     * independently, so "row 1" is a bench press here and a hangboard over there. If a number
     * could overrule an identity, a merged journal would show one exercise holding both
     * histories, with a record computed across the two.
     */
    @Test
    fun `two exercises that share a number keep separate histories when they have identities`() {
        nextId = 1L
        val mine = ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH, uid = benchUid)
        val theirs = ExerciseRef(1, "Squat", ExerciseForm.STRENGTH, uid = squatUid)
        val events = listOf(
            row(strengthSetOf(mine, "2026-08-01", reps = 5, weightKg = 60.0), "2026-08-01T10:00:00"),
            row(strengthSetOf(theirs, "2026-08-01", reps = 5, weightKg = 150.0), "2026-08-01T10:10:00"),
        )
        val activities = readActivities(events)

        assertEquals(60.0, heaviestSet(activities, ExerciseLink(benchUid, 1))!!.value, 1e-9)
        assertEquals(150.0, heaviestSet(activities, ExerciseLink(squatUid, 1))!!.value, 1e-9)
        // and the day shows two blocks, not one block of two
        assertEquals(2, buildSession(events, "2026-08-01").groups.size)
    }

    @Test
    fun `the heatmap counts an exercise once whichever link its entries carry`() {
        for (withUid in listOf(true, false)) {
            val perDay = activitiesByDay(journal(withUid), "2026-08-01", "2026-08-05")
            assertEquals("entries written with uid=$withUid", 2, perDay.getValue("2026-08-03").size)
        }
    }
}
