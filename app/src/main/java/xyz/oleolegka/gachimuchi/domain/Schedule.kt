package xyz.oleolegka.gachimuchi.domain

import java.time.LocalDate

/**
 * The planning calendar — a port of `bot/schedule.py` (decisions.md §12-B).
 *
 * A slot is the MASTER record of a plan ("session N at HH:MM, repeated by a rule from
 * an anchor date"). Rows for individual occurrences are NOT stored: the calendar for
 * any month is computed from a handful of slots, and editing a slot changes all its
 * future occurrences at once.
 *
 * STATUS GRANULARITY IS THE DAY, NOT THE SLOT (a deliberate simplification, same as on
 * the server): the model has no link from an event to a particular slot, so a day with
 * two slots and one workout counts as fully done, and `extra` means "there was activity
 * and NOT A SINGLE slot" rather than "activity outside the plan".
 *
 * WHAT THE RULES DELIBERATELY LACK: UNTIL, EXDATE/RDATE, intervals other than 1, and
 * several weekdays in one slot — "Gym on Mon/Thu" is TWO weekly slots.
 */

const val REPEAT_NONE = "none"
const val REPEAT_DAILY = "daily"
const val REPEAT_WEEKLY = "weekly"
val REPEAT_RULES = listOf(REPEAT_NONE, REPEAT_DAILY, REPEAT_WEEKLY)

/** A master plan slot (the domain mirror of a `slots` table row). */
data class Slot(
    val id: Long,
    val name: String,
    val atTime: String?,
    val repeatRule: String,
    val anchorDate: String,
)

/** A single occurrence of a slot in the calendar: the day plus the master slot itself. */
data class SlotOccurrence(val day: String, val slot: Slot) {
    val name: String get() = slot.name
    val atTime: String? get() = slot.atTime
}

/** Plan/fact states of a day (§12-B; the strings match the dashboard tokens). */
enum class DayState { DONE, MISS, PLAN, EXTRA, EMPTY }

/** Plan versus fact for a single day of the range. */
data class DayStatus(
    val day: String,
    val occurrences: List<SlotOccurrence>,
    val hasActivity: Boolean,
    val state: DayState,
)

/**
 * Whether a slot falls on a day according to its rule. No rule ever produces
 * occurrences BEFORE the anchor: the anchor is when the plan starts ("I set up daily
 * stretching in July" does not mean it was planned in June). An unknown rule is ignored
 * rather than allowed to crash the calendar.
 */
internal fun occursOn(slot: Slot, day: LocalDate, anchor: LocalDate): Boolean {
    if (day.isBefore(anchor)) return false
    return when (slot.repeatRule) {
        REPEAT_NONE -> day == anchor
        REPEAT_DAILY -> true
        REPEAT_WEEKLY -> (day.toEpochDay() - anchor.toEpochDay()) % 7 == 0L
        else -> false
    }
}

/**
 * Slot occurrences over the INCLUSIVE range [dateFrom]..[dateTo], sorted by
 * (day, time, id). Within a day, slots without a time come AFTER slots with one.
 */
fun slotsForRange(slots: List<Slot>, dateFrom: LocalDate, dateTo: LocalDate): List<SlotOccurrence> {
    require(!dateTo.isBefore(dateFrom)) { "date_to is earlier than date_from" }
    if (slots.isEmpty()) return emptyList()
    val anchors = slots.associate { it.id to LocalDate.parse(it.anchorDate) }
    val out = ArrayList<SlotOccurrence>()
    var day = dateFrom
    while (!day.isAfter(dateTo)) {
        for (s in slots) {
            if (occursOn(s, day, anchors.getValue(s.id))) {
                out.add(SlotOccurrence(day.toString(), s))
            }
        }
        day = day.plusDays(1)
    }
    return out.sortedWith(
        compareBy({ it.day }, { it.atTime == null }, { it.atTime ?: "" }, { it.slot.id })
    )
}

/**
 * Plan/fact status of EVERY day in the (inclusive) range, in ascending date order —
 * empty days get a row too: the calendar needs the whole grid.
 *
 * [today] is the past/future boundary: a day with a slot and no activity counts as
 * missed only if it is strictly earlier than [today]; today itself is not missed yet.
 */
fun planVsFact(
    slots: List<Slot>,
    activeDays: Set<String>,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    today: LocalDate,
): List<DayStatus> {
    require(!dateTo.isBefore(dateFrom)) { "date_to is earlier than date_from" }
    val byDay = slotsForRange(slots, dateFrom, dateTo).groupBy { it.day }
    val out = ArrayList<DayStatus>()
    var day = dateFrom
    while (!day.isAfter(dateTo)) {
        val iso = day.toString()
        val occs = byDay[iso].orEmpty()
        val act = iso in activeDays
        val state = when {
            occs.isNotEmpty() && act -> DayState.DONE
            occs.isNotEmpty() && day.isBefore(today) -> DayState.MISS
            occs.isNotEmpty() -> DayState.PLAN
            act -> DayState.EXTRA
            else -> DayState.EMPTY
        }
        out.add(DayStatus(iso, occs, act, state))
        day = day.plusDays(1)
    }
    return out
}
