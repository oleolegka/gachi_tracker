package xyz.oleolegka.gachimuchi.domain

/**
 * The workout as an explicit thing: a point in the journal you can add exercises to before
 * you have done any of them.
 *
 * ── Why this exists next to domain/Session.kt rather than instead of it ─────────
 * A [Session] is "everything recorded on this date". That definition needs no start button
 * and cannot be got wrong, which is exactly why it is still the floor the app stands on. It
 * also cannot express the three things this file is for: an exercise that is PLANNED for the
 * next hour and has no sets yet, a rest chosen PER EXERCISE rather than per app, and two
 * separate workouts on one day. So the workout is layered on top — the journal keeps working
 * with no workout at all, and a workout is extra structure over the same rows.
 *
 * ── Nothing here is stored as state ─────────────────────────────────────────────
 * Same rule as the rest of the domain: pure functions over a list of events, no Android, no
 * Room, all testable on the JVM. A workout is not a row that has to be kept in step with the
 * sets recorded into it — it is folded out of them on demand, so it cannot disagree with them.
 *
 * ── What a workout does NOT have ────────────────────────────────────────────────
 * A finish event. See [TYPE_WORKOUT_STARTED]; the price of that decision is paid here, in
 * [openWorkout], which has to work out which workout is in progress without ever being told
 * that one ended.
 */

/** One exercise inside a workout, with the rest chosen for it and the sets it collected. */
data class WorkoutExercise(
    val exerciseId: Long,
    /**
     * Seconds of rest chosen when the exercise was added to this workout, or null when it
     * was never added explicitly and is only present because a set named it.
     *
     * Null is NOT "use zero" — it is "nobody said", and the caller resolves it through
     * [restHintSec], which knows about the catalog column and the journal.
     */
    val restSec: Int?,
    /** In the order recorded. Cancelled sets are already gone (the reducers drop them). */
    val sets: List<ActivityEvent>,
) {
    val isEmpty: Boolean get() = sets.isEmpty()
}

/**
 * A whole workout, folded out of the journal.
 *
 * [opDate] is the day the TRAINING belongs to and comes from the start event's payload, not
 * from anybody's timestamp — a workout typed in a fortnight late is dated to the day it
 * happened. Every set inside it is understood to belong to that same day; see [buildWorkout]
 * for what happens when a set disagrees.
 */
data class Workout(
    val id: Long,
    /**
     * Identity of the start event, which IS the identity of the workout — see
     * [xyz.oleolegka.gachimuchi.data.db.EventEntity.uid]. This is what rows recorded during
     * the workout point at, and what an export refers to; [id] is the local row number and
     * means nothing off this phone.
     */
    val uid: String,
    /** Honest write time of the start event, which is not the same as [opDate]. */
    val ts: String,
    val opDate: String,
    /** The planned session it was started from, when it was started from one. */
    val slotId: Long?,
    /** Exercises IN THE ORDER THEY WERE ADDED, including ones with no sets yet. */
    val exercises: List<WorkoutExercise>,
    /**
     * Entries recorded during the workout that name no exercise — in practice a weigh-in,
     * which by design carries no exercise_id (see [Bodyweight]).
     *
     * They are kept rather than dropped because dropping them is how a record silently stops
     * existing: the row would belong to a workout, and the workout would not show it.
     */
    val entriesWithoutExercise: List<ActivityEvent>,
) {
    val setCount: Int = exercises.sumOf { it.sets.size } + entriesWithoutExercise.size

    /** Nothing has been added and nothing recorded — the state right after "start". */
    val isEmpty: Boolean get() = exercises.isEmpty() && entriesWithoutExercise.isEmpty()

    /**
     * Whether this workout is being typed up after the fact.
     *
     * A backdated workout is SILENT: no rest countdown, no interval run, no alarm. A pause
     * that ended two weeks ago is not something to wait out. The timers are implemented
     * elsewhere (timer/) — this is the flag they are meant to read.
     */
    fun isBackdated(today: String): Boolean = opDate < today
}

/**
 * What a journal row says about the workout it was recorded during, or null for a row
 * recorded outside any.
 *
 * ── The order the four sources are consulted in ─────────────────────────────────
 * The `workout_uid` COLUMN first, because it is the identity that survives leaving this
 * phone, then the payload's own uid field, then the two numeric links they replaced. A
 * [WorkoutRef] carrying a uid never falls back to a number — see [WorkoutRef.matches] for why
 * that matters — so the ordering here decides which link a row is judged by, once and for all
 * readers, instead of each reader picking for itself.
 *
 * The payload is consulted at all for [TYPE_WORKOUT_EXERCISE_ADDED] specifically: that event
 * states its workout in its own payload as well as in the column, so that a row arriving from
 * another journal (which has this app's columns nowhere) still lands in the right workout.
 */
