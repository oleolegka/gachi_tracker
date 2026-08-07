package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Editing the plan, without a database or a screen: what the editor is allowed to save,
 * what a typed time means, and what the words next to the buttons promise (§12-B).
 */
class SlotDraftTest {

    private val monday = LocalDate.of(2026, 8, 3)

    @Test
    fun `a time is read the way it is typed on a phone`() {
        assertEquals("18:00", parseSlotTime("18:00"))
        assertEquals("18:00", parseSlotTime(" 18:00 "))
        assertEquals("07:00", parseSlotTime("7"))
        assertEquals("18:00", parseSlotTime("18"))
        assertEquals("07:30", parseSlotTime("730"))
        assertEquals("07:30", parseSlotTime("0730"))
        assertEquals("07:30", parseSlotTime("7.30"))
        assertEquals("18:30", parseSlotTime("18 30"))
        assertEquals("00:00", parseSlotTime("0"))
    }

    @Test
    fun `a time that is not a time is refused rather than guessed at`() {
        assertNull(parseSlotTime("25:00")) // not rolled over into tomorrow
        assertNull(parseSlotTime("12:75"))
        assertNull(parseSlotTime("evening"))
        assertNull(parseSlotTime("1:2:3"))
        assertNull(parseSlotTime("12345"))
        assertNull(parseSlotTime("18:000"))
        assertNull(parseSlotTime("")) // empty means "no time", which the draft handles
    }

    @Test
    fun `a half-typed minute is not a time`() {
        // the field auto-formats, so "18:5" on screen means three of four digits are in —
        // reading it as 18:05 would store a time nobody typed whenever 18:50 was meant
        assertNull(parseSlotTime("18:5"))
        assertNull(parseSlotTime("17:0"))
        assertNull(parseSlotTime("9:3"))
        // the whole minute is fine either way round
        assertEquals("09:30", parseSlotTime("9:30"))
        assertEquals("17:00", parseSlotTime("17:00"))
    }

    @Test
    fun `the colon appears while the digits are typed`() {
        assertEquals("", formatTimeDigits(""))
        assertEquals("1", formatTimeDigits("1"))
        assertEquals("17", formatTimeDigits("17"))
        assertEquals("17:0", formatTimeDigits("170"))
        assertEquals("17:00", formatTimeDigits("1700"))
    }

    @Test
    fun `a leading digit that cannot start an hour makes the hour one digit`() {
        assertEquals("9", formatTimeDigits("9"))
        assertEquals("9:3", formatTimeDigits("93"))
        assertEquals("9:30", formatTimeDigits("930"))
        // 25 is not an hour, so it is read as 2 o'clock and something
        assertEquals("2:5", formatTimeDigits("25"))
        assertEquals("2:50", formatTimeDigits("250"))
    }

    @Test
    fun `anything that is not a digit is dropped, so a pasted time works too`() {
        assertEquals("17:00", formatTimeDigits("17:00"))
        assertEquals("17:00", formatTimeDigits("17 00"))
        assertEquals("17:00", formatTimeDigits("1700999"))
    }

    @Test
    fun `what the field shows is what the parser reads back`() {
        // every state the field can be in: complete ones parse, half-typed ones do not
        assertEquals("07:00", parseSlotTime(formatTimeDigits("7")))
        assertEquals("18:00", parseSlotTime(formatTimeDigits("18")))
        assertEquals("09:30", parseSlotTime(formatTimeDigits("930")))
        assertEquals("17:00", parseSlotTime(formatTimeDigits("1700")))
        assertNull(parseSlotTime(formatTimeDigits("170")))
        assertNull(parseSlotTime(formatTimeDigits("93")))
        assertNull(parseSlotTime(formatTimeDigits("")))
    }

    @Test
    fun `a time read back and formatted again is the same time`() {
        assertEquals(18 * 60 + 5, parseMinuteOfDay("18:05"))
        assertEquals("18:05", formatTime(18, 5))
        assertNull(parseMinuteOfDay(null))
        assertNull(parseMinuteOfDay("18"))
        assertNull(parseMinuteOfDay("18:60"))
    }

    @Test
    fun `a draft needs a name and nothing else`() {
        val empty = newSlotDraft(monday)
        assertEquals(SlotProblem.NAME_EMPTY, empty.problem())
        assertNull(empty.toSlot())

        val named = empty.copy(name = "Gym")
        assertNull(named.problem())
        val slot = named.toSlot()!!
        assertEquals("Gym", slot.name)
        assertNull("no time is a normal plan, not a missing field", slot.atTime)
        assertEquals(REPEAT_NONE, slot.repeatRule)
        assertEquals(monday.toString(), slot.anchorDate)
    }

