package xyz.oleolegka.gachimuchi.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.LOCAL_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity
import xyz.oleolegka.gachimuchi.domain.AMENDMENT_PROTECTED_KEYS
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.EntryAmended
import xyz.oleolegka.gachimuchi.domain.EntryDeleted
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.MIN_STEP_SEC
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.SetCancel
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.TYPE_ENTRY_AMENDED
import xyz.oleolegka.gachimuchi.domain.TYPE_ENTRY_DELETED
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.TYPE_SET_CANCEL
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_EXERCISE_ADDED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_FINISHED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_STARTED
import xyz.oleolegka.gachimuchi.domain.formFromEvent
import xyz.oleolegka.gachimuchi.domain.toJsonObject
import xyz.oleolegka.gachimuchi.domain.Workout
import xyz.oleolegka.gachimuchi.domain.WorkoutExerciseAdded
import xyz.oleolegka.gachimuchi.domain.WorkoutFinished
import xyz.oleolegka.gachimuchi.domain.WorkoutStarted
import xyz.oleolegka.gachimuchi.domain.bodyweightAt
import xyz.oleolegka.gachimuchi.domain.ExerciseIdentity
import xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey
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

    /** Log time (ts) — second precision, same as on the server (`db._now`). */
    private fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

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
            EventEntity(
                ts = now(), type = stamped.type, payload = stamped.toPayload(),
                // both links, and the uid is the one the reducers believe: see EventEntity
                workoutId = workout?.id, workoutUid = workout?.uid,
            )
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
            EventEntity(
                ts = now(), type = TYPE_WORKOUT_FINISHED,
                payload = payloadJson.encodeToString(WorkoutFinished(workoutId, uid)),
                // the link in the column as well as the payload, so one query finds every row
                // of a workout whatever its type — same as the "exercise added" event
                workoutId = workoutId, workoutUid = uid,
            )
        )
    }

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
            EventEntity(
                ts = now(), type = TYPE_WORKOUT_STARTED,
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
     * Puts an exercise into a workout with a chosen rest, before any set of it exists.
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
     */
    suspend fun addExerciseToWorkout(workoutId: Long, exerciseId: Long, restSec: Int): Long {
        val workoutUid = db.events().byId(workoutId)?.uid
        val exerciseUid = db.exercises().byId(exerciseId)?.uid
        val id = db.events().insert(
            EventEntity(
                ts = now(), type = TYPE_WORKOUT_EXERCISE_ADDED,
                payload = payloadJson.encodeToString(
                    WorkoutExerciseAdded(
                        workoutId = workoutId, exerciseId = exerciseId, restSec = restSec,
                        workoutUid = workoutUid, exerciseUid = exerciseUid,
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
     */
    suspend fun copyPlannedExercises(
        workoutId: Long,
        slotId: Long,
        settings: TimerSettings,
    ): List<Long> {
        val planned = slotExercises(slotId)
        if (planned.isEmpty()) return emptyList()
        val events = allEvents()
        return planned.map { entry ->
            val rest = entry.restSec?.takeIf { it >= MIN_STEP_SEC }
                ?: restHintSec(settings, events, exercise(entry.exerciseId)?.toRef())
            addExerciseToWorkout(workoutId, entry.exerciseId, rest)
        }
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
            EventEntity(
                ts = now(), type = TYPE_SET_CANCEL,
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
        val uid = db.events().byId(eventId)?.uid ?: return null
        return db.events().insert(
            EventEntity(
                ts = now(), type = TYPE_ENTRY_DELETED,
                payload = payloadJson.encodeToString(EntryDeleted(targetUid = uid)),
            )
        )
    }

    /**
     * Corrects the values of an event already written: [fields] is a fragment of its payload
     * carrying only what changed.
     *
     * ── What it refuses, and why refusing is the point ──────────────────────────
     * Two checks, and both throw rather than writing something the readers would have to make
     * the best of:
     *
     *  - a field in [AMENDMENT_PROTECTED_KEYS] — moving a set to another exercise is a deletion
     *    and a new entry, never a correction (see [TYPE_ENTRY_AMENDED]);
     *  - a patch that leaves the payload UNREADABLE. The merged result is parsed here, before
     *    anything is written, exactly as the readers will parse it. Without this a correction
     *    of "6" to "0" reps would be accepted, and the entry would then vanish from every
     *    screen — a deletion the user never asked for, arriving disguised as an edit. Service
     *    events ([TYPE_WORKOUT_STARTED], [TYPE_WORKOUT_EXERCISE_ADDED]) are checked the same
     *    way against their own payload classes.
     *
     * Returns the id of the amendment, or null when [eventId] names no row (see [deleteEntry]).
     */
    suspend fun amendEntry(eventId: Long, fields: JsonObject): Long? {
        val target = db.events().byId(eventId) ?: return null
        val refused = fields.keys.filter { it in AMENDMENT_PROTECTED_KEYS }
        require(refused.isEmpty()) {
            "an amendment may not change which exercise or workout an entry belongs to: $refused"
        }
        require(fields.isNotEmpty()) { "an amendment with no fields corrects nothing" }
        // parsed exactly as a reader would, so a patch that would make the entry unreadable
        // fails here instead of making it disappear later
        checkAmendedPayload(target.type, target.payload, fields)
        return db.events().insert(
            EventEntity(
                ts = now(), type = TYPE_ENTRY_AMENDED,
                payload = payloadJson.encodeToString(
                    EntryAmended(targetUid = target.uid, fields = fields)
                ),
            )
        )
    }

    /**
     * Corrects an activity entry from the whole form the editor is holding.
     *
     * The convenience the screens will actually use: an edit dialog has a filled-in form, not a
     * diff. The protected keys are STRIPPED rather than refused here — the form necessarily
     * carries the exercise it belongs to, and making every caller remove it by hand would be a
     * rule enforced by remembering it. What that means in one sentence: the values and the date
     * of [updated] are applied, and the exercise it names is ignored.
     */
    suspend fun amendEntry(eventId: Long, updated: ActivityForm): Long? {
        val fields = JsonObject(updated.toJsonObject().filterKeys { it !in AMENDMENT_PROTECTED_KEYS })
        return amendEntry(eventId, fields)
    }

    /**
     * Throws unless the target's payload survives the patch. Sets are checked through
     * [formFromEvent], which is the reader; the two service events have no entry there and are
     * checked against the classes their own readers use.
     */
    private fun checkAmendedPayload(type: String, payload: String, fields: JsonObject) {
        val base = payloadJson.parseToJsonElement(payload) as? JsonObject
            ?: throw IllegalArgumentException("event $type has no readable payload to amend")
        val merged = JsonObject(LinkedHashMap(base).apply { putAll(fields) })
        val text = payloadJson.encodeToString(JsonObject.serializer(), merged)
        when (type) {
            TYPE_WORKOUT_STARTED -> payloadJson.decodeFromString<WorkoutStarted>(text)
            TYPE_WORKOUT_EXERCISE_ADDED -> payloadJson.decodeFromString<WorkoutExerciseAdded>(text)
            else -> formFromEvent(type, text)
        }
    }

    // --- exercise catalog (§11) ---

    suspend fun allExercises(): List<ExerciseEntity> = db.exercises().all()

    suspend fun exercise(id: Long): ExerciseEntity? = db.exercises().byId(id)

    /**
     * The exercise with this IDENTITY, creating it if there is none: name, form, edge and
     * work:rest protocol together (see [ExerciseIdentity]), not the name on its own.
     *
     * ── What this used to do, and what it cost ─────────────────────────────────
     * It looked for a row with the same normalized NAME and returned it, dropping the edge
     * and the protocol it had been handed without a word. §12-A says those are part of what a
     * hangboard exercise IS, so adding hangs on a 15 mm edge while 20 mm hangs existed handed
     * back the 20 mm row: two exercises became one, every set went into one history, and
     * nothing anywhere said so. The only thing that ever prevented it was the user's habit of
     * typing the edge into the name.
     *
     * Now a different edge, a different protocol or a different form is a DIFFERENT EXERCISE
     * and gets a row of its own — which is also what the UNIQUE index added in schema version
     * 15 enforces, so this lookup and the constraint cannot disagree.
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
        edgeMm: Double? = null,
        workSec: Double? = null,
        restSec: Double? = null,
        defaultRestSec: Int? = null,
    ): Long {
        val key = exerciseIdentityKey(name, form.code, edgeMm, workSec, restSec)
        db.exercises().byIdentityKey(key)?.let { found ->
            if (defaultRestSec != null) setDefaultRest(found.id, defaultRestSec)
            if (found.hidden) setHidden(found.id, false)
            return found.id
        }
        return db.exercises().insert(
            ExerciseEntity(
                name = name, form = form.code, createdAt = now(),
                edgeMm = edgeMm, protocolWorkSec = workSec, protocolRestSec = restSec,
                defaultRestSec = defaultRestSec,
            )
        )
    }

    /**
     * Corrects what an exercise is: the name it was given, the edge it is done on, the
     * protocol it is run at. The form is deliberately absent — see below.
     *
     * ── What this does to the history, said out loud ───────────────────────────
     * Nothing is rewritten, and the sets do not move. Every set names its exercise by uid, so
     * they all stay with this row and its records, charts and totals are computed over the
     * same sets as before.
     *
     * What DOES change is what those sets mean. A hangboard set carries a SNAPSHOT of the edge
     * and protocol it was performed at (see HoldSet), so after correcting an edge from 20 to 15
     * the row says 15 mm while sets recorded before the correction still say 20. That is the
     * honest record of a typo being fixed: the sets were performed on whatever the user
     * actually used, and the app was never told. It is also the record of a genuine mistake if
     * the edit is wrong — an exercise really trained on 20 mm, relabelled 15, now has a history
     * that claims a harder edge than was ever hung on.
     *
     * The app cannot tell those two apart, so it does neither automatically. THIS EDIT IS FOR
     * CORRECTING WHAT THE CATALOG SAYS, not for recording a change of training. An exercise
     * that has genuinely moved to a different edge is a different exercise under §12-A and
     * should be created as one; it will then have its own history from that day, which is what
     * actually happened.
     *
     * ── Why the form cannot be changed ─────────────────────────────────────────
     * The form decides the SHAPE of the payload a set is written in. Changing it would leave
     * every set already logged in the old shape, read by a screen expecting the new one — a
     * strength history that the hold reducers cannot read, in the one place where being able
     * to read the history is the whole product. Getting the form wrong means creating the
     * exercise again and hiding the mistake.
     *
     * Returns what happened, because the identity is unique in the schema and "there is
     * already an exercise like that" is a normal answer the user has to be given rather than
     * a crash.
     *
     * NONE OF THE THREE NUMBERS HAS A DEFAULT, deliberately. Null here means "this exercise
     * has no edge / no protocol", not "leave whatever is stored alone", and a default would
     * make forgetting an argument the way to silently strip a hangboard exercise of the two
     * values that decide which sets are its own.
     */
    suspend fun editExercise(
        id: Long,
        name: String,
        edgeMm: Double?,
        workSec: Double?,
        restSec: Double?,
    ): ExerciseEdit {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ExerciseEdit.Blank
        val stored = db.exercises().byId(id) ?: return ExerciseEdit.Gone
        val key = exerciseIdentityKey(trimmed, stored.form, edgeMm, workSec, restSec)
        // asked before it is attempted, so the ordinary collision is an answer rather than a
        // caught exception; the constraint is still there underneath as the thing that makes
        // the answer true
        db.exercises().byIdentityKey(key)?.let { clash ->
            if (clash.id != id) return ExerciseEdit.Taken(clash.name)
        }
        val touched = db.exercises().editIdentity(
            id = id, name = trimmed, edgeMm = edgeMm, workSec = workSec, restSec = restSec,
            identityKey = key,
        )
        return if (touched == 0) ExerciseEdit.Gone else ExerciseEdit.Saved
    }

    /**
     * Keeps an exercise out of the pickers, or brings it back.
     *
     * NOT a delete, and there is no delete to offer instead — see [ExerciseEntity.hidden]. A
     * hidden exercise keeps every set it ever had, goes on counting in the totals it always
     * counted in, and stays reachable from the overview, which is where it is brought back
     * from.
     */
    suspend fun setHidden(exerciseId: Long, hidden: Boolean) =
        db.exercises().setHidden(exerciseId, hidden)

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

/**
 * The catalog row as the domain sees it. Screens build forms out of an [ExerciseRef] and
 * never assemble a payload themselves, so an exercise cannot lose its identity — for
 * holds that identity includes edge and protocol (§12-A).
 *
 * An unreadable form code degrades to a check-in rather than throwing: that is the only
 * form whose entry card cannot write a wrong-shaped payload, so a corrupted row costs a
 * useless card instead of a crash on the screen the user is standing in the gym with.
 */
fun ExerciseEntity.toRef(): ExerciseRef = ExerciseRef(
    id = id,
    uid = uid,
    name = name,
    form = runCatching { ExerciseForm.fromCode(form) }.getOrDefault(ExerciseForm.TICK),
    edgeMm = edgeMm,
    workSec = protocolWorkSec,
    restSec = protocolRestSec,
    defaultRestSec = defaultRestSec,
    ledByProtocolFlag = ledByProtocol,
    oneSided = oneSided,
)

fun EventEntity.toJournalEvent() = JournalEvent(
    id = id, ts = ts, spaceId = spaceId, authorId = authorId, type = type, payload = payload,
    workoutId = workoutId, uid = uid, workoutUid = workoutUid,
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
