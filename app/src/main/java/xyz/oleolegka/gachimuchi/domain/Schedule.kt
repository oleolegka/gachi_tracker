package xyz.oleolegka.gachimuchi.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
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
 * STATUS IS PER SLOT, and it is decided TWO WAYS. A workout started from a planned session
 * says which one in its own start event, and such a slot is closed by that workout and by
 * nothing else. Everything logged WITHOUT pressing start names no plan, and for that the old
 * inference by clock time remains — see [matchDay], which writes out both rules and the places
 * they disagree. Two slots on the same day can end up in different states: the morning gym
 * session done, the evening hangboard still planned.
 *
 * WHAT THE RULES DELIBERATELY LACK: UNTIL, EXDATE/RDATE, intervals other than 1, and
 * several weekdays in one slot — "Gym on Mon/Thu" is TWO weekly slots.
 */

const val REPEAT_NONE = "none"
const val REPEAT_DAILY = "daily"
const val REPEAT_WEEKLY = "weekly"
val REPEAT_RULES = listOf(REPEAT_NONE, REPEAT_DAILY, REPEAT_WEEKLY)

/**
 * How long before its time a slot can already be closed by an activity: you may start a
 * session a little early, and the entry is written when the first set is done.
 */
const val SLOT_WINDOW_BEFORE_MIN = 30

/**
 * How long after its time a slot can still be closed. Generous on purpose and asymmetric:
 * an entry is written DURING or AFTER a session, essentially never long before it, and a
 * session itself lasts an hour or two. This is also the moment a slot becomes "missed" —
 * as long as the window is open, an entry can still land in it, so nothing is called
 * missed while there is a chance of it.
 */
const val SLOT_WINDOW_AFTER_MIN = 180

/**
 * One exercise planned into a slot: which catalog exercise it is, and the rest between its
 * sets for THIS session.
 *
 * The exercise is held by ID and nothing else — no name, no form. Copying the name in would
 * make the plan disagree with the catalog the moment an exercise is renamed, and §11 exists
 * precisely so that a name is a label on a row rather than an identity. A dangling id (the
 * exercise was deleted) reads as "that exercise is not in the catalog any more", which is
 * the honest answer; it is not silently dropped, because a plan quietly losing a line is
 * worse than one showing a line it cannot name.
 *
 * [restSec] is NULLABLE and null is the normal value: it means "whatever this exercise
 * usually gets" (the catalog's remembered rest, and failing that the settings). A number
 * here is the user saying "in this session, that one is different" — a heavy day wants
 * three minutes where the same exercise normally gets ninety seconds.
 *
 * There is no position field: the LIST ORDER is the order. The stored table has a position
 * column, but it is written from the index on save, so there is no second copy of the order
 * that can drift from the one the editor shows.
 *
 * [side] is null for everything the plan EDITOR ever writes here — a slot's own composition
 * names no side, for the reason [xyz.oleolegka.gachimuchi.data.ActivityRepository.copyPlannedExercises]
 * gives: which hand is out of scope for a plan. It exists on this type anyway because a PAST
 * WORKOUT'S composition is read back as a list of these too (see [asPlanned]), to go through the
 * one funnel both a plan and a workout being used as a template share ([resolvedCards]) — and a
 * workout's cards, unlike a plan's, already know their side. A non-null [side] here is trusted
 * as-is and is never re-fanned by [resolvedCards], which is what stops an already-split
 * one-sided pair from being split again.
 */
data class PlannedExercise(
    val exerciseId: Long,
    val restSec: Int? = null,
    val side: HoldSide? = null,
)

/**
 * A master plan slot (the domain mirror of a `slots` table row).
 *
 * [exercises] is what the session is MEANT to consist of, and it is empty far more often
 * than not — a slot is a promise to train at a time, and filling in the exercises ahead of
 * time is an extra the user may never bother with. Nothing in the calendar reads it: the
 * plan/fact verdict is about whether a workout happened, not about whether it matched.
 */
