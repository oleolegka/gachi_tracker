package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Planning calendar tests: rule expansion and the per-slot plan/fact status (§12-B). */
class ScheduleTest {

    private val monday = LocalDate.of(2026, 8, 3)

    private fun slot(
        id: Long, name: String, rule: String, anchor: LocalDate, at: String? = null,
    ) = Slot(id, name, at, rule, anchor.toString())

    /** An entry recorded on the day it belongs to, at a known clock time. */
    private fun act(id: Long, day: LocalDate, at: String) =
        ActivityStamp(id, day.toString(), parseMinuteOfDay(at))

    /** An entry backfilled later: it counts for its day, but its clock time says nothing. */
    private fun backfilled(id: Long, day: LocalDate) = ActivityStamp(id, day.toString(), null)

    private fun at(day: LocalDate, time: String): LocalDateTime =
        day.atStartOfDay().plusMinutes(parseMinuteOfDay(time)!!.toLong())

    private fun statesOf(
        slots: List<Slot>,
        acts: List<ActivityStamp>,
        day: LocalDate,
        now: LocalDateTime,
    ): List<SlotState> = planVsFact(slots, acts, day, day, now).single().slots.map { it.state }

    // --- rule expansion ---

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

    // --- the day grid ---

    @Test
    fun `day states are done, missed, planned, unplanned and empty`() {
        val slots = listOf(slot(1, "Gym", REPEAT_WEEKLY, monday, "18:00"))
        val today = monday.plusDays(14) // two weeks later
        val acts = listOf(act(1, monday, "18:10"), act(2, monday.plusDays(3), "12:00"))
        val days = planVsFact(slots, acts, monday, today, at(today, "12:00")).associateBy { it.day }

        assertEquals(DayState.DONE, days.getValue(monday.toString()).state)
        assertEquals(DayState.EXTRA, days.getValue(monday.plusDays(3).toString()).state)
        assertEquals(DayState.MISS, days.getValue(monday.plusDays(7).toString()).state)
        assertEquals(DayState.PLAN, days.getValue(today.toString()).state)
        assertEquals(DayState.EMPTY, days.getValue(monday.plusDays(1).toString()).state)
    }

    @Test
    fun `the grid returns a row for every day of the range, empty ones included`() {
        val days = planVsFact(emptyList(), emptyList(), monday, monday.plusDays(6), monday.atStartOfDay())
        assertEquals(7, days.size)
        assertTrue(days.all { it.state == DayState.EMPTY })
    }

    // --- per-slot status: the reported bug ---

    @Test
    fun `a session later today is planned even though the day already has an entry`() {
        // the bug from the device: "bouldering gym" added for 20:00 showed up as done at once
        val slots = listOf(slot(1, "Bouldering gym", REPEAT_NONE, monday, "20:00"))
        val acts = listOf(act(1, monday, "09:30")) // the morning session, unrelated to the plan
        val day = planVsFact(slots, acts, monday, monday, at(monday, "10:00")).single()

        assertEquals(listOf(SlotState.PLAN), day.slots.map { it.state })
        assertEquals(DayState.PLAN, day.state)
        assertEquals(1, day.unmatchedActivities) // the morning entry is unplanned training
    }

    @Test
    fun `a slot is done once an entry lands in its window`() {
        val slots = listOf(slot(1, "Bouldering gym", REPEAT_NONE, monday, "20:00"))
        val acts = listOf(act(1, monday, "20:40"))
        val day = planVsFact(slots, acts, monday, monday, at(monday, "21:00")).single()

        assertEquals(listOf(SlotState.DONE), day.slots.map { it.state })
        assertEquals(1L, day.slots.single().closedByActivityId)
        assertEquals(setOf(1L), day.closedByActivityIds)
        assertEquals(0, day.unmatchedActivities)
    }

