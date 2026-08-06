package xyz.oleolegka.gachimuchi.domain

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * The logging session: everything the "log a workout" screen needs, as pure functions
 * over the journal.
 *
 * A SESSION IS NOT A STORED ENTITY. There is no "workout" row and no "start"/"finish"
 * event: a session is simply everything recorded on a given [opDate], grouped by
 * exercise. That is deliberate — it keeps the append-only journal as the single truth,
 * it survives the app being killed mid-workout (nothing to resume, the entries are
 * already there), and "continue today's workout" costs nothing to implement. The price
 * is that a session cannot span midnight; for a personal gym diary that is acceptable.
 *
 * Everything here is a reducer, so the whole logging flow — prefilling from the last
 * set, repeating a set, cancelling one, catching a record, deriving rest — is testable
 * on the JVM without Compose and without a device.
 */

/**
 * The catalog attributes the entry card needs, without dragging Room types into the
 * domain. For holds this carries the §12-A identity (edge and protocol), which is why
 * those two are never asked for per set: they belong to the exercise.
 */
data class ExerciseRef(
    val id: Long,
    val name: String,
    val form: ExerciseForm,
    val edgeMm: Double? = null,
    val workSec: Double? = null,
    val restSec: Double? = null,
) {
    /** A work:rest protocol is a pair or nothing at all (the [HoldSet] validator insists). */
    val protocol: Pair<Double, Double>? =
        if (workSec != null && restSec != null) workSec to restSec else null
}

/** The activity name carried by a form; body weight has none, so its role is used. */
fun ActivityForm.activityName(): String = when (this) {
    is StrengthSet -> exercise
    is HoldSet -> activity
    is Duration -> activity
    is Tick -> activity
    is Cardio -> activity
    is Bodyweight -> "Body weight"
}

// --- building a form out of what the entry card collected -----------------------------
//
// The builders exist so that a screen never constructs a payload by hand: an exercise
// knows its own identity (id, name and — for holds — edge and protocol), and forgetting
// to pass one of those would silently split the history of one exercise in two.

/**
 * A strength set. Own body weight and an absolute weight are mutually exclusive
 * ([StrengthSet] enforces it): with [ownWeight] the number entered means ADDED weight,
 * and an empty or zero one means a clean body-weight set.
 */
fun strengthSetOf(
    exercise: ExerciseRef,
    opDate: String,
    reps: Int,
    weightKg: Double? = null,
    ownWeight: Boolean = false,
    addedKg: Double? = null,
): StrengthSet = if (ownWeight) {
    StrengthSet(
        exercise = exercise.name, reps = reps, ownWeight = true,
        addedKg = addedKg?.takeIf { it > 0 }, exerciseId = exercise.id, opDate = opDate,
    )
} else {
    StrengthSet(
        exercise = exercise.name, reps = reps, weightKg = weightKg?.takeIf { it > 0 },
        exerciseId = exercise.id, opDate = opDate,
    )
}

/**
 * A hold set. §12-A: edge and protocol come FROM THE EXERCISE and are written into the
 * payload as a snapshot — the entry card never asks for them.
 */
fun holdSetOf(
    exercise: ExerciseRef,
    opDate: String,
    addedKg: Double? = null,
    reps: Int? = null,
    holdSec: Double? = null,
): HoldSet = HoldSet(
    activity = exercise.name,
    // zero is "not filled in", not a set of zero reps: the validator would reject it and
    // take the screen down mid-workout
    reps = reps?.takeIf { it > 0 },
    holdSec = holdSec?.takeIf { it > 0 },
    workSec = exercise.protocol?.first,
    restSec = exercise.protocol?.second,
    edgeMm = exercise.edgeMm,
    addedKg = addedKg?.takeIf { it > 0 },
    ownWeight = true,
    exerciseId = exercise.id,
    opDate = opDate,
)

fun cardioOf(
    exercise: ExerciseRef,
    opDate: String,
    distanceM: Double? = null,
    durationSec: Int? = null,
    paceSecPerKm: Double? = null,
): Cardio = Cardio(
    activity = exercise.name,
    distanceM = distanceM?.takeIf { it > 0 },
    durationSec = durationSec?.takeIf { it > 0 },
    paceSecPerKm = paceSecPerKm?.takeIf { it > 0 },
    exerciseId = exercise.id,
    opDate = opDate,
)

fun durationOf(exercise: ExerciseRef, opDate: String, durationSec: Int): Duration =
    Duration(activity = exercise.name, durationSec = durationSec, exerciseId = exercise.id, opDate = opDate)

