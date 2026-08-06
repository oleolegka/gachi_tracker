package xyz.oleolegka.gachimuchi.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local storage schema — a mirror of the server one (`bot/db.py`), so that future sync
 * does not turn into translating one model into another.
 *
 * The source of truth is the APPEND-ONLY journal [EventEntity]: every domain record is
 * an event with a type and a JSON payload. Nothing is edited and nothing is deleted; a
 * correction is a new event (the `set_cancel` reversal cancels a set). This is a
 * deliberate choice in favour of conflict-free sync: two devices appending to opposite
 * ends of the journal merge by union rather than by resolving field conflicts.
 *
 * The catalog [ExerciseEntity], the aliases [AliasEntity] and the slots [SlotEntity]
 * are NOT part of the journal: they are editable reference data and the plan (§12-B
 * explicitly allows editing the plan).
 *
 * `space_id` (the profile) is present everywhere, exactly as on the server:
 * multi-tenancy is preserved in the schema even though the app has exactly one profile
 * so far ([LOCAL_SPACE_ID]).
 */

/** The single local profile. The space_id column is kept for schema compatibility. */
const val LOCAL_SPACE_ID = 1L

/** Author of local records (on the server this is the Telegram id). */
const val LOCAL_AUTHOR_ID = 1L

/** Author of the demo seed: negative, so that wiping the seed cannot touch real records. */
const val SEED_AUTHOR_ID = -777L

@Entity(
    tableName = "events",
    indices = [Index(value = ["space_id", "id"])],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,
    @androidx.room.ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    @androidx.room.ColumnInfo(name = "author_id") val authorId: Long = LOCAL_AUTHOR_ID,
    val type: String,
    val payload: String,
)

/**
 * A canonical exercise (§11): statistics and records aggregate by `id` rather than by
 * word — "squat" and "squats" are two aliases of one row.
 *
 * [edgeMm], [protocolWorkSec] and [protocolRestSec] are an EXTENSION over the server
 * table (which has five columns). The reason is §12-A: hangboard identity is
 * name + edge + protocol, so edge and protocol belong to the exercise, not to the set.
 * That refactor has not been done on the server yet (it is waiting for the design to
 * settle), so the schema here is DELIBERATELY ahead — when sync arrives, the server
 * will have to add these fields, otherwise identity will drift apart.
 */
@Entity(
    tableName = "exercises",
    indices = [Index(value = ["space_id", "id"])],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    /** Form code, the values of Python's `flow.FORM_*` (see ExerciseForm). */
    val form: Int,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: String,
    @androidx.room.ColumnInfo(name = "edge_mm") val edgeMm: Double? = null,
    @androidx.room.ColumnInfo(name = "protocol_work_sec") val protocolWorkSec: Double? = null,
    @androidx.room.ColumnInfo(name = "protocol_rest_sec") val protocolRestSec: Double? = null,
)

/**
 * An alias: word -> exercise_id (the generic `dictionary` table, repurposed).
 * [blocked] preserves the server behaviour of "a word leads to two different exercises,
 * so the word is no longer evidence".
 */
@Entity(tableName = "aliases", primaryKeys = ["space_id", "key"])
data class AliasEntity(
    @androidx.room.ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val key: String,
    /** The exercise_id the word points at. */
    val value: Long,
    val uses: Int = 1,
    val blocked: Boolean = false,
)

/** A master slot of the planning calendar (§12-B). Occurrences are computed, not stored. */
@Entity(
    tableName = "slots",
    indices = [Index(value = ["space_id", "id"])],
)
data class SlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    /** The name of the SESSION ("Gym", "Hangboard"), not of an exercise. */
    val name: String,
    @androidx.room.ColumnInfo(name = "at_time") val atTime: String?,
    @androidx.room.ColumnInfo(name = "repeat_rule") val repeatRule: String,
    @androidx.room.ColumnInfo(name = "anchor_date") val anchorDate: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: String,
)
