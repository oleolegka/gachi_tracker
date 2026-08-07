package xyz.oleolegka.gachimuchi.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey
import xyz.oleolegka.gachimuchi.domain.newUid

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
 * The catalog [ExerciseEntity], the slots [SlotEntity] and the composition of a slot
 * [SlotExerciseEntity] are NOT part of the journal: they are editable reference data and the
 * plan (§12-B explicitly allows editing the plan).
 *
 * ── Every stored row carries a `uid`, and the numeric `id` is local plumbing ─────
 * `id` is an autoincrement: a count of how many rows THIS phone has written. It is fine as
 * a row address inside one database and useless as an identity anywhere else, because a
 * second device hands out the same numbers to entirely different training. `uid` (schema
 * version 8, a UUIDv7 — see [newUid]) is the identity that travels: it is what a merge of
 * two journals matches on, and what an exported file refers to.
 *
 * The rule that follows: anything one row says about another row is said in uids. The
 * numeric columns that predate version 8 are kept alongside for as long as there are
 * readers on them, and they are the thing to remove, never the thing to add to.
 *
 * `space_id` (the profile) is present everywhere, exactly as on the server:
 * multi-tenancy is preserved in the schema even though the app has exactly one profile
 * so far ([LOCAL_SPACE_ID]).
 *
 * The server schema also has a `dictionary` table of learned synonyms, which this one
 * mirrored until schema version 7. It is gone (see [AppDatabase] on the 6 -> 7 migration):
 * the words were only ever taught by a parser of free text, and in the app an exercise is
 * picked from a list.
 */

/** The single local profile. The space_id column is kept for schema compatibility. */
const val LOCAL_SPACE_ID = 1L

/** Author of local records (on the server this is the Telegram id). */
const val LOCAL_AUTHOR_ID = 1L

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
    ],
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
    /** Stable identity of this event across devices and exports — see [newUid]. */
    val uid: String = newUid(),
    /**
     * The same link as [workoutId], said in the identity that travels (schema version 9):
     * the `uid` of the `workout_started` event that opened the workout this row was recorded
     * during, or null for "recorded outside any workout".
     *
     * BOTH COLUMNS ARE WRITTEN, and the uid is the one the readers believe. The numeric one
     * stays for rows that predate version 9 — the 8 -> 9 migration fills the uid in for every
     * one it can resolve, and a row pointing at a workout that is no longer in the journal
     * keeps its dangling number and nothing else, which is the honest record of what it says.
     *
     * There is still deliberately no foreign key, for the reason [workoutId] gives.
     */
    @androidx.room.ColumnInfo(name = "workout_uid") val workoutUid: String? = null,
)

/**
 * A canonical exercise (§11): statistics and records aggregate by `id` rather than by the
 * word an entry happens to carry, so "squat" and "squats" cannot end up as two histories.
 *
 * [edgeMm], [protocolWorkSec] and [protocolRestSec] are an EXTENSION over the server
 * table (which has five columns). The reason is §12-A: hangboard identity is
 * name + edge + protocol, so edge and protocol belong to the exercise, not to the set.
 * That refactor has not been done on the server yet (it is waiting for the design to
 * settle), so the schema here is DELIBERATELY ahead — when sync arrives, the server
 * will have to add these fields, otherwise identity will drift apart.
 *
 * ── The identity is a constraint now, not a convention (schema version 15) ──────
 * §12-A was a rule written in documentation and obeyed by the readers, while the writer —
 * the one place that creates rows — looked an exercise up by NAME and handed back whatever
 * it found. Hangs on a 15 mm edge added while 20 mm hangs existed became 20 mm hangs, and
 * two histories merged for good, silently. [identityKey] closes it in the schema itself:
 * the four values that make an exercise what it is are folded into one string and carry a
 * UNIQUE index, so a second row of one identity is not something a bug can create.
 */
