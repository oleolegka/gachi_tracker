package xyz.oleolegka.gachimuchi.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.LOCAL_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.EntryAmended
import xyz.oleolegka.gachimuchi.domain.EntryDeleted
import xyz.oleolegka.gachimuchi.domain.ExerciseDeleted
import xyz.oleolegka.gachimuchi.domain.TYPE_EXERCISE_DELETED
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.journalView
import xyz.oleolegka.gachimuchi.domain.MIN_STEP_SEC
import xyz.oleolegka.gachimuchi.domain.PREPARE_DEFAULT_SEC
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.SetCancel
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.TYPE_ENTRY_AMENDED
import xyz.oleolegka.gachimuchi.domain.TYPE_ENTRY_DELETED
import xyz.oleolegka.gachimuchi.domain.TYPE_HOLD_SET
import xyz.oleolegka.gachimuchi.domain.TYPE_STRENGTH_SET
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.TYPE_SET_CANCEL
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_EXERCISE_ADDED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_EXERCISE_FINISHED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_FINISHED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_STARTED
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.formFromEvent
import xyz.oleolegka.gachimuchi.domain.toJsonObject
import xyz.oleolegka.gachimuchi.domain.Workout
import xyz.oleolegka.gachimuchi.domain.WorkoutExerciseAdded
import xyz.oleolegka.gachimuchi.domain.WorkoutExerciseFinished
import xyz.oleolegka.gachimuchi.domain.WorkoutFinished
import xyz.oleolegka.gachimuchi.domain.WorkoutStarted
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.cardKey
import xyz.oleolegka.gachimuchi.domain.bodyweightAt
import xyz.oleolegka.gachimuchi.domain.ExerciseIdentity
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
import xyz.oleolegka.gachimuchi.domain.OrderedCard
import xyz.oleolegka.gachimuchi.domain.OrderedExercise
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_ORDER_SET
import xyz.oleolegka.gachimuchi.domain.WorkoutOrder
import xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey
import xyz.oleolegka.gachimuchi.domain.exerciseLink
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.wantsBodyweightSnapshot
import xyz.oleolegka.gachimuchi.domain.withBodyweightSnapshot
import xyz.oleolegka.gachimuchi.domain.openWorkout
import xyz.oleolegka.gachimuchi.domain.openWorkoutRow
import xyz.oleolegka.gachimuchi.domain.payloadJson
import xyz.oleolegka.gachimuchi.domain.restHintSec
import xyz.oleolegka.gachimuchi.domain.toPayload
import xyz.oleolegka.gachimuchi.domain.toSlot as draftToSlot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The single point of writing to and reading from the journal. The domain reducers live
 * in `domain/Journal.kt` and work with [JournalEvent] — the repository is responsible
 * only for "fetch the events" and "append an event"; there is no domain logic here.
 */
class ActivityRepository(private val db: AppDatabase) {

    /**
     * The program library, reused rather than re-wrapping `db.programs()` a second time — see
     * the find-or-create-protocol-program logic below, and [toRef], for what this is for.
     */
    private val programRepo = ProgramRepository(db)

    /** Log time (ts) — second precision, same as on the server (`db._now`). */
    private fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    /**
     * A journal row, stamped with everything the schema wants to know about WHEN it was written
     * and WHICH DAY it is about.
     *
     * ONE FACTORY FOR EVERY APPEND, on the same grounds [record] gives for attaching the
     * workout here rather than at the call sites: there are seven places in this class that
     * write to the journal, and a row that arrived with its time columns half filled in would be
     * a row nothing could sort — found years later, on the one device holding the history.
     *
     * The day is read back out of the payload rather than passed in, so the column cannot
     * disagree with the JSON beside it. One consequence worth naming: an AMENDMENT names no day
     * of its own (its payload is a target and a patch), so it gets a null `op_date` even when the
     * patch inside it moves an entry to another day — see [EventEntity.opDate], which is where
     * the readers are told not to trust the column across a correction.
     *
     * [occurredTs] defaults to "now" — this row IS its own training, freshly recorded — and
     * [writeNewVersion] is the one caller that overrides it, with the value it is INHERITING
     * from the row being superseded rather than a fresh instant; see [EventEntity.occurredTs].
     */
    private fun event(
        type: String,
        payload: String,
        workoutId: Long? = null,
        workoutUid: String? = null,
        occurredTs: String? = null,
    ): EventEntity {
        val written = WriteTime.now()
        return EventEntity(
            ts = written.local,
            type = type,
            payload = payload,
            workoutId = workoutId,
            workoutUid = workoutUid,
            opDate = opDateOfPayload(payload),
            tsUtc = written.utc,
            tzOffsetMin = written.offsetMin,
            occurredTs = occurredTs ?: written.local,
        )
    }

    val events: Flow<List<JournalEvent>> =
        db.events().observeAll().map { rows -> rows.map { it.toJournalEvent() } }

    val exercises: Flow<List<ExerciseEntity>> = db.exercises().observeAll()

    /**
     * The plan, each slot carrying what it is meant to consist of.
     *
     * Two tables combined here rather than left for the screens to join, so that "a slot"
     * means the same thing everywhere: a screen holding a [Slot] can read its composition
     * without a second lookup, and there is no half-loaded slot to forget about. Both flows
     * are live, so adding an exercise to a plan reaches the calendar the same way renaming
     * the slot does.
     */
    val slots: Flow<List<Slot>> = combine(
        db.slots().observeAll(),
        db.slots().observeExercises(),
    ) { rows, planned -> assembleSlots(rows, planned) }

    suspend fun allEvents(): List<JournalEvent> = db.events().all().map { it.toJournalEvent() }

    suspend fun eventCount(): Int = db.events().count()

