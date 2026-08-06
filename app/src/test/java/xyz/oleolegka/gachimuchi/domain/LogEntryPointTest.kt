package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The way into logging: which exercise the entry card opens on.
 *
 * This is the step that used to end in "No exercise chosen" — the state a first run landed
 * in, and the one that turns "write down the set I just did" into a search. It is a pure
 * reducer over the journal plus the catalog, so it is verified here rather than on a
 * device.
 */
class LogEntryPointTest {

    private var nextId = 1L

    private fun ev(form: ActivityForm, ts: String) =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload())

    private val bench = ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH)
    private val squat = ExerciseRef(2, "Squat", ExerciseForm.STRENGTH)
    private val boulder = ExerciseRef(3, "Boulder gym", ExerciseForm.TICK)

    private val today = "2026-08-06"
    private val yesterday = "2026-08-05"

    private val wholeCatalog = listOf(bench.id, squat.id, boulder.id)

    @Test
    fun `an empty catalog offers nothing`() {
        assertNull(exerciseToLogNext(emptyList(), today, emptyList()))
        // and a journal is no help either when there is nothing left to log against
        val events = listOf(ev(strengthSetOf(bench, today, reps = 5, weightKg = 60.0), "${today}T10:00:00"))
        assertNull(exerciseToLogNext(events, today, emptyList()))
    }

    @Test
    fun `a lone exercise is offered even though it has never been used`() {
        assertEquals(boulder.id, exerciseToLogNext(emptyList(), today, listOf(boulder.id)))
    }

    @Test
    fun `several never-used exercises are not guessed between`() {
        // a wrong guess would silently prefill the card from the wrong history; asking is
        // the honest answer here
        assertNull(exerciseToLogNext(emptyList(), today, wholeCatalog))
    }

    @Test
    fun `the workout is continued on the exercise it left off on`() {
        val events = listOf(
            ev(strengthSetOf(bench, today, reps = 5, weightKg = 60.0), "${today}T10:00:00"),
            ev(strengthSetOf(squat, today, reps = 5, weightKg = 80.0), "${today}T10:20:00"),
        )
        assertEquals(squat.id, exerciseToLogNext(events, today, wholeCatalog))
    }

    @Test
    fun `with nothing logged today the most recent exercise of the journal is offered`() {
        val events = listOf(
            ev(strengthSetOf(bench, "2026-08-01", reps = 5, weightKg = 60.0), "2026-08-01T10:00:00"),
            ev(strengthSetOf(squat, yesterday, reps = 5, weightKg = 80.0), "${yesterday}T10:00:00"),
        )
        assertEquals(squat.id, exerciseToLogNext(events, today, wholeCatalog))
    }

    @Test
    fun `recency beats frequency, the same way the picker orders itself`() {
        val events = buildList {
            repeat(10) { add(ev(strengthSetOf(bench, "2026-07-01", reps = 5, weightKg = 60.0), "2026-07-01T10:00:00")) }
            add(ev(tickOf(boulder, yesterday), "${yesterday}T19:00:00"))
        }
        assertEquals(boulder.id, exerciseToLogNext(events, today, wholeCatalog))
    }

    @Test
    fun `an exercise gone from the catalog is never offered, however recent`() {
        val events = listOf(
            ev(strengthSetOf(bench, yesterday, reps = 5, weightKg = 60.0), "${yesterday}T10:00:00"),
            ev(strengthSetOf(squat, today, reps = 5, weightKg = 80.0), "${today}T10:00:00"),
        )
        // squat is the newest on both counts but no longer exists: the card could not look
        // up a form for it
        assertEquals(bench.id, exerciseToLogNext(events, today, listOf(bench.id)))
    }

    @Test
    fun `entries with no exercise behind them are skipped`() {
        // body weight carries no exercise_id at all (see LogScreen), so it can never be the
        // thing the card opens on
        val events = listOf(
            ev(strengthSetOf(bench, today, reps = 5, weightKg = 60.0), "${today}T10:00:00"),
            ev(bodyweightOf(today, weightKg = 74.2), "${today}T10:30:00"),
        )
        assertEquals(bench.id, exerciseToLogNext(events, today, wholeCatalog))
    }
}
