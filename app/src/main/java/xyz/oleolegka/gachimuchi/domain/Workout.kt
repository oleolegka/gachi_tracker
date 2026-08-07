package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * ── A workout is closed by a button, and the clock has nothing to do with it ────
 * It used to end at midnight, because there was no finish event and something had to define
 * "the one in progress". That rule was wrong in the direction that costs training: an evening
 * session that runs to three in the morning is one session, and midnight would split it in
 * two with the last sets in the wrong half. So there is a finish event now
 * ([TYPE_WORKOUT_FINISHED]), and what closes a workout is exactly two things — pressing the
 * button, and starting the next one, which closes a forgotten one on the way past.
 *
 * NOTE, no whitewashing: the comment on [TYPE_WORKOUT_STARTED] in domain/Forms.kt still says
 * there is deliberately no closing event. It is out of date as of this change and could not
 * be corrected here — that file belongs to another change in flight.
 *
 * The cost of dropping the midnight rule, stated plainly: a workout nobody finished stays
 * open indefinitely. A set logged next Tuesday with no workout started is filed under last
 * Thursday's, and — because the end time is read off the last set (see [Workout.endTs]) —
 * that workout's end time moves to next Tuesday with it. Midnight used to cap the damage at
 * one day. What replaces it is the button and the "start closes the previous" rule, both of
 * which are actions the user takes rather than a clock guessing on their behalf (§13.8).
 */

/**
 * "That workout is over."
 *
 * A SERVICE event, like [TYPE_SET_CANCEL] and [TYPE_WORKOUT_STARTED]: it records no training,
 * so it is absent from [ACTIVITY_TYPES] and every reducer that folds sets ignores it.
 *
 * ── It is a status and not a lock ───────────────────────────────────────────────
 * A finished workout can still be opened and written into — the forgotten last set typed in
 * on the way to the car is the case, and refusing it would send that set into the next
 * workout or into no workout at all. Nothing here re-opens: the workout stays finished and
 * its end time simply moves, because the end time is READ OFF THE LAST SET
 * ([Workout.endTs]) rather than stamped by this event.
 *
 * ── Which is why this event carries no time ─────────────────────────────────────
 * The obvious payload would be "ended at HH:MM", and it would be wrong twice over: it would
 * be the moment the button was pressed (in the changing room, ten minutes after the last
 * set) and it would then have to be corrected every time a set was added afterwards — an
 * update, in a journal that has none. The last set is a fact already in the journal, and a
 * workout with no sets at all has its own start time, which is the honest answer for a
 * session that recorded nothing.
 */
const val TYPE_WORKOUT_FINISHED = "workout_finished"

/**
 * Payload of [TYPE_WORKOUT_FINISHED] — the workout being closed, said twice.
 *
 * The columns are how this app finds it; the payload says it again so the event is complete
 * on its own in an exported or merged journal, for the reason spelled out on
 * [WorkoutExerciseAdded]. [workoutUid] is the identity and [workoutId] is the local row
 * number, which means nothing off the phone that wrote it.
 */
@Serializable
data class WorkoutFinished(
    @SerialName("workout_id") val workoutId: Long,
    @SerialName("workout_uid") val workoutUid: String? = null,
)

