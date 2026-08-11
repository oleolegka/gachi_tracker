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

/**
 * "This CARD is done." The same idea as [TYPE_WORKOUT_FINISHED] one level down — a status
 * on one exercise of a workout (one SIDE of it, for a one-sided exercise — see
 * [WorkoutExerciseFinished.side]) rather than on the workout as a whole.
 *
 * A SERVICE event, like its whole-workout sibling: it records no training, so it is absent
 * from [ACTIVITY_TYPES] and every reducer that folds sets ignores it.
 *
 * ── A status and not a lock, same as [TYPE_WORKOUT_FINISHED] ────────────────────
 * A finished card can still take a set — nothing here refuses one — and doing so does not
 * un-finish it. What the button on the card actually buys the user (§14.2's own words: "so
 * it interferes less and cannot be tapped by mistake") is that the card stops OFFERING to
 * take one: the entry form is not raised by a tap, and the rest countdown running under it
 * is dismissed (see [xyz.oleolegka.gachimuchi.ui.MainViewModel.finishWorkoutExercise]) rather
 * than left ticking under a card nobody is going to look at again this session.
 *
 * ── Undone by deleting it, exactly like everything else ─────────────────────────
 * There is no separate "un-finish" event. [TYPE_ENTRY_DELETED] already means "this should
 * not be there", and it already applies to itself, which is what makes undoing an undo work
 * for a set, a workout, an "exercise added" row — and now this. A dedicated reversing event
 * would be a second way of saying the same thing the rest of the journal already has one
 * word for.
 *
 * ── Why the card also moves when this is written ─────────────────────────────────
 * See [buildWorkout] for the grouping this event drives (finished cards drawn together,
 * above every active one) and [xyz.oleolegka.gachimuchi.data.ActivityRepository.finishWorkoutExercise]
 * for the accompanying order write that makes the group read in the order the cards were
 * actually finished in, rather than the order they happened to be added in.
 */
const val TYPE_WORKOUT_EXERCISE_FINISHED = "workout_exercise_finished"

/**
 * Payload of [TYPE_WORKOUT_EXERCISE_FINISHED] — the card being closed, said the same way
 * [OrderedExercise] names one: by identity where there is one, by number where there is not,
 * and never neither (see its own `init`). The workout is also named twice, in the payload as
 * well as in the column, for the reason spelled out on [WorkoutExerciseAdded].
 */
@Serializable
data class WorkoutExerciseFinished(
    @SerialName("workout_id") val workoutId: Long,
    @SerialName("exercise_id") val exerciseId: Long? = null,
    @SerialName("exercise_uid") val exerciseUid: String? = null,
    @SerialName("workout_uid") val workoutUid: String? = null,
    /** Which card this is — the same idea as [OrderedExercise.side], matched the same way. */
    @SerialName("side") val side: String? = null,
) {
    init {
        require(exerciseId != null || exerciseUid != null) {
            "workout_exercise_finished: an entry must name an exercise by uid or by id"
        }
    }
}

/** The pair read as one reference — the same funnel [OrderedExercise.link] is. */
fun WorkoutExerciseFinished.link(): ExerciseLink = ExerciseLink(exerciseUid, exerciseId)

/** [side] read as the domain compares it — see [OrderedExercise.sideOf]. */
val WorkoutExerciseFinished.sideOf: HoldSide? get() = HoldSide.fromCode(side)

/**
 * "This is the order I want the exercises of that workout in."
 *
 * A SERVICE event like [TYPE_WORKOUT_FINISHED]: it records no training, it is absent from
 * [ACTIVITY_TYPES], and every reducer that folds sets ignores it.
 *
 * ── Why the order needs an event at all ─────────────────────────────────────────
 * It did not have one, and the order was the order the exercises were ADDED in — recovered
 * from the journal rather than stored anywhere. That is a sound default and a wrong fact: a
 * machine is taken while you are warming up, you do the next thing instead, and the list on
 * the phone now disagrees with the session being done. The journal is append-only, so the
 * order cannot be fixed by editing the past. It has to be said again, later, in a new row.
 *
 * ── Why the WHOLE order and not "move this one after that one" ──────────────────
 * Both would work with one device. Only this one is safe with two:
 *
 *  - LAST WRITER WINS, with nothing to resolve. Two phones that both reordered the same
 *    workout merge into one list — the later row's — instead of into a sequence of moves whose
 *    result depends on which order they are replayed in.
 *  - THE ORDER CANNOT BE PARTIAL. A move names a neighbour, and a neighbour can have been
 *    removed from the workout by the time the move is read; the result is then undefined and
 *    every reader gets to invent it. A full list has no neighbours to lose.
 *  - The fold is one pass and a list lookup, rather than a replay with its own history.
 *
 * What it costs, stated: the row records a RESULT and not an intention, so nothing in the
 * journal knows that "bench moved to the top" — only that the order became this. And the
 * payload grows with the number of exercises in the workout, which at one row per drag and a
 * handful of exercises per session is nothing worth optimising.
 *
 * ── An exercise added afterwards goes to the END ────────────────────────────────
 * Decided in [buildWorkout] and by the row NUMBERS rather than by the payload: an exercise
 * whose first row in this workout is newer than the order event could not have been meant by
 * it, so it lands after everything the event named. That covers the ordinary case (add an
 * exercise mid-session, it appears at the bottom where "add" always puts things) and the awkward
 * one (an exercise removed and later added back returns to the end rather than teleporting into
 * the slot it used to hold).
 */