@Entity(
    tableName = "exercises",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
        Index(value = ["space_id", "identity_key"], unique = true),
    ],
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
    /** Stable identity of this exercise across devices and exports — see [newUid]. */
    val uid: String = newUid(),
    /**
     * Whether this exercise is trained ONE LIMB AT A TIME (schema version 13): a one-arm
     * hang, a pistol squat, a single-leg deadlift.
     *
     * ── Why the flag is here and the side is on the set ─────────────────────────
     * Which hand a particular hang used is a fact about that hang
     * ([xyz.oleolegka.gachimuchi.domain.HoldSet.side]). Whether the exercise is done one hand
     * at a time is a fact about the exercise, and it has to be answerable BEFORE any set
     * exists — the entry card has to know to ask which hand, and the timer has to know to
     * announce the change of hands between sets. Neither can wait for a set to be logged.
     *
     * It is also what makes a MISSING side a defect rather than a shrug: on an exercise
     * marked one-sided, a set that named no hand is a hole in the data, and the reducers say
     * so out loud instead of filing it as "both"
     * (see [xyz.oleolegka.gachimuchi.domain.holdRecord]).
     *
     * NOT NULL with false as the answer for every row that predates it, which is the true
     * one: nothing in the catalog was one-sided before there was a way to say so. It is
     * non-null rather than a `Boolean?` because, unlike [ledByProtocol], there is no third
     * state to represent — an exercise either is trained one limb at a time or it is not, and
     * a null would be a second spelling of false that every reader would have to remember.
     * The price is paid in the migration, which rebuilds the table rather than adding a
     * column with a DEFAULT a fresh install would not have (see MIGRATION_12_13).
     */
    @androidx.room.ColumnInfo(name = "one_sided") val oneSided: Boolean = false,
    /**
     * What share of your body weight this exercise lifts (schema version 14): 1.0 for a
     * pull-up, around 0.65 for a push-up, null for "nobody has said".
     *
     * ── What it is for ─────────────────────────────────────────────────────────
     * Body-weight sets used to be worth ZERO on the tonnage chart, so a week of pull-ups
     * looked like a week of doing nothing. With this and the weight recorded on the set
     * itself ([xyz.oleolegka.gachimuchi.domain.StrengthSet.bodyweightKg]) a set is worth
     * `share x body weight + added weight` per rep.
     *
     * ── Why null is a real answer and not a zero ────────────────────────────────
     * Null means the volume of those sets is UNKNOWN, not that it is nothing, and the charts
     * behave for such an exercise exactly as they did before this column existed — which is
     * the point: a catalog nobody has filled in must not have its history redrawn. A default
     * of, say, 1.0 would have been a guess applied silently to every push-up ever logged.
     *
     * The number is a rough share of a whole body and cannot exceed it: a value outside
     * (0, 1] is treated as absent rather than used
     * (see [xyz.oleolegka.gachimuchi.domain.usableShare]), on the same grounds as a
     * non-positive edge on this row.
     */
    @androidx.room.ColumnInfo(name = "bodyweight_share") val bodyweightShare: Double? = null,
    /**
     * Whether this exercise is kept out of the pickers (schema version 15).
     *
     * ── Hidden, and deliberately not deleted ───────────────────────────────────
     * The journal outlives the catalog. Every set ever logged names its exercise by uid, and
     * deleting the row would leave years of sets pointing at nothing — the history would still
     * list them, and they would belong to no exercise, have no records and appear on no chart.
     * That is a worse outcome than the problem being solved, which is only that a list has
     * something in it nobody trains any more.
     *
     * So hiding is a PRESENTATION choice and nothing else. A hidden exercise keeps its sets,
     * its records and its charts; it is absent from the list you pick from when logging, and
     * from nowhere else. It is not part of [identityKey] for the same reason: hiding an
     * exercise must not make room for a second row claiming to be it.
     *
     * NOT NULL with false for every row that predates it, on the same grounds as [oneSided].
     */
    val hidden: Boolean = false,
    /**
     * The exercise's identity as one string — see
     * [xyz.oleolegka.gachimuchi.domain.ExerciseIdentity] (schema version 15).
     *
     * ── Derived state, and how it is kept from drifting ────────────────────────
     * This is not an independent fact: it is [name], [form], [edgeMm], [protocolWorkSec] and
     * [protocolRestSec] folded together, and a row whose key disagrees with its own columns
     * would be invisible to the lookup that prevents duplicates. Two things hold it in place.
     *
     * It is a CONSTRUCTOR DEFAULT computed from the parameters above it, so there is no way to
     * build the entity without it and no call site that has to remember — every insert in the
     * app, the backup restore included, gets a correct key for free.
     *
     * And the only statement that may change an identity ([ExerciseDao.editIdentity]) writes
     * the new key in the same UPDATE as the values it was computed from. There is deliberately
     * no other way to touch those four columns: Room's whole-entity `@Update` is gone from the
     * DAO precisely because it would let a caller rewrite the name and leave the key behind.
     *
     * Last in the parameter list because a Kotlin default may only refer to parameters
     * declared before it.
     */
    @androidx.room.ColumnInfo(name = "identity_key")
    val identityKey: String = exerciseIdentityKey(name, form, edgeMm, protocolWorkSec, protocolRestSec),
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
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
    ],
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
    /** Stable identity of this program across devices and exports — see [newUid]. */
    val uid: String = newUid(),
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
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
    ],
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
    /** Stable identity of this slot across devices and exports — see [newUid]. */
    val uid: String = newUid(),
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
 */
@Entity(
    tableName = "slot_exercises",
    indices = [
        Index(value = ["slot_id"]),
        Index(value = ["uid"], unique = true),
    ],
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
    /** Stable identity of this planned line across devices and exports — see [newUid]. */
    val uid: String = newUid(),
)