/** One exercise inside a workout, with the rest chosen for it and the sets it collected. */
data class WorkoutExercise(
    /** Which exercise this is, said as fully as the rows that mentioned it managed to. */
    val exercise: ExerciseLink,
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
    /**
     * The local catalog row number, for the screens that still navigate by one.
     *
     * Null only for an exercise that no row in this journal named by number — which cannot
     * happen for anything this app wrote, and can for a journal merged in from elsewhere. Such
     * a block has nothing on this phone to open, and the screen has to say so rather than
     * pretend the exercise is missing.
     */
    val exerciseId: Long? get() = exercise.id

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
    /**
     * The planned session it was started from — see [SlotLink] — or null when it was started
     * off-plan, which is the ordinary case.
     */
    val slot: SlotLink?,
    /**
     * What this workout was called when it was started, or null when nobody named it — which
     * is the ordinary state of a workout started off-plan, and not a defect.
     *
     * A SNAPSHOT and not a lookup: see [WorkoutStarted]. Screens that have nothing else to
     * head a card with fall back to the time of day rather than to the plan's current name.
     */
    val name: String?,
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
    /**
     * Somebody said this workout was over — by the button, or by starting the next one.
     *
     * A STATUS AND NOT A LOCK: a finished workout can still be opened and written into, and
     * doing so does not re-open it. See [TYPE_WORKOUT_FINISHED].
     */
    val finished: Boolean = false,
) {
    /**
     * The plan's local row number, for the screens that still navigate the plan by one.
     *
     * Null for a workout started off-plan, and also for one whose start event named its plan
     * only by identity — a journal merged in from elsewhere. Neither has a slot on this phone
     * to open.
     */
    val slotId: Long? get() = slot?.id

    val setCount: Int = exercises.sumOf { it.sets.size } + entriesWithoutExercise.size

    /**
     * When the training stopped: the write time of the LAST row recorded into it, or the
     * workout's own start when it recorded nothing.
     *
     * ── Derived, because a stamped moment would be wrong from the second it was written ──
     * The alternative is to record "ended at" on the finish event, and that moment is the one
     * the button was pressed — in the changing room, after the phone came back out of a
     * pocket. Worse, it would go stale: a forgotten set typed in afterwards is training that
     * happened before the end, and in an append-only journal there is nothing to update. This
     * is folded out of the sets instead, so adding one moves the end and no correction is
     * needed anywhere.
     *
     * The largest id and not the latest timestamp, because ids increase with WRITING order
     * and that is what "the last thing recorded" means. For a workout typed up after the fact
     * the two are the same question anyway; for one where the phone's clock moved they are
     * not, and journal order is the reading that cannot be argued with.
     */
    val endTs: String =
        (exercises.flatMap { it.sets } + entriesWithoutExercise).maxByOrNull { it.id }?.ts ?: ts

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
 * The payload is consulted at all for the two SERVICE events that belong to a workout without
 * recording any training — "exercise added" and "finished". Each states its workout in its own
 * payload as well as in the column, so that a row arriving from another journal (which has
 * this app's columns nowhere) still lands in the right workout.
 */
fun JournalEvent.workoutRef(): WorkoutRef? {
    val added = workoutExerciseAddedOrNull()
    val done = workoutFinishedOrNull()
    val uid = workoutUid ?: added?.workoutUid ?: done?.workoutUid
    val id = workoutId ?: added?.workoutId ?: done?.workoutId
    return if (uid == null && id == null) null else WorkoutRef(uid, id)
}

/** Payload of an "exercise added" row, or null for any other row and for an unreadable one. */
fun JournalEvent.workoutExerciseAddedOrNull(): WorkoutExerciseAdded? =
    if (type != TYPE_WORKOUT_EXERCISE_ADDED) {
        null
    } else {
        runCatching { payloadJson.decodeFromString<WorkoutExerciseAdded>(payload) }.getOrNull()
    }

/** Payload of a "workout finished" row, or null for any other row and for an unreadable one. */
fun JournalEvent.workoutFinishedOrNull(): WorkoutFinished? =
    if (type != TYPE_WORKOUT_FINISHED) {
        null
    } else {
        runCatching { payloadJson.decodeFromString<WorkoutFinished>(payload) }.getOrNull()
    }

/**
 * Start events, newest last, paired with what their payload says.
 *
 * THROUGH [liveEvents], which it did not used to be. A workout started by mistake can now be
 * deleted and one started on the wrong date corrected, and this is the reducer every other
 * question about workouts is asked through — so a start event that bypassed the check here was
 * a workout that could be removed from the logging feed and stay in the day's cards.
 *
 * A start event whose payload will not parse is NOT skipped — the same reasoning as
 * [formFromEventOrNull], only sharper: skipping it would orphan every set recorded into that
 * workout, and those sets are the training. It degrades to "dated by its write time, started
 * from no slot", which is what an older or hand-edited row would have meant anyway.
 */
internal fun workoutStarts(events: List<JournalEvent>): List<Pair<JournalEvent, WorkoutStarted?>> =
    liveEvents(events).filter { it.type == TYPE_WORKOUT_STARTED }
        .map { it to runCatching { payloadJson.decodeFromString<WorkoutStarted>(it.payload) }.getOrNull() }

/**
 * What a start event says about the plan it was started from, or null when it names none.
 *
 * The one funnel, same as [ActivityForm.exerciseLink] for exercises: the two payload fields are
 * never read apart from each other, so no reader gets to decide for itself whether the uid or
 * the number wins.
 */
fun WorkoutStarted.slotLink(): SlotLink? =
    if (slotUid == null && slotId == null) null else SlotLink(slotUid, slotId)

/** The day a row was WRITTEN on, which is not the day the training it records belongs to. */
private fun JournalEvent.writeDay(): String = ts.substringBefore('T')

/**
 * The workout in progress, or null when there is none.
 *
 * DEFINED AS: the workout started LAST, unless it has been finished. Two rules and no clock —
 * which is the whole of the change from what this used to be.
 *
 * ── Why the last one, rather than "the last one not finished" ───────────────────
 * Because starting a workout finishes the one before it (ActivityRepository.startWorkout), so
 * an earlier workout still standing open is a journal that has been merged or hand-edited
 * rather than one this app wrote. Reaching past the newest start to find an older open
 * workout would file today's sets into last week's session — a worse answer than "nothing is
 * open", which at least leaves the set unattached and visible on its day.
 *
 * ── The two consequences, stated rather than hidden ─────────────────────────────
 *  - A workout NOBODY FINISHED stays open indefinitely. Sets logged days later with no
 *    workout started land in it, and its end time ([Workout.endTs]) follows them. Midnight
 *    used to cap that at a day; it also used to split a session that ran past three in the
 *    morning, which is the training this app is actually used for.
 *  - Sets recorded later the same day without pressing start again are still filed under the
 *    morning's workout — unchanged, and still preferable to a timeout that guesses.
 */
fun openWorkout(events: List<JournalEvent>): Workout? =
    openWorkoutRow(events)?.let { buildWorkout(events, it.id) }

/**
 * The start event of [openWorkout] — what the repository stamps onto the rows it appends.
 *
 * The whole row rather than its id, because a row is stamped with the workout's UID now and
 * the number only alongside it; handing back one of the two would put the choice of which
 * link to write at the call site.
 */
fun openWorkoutRow(events: List<JournalEvent>): JournalEvent? =
    workoutStarts(events).lastOrNull()?.first?.takeIf { !isFinished(events, it) }

/** Whether any row in [events] closes the workout started by [startRow]. */
private fun isFinished(events: List<JournalEvent>, startRow: JournalEvent): Boolean =
    events.any { it.type == TYPE_WORKOUT_FINISHED && it.workoutRef()?.matches(startRow) == true }

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
    /*
     * Folded ONCE, here, and passed down. The loop below used to walk the raw list, which is
     * how an exercise added to a workout by mistake stayed in the workout after being removed:
     * the sets went through readActivities and were dropped, the "exercise added" rows did not
     * go through anything at all. liveEvents is idempotent, so handing it on costs nothing.
     */
    val journal = liveEvents(events)
    val (startRow, started) = workoutStarts(journal).firstOrNull { (row, _) -> row.id == workoutId }
        ?: return null

    // parsed once: readActivities is what drops deleted sets and unreadable payloads, and
    // rebuilding it per row would fold the whole journal for every event in it
    val live = readActivities(journal).associateBy { it.id }

    // keyed by ExerciseLink.key so that an entry naming its exercise by identity and one
    // naming it by number land in the same block; the link itself is merged as they arrive,
    // so the block ends up knowing both
    val sets = LinkedHashMap<String, MutableList<ActivityEvent>>()
    val links = LinkedHashMap<String, ExerciseLink>()
    val rests = HashMap<String, Int>()
    val unkeyed = ArrayList<ActivityEvent>()
    var finished = false

    fun remember(link: ExerciseLink): String {
        val key = link.key
        links[key] = links[key]?.mergedWith(link) ?: link
        sets.getOrPut(key) { mutableListOf() }
        return key
    }

    for (row in journal) {
        if (row.workoutRef()?.matches(startRow) != true) continue
        if (row.type == TYPE_WORKOUT_FINISHED) {
            // sets recorded AFTER this one are still folded in below: finishing is a status,
            // not a lock, and the forgotten set typed up afterwards belongs here
            finished = true
            continue
        }
        val added = row.workoutExerciseAddedOrNull()
        if (added != null) {
            rests[remember(ExerciseLink(added.exerciseUid, added.exerciseId))] = added.restSec
            continue
        }
        val activity = live[row.id] ?: continue
        val link = activity.form.exerciseLink()
        if (link == null) unkeyed += activity else sets.getValue(remember(link)) += activity
    }

    return Workout(
        id = startRow.id,
        uid = startRow.uid,
        ts = startRow.ts,
        opDate = started?.opDate ?: startRow.writeDay(),
        slot = started?.slotLink(),
        // a name of nothing but spaces is nobody having named it, decided here rather than
        // in each screen that would otherwise draw a blank heading
        name = started?.name?.takeIf { it.isNotBlank() },
        exercises = sets.map { (key, ofExercise) ->
            WorkoutExercise(links.getValue(key), rests[key], ofExercise)
        },
        entriesWithoutExercise = unkeyed,
        finished = finished,
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