fun tickOf(exercise: ExerciseRef, opDate: String): Tick =
    Tick(activity = exercise.name, exerciseId = exercise.id, opDate = opDate)

/** Body weight carries neither a name nor an exercise_id — the exercise is only the route in. */
fun bodyweightOf(opDate: String, weightKg: Double): Bodyweight =
    Bodyweight(weightKg = weightKg, opDate = opDate)

// --- prefilling the entry card -------------------------------------------------------

/** The last non-cancelled strength set of an exercise (across aliases, by exercise_id). */
fun lastStrengthSet(events: List<JournalEvent>, exerciseId: Long): StrengthSet? =
    strengthSetsByExerciseId(events, exerciseId).lastOrNull()

fun lastDuration(events: List<JournalEvent>, exerciseId: Long): Duration? =
    formsByExerciseId<Duration>(events, exerciseId, TYPE_DURATION).lastOrNull()

/** The last weigh-in. Body weight has no exercise_id, so the whole series is used. */
fun lastBodyweight(events: List<JournalEvent>): Bodyweight? = bodyweightSeries(events).lastOrNull()

// --- the session feed ----------------------------------------------------------------

/**
 * How long a rest may last before it stops being a rest. A 40-minute gap is a break in
 * the workout (a phone call, a queue for the rack), not the pause the plan is about, and
 * writing it down would poison the averages. Gaps above this are reported as null.
 */
const val MAX_REST_SEC: Double = 20 * 60.0

/** One recorded set in the feed, with everything the row needs already computed. */
data class SessionSet(
    val eventId: Long,
    val form: ActivityForm,
    /** Set as it was written: the record this set broke, or null. */
    val record: RecordHit?,
    /**
     * The pause before this set, in seconds. Taken from the previous set's explicit
     * `rest_after_sec` when it has one, otherwise derived from the gap between the two
     * write times. Null for the first set of an exercise and for implausible gaps.
     */
    val restBeforeSec: Double?,
)

/** The sets of one exercise within the session, in the order they were recorded. */
data class SessionGroup(
    val groupKey: String,
    val exerciseId: Long?,
    val name: String,
    val sets: List<SessionSet>,
)

