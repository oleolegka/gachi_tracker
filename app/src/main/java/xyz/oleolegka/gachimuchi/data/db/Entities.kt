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
 * The catalog [ExerciseEntity], the aliases [AliasEntity], the slots [SlotEntity] and the
 * composition of a slot [SlotExerciseEntity] are NOT part of the journal: they are editable
 * reference data and the plan (§12-B explicitly allows editing the plan).
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

/**
 * Marks a row as something the DEMO SEED created (schema version 4).
 *
 * Events carry their origin in [SEED_AUTHOR_ID] and always did. The catalog, the aliases
 * and the slots did not, and that was the hole: demo hangs, demo words and a demo plan were
 * written into a real profile with nothing to tell them apart from the user's own, so the
 * only way to get rid of them was to clear the app's data and lose the journal with them.
 *
 * The flag is set on INSERT only. An exercise the seed found already there (the catalog
 * deduplicates by name) belongs to the user and stays unmarked, so removing the demo data
 * cannot take it away.
 */
const val COLUMN_SEEDED = "seeded"

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
    /**
     * The workout this row was recorded during (schema version 5): the id of the
     * `workout_started` event that opened it. Null means "recorded outside any workout".
     *
     * NULL HAS TO STAY LEGAL, and that is the whole point of the column being nullable
     * rather than defaulted. The app is used standing in a gym with one hand: opening it
     * and writing a set has to work without pressing "start" first, otherwise the button
     * becomes a toll gate in front of the only thing the app is for. Every row already in
     * the journal reads as unattached too, which is what it was.
     *
     * There is deliberately NO foreign key to `events`. The journal is append-only and a
     * dangling id simply reads as "that workout is not in this journal" — which is the
     * honest answer once rows start arriving from the bot, where the id space is its own.
     *
     * A column and not a payload field, unlike everything else about an event. The payload
     * schema is the EXCHANGE format shared with the Python bot (see domain/Forms.kt), and
     * the bot has no notion of a workout yet; putting the link there would have meant
     * changing six payload shapes it already reads. The service event that adds an exercise
     * to a workout does carry the id in its payload, because that event is new on both
     * sides and has nothing to stay compatible with.
     */
    @androidx.room.ColumnInfo(name = "workout_id") val workoutId: Long? = null,
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
    /** Created by the demo seed and removable with it — see [COLUMN_SEEDED]. */
    @androidx.room.ColumnInfo(name = COLUMN_SEEDED) val seeded: Boolean = false,
    /**
     * The rest between sets last chosen for this exercise, in seconds (schema version 5),
     * or null while nothing has been chosen yet.
     *
     * A REMEMBERED DECISION, not a measurement, and that is why it is a column rather than
     * something derived. `lastRestSec` (domain/TimerSettings.kt) already reads the pause out
     * of the journal timestamps, but it answers "how long did you actually stand around last
     * time", which includes the queue for the rack and the conversation. What the user picks
     * when adding the exercise to a workout is a different fact — the rest they MEANT — and
     * it has to survive a session in which they never got that pause right.
     *
     * The derived number stays as the fallback for an exercise this has never been set on,
     * so nothing regresses for a catalog that predates the column.
     */
    @androidx.room.ColumnInfo(name = "default_rest_sec") val defaultRestSec: Int? = null,
    /**
     * Whether a set of this exercise is RUN BY ITS PROTOCOL (true) or is simply followed by
     * a rest countdown (false), or null for "decide from whether a protocol exists" (schema
     * version 5).
     *
     * The null is the interesting value. Having a work:rest protocol is what the app used to
     * infer this from, and for repeaters that inference is right. It is wrong for a maximum
     * added-weight hang: the row carries a protocol because §12-A makes protocol part of
     * hangboard identity, but the exercise is trained like a strength lift — one effort, then
     * a long pause — and a timer that starts calling out 7:3 intervals during it is noise.
     *
     * So the column overrides the inference where the user has said so, and stays null
     * everywhere else rather than freezing today's guess into every existing row.
     */
    @androidx.room.ColumnInfo(name = "led_by_protocol") val ledByProtocol: Boolean? = null,
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
    /** Created by the demo seed and removable with it — see [COLUMN_SEEDED]. */
    @androidx.room.ColumnInfo(name = COLUMN_SEEDED) val seeded: Boolean = false,
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
    /** Created by the demo seed and removable with it — see [COLUMN_SEEDED]. */
    @androidx.room.ColumnInfo(name = COLUMN_SEEDED) val seeded: Boolean = false,
)

/**
 * One exercise planned into a slot (schema version 6): the intended composition of a
 * session, which a workout started from that slot can be filled in from.
 *
 * A TABLE RATHER THAN A JSON COLUMN ON `slots`, for the same reason the program tables are
 * three tables and not a blob (see [ProgramEntity]): the row is the unit the editor works
 * in, and a blob turns adding one exercise into a read-modify-write of the whole list. It
 * also keeps the exercise link queryable, which "what am I supposed to be doing today" will
 * eventually want.
 *
 * ON DELETE CASCADE against `slots`, so deleting a plan cannot leave its composition behind
 * as rows nothing can reach. That is the ONLY foreign key here, and the omission of the
 * other one is the decision worth writing down: there is deliberately no key on
 * [exerciseId]. The catalog is editable and §12-A can split a hangboard exercise by edge — a
 * cascade would let deleting an exercise silently rewrite a plan, and `SET NULL` would leave
 * a planned line pointing at nothing while claiming to be intact. A dangling id simply reads
 * as "that exercise is gone", which the editor can say out loud.
 *
 * [position] is written from the list index on every save (the composition is replaced, not
 * diffed), so the stored order and the order on screen cannot drift apart.
 *
 * There is no `seeded` column: the demo seed writes no compositions, and if it ever does,
 * these rows go when their slot does.
 */
@Entity(
    tableName = "slot_exercises",
    indices = [Index(value = ["slot_id"])],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = SlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["slot_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        )
    ],
)
data class SlotExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "slot_id") val slotId: Long,
    @androidx.room.ColumnInfo(name = "exercise_id") val exerciseId: Long,
    val position: Int,
    /** Rest between sets of this exercise IN THIS SESSION, or null for "the usual one". */
    @androidx.room.ColumnInfo(name = "rest_sec") val restSec: Int? = null,
)
