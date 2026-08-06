package xyz.oleolegka.gachimuchi.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

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

// --- editing the plan ----------------------------------------------------------------
//
// Slots are the one part of the model that is EDITED rather than appended to (the journal
// of facts is append-only; the plan is not — see data/db/Entities.kt). Everything below is
// pure: the editor screen holds a draft, asks these functions whether it is usable and
// what it would mean, and only then hands it to the repository.

/**
 * A slot as the editor holds it — with the TIME AS TYPED rather than as stored.
 *
 * The raw text is deliberately part of the draft: "18:3" has to survive on screen while
 * the third digit is being typed, and a draft that parsed eagerly would either throw the
 * keystroke away or store a time nobody meant. [problem] answers "can this be saved",
 * [toSlot] turns it into the stored shape, and neither can be skipped by the caller,
 * because a draft is not a [Slot] and nothing accepts it in place of one.
 */
data class SlotDraft(
    val name: String = "",
    val timeText: String = "",
    val repeatRule: String = REPEAT_NONE,
    val anchorDate: String,
)

/** Why a draft cannot be saved yet. One reason at a time — the editor shows one message. */
enum class SlotProblem { NAME_EMPTY, TIME_UNREADABLE, RULE_UNKNOWN, DATE_UNREADABLE }

/** The draft of a slot that already exists: what "edit" opens with. */
fun Slot.toDraft(): SlotDraft = SlotDraft(
    name = name,
    timeText = atTime.orEmpty(),
    repeatRule = repeatRule,
    anchorDate = anchorDate,
)

/** The draft of a NEW slot on [day]: a one-off with no time, which is the cheapest plan. */
fun newSlotDraft(day: LocalDate): SlotDraft = SlotDraft(anchorDate = day.toString())

/**
 * The first thing wrong with the draft, or null when it can be saved.
 *
 * A blank time is NOT a problem: a session without a clock time is a normal plan ("gym
 * some time on Thursday"), which is why the column is nullable in the first place.
 */
fun SlotDraft.problem(): SlotProblem? = when {
    name.isBlank() -> SlotProblem.NAME_EMPTY
    timeText.isNotBlank() && parseSlotTime(timeText) == null -> SlotProblem.TIME_UNREADABLE
    repeatRule !in REPEAT_RULES -> SlotProblem.RULE_UNKNOWN
    runCatching { LocalDate.parse(anchorDate) }.isFailure -> SlotProblem.DATE_UNREADABLE
    else -> null
}

/** The message under the editor's fields for [problem]. */
fun problemText(problem: SlotProblem): String = when (problem) {
    SlotProblem.NAME_EMPTY -> "Give the session a name, for example Gym or Fingerboard."
    SlotProblem.TIME_UNREADABLE -> "The time should read like 18:00, or be left empty."
    SlotProblem.RULE_UNKNOWN -> "Pick how often it repeats."
    SlotProblem.DATE_UNREADABLE -> "Pick the day it belongs to."
}

/** The draft as a storable slot, or null when [problem] says it is not one yet. */
fun SlotDraft.toSlot(id: Long = 0L): Slot? {
    if (problem() != null) return null
    return Slot(
        id = id,
        name = name.trim(),
        atTime = parseSlotTime(timeText),
        repeatRule = repeatRule,
        anchorDate = anchorDate,
    )
}

private val TIME_SEPARATORS = charArrayOf(':', '.', ',', '-', ' ')

/**
 * A typed time as the stored "HH:MM", or null when it is not a time.
 *
 * Typing is generous on purpose — "7", "730", "7.30" and "07:30" all mean half past seven,
 * and on a phone the colon lives one keyboard away from the digits. What it will NOT do is
 * guess: an hour past 23 or a minute past 59 is rejected rather than rolled over, because
 * "25:00" is a typo and silently turning it into tomorrow's 01:00 would plan the wrong day.
 */
