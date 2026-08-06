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

/**
 * An interval program (schema version 2).
 *
 * Programs are reference data, like the catalog and the slots, and are NOT part of the
 * journal: they are edited and deleted freely, and running one records nothing.
 *
 * The three tables mirror the domain shape one for one (program -> group -> block, see
 * domain/Program.kt) instead of storing a serialised blob in a single column. The reason
 * is that the editor changes one block at a time, and a blob turns every such edit into a
 * read-modify-write of the whole program. The price is two joins to load one program,
 * which at this scale is nothing.
 *
 * The server has no equivalent of these tables yet — unlike the rest of the schema, they
 * are local-only for now, and syncing programs is a later decision.
 */
@Entity(
    tableName = "programs",
    indices = [Index(value = ["space_id", "id"])],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    /** Lead-in before the first work step, in seconds; 0 means start straight away. */
    @androidx.room.ColumnInfo(name = "prepare_sec") val prepareSec: Int,
    val position: Int = 0,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: String,
    /**
     * The catalog exercise this program trains, when it is exactly one (schema version 3).
     *
     * Nullable and deliberately WITHOUT a foreign key. A program is reference data that
     * outlives the catalog row it points at — an exercise renamed, split by edge (§12-A) or
     * deleted must not take a hand-written protocol down with it, which `ON DELETE CASCADE`
     * would, and `ON DELETE SET NULL` would still make deleting an exercise silently edit
     * programs. A dangling id simply reads as "no link" and the offer asks again.
     */
    @androidx.room.ColumnInfo(name = "exercise_id") val exerciseId: Long? = null,
    /**
     * The heading this program is filed under on the timer tab (schema version 3), or the
     * empty string. Free text on the row rather than a folder table: see [programSections]
     * in domain/Program.kt for why that trade was made.
     */
    val category: String = "",
)

/**
 * A group of blocks, repeated as a unit. [position] fixes the order inside the program —
 * the row id would not survive a block being deleted and re-added in a different place.
 *
 * ON DELETE CASCADE is declared so that deleting a program cannot leave orphan groups
 * behind. Room needs foreign keys switched on at the connection level for that to bite,
 * which [AppDatabase] does.
 */
@Entity(
    tableName = "program_groups",
    indices = [Index(value = ["program_id"])],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = ProgramEntity::class,
            parentColumns = ["id"],
            childColumns = ["program_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        )
    ],
)
data class ProgramGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "program_id") val programId: Long,
    val name: String,
    val position: Int,
    val repeats: Int,
    @androidx.room.ColumnInfo(name = "rest_between_repeats_sec") val restBetweenRepeatsSec: Int,
    @androidx.room.ColumnInfo(name = "rest_after_sec") val restAfterSec: Int,
)

/** One timed effort with the pause that follows it, repeated [repeats] times. */
@Entity(
    tableName = "program_blocks",
    indices = [Index(value = ["group_id"])],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = ProgramGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        )
    ],
)
data class ProgramBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "group_id") val groupId: Long,
    val name: String,
    val position: Int,
    @androidx.room.ColumnInfo(name = "work_sec") val workSec: Int,
    @androidx.room.ColumnInfo(name = "rest_sec") val restSec: Int,
    val repeats: Int,
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