const val TYPE_WORKOUT_ORDER_SET = "workout_order_set"

/**
 * Payload of [TYPE_WORKOUT_ORDER_SET] — the workout, and its exercises in the wanted order.
 *
 * The workout is named here as well as in the columns for the reason spelled out on
 * [WorkoutExerciseAdded]: an exported or merged journal is a stream of events with none of this
 * app's columns, and the row still has to land in the right workout.
 *
 * [order] may name exercises this workout does not (or no longer) contain, and may leave out
 * exercises it does. Neither is an error — see [buildWorkout] — because the journal it describes
 * keeps changing after it is written and there is nothing to go back and correct.
 */
@Serializable
data class WorkoutOrder(
    @SerialName("workout_id") val workoutId: Long,
    @SerialName("order") val order: List<OrderedExercise>,
    @SerialName("workout_uid") val workoutUid: String? = null,
)

/**
 * One exercise named by an order event, said the same two ways every other row names one.
 *
 * [exerciseUid] is the identity and [exerciseId] the local row number it replaced; the readers
 * go through [ExerciseLink] and believe the identity, for the reason on [ExerciseLink.matches].
 * An entry naming NEITHER identifies nothing, and a payload carrying one is refused here rather
 * than silently shifting every exercise after it by one place.
 */
@Serializable
data class OrderedExercise(
    @SerialName("exercise_id") val exerciseId: Long? = null,
    @SerialName("exercise_uid") val exerciseUid: String? = null,
    /**
     * Which card this entry means, for an exercise that has two — see
     * [WorkoutExerciseAdded.side]. Matched against [WorkoutExercise.side] exactly, the same
     * "matches or it does not" rule [exerciseId]/[exerciseUid] already follow: null names the
     * one card an exercise that is not one-sided has, and an order event written before this
     * field existed carries null for every entry, which is why such an event cannot rearrange a
     * left/right split it predates — see [reordered].
     */
    @SerialName("side") val side: String? = null,
) {
    init {
        require(exerciseId != null || exerciseUid != null) {
            "workout_order_set: an entry must name an exercise by uid or by id"
        }
    }
}

/** The pair read as one reference, so no reader picks for itself which half wins. */
fun OrderedExercise.link(): ExerciseLink = ExerciseLink(exerciseUid, exerciseId)

/** [side] read as the domain compares it — see [HoldSet.sideOf], the same idea for a set. */
val OrderedExercise.sideOf: HoldSide? get() = HoldSide.fromCode(side)

/**
 * One card named for the order event or the "add to workout" write — an exercise, and which
 * side of it when it has one. The screen-facing counterpart of [OrderedExercise]: callers hold
 * an [ExerciseLink] and a [HoldSide], not a payload's raw strings.
 */