    @Test
    fun `a draft is refused for an unreadable time, an unknown rule and a broken date`() {
        val base = SlotDraft(name = "Gym", anchorDate = monday.toString())
        assertEquals(SlotProblem.TIME_UNREADABLE, base.copy(timeText = "half past six").problem())
        assertEquals(SlotProblem.RULE_UNKNOWN, base.copy(repeatRule = "monthly").problem())
        assertEquals(SlotProblem.DATE_UNREADABLE, base.copy(anchorDate = "someday").problem())
        assertNull(base.copy(timeText = "99:99").toSlot())
        // every reason has a sentence to show under the fields
        SlotProblem.entries.forEach { assertTrue(problemText(it).isNotBlank()) }
    }

    @Test
    fun `saving normalizes the name and the time`() {
        val draft = SlotDraft(
            name = "  Fingerboard  ", timeText = "1830",
            repeatRule = REPEAT_WEEKLY, anchorDate = monday.toString(),
        )
        val slot = draft.toSlot(id = 7)!!
        assertEquals(7L, slot.id)
        assertEquals("Fingerboard", slot.name)
        assertEquals("18:30", slot.atTime)
    }

    @Test
    fun `a stored slot opens in the editor exactly as it was stored`() {
        val slot = Slot(3, "Gym", "18:00", REPEAT_WEEKLY, monday.toString())
        val draft = slot.toDraft()
        assertEquals(SlotDraft("Gym", "18:00", REPEAT_WEEKLY, monday.toString()), draft)
        assertEquals(slot, draft.toSlot(id = 3))
    }

    @Test
    fun `the repeat reads as a badge in a list and as a sentence in the editor`() {
        assertNull(repeatBadge(REPEAT_NONE))
        assertEquals("every day", repeatBadge(REPEAT_DAILY))
        assertEquals("every week", repeatBadge(REPEAT_WEEKLY))

        // the weekday of a weekly slot comes from the DATE field, which is the surprising part
        assertEquals("Repeats every Monday", repeatLabel(REPEAT_WEEKLY, monday.toString()))
        assertEquals("Repeats every Thursday", repeatLabel(REPEAT_WEEKLY, monday.plusDays(3).toString()))
        assertEquals("Repeats every day", repeatLabel(REPEAT_DAILY, monday.toString()))
        assertEquals("Happens once", repeatLabel(REPEAT_NONE, monday.toString()))
    }

    @Test
    fun `the next occurrence is the first day the rule lands on from a given day`() {
        val weekly = Slot(1, "Gym", "18:00", REPEAT_WEEKLY, monday.toString())
        assertEquals(monday, nextOccurrence(weekly, monday))
        assertEquals(monday.plusDays(7), nextOccurrence(weekly, monday.plusDays(1)))
        assertEquals(monday.plusDays(7), nextOccurrence(weekly, monday.plusDays(7)))
        // never before the anchor: the plan starts when it was set up
        assertEquals(monday, nextOccurrence(weekly, monday.minusDays(30)))

        val daily = Slot(2, "Stretching", null, REPEAT_DAILY, monday.toString())
        assertEquals(monday.plusDays(2), nextOccurrence(daily, monday.plusDays(2)))

        val once = Slot(3, "Running", null, REPEAT_NONE, monday.toString())
        assertEquals(monday, nextOccurrence(once, monday))
        assertNull("a one-off that has passed has nothing coming", nextOccurrence(once, monday.plusDays(1)))
    }

    @Test
    fun `the delete warning says the past goes too and the journal does not`() {
        val weekly = Slot(1, "Gym", "18:00", REPEAT_WEEKLY, monday.toString())
        val warning = deletionWarning(weekly)
        assertTrue(warning.contains("Gym"))
        assertTrue("it names the weekday", warning.contains("every Monday"))
        assertTrue("it admits past occurrences go too", warning.contains("already gone"))
        assertTrue("it admits single dates cannot be skipped", warning.contains("whole series"))
        assertTrue("it promises the facts survive", warning.contains("logged are not touched"))

        val daily = deletionWarning(weekly.copy(repeatRule = REPEAT_DAILY))
        assertTrue(daily.contains("every day"))
        assertTrue(daily.contains("already gone"))

        // a one-off has no series, so it does not get the series sentence
        val once = deletionWarning(weekly.copy(repeatRule = REPEAT_NONE))
        assertTrue(once.contains("planned once"))
        assertTrue(once.contains("logged are not touched"))
    }

    // --- what the session is made of -------------------------------------------------------