    /**
     * Appends an activity form to the journal, filed under the workout in progress if there
     * is one. Returns the event id.
     *
     * The attachment happens HERE rather than at the call sites on purpose: every screen that
     * logs anything goes through this one method, and a screen that forgot to pass the
     * workout would write a set that silently falls out of the workout it was done in. The
     * cost is that recording folds the journal to find the open workout — at personal scale
     * (thousands of rows) that is nothing, and it keeps one definition of "which workout is
     * open" instead of a second one written in SQL that would drift from the domain's.
     *
     * [attachToWorkout] = false is the caller saying "this one is on its own". It exists
     * because the app now offers "log a single entry" as a thing distinct from training, and
     * without it that offer would be a lie whenever a workout happened to be open: the entry
     * would be filed inside the workout and would show up as part of it. Silence is not
     * available here — the entry lands somewhere either way, and only the caller knows which
     * of the two the user asked for.
     *
     * There used to be a second condition on the attachment, excluding rows written by the
     * demo seed's author id. Every row the app writes is the user's own now, so the author
     * is no longer something a caller can choose: [LOCAL_AUTHOR_ID] is the only value, and
     * the column survives only because the server schema has it (see Entities.kt).
     */
    suspend fun record(
        form: ActivityForm,
        attachToWorkout: Boolean = true,
        /**
         * File it under THIS workout, whatever is open.
         *
         * The screen that is drawing a workout knows which one it is drawing, and that is a
         * better answer than folding the journal for "the open one" — they differ exactly
         * when a FINISHED workout is opened to add the set that was forgotten in the changing
         * room (§13, finishing is a status and not a lock). Without this that set would land
         * in whatever happened to be open instead, which is either another workout or nothing.
         */
        intoWorkoutId: Long? = null,
    ): Long {
        // one read, shared by both consumers, and skipped entirely when neither wants it —
        // a named workout is looked up by id and needs no fold
        val needsEvents = form.wantsBodyweightSnapshot || (attachToWorkout && intoWorkoutId == null)
        val events = if (needsEvents) allEvents() else emptyList()
        val workout: JournalEvent? = when {
            intoWorkoutId != null -> db.events().byId(intoWorkoutId)?.toJournalEvent()
            attachToWorkout -> openWorkoutRow(events)
            else -> null
        }
        // the body-weight snapshot is stamped HERE for the same reason the workout link is:
        // one method sees every write, and a screen that forgot would log a set with no
        // volume at all. See [withBodyweightSnapshot].
        val stamped = form.withBodyweightSnapshot { day -> bodyweightAt(events, day) }
        return db.events().insert(
            event(
                type = stamped.type, payload = stamped.toPayload(),
                // both links, and the uid is the one the reducers believe: see EventEntity
                workoutId = workout?.id, workoutUid = workout?.uid,
            )
        )
    }

    /**
     * Writes the ACTUALLY MEASURED rest onto the previous live set of [exercise], as an
     * amendment — see [xyz.oleolegka.gachimuchi.domain.actualRestSec] for what "actually
     * measured" means and where the number comes from (a rest floor's wall-clock start).
     *
     * ── Why this refuses to be part of [record] ──────────────────────────────────
     * The set this corrects is not the one being written — it is the ONE BEFORE IT, already in
     * the journal. [record] appends; this amends, and folding the two into one method would
     * make one call do two different kinds of write to two different rows, which is exactly the
     * confusion [amendEntry] exists to keep separate from ordinary recording.
     *
     * ── MUST be called before the set that measured it is written ─────────────────
     * "The previous set" is found by asking for the LAST live set of this exercise right now.
     * Call this after the new set has already landed and that search finds the new set instead
     * — the one this call is trying to correct rests BEFORE, not the one it is itself the rest
     * after. See [xyz.oleolegka.gachimuchi.ui.MainViewModel.addSet], the only caller, for the
     * order that keeps this true.
     *
     * Returns the id of the NEW, whole version of that set — see [amendEntry] — or null when
     * [exercise] has no live set yet to amend — its very first set of a session, which is the
     * honest case where there is nothing to correct because nothing was rested for.
     *
     * [side] narrows "the previous set" to the same CARD when the exercise is trained one limb
     * at a time — the left hand's floor measures the pause since the left hand's own last set,
     * and amending whichever hand happens to be more recent in the journal would occasionally
     * attribute the wrong hand's rest to the wrong hand's set. Null matches a set with no side
     * at all, which is every set of an exercise that is not one-sided.
     */
    suspend fun recordActualRest(exercise: ExerciseLink, actualRestSec: Double, side: HoldSide? = null): Long? {
        val target = readActivities(allEvents(), listOf(TYPE_STRENGTH_SET, TYPE_HOLD_SET))
            .lastOrNull {
                it.form.exerciseLink()?.matches(exercise) == true && (it.form as? HoldSet)?.sideOf == side
            }
            ?: return null
        return amendEntry(
            target.id,
            JsonObject(mapOf("rest_after_sec" to JsonPrimitive(actualRestSec))),
        )
    }

    // --- workouts (domain/Workout.kt folds them back out) ---

    /** Today, as the journal writes dates. */
    private fun today(): String = LocalDate.now().toString()

    /** The workout in progress, or null — see [openWorkout] for what "in progress" means. */
    suspend fun currentWorkoutId(): Long? = openWorkoutRow(allEvents())?.id

    suspend fun currentWorkout(): Workout? = openWorkout(allEvents())

    /**
     * Writes "that workout is over".
     *
     * No time is recorded — the end is read off the last set (see [Workout.endTs]) — and
     * nothing is locked: the workout can still be opened and written into afterwards, which is
     * how the set forgotten in the changing room gets in.
     */
    suspend fun finishWorkout(workoutId: Long): Long {
        val uid = db.events().byId(workoutId)?.uid
        return db.events().insert(
            event(
                type = TYPE_WORKOUT_FINISHED,
                payload = payloadJson.encodeToString(WorkoutFinished(workoutId, uid)),
                // the link in the column as well as the payload, so one query finds every row
                // of a workout whatever its type — same as the "exercise added" event
                workoutId = workoutId, workoutUid = uid,
            )
        )
    }

    /**
     * Marks one CARD of a workout done — see [TYPE_WORKOUT_EXERCISE_FINISHED]. The per-card
     * sibling of [finishWorkout]: a status, not a lock, and undone by
     * [unfinishWorkoutExercise] rather than by a re-open, because there is nothing here to
     * re-open.
     *
     * ── ONE write, and why there is no second one ─────────────────────────────────
     * This used to also state a fresh [TYPE_WORKOUT_ORDER_SET] putting the card ahead of the
     * active ones, so that the finished group would read in completion order. It was both
     * redundant and unreachable in its effect: `groupedByCardStatus` already orders that group
     * by the id of the very event written above, and it does so on every fold, so the order
     * this wrote was thrown away the moment it was read back. Two mechanisms deciding one
     * order is a disagreement waiting to happen, and the one that always won is the cheaper.
     *
     * Cheaper by a lot, and on the path a finger is on: producing that order meant folding
     * THE WHOLE JOURNAL ([allEvents] plus [buildWorkout]) to learn the current arrangement of
     * one workout. The journal only grows — deletions are events too — so the cost of tapping
     * "done" grew with every session ever recorded. What is left is two lookups by id and one
     * insert, which is what the whole-workout [finishWorkout] beside it always did.
     */
    suspend fun finishWorkoutExercise(workoutId: Long, exercise: ExerciseLink, side: HoldSide? = null): Long {
        val workoutUid = db.events().byId(workoutId)?.uid
        val exerciseUid = exercise.uid ?: exercise.id?.let { db.exercises().byId(it)?.uid }
        val id = db.events().insert(
            event(
                type = TYPE_WORKOUT_EXERCISE_FINISHED,
                payload = payloadJson.encodeToString(
                    WorkoutExerciseFinished(
                        workoutId = workoutId, exerciseId = exercise.id, exerciseUid = exerciseUid,
                        workoutUid = workoutUid, side = side?.code,
                    )
                ),
                workoutId = workoutId,
                workoutUid = workoutUid,
            )
        )
        return id
    }