data class OrderedCard(val exercise: ExerciseLink, val side: HoldSide? = null)

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
    /**
     * The "exercise added" rows that put this exercise in the workout, if any.
     *
     * Carried because REMOVING an exercise from a workout is deleting them together with its
     * sets, and a screen holding only the folded block would have nothing to name. Several,
     * not one: adding an exercise again is how the rest is changed in an append-only journal
     * (see [buildWorkout]), so an exercise whose rest was reconsidered twice has three rows
     * saying it belongs here — and leaving any of them alive would put the card straight back.
     *
     * Empty for an exercise nobody added explicitly, which is present only because a set named
     * it. Removing that one is removing its sets, and there is nothing else to take out.
     */
    val addedEventIds: List<Long> = emptyList(),
    /**
     * Which card of the exercise this is, or null for the ordinary exercise that has only one.
     *
     * On an exercise trained one limb at a time ([xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.oneSided])
     * this is what tells two blocks of the same [exercise] apart — one [HoldSide.LEFT], one
     * [HoldSide.RIGHT] — so they draw as two independent cards with two independent rests
     * rather than colliding into one. See [buildWorkout] for how a block ends up with a side:
     * the "added" row that put it here, or failing that the first set that named a side.
     *
     * A THIRD, SIDELESS block is possible for the same exercise, and it is not a bug: a set
     * recorded with no side at all (a journal merged from elsewhere, an amendment that cleared
     * it) belongs to neither hand's history and gets its own block rather than being folded
     * into either one — the same refusal to guess [holdRecord] makes about such a set.
     */
    val side: HoldSide? = null,
    /**
     * The id of the live [TYPE_WORKOUT_EXERCISE_FINISHED] event that marked this CARD done, or
     * null for a card nobody has marked.
     *
     * ── The mark and the way to undo it are ONE field on purpose ────────────────────
     * There used to be a `finished: Boolean` beside this, and the two could only ever say the
     * same thing: both were read off the same map, in the same expression, one with
     * `containsKey` and one with `get`. Two fields that must agree are two fields that can be
     * made to disagree — by a copy() that sets one, by a fold that forgets the other — and the
     * screen was already asking the same question twice to get both halves. So the id is the
     * whole of the state and [finished] below is derived from it, which is a fact the type
     * enforces rather than a rule someone has to keep.
     *
     * Carried here rather than looked up again because a screen offering the undo control is
     * already holding this block — see
     * [xyz.oleolegka.gachimuchi.data.ActivityRepository.unfinishWorkoutExercise] for what
     * deleting it does.
     */
    val finishedEventId: Long? = null,
) {
    /**
     * Whether this CARD has been marked done — see [TYPE_WORKOUT_EXERCISE_FINISHED]. A status
     * and not a lock, the same rule [Workout.finished] follows one level up: the card can
     * still be written into, and doing so does not clear this.
     *
     * Drives [buildWorkout]'s grouping (every finished card drawn above every active one) and
     * is what a screen reads to draw the collapsed card and the green check.
     */
    val finished: Boolean get() = finishedEventId != null

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
 * The identity of a CARD rather than of an exercise: [WorkoutExercise.exercise]'s key plus its
 * [WorkoutExercise.side]. Two cards of one one-sided exercise share the first half and differ
 * in the second, which is the whole reason this exists — a screen keying its list by the
 * exercise alone would draw the left and right card of one exercise as the same row.
 */
val WorkoutExercise.cardKey: String get() = workoutCardKey(exercise.key, side)

/** The one place [cardKey] is actually computed, so [buildWorkout] and readers agree on it. */
private fun workoutCardKey(exerciseKey: String, side: HoldSide?): String =
    "$exerciseKey#${side?.code ?: "-"}"

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
    /**
     * Exercises IN THE ORDER THEY ARE TO BE DONE, including ones with no sets yet.
     *
     * Which is the order they were added in until somebody says otherwise, and the order the
     * last [TYPE_WORKOUT_ORDER_SET] row states once they have — a machine being occupied is not
     * a reason to do the session in the order it was sketched in. Folded by [buildWorkout], so
     * every screen showing a workout shows the same one.
     *
     * ── EVERY FINISHED CARD FIRST, in the order they were finished ──────────────────
     * The two groups (see [WorkoutExercise.finished]) are never interleaved: every card
     * [buildWorkout] considers finished comes before every one it does not, whatever the flat
     * order above says about them individually. WITHIN each group the flat order still
     * decides — added order, or the last drag — and for the finished group that flat order is
     * kept in completion order by
     * [xyz.oleolegka.gachimuchi.data.ActivityRepository.finishWorkoutExercise] writing a fresh
     * one alongside every [TYPE_WORKOUT_EXERCISE_FINISHED] it appends. The split itself is
     * enforced HERE regardless, rather than trusted to that write having landed — a merged
     * journal, or the second of the two writes never reaching the table, must not be able to
     * put a finished card back among the active ones.
     */
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
     * The id of the live [TYPE_WORKOUT_FINISHED] event that closed this workout, or null for
     * one still going — the same "the mark and the way to undo it are one field" choice
     * [WorkoutExercise.finishedEventId] makes for a single card, and for the same reason: a
     * screen offering to undo the finish already needs this id to name the deletion, and a
     * second `finished: Boolean` beside it would only be a fact that could disagree.
     */
    val finishedEventId: Long? = null,
) {
    /**
     * Somebody said this workout was over — by the button, or by starting the next one.
     *
     * A STATUS AND NOT A LOCK: a finished workout can still be opened and written into, and
     * doing so does not re-open it. See [TYPE_WORKOUT_FINISHED].
     */
    val finished: Boolean get() = finishedEventId != null

    /**
     * The plan's local row number, for the screens that still navigate the plan by one.
     *
     * Null for a workout started off-plan, and also for one whose start event named its plan
     * only by identity — a journal merged in from elsewhere. Neither has a slot on this phone
     * to open.
     */
    val slotId: Long? get() = slot?.id

    /**
     * How many SETS this workout holds — the count "N exercises, M sets" is built from.
     *
     * A body-weight entry recorded into a workout lands in [entriesWithoutExercise] (it names
     * no exercise) but is not a set: stepping on the scales is not a rep of anything, and a
     * workout that held nothing else must not read "1 set" over it. Every other kind of loose
     * entry a workout can hold IS a set — that is what "recorded with no exercise" has meant
     * since before body weight existed as a form — so only [Bodyweight] is filtered out here.
     */
    val setCount: Int = exercises.sumOf { it.sets.size } +
        entriesWithoutExercise.count { it.form !is Bodyweight }

    /**
     * When the training stopped: when the LAST set recorded into it actually happened, or the
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
     * ── By [happenedAt], not by id or write time ─────────────────────────────────────
     * This used to be the write time of whichever set carried the largest id — sound while the
     * only way a row could join a workout was to be trained into it there and then, so "written
     * last" and "trained last" were the same fact. A correction breaks that the same way it
     * breaks [openWorkoutRow]: fixing a typo in a set from three weeks ago writes a new row,
     * with today's id and today's ts, into a workout that ended three weeks ago — and the old
     * id-based rule would read the fix itself as the moment training stopped, showing "finished
     * [today]" on a session nobody has touched since. [happenedAt] is inherited across the
     * correction, so the identified set's OWN happened-at time is shown rather than the
     * correction's write time, and an unrelated typo fix can no longer move a workout's end.
     * The id tie-break keeps the previous behaviour for two ORIGINAL sets in the same second,
     * where [happenedAt] cannot tell them apart.
     */
    val endTs: String =
        (exercises.flatMap { it.sets } + entriesWithoutExercise)
            .maxWithOrNull(compareBy({ it.happenedAt }, { it.id }))?.happenedAt ?: ts

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
 * One exercise staged into a workout that has not been started yet — see §13.1, "the start
 * event is created lazily", and [xyz.oleolegka.gachimuchi.ui.MainViewModel]'s draft state.
 *
 * The same two facts [WorkoutExercise] carries for a card already in a real workout — which
 * exercise, and the rest chosen for it — because that is all a card can say before it exists:
 * no sets, no "added" row, nothing a card in a real workout also has to have written for it.
 */
data class DraftCard(val exerciseId: Long, val restSec: Int, val side: HoldSide? = null)

/**
 * [cards] shaped as a [Workout], so the screen that draws one can draw the other without
 * knowing the difference — see [xyz.oleolegka.gachimuchi.ui.screens.WorkoutLogScreen].
 *
 * [id]/[uid] are sentinels: nothing about a draft is a row yet, and nothing downstream reads
 * them off a [Workout] built here — the actions a screen is handed close over the real id once
 * [xyz.oleolegka.gachimuchi.ui.MainViewModel.promoteDraft] has written one, never this one.
 */
fun draftWorkout(opDate: String, name: String?, cards: List<DraftCard>, linkOf: (Long) -> ExerciseLink): Workout =
    Workout(
        id = 0L,
        uid = "",
        ts = "",
        opDate = opDate,
        slot = null,
        name = name,
        exercises = cards.map { WorkoutExercise(linkOf(it.exerciseId), it.restSec, emptyList(), side = it.side) },
        entriesWithoutExercise = emptyList(),
    )

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
    val ordered = workoutOrderOrNull()
    val cardDone = workoutExerciseFinishedOrNull()
    val uid = workoutUid ?: added?.workoutUid ?: done?.workoutUid ?: ordered?.workoutUid ?: cardDone?.workoutUid
    val id = workoutId ?: added?.workoutId ?: done?.workoutId ?: ordered?.workoutId ?: cardDone?.workoutId
    return if (uid == null && id == null) null else WorkoutRef(uid, id)
}

