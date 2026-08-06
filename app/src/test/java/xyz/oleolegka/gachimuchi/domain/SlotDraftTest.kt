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
        assertEquals("18:05", parseSlotTime("18:5"))
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
}