    @Test
    fun `a slot is not missed while its window is still open`() {
        val slots = listOf(slot(1, "Gym", REPEAT_NONE, monday, "18:00"))
        // 19:00 is past the slot but inside the three hours it can still be closed in
        assertEquals(listOf(SlotState.PLAN), statesOf(slots, emptyList(), monday, at(monday, "19:00")))
        // 21:30 is past the window: nothing can close it any more
        assertEquals(listOf(SlotState.MISS), statesOf(slots, emptyList(), monday, at(monday, "21:30")))
    }

    @Test
    fun `a past day with a slot and no entry is missed`() {
        val slots = listOf(slot(1, "Gym", REPEAT_NONE, monday, "18:00"))
        val states = statesOf(slots, emptyList(), monday, at(monday.plusDays(1), "09:00"))
        assertEquals(listOf(SlotState.MISS), states)
    }

    // --- several slots in one day ---

    @Test
    fun `two slots and one morning entry close the morning one`() {
        val slots = listOf(
            slot(1, "Gym", REPEAT_NONE, monday, "08:00"),
            slot(2, "Hangboard", REPEAT_NONE, monday, "20:00"),
        )
        val day = planVsFact(slots, listOf(act(7, monday, "08:20")), monday, monday, at(monday, "12:00")).single()

        assertEquals(listOf(SlotState.DONE, SlotState.PLAN), day.slots.map { it.state })
        assertEquals(7L, day.slots[0].closedByActivityId)
        assertNull(day.slots[1].closedByActivityId)
        assertEquals(DayState.PLAN, day.state) // the evening session is still outstanding
    }

    @Test
    fun `two slots and one evening entry close the evening one, the morning is missed`() {
        val slots = listOf(
            slot(1, "Gym", REPEAT_NONE, monday, "08:00"),
            slot(2, "Hangboard", REPEAT_NONE, monday, "20:00"),
        )
        val day = planVsFact(slots, listOf(act(7, monday, "20:05")), monday, monday, at(monday, "21:00")).single()

        assertEquals(listOf(SlotState.MISS, SlotState.DONE), day.slots.map { it.state })
        assertEquals(7L, day.slots[1].closedByActivityId)
        assertEquals(DayState.MISS, day.state) // a hole in the day dominates the colour
    }

    @Test
    fun `two entries close two slots, each the one it is nearest to`() {
        val slots = listOf(
            slot(1, "Gym", REPEAT_NONE, monday, "08:00"),
            slot(2, "Hangboard", REPEAT_NONE, monday, "20:00"),
        )
        val acts = listOf(act(1, monday, "20:30"), act(2, monday, "08:05"))
        val day = planVsFact(slots, acts, monday, monday, at(monday, "22:00")).single()

        assertEquals(listOf(SlotState.DONE, SlotState.DONE), day.slots.map { it.state })
        assertEquals(2L, day.slots[0].closedByActivityId)
        assertEquals(1L, day.slots[1].closedByActivityId)
        assertEquals(DayState.DONE, day.state)
    }

    @Test
    fun `one entry never closes two slots`() {
        val slots = listOf(
            slot(1, "Gym", REPEAT_NONE, monday, "18:00"),
            slot(2, "Stretching", REPEAT_NONE, monday, "18:30"),
        )
        val day = planVsFact(slots, listOf(act(1, monday, "18:10")), monday, monday, at(monday, "23:00")).single()

        // nearest first: 18:00 is ten minutes away, 18:30 is twenty
        assertEquals(listOf(SlotState.DONE, SlotState.MISS), day.slots.map { it.state })
    }

    @Test
    fun `an entry far from every slot closes nothing and stays unplanned`() {
        val slots = listOf(slot(1, "Gym", REPEAT_NONE, monday, "08:00"))
        val day = planVsFact(slots, listOf(act(1, monday, "14:00")), monday, monday, at(monday, "23:00")).single()

        assertEquals(listOf(SlotState.MISS), day.slots.map { it.state })
        assertEquals(1, day.unmatchedActivities)
        assertEquals(DayState.MISS, day.state)
    }

    // --- slots without a time ---