/** Payload of an "exercise added" row, or null for any other row and for an unreadable one. */
fun JournalEvent.workoutExerciseAddedOrNull(): WorkoutExerciseAdded? =
    if (type != TYPE_WORKOUT_EXERCISE_ADDED) {
        null
    } else {
        runCatching { payloadJson.decodeFromString<WorkoutExerciseAdded>(payload) }.getOrNull()
    }

/** Payload of a "card finished" row, or null for any other row and for an unreadable one. */
fun JournalEvent.workoutExerciseFinishedOrNull(): WorkoutExerciseFinished? =
    if (type != TYPE_WORKOUT_EXERCISE_FINISHED) {
        null
    } else {
        runCatching { payloadJson.decodeFromString<WorkoutExerciseFinished>(payload) }.getOrNull()
    }

/** Payload of a "workout finished" row, or null for any other row and for an unreadable one. */
fun JournalEvent.workoutFinishedOrNull(): WorkoutFinished? =
    if (type != TYPE_WORKOUT_FINISHED) {
        null
    } else {
        runCatching { payloadJson.decodeFromString<WorkoutFinished>(payload) }.getOrNull()
    }

/**
 * Payload of an order row, or null for any other row and for an unreadable one.
 *
 * Null for a DAMAGED one on purpose, same rule as everywhere else here: an order that will not
 * parse leaves the workout in the order it was added in, which is the answer the app gave before
 * this event existed. The alternative — throwing — takes down the screen somebody is holding in
 * a gym over a row that only decides which card is drawn first.
 */
