package xyz.oleolegka.gachimuchi.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A day as a short list of cards: the things you can start, the thing you are in the middle
 * of, and the things you have already done.
 *
 * ── One list, two screens ───────────────────────────────────────────────────────
 * The Today tab and the day picked on the calendar show the SAME list and differ only in
 * which date they ask for. That is why the whole thing is here rather than inside either
 * screen: two screens computing "what happened on this day" separately is two answers, and
 * the day they disagree is the day the user stops believing the app. The screens keep the
 * drawing; every decision — what is a card, in which order, what its subtitle says — is
 * decided once, in a pure function, and tested on the JVM.
 *
 * ── What is and is not a card ───────────────────────────────────────────────────
 * A card is a WORKOUT or a group of entries recorded OUTSIDE one. It is deliberately not
 * "an exercise" (which is what the Today screen used to list): a gym session and two
 * stretching entries make three cards, not eleven, and three cards fit on a phone.
 *
 * Entries logged with no workout around them are grouped BY EXERCISE — five fingerboard
 * sets on their own are one card, not five. Without that the whole point of the change is
 * lost the first time somebody logs a real set of sets.
 *
 * ── Colour says nothing ─────────────────────────────────────────────────────────
 * The kinds are told apart by their SUBTITLE, in words. A workout says how many exercises
 * and how many sets; a single entry says it was outside a workout. Nothing in this app
 * encodes a status in colour alone, and the card list is not going to be the exception.
 */

/** Which of the four things a card is. */
enum class DayCardKind {
    /** A planned session with no workout against it yet. */
    PLANNED,

    /** The workout in progress — see [openWorkout] for what "in progress" means. */
    RUNNING,

    /** A workout that is not the open one. */
    DONE,

    /** Entries of one exercise recorded outside any workout. */
    SINGLE,
}

/** What the card's primary tap does. */
enum class DayCardAction {
    /** Begin a workout from this plan. */
    START,

    /** Go back into the workout that is already running. */
    CONTINUE,

    /** Open what is behind the card, to look at it. */
    OPEN,

    /** Nothing to do here — see [dayCards] for the two cases that produce it. */
    NONE,
}

/**
 * One card of a day.
 *
 * The three id fields are the card's target and only one of them is ever set for a given
 * [kind]; they are nullable rather than a sealed hierarchy because every consumer wants the
 * same six strings out of a card and would otherwise repeat the same `when` to get them.
 */
data class DayCard(
    val kind: DayCardKind,
    /** Stable within a day — the list key, and what the tests assert order on. */
    val key: String,
    val title: String,
    val subtitle: String,
    /** "18:20", "18:20 - 19:35", or "" when nothing on the card carries a usable clock time. */
    val timeLabel: String,
    val action: DayCardAction,
    /** Records broken on this card, in one line, or null when there were none. */
    val recordLine: String? = null,
    /** [DayCardKind.PLANNED]: the slot to start from. */
    val slotId: Long? = null,
    /** [DayCardKind.RUNNING] and [DayCardKind.DONE]: the workout to open. */
    val workoutId: Long? = null,
    /**
     * What the workout was CALLED, or null when nobody named it.
     *
     * Carried beside [title] rather than inferred from it: an unnamed workout is titled by its
     * time range, so a screen offering to rename one would otherwise have to guess whether the
     * title it is holding is a name or a clock — and would offer "18:05 - 19:35" as the value
     * to edit. Null here is the honest "nobody named it", and the field a rename starts empty.
     */
    val workoutName: String? = null,
    /** [DayCardKind.SINGLE]: the exercise its entries are about, when they name one. */
    val exerciseId: Long? = null,
)

/** The whole day: its cards, and whether anything may still be recorded against it. */
data class DayCards(
    val date: String,
    val cards: List<DayCard>,
    /**
     * Whether this day can be trained.
     *
     * False for the future and only for the future. Recording a set you have not done yet
     * is not a feature with an edge case, it is a wrong journal; typing up a day already
     * gone is exactly what the model was reshaped for (§13.6), so the past stays open.
     */
    val canRecord: Boolean,
) {
    val isEmpty: Boolean get() = cards.isEmpty()
}

/**
 * The cards of [date].
 *
 * [today] decides which workout is the running one and whether the day can still be
 * recorded against; [now] is the clock the plan is judged by (domain/Schedule.kt), which is
 * a finer reading than the date — a session at 20:00 is still outstanding at noon.
 *
 * ── When a plan gets a card and when it does not ────────────────────────────────
 * A slot is shown as startable unless something already answers it. Two things can:
 *
 *  - a workout on this day that was STARTED FROM IT ([Workout.slot]), which is the exact link
 *    §13.7 introduced and needs no guessing;
 *  - the plan/fact verdict (domain/Schedule.kt), which closes the slot outright once anything
 *    was recorded — by that same link where there is one, and by clock proximity for entries
 *    logged without anyone pressing "start".
 *
 * The two overlap almost entirely now that the verdict reads the link as well, and the first
 * one is kept for the case the second cannot cover: a workout started from a plan with NOTHING
 * logged into it yet. The calendar sees entries, so it has nothing to close the slot with and
 * rightly leaves it outstanding — but the day screen would then show the plan next to the very
 * workout that is answering it.
 *
 * What is left of the guess: a workout started OFF-PLAN near a planned time will still absorb
 * that slot, and a set of push-ups still counts against a planned gym session. That is the
 * same heuristic the calendar's colours use, so the two agree.
 */
