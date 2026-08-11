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
 * domain. For holds this carries the §12-A identity (the protocol), which is why it is
 * never asked for per set: it belongs to the exercise.
 */
data class ExerciseRef(
    val id: Long,
    val name: String,
    val form: ExerciseForm,
    /**
     * Identity of the catalog row (schema version 8), or null for a caller that holds only a
     * local number.
     *
     * Every form built here is stamped with it, which is what makes an entry able to name its
     * exercise outside this phone. Null is tolerated rather than required because the fixtures
     * and a few screens still address exercises by number; such an entry is matched by number
     * and cannot be merged with another device's, which is the honest consequence.
     */
    val uid: String? = null,
    val workSec: Double? = null,
    val restSec: Double? = null,
    /**
     * The rest the user last chose for this exercise, and whether its sets are run by the
     * protocol — the two catalog columns added in schema version 5. Both are nullable and
     * both mean "nothing has been said", which is why they are read through
     * [restHintSec] and [ledByProtocol] in domain/Workout.kt rather than used directly:
     * the answer for a null is derived, not assumed.
     */
    val defaultRestSec: Int? = null,
    val ledByProtocolFlag: Boolean? = null,
    /**
     * Trained ONE LIMB AT A TIME (schema version 13) — see
     * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.oneSided].
     *
     * On the entry card this is what makes the side worth asking for; the side itself is
     * recorded on the set ([HoldSet.side]) and not here.
     */
    val oneSided: Boolean = false,
    /**
     * The RESOLVED schedule of a hold — the whole [WorkoutProgram] the catalog row points at,
     * not just the two numbers of its first block (§18.15).
     *
     * [workSec]/[restSec] above are that first block, and they stay: everything that speaks the
     * plain "work : rest" pair — the entry card, the identity chip, the "Start 7:3" button —
     * reads them and has no use for the rest. What could not be expressed with them is a
     * schedule with a SECOND block, a changed order or a repeat, and that is exactly what used
     * to be lost on the way to a run (see [scheduledRun]). So the schedule now travels whole,
     * and [scheduleKind] is how a caller asks which of the three shapes it has.
     *
     * Null for a hold with no schedule at all, for any other form, and for the callers that
     * build a ref by hand without one (fixtures, and the screens that only need a name and an
     * id) — the same tolerance [uid] has, with the same consequence: such a ref reads as
     * [ScheduleKind.NONE] and offers no conducted run.
     */
    val schedule: WorkoutProgram? = null,
) {
    /**
     * Which of the three shapes this exercise's schedule is — see [ScheduleKind].
     *
     * A ref built with the two numbers and no [schedule] behind them reads as
     * [ScheduleKind.PAIR] rather than as [ScheduleKind.NONE], and that is not a convenience:
     * [workSec]/[restSec] ARE a work:rest pair, which is precisely what that branch means. Such
     * a ref is what the screens which only ever spoke the pair still build (and every fixture
     * that predates schedules), and demoting them to "no schedule at all" would take the
     * conductor away from exercises that have had it since the app had a timer.
     */
    val scheduleKind: ScheduleKind = when {
        schedule != null -> scheduleKindOf(schedule)
        workSec != null && workSec > 0 && restSec != null && restSec > 0 -> ScheduleKind.PAIR
        else -> ScheduleKind.NONE
    }

    /**
     * A work:rest protocol is a pair or nothing at all (the [HoldSet] validator insists),
     * and both halves have to be POSITIVE.
     *
     * The zero check is not belt-and-braces: a catalog row can carry a zero (an exercise
     * created before the entry form rejected one, or a row that arrives from the bot's
     * journal), and the validator rejects a non-positive `work_sec` by throwing. Since
     * `holdSetOf` builds its form inside the Add button's own click handler, that throw
     * would come out as a crash on the one button this app is built around. A zero here is
     * "no protocol was ever set", which is exactly what a null means.
     */
    val protocol: Pair<Double, Double>? =
        if (workSec != null && workSec > 0 && restSec != null && restSec > 0) {
            workSec to restSec
        } else {
            null
        }

    /**
     * Whether a tap can hand this exercise to the conductor at all.
     *
     * Two roads reach a run and they do not need the same things, which is why this is not
     * simply "[protocol] is not null". A [ScheduleKind.PAIR] needs the pair itself, because the
     * run is BUILT out of it. A [ScheduleKind.STRICT] schedule needs nothing but its own work
     * steps — and it is allowed to open with a block that has NO REST, which reads as a null
     * [protocol] and would have quietly demoted the richest schedules in the catalog back to
     * the manual entry form.
     */
    val canBeConducted: Boolean = when (scheduleKind) {
        ScheduleKind.NONE -> false
        ScheduleKind.PAIR -> protocol != null
        ScheduleKind.STRICT -> true
    }

    /** How the journal names this exercise — see [ExerciseLink]. */
    val link: ExerciseLink = ExerciseLink(uid, id)
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
// knows its own identity (id, name and — for holds — the protocol), and forgetting
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
    /** Ramp-up rather than working weight — see [StrengthSet.warmup]. */
    warmup: Boolean = false,
    /** Fell short of the reps this was attempted at — see [StrengthSet.incomplete]. */
    incomplete: Boolean = false,
    /**
     * Which side this was, for an exercise trained one limb at a time ([ExerciseRef.oneSided])
     * — a pistol squat, a one-arm row. See [holdSetOf]'s own [HoldSide] parameter for why this
     * is not validated against the flag here either.
     */
    side: HoldSide? = null,
): StrengthSet = if (ownWeight) {
    StrengthSet(
        exercise = exercise.name, reps = reps, ownWeight = true,
        // zero is "nothing was added", which the payload says by leaving the field out; the
        // sign is KEPT, because a negative one is assistance and not a mistyped positive
        addedKg = addedKg?.takeIf { it != 0.0 }, exerciseId = exercise.id,
        exerciseUid = exercise.uid, opDate = opDate, warmup = warmup, incomplete = incomplete,
        side = side?.code,
    )
} else {
    StrengthSet(
        exercise = exercise.name, reps = reps, weightKg = weightKg?.takeIf { it > 0 },
        exerciseId = exercise.id, exerciseUid = exercise.uid, opDate = opDate,
        warmup = warmup, incomplete = incomplete, side = side?.code,
    )
}