data class Slot(
    val id: Long,
    val name: String,
    val atTime: String?,
    val repeatRule: String,
    val anchorDate: String,
    val exercises: List<PlannedExercise> = emptyList(),
    /**
     * Identity of the plan row ([xyz.oleolegka.gachimuchi.data.db.SlotEntity.uid]), or null for
     * a slot built by hand in a test rather than read out of the database.
     *
     * A workout started from this slot records it, so that "which plan was this" keeps its
     * answer in a journal that has left the phone it was written on.
     */
    val uid: String? = null,
) {
    /** How a workout names the plan it was started from — see [SlotLink]. */
    val link: SlotLink get() = SlotLink(uid, id)
}

/**
 * Which planned session a workout was started from — the identity where the start event has
 * one, and the local row number where it is old enough not to.
 *
 * Same shape and same rule as [ExerciseLink]: identities decide whenever both sides can speak
 * them, and a number is consulted only when one of the two cannot. Two phones number their
 * plans independently, so a number alone would let a merged journal claim a workout was
 * started from somebody else's Tuesday.
 *
 * Null is not represented here: a workout started off-plan has no [SlotLink] at all.
 */
data class SlotLink(val uid: String?, val id: Long?) {

    /** Whether two references name the same planned session. */
    fun matches(other: SlotLink): Boolean =
        if (uid != null && other.uid != null) uid == other.uid else id != null && id == other.id
}

/**
 * What is planned in one slot, for a caller that holds the plan and a slot id rather than
 * the slot itself — which is the shape starting a workout from a planned session has.
 *
 * An unknown id (or none) answers "nothing planned" rather than throwing: the slot may have
 * been deleted between the screen reading it and the button being pressed, and a workout
 * must start regardless. Nothing here is required for a workout to happen — see [Slot].
 */
fun plannedExercises(slots: List<Slot>, slotId: Long?): List<PlannedExercise> =
    slots.firstOrNull { it.id == slotId }?.exercises.orEmpty()

/** A single occurrence of a slot in the calendar: the day plus the master slot itself. */
data class SlotOccurrence(val day: String, val slot: Slot) {
    val name: String get() = slot.name
    val atTime: String? get() = slot.atTime

    /** Minutes since midnight, or null for a slot with no time ("some time that day"). */
    val minuteOfDay: Int? get() = parseMinuteOfDay(slot.atTime)
}

/** State of ONE slot occurrence. */
enum class SlotState { DONE, MISS, PLAN }

/** A slot occurrence together with its verdict and the activity that closed it. */
data class SlotStatus(
    val occurrence: SlotOccurrence,
    val state: SlotState,
    val closedByActivityId: Long? = null,
) {
    val day: String get() = occurrence.day
    val name: String get() = occurrence.name
    val atTime: String? get() = occurrence.atTime
    val slot: Slot get() = occurrence.slot
}

/*
 * There used to be an `offersLogging(today)` here, which decided that only TODAY's
 * outstanding slots may offer a way to log against them. Its reason was true when it was
 * written and is not any more: the logging screen wrote entries for today, so a button on
 * last Tuesday's missed slot would have recorded the workout on the wrong date.
 *
 * A workout now carries its own op_date (§13.6) and the screen is told which day it writes
 * under, so starting one from a slot in the past dates it to that slot's day. Keeping the
 * rule would have kept the restriction without the reason for it, and left a function in
 * the domain stating a fact about the app that had stopped being one. Whether a day can be
 * recorded against at all is decided in domain/DayCards.kt, and the answer there is "any
 * day that is not in the future".
 */

/** Plan/fact states of a day (§12-B; the strings match the dashboard tokens). */
enum class DayState { DONE, MISS, PLAN, EXTRA, EMPTY }

/**
 * Plan versus fact for a single day.
 *
 * [unmatchedActivities] are the entries that closed no slot — training that was not in
 * the plan (or was recorded far away from any planned time).
 */
data class DayStatus(
    val day: String,
    val slots: List<SlotStatus>,
    val activityCount: Int,
    val unmatchedActivities: Int,
    val state: DayState,
) {
    val occurrences: List<SlotOccurrence> get() = slots.map { it.occurrence }
    val hasActivity: Boolean get() = activityCount > 0

    /** Ids of the entries that closed a slot — the rest of the day's entries are unplanned. */
    val closedByActivityIds: Set<Long> get() = slots.mapNotNull { it.closedByActivityId }.toSet()
}

