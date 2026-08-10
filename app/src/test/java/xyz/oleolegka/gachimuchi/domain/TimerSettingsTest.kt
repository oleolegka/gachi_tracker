package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing how long the rest between sets should be, out of what the journal already
 * knows, and the formatting the countdown is read in.
 *
 * The interesting cases are all about a number being WRONG in a way nobody would notice:
 * a suggestion computed from a day with one set, or from a session where the phone rang
 * halfway through, reads as authoritative and is not.
 */
class TimerSettingsTest {

    private var nextId = 1L

    private fun ev(form: ActivityForm, ts: String) =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload())

    private val bench = ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH)
    private val squat = ExerciseRef(2, "Squat", ExerciseForm.STRENGTH)
    private val day = "2026-08-06"

    private fun set(exercise: ExerciseRef, opDate: String, ts: String) =
        ev(strengthSetOf(exercise, opDate, reps = 5, weightKg = 60.0), ts)

    // --- what the journal can say -----------------------------------------------------

    @Test
    fun `the offered rest is the gap actually taken between the last sets`() {
        val events = listOf(
            set(bench, day, "2026-08-06T10:00:00"),
            set(bench, day, "2026-08-06T10:02:00"),
            set(bench, day, "2026-08-06T10:04:00"),
        )
        assertEquals(120, lastRestSec(events, bench.id))
    }

    @Test
    fun `one long gap does not drag the offer up, because the median ignores it`() {
        val events = listOf(
            set(bench, day, "2026-08-06T10:00:00"),
            set(bench, day, "2026-08-06T10:02:00"),
            set(bench, day, "2026-08-06T10:04:00"),
            // a phone call: still under the twenty-minute cutoff, so it counts as a rest
            set(bench, day, "2026-08-06T10:22:00"),
            set(bench, day, "2026-08-06T10:24:00"),
        )
        // the mean of 120, 120, 1080, 120 would be 360; the median is 120
        assertEquals(120, lastRestSec(events, bench.id))
    }

    @Test
    fun `a gap longer than a rest is a break in the workout and is not counted`() {
        val events = listOf(
            set(bench, day, "2026-08-06T10:00:00"),
            // forty minutes: over MAX_REST_SEC, so it is dropped rather than offered
            set(bench, day, "2026-08-06T10:40:00"),
            set(bench, day, "2026-08-06T10:43:00"),
        )
        assertEquals(180, lastRestSec(events, bench.id))
    }

    @Test
    fun `a day with a single set contains no pause and is skipped for an earlier one`() {
        val events = listOf(
            set(bench, "2026-08-04", "2026-08-04T10:00:00"),
            set(bench, "2026-08-04", "2026-08-04T10:03:00"),
            // today's session has only started
            set(bench, day, "2026-08-06T10:00:00"),
        )
        assertEquals(180, lastRestSec(events, bench.id))
    }

    @Test
    fun `the offer comes from the most recent day that has a pause in it`() {
        val events = listOf(
            set(bench, "2026-08-01", "2026-08-01T10:00:00"),
            set(bench, "2026-08-01", "2026-08-01T10:05:00"),
            set(bench, "2026-08-06", "2026-08-06T10:00:00"),
            set(bench, "2026-08-06", "2026-08-06T10:01:30"),
        )
        assertEquals(90, lastRestSec(events, bench.id))
    }

    @Test
    fun `another exercise's rests are not borrowed`() {
        val events = listOf(
            set(squat, day, "2026-08-06T10:00:00"),
            set(squat, day, "2026-08-06T10:05:00"),
            set(bench, day, "2026-08-06T11:00:00"),
        )
        assertNull(lastRestSec(events, bench.id))
        assertEquals(300, lastRestSec(events, squat.id))
    }

    @Test
    fun `an explicit rest written on a record is believed over the derived gap`() {
        val withRest = StrengthSet(
            exercise = bench.name, reps = 5, weightKg = 60.0,
            exerciseId = bench.id, opDate = day, restAfterSec = 90.0,
        )
        val events = listOf(
            ev(withRest, "2026-08-06T10:00:00"),
            // the write times are four minutes apart, but the record says the rest was 90 s
            set(bench, day, "2026-08-06T10:04:00"),
        )
        assertEquals(90, lastRestSec(events, bench.id))
    }

    @Test
    fun `an empty journal offers nothing rather than a made up number`() {
        assertNull(lastRestSec(emptyList(), bench.id))
        assertNull(lastRestSec(listOf(set(bench, day, "2026-08-06T10:00:00")), bench.id))
    }

    @Test
    fun `a cancelled set is not a set, so the pause around it is measured without it`() {
        val first = set(bench, day, "2026-08-06T10:00:00")
        val cancelled = set(bench, day, "2026-08-06T10:01:00")
        val third = set(bench, day, "2026-08-06T10:04:00")
        val reversal = JournalEvent(
            99, "2026-08-06T10:01:05", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(cancelled.id)),
        )
        // with the cancelled set gone the only gap is the full four minutes
        assertEquals(240, lastRestSec(listOf(first, cancelled, third, reversal), bench.id))
    }

    @Test
    fun `offers are rounded to a quarter minute so they read as a decision`() {
        val events = listOf(
            set(bench, day, "2026-08-06T10:00:00"),
            set(bench, day, "2026-08-06T10:02:07"),
        )
        assertEquals(120, lastRestSec(events, bench.id))
        assertEquals(15, roundRest(7.0))
        assertEquals(90, roundRest(86.0))
        // never rounds down to nothing
        assertEquals(15, roundRest(1.0))
    }

    // --- turning that into the duration to start ---------------------------------------

    @Test
    fun `the default is used when adapting is off, even though history is available`() {
        val events = listOf(
            set(bench, day, "2026-08-06T10:00:00"),
            set(bench, day, "2026-08-06T10:05:00"),
        )
        val settings = TimerSettings(defaultRestSec = 120, adaptRestToExercise = false)
        assertEquals(120, resolveRestSec(settings, events, bench.id))
        assertEquals("default", restSourceLabel(settings, events, bench.id))
    }

    @Test
    fun `with adapting on the journal wins, and the screen is told where the number came from`() {
        val events = listOf(
            set(bench, day, "2026-08-06T10:00:00"),
            set(bench, day, "2026-08-06T10:05:00"),
        )
        val settings = TimerSettings(defaultRestSec = 120, adaptRestToExercise = true)
        assertEquals(300, resolveRestSec(settings, events, bench.id))
        assertEquals("from last time", restSourceLabel(settings, events, bench.id))
    }

    @Test
    fun `with adapting on but nothing to go on, it falls back to the default and says so`() {
        val settings = TimerSettings(defaultRestSec = 120, adaptRestToExercise = true)
        assertEquals(120, resolveRestSec(settings, emptyList(), bench.id))
        assertEquals("default", restSourceLabel(settings, emptyList(), bench.id))
        // and with no exercise at all
        assertEquals(120, resolveRestSec(settings, emptyList(), null))
    }

    // --- which entries pull up a timer -------------------------------------------------

    @Test
    fun `strength, holds and duration start a rest - a reading, a check-in and cardio do not`() {
        assertTrue(startsRest(strengthSetOf(bench, day, reps = 5, weightKg = 60.0)))
        assertTrue(
            startsRest(
                holdSetOf(
                    ExerciseRef(3, "Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0),
                    day, addedKg = 5.0, reps = 5,
                )
            )
        )
        // §13.9: a held stretch is still "one set, then a pause, then the next"
        assertTrue(
            startsRest(durationOf(ExerciseRef(6, "Plank", ExerciseForm.DURATION), day, durationSec = 60))
        )
        assertFalse(startsRest(bodyweightOf(day, weightKg = 74.0)))
        assertFalse(startsRest(tickOf(ExerciseRef(4, "Stretching", ExerciseForm.TICK), day)))
        assertFalse(startsRest(cardioOf(ExerciseRef(5, "Run", ExerciseForm.CARDIO), day, distanceM = 5000.0)))
    }

    /**
     * The same rule, asked BEFORE any set exists — what decides whether a workout card offers
     * "set a rest" at all. A weigh-in's card used to offer it unconditionally, which is the
     * remaining trace of "brother mistook the scales for an exercise" (§13.9).
     */
    @Test
    fun `the exercise-kind overload agrees with the form-instance one`() {
        assertTrue(startsRest(ExerciseForm.STRENGTH))
        assertTrue(startsRest(ExerciseForm.HOLD))
        assertTrue(startsRest(ExerciseForm.DURATION))
        assertFalse(startsRest(ExerciseForm.BODYWEIGHT))
        assertFalse(startsRest(ExerciseForm.TICK))
        assertFalse(startsRest(ExerciseForm.CARDIO))
    }

    // --- reading the clock -------------------------------------------------------------

    @Test
    fun `the countdown is formatted the way a clock is read`() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:07", formatClock(7))
        assertEquals("2:05", formatClock(125))
        assertEquals("1:00:00", formatClock(3600))
        assertEquals("1:02:03", formatClock(3723))
        // a negative remainder is a bug elsewhere, but it must not print a minus sign
        assertEquals("0:00", formatClock(-5))
    }

    @Test
    fun `remaining milliseconds round up, so the last second is shown until it is gone`() {
        assertEquals(3, ceilSeconds(2_001))
        assertEquals(3, ceilSeconds(3_000))
        assertEquals(1, ceilSeconds(1))
        assertEquals(0, ceilSeconds(0))
    }

    // --- typing a length of time (§13.9) ------------------------------------------------

    @Test
    fun `digits shift in from the right, seconds first - a stopwatch, not a clock`() {
        assertEquals("", formatDurationDigits(""))
        // "30" typed is thirty seconds, the common short rest, with no leading zero needed
        assertEquals("0:30", formatDurationDigits("30"))
        assertEquals("0:03", formatDurationDigits("3"))
        // the third digit pushes the first two into the minutes
        assertEquals("1:30", formatDurationDigits("130"))
        assertEquals("12:34", formatDurationDigits("1234"))
        // a pasted "1:30" reads the same as the digits alone: the colon carries no meaning
        assertEquals("1:30", formatDurationDigits("1:30"))
    }

    @Test
    fun `the typed text reads back as whole seconds, minutes unbounded`() {
        assertNull(parseDurationText(""))
        assertEquals(3, parseDurationText("0:03"))
        assertEquals(30, parseDurationText("0:30"))
        assertEquals(90, parseDurationText("1:30"))
        // a day's worth of rest, MAX_REST_INPUT_SEC's own ceiling — parsing itself refuses
        // nothing this large; a caller decides whether it is IN RANGE
        assertEquals(MAX_REST_INPUT_SEC, parseDurationText("1440:00"))
    }

    /** "The whole minute or nothing" — [parseSlotTime]'s own rule, for the same reason here. */
    @Test
    fun `seconds past 59 are refused rather than carried into the minutes`() {
        assertNull(parseDurationText("0:99"))
        assertNull(parseDurationText("1:60"))
    }

    @Test
    fun `a bump button writes the same shape typing reaches`() {
        assertEquals("0:30", formatDurationSec(30))
        assertEquals("1:30", formatDurationSec(90))
        assertEquals(90, parseDurationText(formatDurationSec(90)))
    }
}