/**
 * A hold set. §12-A: the protocol comes FROM THE EXERCISE and is written into the payload
 * as a snapshot — the entry card never asks for it.
 */
fun holdSetOf(
    exercise: ExerciseRef,
    opDate: String,
    addedKg: Double? = null,
    reps: Int? = null,
    holdSec: Double? = null,
    /**
     * The pause AFTER this set. The entry card leaves it out on purpose (it cannot know a
     * pause that has not happened yet — see [secondsBetween]); a set written from a
     * finished interval run can, because the program states it.
     */
    restAfterSec: Double? = null,
    /** Ramp-up rather than a working hang — see [StrengthSet.warmup]. */
    warmup: Boolean = false,
    /** Fell short of the protocol this was attempted at — see [StrengthSet.incomplete]. */
    incomplete: Boolean = false,
    /**
     * Which hand this was, for an exercise trained one at a time ([ExerciseRef.oneSided]).
     *
     * NOT validated against that flag, deliberately. This builder runs inside the Add
     * button's own click handler, and a `require` here would come out as a crash on the one
     * button the app is built around — the same reasoning the protocol is sanitised for
     * rather than rejected. A one-sided exercise logged without a side is a
     * defect the READERS report, out loud, where nobody is mid-set (see [holdRecord]).
     */
    side: HoldSide? = null,
): HoldSet = HoldSet(
    activity = exercise.name,
    // zero is "not filled in", not a set of zero reps: the validator would reject it and
    // take the screen down mid-workout
    reps = reps?.takeIf { it > 0 },
    holdSec = holdSec?.takeIf { it > 0 },
    workSec = exercise.protocol?.first,
    restSec = exercise.protocol?.second,
    // the sign survives: a hang off a band is recorded as a negative added weight, and
    // stripping it would silently turn "helped by 15 kg" into an unweighted hang
    addedKg = addedKg?.takeIf { it != 0.0 },
    ownWeight = true,
    warmup = warmup,
    incomplete = incomplete,
    side = side?.code,
    exerciseId = exercise.id,
    exerciseUid = exercise.uid,
    restAfterSec = restAfterSec?.takeIf { it > 0 },
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
    exerciseUid = exercise.uid,
    opDate = opDate,
)

fun durationOf(exercise: ExerciseRef, opDate: String, durationSec: Int): Duration =
    Duration(
        activity = exercise.name, durationSec = durationSec, exerciseId = exercise.id,
        exerciseUid = exercise.uid, opDate = opDate,
    )

fun tickOf(exercise: ExerciseRef, opDate: String): Tick =
    Tick(
        activity = exercise.name, exerciseId = exercise.id, exerciseUid = exercise.uid,
        opDate = opDate,
    )

/** Body weight carries neither a name nor an exercise_id — the exercise is only the route in. */
fun bodyweightOf(opDate: String, weightKg: Double): Bodyweight =
    Bodyweight(weightKg = weightKg, opDate = opDate)