    /**
     * Undoes [finishWorkoutExercise]: deletes its "card finished" event, the same reversal
     * every other entry in this app gets ([deleteEntry]). No re-finish event and no restored
     * position beyond whatever the flat order already says — the card rejoins the active
     * group wherever [buildWorkout] now folds it to, which is right where it was left: at the
     * top of the active group, since that is where a card sits immediately after the last one
     * finished before it.
     *
     * Returns the id of the deletion, or null when [eventId] names no row here — the same
     * "nothing to undo" answer [deleteEntry] gives.
     */
    suspend fun unfinishWorkoutExercise(eventId: Long): Long? = deleteEntry(eventId)

    /**
     * Opens a workout and returns its id, which IS the id of the event just written.
     *
     * [opDate] defaults to today and is passed explicitly when old training is being typed
     * up. A workout dated in the past is silent — nothing in it counts anything down; see
     * [WorkoutStarted].
     *
     * [slotId] records which planned session this was started from, when the user picked one.
     * The plan's identity is written beside its number, and the readers believe the identity —
     * see [WorkoutStarted]. A number naming a slot this database does not hold writes no uid,
     * which is the honest answer rather than an invented one.
     *
     * [name] is what to call this workout, and it is written into the event as a SNAPSHOT. A
     * caller that passes none and names a plan gets the plan's name as it reads RIGHT NOW —
     * copied once, here, so that editing the plan next month leaves this workout alone. A
     * workout with neither is nameless, which is a state the screens are built for.
     */
    suspend fun startWorkout(
        opDate: String = today(),
        slotId: Long? = null,
        name: String? = null,
    ): Long {
        /*
         * THE FORGOTTEN ONE IS CLOSED ON THE WAY PAST, silently. Nobody presses "finish"
         * reliably, and the alternative to closing it here is two workouts open at once —
         * after which "the one in progress" has to guess, which is what the midnight rule
         * used to do and what §13 replaced. Silent because there is nothing to decide: the
         * user has just said, by starting this one, that the previous one is over.
         */
        openWorkoutRow(allEvents())?.let { finishWorkout(it.id) }
        val slot = slotId?.let { db.slots().byId(it) }
        return db.events().insert(
            event(
                type = TYPE_WORKOUT_STARTED,
                payload = payloadJson.encodeToString(
                    WorkoutStarted(
                        opDate = opDate,
                        slotId = slotId,
                        slotUid = slot?.uid,
                        name = (name ?: slot?.name)?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
        )
    }

    /**
     * Puts an exercise into a workout with a chosen rest, before any set of it exists — or, for
     * one CARD of an exercise trained one limb at a time, [side] says which.
     *
     * Two writes, and they are two different facts. The journal event says "this exercise is
     * part of THAT workout, at this rest" and is history. The catalog column says "this is
     * the rest I want for this exercise from now on" and is the answer the next workout will
     * be offered. Neither can be derived from the other: the workout needs to keep the rest
     * it was actually run at even after the preference changes.
     *
     * The workout link is written into the COLUMN as well as the payload, so that one query
     * finds everything belonging to a workout regardless of event type — see
     * [WorkoutExerciseAdded] for why the payload carries it too.
     *
     * ONE CARD PER CALL. A one-sided exercise's two cards are two calls — see
     * [copyPlannedExercises] and [xyz.oleolegka.gachimuchi.ui.screens.WorkoutLogScreen] for the
     * two places that fan out to both — because a card being touched is what the caller actually
     * knows: adding the exercise fresh means both, changing one card's own rest means one.
     */
    suspend fun addExerciseToWorkout(
        workoutId: Long,
        exerciseId: Long,
        restSec: Int,
        side: HoldSide? = null,
    ): Long {
        val workoutUid = db.events().byId(workoutId)?.uid
        val exerciseUid = db.exercises().byId(exerciseId)?.uid
        val id = db.events().insert(
            event(
                type = TYPE_WORKOUT_EXERCISE_ADDED,
                payload = payloadJson.encodeToString(
                    WorkoutExerciseAdded(
                        workoutId = workoutId, exerciseId = exerciseId, restSec = restSec,
                        workoutUid = workoutUid, exerciseUid = exerciseUid, side = side?.code,
                    )
                ),
                workoutId = workoutId,
                workoutUid = workoutUid,
            )
        )
        setDefaultRest(exerciseId, restSec)
        return id
    }

    /**
     * Copies a plan's composition into a workout, in order, and hands back the rows written.
     *
     * ── A COPY, never a reference (§13.7) ───────────────────────────────────────
     * The plan is editable and the facts are not. Rewriting a slot next month must not
     * rewrite what a workout done in August consisted of, so the exercises are written INTO
     * the workout as ordinary "exercise added" events — the same events the user's own taps
     * produce, indistinguishable afterwards, which is also why nothing downstream had to
     * learn about plans.
     *
     * ── Which rest wins ─────────────────────────────────────────────────────────
     * The one ON THE PLAN when the plan names one, because a rest written next to an exercise
     * in a planned session is a statement about THAT session. Failing that, [restHintSec]
     * answers in its usual order: what the user last chose for the exercise, then what the
     * journal says they actually rested, then the configured default. §13.8 left this open;
     * this is the answer, and it is the only order in which the more specific statement wins.
     *
     * ── Two things it deliberately does anyway ──────────────────────────────────
     * A planned exercise that is no longer in the catalog is still added: the workout should
     * consist of what was planned, and a card for an exercise this phone cannot build a form
     * for is already a state the screen handles. And [addExerciseToWorkout] writes the rest
     * onto the catalog row as well, so a rest that came off the PLAN becomes the exercise's
     * remembered answer — which is right when the plan is where the user last thought about
     * it, and is a side effect worth knowing about either way.
     *
     * Reads the slot's rows directly rather than through `plannedExercises` over the whole
     * plan: same list, one query, and this path only ever holds an id.
     *
     * ── A one-sided exercise arrives with both its cards, here as much as from the picker ──
     * The plan itself says nothing about a side — that question is out of scope for the plan
     * editor (§13.7 is about composition and rest, not about hands) — but the workout it lands
     * in is the same kind of workout a manual "add exercise" produces, and that one always gets
     * two cards for an exercise the catalog flags [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.oneSided].
     * So this fans out the same way, at the same rest, rather than leaving a plan-started
     * workout with the one card the picker path would never produce.
     */
    suspend fun copyPlannedExercises(
        workoutId: Long,
        slotId: Long,
        settings: TimerSettings,
    ): List<Long> {
        val planned = slotExercises(slotId)
        if (planned.isEmpty()) return emptyList()
        val events = allEvents()
        return planned.flatMap { entry ->
            val ref = exercise(entry.exerciseId)?.let { toRef(it) }
            val rest = entry.restSec?.takeIf { it >= MIN_STEP_SEC } ?: restHintSec(settings, events, ref)
            if (ref?.oneSided == true) {
                listOf(
                    addExerciseToWorkout(workoutId, entry.exerciseId, rest, HoldSide.LEFT),
                    addExerciseToWorkout(workoutId, entry.exerciseId, rest, HoldSide.RIGHT),
                )
            } else {
                listOf(addExerciseToWorkout(workoutId, entry.exerciseId, rest))
            }
        }
    }

    /**
     * States the order the exercises of a workout are to be done in.
     *
     * ── One row per drop, carrying the WHOLE order ──────────────────────────────
     * Not "move this one after that one" — see [TYPE_WORKOUT_ORDER_SET] for the reasoning, of
     * which the short form is that a full list merges by "last writer wins" and a sequence of
     * moves does not. It is written on the DROP and not on every swap the finger passes
     * through, so dragging one card the length of the list is one event and not six.
     *
     * The identity of each exercise is filled in from the catalog where the journal did not
     * already carry one, so the row is as portable as this phone can make it. An exercise this
     * database has no row for is still written, by number — it is in the workout, and leaving it
     * out of the order would quietly move it to the bottom of the list.
     *
     * Returns the id of the row written, or null when [order] is empty: an order that names
     * nothing states nothing, and writing it would only mean the next reader has one more row to
     * fold to reach the same answer.
     *
     * [order] names CARDS, not exercises — see [OrderedCard] — so that dragging the left card of
     * a one-sided exercise past its own right card states a whole order the way every other drag
     * does, rather than trying to move "the exercise" as if it had only one place to be.
     */
    suspend fun setWorkoutExerciseOrder(workoutId: Long, order: List<OrderedCard>): Long? {
        if (order.isEmpty()) return null
        val workoutUid = db.events().byId(workoutId)?.uid
        val entries = order.map { card ->
            val link = card.exercise
            OrderedExercise(
                exerciseId = link.id,
                exerciseUid = link.uid ?: link.id?.let { db.exercises().byId(it)?.uid },
                side = card.side?.code,
            )
        }
        return db.events().insert(
            event(
                type = TYPE_WORKOUT_ORDER_SET,
                payload = payloadJson.encodeToString(
                    WorkoutOrder(workoutId = workoutId, order = entries, workoutUid = workoutUid)
                ),
                // in the column as well as the payload, so one query still finds every row of a
                // workout whatever its type — same as "exercise added" and "finished"
                workoutId = workoutId,
                workoutUid = workoutUid,
            )
        )
    }

    /** Remembers the rest chosen for an exercise — see [ExerciseDao.setDefaultRest]. */
    suspend fun setDefaultRest(exerciseId: Long, restSec: Int?) =
        db.exercises().setDefaultRest(exerciseId, restSec)

    /** "Run this by its protocol" / "just count the rest" / null to go back to inferring it. */
    suspend fun setLedByProtocol(exerciseId: Long, ledByProtocol: Boolean?) =
        db.exercises().setLedByProtocol(exerciseId, ledByProtocol)

    /** "This one is done one hand at a time" — see [ExerciseDao.setOneSided]. */
    suspend fun setOneSided(exerciseId: Long, oneSided: Boolean) =
        db.exercises().setOneSided(exerciseId, oneSided)

    /** What share of body weight this exercise lifts — see [ExerciseDao.setBodyweightShare]. */
    suspend fun setBodyweightShare(exerciseId: Long, share: Double?) =
        db.exercises().setBodyweightShare(exerciseId, share)

    /**
     * Points an exercise at a picture, or takes the picture away (null) — see
     * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.pictureId].
     *
     * A one-column write, like [setOneSided] beside it: the FILE this id names is
     * [xyz.oleolegka.gachimuchi.data.ExercisePictureStore]'s concern, not this repository's —
     * it needs a `Context` this class is deliberately never handed (see
     * [xyz.oleolegka.gachimuchi.data.GalleryStore] for the same split). The caller is expected
     * to have already copied the new file in, and to
     * remove the old one after this returns; see `ui/components/ExerciseEditor.kt`, the only
     * caller, for where that happens.
     */
    suspend fun setPicture(exerciseId: Long, pictureId: String?) =
        db.exercises().setPictureId(exerciseId, pictureId)

    /**
     * Cancels a set: the journal is append-only, so a REVERSING event is written while
     * the set itself stays in the history (the reducers exclude it).
     *
     * KEPT AS IT WAS, still writing the old event type, even though [deleteEntry] says the same
     * thing about any event and is what new callers should use. Two reasons: every journal in
     * existence is full of `set_cancel` and the readers have to understand it regardless, and a
     * method that quietly started writing a different event type would make the rows this app
     * wrote before and after the change distinguishable for no benefit to anyone.
     */
    suspend fun cancelSet(eventId: Long): Long =
        db.events().insert(
            event(
                type = TYPE_SET_CANCEL,
                // both links: the uid is what the reducers read, the number is what a build
                // older than schema version 9 would look for
                payload = payloadJson.encodeToString(
                    SetCancel(cancels = eventId, cancelsUid = db.events().byId(eventId)?.uid)
                ),
            )
        )

    // --- correcting and removing what was written (domain/Amendments.kt folds it) ---

    /**
     * Removes ANY event from every reading of the journal — a set, a workout started by
     * mistake, an exercise added to the wrong one, or an earlier deletion.
     *
     * Nothing is deleted from the table: this appends an event naming the one to stop reading,
     * and [journalView] is what turns the pair into an answer. Deleting a deletion is therefore
     * how something comes back, and it needs no special method — this one, pointed at the
     * deletion's own row.
     *
     * Returns the id of the event written, or null when [eventId] names no row here. Null
     * rather than a throw because the only way to reach this with a stale id is a screen that
     * was drawn before somebody removed the row, and a tap arriving one recomposition late
     * should do nothing rather than crash.
     */
    suspend fun deleteEntry(eventId: Long): Long? {
        val target = currentVersionOf(eventId) ?: return null
        return db.events().insert(
            event(
                type = TYPE_ENTRY_DELETED,
                payload = payloadJson.encodeToString(EntryDeleted(targetUid = target.uid)),
            )
        )
    }

    /**
     * The row [eventId] names, resolved forward to whatever is CURRENTLY live if it has since
     * been corrected.
     *
     * ── Why a numeric id is not enough on its own any more ──────────────────────
     * A caller can hold this id from before the row it names was corrected — a workout screen
     * that opened workout #7 and kept that number around is the case that matters, because
     * [renameWorkout] and a date correction both write a NEW start row under a NEW id (see
     * domain/Amendments.kt's header, "A correction is now a whole new row"). Acting on the OLD
     * id directly — as [db.events][xyz.oleolegka.gachimuchi.data.db.EventDao.byId] alone would,
     * since the old row is never removed from the table — would supersede the row that is
     * ALREADY superseded, leaving the truly current one untouched by the very action aimed at
     * it. [xyz.oleolegka.gachimuchi.domain.JournalView.canonicalUid] is the fold that already
     * knows the current end of that chain; this is [deleteEntry] and [amendEntry] asking it
     * before deciding what "the row named by [eventId]" means.
     */
    private suspend fun currentVersionOf(eventId: Long): JournalEvent? {
        val raw = db.events().byId(eventId)?.toJournalEvent() ?: return null
        val events = allEvents()
        val canonical = journalView(events).canonicalUid(raw.uid)
        return if (canonical == raw.uid) raw else events.firstOrNull { it.uid == canonical } ?: raw
    }

    /**
     * Corrects the values of an event already written: [fields] is a fragment of its payload
     * carrying only what changed onto the CURRENT payload, and the result is written as a whole
     * new row — see domain/Amendments.kt's header for the model this is half of.
     *
     * ── What it refuses, and why refusing is the point ──────────────────────────
     * A patch that leaves the payload UNREADABLE. The merged result is parsed here, before
     * anything is written, exactly as the readers will parse it. Without this a correction of
     * "6" to "0" reps would be accepted, and the entry would then vanish from every screen — a
     * deletion the user never asked for, arriving disguised as an edit. Service events
     * ([TYPE_WORKOUT_STARTED], [TYPE_WORKOUT_EXERCISE_ADDED]) are checked the same way against
     * their own payload classes.
     *
     * There is deliberately no check here on WHICH keys [fields] names any more — a full
     * rewrite protects nothing an earlier version of this method needed to refuse, because the
     * new row it produces is the whole truth about the entry rather than a patch riding on the
     * old row's identity. The two callers this app ships ([recordActualRest], [renameWorkout])
     * never touch an exercise or a workout link through here, so this is exercised only by
     * whichever value they DO name; a caller that handed in one of those keys anyway would
     * simply move the row the way [amendEntry] taking a whole [ActivityForm] always could.
     *
     * Returns the id of the NEW version — the row every future correction and lookup should
     * name — or null when [eventId] names no row (see [deleteEntry]).
     */
    suspend fun amendEntry(eventId: Long, fields: JsonObject): Long? {
        val target = currentVersionOf(eventId) ?: return null
        require(fields.isNotEmpty()) { "an amendment with no fields corrects nothing" }
        // parsed exactly as a reader would, so a patch that would make the entry unreadable
        // fails here instead of making it disappear later
        val merged = mergedAndCheckedPayload(target.type, target.payload, fields)
        return writeNewVersion(target, target.type, merged)
    }

    /**
     * Names a workout that has already been started, or takes its name away.
     *
     * ── A correction like any other, which is why there is no new event ─────────
     * The name lives in the start event's payload, and the start event is an event: an
     * amendment naming it is exactly the mechanism [amendEntry] already provides, and
     * [journalView] already folds. Inventing a `workout_renamed` type would be a second shape
     * for every reader to learn, for a change that is a value in a payload.
     *
     * The snapshot rule is untouched. The name is still what THIS workout is called and never
     * a lookup of the plan it came from (see [WorkoutStarted]); renaming a plan still leaves
     * every workout ever started from it alone. What this adds is that the snapshot can be
     * corrected — which is the same thing every other value in this journal can now do.
     *
     * A blank name is a REMOVAL of the name and not a name made of spaces: the payload gets a
     * null, the readers fall back to the time of day, and a workout goes back to being
     * nameless the way it started. Returns the id of the workout's new, whole version — see
     * [amendEntry] — or null when [workoutId] names no row here.
     */
    suspend fun renameWorkout(workoutId: Long, name: String?): Long? {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        return amendEntry(
            workoutId,
            JsonObject(mapOf("name" to (clean?.let { JsonPrimitive(it) } ?: JsonNull))),
        )
    }

    /**
     * Corrects an activity entry from the whole form the editor is holding, by writing it as a
     * whole new row — see domain/Amendments.kt's header for the model this is half of.
     *
     * The convenience the screens will actually use: an edit dialog has a filled-in form, not a
     * diff, and [updated] is written EXACTLY as it stands — including the exercise it names,
     * unlike the version of this method that existed before the full-version model. That used
     * to matter: a patch riding on the OLD row's identity that changed which exercise it named
     * would rewrite what every past reading of that one row had meant. It does not any more —
     * the old row is untouched forever, and a new row naming a different exercise is simply
     * what "delete this set and log it again, correctly" has always looked like from the
     * outside (see [TYPE_ENTRY_AMENDED]'s own KDoc). [EntryEditorDialog] still never offers to
     * change it, because moving a set is not what an edit dialog is for; this is what stopped
     * making that a rule the model itself enforces.
     */
    suspend fun amendEntry(eventId: Long, updated: ActivityForm): Long? {
        val target = currentVersionOf(eventId) ?: return null
        return writeNewVersion(target, updated.type, updated.toPayload())
    }

    /**
     * The write both [amendEntry] overloads end in: a whole new row of [type]/[payload] — in
     * the same workout as [target], since a correction does not change which workout an entry
     * belongs to, and carrying forward WHEN IT HAPPENED rather than stamping the correction's
     * own moment — and a [TYPE_ENTRY_DELETED] naming [target] as superseded by it.
     *
     * ── [occurredTs] is INHERITED, never re-stamped ──────────────────────────────
     * [target]'s own [xyz.oleolegka.gachimuchi.data.db.EventEntity.occurredTs] is copied onto
     * the new row unchanged (falling back to [target]'s own `ts` for a row from before that
     * column existed — the same fallback [xyz.oleolegka.gachimuchi.domain.happenedAt] reads
     * everywhere else). This is what keeps a corrected set from jumping to the end of its
     * session or workout on screen: the JOURNAL position of the new row is the moment of the
     * correction, but the DISPLAY order (domain/Session.kt, domain/Workout.kt) is sorted by
     * this instead, and it never moves across however many times a row is corrected again.
     *
     * The new row is inserted FIRST and its uid is already known before either insert happens
     * ([xyz.oleolegka.gachimuchi.data.db.EventEntity.uid] is generated when the row is built,
     * not by the table), so the marker names a real row from the moment it exists — there is no
     * window where the target is dead and nothing yet stands in its place.
     */
    private suspend fun writeNewVersion(target: JournalEvent, type: String, payload: String): Long {
        val newVersion = event(
            type = type, payload = payload, workoutId = target.workoutId, workoutUid = target.workoutUid,
            occurredTs = target.occurredTs ?: target.ts,
        )
        val newId = db.events().insert(newVersion)
        db.events().insert(
            event(
                type = TYPE_ENTRY_DELETED,
                payload = payloadJson.encodeToString(
                    EntryDeleted(targetUid = target.uid, successorUid = newVersion.uid)
                ),
            )
        )
        return newId
    }

    /**
     * [fields] laid over [payload], parsed exactly as a reader would so a patch that would
     * leave the entry unreadable fails here rather than later. Sets are checked through
     * [formFromEvent], which is the reader; the two service events have no entry there and are
     * checked against the classes their own readers use. Returns the merged payload text.
     */
    private fun mergedAndCheckedPayload(type: String, payload: String, fields: JsonObject): String {
        val base = payloadJson.parseToJsonElement(payload) as? JsonObject
            ?: throw IllegalArgumentException("event $type has no readable payload to amend")
        val merged = JsonObject(LinkedHashMap(base).apply { putAll(fields) })
        val text = payloadJson.encodeToString(JsonObject.serializer(), merged)
        when (type) {
            TYPE_WORKOUT_STARTED -> payloadJson.decodeFromString<WorkoutStarted>(text)
            TYPE_WORKOUT_EXERCISE_ADDED -> payloadJson.decodeFromString<WorkoutExerciseAdded>(text)
            TYPE_WORKOUT_ORDER_SET -> payloadJson.decodeFromString<WorkoutOrder>(text)
            else -> formFromEvent(type, text)
        }
        return text
    }

    // --- exercise catalog (§11) ---

    suspend fun allExercises(): List<ExerciseEntity> = db.exercises().all()

    suspend fun exercise(id: Long): ExerciseEntity? = db.exercises().byId(id)

    /**
     * The exercise with this IDENTITY, creating it if there is none: name, form and work:rest
     * protocol together (see [ExerciseIdentity]), not the name on its own.
     *
     * ── What this used to do, and what it cost ─────────────────────────────────
     * It looked for a row with the same normalized NAME and returned it, dropping the protocol
     * it had been handed without a word. §12-A says the protocol is part of what a hangboard
     * exercise IS, so adding hangs on a different protocol while another "Hangs" existed handed
     * back the old row: two exercises became one, every set went into one history, and nothing
     * anywhere said so.
     *
     * Now a different protocol or a different form is a DIFFERENT EXERCISE and gets a row of
     * its own — which is also what the UNIQUE index added in schema version 15 enforces, so
     * this lookup and the constraint cannot disagree.
     *
     * ── What a found row does and does not pick up ──────────────────────────────
     * Nothing about its identity, because a row can only be found by matching it exactly.
     *
     * [defaultRestSec] is written when it is given, because it is a PREFERENCE: "I want two
     * and a half minutes between these" replaces the previous answer to the same question by
     * design and strands no history.
     *
     * A HIDDEN row that is found comes back into the pickers. Hiding says "do not offer me
     * this any more"; typing its name and logging a set says the opposite, and leaving it
     * hidden would mean an exercise that is being trained and cannot be found in the list to
     * train it again.
     */
    suspend fun ensureExercise(
        name: String,
        form: ExerciseForm,
        workSec: Double? = null,
        restSec: Double? = null,
        defaultRestSec: Int? = null,
    ): Long {
        val program = resolveOrCreateProtocolProgram(name, workSec, restSec)
        val key = exerciseIdentityKey(name, form.code, program?.uid)
        db.exercises().byIdentityKey(key)?.let { found ->
            if (defaultRestSec != null) setDefaultRest(found.id, defaultRestSec)
            if (found.hidden) setHidden(found.id, false)
            linkProtocolProgramIfUnclaimed(program, found.id)
            return found.id
        }
        val id = db.exercises().insert(
            ExerciseEntity(
                name = name, form = form.code, createdAt = now(),
                protocolProgramId = program?.id,
                defaultRestSec = defaultRestSec,
                identityKey = key,
            )
        )
        linkProtocolProgramIfUnclaimed(program, id)
        return id
    }

    /**
     * Corrects what an exercise is CALLED. The protocol and the form are both absent — see
     * below for the protocol, and the class-level note above [editExercise] used to carry for
     * the form (that reasoning is unchanged: a payload's shape must not move under history that
     * was already written in it).
     *
     * ── Why the protocol left this method entirely ───────────────────────────────
     * It used to be a parameter here, resolved through the same find-or-create logic
     * [ensureExercise] uses and then repointed onto the row — "correcting a typo in the
     * catalog's claim about itself". The owner's rule closes that door: "such a thing cannot
     * happen: it breaks the statistics. If yesterday it was one protocol and today another,
     * that is a NEW exercise." An existing exercise's protocol — INCLUDING having none at all —
     * is therefore not a value this method can be handed; [ExerciseEntity.protocolProgramId] is
     * carried across UNCHANGED, exactly as [stored] already has it.
     *
     * ── What this does to the history, said out loud ───────────────────────────
     * Nothing is rewritten, and the sets do not move. Every set names its exercise by uid, so
     * they all stay with this row and its records, charts and totals are computed over the same
     * sets as before — same as it always was, minus the one door this method used to open onto
     * the protocol underneath them.
     *
     * Returns what happened, because the identity is unique in the schema and "there is
     * already an exercise like that" is a normal answer the user has to be given rather than a
     * crash — a rename CAN still collide: two exercises created on the identical protocol pair
     * share one library program (see `resolveOrCreateProtocolProgram`'s "found" rule), and
     * renaming one to the other's name collides on (name, form, program uid) exactly as it
     * always could.
     */
    suspend fun editExercise(id: Long, name: String): ExerciseEdit {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ExerciseEdit.Blank
        val stored = db.exercises().byId(id) ?: return ExerciseEdit.Gone
        val programUid = stored.protocolProgramId?.let { programRepo.programById(it)?.uid }
        val key = exerciseIdentityKey(trimmed, stored.form, programUid)
        // asked before it is attempted, so the ordinary collision is an answer rather than a
        // caught exception; the constraint is still there underneath as the thing that makes
        // the answer true
        db.exercises().byIdentityKey(key)?.let { clash ->
            if (clash.id != id) return ExerciseEdit.Taken(clash.name)
        }
        val touched = db.exercises().editIdentity(
            id = id, name = trimmed, programId = stored.protocolProgramId,
            identityKey = key,
        )
        return if (touched == 0) ExerciseEdit.Gone else ExerciseEdit.Saved
    }

    // --- the protocol program a plain work:rest pair resolves to (requirement 3) ----------
    //
    // The exercise create/edit dialogs still ask for two plain numbers, Work and Rest — see
    // ui/screens/ExercisePicker.kt's CreateExerciseForm and ui/components/ExerciseEditor.kt's
    // EditExerciseDialog, neither of which changed shape for this. All of the "an exercise's
    // protocol is a library program now" logic lives here instead, so that a person filling in
    // two boxes gets a program in the library for free and never sees one being built.

    /**
     * Finds an existing library program shaped exactly like the minimal one-block-one-group
     * protocol [workSec]/[restSec] would produce, or creates one. Null when the pair is not a
     * protocol at all — same positivity/pairing rule [ExerciseRef.protocol] applies, and no
     * program is invented for an exercise with no protocol.
     *
     * "Found" matches on SHAPE AND NUMBERS ONLY, not on name or category: a hand-authored
     * program that happens to have exactly one group, one block, `repeats == 1` on both, and
     * this work/rest is a legitimate match to reuse, the same way this app already accepts
     * value-based coincidences elsewhere. No hidden "auto-generated" flag is written — that
     * would make a perfectly good program a second-class citizen of a library the owner wants
     * to stay fully-featured.
     */
    private suspend fun resolveOrCreateProtocolProgram(
        exerciseName: String,
        workSec: Double?,
        restSec: Double?,
    ): WorkoutProgram? {
        if (workSec == null || restSec == null || workSec <= 0 || restSec <= 0) return null
        val workInt = workSec.toInt()
        val restInt = restSec.toInt()
        programRepo.allPrograms().firstOrNull { it.isMinimalProtocol(workInt, restInt) }?.let { return it }
        val id = programRepo.save(
            WorkoutProgram(
                name = "$exerciseName protocol",
                prepareSec = PREPARE_DEFAULT_SEC,
                category = "Protocols",
                groups = listOf(
                    ProgramGroup(
                        name = exerciseName,
                        repeats = 1,
                        blocks = listOf(
                            ProgramBlock(
                                name = exerciseName, workSec = workInt, restSec = restInt, repeats = 1,
                            )
                        ),
                    )
                ),
            )
        )
        return programRepo.programById(id)
    }

    /** Whether [this] is exactly the one-block-one-group shape a plain protocol pair produces. */
    private fun WorkoutProgram.isMinimalProtocol(workSec: Int, restSec: Int): Boolean {
        val group = groups.singleOrNull() ?: return false
        val block = group.blocks.singleOrNull() ?: return false
        return group.repeats == 1 && block.repeats == 1 &&
            block.workSec == workSec && block.restSec == restSec
    }

    /**
     * Claims [program] for [exerciseId] so the "offer to log a set" flow in `RunLogDialog`
     * works for it too — but only when nobody has claimed it yet.
     *
     * [xyz.oleolegka.gachimuchi.data.db.ProgramEntity.exerciseId] is inherently singular, and a
     * program shared by several exercises (two exercises with the same numbers, or one manually
     * pointed at a hand-authored program another exercise already claimed) can only usefully
     * name one of them. Whichever exercise claimed it first keeps the convenience; every other
     * exercise sharing the program still gets its correct identity and history, just not this
     * one perk. Deliberately not fixed here — see this method's callers.
     */
    private suspend fun linkProtocolProgramIfUnclaimed(program: WorkoutProgram?, exerciseId: Long) {
        if (program != null && program.exerciseId == null) programRepo.linkExercise(program.id, exerciseId)
    }

    /**
     * The catalog row as the domain sees it, with its protocol program resolved — see
     * [xyz.oleolegka.gachimuchi.domain.CatalogRow.toRef] for why that pure mapping does not
     * reach for a database itself, and why the resolution happens once, here.
     */
    suspend fun toRef(exercise: ExerciseEntity): ExerciseRef =
        exercise.toRef(exercise.protocolProgramId?.let { programRepo.programById(it) })

    /**
     * Keeps an exercise out of the pickers, or brings it back.
     *
     * NOT a delete — see [ExerciseEntity.hidden] and [deleteExercise] below for the other one. A
     * hidden exercise keeps every set it ever had, goes on counting in the totals it always
     * counted in, stays reachable from the overview, and — unlike a deleted one — its own
     * history is still there to look at; hiding only ever touches the pickers.
     */
    suspend fun setHidden(exerciseId: Long, hidden: Boolean) =
        db.exercises().setHidden(exerciseId, hidden)

    /**
     * Removes an exercise from every reading of the app: its own row in the catalog, and every
     * set, "added to a workout" and "card finished" row that names it.
     *
     * ── One event, not a DELETE ──────────────────────────────────────────────────
     * The catalog row is untouched — still in the `exercises` table forever, exactly as it was
     * — and so is every entry it ever collected. What is written is a [TYPE_EXERCISE_DELETED]
     * event, and [xyz.oleolegka.gachimuchi.domain.journalView] is what turns it into "nowhere to
     * be seen": the same fold that already does this for one entry at a time
     * ([TYPE_ENTRY_DELETED]), extended to cascade from one exercise to everything that names it.
     * See that event's own KDoc in domain/Forms.kt for why this is not a second
     * [ExerciseEntity.hidden] column.
     *
     * Both links are written — [ExerciseEntity.id] and [ExerciseEntity.uid] — so that a row
     * recorded before schema version 10, which may name this exercise by number alone, still
     * folds dead along with everything logged about it since.
     *
     * Reversible the same way every other deletion in this app is: [deleteEntry] pointed at the
     * uid of the event this returns undoes it, with no dedicated "restore" action needed. There
     * is no button for that yet — see the app's own screens for what is actually offered today.
     *
     * Returns the id of the event written.
     */
    suspend fun deleteExercise(exercise: ExerciseEntity): Long =
        db.events().insert(
            event(
                type = TYPE_EXERCISE_DELETED,
                payload = payloadJson.encodeToString(
                    ExerciseDeleted(targetId = exercise.id, targetUid = exercise.uid)
                ),
            )
        )

    // --- calendar slots (§12-B) ---

    suspend fun allSlots(): List<Slot> = assembleSlots(db.slots().all(), db.slots().allExercises())

    suspend fun createSlot(
        name: String,
        atTime: String? = null,
        repeatRule: String,
        anchorDate: String,
    ): Long = db.slots().insert(
        SlotEntity(
            name = name.trim(), atTime = atTime, repeatRule = repeatRule,
            anchorDate = anchorDate, createdAt = now(),
        )
    )

    suspend fun slot(id: Long): Slot? =
        db.slots().byId(id)?.toSlot(db.slots().exercisesOf(id).map { it.toPlanned() })

    /**
     * What one slot is meant to consist of, in order — the read the "start a workout from
     * this session" path needs when it holds an id and not the slot.
     *
     * A slot that does not exist answers with an empty list rather than throwing. Nothing
     * about starting a workout depends on this succeeding: an empty composition is the
     * normal case (see domain/Schedule.kt), so "the slot is gone" and "the slot had nothing
     * in it" deserve the same answer here.
     */
    suspend fun slotExercises(slotId: Long): List<PlannedExercise> =
        db.slots().exercisesOf(slotId).map { it.toPlanned() }

    /**
     * Writes a slot the editor built: an INSERT when [id] is null, an UPDATE of that id
     * otherwise. Returns the slot id, or null when the draft is not storable.
     *
     * The draft is validated here as well as on the screen. That is not belt and braces
     * for its own sake — this is the boundary the database is behind, and a slot with a
     * blank name or an unreadable time would come back out as a row the calendar has to
     * render forever. The screen refuses first (with a message); the repository refuses
     * last (quietly), and neither relies on the other.
     *
     * The composition is written in the same call and REPLACED rather than diffed, exactly
     * as a program's groups are (see ProgramRepository): adding, removing and reordering all
     * become the same two statements, and no path leaves a stale row behind. It follows the
     * refusals — a draft that is not storable writes no exercises either, and neither does
     * an edit of a slot that has been deleted in the meantime.
     */
    suspend fun saveSlot(draft: SlotDraft, id: Long? = null): Long? {
        // aliased on import: this file also declares a SlotEntity.toSlot of its own
        val slot = draft.draftToSlot(id ?: 0L) ?: return null
        val savedId = if (id == null) {
            createSlot(slot.name, slot.atTime, slot.repeatRule, slot.anchorDate)
        } else {
            val touched = db.slots().updateFields(
                id = id,
                name = slot.name,
                atTime = slot.atTime,
                repeatRule = slot.repeatRule,
                anchorDate = slot.anchorDate,
            )
            if (touched == 0) return null
            id
        }
        writeSlotExercises(savedId, slot.exercises)
        return savedId
    }

    /** Replaces a slot's composition. The list order becomes the stored `position`. */
    private suspend fun writeSlotExercises(slotId: Long, exercises: List<PlannedExercise>) {
        db.slots().deleteExercisesOf(slotId)
        if (exercises.isEmpty()) return
        db.slots().insertExercises(
            exercises.mapIndexed { index, planned ->
                SlotExerciseEntity(
                    slotId = slotId,
                    exerciseId = planned.exerciseId,
                    position = index,
                    restSec = planned.restSec,
                )
            }
        )
    }

    /**
     * Deletes one slot — WITH ALL ITS OCCURRENCES, which is not a separate step: the
     * occurrences are computed from this row, so removing it removes the whole series,
     * past days included. Nothing in the journal is affected (see domain/Schedule.kt,
     * `deletionWarning`, which is the text the user confirms).
     *
     * The planned composition goes too, by cascade rather than by a second statement here —
     * see [SlotExerciseEntity]. It has to go: those rows are reachable only through the slot.
     */
    suspend fun deleteSlot(id: Long) = db.slots().deleteByIds(listOf(id))
}

// ExerciseEntity.toRef() has moved to data/CatalogMapping.kt, alongside the rest of the
// catalog row's views (see the KDoc there for why the four were consolidated).

fun EventEntity.toJournalEvent() = JournalEvent(
    id = id, ts = ts, spaceId = spaceId, authorId = authorId, type = type, payload = payload,
    workoutId = workoutId, uid = uid, workoutUid = workoutUid,
    opDate = opDate, tsUtc = tsUtc, tzOffsetMin = tzOffsetMin, occurredTs = occurredTs,
)

fun SlotEntity.toSlot(exercises: List<PlannedExercise> = emptyList()) = Slot(
    id = id, name = name, atTime = atTime, repeatRule = repeatRule, anchorDate = anchorDate,
    exercises = exercises, uid = uid,
)

fun SlotExerciseEntity.toPlanned() = PlannedExercise(exerciseId = exerciseId, restSec = restSec)

/**
 * What came of correcting an exercise — see [ActivityRepository.editExercise].
 *
 * A result rather than an exception or a bare Boolean, because two of these four are things
 * the person typing has to be TOLD, in words that name the exercise they collided with. A
 * Boolean would have made "there is already one of those" and "somebody deleted it while the
 * dialog was open" the same answer, and the right thing to say about them is not the same.
 */
sealed interface ExerciseEdit {
    data object Saved : ExerciseEdit

    /** A name of nothing but spaces. Refused here as well as on screen; see [saveSlot]. */
    data object Blank : ExerciseEdit

    /** The row is not there any more — nothing was written. */
    data object Gone : ExerciseEdit

    /** Another exercise already has this identity. [name] is what it is called. */
    data class Taken(val name: String) : ExerciseEdit
}

/**
 * Slot rows plus their composition rows -> the plan as the domain sees it.
 *
 * The composition arrives already ordered by (slot, position), and [groupBy] preserves that
 * order — which is why nothing sorts again here. A slot with no rows gets an empty list
 * rather than being skipped: an empty composition is the normal state of a plan, not a slot
 * that failed to load.
 */
private fun assembleSlots(
    rows: List<SlotEntity>,
    planned: List<SlotExerciseEntity>,
): List<Slot> {
    val bySlot = planned.groupBy { it.slotId }
    return rows.map { row -> row.toSlot(bySlot[row.id].orEmpty().map { it.toPlanned() }) }
}