/**
 * One recorded activity as the calendar sees it: which day it belongs to, and at what
 * time of day it was written.
 *
 * [minuteOfDay] is null when the clock time says nothing about when the training
 * happened — an entry backfilled on another day carries the time it was TYPED, not the
 * time it was trained. Such an entry still counts as a fact for its day, it just cannot
 * be attributed to a slot by time.
 */
data class ActivityStamp(
    val id: Long,
    val day: String,
    val minuteOfDay: Int?,
    /**
     * The planned session the workout this entry was recorded during was STARTED FROM, or null
     * when it was recorded outside a workout or in one started off-plan.
     *
     * A STATEMENT RATHER THAN A GUESS — the button that started the workout was on that slot's
     * own card — and [matchDay] treats it as one, ignoring the clock entirely for such an entry.
     *
     * It rides on the ENTRY and not on the workout because the calendar sees nothing but
     * entries, and that has a consequence worth stating: a workout that was started from a slot
     * and then had nothing logged into it produces no stamp at all, so it closes nothing and the
     * slot stays outstanding. That is the right answer — a session started and abandoned is not
     * a session that was done — and it is why the day screen still hides such a plan by its own
     * rule (see domain/DayCards.kt) rather than through this one.
     */
    val slot: SlotLink? = null,
)