fun dayCards(
    events: List<JournalEvent>,
    slots: List<Slot>,
    date: LocalDate,
    today: LocalDate,
    now: LocalDateTime,
): DayCards {
    val iso = date.toString()
    val canRecord = !date.isAfter(today)
    val openUid = openWorkoutRow(events)?.uid
    val workouts = workoutsOn(events, iso)
    val startedFromSlot = workouts.mapNotNull { it.slot }

    /*
     * The records of the day, computed ONCE off the same reducer the logging feed uses, and
     * then looked up by event id. Recomputing them per card would fold the whole journal
     * once per card, and — worse — a second implementation of "was that a record" is a
     * second answer waiting to disagree with the first.
     */
    val recordOf: Map<Long, RecordHit?> = buildSession(events, iso).groups
        .flatMap { it.sets }
        .associate { it.eventId to it.record }

    val rows = ArrayList<Placed>()

    for (status in slotStatuses(slots, events, date, now)) {
        if (status.state == SlotState.DONE) continue
        if (startedFromSlot.any { it.matches(status.slot.link) }) continue
        rows += placedPlan(status, canRecord)
    }

    for (workout in workouts) {
        rows += placedWorkout(workout, recordOf, running = workout.uid == openUid)
    }

    for (group in looseGroups(events, iso)) {
        rows += placedSingle(group, recordOf)
    }

    rows.sortWith(compareBy({ it.minute ?: Int.MAX_VALUE }, { it.rank }, { it.tiebreak }))
    return DayCards(date = iso, cards = rows.map { it.card }, canRecord = canRecord)
}

// --- placement ------------------------------------------------------------------------
//
// A card carries no sort key of its own: the ordering is an argument about the day, not a
// property of a card, and putting it in the data class would mean every test fixture had to
// state it. So the sort fields travel alongside the card and are dropped on the way out.

/** A card with the three things the day's order is decided by. */
private data class Placed(
    /** Minutes since midnight, or null when nothing dates this card within the day. */
    val minute: Int?,
    /**
     * Facts before plans when both are undated: what happened outranks what was merely
     * intended, and a plan with no time means "some time that day" anyway.
     */
    val rank: Int,
    /** Journal order, so two undated cards never swap places between two readings. */
    val tiebreak: Long,
    val card: DayCard,
)

private const val RANK_FACT = 0
private const val RANK_PLAN = 1

private fun slotStatuses(
    slots: List<Slot>,
    events: List<JournalEvent>,
    date: LocalDate,
    now: LocalDateTime,
): List<SlotStatus> =
    planVsFact(slots, activityStamps(events, date.toString(), date.toString()), date, date, now)
        .firstOrNull()?.slots.orEmpty()

private fun placedPlan(status: SlotStatus, canRecord: Boolean): Placed = Placed(
    minute = status.occurrence.minuteOfDay,
    rank = RANK_PLAN,
    tiebreak = status.slot.id,
    card = DayCard(
        kind = DayCardKind.PLANNED,
        key = "slot:${status.slot.id}",
        title = status.name,
        subtitle = planSubtitle(status, canRecord),
        timeLabel = status.atTime.orEmpty(),
        // a day in the future has nothing to start: the set has not been done yet
        action = if (canRecord) DayCardAction.START else DayCardAction.NONE,
        slotId = status.slot.id,
    ),
)

private fun planSubtitle(status: SlotStatus, canRecord: Boolean): String = when {
    status.state == SlotState.MISS -> "missed - nothing was recorded"
    !canRecord -> "planned"
    else -> "not started yet"
}

private fun placedWorkout(
    workout: Workout,
    recordOf: Map<Long, RecordHit?>,
    running: Boolean,
): Placed {
    val entries = workout.exercises.flatMap { it.sets } + workout.entriesWithoutExercise
    val times = clockTimes(entries.map { it.ts } + workout.ts, workout.opDate)
    // the name is the SNAPSHOT taken when the workout was started, never the plan's name as
    // it reads today: the plan is editable and this is a fact about a day already lived. The
    // plan is still linked, it is simply not asked what the workout is called.
    val name = workout.name
    val range = timeRange(times)
    return Placed(
        minute = times.minOrNull()?.let { parseMinuteOfDay(it) },
        rank = RANK_FACT,
        tiebreak = workout.id,
        card = DayCard(
            kind = if (running) DayCardKind.RUNNING else DayCardKind.DONE,
            key = "workout:${workout.id}",
            // a workout nobody named is shown BY ITS TIME (§13: a name must never be a
            // condition of starting one), and the label is dropped so it is not said twice
            title = name ?: range.ifEmpty { "Workout" },
            subtitle = workoutSubtitle(workout, running),
            timeLabel = if (name != null) range else "",
            action = if (running) DayCardAction.CONTINUE else DayCardAction.OPEN,
            recordLine = recordLine(entries.map { it.id }, recordOf),
            slotId = workout.slotId,
            workoutId = workout.id,
            workoutName = name,
        ),
    )
}