    @Test
    fun `a slot without a time is closed by any entry of the day`() {
        val slots = listOf(slot(1, "Walk", REPEAT_NONE, monday))
        val day = planVsFact(slots, listOf(act(1, monday, "14:00")), monday, monday, at(monday, "15:00")).single()
        assertEquals(listOf(SlotState.DONE), day.slots.map { it.state })
    }

    @Test
    fun `a slot without a time is planned all day and missed only afterwards`() {
        val slots = listOf(slot(1, "Walk", REPEAT_NONE, monday))
        assertEquals(listOf(SlotState.PLAN), statesOf(slots, emptyList(), monday, at(monday, "23:59")))
        val next = planVsFact(slots, emptyList(), monday, monday, monday.plusDays(1).atStartOfDay()).single()
        assertEquals(listOf(SlotState.MISS), next.slots.map { it.state })
    }

    @Test
    fun `a timed slot takes the entry it matches, the untimed one takes what is left`() {
        val slots = listOf(
            slot(1, "Gym", REPEAT_NONE, monday, "08:00"),
            slot(2, "Walk", REPEAT_NONE, monday), // no time: sorted after the timed one
        )
        val acts = listOf(act(1, monday, "08:10"), act(2, monday, "15:00"))
        val day = planVsFact(slots, acts, monday, monday, at(monday, "20:00")).single()

        assertEquals(listOf(SlotState.DONE, SlotState.DONE), day.slots.map { it.state })
        assertEquals(1L, day.slots[0].closedByActivityId) // matched by time
        assertEquals(2L, day.slots[1].closedByActivityId) // the leftover
    }

    @Test
    fun `an untimed slot is not closed by tomorrow's entry`() {
        val slots = listOf(slot(1, "Walk", REPEAT_DAILY, monday))
        val acts = listOf(act(1, monday.plusDays(1), "10:00"))
        val states = statesOf(slots, acts, monday, at(monday.plusDays(1), "11:00"))
        assertEquals(listOf(SlotState.MISS), states)
    }

    @Test
    fun `an untimed slot of a future day is planned, entries or not`() {
        val slots = listOf(slot(1, "Walk", REPEAT_NONE, monday.plusDays(1)))
        val day = planVsFact(
            slots, emptyList(), monday.plusDays(1), monday.plusDays(1), at(monday, "10:00"),
        ).single()
        assertEquals(listOf(SlotState.PLAN), day.slots.map { it.state })
    }

    // --- entries backfilled on another day ---

    @Test
    fun `a backfilled entry closes a slot of its day even without a clock time`() {
        val slots = listOf(slot(1, "Gym", REPEAT_NONE, monday, "18:00"))
        val states = statesOf(slots, listOf(backfilled(1, monday)), monday, at(monday.plusDays(1), "09:00"))
        assertEquals(listOf(SlotState.DONE), states)
    }

    @Test
    fun `a backfilled entry closes the earliest slot and only one of them`() {
        val slots = listOf(
            slot(1, "Gym", REPEAT_NONE, monday, "08:00"),
            slot(2, "Hangboard", REPEAT_NONE, monday, "20:00"),
        )
        val states = statesOf(slots, listOf(backfilled(1, monday)), monday, at(monday.plusDays(1), "09:00"))
        assertEquals(listOf(SlotState.DONE, SlotState.MISS), states)
    }

    @Test
    fun `matching by time wins over the fallback`() {
        val slots = listOf(
            slot(1, "Gym", REPEAT_NONE, monday, "08:00"),
            slot(2, "Hangboard", REPEAT_NONE, monday, "20:00"),
        )
        // the backfilled entry could go anywhere; the timed one belongs to the evening slot
        val acts = listOf(act(1, monday, "20:15"), backfilled(2, monday))
        val day = planVsFact(slots, acts, monday, monday, at(monday.plusDays(1), "09:00")).single()

        assertEquals(listOf(SlotState.DONE, SlotState.DONE), day.slots.map { it.state })
        assertEquals(2L, day.slots[0].closedByActivityId) // the fallback took what was left
        assertEquals(1L, day.slots[1].closedByActivityId) // matched by time
    }