// --- prefilling the entry card -------------------------------------------------------

/** The last non-cancelled strength set of an exercise, by its identity and not by name. */
fun lastStrengthSet(events: List<JournalEvent>, exercise: ExerciseLink): StrengthSet? =
    strengthSetsOfExercise(events, exercise).lastOrNull()

fun lastDuration(events: List<JournalEvent>, exercise: ExerciseLink): Duration? =
    formsOfExercise<Duration>(events, exercise, TYPE_DURATION).lastOrNull()

/** The last weigh-in. Body weight has no exercise_id, so the whole series is used. */
fun lastBodyweight(events: List<JournalEvent>): Bodyweight? = bodyweightSeries(events).lastOrNull()

/**
 * What the scales last said ON OR BEFORE [opDate], or null if they had said nothing yet.
 *
 * BY DAY AND NOT BY WRITE ORDER, which is the difference that matters for the one case this
 * exists for: typing up training from a fortnight ago. [lastBodyweight] answers "the most
 * recent weigh-in", and stamping that onto a backdated set would record today's weight as
 * the weight of a day two weeks gone. The sort is stable, so several weigh-ins on one day
 * resolve to the last one written that day.
 */
fun bodyweightAt(events: List<JournalEvent>, opDate: String): Double? =
    bodyweightSeries(events)
        .filter { it.opDate <= opDate }
        .sortedBy { it.opDate }
        .lastOrNull()
        ?.weightKg

/**
 * The same form with its body-weight snapshot filled in, or unchanged when there is nothing
 * to fill in — see [StrengthSet.bodyweightKg].
 *
 * ── Why this happens at the moment of recording and not on the entry card ───────
 * The same reasoning [xyz.oleolegka.gachimuchi.data.ActivityRepository.record] gives for
 * attaching the workout there: every screen that logs anything goes through one method, and a
 * screen that forgot to stamp the weight would write a set that silently has no volume. It is
 * also the only moment at which "the last weigh-in" is a defined quantity.
 *
 * A form that ALREADY carries a snapshot is left alone — a caller that knows better (an
 * import, a set reconstructed from a finished interval run) is not overruled.
 */
/**
 * Whether [withBodyweightSnapshot] would have anything to do.
 *
 * Exists so that a caller can skip FETCHING the journal for a write that will not use it —
 * recording a set already folds the whole journal once to find the open workout, and a second
 * read for a barbell set, which can never carry a snapshot, is work done for nothing on the
 * one path the user is standing in a gym waiting for.
 */
val ActivityForm.wantsBodyweightSnapshot: Boolean
    get() = when (this) {
        is LoadedSet -> ownWeight && bodyweightKg == null
        else -> false
    }