/**
 * "3 exercises, 11 sets" — the state of the workout, which is what tells it apart from a
 * single entry at a glance. An exercise added and not yet done still counts: it is in the
 * workout, and the list inside the workout will show it.
 */
private fun workoutSubtitle(workout: Workout, running: Boolean): String {
    val body = if (workout.isEmpty) {
        "nothing recorded yet"
    } else {
        "${count(workout.exercises.size, "exercise", "exercises")}, " +
            count(workout.setCount, "set", "sets")
    }
    return if (running) "in progress - $body" else body
}

private fun placedSingle(group: LooseGroup, recordOf: Map<Long, RecordHit?>): Placed {
    val times = clockTimes(group.entries.map { it.ts }, group.opDate)
    return Placed(
        minute = times.minOrNull()?.let { parseMinuteOfDay(it) },
        rank = RANK_FACT,
        tiebreak = group.entries.first().id,
        card = DayCard(
            kind = DayCardKind.SINGLE,
            key = "single:${group.key}",
            title = group.name,
            /*
             * "entries", not "sets": what lands here is whatever was recorded on its own,
             * and that includes a weigh-in and a check-in, neither of which is a set. The
             * workout subtitle above says "sets" because a workout is made of them.
             */
            subtitle = "outside a workout - ${count(group.entries.size, "entry", "entries")}",
            timeLabel = timeRange(times),
            // an entry that names no catalog exercise (a weigh-in) has no breakdown to open
            action = if (group.exerciseId != null) DayCardAction.OPEN else DayCardAction.NONE,
            recordLine = recordLine(group.entries.map { it.id }, recordOf),
            exerciseId = group.exerciseId,
        ),
    )
}

// --- entries recorded outside any workout ----------------------------------------------

/** Entries of one exercise, logged with no workout around them. */
private data class LooseGroup(
    val key: String,
    val name: String,
    val exerciseId: Long?,
    val opDate: String,
    val entries: List<ActivityEvent>,
)

/**
 * Loose entries grouped by exercise, in the order the exercises first appeared.
 *
 * Grouping goes by exercise_id and falls back to the normalized name, the same rule
 * [buildSession] uses — so an entry written before the catalog existed, and a weigh-in,
 * which never has an id, still get a card instead of disappearing.
 */
private fun looseGroups(events: List<JournalEvent>, opDate: String): List<LooseGroup> {
    val buckets = LinkedHashMap<String, MutableList<ActivityEvent>>()
    for (entry in setsOutsideWorkouts(events, opDate)) {
        val exercise = entry.form.exerciseLink()
        buckets.getOrPut(exercise?.key ?: "name:${entry.key ?: entry.type}") { mutableListOf() } += entry
    }
    return buckets.map { (key, entries) ->
        LooseGroup(
            key = key,
            name = entries.first().form.activityName(),
            exerciseId = entries.first().form.exerciseLink()?.id,
            opDate = opDate,
            entries = entries,
        )
    }
}

// --- the small pieces -------------------------------------------------------------------

/**
 * "HH:mm" of each timestamp that was WRITTEN on the day it is filed under, in order.
 *
 * The condition is the one domain/Schedule.kt already applies to its own stamps: an entry
 * backfilled on another day carries the time it was TYPED, and printing "recorded at 23:40"
 * on a workout that happened last Tuesday morning would be a plausible-looking lie.
 */
private fun clockTimes(timestamps: List<String>, opDate: String): List<String> =
    timestamps.mapNotNull { ts ->
        if (ts.length >= 16 && ts.startsWith("${opDate}T")) ts.substring(11, 16) else null
    }.sorted()

/** "18:20", "18:20 - 19:35", or "" — the times of a card, as a label. */
private fun timeRange(times: List<String>): String {
    val first = times.firstOrNull() ?: return ""
    val last = times.last()
    return if (first == last) first else "$first - $last"
}

/** "1 set" / "4 sets" — the plural said out loud rather than with a bracketed s. */
private fun count(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

/**
 * The records line of a card, or null.
 *
 * One record is worth spelling out, since the number is the whole news. Several are worth
 * only counting: three record lines on a card push everything else off the screen, and the
 * detail behind the card has them all anyway.
 */
private fun recordLine(eventIds: List<Long>, recordOf: Map<Long, RecordHit?>): String? {
    val hits = eventIds.mapNotNull { recordOf[it] }
    return when (hits.size) {
        0 -> null
        1 -> "Record: ${hits.single().text}"
        else -> "${hits.size} records"
    }
}
