package xyz.oleolegka.gachimuchi.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.oleolegka.gachimuchi.data.db.AliasDao
import xyz.oleolegka.gachimuchi.data.db.AliasEntity
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.LOCAL_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.JournalEvent
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
import xyz.oleolegka.gachimuchi.domain.openWorkoutId
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

    val aliases: Flow<List<AliasEntity>> = db.aliases().observeAll()

    val slots: Flow<List<Slot>> = db.slots().observeAll().map { rows -> rows.map { it.toSlot() } }

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
     * The DEMO SEED is excluded by author. Its sets are backdated synthetic history and
     * pressing "demo data" while a real workout is open must not pour them into it.
     */
    suspend fun record(form: ActivityForm, authorId: Long = LOCAL_AUTHOR_ID): Long =
        db.events().insert(
            EventEntity(
                ts = now(), authorId = authorId, type = form.type, payload = form.toPayload(),
                workoutId = if (authorId == LOCAL_AUTHOR_ID) currentWorkoutId() else null,
            )
        )

    // --- workouts (domain/Workout.kt folds them back out) ---

    /** Today, as the journal writes dates. */
    private fun today(): String = LocalDate.now().toString()

    /** The workout in progress, or null — see [openWorkout] for what "in progress" means. */
    suspend fun currentWorkoutId(): Long? = openWorkoutId(allEvents(), today())

    suspend fun currentWorkout(): Workout? = openWorkout(allEvents(), today())

    /**
     * Opens a workout and returns its id, which IS the id of the event just written.
     *
     * [opDate] defaults to today and is passed explicitly when old training is being typed
     * up. A workout dated in the past is silent — nothing in it counts anything down; see
     * [WorkoutStarted].
     *
     * [slotId] records which planned session this was started from, when the user picked one.
     */
    suspend fun startWorkout(
        opDate: String = today(),
        slotId: Long? = null,
        authorId: Long = LOCAL_AUTHOR_ID,
    ): Long = db.events().insert(
        EventEntity(
            ts = now(), authorId = authorId, type = TYPE_WORKOUT_STARTED,
            payload = payloadJson.encodeToString(WorkoutStarted(opDate = opDate, slotId = slotId)),
        )
    )

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
    suspend fun addExerciseToWorkout(
        workoutId: Long,
        exerciseId: Long,
        restSec: Int,
        authorId: Long = LOCAL_AUTHOR_ID,
    ): Long {
        val id = db.events().insert(
            EventEntity(
                ts = now(), authorId = authorId, type = TYPE_WORKOUT_EXERCISE_ADDED,
                payload = payloadJson.encodeToString(
                    WorkoutExerciseAdded(workoutId = workoutId, exerciseId = exerciseId, restSec = restSec)
                ),
                workoutId = workoutId,
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
     * Wipes the DEMO SEED events (by author). This is the only delete in the journal and
     * the only admissible one: the seed was never part of the user's history — it is
     * synthetic data written so the screens are not empty. Real records
     * (author = [LOCAL_AUTHOR_ID]) are left alone.
     */
    suspend fun clearSeedEvents(): Int = db.events().deleteBySeedAuthor()

    /**
     * Cancels a set: the journal is append-only, so a REVERSING event is written while
     * the set itself stays in the history (the reducers exclude it).
     */
    suspend fun cancelSet(eventId: Long, authorId: Long = LOCAL_AUTHOR_ID): Long =
        db.events().insert(
            EventEntity(
                ts = now(), authorId = authorId, type = TYPE_SET_CANCEL,
                payload = payloadJson.encodeToString(SetCancel(eventId)),
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
        seeded: Boolean = false,
        defaultRestSec: Int? = null,
    ): Long {
        val want = normPhrase(name)
        // an exercise that is already there is the USER'S, whatever created this call: the
        // seed mark is set on insert and never stamped onto a row found by name, so pressing
        // "demo data" cannot make a real exercise deletable
        db.exercises().all().firstOrNull { normPhrase(it.name) == want }?.let { found ->
            if (defaultRestSec != null) setDefaultRest(found.id, defaultRestSec)
            return found.id
        }
        return db.exercises().insert(
            ExerciseEntity(
                name = name, form = form.code, createdAt = now(),
                edgeMm = edgeMm, protocolWorkSec = workSec, protocolRestSec = restSec,
                seeded = seeded, defaultRestSec = defaultRestSec,
            )
        )
    }

    /**
     * Learns an alias word -> exercise_id. The mechanics come from the server: both the
     * phrase itself and its FIRST WORD are learned; if the word already points at a
     * different exercise, the word is blocked (from then on phrases and the picker
     * decide) instead of being silently relearned.
     */
    /**
     * [seeded] = true is the demo seed learning a word, and it behaves differently in one
     * respect: it only ever ADDS words that are not there yet. It does not repoint an
     * existing alias, does not bump its use count and does not block it. Demo data must not
     * be able to change where a word the user taught the app leads, and a word the seed did
     * create is marked so that removing the demo takes it back out again.
     */
    suspend fun learnAlias(word: String, exerciseId: Long, seeded: Boolean = false) {
        val phrase = normPhrase(word) ?: return
        val existingPhrase = db.aliases().byKey(phrase)
        if (!(seeded && existingPhrase != null)) {
            db.aliases().upsert(
                AliasEntity(key = phrase, value = exerciseId, seeded = existingPhrase?.seeded ?: seeded)
            )
        }
        val first = phrase.substringBefore(' ')
        if (first == phrase) return
        val existing = db.aliases().byKey(first)
        when {
            existing == null ->
                db.aliases().upsert(AliasEntity(key = first, value = exerciseId, seeded = seeded))

            seeded -> Unit // the seed never touches a word that already means something
            existing.blocked -> Unit
            existing.value == exerciseId ->
                db.aliases().upsert(existing.copy(uses = existing.uses + 1))

            else -> db.aliases().upsert(existing.copy(blocked = true))
        }
    }

    /** Word -> exercise (via aliases: the whole phrase first, then the first word). */
    suspend fun resolveExercise(word: String): ExerciseEntity? {
        val phrase = normPhrase(word) ?: return null
        val dao: AliasDao = db.aliases()
        dao.byKey(phrase)?.takeIf { !it.blocked }?.let { return db.exercises().byId(it.value) }
        val first = phrase.substringBefore(' ')
        if (first != phrase) {
            dao.byKey(first)?.takeIf { !it.blocked }?.let { return db.exercises().byId(it.value) }
        }
        return null
    }

    // --- calendar slots (§12-B) ---

    suspend fun allSlots(): List<Slot> = db.slots().all().map { it.toSlot() }

    suspend fun createSlot(
        name: String,
        atTime: String? = null,
        repeatRule: String,
        anchorDate: String,
        seeded: Boolean = false,
    ): Long = db.slots().insert(
        SlotEntity(
            name = name.trim(), atTime = atTime, repeatRule = repeatRule,
            anchorDate = anchorDate, createdAt = now(), seeded = seeded,
        )
    )

    suspend fun slot(id: Long): Slot? = db.slots().byId(id)?.toSlot()

    /**
     * Writes a slot the editor built: an INSERT when [id] is null, an UPDATE of that id
     * otherwise. Returns the slot id, or null when the draft is not storable.
     *
     * The draft is validated here as well as on the screen. That is not belt and braces
     * for its own sake — this is the boundary the database is behind, and a slot with a
     * blank name or an unreadable time would come back out as a row the calendar has to
     * render forever. The screen refuses first (with a message); the repository refuses
     * last (quietly), and neither relies on the other.
     */
    suspend fun saveSlot(draft: SlotDraft, id: Long? = null): Long? {
        // aliased on import: this file also declares a SlotEntity.toSlot of its own
        val slot = draft.draftToSlot(id ?: 0L) ?: return null
        if (id == null) {
            return createSlot(slot.name, slot.atTime, slot.repeatRule, slot.anchorDate)
        }
        val touched = db.slots().updateFields(
            id = id,
            name = slot.name,
            atTime = slot.atTime,
            repeatRule = slot.repeatRule,
            anchorDate = slot.anchorDate,
        )
        return if (touched > 0) id else null
    }

    /**
     * Deletes one slot — WITH ALL ITS OCCURRENCES, which is not a separate step: the
     * occurrences are computed from this row, so removing it removes the whole series,
     * past days included. Nothing in the journal is affected (see domain/Schedule.kt,
     * `deletionWarning`, which is the text the user confirms).
     */
    suspend fun deleteSlot(id: Long) = db.slots().deleteByIds(listOf(id))

    /** The plan is freely editable (append-only applies to facts, not to the plan). */
    suspend fun deleteSlots(ids: List<Long>) = db.slots().deleteByIds(ids)

    // --- removing the demo data (data/seed/DemoCleanup.kt decides WHAT, this is HOW) ---

    /** Slot rows as stored, seed mark included — [slots] drops it on the way to the domain. */
    suspend fun allSlotRows(): List<SlotEntity> = db.slots().all()

    suspend fun allAliases(): List<AliasEntity> = db.aliases().all()

    suspend fun deleteExercises(ids: List<Long>) {
        if (ids.isNotEmpty()) db.exercises().deleteByIds(ids)
    }

    suspend fun deleteAliases(keys: List<String>) {
        if (keys.isNotEmpty()) db.aliases().deleteByKeys(keys)
    }

    /** Turns seeded exercises into ordinary ones — see [ExerciseDao.clearSeedMark]. */
    suspend fun keepExercises(ids: List<Long>) {
        if (ids.isNotEmpty()) db.exercises().clearSeedMark(ids)
    }
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
    workoutId = workoutId,
)

fun SlotEntity.toSlot() = Slot(
    id = id, name = name, atTime = atTime, repeatRule = repeatRule, anchorDate = anchorDate,
)
