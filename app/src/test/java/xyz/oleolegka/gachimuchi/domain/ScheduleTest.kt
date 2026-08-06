package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Planning calendar tests: rule expansion and the ternary day status (§12-B). */
class ScheduleTest {

    private val monday = LocalDate.of(2026, 8, 3)

    private fun slot(
        id: Long, name: String, rule: String, anchor: LocalDate, at: String? = null,
    ) = Slot(id, name, at, rule, anchor.toString())

    @Test
    fun `a one-off slot lands exactly on its anchor`() {
        val slots = listOf(slot(1, "Running", REPEAT_NONE, monday))
        val occ = slotsForRange(slots, monday.minusDays(3), monday.plusDays(10))
        assertEquals(1, occ.size)
        assertEquals(monday.toString(), occ.single().day)
    }

    @Test
    fun `a weekly slot repeats every seven days and never before its anchor`() {
        val slots = listOf(slot(1, "Gym", REPEAT_WEEKLY, monday, "18:00"))
        val occ = slotsForRange(slots, monday.minusDays(14), monday.plusDays(21))
        assertEquals(listOf("2026-08-03", "2026-08-10", "2026-08-17", "2026-08-24"), occ.map { it.day })
    }

    @Test
    fun `a daily slot covers every day from its anchor on`() {
        val slots = listOf(slot(1, "Stretching", REPEAT_DAILY, monday))
        assertEquals(5, slotsForRange(slots, monday.minusDays(2), monday.plusDays(4)).size)
    }

    @Test
    fun `slots without a time come after timed slots on the same day`() {
        val slots = listOf(
            slot(1, "No time", REPEAT_NONE, monday),
            slot(2, "Evening", REPEAT_NONE, monday, "18:00"),
            slot(3, "Morning", REPEAT_NONE, monday, "08:00"),
        )
        val occ = slotsForRange(slots, monday, monday)
        assertEquals(listOf("Morning", "Evening", "No time"), occ.map { it.name })
    }

    @Test
    fun `day states are done, missed, planned, unplanned and empty`() {
        val slots = listOf(slot(1, "Gym", REPEAT_WEEKLY, monday, "18:00"))
        val today = monday.plusDays(14) // two weeks later
        val active = setOf(monday.toString(), monday.plusDays(3).toString())
        val days = planVsFact(slots, active, monday, today, today).associateBy { it.day }

        assertEquals(DayState.DONE, days.getValue(monday.toString()).state)
        assertEquals(DayState.EXTRA, days.getValue(monday.plusDays(3).toString()).state)
        assertEquals(DayState.MISS, days.getValue(monday.plusDays(7).toString()).state)
        assertEquals(DayState.PLAN, days.getValue(today.toString()).state)
        assertEquals(DayState.EMPTY, days.getValue(monday.plusDays(1).toString()).state)
    }

    @Test
    fun `today with a slot is not missed yet`() {
        val slots = listOf(slot(1, "Gym", REPEAT_NONE, monday, "18:00"))
        val days = planVsFact(slots, emptySet(), monday, monday, monday)
        assertEquals(DayState.PLAN, days.single().state)
    }

    @Test
    fun `the grid returns a row for every day of the range, empty ones included`() {
        val days = planVsFact(emptyList(), emptySet(), monday, monday.plusDays(6), monday)
        assertEquals(7, days.size)
        assertTrue(days.all { it.state == DayState.EMPTY })
    }
}