fun ActivityForm.withBodyweightSnapshot(weightAt: (String) -> Double?): ActivityForm =
    if (this !is LoadedSet || !ownWeight || bodyweightKg != null) {
        this
    } else {
        weightAt(opDate)?.let { snapshot ->
            when (this) {
                is StrengthSet -> copy(bodyweightKg = snapshot)
                is HoldSet -> copy(bodyweightKg = snapshot)
            }
        } ?: this
    }

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
    /** Write time of the event — what the Today feed prints a session's time range from. */
    val ts: String,
    val form: ActivityForm,
    /** Set as it was written: the record this set broke, or null. */
    val record: RecordHit?,
    /**
     * The pause before this set, in seconds. Taken from the previous set's explicit
     * `rest_after_sec` when it has one, otherwise derived from the gap between the two
     * write times. Null for the first set of an exercise and for implausible gaps.
     *
     * STILL BY WRITE TIME, deliberately unchanged by [happenedAt] below — a floor measures
     * wall-clock time between two ACTUAL writes, and a set typed up long after the fact was
     * never rested for. See [buildSession]'s own note on this.
     */
    val restBeforeSec: Double?,
    /** See [xyz.oleolegka.gachimuchi.domain.happenedAt] — what [buildSession] sorts a group by. */
    val happenedAt: String = ts,
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
 * Groups follow the order in which the exercises FIRST appeared that day, by write time — that
 * decision is unaffected by any of this, see below. WITHIN a group, the sets are sorted by
 * [ActivityEvent.happenedAt] rather than left in journal order: the screen is a tape of the
 * workout as it was actually done, and journal order stopped being that the moment a set could
 * be corrected without moving (domain/Amendments.kt's header). Cancelled sets are gone (the
 * reducers drop them), but their events remain in the journal.
 *
 * ── What is deliberately STILL by write time ─────────────────────────────────────
 * The rest-before-a-set calculation reads the journal in ITS OWN order, unchanged —
 * [restBeforeSec]'s derived half is a gap between two ACTUAL writes (a set typed up after the
 * fact was never rested for, whatever [happenedAt] says it happened at). This is the one place
 * write order stays the right answer: the floor measures wall-clock time between two writes,
 * and a correction changes what a row says, never when it was typed.
 *
 * ── What is NOT, any more: the record check ───────────────────────────────────────
 * [recordAt] used to ask "was this a record against everything EARLIER IN THE JOURNAL" — a
 * question that quietly meant "everything with a smaller row id" for as long as an edit could
 * not move. It can now: a corrected set is a whole new row appended at the END of the journal
 * (domain/Amendments.kt's header) that may have happened FIRST. Judging it against rows that
 * come after it in write order but before it in training order got the record wrong in both
 * directions — a correction to an early set could steal a later set's record, or hand the
 * correction one of its own it never broke. So [recordAt] now asks "was this a record against
 * everything that happened EARLIER" — by [happenedAt], the same clock the display order and
 * every other reader in this change reads through.
 *
 * Grouping goes by exercise_id, so entries spelled differently (the bot writes whatever
 * sentence it was given) still land in one block. Entries with no id (written before the
 * catalog existed, and body weight, which never has one) fall back to the normalized name
 * so they still show up instead of vanishing.
 */
fun buildSession(events: List<JournalEvent>, opDate: String): Session {
    val all = readActivities(events)
    val buckets = LinkedHashMap<String, MutableList<SessionSet>>()
    val labels = HashMap<String, Pair<Long?, String>>()
    val lastTs = HashMap<String, String>()

    for (ev in all) {
        if (ev.opDate != opDate) continue
        val exercise = ev.form.exerciseLink()
        val groupKey = exercise?.key ?: "name:${ev.key ?: ev.type}"
        val bucket = buckets.getOrPut(groupKey) { mutableListOf() }
        labels.getOrPut(groupKey) { exercise?.id to ev.form.activityName() }

        val previous = bucket.lastOrNull()
        val rest = previous?.let { explicitRestAfter(it.form) }
            ?: lastTs[groupKey]?.let { secondsBetween(it, ev.ts) }?.takeIf { it > 0 && it <= MAX_REST_SEC }

        bucket += SessionSet(
            eventId = ev.id,
            ts = ev.ts,
            form = ev.form,
            record = recordAt(all, ev),
            restBeforeSec = rest,
            happenedAt = ev.happenedAt,
        )
        lastTs[groupKey] = ev.ts
    }

    val groups = buckets.map { (key, sets) ->
        val (id, name) = labels.getValue(key)
        // happenedAt first, then eventId (journal order) as the tie-break for two sets that
        // happened in the same wall-clock second — the same rule domain/Amendments.kt's
        // journalView already settles a tie by, so two ties resolve the same way everywhere
        SessionGroup(
            groupKey = key, exerciseId = id, name = name,
            sets = sets.sortedWith(compareBy({ it.happenedAt }, { it.eventId })),
        )
    }
    return Session(opDate, groups)
}

/**
 * The record [target] broke, compared against everything that happened EARLIER — the same
 * comparison the bot performs at write time, asked of the training log AS IT ACTUALLY
 * UNFOLDED rather than as it happens to be laid out on disk. Entries with no exercise_id are
 * skipped: there is nothing to compare them against.
 *
 * "Earlier" is decided by [happenedAt] and not by position in [all] — see this function's own
 * caller, [buildSession], for why a row's journal position stopped being a safe stand-in for
 * when it happened. [isBefore] is the same happenedAt-then-id rule every other "the order
 * things really happened in" reader in this file and domain/Workout.kt settles a tie by, so a
 * set that lands in the same wall-clock second as another is judged the same way everywhere.
 */
private fun recordAt(all: List<ActivityEvent>, target: ActivityEvent): RecordHit? {
    val exercise = target.form.exerciseLink() ?: return null
    val prior = all.filter { isBefore(it, target) }
    fun <T : ActivityForm> priorOf(pick: (ActivityForm) -> T?): List<T> =
        prior.mapNotNull { pick(it.form)?.takeIf { _ -> it.form.exerciseLink()?.matches(exercise) == true } }

    return when (val form = target.form) {
        // outward one branch — LoadedSet is the only pair that has a record model at all; which
        // record function applies still depends on the concrete form, so that stays nested
        is LoadedSet -> when (form) {
            is StrengthSet ->
                evaluateStrengthRecord(
                    priorOf { it as? StrengthSet }, form.weightKg, form.reps,
                    warmup = form.warmup, side = form.sideOf, incomplete = form.incomplete,
                )

            is HoldSet -> evaluateHoldRecord(priorOf { it as? HoldSet }, form)
        }

        else -> null
    }
}

/**
 * Whether [a] happened strictly before [b] — [happenedAt] first, [ActivityEvent.id] (journal
 * order) as the tie-break for two entries the clock cannot tell apart, the same rule
 * [buildSession]'s own group sort and domain/Workout.kt already settle a same-second tie by.
 */
private fun isBefore(a: ActivityEvent, b: ActivityEvent): Boolean =
    a.happenedAt < b.happenedAt || (a.happenedAt == b.happenedAt && a.id < b.id)

/**
 * The rest a form states outright (the bot writes it; the app does not).
 *
 * Not private: the rest timer derives its offered duration the same way the session feed
 * derives the "rest 2:30" line (domain/TimerSettings.kt). Two implementations of "how long
 * was the pause" would drift apart, and the screen and the timer would then disagree about
 * the same two events.
 */
internal fun explicitRestAfter(form: ActivityForm): Double? = when (form) {
    is LoadedSet -> form.restAfterSec
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

/** Usage of every exercise in the journal (entries naming none are skipped). */
fun exerciseUsage(events: List<JournalEvent>): Map<Long, ExerciseUsage> {
    val out = HashMap<Long, ExerciseUsage>()
    for (ev in readActivities(events)) {
        val id = ev.form.exerciseLink()?.id ?: continue
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
 * Which exercise the entry card should open on when the logging screen is entered without
 * one already chosen.
 *
 * The point is that "I just did a set, write it down" must not begin with a hunt. Opening
 * on "No exercise chosen" costs two taps and a decision before a single number can be
 * typed, and it is the state a fresh install lands in — the one place where the app can
 * least afford to look like it is asking a question it could answer itself.
 *
 * In order of preference:
 *  1. the exercise today's session left off on — carrying on with the same one is the
 *     overwhelmingly common case between sets;
 *  2. otherwise the most recently used exercise still in the catalog, which is exactly
 *     what [pickerOrder] would have put at the top of the picker anyway;
 *  3. otherwise the only exercise in the catalog, if there is exactly one — a new install
 *     with one exercise and no history should still open ready to type.
 *
 * Null when the catalog is empty, and when it holds several exercises none of which has
 * ever been used: picking between untouched strangers would be a guess, and a wrong guess
 * that silently prefills a weight is worse than an honest question. Whatever comes back is
 * a starting point, never a commitment — the picker is one tap away on the same card.
 *
 * Exercises missing from [catalogIds] are ignored throughout: the journal outlives the
 * catalog (entries survive an exercise being deleted), and the entry card cannot open on
 * an exercise whose form it can no longer look up.
 */
fun exerciseToLogNext(
    events: List<JournalEvent>,
    opDate: String,
    catalogIds: Collection<Long>,
): Long? {
    val known = catalogIds.toSet()
    if (known.isEmpty()) return null

    val leftOffOn = buildSession(events, opDate).groups
        .mapNotNull { it.exerciseId }
        .lastOrNull { it in known }
    if (leftOffOn != null) return leftOffOn

    val usage = exerciseUsage(events).filterKeys { it in known }
    if (usage.isNotEmpty()) return usage.keys.minWithOrNull(pickerOrder(usage))

    return known.singleOrNull()
}

/**
 * Whether an exercise matches what was typed into the search field. A substring of the
 * NAME, normalized the same way the journal keys are, so "bench" finds "Bench press". An
 * empty query matches everything.
 *
 * ── It used to match learned synonyms too, and no longer can ────────────────────
 * The app kept a table of words the user had taught it, filled in by watching which exercise
 * was tapped while a word sat in this search box. That mechanism came from the telegram bot,
 * where an exercise was named by typing a sentence and a synonym was the only way to be
 * understood. Here the exercise is picked from a list ordered by recency, so a synonym could
 * only ever save keystrokes in a search — and it could not be set on purpose, only guessed
 * at from a tap.
 *
 * The consequence for the picker is deliberate and worth naming: a word that matches no name
 * now narrows the list to NOTHING, where it used to fall back to the whole catalog. That
 * fallback existed to keep the tap-that-teaches reachable; with nothing left to teach, a
 * search that quietly ignores what was typed would just be a search box that lies. The
 * screen says nothing matched and offers to clear the search — see ui/screens/ExercisePicker.
 */
fun matchesExerciseQuery(name: String, query: String): Boolean {
    val q = normPhrase(query) ?: return true
    return normPhrase(name)?.contains(q) == true
}