/** "HH:MM" -> minutes since midnight; null for null or unparsable input. */
fun parseMinuteOfDay(at: String?): Int? {
    val text = at?.trim() ?: return null
    val parts = text.split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/**
 * Journal events turned into calendar facts over the inclusive [dateFrom]..[dateTo]
 * range. Body weight is excluded through [FACT_TYPES] — stepping on the scales is not
 * training.
 *
 * This is also where an entry picks up the plan it was recorded against
 * ([ActivityStamp.slot]): the link lives on the workout's start event, not on the entry, so
 * it has to be carried across here — the calendar never sees a [Workout].
 */
fun activityStamps(
    events: List<JournalEvent>,
    dateFrom: String,
    dateTo: String,
    types: Collection<String> = FACT_TYPES,
): List<ActivityStamp> {
    // resolved once for the whole range: doing it per entry would walk the journal again for
    // every set in it, and a month of the calendar asks for a lot of sets
    val startedFromAPlan = workoutStarts(events).mapNotNull { (row, started) ->
        started?.slotLink()?.let { row to it }
    }
    return readActivities(events, types, dateFrom, dateTo).map { ev ->
        // ts is "YYYY-MM-DDTHH:MM:SS" (data/ActivityRepository.now); only a timestamp from the
        // SAME day as the entry tells us anything about when the training took place
        val sameDay = ev.ts.length >= 16 && ev.ts.startsWith(ev.opDate + "T")
        val minute = if (sameDay) parseMinuteOfDay(ev.ts.substring(11, 16)) else null
        val ref = ev.workout
        val slot = if (ref == null) null else {
            startedFromAPlan.firstOrNull { (start, _) -> ref.matches(start) }?.second
        }
        ActivityStamp(id = ev.id, day = ev.opDate, minuteOfDay = minute, slot = slot)
    }
}

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

/** The moment a slot's window opens: [SLOT_WINDOW_BEFORE_MIN] before it, or midnight. */
internal fun windowOpensAt(occ: SlotOccurrence): LocalDateTime {
    val start = LocalDate.parse(occ.day).atStartOfDay()
    val minute = occ.minuteOfDay ?: return start
    return start.plusMinutes((minute - SLOT_WINDOW_BEFORE_MIN).toLong())
}

/** The moment it closes: [SLOT_WINDOW_AFTER_MIN] after it, or the end of the day. */
internal fun windowClosesAt(occ: SlotOccurrence): LocalDateTime {
    val start = LocalDate.parse(occ.day).atStartOfDay()
    val minute = occ.minuteOfDay ?: return start.plusDays(1)
    return start.plusMinutes((minute + SLOT_WINDOW_AFTER_MIN).toLong())
}

/**
 * Which activity closes which slot on ONE day. Returns slot index -> activity id;
 * a slot missing from the map was not closed.
 *
 * [acts] must arrive in the day's stable order (see [planVsFact]) — both halves of the rule
 * below break ties by it, so a different order would be a different verdict.
 *
 * ── The statement, which is asked first ─────────────────────────────────────────
 * 0. An entry recorded during a workout that was STARTED FROM a slot ([ActivityStamp.slot])
 *    closes THAT slot and no other one. These pairs are made before anything else and the
 *    clock is not consulted for them at all. An entry that names a plan is then FINISHED
 *    WITH, whether it closed anything or not: it never falls through to the guess below.
 *
 * ── The guess, for everything that named no plan ────────────────────────────────
 * 1. Each activity closes AT MOST ONE slot, and each slot needs its own activity — two
 *    slots and one workout means one of them stays open.
 * 2. A slot can only be closed once its window has OPENED ([windowOpensAt]). A slot whose
 *    time has not arrived yet can therefore never be "done" — the bug this rule exists
 *    for: an entry made at noon used to mark an eight-in-the-evening session as done.
 * 3. A timed slot and an activity with a known clock time pair up only if the activity
 *    falls inside the window [time - 30 min, time + 3 h]; the pair with the SMALLEST
 *    distance is taken first, then the next one, and so on (a greedy nearest match).
 * 4. A slot WITHOUT a time ("some time that day") pairs with any activity of the day, and
 *    an activity whose clock time is unknown (backfilled on another day) pairs with any
 *    slot of the day whose window has opened. Both are the fallback: they are only
 *    considered after every match by time has been made, and they run in day order
 *    (earliest slot first).
 * 5. Whatever is left over stays unmatched: such an activity is UNPLANNED training, and
 *    such a slot is missed once its window has closed.
 *
 * ── Where the statement and the guess disagree ──────────────────────────────────
 * The guess has one defect that cannot be tuned away: it never looks at WHAT was trained, so
 * a set of push-ups closes a planned gym session as well as the gym session would. Rule 0 is
 * the fix, and it changes three verdicts.
 *
 * **A workout started from a slot but recorded far from its time.** The old rule left the
 * slot missed and the sets unplanned; now the slot is done. Rule 2 does not apply to it
 * either — sets logged at noon close a session planned for eight in the evening if that is
 * the session they were started from. Rule 2 exists to stop an UNRELATED entry claiming a
 * session that has not happened yet, and nothing here is unrelated: somebody pressed start on
 * that card. The plan was answered early rather than not at all.
 *
 * **Two workouts started from the same slot.** One slot is one session, so the FIRST entry in
 * the day's order closes it and everything the other workout recorded stays unmatched —
 * unplanned training on a day that also had a plan. Deliberately not "both close it" (nothing
 * downstream can hold two activities against one slot) and deliberately not "the second one
 * goes to the guess" (see below).
 *
 * **A workout naming a slot that is not on the day** — the slot was deleted, its rule was
 * edited so this date is no longer one of its occurrences, or the journal was merged in from a
 * phone whose plans this one does not have. There is nothing to close: those entries stay
 * unmatched and any OTHER slot on the day stays open, exactly as if that training had not
 * happened. They are not handed back to the guess, and that is the whole point of the change —
 * a workout that says "this was Tuesday's gym" must not be allowed to close Tuesday's
 * hangboard instead just because the two times are close. The cost is real and is the same one
 * [deletionWarning] already promises out loud: removing a plan un-plans the days it sat on, so
 * a day that read as done can go back to reading as merely trained.
 */
internal fun matchDay(
    occs: List<SlotOccurrence>,
    acts: List<ActivityStamp>,
    now: LocalDateTime,
): Map<Int, Long> {
    if (occs.isEmpty() || acts.isEmpty()) return emptyMap()

    val taken = HashMap<Int, Long>()
    // Rule 0. A slot occurs at most once in a day, so "the first occurrence that matches" is
    // the only one; the second entry naming an already-closed slot closes nothing.
    for (act in acts) {
        val link = act.slot ?: continue
        val occIdx = occs.indexOfFirst { it.slot.link.matches(link) }
        if (occIdx >= 0 && occIdx !in taken) taken[occIdx] = act.id
    }

    // tier 0 = matched by time, tier 1 = fallback; inside a tier the closest pair wins,
    // then the earlier slot, then the earlier activity
    data class Candidate(val tier: Int, val cost: Int, val occIdx: Int, val actIdx: Int)

    val candidates = ArrayList<Candidate>()
    occs.forEachIndexed { occIdx, occ ->
        if (occIdx in taken) return@forEachIndexed
        if (now.isBefore(windowOpensAt(occ))) return@forEachIndexed
        val slotMinute = occ.minuteOfDay
        acts.forEachIndexed { actIdx, act ->
            // an entry that named its plan was dealt with above and is out of the guess
            if (act.slot != null) return@forEachIndexed
            val actMinute = act.minuteOfDay
            if (slotMinute != null && actMinute != null) {
                val delta = actMinute - slotMinute
                if (delta < -SLOT_WINDOW_BEFORE_MIN || delta > SLOT_WINDOW_AFTER_MIN) return@forEachIndexed
                candidates.add(Candidate(0, if (delta < 0) -delta else delta, occIdx, actIdx))
            } else {
                candidates.add(Candidate(1, 0, occIdx, actIdx))
            }
        }
    }
    candidates.sortWith(compareBy({ it.tier }, { it.cost }, { it.occIdx }, { it.actIdx }))
    val usedActs = HashSet<Int>()
    for (c in candidates) {
        if (c.occIdx in taken || c.actIdx in usedActs) continue
        taken[c.occIdx] = acts[c.actIdx].id
        usedActs.add(c.actIdx)
    }
    return taken
}

/**
 * Plan/fact status of EVERY day in the (inclusive) range, in ascending date order —
 * empty days get a row too: the calendar needs the whole grid.
 *
 * [now] is the clock: it decides which slots can already be closed and which are late
 * (see [matchDay]). Slot states are computed first, and the day's own state is their
 * summary:
 *
 * - no slots at all: EXTRA when something was recorded, EMPTY otherwise;
 * - any slot missed: MISS (a hole in the day is the thing worth seeing);
 * - every slot done: DONE;
 * - otherwise PLAN — the day still has something outstanding, even if part of it is done.
 */
fun planVsFact(
    slots: List<Slot>,
    activities: List<ActivityStamp>,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    now: LocalDateTime,
): List<DayStatus> {
    require(!dateTo.isBefore(dateFrom)) { "date_to is earlier than date_from" }
    val occsByDay = slotsForRange(slots, dateFrom, dateTo).groupBy { it.day }
    val actsByDay = activities.groupBy { it.day }
    val out = ArrayList<DayStatus>()
    var day = dateFrom
    while (!day.isAfter(dateTo)) {
        val iso = day.toString()
        val occs = occsByDay[iso].orEmpty()
        // the fallback pairs in journal order, so a stable activity order is part of the rule
        val acts = actsByDay[iso].orEmpty()
            .sortedWith(compareBy({ it.minuteOfDay ?: Int.MAX_VALUE }, { it.id }))
        val matched = matchDay(occs, acts, now)
        val statuses = occs.mapIndexed { idx, occ ->
            val closedBy = matched[idx]
            val state = when {
                closedBy != null -> SlotState.DONE
                !now.isBefore(windowClosesAt(occ)) -> SlotState.MISS
                else -> SlotState.PLAN
            }
            SlotStatus(occurrence = occ, state = state, closedByActivityId = closedBy)
        }
        val state = when {
            statuses.isEmpty() -> if (acts.isEmpty()) DayState.EMPTY else DayState.EXTRA
            statuses.any { it.state == SlotState.MISS } -> DayState.MISS
            statuses.all { it.state == SlotState.DONE } -> DayState.DONE
            else -> DayState.PLAN
        }
        out.add(
            DayStatus(
                day = iso,
                slots = statuses,
                activityCount = acts.size,
                unmatchedActivities = acts.size - matched.size,
                state = state,
            )
        )
        day = day.plusDays(1)
    }
    return out
}

// --- the calendar's dots --------------------------------------------------------------
//
// A rework of §12-B's grid (2026-08-10): the cell used to carry the day's own [DayState] as
// a wash, which had no honest answer for "two sessions, one done and one missed" — a day is
// not one verdict, its SLOTS are. The dots now say so directly, one per slot still open and
// one per thing actually done, instead of the cell trying to average them into a single tint.

/** How many dots the calendar draws under a day before the rest collapse into a "+N" mark. */
const val MAX_CALENDAR_DOTS = 6

/**
 * A day's dots for the calendar: how many, and in which colour.
 *
 * [states] reuses [SlotState] as the colour a dot draws in rather than inventing a fourth
 * enum, because the three colours a dot can be ARE the three verdicts a slot can carry:
 * green for done, red for missed, blue for still planned. A [SlotState.DONE] entry here is
 * not always a slot's own verdict, though — see [calendarDots] for where the green ones
 * without a slot behind them come from.
 *
 * [overflow] is how many were left off past [MAX_CALENDAR_DOTS], and it is never silently
 * zero when dots were actually dropped — the bug this replaces threw away everything past
 * three dots without saying so.
 */
data class DayDots(val states: List<SlotState>, val overflow: Int) {
    val isEmpty: Boolean get() = states.isEmpty() && overflow == 0
}

/**
 * The calendar's dots for one day: one PER JOURNAL INSTANCE, not per exercise — a whole
 * workout is one dot however many exercises it holds, and a run of entries logged with no
 * workout around them is one dot too (see domain/Analytics.kt's `journalInstanceCounts`,
 * which counts the same units domain/DayCards.kt turns into RUNNING/DONE/SINGLE cards).
 * [instanceCount] is that count, folded elsewhere because it needs the journal and this
 * function is only handed [status].
 *
 * ── Green covers more than a closed slot ─────────────────────────────────────────
 * Every instance draws a green [SlotState.DONE] dot, whether or not it happened to close a
 * plan — training that was never on the plan still gets its green dot ("well I did it
 * anyway"), which the old per-day wash had no colour for at all.
 *
 * ── A DONE slot draws no dot of its own ───────────────────────────────────────────
 * The instance that closed it already drew one; a second dot for the same session would be
 * the same training counted twice. [SlotState.MISS] and [SlotState.PLAN] have no instance
 * standing in for them, so each of those still draws its own.
 */
fun calendarDots(status: DayStatus, instanceCount: Int, maxDots: Int = MAX_CALENDAR_DOTS): DayDots {
    require(instanceCount >= 0) { "instanceCount must not be negative" }
    val all = ArrayList<SlotState>(instanceCount + status.slots.size)
    repeat(instanceCount) { all += SlotState.DONE }
    for (slot in status.slots) {
        if (slot.state != SlotState.DONE) all += slot.state
    }
    val shown = all.take(maxDots)
    return DayDots(shown, all.size - shown.size)
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
    /**
     * The planned session, in order. AN EMPTY LIST IS A COMPLETE DRAFT — [problem] never
     * looks at it. "Gym on Thursday" with nothing under it is the plan most of the time,
     * and making the composition a thing that has to be filled in would turn the cheapest
     * useful plan into a form.
     */
    val exercises: List<PlannedExercise> = emptyList(),
)

/**
 * Why a draft cannot be saved yet. One reason at a time — the editor shows one message.
 *
 * [DATE_IN_PAST] is never returned by [problem] itself — see [isBackdated] for why it lives
 * apart — but it is a member here because [problemText] and the editor's "one message" still
 * have to be able to say it.
 */
enum class SlotProblem { NAME_EMPTY, TIME_UNREADABLE, RULE_UNKNOWN, DATE_UNREADABLE, DATE_IN_PAST }

/** The draft of a slot that already exists: what "edit" opens with. */
fun Slot.toDraft(): SlotDraft = SlotDraft(
    name = name,
    timeText = atTime.orEmpty(),
    repeatRule = repeatRule,
    anchorDate = anchorDate,
    exercises = exercises,
)

/** The draft of a NEW slot on [day]: a one-off with no time, which is the cheapest plan. */
fun newSlotDraft(day: LocalDate): SlotDraft = SlotDraft(anchorDate = day.toString())

/**
 * The first thing wrong with the draft, or null when it can be saved.
 *
 * A blank time is NOT a problem: a session without a clock time is a normal plan ("gym
 * some time on Thursday"), which is why the column is nullable in the first place.
 *
 * Neither is an empty list of exercises, and that one is deliberate rather than an
 * oversight: the composition of a session is an OPTIONAL note to self, and a plan that
 * refused to save until it was filled in would be a form standing between the user and the
 * one thing this dialog is for.
 */
fun SlotDraft.problem(): SlotProblem? = when {
    name.isBlank() -> SlotProblem.NAME_EMPTY
    timeText.isNotBlank() && parseSlotTime(timeText) == null -> SlotProblem.TIME_UNREADABLE
    repeatRule !in REPEAT_RULES -> SlotProblem.RULE_UNKNOWN
    runCatching { LocalDate.parse(anchorDate) }.isFailure -> SlotProblem.DATE_UNREADABLE
    else -> null
}

/** The message under the editor's fields for [problem] (or for [isBackdated], for the editor). */
fun problemText(problem: SlotProblem): String = when (problem) {
    SlotProblem.NAME_EMPTY -> "Give the session a name, for example Gym or Fingerboard."
    SlotProblem.TIME_UNREADABLE -> "Finish the time: type the digits and 1700 becomes 17:00. " +
        "An empty field means some time that day."
    SlotProblem.RULE_UNKNOWN -> "Pick how often it repeats."
    SlotProblem.DATE_UNREADABLE -> "Pick the day it belongs to."
    SlotProblem.DATE_IN_PAST -> "Plans can only be made for today or later — a day already " +
        "gone keeps whatever it already shows. Move the date forward, or log what actually " +
        "happened instead."
}

/**
 * Whether [today] is why this draft cannot be saved from the EDITOR — a day already gone,
 * as opposed to something wrong with a field (see [SlotProblem.DATE_IN_PAST]).
 *
 * ── Reported bugs, and why the fix is here ───────────────────────────────────────
 * "Nailing a plan onto yesterday" turned out to be two bugs with one cause: the anchor date
 * had no floor. A one-off could be planned straight onto a day already gone, rewriting
 * whatever `planVsFact` already said about it (a MISSED slot only exists because nothing
 * closed it — a plan added after the fact IS a way to manufacture one). And a REPEATING slot
 * anchored on a past day made every past occurrence of it "planned" as well, because
 * [occursOn] has always started counting from the anchor, not from today — it was never
 * wrong, the anchor it was handed was. Refusing an anchor before today closes both at once:
 * the earliest a newly saved slot can occur is today, whatever the rule says.
 *
 * ── Kept apart from [problem] ─────────────────────────────────────────────────────
 * [problem] is also what [toSlot] checks, and [toSlot] has no "today" of its own — it is
 * reached by more than the screen (data/ActivityRepository.kt's `saveSlot` calls it
 * directly, on drafts built outside any dialog). "Plans cannot be backdated" is the
 * EDITOR's own rule about what a person is doing right now, not a property every caller of
 * [toSlot] has to prove, so only the screen asks this question and only the screen enforces
 * the answer.
 */
fun SlotDraft.isBackdated(today: LocalDate): Boolean =
    runCatching { LocalDate.parse(anchorDate) }.getOrNull()?.isBefore(today) == true

/** The draft as a storable slot, or null when [problem] says it is not one yet. */
fun SlotDraft.toSlot(id: Long = 0L): Slot? {
    if (problem() != null) return null
    return Slot(
        id = id,
        name = name.trim(),
        atTime = parseSlotTime(timeText),
        repeatRule = repeatRule,
        anchorDate = anchorDate,
        exercises = exercises,
    )
}

// --- editing the composition ----------------------------------------------------------
//
// Four operations on a list, kept here rather than in the dialog for the usual reason: they
// are the part with the off-by-one in it. An index that no longer exists is a NO-OP in all
// of them — the editor and the list it is editing are one recomposition apart, and a tap
// that arrives against a stale index should do nothing rather than crash the dialog the
// user is halfway through.

/** Appends an exercise to the plan. Duplicates are allowed — see [SlotDraft.exercises]. */
fun SlotDraft.withExerciseAdded(exerciseId: Long, restSec: Int? = null): SlotDraft =
    copy(exercises = exercises + PlannedExercise(exerciseId, restSec?.takeIf { it > 0 }))

fun SlotDraft.withExerciseRemoved(index: Int): SlotDraft =
    if (index !in exercises.indices) this
    else copy(exercises = exercises.filterIndexed { i, _ -> i != index })

/**
 * Moves one exercise by [delta] places. Off either end is a no-op rather than a clamp: the
 * buttons that drive this are disabled at the ends, so an out-of-range move means the list
 * changed underneath and doing nothing is the only answer that cannot reorder the wrong row.
 */
fun SlotDraft.withExerciseMoved(index: Int, delta: Int): SlotDraft {
    val target = index + delta
    if (index !in exercises.indices || target !in exercises.indices || delta == 0) return this
    val moved = exercises.toMutableList()
    moved.add(target, moved.removeAt(index))
    return copy(exercises = moved)
}

/**
 * Sets (or clears) the rest for one planned exercise. A non-positive number is stored as
 * null: zero is not a rest of no seconds, it is the absence of an answer, and null is how
 * "nothing was said, use the usual one" is spelled everywhere else in the model.
 */
fun SlotDraft.withExerciseRest(index: Int, restSec: Int?): SlotDraft {
    if (index !in exercises.indices) return this
    val wanted = restSec?.takeIf { it > 0 }
    return copy(
        exercises = exercises.mapIndexed { i, e -> if (i == index) e.copy(restSec = wanted) else e }
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
 *
 * A HALF-TYPED MINUTE IS NOT A TIME. "18:5" is refused rather than read as 18:05, because
 * the editor no longer waits for a colon to be typed — [formatTimeDigits] puts it in as the
 * digits arrive, so "18:5" on screen means three digits of four are in and the fourth is
 * still coming. Reading it as 18:05 would silently store a time nobody typed, exactly half
 * the time (the other half meant 18:50). The whole minute or nothing.
 */
fun parseSlotTime(text: String): String? {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.any { !it.isDigit() && it !in TIME_SEPARATORS }) return null
    val parts = cleaned.split(*TIME_SEPARATORS).filter { it.isNotEmpty() }
    val (hour, minute) = when (parts.size) {
        2 -> {
            if (parts[0].length > 2 || parts[1].length != 2) return null
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

// --- typing a time -------------------------------------------------------------------
//
// The slot time is stored as "HH:MM", and a phone's number keypad has no colon on it —
// so the colon is never typed. It is either placed by [formatTimeDigits] as the digits
// arrive, or the whole value comes from the clock dialog.

/**
 * Digits as typed -> what the field should show. The colon appears by itself:
 * "1" -> "1", "17" -> "17", "170" -> "17:0", "1700" -> "17:00", and
 * "9" -> "9", "93" -> "9:3", "930" -> "9:30".
 *
 * The hour takes two digits only when two digits can BE an hour: a leading 3..9, or a
 * pair over 23, means the hour was a single digit ("25" is 2:5x, not a broken 25 o'clock).
 * Everything that is not a digit is dropped, so pasting "17:00" also works.
 */
fun formatTimeDigits(input: String): String {
    val digits = input.filter { it.isDigit() }
    if (digits.isEmpty()) return ""
    val twoDigitHour = digits[0] < '3' && (digits.length < 2 || digits.take(2).toInt() <= 23)
    val hourLen = if (twoDigitHour) 2 else 1
    if (digits.length <= hourLen) return digits
    return digits.take(hourLen) + ":" + digits.drop(hourLen).take(2)
}

/** Hour and minute from the clock dialog -> the stored "HH:MM". */
fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

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