/** The whole session of one day. */
data class Session(
    val opDate: String,
    val groups: List<SessionGroup>,
) {
    val setCount: Int = groups.sumOf { it.sets.size }

    /** Target of "undo the last set": the most recent live entry of the day, if any. */
    val lastEventId: Long? = groups.flatMap { it.sets }.maxByOrNull { it.eventId }?.eventId

    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Builds the session of [opDate] out of the journal.
 *
 * Groups follow the order in which the exercises FIRST appeared that day, and the sets
 * inside a group follow the order they were recorded in — the screen is a live tape of
 * the workout, not a sorted report. Cancelled sets are gone (the reducers drop them),
 * but their events remain in the journal.
 *
 * Grouping goes by exercise_id, which merges aliases into one block. Entries with no id
 * (written before the catalog existed, and body weight, which never has one) fall back
 * to the normalized name so they still show up instead of vanishing.
 */
fun buildSession(events: List<JournalEvent>, opDate: String): Session {
    val all = readActivities(events)
    val buckets = LinkedHashMap<String, MutableList<SessionSet>>()
    val labels = HashMap<String, Pair<Long?, String>>()
    val lastTs = HashMap<String, String>()

    for ((index, ev) in all.withIndex()) {
        if (ev.opDate != opDate) continue
        val exerciseId = ev.form.exerciseId
        val groupKey = exerciseId?.let { "id:$it" } ?: "name:${ev.key ?: ev.type}"
        val bucket = buckets.getOrPut(groupKey) { mutableListOf() }
        labels.getOrPut(groupKey) { exerciseId to ev.form.activityName() }

        val previous = bucket.lastOrNull()
        val rest = previous?.let { explicitRestAfter(it.form) }
            ?: lastTs[groupKey]?.let { secondsBetween(it, ev.ts) }?.takeIf { it > 0 && it <= MAX_REST_SEC }

        bucket += SessionSet(
            eventId = ev.id,
            form = ev.form,
            record = recordAt(all, index),
            restBeforeSec = rest,
        )
        lastTs[groupKey] = ev.ts
    }

    val groups = buckets.map { (key, sets) ->
        val (id, name) = labels.getValue(key)
        SessionGroup(groupKey = key, exerciseId = id, name = name, sets = sets)
    }
    return Session(opDate, groups)
}

/**
 * The record broken by the set at [index], compared against everything EARLIER in the
 * journal — the same comparison the bot performs at write time. Entries with no
 * exercise_id are skipped: there is nothing to compare them against.
 */
private fun recordAt(all: List<ActivityEvent>, index: Int): RecordHit? {
    val prior = all.subList(0, index)
    return when (val form = all[index].form) {
        is StrengthSet -> form.exerciseId?.let { id ->
            evaluateStrengthRecord(
                prior.mapNotNull { (it.form as? StrengthSet)?.takeIf { s -> s.exerciseId == id } },
                form.weightKg, form.reps,
            )
        }

        is HoldSet -> form.exerciseId?.let { id ->
            evaluateHoldRecord(
                prior.mapNotNull { (it.form as? HoldSet)?.takeIf { h -> h.exerciseId == id } },
                form,
            )
        }

        else -> null
    }
}

/**
 * The rest a form states outright (the bot and the demo seed write it; the app does not).
 *
 * Not private: the rest timer derives its offered duration the same way the session feed
 * derives the "rest 2:30" line (domain/TimerSettings.kt). Two implementations of "how long
 * was the pause" would drift apart, and the screen and the timer would then disagree about
 * the same two events.
 */
internal fun explicitRestAfter(form: ActivityForm): Double? = when (form) {
    is StrengthSet -> form.restAfterSec
    is HoldSet -> form.restAfterSec
    else -> null
}

/**
 * Seconds between two journal timestamps, or null if either fails to parse.
 *
 * WHY REST IS DERIVED AND NOT WRITTEN (no whitewashing). `rest_after_sec` is the pause
 * AFTER a set, so its value only becomes known when the NEXT set is logged — by which
 * time the previous event is already in an append-only journal and cannot be amended.
 * Writing the gap onto the new set instead would mean storing "rest after set N" in the
 * payload of set N+1, which is a different fact and would be read wrongly by the bot,
 * which shares this payload schema. So the app derives the pause from the `ts` of the
 * two events, which it already stores honestly, and defers to the explicit field
 * whenever a record carries one.
 */
internal fun secondsBetween(fromTs: String, toTs: String): Double? = runCatching {
    ChronoUnit.SECONDS.between(LocalDateTime.parse(fromTs), LocalDateTime.parse(toTs)).toDouble()
}.getOrNull()

// --- the exercise picker -------------------------------------------------------------

/** How often and how recently an exercise was used — the ordering of the picker. */
data class ExerciseUsage(val lastDate: String, val count: Int)

/** Usage of every exercise in the journal, by exercise_id (entries with no id are skipped). */
fun exerciseUsage(events: List<JournalEvent>): Map<Long, ExerciseUsage> {
    val out = HashMap<Long, ExerciseUsage>()
    for (ev in readActivities(events)) {
        val id = ev.form.exerciseId ?: continue
        val current = out[id]
        out[id] = ExerciseUsage(
            lastDate = if (current == null || ev.opDate > current.lastDate) ev.opDate else current.lastDate,
            count = (current?.count ?: 0) + 1,
        )
    }
    return out
}

/**
 * Picker order: MOST RECENTLY USED first, then the most used, then by id for stability.
 * Recency wins over frequency on purpose — during a workout you reach for what you have
 * been doing lately, while an exercise dropped six months ago should sink no matter how
 * many sets it once collected. Exercises never used yet go last.
 */
fun pickerOrder(usage: Map<Long, ExerciseUsage>): Comparator<Long> = Comparator { a, b ->
    val ua = usage[a]
    val ub = usage[b]
    when {
        ua == null && ub == null -> a.compareTo(b)
        ua == null -> 1
        ub == null -> -1
        ua.lastDate != ub.lastDate -> ub.lastDate.compareTo(ua.lastDate)
        ua.count != ub.count -> ub.count.compareTo(ua.count)
        else -> a.compareTo(b)
    }
}

/**
 * Whether an exercise matches what was typed into the search field. Both the name and
 * the learned aliases are matched, normalized the same way the journal keys are, so
 * "bench" finds "Bench press" through either route. An empty query matches everything.
 */
fun matchesExerciseQuery(name: String, aliases: List<String>, query: String): Boolean {
    val q = normPhrase(query) ?: return true
    if (normPhrase(name)?.contains(q) == true) return true
    return aliases.any { normPhrase(it)?.contains(q) == true }
}
