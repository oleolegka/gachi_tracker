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
import xyz.oleolegka.gachimuchi.domain.TYPE_SET_CANCEL
import xyz.oleolegka.gachimuchi.domain.normPhrase
import xyz.oleolegka.gachimuchi.domain.payloadJson
import xyz.oleolegka.gachimuchi.domain.toPayload
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

    /** Appends an activity form to the journal. Returns the event id. */
    suspend fun record(form: ActivityForm, authorId: Long = LOCAL_AUTHOR_ID): Long =
        db.events().insert(
            EventEntity(ts = now(), authorId = authorId, type = form.type, payload = form.toPayload())
        )

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
     */
    suspend fun ensureExercise(
        name: String,
        form: ExerciseForm,
        edgeMm: Double? = null,
        workSec: Double? = null,
        restSec: Double? = null,
    ): Long {
        val want = normPhrase(name)
        db.exercises().all().firstOrNull { normPhrase(it.name) == want }?.let { return it.id }
        return db.exercises().insert(
            ExerciseEntity(
                name = name, form = form.code, createdAt = now(),
                edgeMm = edgeMm, protocolWorkSec = workSec, protocolRestSec = restSec,
            )
        )
    }

    /**
     * Learns an alias word -> exercise_id. The mechanics come from the server: both the
     * phrase itself and its FIRST WORD are learned; if the word already points at a
     * different exercise, the word is blocked (from then on phrases and the picker
     * decide) instead of being silently relearned.
     */
    suspend fun learnAlias(word: String, exerciseId: Long) {
        val phrase = normPhrase(word) ?: return
        db.aliases().upsert(AliasEntity(key = phrase, value = exerciseId))
        val first = phrase.substringBefore(' ')
        if (first == phrase) return
        val existing = db.aliases().byKey(first)
        when {
            existing == null -> db.aliases().upsert(AliasEntity(key = first, value = exerciseId))
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
    ): Long = db.slots().insert(
        SlotEntity(
            name = name.trim(), atTime = atTime, repeatRule = repeatRule,
            anchorDate = anchorDate, createdAt = now(),
        )
    )

    /** The plan is freely editable (append-only applies to facts, not to the plan). */
    suspend fun deleteSlots(ids: List<Long>) = db.slots().deleteByIds(ids)
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
)

fun EventEntity.toJournalEvent() = JournalEvent(
    id = id, ts = ts, spaceId = spaceId, authorId = authorId, type = type, payload = payload,
)

fun SlotEntity.toSlot() = Slot(
    id = id, name = name, atTime = atTime, repeatRule = repeatRule, anchorDate = anchorDate,
)
