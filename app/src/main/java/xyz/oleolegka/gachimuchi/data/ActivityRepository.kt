package xyz.oleolegka.gachimuchi.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.LOCAL_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.SetCancel
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.TYPE_SET_CANCEL
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_EXERCISE_ADDED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_STARTED
import xyz.oleolegka.gachimuchi.domain.Workout
import xyz.oleolegka.gachimuchi.domain.WorkoutExerciseAdded
import xyz.oleolegka.gachimuchi.domain.WorkoutStarted
import xyz.oleolegka.gachimuchi.domain.normPhrase
import xyz.oleolegka.gachimuchi.domain.openWorkout
import xyz.oleolegka.gachimuchi.domain.openWorkoutRow
import xyz.oleolegka.gachimuchi.domain.payloadJson
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
    suspend fun record(form: ActivityForm, attachToWorkout: Boolean = true): Long {
        val workout = if (attachToWorkout) openWorkoutRow(allEvents(), today()) else null
        return db.events().insert(
            EventEntity(
                ts = now(), type = form.type, payload = form.toPayload(),
                // both links, and the uid is the one the reducers believe: see EventEntity
                workoutId = workout?.id, workoutUid = workout?.uid,
            )
        )
    }

    // --- workouts (domain/Workout.kt folds them back out) ---

    /** Today, as the journal writes dates. */
    private fun today(): String = LocalDate.now().toString()

    /** The workout in progress, or null — see [openWorkout] for what "in progress" means. */
    suspend fun currentWorkoutId(): Long? = openWorkoutRow(allEvents(), today())?.id

    suspend fun currentWorkout(): Workout? = openWorkout(allEvents(), today())

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
     */
    suspend fun startWorkout(opDate: String = today(), slotId: Long? = null): Long {
        val slot = slotId?.let { db.slots().byId(it) }
        return db.events().insert(
            EventEntity(
                ts = now(), type = TYPE_WORKOUT_STARTED,
                payload = payloadJson.encodeToString(
                    WorkoutStarted(opDate = opDate, slotId = slotId, slotUid = slot?.uid),
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

    /** Remembers the rest chosen for an exercise — see [ExerciseDao.setDefaultRest]. */
    suspend fun setDefaultRest(exerciseId: Long, restSec: Int?) =
        db.exercises().setDefaultRest(exerciseId, restSec)

    /** "Run this by its protocol" / "just count the rest" / null to go back to inferring it. */
    suspend fun setLedByProtocol(exerciseId: Long, ledByProtocol: Boolean?) =
        db.exercises().setLedByProtocol(exerciseId, ledByProtocol)

    /**
     * Cancels a set: the journal is append-only, so a REVERSING event is written while
     * the set itself stays in the history (the reducers exclude it).
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

    // --- exercise catalog (§11) ---

    suspend fun allExercises(): List<ExerciseEntity> = db.exercises().all()

    suspend fun exercise(id: Long): ExerciseEntity? = db.exercises().byId(id)

    /**
     * Creates an exercise. Deduplication by NORMALIZED name is advisory, same as on the
     * server (there is no UNIQUE index): if an exercise with that name already exists,
     * its id is returned.
     *
     * ── What a found row does and does not pick up ──────────────────────────────
     * Name, form, edge and protocol of an existing row are NEVER touched. Those four are the
     * exercise's IDENTITY (§12-A puts edge and protocol in it), and quietly rewriting them
     * from whatever a caller passed would move an exercise's history onto a different
     * exercise — the failure this whole catalog exists to prevent.
     *
     * [defaultRestSec] is the one exception, and only when it is given. It is a PREFERENCE,
     * not identity: "I want two and a half minutes between these" is a statement about the
     * next set, it replaces the previous answer to the same question by design, and it
     * carries no history that a rewrite could strand.
     */
    suspend fun ensureExercise(
        name: String,
        form: ExerciseForm,
        edgeMm: Double? = null,
        workSec: Double? = null,
        restSec: Double? = null,
        defaultRestSec: Int? = null,
    ): Long {
        val want = normPhrase(name)
        db.exercises().all().firstOrNull { normPhrase(it.name) == want }?.let { found ->
            if (defaultRestSec != null) setDefaultRest(found.id, defaultRestSec)
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