    @Test
    fun `a backfilled entry cannot close a slot whose time has not come`() {
        val slots = listOf(slot(1, "Gym", REPEAT_NONE, monday, "20:00"))
        val states = statesOf(slots, listOf(backfilled(1, monday)), monday, at(monday, "10:00"))
        assertEquals(listOf(SlotState.PLAN), states)
    }

    // --- the window edges ---

    @Test
    fun `the window reaches half an hour back and three hours forward`() {
        val slots = listOf(slot(1, "Gym", REPEAT_NONE, monday, "18:00"))
        val late = at(monday, "23:00")
        assertEquals(listOf(SlotState.DONE), statesOf(slots, listOf(act(1, monday, "17:30")), monday, late))
        assertEquals(listOf(SlotState.DONE), statesOf(slots, listOf(act(1, monday, "21:00")), monday, late))
        assertEquals(listOf(SlotState.MISS), statesOf(slots, listOf(act(1, monday, "17:29")), monday, late))
        assertEquals(listOf(SlotState.MISS), statesOf(slots, listOf(act(1, monday, "21:01")), monday, late))
    }

    // --- which slots offer a way in to logging ---

    @Test
    fun `only a slot of today that is not done offers logging`() {
        val slots = listOf(
            slot(1, "Gym", REPEAT_DAILY, monday, "08:00"),
            slot(2, "Hangboard", REPEAT_DAILY, monday, "20:00"),
        )
        // the morning session is done, the evening one is not
        val day = planVsFact(
            slots, listOf(act(1, monday, "08:10")), monday, monday, at(monday, "12:00"),
        ).single()
        assertEquals(listOf(false, true), day.slots.map { it.offersLogging(monday) })

        // the same rows read on another day offer nothing: the logging screen writes for
        // today, so a button here would record the workout on the wrong date
        assertTrue(day.slots.none { it.offersLogging(monday.plusDays(1)) })

        // and a missed slot of a day gone by is exactly the row that must not offer it
        val past = planVsFact(
            slots, emptyList(), monday, monday, at(monday.plusDays(3), "09:00"),
        ).single()
        assertEquals(listOf(SlotState.MISS, SlotState.MISS), past.slots.map { it.state })
        assertTrue(past.slots.none { it.offersLogging(monday.plusDays(3)) })
    }

    // --- journal events turned into calendar facts ---

    @Test
    fun `an entry recorded on its own day keeps its clock time`() {
        val events = listOf(
            JournalEvent(
                id = 1, ts = "2026-08-03T19:42:10", spaceId = 1, authorId = 1,
                type = TYPE_TICK, payload = """{"activity":"gym","op_date":"2026-08-03"}""",
            )
        )
        val stamps = activityStamps(events, "2026-08-01", "2026-08-31")
        assertEquals(listOf(ActivityStamp(1, "2026-08-03", 19 * 60 + 42)), stamps)
    }

    @Test
    fun `an entry recorded on another day has no clock time`() {
        val events = listOf(
            JournalEvent(
                id = 1, ts = "2026-08-04T09:00:00", spaceId = 1, authorId = 1,
                type = TYPE_TICK, payload = """{"activity":"gym","op_date":"2026-08-03"}""",
            )
        )
        assertNull(activityStamps(events, "2026-08-01", "2026-08-31").single().minuteOfDay)
    }

    @Test
    fun `stepping on the scales is not a training fact`() {
        val events = listOf(
            JournalEvent(
                id = 1, ts = "2026-08-03T07:00:00", spaceId = 1, authorId = 1,
                type = TYPE_BODYWEIGHT, payload = """{"op_date":"2026-08-03","weight_kg":70.0}""",
            )
        )
        assertTrue(activityStamps(events, "2026-08-01", "2026-08-31").isEmpty())
    }
}