fun parseSlotTime(text: String): String? {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.any { !it.isDigit() && it !in TIME_SEPARATORS }) return null
    val parts = cleaned.split(*TIME_SEPARATORS).filter { it.isNotEmpty() }
    val (hour, minute) = when (parts.size) {
        2 -> {
            if (parts[0].length > 2 || parts[1].length > 2) return null
            (parts[0].toIntOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
        }

        1 -> {
            val digits = parts[0]
            when (digits.length) {
                // a lone hour: "7" is 07:00, not 00:07 — nobody plans a session by the minute
                1, 2 -> digits.toInt() to 0
                3 -> digits.substring(0, 1).toInt() to digits.substring(1).toInt()
                4 -> digits.substring(0, 2).toInt() to digits.substring(2).toInt()
                else -> return null
            }
        }

        else -> return null
    }
    if (hour !in 0..23 || minute !in 0..59) return null
    return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

/**
 * The repeat as a BADGE for a list row: "every day", "every week", or nothing for a
 * one-off (a badge saying "once" on most of the rows is noise).
 */
fun repeatBadge(repeatRule: String): String? = when (repeatRule) {
    REPEAT_DAILY -> "every day"
    REPEAT_WEEKLY -> "every week"
    else -> null
}

/**
 * The repeat spelled out for the EDITOR, where the weekday matters: a weekly slot repeats
 * on the weekday of its anchor, and that is decided by the date field rather than by the
 * repeat field — the one thing about this model that surprises people.
 */
fun repeatLabel(repeatRule: String, anchorDate: String): String {
    val anchor = runCatching { LocalDate.parse(anchorDate) }.getOrNull()
    return when (repeatRule) {
        REPEAT_DAILY -> "Repeats every day"
        REPEAT_WEEKLY -> anchor?.let { "Repeats every ${weekdayName(it.dayOfWeek)}" } ?: "Repeats every week"
        REPEAT_NONE -> "Happens once"
        else -> "Unknown repeat"
    }
}

private fun weekdayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

/**
 * The first day from [from] on that the slot falls on, or null when there is none (a
 * one-off already in the past, or a rule this build does not know).
 */
fun nextOccurrence(slot: Slot, from: LocalDate): LocalDate? {
    val anchor = runCatching { LocalDate.parse(slot.anchorDate) }.getOrNull() ?: return null
    val start = if (from.isBefore(anchor)) anchor else from
    return when (slot.repeatRule) {
        REPEAT_NONE -> anchor.takeIf { !it.isBefore(from) }
        REPEAT_DAILY -> start
        REPEAT_WEEKLY -> start.plusDays((7 - (start.toEpochDay() - anchor.toEpochDay()) % 7) % 7)
        else -> null
    }
}

/**
 * What deleting this slot actually does, in words, for the confirmation.
 *
 * It says PAST occurrences go too, and that is not padding: occurrences are computed from
 * the master row rather than stored (see the header of this file), so deleting a weekly
 * slot does not merely stop it — the days it used to sit on stop being planned days, and a
 * day that showed as missed becomes an ordinary empty one. The facts in the journal are
 * untouched, which is the other half of the sentence.
 *
 * It also says there is no way to drop a single day, because the model has no exceptions
 * (no EXDATE, no UNTIL): "delete" really is all or nothing, and finding that out after the
 * fact would be the worst way to learn it.
 */
fun deletionWarning(slot: Slot): String {
    val everyOccurrence =
        "Deleting it removes EVERY occurrence — the days still to come and the days " +
            "already gone, so a day it counts as missed stops counting as one. This model " +
            "has no way to skip a single date: it is the whole series or nothing. " +
            "Workouts you have already logged are not touched."
    return when (slot.repeatRule) {
        REPEAT_DAILY -> "\"${slot.name}\" repeats every day. $everyOccurrence"
        REPEAT_WEEKLY -> {
            val weekday = runCatching { LocalDate.parse(slot.anchorDate) }.getOrNull()
                ?.let { " (every ${weekdayName(it.dayOfWeek)})" }.orEmpty()
            "\"${slot.name}\" repeats every week$weekday. $everyOccurrence"
        }

        else -> "\"${slot.name}\" is planned once, on ${slot.anchorDate}. Deleting it takes " +
            "it off the plan. Workouts you have already logged are not touched."
    }
}