fun JournalEvent.workoutOrderOrNull(): WorkoutOrder? =
    if (type != TYPE_WORKOUT_ORDER_SET) {
        null
    } else {
        runCatching { payloadJson.decodeFromString<WorkoutOrder>(payload) }.getOrNull()
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
internal fun JournalEvent.writeDay(): String = ts.substringBefore('T')

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
 * ── "Last" is by [happenedAt], not by row id ─────────────────────────────────────
 * It did not used to need saying: the only way a [TYPE_WORKOUT_STARTED] row was ever written
 * was by pressing "start", so the newest ROW and the workout started most recently were the
 * same fact. That stopped being true the moment a workout could be corrected (renaming one, or
 * fixing its date, writes a brand new start row — domain/Amendments.kt's header): the newest
 * ROW can now be a typo fixed today in a workout from last month, appended after every start
 * this app has genuinely written since. Reading such a correction as "the last workout
 * started" would either hijack today's still-open session (its sets misfiled into last
 * month's) or, if the corrected workout was already finished, make openWorkoutRow answer null
 * while a real one sits open earlier in the journal — the exact failure this function exists
 * to avoid, now caused by the very rule meant to avoid it. [happenedAt] is inherited across a
 * correction, so the corrected row stays exactly where it always was in training time, and an
 * unrelated correction can no longer change which workout this answers with.
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
fun openWorkoutRow(events: List<JournalEvent>): JournalEvent? {
    // folded ONCE and handed to both halves: "which workout was started last" and "was it
    // closed" have to be answered off the same journal. A finish event is an event like any
    // other, so deleting one re-opens the workout it closed — which is the only way back from
    // a button pressed by mistake, and there is no separate "re-open" event for it.
    val journal = liveEvents(events)
    // happenedAt first, then id (journal order) for a same-second tie — see this function's
    // own KDoc for why the raw journal order this used to pick .lastOrNull() by is no longer
    // safe once a correction can rewrite a workout's own start row
    val last = workoutStarts(journal).maxWithOrNull(compareBy({ it.first.happenedAt }, { it.first.id }))?.first
    return last?.takeIf { !isFinished(journal, it) }
}

/**
 * Whether any row in [events] closes the workout started by [startRow].
 *
 * Private, and it takes an ALREADY FOLDED journal. Handed the raw list it would count a finish
 * event that has since been deleted, and the workout would stay shut with nothing on screen to
 * say why — the exact class of bug domain/Amendments.kt exists to end.
 */
private fun isFinished(events: List<JournalEvent>, startRow: JournalEvent): Boolean =
    events.any { it.type == TYPE_WORKOUT_FINISHED && it.workoutRef()?.matches(startRow) == true }

/**
 * Folds one workout out of the journal, or null when [workoutId] names no start event here.
 *
 * ── The order of the exercises ──────────────────────────────────────────────────
 * The order they were ADDED IN, unless somebody has said otherwise — see
 * [TYPE_WORKOUT_ORDER_SET]. Added order is the default and not the rule: it is what a workout
 * nobody has reordered means, and it stays the answer for every exercise the last order event
 * did not name. An exercise enters the list on its first appearance, whether that is an explicit
 * "added" event or simply the first set recorded under it — a set logged for an exercise nobody
 * added is real training and cannot be left out of the workout it happened in.
 *
 * Adding an exercise that is already in the list does not reorder it; it only updates the
 * rest, last one winning. That is how changing your mind about the pause is expressed in an
 * append-only journal — there is nothing to edit, so you say it again.
 *
 * The LAST live order event wins, by journal order — the same "say it again" rule the rest
 * uses. Live, because the fold below runs on [liveEvents]: deleting an order event brings the
 * one before it back, and deleting them all brings back the order things were added in. There
 * is no separate "undo the reordering", for the same reason there is no "re-open the workout".
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
    val view = journalView(events)
    val journal = events.mapNotNull { view.revised(it) }
    /*
     * [workoutId] may name a `workout_started` row that has since been CORRECTED — its own date
     * or name amended, which writes a new row under a new id (see domain/Amendments.kt's header
     * on rows other rows point at by uid). A caller holding the id from before that correction
     * — every screen that opened this workout and kept its id around — must still find it, so
     * the id is resolved forward the same way a child's `workout_id` column already is.
     */
    val resolvedId = view.canonicalId(workoutId)
    val (startRow, started) = workoutStarts(journal).firstOrNull { (row, _) -> row.id == resolvedId }
        ?: return null

    // parsed once: readActivities is what drops deleted sets and unreadable payloads, and
    // rebuilding it per row would fold the whole journal for every event in it
    val live = readActivities(journal).associateBy { it.id }

    // keyed by [workoutCardKey] — the exercise AND its side together — so that an entry naming
    // its exercise by identity and one naming it by number land in the same block, and so that
    // the left and right card of a one-sided exercise land in TWO. The link itself is merged as
    // its mentions arrive, so a block ends up knowing both the identity and the number.
    val sets = LinkedHashMap<String, MutableList<ActivityEvent>>()
    val links = LinkedHashMap<String, ExerciseLink>()
    val sides = HashMap<String, HoldSide?>()
    val rests = HashMap<String, Int>()
    val addedRows = HashMap<String, MutableList<Long>>()
    /** The live "card finished" event of each card, keyed the same way — see [WorkoutExercise.finished]. */
    val cardFinished = HashMap<String, Long>()
    val unkeyed = ArrayList<ActivityEvent>()
    /*
     * The FIRST row that put each exercise in this workout, which is what decides whether an
     * order event could have meant it — see [reordered]. The first and not the last: adding an
     * exercise again to change its rest must not make it look newly arrived and send it to the
     * bottom of a list the user has just arranged.
     */
    val firstRow = HashMap<String, Long>()
    /**
     * The live "workout finished" event, by id — overwritten on every one seen rather than
     * just flagged, the same "last one wins" rule [cardFinished] follows for a single card:
     * a workout finished, reopened and finished again carries the LAST such event, which is
     * the one [unfinishWorkoutExercise]'s whole-workout twin has to name to undo it.
     */
    var finishedRowId: Long? = null
    var order: WorkoutOrder? = null
    var orderRowId = 0L

    fun remember(link: ExerciseLink, rowId: Long, side: HoldSide?): String {
        val key = workoutCardKey(link.key, side)
        links[key] = links[key]?.mergedWith(link) ?: link
        sets.getOrPut(key) { mutableListOf() }
        firstRow.putIfAbsent(key, rowId)
        sides.putIfAbsent(key, side)
        return key
    }

    for (row in journal) {
        if (row.workoutRef()?.matches(startRow) != true) continue
        if (row.type == TYPE_WORKOUT_FINISHED) {
            // sets recorded AFTER this one are still folded in below: finishing is a status,
            // not a lock, and the forgotten set typed up afterwards belongs here
            finishedRowId = row.id
            continue
        }
        val stated = row.workoutOrderOrNull()
        if (stated != null) {
            // last one wins, and an unreadable one is simply not the last one
            order = stated
            orderRowId = row.id
            continue
        }
        val added = row.workoutExerciseAddedOrNull()
        if (added != null) {
            val side = HoldSide.fromCode(added.side)
            val key = remember(ExerciseLink(added.exerciseUid, added.exerciseId), row.id, side)
            rests[key] = added.restSec
            addedRows.getOrPut(key) { mutableListOf() } += row.id
            continue
        }
        val cardDone = row.workoutExerciseFinishedOrNull()
        if (cardDone != null) {
            // liveEvents already dropped every finish event this card's own un-finish
            // (a deletion) undid, so whatever is left standing here is the one live mark —
            // see the header comment on [TYPE_WORKOUT_EXERCISE_FINISHED]
            val key = remember(cardDone.link(), row.id, cardDone.sideOf)
            cardFinished[key] = row.id
            continue
        }
        val activity = live[row.id] ?: continue
        val link = activity.form.exerciseLink()
        if (link == null) {
            unkeyed += activity
        } else {
            // only a LoadedSet ever carries a side (see LoadedSet.sideOf); every other form
            // joins the sideless block, which is the only block such an exercise can have
            val side = (activity.form as? LoadedSet)?.sideOf
            sets.getValue(remember(link, row.id, side)) += activity
        }
    }

    /*
     * Sorted by [happenedAt] rather than left in the journal order the loop above walked: the
     * loop's order decides STRUCTURE (which card, which side, which rest, whether an order
     * event could have meant this card — all of that stays exactly as it was, unaffected), but
     * the sets DRAWN ON a card are a tape of the exercise as it was actually done, the same
     * argument [buildSession] makes for the day's feed. A set corrected after later ones were
     * logged must not visibly jump past them.
     */
    val blocks = sets.map { (key, ofExercise) ->
        WorkoutExercise(
            links.getValue(key), rests[key],
            // happenedAt first, then id (journal order) for a same-second tie, the same rule
            // buildSession settles its own tie by
            ofExercise.sortedWith(compareBy({ it.happenedAt }, { it.id })),
            addedRows[key].orEmpty(), sides[key], finishedEventId = cardFinished[key],
        )
    }
    val ordered = order?.let { reordered(blocks, it.order, orderRowId, firstRow) } ?: blocks

    return Workout(
        id = startRow.id,
        uid = startRow.uid,
        ts = startRow.ts,
        opDate = started?.opDate ?: startRow.writeDay(),
        slot = started?.slotLink(),
        // a name of nothing but spaces is nobody having named it, decided here rather than
        // in each screen that would otherwise draw a blank heading
        name = started?.name?.takeIf { it.isNotBlank() },
        exercises = ordered.groupedByCardStatus(),
        entriesWithoutExercise = unkeyed,
        finishedEventId = finishedRowId,
    )
}

/**
 * [this] with every FINISHED card ([WorkoutExercise.finished]) moved ahead of every ACTIVE
 * one, each group otherwise keeping the relative order it already has — see the note on
 * [Workout.exercises] for why the split is enforced here rather than left to the flat order
 * to have gotten right on its own.
 *
 * A stable partition rather than a sort: [List.partition] preserves the order items already
 * had within each half it hands back, which is the only thing that lets the finished half
 * read as "the order they were finished in" — see
 * [xyz.oleolegka.gachimuchi.data.ActivityRepository.finishWorkoutExercise] for where that
 * order is actually written.
 */
private fun List<WorkoutExercise>.groupedByCardStatus(): List<WorkoutExercise> {
    val (finishedCards, active) = partition { it.finished }
    /*
     * The finished half is sorted by the event that finished it, and the active half is left
     * exactly as it came.
     *
     * Partition alone preserves the order each card already had, which is the order it was
     * ADDED in (or dragged into) — not the order it was finished in. Those are different
     * orders whenever the cards are not finished in the order they were added, which is most
     * sessions, and the difference is the whole point of not putting finished cards at the
     * very top: who finished first has to stay readable.
     *
     * By event id rather than by timestamp: ids are handed out in write order by the same
     * append that writes the row, so they order the finishes without asking the clock, which
     * a phone's owner can move. A card finished, un-finished and finished again carries the
     * LAST such event, so it takes its place at the end of the group, which is where the
     * person who just finished it again expects to find it.
     */
    return finishedCards.sortedBy { it.finishedEventId ?: Long.MAX_VALUE } + active
}

/**
 * [blocks] rearranged to match [wanted], with everything [wanted] could not have meant left in
 * the order it was added in, at the end.
 *
 * ── Every way an order event can be out of date, and what each does ─────────────
 * The event describes a workout that keeps changing after the row is written, and the journal
 * cannot go back and correct it. So all four cases are ordinary rather than exceptional, and
 * none of them may cost the reader a card:
 *
 *  - an entry naming an exercise that has since been REMOVED from the workout matches no block
 *    and is skipped — the exercise drops out of the order the way it dropped out of the workout;
 *  - an entry naming an exercise this workout never held (a merged journal, a hand-edited row)
 *    is the same case and is skipped for the same reason, rather than being allowed to throw;
 *  - a block the event does not name at all keeps its place at the END, in added order. That is
 *    what an exercise added since is, and it is also what an order event that named only half
 *    the workout leaves behind;
 *  - a block whose first row is NEWER than the order row could not have been meant by it, even
 *    if an entry names it. That is the exercise removed and later added back: the stale entry
 *    would otherwise pull it into the slot it used to have, and "added" has always meant "at
 *    the bottom".
 *
 * A duplicate entry (the same exercise twice) claims a block once — [taken] — so the second
 * mention finds nothing and is skipped. Nothing this app writes contains one; a merged journal
 * might, and duplicating the card would be the one answer that invents training.
 *
 * ── Two cards of one exercise are matched by side as well as by identity ────────
 * [taken] and [firstRow] are keyed by [WorkoutExercise.cardKey] rather than by
 * [ExerciseLink.key] alone, and an entry is matched only against a block whose
 * [WorkoutExercise.side] equals the entry's own — see [OrderedExercise.sideOf]. Without that a
 * one-sided exercise's left and right block would look like the same block twice to [taken]: the
 * first entry naming the exercise would claim whichever of the two [firstOrNull] happened to
 * reach first, and the second would find it already taken and fall through to "not named",
 * landing at the end instead of where it was actually dragged to.
 */
private fun reordered(
    blocks: List<WorkoutExercise>,
    wanted: List<OrderedExercise>,
    orderRowId: Long,
    firstRow: Map<String, Long>,
): List<WorkoutExercise> {
    val movable = blocks.filter { (firstRow[it.cardKey] ?: Long.MAX_VALUE) < orderRowId }
    if (movable.isEmpty()) return blocks
    val taken = HashSet<String>()
    val head = ArrayList<WorkoutExercise>(blocks.size)
    for (entry in wanted) {
        val link = entry.link()
        val block = movable.firstOrNull {
            it.cardKey !in taken && it.exercise.matches(link) && it.side == entry.sideOf
        } ?: continue
        taken += block.cardKey
        head += block
    }
    return head + blocks.filterNot { it.cardKey in taken }
}

/**
 * Every journal row a workout is made of: its start, the exercises added to it, the sets
 * recorded into it, and its finish. Empty when [workoutId] names no live start event.
 *
 * ── Why removing a workout is not removing one event ────────────────────────────
 * Deleting the start event alone WOULD take the workout off every screen, and it would leave
 * its sets behind: a row pointing at a workout that is not in the journal is treated as
 * unclaimed by [setsOutsideWorkouts], deliberately, so that a journal merged from elsewhere
 * cannot hide training. The sets would reappear on the day as loose entries and keep counting
 * towards volume, records and the streak — which is not what anybody pressing "delete this
 * workout" is asking for, and worse, is the opposite of what the confirmation promised.
 *
 * So the whole workout is named here, in one place, and the caller deletes the lot. Reversible
 * like every other deletion, one deletion per row.
 *
 * The rows are returned in journal order, the start first. Nothing depends on the order — a
 * deletion names its target by identity — but a stable one keeps the writes readable in an
 * export.
 */
fun workoutEventIds(events: List<JournalEvent>, workoutId: Long): List<Long> {
    val view = journalView(events)
    val journal = events.mapNotNull { view.revised(it) }
    // see buildWorkout for why the incoming id has to be resolved forward first
    val resolvedId = view.canonicalId(workoutId)
    val startRow = workoutStarts(journal).firstOrNull { (row, _) -> row.id == resolvedId }?.first
        ?: return emptyList()
    return listOf(startRow.id) +
        journal.filter { it.id != startRow.id && it.workoutRef()?.matches(startRow) == true }
            .map { it.id }
}

/**
 * Every workout of one training day, in the order they were started — by [happenedAt], not by
 * the position of the (possibly corrected) start row in the journal. A workout renamed or
 * moved onto this day after a LATER workout was already logged must still show up before it,
 * the same argument [buildSession] makes for a corrected set within a day's feed.
 */
fun workoutsOn(events: List<JournalEvent>, opDate: String): List<Workout> =
    workoutStarts(events)
        .filter { (row, started) -> (started?.opDate ?: row.writeDay()) == opDate }
        // happenedAt first, then id (journal order) for a same-second tie
        .sortedWith(compareBy({ (row, _) -> row.happenedAt }, { (row, _) -> row.id }))
        .mapNotNull { (row, _) -> buildWorkout(events, row.id) }

// --- starting a workout like a past one (§13.9) -----------------------------------------
//
// A plan is not the only reasonable answer to "what shall this session consist of". A workout
// nobody bothered to plan ahead of time, but named the same thing three times because it always
// is the same thing ("Push day"), is asking to be offered the same cards without a slot ever
// having existed for it. NAMES ARE NOT UNIQUE — the owner's own words are "we do not forbid it"
// — so the question this answers is always "the LAST workout under this name", never "the one
// named this".

/**
 * Every name a past workout has ever been started under, once each — the list a "start like
 * last time" dropdown offers, not a workout instance (there is no id here to hand back: picking
 * a NAME is what [lastWorkoutNamed] resolves into one, at the moment a workout is started, so
 * that training done in between never goes stale in a list held on screen).
 *
 * Ordered by the most recent use of each name, so the routine trained most recently — the one
 * likeliest to be wanted again — sits at the top.
 *
 * A nameless workout (§13's ordinary case) contributes nothing: there is nothing to offer a
 * dropdown for "no name", and starting one off-plan with no name already has its own path that
 * does not go near this list.
 */
fun pastWorkoutNames(events: List<JournalEvent>): List<String> =
    workoutStarts(events)
        .mapNotNull { (row, started) -> started?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { it to row.happenedAt } }
        // last occurrence of each name decides where it sits in the list; the name itself
        // does not repeat in the result no matter how many workouts carried it
        .groupBy({ (name, _) -> name }, { (_, ts) -> ts })
        .mapValues { (_, timestamps) -> timestamps.max() }
        .entries
        .sortedByDescending { (_, lastUsed) -> lastUsed }
        .map { (name, _) -> name }

/**
 * The workout to copy from when a new one is started under [name] — the LATEST live workout
 * that carried exactly this name, or null when none did (a name never used before, or typed by
 * hand rather than picked off [pastWorkoutNames]).
 *
 * "Latest" by [happenedAt] then id, the same tie-break [openWorkoutRow] uses — three sessions
 * can share one name (§13.9's whole premise), and only the most recent of them is what "like
 * last time" is asking for. Matched by an EXACT, trimmed name: the same string [pastWorkoutNames]
 * hands back, and the same normalisation [xyz.oleolegka.gachimuchi.data.ActivityRepository.startWorkout]
 * already applies before a name is written, so a stray space on the way in cannot make a real
 * match miss.
 */
fun lastWorkoutNamed(events: List<JournalEvent>, name: String): Workout? {
    val target = name.trim().takeIf { it.isNotEmpty() } ?: return null
    val match = workoutStarts(events)
        .filter { (_, started) -> started?.name?.trim() == target }
        .maxWithOrNull(compareBy({ (row, _) -> row.happenedAt }, { (row, _) -> row.id }))
        ?.first
        ?: return null
    return buildWorkout(events, match.id)
}

/**
 * [workout]'s composition read back as [PlannedExercise] entries — the shape [resolvedCards]
 * wants, so "start like last time" goes through the exact same funnel a plan does rather than a
 * second way of turning a source into cards.
 *
 * A COPY of what the workout consisted of, the same rule §13.7 already gives a plan: this reads
 * [Workout.exercises] as [buildWorkout] folds it NOW, which already leaves out a card that was
 * later removed — nothing here re-derives "what used to be there" from a stale snapshot, there
 * is no snapshot to go stale.
 *
 * Each entry's [PlannedExercise.side] is filled in from the card's own [WorkoutExercise.side]
 * rather than left null, which is what tells [resolvedCards] this pair is ALREADY split and
 * must not be fanned out a second time.
 *
 * A card whose exercise this journal never gave a local row number (merged in from elsewhere)
 * is dropped: there is nothing on this phone [xyz.oleolegka.gachimuchi.data.ActivityRepository.addExerciseToWorkout]
 * could add it as.
 */
fun asPlanned(workout: Workout): List<PlannedExercise> =
    workout.exercises.mapNotNull { card ->
        card.exercise.id?.let { id -> PlannedExercise(id, card.restSec, card.side) }
    }

/**
 * [planned] resolved into the CARDS a workout actually gets: the rest each one is offered at,
 * and — for a one-sided exercise a plan named but did not split — the fan into its two.
 *
 * THE ONE FUNNEL both sources of a workout's starting composition go through:
 * [xyz.oleolegka.gachimuchi.data.ActivityRepository.copyPlannedExercises] (a plan, via
 * [PlannedExercise] with no side) and [asPlanned] (a past workout, via one that already names a
 * side). Computing this twice — once in the repository's write path, once wherever a draft
 * needed the same list before anything is written — is exactly the shape of duplication that
 * has cost this app defects fixed in one of the two places and not the other; this exists so
 * there is only the one place left to fix.
 *
 * [refOf] and [restFallback] are handed in rather than looked up here because resolving them
 * needs a database or a loaded [xyz.oleolegka.gachimuchi.ui.UiState] — Android things this pure
 * function is deliberately kept away from, the same rule the rest of `domain/` follows.
 *
 * ── Which rest wins ──────────────────────────────────────────────────────────────
 * [PlannedExercise.restSec] when it names one — a plan's own rest for THAT session, or a past
 * workout's own rest for THAT card — because a rest sitting next to an exercise on the SOURCE is
 * a statement about that source. Failing that, [restFallback], which is `restHintSec`'s usual
 * order (chosen, then measured, then the default) at every call site this has today.
 *
 * ── Fanning a one-sided exercise into two cards ─────────────────────────────────
 * Only when [PlannedExercise.side] is null AND the catalog currently flags the exercise
 * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.oneSided] — a plan says nothing about a side,
 * so this is the one place that decides it, off the catalog's CURRENT answer (the plan predates
 * the flag being changed just as easily as it postdates it, and there is no better fact to ask).
 * An entry that already NAMES a side (see [PlannedExercise.side]) is never fanned: it already IS
 * one card of an already-split pair, and fanning it again would double it.
 */
fun resolvedCards(
    planned: List<PlannedExercise>,
    refOf: (Long) -> ExerciseRef?,
    restFallback: (ExerciseRef?) -> Int,
): List<DraftCard> = planned.flatMap { entry ->
    val ref = refOf(entry.exerciseId)
    val rest = entry.restSec?.takeIf { it >= MIN_STEP_SEC } ?: restFallback(ref)
    when {
        entry.side != null -> listOf(DraftCard(entry.exerciseId, rest, entry.side))
        ref?.oneSided == true -> listOf(
            DraftCard(entry.exerciseId, rest, HoldSide.LEFT),
            DraftCard(entry.exerciseId, rest, HoldSide.RIGHT),
        )
        else -> listOf(DraftCard(entry.exerciseId, rest))
    }
}

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
 * exercise has a SCHEDULE at all. That inference is right for repeaters and wrong for a
 * maximum-weight hang, which carries a protocol only because §12-A makes it part of hangboard
 * identity — which is precisely why the column can override it.
 *
 * The inference used to read the work:rest PAIR instead of the schedule, and that quietly
 * excluded the strictest exercises there are (§18.15): a schedule whose opening block has no
 * rest of its own — because the pause in it comes later — has no pair to read, so it inferred
 * "not led by the protocol" for an exercise that is nothing BUT its protocol.
 */
fun ledByProtocol(exercise: ExerciseRef): Boolean =
    exercise.ledByProtocolFlag ?: (exercise.scheduleKind != ScheduleKind.FREE)