fun JournalEvent.workoutRef(): WorkoutRef? {
    val added = workoutExerciseAddedOrNull()
    val uid = workoutUid ?: added?.workoutUid
    val id = workoutId ?: added?.workoutId
    return if (uid == null && id == null) null else WorkoutRef(uid, id)
}

/** Payload of an "exercise added" row, or null for any other row and for an unreadable one. */
fun JournalEvent.workoutExerciseAddedOrNull(): WorkoutExerciseAdded? =
    if (type != TYPE_WORKOUT_EXERCISE_ADDED) {
        null
    } else {
        runCatching { payloadJson.decodeFromString<WorkoutExerciseAdded>(payload) }.getOrNull()
    }

/**
 * Start events, newest last, paired with what their payload says.
 *
 * A start event whose payload will not parse is NOT skipped — the same reasoning as
 * [formFromEventOrNull], only sharper: skipping it would orphan every set recorded into that
 * workout, and those sets are the training. It degrades to "dated by its write time, started
 * from no slot", which is what an older or hand-edited row would have meant anyway.
 */
private fun workoutStarts(events: List<JournalEvent>): List<Pair<JournalEvent, WorkoutStarted?>> =
    events.filter { it.type == TYPE_WORKOUT_STARTED }
        .map { it to runCatching { payloadJson.decodeFromString<WorkoutStarted>(it.payload) }.getOrNull() }

/** The day a row was WRITTEN on, which is not the day the training it records belongs to. */
private fun JournalEvent.writeDay(): String = ts.substringBefore('T')

/**
 * The workout in progress, or null when there is none.
 *
 * DEFINED AS: the last workout STARTED TODAY, where "today" is measured against the event's
 * write time [JournalEvent.ts] and not against its op_date. That distinction is the whole
 * trick that lets old training be typed in — a workout dated to last month is still the one
 * you are working on right now, for as long as the session you are typing it in lasts.
 *
 * Two consequences, stated rather than hidden, since there is no finish event to lean on:
 *
 *  - A workout stops being open at MIDNIGHT. A session that runs past it leaves the last
 *    sets unattached (they still record perfectly well — that is what nullable
 *    `workout_id` buys) or attached to a workout started after midnight.
 *  - Sets recorded LATER THE SAME DAY without pressing start again are filed under the
 *    morning's workout. The alternative — a timeout after which a workout is assumed over —
 *    would guess, and guessing wrongly here splits one workout into two in the history.
 *
 * Both are the cost of not asking a person mid-gym to press "finish". The button that would
 * fix them is the one nobody presses reliably.
 */
fun openWorkout(events: List<JournalEvent>, today: String): Workout? =
    openWorkoutRow(events, today)?.let { buildWorkout(events, it.id) }

/**
 * The start event of [openWorkout] — what the repository stamps onto the rows it appends.
 *
 * The whole row rather than its id, because a row is stamped with the workout's UID now and
 * the number only alongside it; handing back one of the two would put the choice of which
 * link to write at the call site.
 */
fun openWorkoutRow(events: List<JournalEvent>, today: String): JournalEvent? =
    workoutStarts(events).lastOrNull { (row, _) -> row.writeDay() == today }?.first

/**
 * Folds one workout out of the journal, or null when [workoutId] names no start event here.
 *
 * ── The order of the exercises ──────────────────────────────────────────────────
 * The order they were ADDED IN, which is the order the workout is meant to be done in and
 * the order the screen has to show. An exercise enters the list on its first appearance,
 * whether that is an explicit "added" event or simply the first set recorded under it — a
 * set logged for an exercise nobody added is real training and cannot be left out of the
 * workout it happened in.
 *
 * Adding an exercise that is already in the list does not reorder it; it only updates the
 * rest, last one winning. That is how changing your mind about the pause is expressed in an
 * append-only journal — there is nothing to edit, so you say it again.
 *
 * ── The date ────────────────────────────────────────────────────────────────────
 * A set inside a workout is understood to belong to the WORKOUT's op_date, so a backdated
 * workout dates its own sets. The seam, stated plainly: the set's payload carries an op_date
 * of its own, and if whatever wrote it used a different day, the two disagree and this
 * function believes the workout. Nothing is dropped either way — the set is in the workout —
 * but the day-based views (domain/Session.kt, the calendar) read the payload and would file
 * it elsewhere. The writer is responsible for building sets with the open workout's date.
 */