    @Test
    fun `a plan with no exercises in it is a complete plan`() {
        val draft = newSlotDraft(monday).copy(name = "Gym")
        assertTrue(draft.exercises.isEmpty())
        // the assertion the whole feature hangs on: nothing about the composition can ever
        // stand between the user and saving a slot
        assertNull(draft.problem())
        assertEquals(emptyList<PlannedExercise>(), draft.toSlot()!!.exercises)
    }

    @Test
    fun `a slot with a composition opens in the editor and saves back unchanged`() {
        val slot = Slot(
            3, "Gym", "18:00", REPEAT_WEEKLY, monday.toString(),
            exercises = listOf(PlannedExercise(11, restSec = 180), PlannedExercise(12)),
        )
        val draft = slot.toDraft()
        assertEquals(slot.exercises, draft.exercises)
        assertEquals(slot, draft.toSlot(id = 3))
    }

    @Test
    fun `exercises are added in the order they are picked, duplicates included`() {
        val draft = newSlotDraft(monday).copy(name = "Gym")
            .withExerciseAdded(11)
            .withExerciseAdded(12, restSec = 150)
            .withExerciseAdded(11)

        assertEquals(listOf(11L, 12L, 11L), draft.exercises.map { it.exerciseId })
        assertEquals(listOf(null, 150, null), draft.exercises.map { it.restSec })
    }

    @Test
    fun `moving an exercise reorders the plan, and moving off either end does nothing`() {
        val draft = newSlotDraft(monday).copy(name = "Gym")
            .withExerciseAdded(11).withExerciseAdded(12).withExerciseAdded(13)

        assertEquals(listOf(12L, 11L, 13L), draft.withExerciseMoved(0, 1).exercises.map { it.exerciseId })
        assertEquals(listOf(11L, 13L, 12L), draft.withExerciseMoved(2, -1).exercises.map { it.exerciseId })

        // off the ends, and against an index that is no longer there: the list comes back as
        // it was rather than clamping onto the wrong row
        assertEquals(draft.exercises, draft.withExerciseMoved(0, -1).exercises)
        assertEquals(draft.exercises, draft.withExerciseMoved(2, 1).exercises)
        assertEquals(draft.exercises, draft.withExerciseMoved(7, -1).exercises)
        assertEquals(draft.exercises, draft.withExerciseMoved(1, 0).exercises)
    }

    @Test
    fun `removing takes out the row that was tapped and nothing else`() {
        val draft = newSlotDraft(monday).copy(name = "Gym")
            .withExerciseAdded(11).withExerciseAdded(12).withExerciseAdded(13)

        assertEquals(listOf(11L, 13L), draft.withExerciseRemoved(1).exercises.map { it.exerciseId })
        // a stale index is a no-op: the tap arrived against a list that has since changed
        assertEquals(draft.exercises, draft.withExerciseRemoved(3).exercises)
        assertEquals(draft.exercises, draft.withExerciseRemoved(-1).exercises)
    }

    @Test
    fun `a rest is set per exercise, and zero means no answer rather than no rest`() {
        val draft = newSlotDraft(monday).copy(name = "Gym")
            .withExerciseAdded(11).withExerciseAdded(12)

        assertEquals(listOf(180, null), draft.withExerciseRest(0, 180).exercises.map { it.restSec })
        // back to "whatever this exercise usually gets"
        assertEquals(
            listOf(null, null),
            draft.withExerciseRest(0, 180).withExerciseRest(0, null).exercises.map { it.restSec },
        )
        // zero and a negative are not rests of no seconds, they are the absence of an answer
        assertNull(draft.withExerciseRest(1, 0).exercises[1].restSec)
        assertNull(draft.withExerciseRest(1, -30).exercises[1].restSec)
        assertNull(draft.withExerciseAdded(13, restSec = 0).exercises.last().restSec)

        assertEquals(draft.exercises, draft.withExerciseRest(9, 120).exercises)
    }

    @Test
    fun `what is planned in a slot is readable from the plan by id`() {
        val gym = Slot(
            1, "Gym", "18:00", REPEAT_WEEKLY, monday.toString(),
            exercises = listOf(PlannedExercise(11, 180), PlannedExercise(12)),
        )
        val stretching = Slot(2, "Stretching", null, REPEAT_DAILY, monday.toString())
        val plan = listOf(gym, stretching)

        assertEquals(gym.exercises, plannedExercises(plan, 1))
        // a slot with nothing in it and a slot that is not there answer the same way, because
        // starting a workout must not depend on either
        assertTrue(plannedExercises(plan, 2).isEmpty())
        assertTrue(plannedExercises(plan, 999).isEmpty())
        assertTrue(plannedExercises(plan, null).isEmpty())
    }
}