fun buildWorkout(events: List<JournalEvent>, workoutId: Long): Workout? {
    val (startRow, started) = workoutStarts(events).firstOrNull { (row, _) -> row.id == workoutId }
        ?: return null

    // parsed once: readActivities is what drops cancelled sets and unreadable payloads, and
    // rebuilding it per row would fold the whole journal for every event in it
    val live = readActivities(events).associateBy { it.id }

    val sets = LinkedHashMap<Long, MutableList<ActivityEvent>>()
    val rests = HashMap<Long, Int>()
    val unkeyed = ArrayList<ActivityEvent>()

    for (row in events) {
        if (row.workoutRef()?.matches(startRow) != true) continue
        val added = row.workoutExerciseAddedOrNull()
        if (added != null) {
            sets.getOrPut(added.exerciseId) { mutableListOf() }
            rests[added.exerciseId] = added.restSec
            continue
        }
        val activity = live[row.id] ?: continue
        val exerciseId = activity.form.exerciseId
        if (exerciseId == null) unkeyed += activity else sets.getOrPut(exerciseId) { mutableListOf() } += activity
    }

    return Workout(
        id = startRow.id,
        uid = startRow.uid,
        ts = startRow.ts,
        opDate = started?.opDate ?: startRow.writeDay(),
        slotId = started?.slotId,
        exercises = sets.map { (id, ofExercise) -> WorkoutExercise(id, rests[id], ofExercise) },
        entriesWithoutExercise = unkeyed,
    )
}

/** Every workout of one training day, in the order they were started. */
fun workoutsOn(events: List<JournalEvent>, opDate: String): List<Workout> =
    workoutStarts(events)
        .filter { (row, started) -> (started?.opDate ?: row.writeDay()) == opDate }
        .mapNotNull { (row, _) -> buildWorkout(events, row.id) }

/**
 * The day a set being logged right now belongs to.
 *
 * THE WORKOUT'S DAY WINS. [buildWorkout] files a set under the workout's op_date whatever the
 * set's own payload says, so a screen that built the form with today's date while a backdated
 * workout was open would produce a set the workout shows and the calendar files elsewhere —
 * the two views of one journal disagreeing about the same row. The disagreement cannot be
 * fixed after the fact either: the payload is written once and the journal is append-only.
 *
 * So the rule lives here, one call away from every screen that builds a form, rather than in
 * the comment on [buildWorkout] asking callers to remember it.
 */
fun loggingDay(workout: Workout?, today: String): String = workout?.opDate ?: today

/**
 * Sets of [opDate] that no workout in this journal claims — everything logged the way the app
 * has always worked, with nobody having pressed "start".
 *
 * This is the function that makes the start button optional rather than mandatory, so it is
 * deliberately generous about what counts as unclaimed: a row with no `workout_id` at all,
 * and also a row pointing at a workout that is not in these events. The second case is a
 * dangling id (a journal merged from elsewhere, a start event that never arrived), and the
 * only alternative to showing those sets here is not showing them anywhere.
 */
fun setsOutsideWorkouts(events: List<JournalEvent>, opDate: String): List<ActivityEvent> {
    val starts = workoutStarts(events).map { (row, _) -> row }
    return readActivities(events, dateFrom = opDate, dateTo = opDate)
        .filter { entry -> starts.none { start -> entry.workout?.matches(start) == true } }
}

/**
 * How long the rest between sets of this exercise should be offered as.
 *
 * In order: what the user last CHOSE for this exercise, then what the journal says they
 * actually did (`resolveRestSec`, which already falls back to the configured default), then
 * the configured default. Chosen beats measured on purpose — see
 * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.defaultRestSec]; the measurement includes
 * every queue and conversation, the choice is what was meant.
 *
 * A stored zero is treated as "nothing chosen" rather than as a zero-length rest. Nothing can
 * count down for zero seconds ([MIN_STEP_SEC] is the floor a step is allowed to be), so
 * honouring it would hand the timer a duration it cannot run; "no rest at all" is expressed
 * by not starting one, not by starting one of length zero.
 */
fun restHintSec(settings: TimerSettings, events: List<JournalEvent>, exercise: ExerciseRef?): Int =
    exercise?.defaultRestSec?.takeIf { it >= MIN_STEP_SEC }
        ?: resolveRestSec(settings, events, exercise?.id)

/**
 * Whether sets of this exercise are RUN BY THE PROTOCOL — the timer calling out work and rest
 * inside the set — or are simply followed by a pause.
 *
 * The catalog column decides when it has been set; otherwise it is inferred from whether the
 * exercise has a work:rest protocol at all. That inference is right for repeaters and wrong
 * for a maximum-weight hang, which carries a protocol only because §12-A makes it part of
 * hangboard identity — which is precisely why the column can override it.
 */
fun ledByProtocol(exercise: ExerciseRef): Boolean = exercise.ledByProtocolFlag ?: (exercise.protocol != null)
