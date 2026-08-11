package xyz.oleolegka.gachimuchi.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.LOCAL_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.db.LOCAL_SPACE_ID
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.freeExerciseName
import xyz.oleolegka.gachimuchi.domain.holdSecondsUnderTension
import xyz.oleolegka.gachimuchi.domain.formsOfExercise
import xyz.oleolegka.gachimuchi.domain.formFromEventOrNull
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.TYPE_HOLD_SET
import xyz.oleolegka.gachimuchi.domain.TYPE_SET_CANCEL
import xyz.oleolegka.gachimuchi.domain.TYPE_STRENGTH_SET
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
import xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey
import xyz.oleolegka.gachimuchi.domain.holdSetsOfExercise
import xyz.oleolegka.gachimuchi.domain.isUid
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_EXERCISE_ADDED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_STARTED
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.setsOutsideWorkouts
import xyz.oleolegka.gachimuchi.domain.payloadJson
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.WorkoutStarted
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.toDraft
import xyz.oleolegka.gachimuchi.domain.toPayload

/*
 * SNAPSHOTS OF THE OLD SCHEMA, one class per table per version it differed in.
 *
 * These have to exist. The old databases below used to be declared with the CURRENT entity
 * classes, which meant Room generated today's DDL for them and the "old" database in the test
 * already had every column the migration was about to add. That made every migration test
 * pass for the wrong reason and would have hidden a real one: an upgrade that adds a column
 * fails with ALTER TABLE against a table that already has it. A phone would have refused to
 * open its database while the test suite stayed green.
 *
 * The rule that keeps them honest: a snapshot may only reuse a current entity class for a
 * table that has NOT changed since. Adding a column to a live entity therefore breaks
 * compilation or the assertions here, loudly, which is the point — the alternative is a
 * migration silently tested against the schema it produces.
 */

/** The journal before the workout link of version 5. */
@Entity(tableName = "events", indices = [Index(value = ["space_id", "id"])])
data class EventEntityV4(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    @ColumnInfo(name = "author_id") val authorId: Long = LOCAL_AUTHOR_ID,
    val type: String,
    val payload: String,
)

@Dao
interface LegacyEventDao {
    @Insert
    suspend fun insert(event: EventEntityV4): Long
}

@Entity(tableName = "exercises", indices = [Index(value = ["space_id", "id"])])
data class ExerciseEntityV3(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    val form: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "edge_mm") val edgeMm: Double? = null,
    @ColumnInfo(name = "protocol_work_sec") val protocolWorkSec: Double? = null,
    @ColumnInfo(name = "protocol_rest_sec") val protocolRestSec: Double? = null,
)

@Entity(tableName = "aliases", primaryKeys = ["space_id", "key"])
data class AliasEntityV3(
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val key: String,
    val value: Long,
    val uses: Int = 1,
    val blocked: Boolean = false,
)

@Entity(tableName = "slots", indices = [Index(value = ["space_id", "id"])])
data class SlotEntityV3(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    @ColumnInfo(name = "at_time") val atTime: String?,
    @ColumnInfo(name = "repeat_rule") val repeatRule: String,
    @ColumnInfo(name = "anchor_date") val anchorDate: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

/**
 * The catalog of version 4: marked with the demo-seed flag, but before the workout
 * preferences of version 5.
 */
@Entity(tableName = "exercises", indices = [Index(value = ["space_id", "id"])])
data class ExerciseEntityV4(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    val form: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "edge_mm") val edgeMm: Double? = null,
    @ColumnInfo(name = "protocol_work_sec") val protocolWorkSec: Double? = null,
    @ColumnInfo(name = "protocol_rest_sec") val protocolRestSec: Double? = null,
    @ColumnInfo(name = "seeded") val seeded: Boolean = false,
)

/*
 * The aliases and the slots as they stood from version 4 onwards: the demo-seed mark and
 * nothing else after it. They used to be represented here by the CURRENT entities, which the
 * rule above allowed because neither table changed between 4 and 6.
 *
 * Version 7 changed both — one dropped, one rebuilt without the mark — so the reuse is no
 * longer legal and these snapshots exist. There is nothing left to reuse in the alias case:
 * the table is gone from the app entirely, and this class is now the only description of it
 * anywhere in the repository. That is the point of a snapshot; it describes a database that
 * still exists on phones, not one the code still believes in.
 */

@Entity(tableName = "aliases", primaryKeys = ["space_id", "key"])
data class AliasEntityV4(
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val key: String,
    val value: Long,
    val uses: Int = 1,
    val blocked: Boolean = false,
    @ColumnInfo(name = "seeded") val seeded: Boolean = false,
)

@Entity(tableName = "slots", indices = [Index(value = ["space_id", "id"])])
data class SlotEntityV4(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    @ColumnInfo(name = "at_time") val atTime: String?,
    @ColumnInfo(name = "repeat_rule") val repeatRule: String,
    @ColumnInfo(name = "anchor_date") val anchorDate: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "seeded") val seeded: Boolean = false,
)

/**
 * The catalog of versions 5 and 6: the mark plus the workout preferences. One class for both
 * because version 6 added a table and touched no column — the rule above allows one snapshot
 * to stand for two versions of an unchanged table, it only forbids the CURRENT entity
 * standing in for a table that has since changed.
 */
@Entity(tableName = "exercises", indices = [Index(value = ["space_id", "id"])])
data class ExerciseEntityV5(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    val form: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "edge_mm") val edgeMm: Double? = null,
    @ColumnInfo(name = "protocol_work_sec") val protocolWorkSec: Double? = null,
    @ColumnInfo(name = "protocol_rest_sec") val protocolRestSec: Double? = null,
    @ColumnInfo(name = "seeded") val seeded: Boolean = false,
    @ColumnInfo(name = "default_rest_sec") val defaultRestSec: Int? = null,
    @ColumnInfo(name = "led_by_protocol") val ledByProtocol: Boolean? = null,
)

/**
 * The composition of a slot as version 6 shipped it. Identical to [SlotExerciseEntity] in
 * every column — version 7 left this table alone — but its foreign key has to name the
 * `slots` entity THIS test database declares, and that is [SlotEntityV4].
 */
@Entity(
    tableName = "slot_exercises",
    indices = [Index(value = ["slot_id"])],
    foreignKeys = [
        ForeignKey(
            entity = SlotEntityV4::class,
            parentColumns = ["id"],
            childColumns = ["slot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class SlotExerciseEntityV6(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "slot_id") val slotId: Long,
    @ColumnInfo(name = "exercise_id") val exerciseId: Long,
    val position: Int,
    @ColumnInfo(name = "rest_sec") val restSec: Int? = null,
)

/** Just enough to put rows into the old tables; reading happens through the current DAOs. */
@Dao
interface LegacyCatalogDao {
    @Insert
    suspend fun insertExercise(exercise: ExerciseEntityV3): Long

    @Insert
    suspend fun insertAlias(alias: AliasEntityV3)

    @Insert
    suspend fun insertSlot(slot: SlotEntityV3): Long
}

@Dao
interface LegacyCatalogDaoV4 {
    @Insert
    suspend fun insertExercise(exercise: ExerciseEntityV4): Long

    @Insert
    suspend fun insertAlias(alias: AliasEntityV4)

    @Insert
    suspend fun insertSlot(slot: SlotEntityV4): Long
}

/**
 * Writing into a version 5 or version 6 database.
 *
 * The journal used to be inserted through the CURRENT `EventEntity`, because `events` had not
 * changed since version 5. Version 8 changed it, so that reuse became exactly the kind of lie
 * the rule at the top of this file forbids, and the journal now goes through [EventEntityV7]
 * like everything else.
 */
@Dao
interface LegacyCatalogDaoV5 {
    @Insert
    suspend fun insertEvent(event: EventEntityV7): Long

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntityV5): Long

    @Insert
    suspend fun insertAlias(alias: AliasEntityV4)

    @Insert
    suspend fun insertSlot(slot: SlotEntityV4): Long

    /** So that a version 6 database can be left with a GAP at the top of its catalog. */
    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteExercise(id: Long)
}

@Dao
interface LegacySlotExerciseDao {
    @Insert
    suspend fun insert(row: SlotExerciseEntityV6): Long
}

/**
 * Version 1 of the schema, exactly as it shipped: the four tables that existed before the
 * timer, and nothing else.
 *
 * Declared as a real Room database so that ROOM generates the old DDL. Writing those
 * CREATE TABLE statements by hand would mean the migration is tested against a schema
 * invented here, which is precisely the schema that cannot be wrong.
 */
@Database(
    entities = [EventEntityV4::class, ExerciseEntityV3::class, AliasEntityV3::class, SlotEntityV3::class],
    version = 1,
    exportSchema = false,
)
abstract class SchemaV1Database : RoomDatabase() {
    abstract fun events(): LegacyEventDao
    abstract fun catalog(): LegacyCatalogDao
}

/*
 * Version 2 of the program tables, exactly as they shipped: no link to an exercise and no
 * category. Same reasoning as [SchemaV1Database] — the old DDL is generated by Room from
 * these classes rather than written out here, so the migration is tested against the schema
 * that actually existed on a phone and not against one invented in a test.
 */

@Entity(tableName = "programs", indices = [Index(value = ["space_id", "id"])])
data class ProgramEntityV2(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    @ColumnInfo(name = "prepare_sec") val prepareSec: Int,
    val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Entity(
    tableName = "program_groups",
    indices = [Index(value = ["program_id"])],
    foreignKeys = [
        ForeignKey(
            entity = ProgramEntityV2::class,
            parentColumns = ["id"],
            childColumns = ["program_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ProgramGroupEntityV2(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "program_id") val programId: Long,
    val name: String,
    val position: Int,
    val repeats: Int,
    @ColumnInfo(name = "rest_between_repeats_sec") val restBetweenRepeatsSec: Int,
    @ColumnInfo(name = "rest_after_sec") val restAfterSec: Int,
)

@Entity(
    tableName = "program_blocks",
    indices = [Index(value = ["group_id"])],
    foreignKeys = [
        ForeignKey(
            entity = ProgramGroupEntityV2::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ProgramBlockEntityV2(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "group_id") val groupId: Long,
    val name: String,
    val position: Int,
    @ColumnInfo(name = "work_sec") val workSec: Int,
    @ColumnInfo(name = "rest_sec") val restSec: Int,
    val repeats: Int,
)

@Dao
interface ProgramDaoV2 {
    @Insert
    suspend fun insertProgram(program: ProgramEntityV2): Long

    @Insert
    suspend fun insertGroup(group: ProgramGroupEntityV2): Long

    @Insert
    suspend fun insertBlock(block: ProgramBlockEntityV2): Long
}

@Database(
    entities = [
        EventEntityV4::class,
        ExerciseEntityV3::class,
        AliasEntityV3::class,
        SlotEntityV3::class,
        ProgramEntityV2::class,
        ProgramGroupEntityV2::class,
        ProgramBlockEntityV2::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class SchemaV2Database : RoomDatabase() {
    abstract fun events(): LegacyEventDao
    abstract fun catalog(): LegacyCatalogDao
    abstract fun programs(): ProgramDaoV2
}

/**
 * Version 3: the program tables have grown their exercise link and their category (so they
 * are the CURRENT program entities), while the catalog, the aliases and the slots are still
 * unmarked. This is the phone the 3 -> 4 migration actually runs on — including the one this
 * change was written for, which has a demo history on it and no way to tell it apart.
 */
@Database(
    entities = [
        EventEntityV4::class,
        ExerciseEntityV3::class,
        AliasEntityV3::class,
        SlotEntityV3::class,
        ProgramEntityV3::class,
        ProgramGroupEntityV3::class,
        ProgramBlockEntityV3::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class SchemaV3Database : RoomDatabase() {
    abstract fun events(): LegacyEventDao
    abstract fun catalog(): LegacyCatalogDao
}

/**
 * Version 4: the demo-seed mark is on the catalog, the aliases and the slots, and nothing
 * knows about workouts yet. The journal has no `workout_id` and the catalog no rest
 * preference — this is the phone the 4 -> 5 migration actually runs on.
 */
@Database(
    entities = [
        EventEntityV4::class,
        ExerciseEntityV4::class,
        AliasEntityV4::class,
        SlotEntityV4::class,
        ProgramEntityV3::class,
        ProgramGroupEntityV3::class,
        ProgramBlockEntityV3::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class SchemaV4Database : RoomDatabase() {
    abstract fun events(): LegacyEventDao
    abstract fun catalog(): LegacyCatalogDaoV4
}

/**
 * Version 5: the workout link is on the journal and the rest preferences are on the catalog,
 * and a slot is still nothing but a name, a time and a rule — no composition. This is the
 * phone the 5 -> 6 migration actually runs on.
 */
@Database(
    entities = [
        EventEntityV7::class,
        ExerciseEntityV5::class,
        AliasEntityV4::class,
        SlotEntityV4::class,
        ProgramEntityV3::class,
        ProgramGroupEntityV3::class,
        ProgramBlockEntityV3::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class SchemaV5Database : RoomDatabase() {
    abstract fun catalog(): LegacyCatalogDaoV5
}

/**
 * Version 6: a slot can say what it consists of, and the demo seed is still a feature — the
 * mark is on the catalog, on the aliases and on the plan, and the `aliases` table is full of
 * words the app taught itself. This is the phone the 6 -> 7 migration actually runs on, and
 * the only one that matters in practice, since it is the version currently installed.
 *
 * `slot_exercises` and `events` are the two tables version 7 leaves alone; `events` is
 * therefore inserted through the current entity, while `slot_exercises` gets a snapshot for
 * a reason that is about the foreign key and not about its columns — see
 * [SlotExerciseEntityV6].
 */
@Database(
    entities = [
        EventEntityV7::class,
        ExerciseEntityV5::class,
        AliasEntityV4::class,
        SlotEntityV4::class,
        SlotExerciseEntityV6::class,
        ProgramEntityV3::class,
        ProgramGroupEntityV3::class,
        ProgramBlockEntityV3::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class SchemaV6Database : RoomDatabase() {
    abstract fun catalog(): LegacyCatalogDaoV5
    abstract fun compositions(): LegacySlotExerciseDao
}


/*
 * SNAPSHOTS OF VERSION 7 — the shape of the database immediately before every row grew a
 * `uid`. Five tables changed in the 7 -> 8 step, and the rule at the top of this file says a
 * current entity may only stand in for a table that has NOT changed since; so `events`,
 * `exercises`, `slots`, `slot_exercises` and `programs` all need one, and the two program
 * child tables need one only because their foreign key has to name a `programs` entity that
 * this test's databases actually declare.
 */

/** The journal from version 5 to version 7: the workout link, and no uid yet. */
@Entity(tableName = "events", indices = [Index(value = ["space_id", "id"])])
data class EventEntityV7(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    @ColumnInfo(name = "author_id") val authorId: Long = LOCAL_AUTHOR_ID,
    val type: String,
    val payload: String,
    @ColumnInfo(name = "workout_id") val workoutId: Long? = null,
)

/** The catalog of version 7: the demo mark is gone, the uid has not arrived. */
@Entity(tableName = "exercises", indices = [Index(value = ["space_id", "id"])])
data class ExerciseEntityV7(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    val form: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "edge_mm") val edgeMm: Double? = null,
    @ColumnInfo(name = "protocol_work_sec") val protocolWorkSec: Double? = null,
    @ColumnInfo(name = "protocol_rest_sec") val protocolRestSec: Double? = null,
    @ColumnInfo(name = "default_rest_sec") val defaultRestSec: Int? = null,
    @ColumnInfo(name = "led_by_protocol") val ledByProtocol: Boolean? = null,
)

@Entity(tableName = "slots", indices = [Index(value = ["space_id", "id"])])
data class SlotEntityV7(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    @ColumnInfo(name = "at_time") val atTime: String?,
    @ColumnInfo(name = "repeat_rule") val repeatRule: String,
    @ColumnInfo(name = "anchor_date") val anchorDate: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Entity(
    tableName = "slot_exercises",
    indices = [Index(value = ["slot_id"])],
    foreignKeys = [
        ForeignKey(
            entity = SlotEntityV7::class,
            parentColumns = ["id"],
            childColumns = ["slot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class SlotExerciseEntityV7(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "slot_id") val slotId: Long,
    @ColumnInfo(name = "exercise_id") val exerciseId: Long,
    val position: Int,
    @ColumnInfo(name = "rest_sec") val restSec: Int? = null,
)

/** Programs from version 3 to version 7: linked and filed, and with no uid. */
@Entity(tableName = "programs", indices = [Index(value = ["space_id", "id"])])
data class ProgramEntityV3(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    @ColumnInfo(name = "prepare_sec") val prepareSec: Int,
    val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: Long? = null,
    val category: String = "",
)

@Entity(
    tableName = "program_groups",
    indices = [Index(value = ["program_id"])],
    foreignKeys = [
        ForeignKey(
            entity = ProgramEntityV3::class,
            parentColumns = ["id"],
            childColumns = ["program_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ProgramGroupEntityV3(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "program_id") val programId: Long,
    val name: String,
    val position: Int,
    val repeats: Int,
    @ColumnInfo(name = "rest_between_repeats_sec") val restBetweenRepeatsSec: Int,
    @ColumnInfo(name = "rest_after_sec") val restAfterSec: Int,
)

@Entity(
    tableName = "program_blocks",
    indices = [Index(value = ["group_id"])],
    foreignKeys = [
        ForeignKey(
            entity = ProgramGroupEntityV3::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ProgramBlockEntityV3(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "group_id") val groupId: Long,
    val name: String,
    val position: Int,
    @ColumnInfo(name = "work_sec") val workSec: Int,
    @ColumnInfo(name = "rest_sec") val restSec: Int,
    val repeats: Int,
)

/** Just enough to put rows into a version 7 database. */
@Dao
interface LegacyCatalogDaoV7 {
    @Insert
    suspend fun insertEvent(event: EventEntityV7): Long

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntityV7): Long

    @Insert
    suspend fun insertSlot(slot: SlotEntityV7): Long

    @Insert
    suspend fun insertComposition(row: SlotExerciseEntityV7): Long

    @Insert
    suspend fun insertProgram(program: ProgramEntityV3): Long

    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteExercise(id: Long)
}

/**
 * Version 7: the demo seed and the learned words are gone, and nothing in the database can
 * name itself anywhere but on this phone. This is what the uid migration actually runs on.
 */
@Database(
    entities = [
        EventEntityV7::class,
        ExerciseEntityV7::class,
        SlotEntityV7::class,
        SlotExerciseEntityV7::class,
        ProgramEntityV3::class,
        ProgramGroupEntityV3::class,
        ProgramBlockEntityV3::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class SchemaV7Database : RoomDatabase() {
    abstract fun catalog(): LegacyCatalogDaoV7
}

/*
 * SNAPSHOT OF VERSION 12 — the shape of the database immediately before the catalog could say
 * that an exercise is trained one limb at a time.
 *
 * ONLY `exercises` NEEDS ONE. Versions 8 through 12 have identical DDL (9, 10, 11 and 12 are
 * rewrites of stored JSON and change no column), and of those tables the 12 -> 13 step touches
 * `exercises` alone. The rule at the top of this file allows the CURRENT entity to stand in for
 * a table that has not changed since, which is every other table here — and that is not a
 * shortcut, it is the rule doing its job: the day one of them changes, this database stops
 * describing version 12 and the compiler or these assertions say so.
 */

/*
 * SNAPSHOT OF THE JOURNAL FROM VERSION 9 TO VERSION 15 — the workout link said in uids, and
 * nothing about when a row was written beyond a local clock with no zone.
 *
 * The version 12 and version 14 databases below used to declare the CURRENT `EventEntity` here,
 * which the rule at the top of this file allowed because `events` had not changed since version
 * 9. Version 16 changed it — three columns and an index — so the reuse became exactly the lie
 * that rule exists to forbid, and this snapshot is what replaced it.
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
    ],
)
data class EventEntityV15(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    @ColumnInfo(name = "author_id") val authorId: Long = LOCAL_AUTHOR_ID,
    val type: String,
    val payload: String,
    @ColumnInfo(name = "workout_id") val workoutId: Long? = null,
    val uid: String = xyz.oleolegka.gachimuchi.domain.newUid(),
    @ColumnInfo(name = "workout_uid") val workoutUid: String? = null,
)

/**
 * Programs from version 8 to version 19: today's shape minus `hidden`, which
 * [xyz.oleolegka.gachimuchi.data.db.AppDatabase.Companion.MIGRATION_19_20] adds.
 *
 * A DEDICATED shadow class, unlike every "historical" fixture below this comment used to be —
 * they reused [xyz.oleolegka.gachimuchi.data.db.ProgramEntity] directly, which was safe only
 * because that class had not changed shape since [ProgramEntityV3] gained a `uid` in
 * MIGRATION_7_8. The moment `hidden` was added to it, every fixture reusing it directly started
 * creating a `programs` table with a column no real phone at that version ever had — and
 * MIGRATION_18_19's own raw `INSERT`, which correctly does not mention a column that does not
 * exist yet at that point in the walk, then failed its NOT NULL constraint on a column belonging
 * to the future rather than to the version under test. See [SchemaV12Database] through
 * [SchemaV18Database], all of which use this instead now.
 */
@Entity(
    tableName = "programs",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
    ],
)
data class ProgramEntityV8(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    @ColumnInfo(name = "prepare_sec") val prepareSec: Int,
    val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: Long? = null,
    val category: String = "",
    val uid: String = xyz.oleolegka.gachimuchi.domain.newUid(),
)

/** The catalog of versions 8 to 12: identity, preferences, and nothing about sides. */
@Entity(
    tableName = "exercises",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
    ],
)
data class ExerciseEntityV12(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    val form: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "edge_mm") val edgeMm: Double? = null,
    @ColumnInfo(name = "protocol_work_sec") val protocolWorkSec: Double? = null,
    @ColumnInfo(name = "protocol_rest_sec") val protocolRestSec: Double? = null,
    @ColumnInfo(name = "default_rest_sec") val defaultRestSec: Int? = null,
    @ColumnInfo(name = "led_by_protocol") val ledByProtocol: Boolean? = null,
    val uid: String = xyz.oleolegka.gachimuchi.domain.newUid(),
)

@Dao
interface LegacyCatalogDaoV12 {
    @Insert
    suspend fun insertEvent(event: EventEntityV15): Long

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntityV12): Long

    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteExercise(id: Long)
}

@Database(
    entities = [
        EventEntityV15::class,
        ExerciseEntityV12::class,
        xyz.oleolegka.gachimuchi.data.db.SlotEntity::class,
        xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity::class,
        ProgramEntityV8::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class SchemaV12Database : RoomDatabase() {
    abstract fun catalog(): LegacyCatalogDaoV12
}

/**
 * The catalog of versions 13 and 14: sides and the body-weight share, and NOTHING that says
 * two rows cannot be the same exercise.
 *
 * That last part is the point of this double. Version 15 adds the identity key and its unique
 * index, and the interesting question is what the upgrade does to a catalog that already holds
 * two rows one index would refuse — which cannot be set up through the app at all, only by
 * writing the rows as a version 14 phone could.
 */
@Entity(
    tableName = "exercises",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
    ],
)
data class ExerciseEntityV14(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    val form: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "edge_mm") val edgeMm: Double? = null,
    @ColumnInfo(name = "protocol_work_sec") val protocolWorkSec: Double? = null,
    @ColumnInfo(name = "protocol_rest_sec") val protocolRestSec: Double? = null,
    @ColumnInfo(name = "default_rest_sec") val defaultRestSec: Int? = null,
    @ColumnInfo(name = "led_by_protocol") val ledByProtocol: Boolean? = null,
    val uid: String = xyz.oleolegka.gachimuchi.domain.newUid(),
    @ColumnInfo(name = "one_sided") val oneSided: Boolean = false,
    @ColumnInfo(name = "bodyweight_share") val bodyweightShare: Double? = null,
)

@Dao
interface LegacyCatalogDaoV14 {
    @Insert
    suspend fun insertEvent(event: EventEntityV15): Long

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntityV14): Long
}

@Database(
    entities = [
        EventEntityV15::class,
        ExerciseEntityV14::class,
        xyz.oleolegka.gachimuchi.data.db.SlotEntity::class,
        xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity::class,
        ProgramEntityV8::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity::class,
    ],
    version = 14,
    exportSchema = false,
)
abstract class SchemaV14Database : RoomDatabase() {
    abstract fun catalog(): LegacyCatalogDaoV14
}

/**
 * The catalog of versions 15, 16 and 17: `identity_key` and `hidden` (added at 15), `edge_mm`
 * still on the row. Version 18 ([MIGRATION_17_18]) is what drops `edge_mm` and folds it into
 * the name, which is why this snapshot exists at all — before that change, "the exercises
 * table has not changed since 15" was true and [SchemaV15Database] and [SchemaV16Database]
 * could reuse the CURRENT entity for it, exactly as their own comments used to say. It no
 * longer is, so they reuse this instead.
 *
 * The identity key here is computed the OLD way — name, form, edge, work, rest folded
 * together — by hand rather than through [xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey],
 * because that function computes TODAY's key (three values, no edge) and using it here would
 * silently seed a version 17 phone with a version 18 key. The exact string does not matter to
 * the migration under test — [MIGRATION_17_18] recomputes every key from the columns — only
 * that it is a real, distinct value, which is all a genuine version 17 phone's column ever was.
 */
private fun legacyIdentityKeyV17(
    name: String,
    form: Int,
    edge: Double?,
    work: Double?,
    rest: Double?,
): String {
    fun num(v: Double?) = v?.toString() ?: ""
    return "$name|$form|${num(edge)}|${num(work)}|${num(rest)}"
}

@Entity(
    tableName = "exercises",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
        Index(value = ["space_id", "identity_key"], unique = true),
    ],
)
data class ExerciseEntityV17(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    val form: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "edge_mm") val edgeMm: Double? = null,
    @ColumnInfo(name = "protocol_work_sec") val protocolWorkSec: Double? = null,
    @ColumnInfo(name = "protocol_rest_sec") val protocolRestSec: Double? = null,
    @ColumnInfo(name = "default_rest_sec") val defaultRestSec: Int? = null,
    @ColumnInfo(name = "led_by_protocol") val ledByProtocol: Boolean? = null,
    val uid: String = xyz.oleolegka.gachimuchi.domain.newUid(),
    @ColumnInfo(name = "one_sided") val oneSided: Boolean = false,
    @ColumnInfo(name = "bodyweight_share") val bodyweightShare: Double? = null,
    val hidden: Boolean = false,
    @ColumnInfo(name = "identity_key")
    val identityKey: String = legacyIdentityKeyV17(name, form, edgeMm, protocolWorkSec, protocolRestSec),
)

@Dao
interface LegacyCatalogDaoV17 {
    @Insert
    suspend fun insertEvent(event: EventEntityV16): Long

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntityV17): Long
}

/**
 * Version 15: the catalog is already what it is today, and the journal still says WHEN in one
 * local clock with no zone and hides WHICH DAY inside the payload.
 *
 * Only the journal needs a snapshot of its own ([EventEntityV15]); the catalog reuses
 * [ExerciseEntityV17] rather than the current entity — see its own KDoc for why.
 */
@Dao
interface LegacyEventDaoV15 {
    @Insert
    suspend fun insert(event: EventEntityV15): Long
}

@Database(
    entities = [
        EventEntityV15::class,
        ExerciseEntityV17::class,
        xyz.oleolegka.gachimuchi.data.db.SlotEntity::class,
        xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity::class,
        ProgramEntityV8::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity::class,
    ],
    version = 15,
    exportSchema = false,
)
abstract class SchemaV15Database : RoomDatabase() {
    abstract fun events(): LegacyEventDaoV15
}

/**
 * The journal exactly as it stood from version 16 through version 21: every column that exists
 * on the live [xyz.oleolegka.gachimuchi.data.db.EventEntity] today EXCEPT `occurred_ts`, which
 * only arrived at version 22 ([xyz.oleolegka.gachimuchi.data.db.AppDatabase.Companion.MIGRATION_21_22]).
 *
 * Split out from the live entity for the same reason every other `EntityVNN` in this file
 * exists — see the note at the top: reusing the live class stopped being safe for this table
 * the moment it grew a column none of versions 16-21 had, and every snapshot in that span
 * ([SchemaV16Database], [SchemaV17Database]/[SchemaV18Database] via their `insertEvent`, and
 * [SchemaV20Database]) uses this one now instead.
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
        Index(value = ["space_id", "op_date"]),
    ],
)
data class EventEntityV16(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    @ColumnInfo(name = "author_id") val authorId: Long = LOCAL_AUTHOR_ID,
    val type: String,
    val payload: String,
    @ColumnInfo(name = "workout_id") val workoutId: Long? = null,
    val uid: String = xyz.oleolegka.gachimuchi.domain.newUid(),
    @ColumnInfo(name = "workout_uid") val workoutUid: String? = null,
    @ColumnInfo(name = "op_date") val opDate: String? = null,
    @ColumnInfo(name = "ts_utc") val tsUtc: String? = null,
    @ColumnInfo(name = "tz_offset_min") val tzOffsetMin: Int? = null,
)

@Dao
interface LegacyEventDaoV16 {
    @Insert
    suspend fun insert(event: EventEntityV16): Long
}

@Database(
    entities = [
        EventEntityV16::class,
        ExerciseEntityV17::class,
        xyz.oleolegka.gachimuchi.data.db.SlotEntity::class,
        xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity::class,
        ProgramEntityV8::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity::class,
    ],
    version = 16,
    exportSchema = false,
)
abstract class SchemaV16Database : RoomDatabase() {
    abstract fun events(): LegacyEventDaoV16
}

/**
 * Version 17: every table is already today's shape except the catalog, which still carries
 * `edge_mm` — the fact [MIGRATION_17_18] exists to fold away. The journal reuses the current
 * [xyz.oleolegka.gachimuchi.data.db.EventEntity] directly, exactly as [SchemaV16Database] does,
 * because nothing about it changes at this step either.
 */
@Database(
    entities = [
        EventEntityV16::class,
        ExerciseEntityV17::class,
        xyz.oleolegka.gachimuchi.data.db.SlotEntity::class,
        xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity::class,
        ProgramEntityV8::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity::class,
    ],
    version = 17,
    exportSchema = false,
)
abstract class SchemaV17Database : RoomDatabase() {
    abstract fun catalog(): LegacyCatalogDaoV17
}

/**
 * The identity key of schema version 18 — a bare `(name, form, work, rest)` pair, the shape
 * [MIGRATION_18_19] exists to fold into a program reference. Computed by hand rather than
 * through [xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey], for the same reason
 * [legacyIdentityKeyV17] is: that function computes TODAY's key (name, form, a program uid) and
 * using it here would silently seed a version 18 phone with a version 19 key. The exact string
 * does not matter to the migration under test — [MIGRATION_18_19] recomputes every key from the
 * columns via `fillIdentityKeysWithProgram` — only that it is a real, distinct value, which is
 * all a genuine version 18 phone's column ever was.
 */
private fun legacyIdentityKeyV18(name: String, form: Int, work: Double?, rest: Double?): String {
    fun num(v: Double?) = v?.toString() ?: ""
    return "$name|$form|${num(work)}|${num(rest)}"
}

@Entity(
    tableName = "exercises",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
        Index(value = ["space_id", "identity_key"], unique = true),
    ],
)
data class ExerciseEntityV18(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    val form: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "protocol_work_sec") val protocolWorkSec: Double? = null,
    @ColumnInfo(name = "protocol_rest_sec") val protocolRestSec: Double? = null,
    @ColumnInfo(name = "default_rest_sec") val defaultRestSec: Int? = null,
    @ColumnInfo(name = "led_by_protocol") val ledByProtocol: Boolean? = null,
    val uid: String = xyz.oleolegka.gachimuchi.domain.newUid(),
    @ColumnInfo(name = "one_sided") val oneSided: Boolean = false,
    @ColumnInfo(name = "bodyweight_share") val bodyweightShare: Double? = null,
    val hidden: Boolean = false,
    @ColumnInfo(name = "identity_key")
    val identityKey: String = legacyIdentityKeyV18(name, form, protocolWorkSec, protocolRestSec),
)

@Dao
interface LegacyCatalogDaoV18 {
    @Insert
    suspend fun insertEvent(event: EventEntityV16): Long

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntityV18): Long
}

/**
 * Version 18: every table is already today's shape except the catalog, which still carries
 * `protocol_work_sec`/`protocol_rest_sec` — the pair [MIGRATION_18_19] exists to fold into a
 * program in the timer's library. The journal reuses the current
 * [xyz.oleolegka.gachimuchi.data.db.EventEntity] directly, exactly as [SchemaV17Database] does,
 * because nothing about it changes at this step either.
 */
@Database(
    entities = [
        EventEntityV16::class,
        ExerciseEntityV18::class,
        xyz.oleolegka.gachimuchi.data.db.SlotEntity::class,
        xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity::class,
        ProgramEntityV8::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity::class,
    ],
    version = 18,
    exportSchema = false,
)
abstract class SchemaV18Database : RoomDatabase() {
    abstract fun catalog(): LegacyCatalogDaoV18
}

/**
 * The catalog exactly as it stood from version 19 through version 22: every column the live
 * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity] has today EXCEPT `picture_id`, which only
 * arrived at version 23 ([xyz.oleolegka.gachimuchi.data.db.AppDatabase.Companion.MIGRATION_22_23]).
 *
 * Split out from the live entity for the same reason [EventEntityV16] is — see its own KDoc:
 * reusing the live class stopped being safe for [SchemaV20Database] the moment it grew a
 * column version 20 never had.
 */
@Entity(
    tableName = "exercises",
    indices = [
        Index(value = ["space_id", "id"]),
        Index(value = ["uid"], unique = true),
        Index(value = ["space_id", "identity_key"], unique = true),
    ],
)
data class ExerciseEntityV20(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: Long = LOCAL_SPACE_ID,
    val name: String,
    val form: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "protocol_program_id") val protocolProgramId: Long? = null,
    @ColumnInfo(name = "default_rest_sec") val defaultRestSec: Int? = null,
    @ColumnInfo(name = "led_by_protocol") val ledByProtocol: Boolean? = null,
    val uid: String = xyz.oleolegka.gachimuchi.domain.newUid(),
    @ColumnInfo(name = "one_sided") val oneSided: Boolean = false,
    @ColumnInfo(name = "bodyweight_share") val bodyweightShare: Double? = null,
    val hidden: Boolean = false,
    @ColumnInfo(name = "identity_key")
    val identityKey: String = exerciseIdentityKey(name, form, null),
)

/**
 * Version 20: every table except the journal and the catalog already matches today's
 * [xyz.oleolegka.gachimuchi.data.db.AppDatabase] entities one for one, so those are reused
 * directly. The journal needs [EventEntityV16] and the catalog needs [ExerciseEntityV20] —
 * neither changes at the 20 -> 21 step (only content, see MIGRATION_20_21's own KDoc), but each
 * changes one version later: the journal at 21 -> 22 ([MIGRATION_21_22] adds `occurred_ts`),
 * the catalog at 22 -> 23 ([MIGRATION_22_23] adds `picture_id`) — and the live entities already
 * have those columns, which would seed a "version 20" database with columns version 20 never had.
 */
@Database(
    entities = [
        EventEntityV16::class,
        ExerciseEntityV20::class,
        xyz.oleolegka.gachimuchi.data.db.SlotEntity::class,
        xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramEntity::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity::class,
        xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity::class,
    ],
    version = 20,
    exportSchema = false,
)
abstract class SchemaV20Database : RoomDatabase() {
    abstract fun events(): LegacyEventDaoV16
}

/**
 * Migrating a phone that already has a training journal on it.
 *
 * The thing being protected is not the new tables — a mistake there fails loudly on the
 * next open. It is the JOURNAL: `fallbackToDestructiveMigration` is deliberately not
 * enabled (see AppDatabase), so a broken migration must be caught here rather than on a
 * phone where the only copy of somebody's training history lives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private var opened: RoomDatabase? = null

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(dbName)
    }

    /** Creates a version 1 database with some history in it, and closes it. */
    private suspend fun writeVersion1() {
        val v1 = Room.databaseBuilder(context, SchemaV1Database::class.java, dbName).build()
        opened = v1

        val exerciseId = v1.catalog().insertExercise(
            ExerciseEntityV3(
                name = "Hangs 20 mm", form = ExerciseForm.HOLD.code,
                createdAt = "2026-08-01T10:00:00", edgeMm = 20.0,
                protocolWorkSec = 7.0, protocolRestSec = 3.0,
            )
        )
        val set = strengthSetOf(
            exercise = xyz.oleolegka.gachimuchi.domain.ExerciseRef(
                id = exerciseId, name = "Bench press", form = ExerciseForm.STRENGTH,
            ),
            opDate = "2026-08-01", reps = 5, weightKg = 80.0,
        )
        v1.events().insert(
            EventEntityV4(ts = "2026-08-01T10:00:00", type = set.type, payload = set.toPayload())
        )
        v1.catalog().insertAlias(AliasEntityV3(key = "bench", value = exerciseId))
        v1.catalog().insertSlot(
            SlotEntityV3(
                name = "Gym", atTime = "19:00", repeatRule = "weekly",
                anchorDate = "2026-08-01", createdAt = "2026-08-01T09:00:00",
            )
        )
        v1.close()
        opened = null
    }

    /**
     * Creates a version 2 database with a program already in it, and closes it. This is the
     * phone the 2 -> 3 migration actually runs on: one that has been using the timer.
     */
    private suspend fun writeVersion2(): Long {
        val v2 = Room.databaseBuilder(context, SchemaV2Database::class.java, dbName).build()
        opened = v2

        v2.catalog().insertExercise(
            ExerciseEntityV3(
                name = "Hangs 20 mm", form = ExerciseForm.HOLD.code,
                createdAt = "2026-08-01T10:00:00", edgeMm = 20.0,
                protocolWorkSec = 7.0, protocolRestSec = 3.0,
            )
        )
        val programId = v2.programs().insertProgram(
            ProgramEntityV2(
                name = "Hangboard repeaters 7:3", prepareSec = 15, position = 0,
                createdAt = "2026-08-01T10:00:00",
            )
        )
        val groupId = v2.programs().insertGroup(
            ProgramGroupEntityV2(
                programId = programId, name = "Repeaters", position = 0, repeats = 4,
                restBetweenRepeatsSec = 180, restAfterSec = 0,
            )
        )
        v2.programs().insertBlock(
            ProgramBlockEntityV2(
                groupId = groupId, name = "Hang", position = 0,
                workSec = 7, restSec = 3, repeats = 6,
            )
        )
        v2.close()
        opened = null
        return programId
    }

    /**
     * Opens the database at the CURRENT version, running whatever migrations the file on
     * disk needs to get there. Every test below goes through this: what is being verified is
     * always "an old phone, upgraded to today's build", so there is only ever one target.
     */
    private fun openCurrent(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .also { opened = it }

    @Test
    fun `the journal, the catalog and the slots all survive the upgrade`() = runTest {
        writeVersion1()

        val v2 = openCurrent()

        val events = v2.events().all()
        assertEquals(1, events.size)
        assertTrue(events.single().payload.contains("Bench press"))

        val exercises = v2.exercises().all()
        assertEquals(1, exercises.size)
        // the edge this row carried through version 17 was folded into the name at the 17 -> 18
        // step (MIGRATION_17_18), unconditionally — the original name already read "20 mm" and
        // gets the suffix anyway, which is the documented, accepted quirk of an unconditional fold
        assertEquals("Hangs 20 mm 20mm", exercises.single().name)

        assertEquals(1, v2.slots().all().size)
    }

    @Test
    fun `the program tables exist after the upgrade and accept a program`() = runTest {
        writeVersion1()

        val v2 = openCurrent()
        val repo = ProgramRepository(v2)

        // one already: MIGRATION_18_19 folds writeVersion1's "Hangs 20 mm" protocol (7.0, 3.0)
        // into a library program of its own on the way past
        assertEquals(1, repo.count())
        val id = repo.save(
            WorkoutProgram(
                name = "Repeaters",
                prepareSec = 15,
                groups = listOf(
                    ProgramGroup(
                        name = "Repeaters",
                        blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 6)),
                        repeats = 4,
                        restBetweenRepeatsSec = 180,
                    )
                ),
            )
        )

        val stored = repo.programById(id)
        assertNotNull(stored)
        assertEquals("Repeaters", stored!!.name)
        assertEquals(6, stored.groups.single().blocks.single().repeats)
    }

    @Test
    fun `opening the upgraded database a second time passes Room's own schema check`() = runTest {
        writeVersion1()

        // the first open runs the migration and writes the new identity
        openCurrent().also { it.events().count() }.close()
        opened = null

        // the second open takes the "already at the right version" path, where Room
        // compares the database against the entity definitions and refuses a mismatch
        val again = openCurrent()
        assertEquals(1, again.events().count())
        // one already: see the comment on the previous test
        assertEquals(1, again.programs().countPrograms())
    }

    @Test
    fun `a fresh install creates the current version directly, without any migration`() = runTest {
        val fresh = openCurrent()
        assertEquals(0, fresh.events().count())
        assertEquals(0, fresh.programs().countPrograms())
    }

    // --- version 19 -> 20: a program in the library can be hidden ------------------------

    /**
     * MIGRATION_19_20 is additive on a table [MIGRATION_18_19] already rebuilds — this walks a
     * phone through the WHOLE chain (via [writeVersion1], which lands one program by way of the
     * 18 -> 19 fold) and checks the one new column reads what every migration before it
     * promises: nothing was hidden before there was a way to say so.
     */
    @Test
    fun `every program carried up from an old phone reads as not hidden`() = runTest {
        writeVersion1()

        val db = openCurrent()
        val programs = ProgramRepository(db).allPrograms()

        assertEquals(1, programs.size)
        assertTrue("a program from before hiding existed must not read as hidden", programs.none { it.hidden })
    }

    // --- version 2 -> 3: the program's exercise link and its category -----------------------

    @Test
    fun `a program written before the upgrade survives it, unlinked and uncategorised`() = runTest {
        val programId = writeVersion2()

        val v3 = openCurrent()
        val stored = ProgramRepository(v3).programById(programId)

        assertNotNull("a hand-written protocol must not be lost to a schema change", stored)
        assertEquals("Hangboard repeaters 7:3", stored!!.name)
        assertEquals(15, stored.prepareSec)
        assertEquals(6, stored.groups.single().blocks.single().repeats)
        assertEquals(4, stored.groups.single().repeats)
        // the new columns read as "nothing was said", which is what was true before
        assertNull(stored.exerciseId)
        assertEquals("", stored.category)
    }

    @Test
    fun `after the upgrade a program can be linked and filed, and both come back`() = runTest {
        val programId = writeVersion2()
        val v3 = openCurrent()
        val repo = ProgramRepository(v3)
        val exerciseId = v3.exercises().all().single().id

        repo.save(repo.programById(programId)!!.copy(category = "Hangboard"))
        repo.linkExercise(programId, exerciseId)

        val stored = repo.programById(programId)!!
        assertEquals(exerciseId, stored.exerciseId)
        assertEquals("Hangboard", stored.category)
        // and linking did not disturb the protocol itself
        assertEquals(6, stored.groups.single().blocks.single().repeats)
    }

    @Test
    fun `the upgraded database passes Room's schema check on the next open`() = runTest {
        writeVersion2()

        openCurrent().also { it.programs().countPrograms() }.close()
        opened = null

        // the second open compares the database against the entity definitions and refuses a
        // mismatch, which is what would catch a column type or a default written wrongly
        //
        // two: the hand-written "Hangboard repeaters 7:3" writeVersion2 wrote directly, plus
        // the minimal program MIGRATION_18_19 folds "Hangs 20 mm"'s protocol into on the way
        // past — the two are different shapes (repeats 4/6 against 1/1) so they do not merge
        val again = openCurrent()
        assertEquals(2, again.programs().countPrograms())
    }

    // --- version 3 -> 4: the demo-seed mark ------------------------------------------------

    /**
     * A phone in use before the mark existed: a catalog, a word, a plan and a journal entry,
     * none of which knows whether it came from the demo seed. This is the state the upgrade
     * has to be safe on, because it is the state the app is being upgraded from.
     */
    private suspend fun writeVersion3() {
        val v3 = Room.databaseBuilder(context, SchemaV3Database::class.java, dbName).build()
        opened = v3

        val exerciseId = v3.catalog().insertExercise(
            ExerciseEntityV3(
                name = "Bench press", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-05-01T10:00:00",
            )
        )
        val set = strengthSetOf(
            exercise = xyz.oleolegka.gachimuchi.domain.ExerciseRef(
                id = exerciseId, name = "Bench press", form = ExerciseForm.STRENGTH,
            ),
            opDate = "2026-05-01", reps = 5, weightKg = 80.0,
        )
        v3.events().insert(
            EventEntityV4(ts = "2026-05-01T10:00:00", type = set.type, payload = set.toPayload())
        )
        v3.catalog().insertAlias(AliasEntityV3(key = "bench", value = exerciseId))
        v3.catalog().insertSlot(
            SlotEntityV3(
                name = "Gym", atTime = "18:00", repeatRule = "weekly",
                anchorDate = "2026-05-04", createdAt = "2026-05-01T09:00:00",
            )
        )
        v3.close()
        opened = null
    }

    /**
     * The 3 -> 4 step added a column that 6 -> 7 has since taken away again, so what this
     * checks is no longer the column but the WALK: a phone at version 3 has to get through
     * an add of `seeded` and a drop of it without losing anything on the way, and the add is
     * still real code that still runs.
     */
    @Test
    fun `everything already on the phone survives the mark being added and dropped again`() =
        runTest {
            writeVersion3()

            val v7 = openCurrent()

            assertEquals(1, v7.events().count())
            assertEquals(1, v7.slots().all().size)
            assertEquals("Gym", v7.slots().all().single().name)
            val exercise = v7.exercises().all().single()
            assertEquals("Bench press", exercise.name)
        }

    @Test
    fun `the marked database passes Room's schema check on the next open`() = runTest {
        writeVersion3()

        openCurrent().also { it.events().count() }.close()
        opened = null

        // Room compares the database against the entity definitions here, which is what
        // catches a column declared NOT NULL in one place and nullable in the other
        val again = openCurrent()
        assertEquals(1, again.events().count())
        assertEquals(1, again.exercises().all().size)
    }

    // --- version 4 -> 5: the workout link and the exercise's rest preference ---------------

    /**
     * A phone in use before workouts existed: a journal of sets nobody started a workout for,
     * a catalog exercise, an alias and a plan slot. This is the version installed right now,
     * so it is the upgrade that will actually happen to somebody's data.
     */
    private suspend fun writeVersion4(): Long {
        val v4 = Room.databaseBuilder(context, SchemaV4Database::class.java, dbName).build()
        opened = v4

        val exerciseId = v4.catalog().insertExercise(
            ExerciseEntityV4(
                name = "Hangs 20 mm", form = ExerciseForm.HOLD.code,
                createdAt = "2026-07-01T10:00:00", edgeMm = 20.0,
                protocolWorkSec = 7.0, protocolRestSec = 3.0,
            )
        )
        for (day in listOf("2026-07-01", "2026-07-03")) {
            val set = strengthSetOf(
                exercise = xyz.oleolegka.gachimuchi.domain.ExerciseRef(
                    id = exerciseId, name = "Bench press", form = ExerciseForm.STRENGTH,
                ),
                opDate = day, reps = 5, weightKg = 80.0,
            )
            v4.events().insert(
                EventEntityV4(ts = "${day}T10:00:00", type = set.type, payload = set.toPayload())
            )
        }
        v4.catalog().insertAlias(AliasEntityV4(key = "bench", value = exerciseId))
        v4.catalog().insertSlot(
            SlotEntityV4(
                name = "Gym", atTime = "19:00", repeatRule = "weekly",
                anchorDate = "2026-07-01", createdAt = "2026-07-01T09:00:00",
            )
        )
        v4.close()
        opened = null
        return exerciseId
    }

    @Test
    fun `sets recorded before workouts existed survive, belonging to no workout`() = runTest {
        writeVersion4()

        val v5 = openCurrent()

        // the journal is intact, payloads included
        val events = v5.events().all()
        assertEquals(2, events.size)
        assertTrue(events.first().payload.contains("Bench press"))
        assertEquals(1, v5.slots().all().size)

        /*
         * And every one of those rows reads as "recorded outside any workout", which is the
         * literal truth about them: the app had no workouts when they were written, so there
         * is no workout they could honestly be filed under. Inventing one to fill the column
         * would have manufactured history that never happened.
         */
        assertTrue(events.all { it.workoutId == null })

        // same for the catalog: nothing has been said about the rest or about the protocol,
        // and null is how "nothing has been said" is spelled — see ExerciseEntity
        val exercise = v5.exercises().all().single()
        // the edge folds into the name at MIGRATION_17_18, unconditionally — see the earlier
        // test on writeVersion1 for why the suffix is appended even though it reads redundant
        assertEquals("Hangs 20 mm 20mm", exercise.name)
        assertNull(exercise.defaultRestSec)
        assertNull(exercise.ledByProtocol)
    }

    @Test
    fun `the columns the upgrade added are real and hold what is written to them`() = runTest {
        val exerciseId = writeVersion4()
        val repo = ActivityRepository(openCurrent())

        // this is the assertion that a column merely APPEARING is not enough: the migration
        // has to leave one a value round-trips through, in the type the entity declares
        val workoutId = repo.startWorkout(opDate = "2026-08-07")
        repo.addExerciseToWorkout(workoutId, exerciseId, restSec = 150)
        repo.setLedByProtocol(exerciseId, false)

        val stored = repo.exercise(exerciseId)!!
        assertEquals(150, stored.defaultRestSec)
        // false, not null: the two are different answers and a nullable INTEGER has to keep
        // them apart, which is exactly what a wrongly declared column would flatten
        assertEquals(false, stored.ledByProtocol)

        val added = repo.allEvents().single { it.type == TYPE_WORKOUT_EXERCISE_ADDED }
        assertEquals(workoutId, added.workoutId)
    }

    @Test
    fun `the workout columns pass Room's schema check on the next open`() = runTest {
        writeVersion4()

        openCurrent().also { it.events().count() }.close()
        opened = null

        // the second open is where Room compares the database against the entity definitions;
        // a column added as NOT NULL, or as the wrong affinity, is refused here rather than
        // on a phone
        val again = openCurrent()
        assertEquals(2, again.events().count())
        assertEquals(1, again.exercises().all().size)
    }

    // --- version 5 -> 6: what a planned session is made of ---------------------------------

    /**
     * A phone in use before a slot could say what it consisted of: a plan, a catalog, a word
     * and a journal entry filed under no workout. Returns the slot id, because everything
     * this migration is about hangs off it.
     */
    private suspend fun writeVersion5(): Long {
        val v5 = Room.databaseBuilder(context, SchemaV5Database::class.java, dbName).build()
        opened = v5

        val exerciseId = v5.catalog().insertExercise(
            ExerciseEntityV5(
                name = "Bench press", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-07-01T10:00:00", defaultRestSec = 150,
            )
        )
        val set = strengthSetOf(
            exercise = xyz.oleolegka.gachimuchi.domain.ExerciseRef(
                id = exerciseId, name = "Bench press", form = ExerciseForm.STRENGTH,
            ),
            opDate = "2026-07-01", reps = 5, weightKg = 80.0,
        )
        v5.catalog().insertEvent(
            EventEntityV7(ts = "2026-07-01T10:00:00", type = set.type, payload = set.toPayload())
        )
        v5.catalog().insertAlias(AliasEntityV4(key = "bench", value = exerciseId))
        val slotId = v5.catalog().insertSlot(
            SlotEntityV4(
                name = "Gym", atTime = "19:00", repeatRule = REPEAT_WEEKLY,
                anchorDate = "2026-07-01", createdAt = "2026-07-01T09:00:00",
            )
        )
        v5.close()
        opened = null
        return slotId
    }

    @Test
    fun `a plan written before compositions existed comes through with nothing planned in it`() =
        runTest {
            val slotId = writeVersion5()

            val repo = ActivityRepository(openCurrent())
            val slot = repo.slot(slotId)

            assertNotNull("a plan must not be lost to a schema change", slot)
            assertEquals("Gym", slot!!.name)
            assertEquals("19:00", slot.atTime)
            assertEquals(REPEAT_WEEKLY, slot.repeatRule)

            /*
             * And it reads as "nothing is planned in it", which is not a placeholder for data
             * that failed to migrate: an empty composition is a COMPLETE plan (see
             * domain/Schedule.kt), and it is the literal truth about a slot written by a build
             * that had no way to say anything else.
             */
            assertTrue(slot.exercises.isEmpty())
            assertTrue(repo.slotExercises(slotId).isEmpty())

            // and nothing else moved
            assertEquals(1, repo.eventCount())
            assertEquals(1, repo.allSlots().size)
        }

    @Test
    fun `the table the upgrade added is real and a composition round-trips through it`() = runTest {
        val slotId = writeVersion5()
        val db = openCurrent()
        val repo = ActivityRepository(db)
        val exerciseId = db.exercises().all().single().id

        // a table merely APPEARING is not enough: it has to hold values in the types the
        // entity declares, the optional rest included
        repo.saveSlot(
            repo.slot(slotId)!!.toDraft().copy(
                exercises = listOf(
                    PlannedExercise(exerciseId, restSec = 180),
                    PlannedExercise(exerciseId, restSec = null),
                )
            ),
            id = slotId,
        )

        val stored = repo.slot(slotId)!!.exercises
        assertEquals(listOf(exerciseId, exerciseId), stored.map { it.exerciseId })
        // null survives as null rather than collapsing into a zero: the two are different
        // answers, exactly as with led_by_protocol above
        assertEquals(listOf(180, null), stored.map { it.restSec })
    }

    @Test
    fun `deleting an upgraded slot takes its composition with it`() = runTest {
        val slotId = writeVersion5()
        val db = openCurrent()
        val repo = ActivityRepository(db)
        val exerciseId = db.exercises().all().single().id

        repo.saveSlot(
            repo.slot(slotId)!!.toDraft().copy(exercises = listOf(PlannedExercise(exerciseId))),
            id = slotId,
        )
        assertEquals(1, db.slots().allExercises().size)

        /*
         * The cascade has to survive the migration, and that is a property of the table the
         * MIGRATION wrote rather than of the entity: a CREATE TABLE without the foreign key
         * would still open, still store, and still read back — and would quietly leave a row
         * per deleted plan behind, reachable by nothing.
         */
        repo.deleteSlot(slotId)
        assertEquals(0, repo.allSlots().size)
        assertTrue(db.slots().allExercises().isEmpty())
    }

    @Test
    fun `the composition table passes Room's schema check on the next open`() = runTest {
        writeVersion5()

        openCurrent().also { it.events().count() }.close()
        opened = null

        // the second open compares the database against the entity definitions, which is what
        // catches a column affinity or an index name written differently here and there
        val again = openCurrent()
        assertEquals(1, again.events().count())
        assertEquals(1, again.slots().all().size)
    }

    // --- version 6 -> 7: the demo mark and the learned words go -----------------------------

    /** What a version 6 database was left holding, so the tests can name its rows. */
    private data class Phone(
        val slotId: Long,
        /** An exercise the demo seed created, mark and all. */
        val seededExerciseId: Long,
        /** An exercise DELETED before the upgrade — its id must never come back. */
        val goneExerciseId: Long,
    )

    /**
     * A phone with the demo seed on it, which is what version 6 shipped: a catalog where some
     * rows are marked as the seed's and some are the user's, a plan with a composition, a
     * journal, and a table of words the app taught itself.
     *
     * It also has a HOLE at the top of its catalog — an exercise created and deleted again —
     * because that is the state in which rebuilding a table can quietly start reissuing ids.
     */
    private suspend fun writeVersion6(): Phone {
        val v6 = Room.databaseBuilder(context, SchemaV6Database::class.java, dbName).build()
        opened = v6

        val mine = v6.catalog().insertExercise(
            ExerciseEntityV5(
                name = "Bench press", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-07-01T10:00:00", defaultRestSec = 150, ledByProtocol = false,
            )
        )
        val seeded = v6.catalog().insertExercise(
            ExerciseEntityV5(
                name = "Hangs 20 mm", form = ExerciseForm.HOLD.code,
                createdAt = "2026-07-01T10:00:00", edgeMm = 20.0,
                protocolWorkSec = 7.0, protocolRestSec = 3.0, seeded = true,
            )
        )
        val gone = v6.catalog().insertExercise(
            ExerciseEntityV5(
                name = "Overhead press", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-07-02T10:00:00",
            )
        )
        v6.catalog().deleteExercise(gone)

        val set = strengthSetOf(
            exercise = xyz.oleolegka.gachimuchi.domain.ExerciseRef(
                id = mine, name = "Bench press", form = ExerciseForm.STRENGTH,
            ),
            opDate = "2026-07-01", reps = 5, weightKg = 80.0,
        )
        v6.catalog().insertEvent(
            EventEntityV7(ts = "2026-07-01T10:00:00", type = set.type, payload = set.toPayload())
        )
        v6.catalog().insertAlias(AliasEntityV4(key = "bench", value = mine))
        v6.catalog().insertAlias(AliasEntityV4(key = "hang20", value = seeded, seeded = true))
        val slotId = v6.catalog().insertSlot(
            SlotEntityV4(
                name = "Gym", atTime = "19:00", repeatRule = REPEAT_WEEKLY,
                anchorDate = "2026-07-01", createdAt = "2026-07-01T09:00:00", seeded = true,
            )
        )
        v6.compositions().insert(
            SlotExerciseEntityV6(slotId = slotId, exerciseId = mine, position = 0, restSec = 180)
        )
        v6.compositions().insert(
            SlotExerciseEntityV6(slotId = slotId, exerciseId = seeded, position = 1, restSec = null)
        )
        v6.close()
        opened = null
        return Phone(slotId = slotId, seededExerciseId = seeded, goneExerciseId = gone)
    }

    @Test
    fun `dropping the seed mark keeps every row it was on, marked or not`() = runTest {
        val (slotId, seededId, _) = writeVersion6()

        val repo = ActivityRepository(openCurrent())

        /*
         * The exercise the seed created comes through exactly like the user's own. There is
         * no such thing as demo data any more, so "it was the demo's" is not a reason to
         * delete a row during an upgrade — silently, with no dialog and no undo, which is the
         * one kind of delete this app must never do.
         */
        assertEquals(2, repo.allExercises().size)
        val seeded = repo.exercise(seededId)!!
        // the edge folds into the name at MIGRATION_17_18, unconditionally
        assertEquals("Hangs 20 mm 20mm", seeded.name)

        // and the preferences of version 5 survive being copied into a rebuilt table
        val mine = repo.allExercises().single { it.name == "Bench press" }
        assertEquals(150, mine.defaultRestSec)
        // false, not null: the rebuild has to keep a nullable INTEGER nullable, or "the user
        // said no" collapses into "nothing has been said"
        assertEquals(false, mine.ledByProtocol)

        val slot = repo.slot(slotId)!!
        assertEquals("Gym", slot.name)
        assertEquals("19:00", slot.atTime)
        assertEquals(1, repo.eventCount())
    }

    @Test
    fun `rebuilding the plan does not take its composition with it`() = runTest {
        val (slotId, seededId, _) = writeVersion6()

        val repo = ActivityRepository(openCurrent())

        /*
         * THE ONE THING THAT COULD GO SILENTLY WRONG. `slots` cannot lose a column without
         * being dropped and recreated, and `slot_exercises` cascades from it — with foreign
         * keys enabled, dropping the parent deletes the children, and the upgrade would take
         * every planned session's contents away while looking like a success. Room runs
         * migrations before it turns foreign keys on, which is what makes the rebuild safe;
         * this is the assertion that says so rather than assuming it.
         */
        val planned = repo.slotExercises(slotId)
        assertEquals(2, planned.size)
        assertEquals(seededId, planned[1].exerciseId)
        // null survives as null, and the order survives as the order
        assertEquals(listOf(180, null), planned.map { it.restSec })
    }

    @Test
    fun `the cascade is still a cascade after the plan table is rebuilt`() = runTest {
        val (slotId, _, _) = writeVersion6()

        val db = openCurrent()
        val repo = ActivityRepository(db)

        // the rebuilt `slots` is a different table from the one `slot_exercises` was created
        // against, so the key that ties them has to be re-established by the migration and
        // not merely survive in the child's own DDL
        repo.deleteSlot(slotId)
        assertEquals(0, repo.allSlots().size)
        assertTrue(db.slots().allExercises().isEmpty())
    }

    @Test
    fun `an id that was handed out before the rebuild is never handed out again`() = runTest {
        val (_, _, goneId) = writeVersion6()

        val repo = ActivityRepository(openCurrent())

        /*
         * The rebuilt catalog holds no row with that id, so its AUTOINCREMENT counter would
         * restart below it — unless the migration carries the old counter across, which is
         * what this checks.
         *
         * It matters because the journal outlives the catalog: an entry keeps the
         * exercise_id of a row that has been deleted. Reissuing the id would silently
         * re-attach every one of those entries to whatever was created next, which is a
         * corruption nothing on any screen would show as one.
         */
        val fresh = repo.ensureExercise("Front squat", ExerciseForm.STRENGTH)
        assertTrue("id $fresh belonged to an exercise deleted before the upgrade", fresh > goneId)
    }

    @Test
    fun `the words the app taught itself are gone, table and all`() = runTest {
        writeVersion6()

        val db = openCurrent()
        // Room's own check on the second open only knows about tables it declares, so the one
        // that is supposed to be ABSENT has to be asked for directly — through the open helper
        // rather than through Room, whose query() refuses to run on the test's thread
        db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'aliases'")
            .use { assertEquals(0, it.count) }
    }

    @Test
    fun `the rebuilt tables pass Room's schema check on the next open`() = runTest {
        writeVersion6()

        openCurrent().also { it.events().count() }.close()
        opened = null

        /*
         * This is the assertion that catches a hand-written CREATE TABLE drifting from the
         * entity: a column affinity, a NOT NULL, an index name. Room compares the database
         * against the entity definitions on an open that runs no migration, and refuses a
         * mismatch — here rather than on a phone, where the refusal is a crash on launch.
         */
        val again = openCurrent()
        assertEquals(1, again.events().count())
        assertEquals(2, again.exercises().all().size)
        assertEquals(1, again.slots().all().size)
        assertEquals(2, again.slots().allExercises().size)
    }

    @Test
    fun `a phone that skipped every release in between still arrives intact`() = runTest {
        // 1 -> 8 in one go. Nobody upgrades one version at a time, and a chain that only ever
        // gets tested link by link is a chain whose middle is untested.
        writeVersion1()

        val current = openCurrent()

        assertEquals(1, current.events().count())
        assertTrue(current.events().all().single().workoutId == null)
        val exercise = current.exercises().all().single()
        assertNull(exercise.defaultRestSec)
        // the edge folds into the name at MIGRATION_17_18, unconditionally
        assertEquals("Hangs 20 mm 20mm", exercise.name)
        assertEquals(1, current.slots().all().size)
        assertTrue(current.slots().allExercises().isEmpty())
        // one: MIGRATION_18_19 folds this exercise's protocol into a library program too
        assertEquals(1, current.programs().countPrograms())
    }

    // --- version 7 -> 8: every row learns its own name --------------------------------------

    /** What a version 7 database was left holding. */
    private data class PhoneV7(
        val slotId: Long,
        val exerciseId: Long,
        val goneExerciseId: Long,
        val workoutStartId: Long = 0,
    )

    /**
     * A phone at version 7 with a real history on it: a catalog, a plan with a composition, a
     * program, and a journal of sets written on different days. It also has a HOLE at the top
     * of its catalog, because rebuilding five tables is exactly where an id counter gets lost.
     */
    private suspend fun writeVersion7(): PhoneV7 {
        val v7 = Room.databaseBuilder(context, SchemaV7Database::class.java, dbName).build()
        opened = v7

        val exerciseId = v7.catalog().insertExercise(
            ExerciseEntityV7(
                name = "Bench press", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-07-01T10:00:00", defaultRestSec = 150, ledByProtocol = false,
            )
        )
        val gone = v7.catalog().insertExercise(
            ExerciseEntityV7(
                name = "Overhead press", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-07-02T10:00:00",
            )
        )
        v7.catalog().deleteExercise(gone)

        for (day in listOf("2026-07-01", "2026-07-03", "2026-07-05")) {
            val set = strengthSetOf(
                exercise = xyz.oleolegka.gachimuchi.domain.ExerciseRef(
                    id = exerciseId, name = "Bench press", form = ExerciseForm.STRENGTH,
                ),
                opDate = day, reps = 5, weightKg = 80.0,
            )
            v7.catalog().insertEvent(
                EventEntityV7(ts = "${day}T10:00:00", type = set.type, payload = set.toPayload())
            )
        }

        val slotId = v7.catalog().insertSlot(
            SlotEntityV7(
                name = "Gym", atTime = "19:00", repeatRule = REPEAT_WEEKLY,
                anchorDate = "2026-07-01", createdAt = "2026-07-01T09:00:00",
            )
        )
        v7.catalog().insertComposition(
            SlotExerciseEntityV7(slotId = slotId, exerciseId = exerciseId, position = 0, restSec = 180)
        )
        v7.catalog().insertComposition(
            SlotExerciseEntityV7(slotId = slotId, exerciseId = exerciseId, position = 1, restSec = null)
        )

        /*
         * A workout started from that plan, and one set recorded inside it. Version 7 could
         * name neither the workout nor the plan by identity, which is what the 8 -> 9 and the
         * 10 -> 11 steps have to translate.
         *
         * The payload is spelled out as JSON rather than built from [WorkoutStarted]. That is
         * the whole difference between a fixture and a phone: the class writes every key it
         * knows about, `slot_uid` among them, so a payload built from it would carry the key
         * with a null in it — which is a shape version 7 never wrote. The migration has to
         * cope with the key being ABSENT, and it can only be shown to if the fixture leaves
         * it out.
         */
        val startId = v7.catalog().insertEvent(
            EventEntityV7(
                ts = "2026-07-05T18:00:00",
                type = TYPE_WORKOUT_STARTED,
                payload = """{"op_date":"2026-07-05","slot_id":$slotId}""",
            )
        )
        val inWorkout = strengthSetOf(
            exercise = xyz.oleolegka.gachimuchi.domain.ExerciseRef(
                id = exerciseId, name = "Bench press", form = ExerciseForm.STRENGTH,
            ),
            opDate = "2026-07-05", reps = 3, weightKg = 90.0,
        )
        v7.catalog().insertEvent(
            EventEntityV7(
                ts = "2026-07-05T18:10:00", type = inWorkout.type,
                payload = inWorkout.toPayload(), workoutId = startId,
            )
        )

        v7.catalog().insertProgram(
            ProgramEntityV3(
                name = "Repeaters 7:3", prepareSec = 15, position = 0,
                createdAt = "2026-06-01T10:00:00", category = "Hangboard",
            )
        )
        v7.close()
        opened = null
        return PhoneV7(
            slotId = slotId, exerciseId = exerciseId, goneExerciseId = gone,
            workoutStartId = startId,
        )
    }

    @Test
    fun `every row already on the phone comes out of the upgrade with an id of its own`() =
        runTest {
            val (slotId, _, _) = writeVersion7()

            val db = openCurrent()

            val uids = db.events().all().map { it.uid } +
                db.exercises().all().map { it.uid } +
                db.slots().all().map { it.uid } +
                db.slots().allExercises().map { it.uid } +
                db.programs().allPrograms().map { it.uid }

            // thirteen rows across five tables, and not one of them left holding the empty
            // string the rebuild inserted before the ids were handed out
            assertEquals(5 + 1 + 1 + 2 + 1, uids.size)
            assertTrue("a migrated row was left without a uid", uids.all { isUid(it) })
            assertEquals("two rows were given the same uid", uids.size, uids.toSet().size)

            // and nothing moved while that happened
            assertEquals(1, db.slots().all().size)
            assertEquals(2, ActivityRepository(db).slotExercises(slotId).size)
        }

    @Test
    fun `the ids of migrated rows sort the way the rows themselves do`() = runTest {
        writeVersion7()

        val db = openCurrent()

        /*
         * The whole reason for choosing UUIDv7 over v4: the leading 48 bits are the millisecond
         * the id was minted at, so plain string order is creation order. A migration that
         * stamped "now" onto every old row would satisfy the uniqueness check above and quietly
         * throw that property away, which is the kind of thing nothing else would ever notice.
         *
         * The sets were written on three different days, so their ids have to come out in the
         * same order as their timestamps.
         */
        val events = db.events().all().sortedBy { it.ts }
        assertEquals(events.map { it.uid }, events.map { it.uid }.sorted())
    }

    @Test
    fun `the uid index is unique, and the database says so rather than merely believing it`() =
        runTest {
            writeVersion7()

            val db = openCurrent()
            val existing = db.events().all().first().uid

            val clash = runCatching {
                db.events().insert(
                    EventEntity(
                        ts = "2026-07-06T10:00:00",
                        type = xyz.oleolegka.gachimuchi.domain.TYPE_TICK,
                        payload = """{"activity":"x","op_date":"2026-07-06","activity_key":"x"}""",
                        uid = existing,
                    )
                )
            }.exceptionOrNull()

            assertNotNull("two events were allowed to share a uid", clash)
        }

    @Test
    fun `an id handed out before the uid rebuild is never handed out again`() = runTest {
        val (_, _, goneId) = writeVersion7()

        val repo = ActivityRepository(openCurrent())

        // five tables were dropped and recreated in one migration, and every one of them
        // restarts its AUTOINCREMENT counter at its highest surviving id unless the counter is
        // carried across. The journal outlives the catalog, so a reissued exercise id silently
        // re-attaches old entries to a new exercise.
        val fresh = repo.ensureExercise("Front squat", ExerciseForm.STRENGTH)
        assertTrue("id $fresh belonged to an exercise deleted before the upgrade", fresh > goneId)
    }

    @Test
    fun `the composition still cascades after five tables are rebuilt`() = runTest {
        val (slotId, _, _) = writeVersion7()

        val db = openCurrent()
        val repo = ActivityRepository(db)

        // `slot_exercises` was recreated against a `slots` that was itself recreated, so the
        // key that ties them has to be re-established rather than merely survive
        repo.deleteSlot(slotId)
        assertEquals(0, repo.allSlots().size)
        assertTrue(db.slots().allExercises().isEmpty())
    }

    @Test
    fun `the uid columns pass Room's schema check on the next open`() = runTest {
        writeVersion7()

        openCurrent().also { it.events().count() }.close()
        opened = null

        val again = openCurrent()
        assertEquals(5, again.events().count())
        assertEquals(1, again.exercises().all().size)
        assertEquals(1, again.programs().countPrograms())
    }

    // --- version 8 -> 9: the workout link said in uids ---------------------------------------

    @Test
    fun `a set recorded inside a workout is repointed at that workout's uid`() = runTest {
        val phone = writeVersion7()

        val db = openCurrent()
        val events = db.events().all()

        val start = events.single { it.type == TYPE_WORKOUT_STARTED }
        assertEquals(phone.workoutStartId, start.id)

        /*
         * THE BACKFILL IS THE POINT OF THE STEP. Adding the column empty and letting only new
         * rows fill it would split every workout in two: the sets written before the upgrade
         * would be findable only by the number and the ones after it only by the uid, and the
         * reducers read the uid first.
         */
        val inWorkout = events.single { it.workoutId != null }
        assertEquals(start.uid, inWorkout.workoutUid)

        // and the rows that belonged to no workout still belong to none — a uid invented for
        // them would be a claim about a workout that never happened
        assertTrue(events.filter { it.id != inWorkout.id }.all { it.workoutUid == null })
    }

    // --- version 9 -> 10: entries name their exercise by identity ----------------------------

    @Test
    fun `every entry that can be resolved comes out naming its exercise by identity`() = runTest {
        val phone = writeVersion7()

        val repo = ActivityRepository(openCurrent())
        val exerciseUid = repo.exercise(phone.exerciseId)!!.uid

        val sets = repo.allEvents().mapNotNull { formFromEventOrNull(it.type, it.payload) }
        assertEquals(4, sets.size)
        assertTrue(
            "an entry was left naming its exercise only by number",
            sets.all { it.exerciseUid == exerciseUid },
        )
        // the number is kept beside it rather than replaced: a build older than version 10
        // still has to be able to read this journal
        assertTrue(sets.all { it.exerciseId == phone.exerciseId })
    }

    @Test
    fun `an entry written before the field existed is back-filled too, key and all`() = runTest {
        val phone = writeVersion7()
        /*
         * THE SHAPE THE TEST ABOVE CANNOT PRODUCE, and the one every real phone is full of.
         *
         * That fixture builds its payloads from StrengthSet, which is TODAY's class: written
         * with `encodeDefaults` it stores `"exercise_uid": null` — the key present, holding
         * nothing. A build old enough to predate the field wrote no key at all. The two are
         * different JSON, and the difference is what the 9 -> 10 backfill got wrong once
         * already: a null is a JsonNull, an object, not an absence, and "the key is there"
         * read it as a link.
         *
         * Both shapes resolve correctly now. Only one of them was ever exercised, and it was
         * the one no phone has — so a later change that made the backfill depend on the key
         * being present would break every upgrade and pass every test. Hence a payload spelled
         * out by hand, without the key.
         */
        val strayId = writeExtraRowAtVersion7(
            TYPE_STRENGTH_SET,
            """{"exercise":"Bench press","exercise_key":"bench press","reps":5,""" +
                """"weight_kg":80.0,"own_weight":false,"exercise_id":${phone.exerciseId},""" +
                """"op_date":"2026-07-04"}""",
        )

        val repo = ActivityRepository(openCurrent())
        val exerciseUid = repo.exercise(phone.exerciseId)!!.uid
        val events = repo.allEvents()

        val migrated = formFromEventOrNull(
            events.single { it.id == strayId }.type,
            events.single { it.id == strayId }.payload,
        )!!
        assertEquals(exerciseUid, migrated.exerciseUid)
        assertEquals(phone.exerciseId, migrated.exerciseId)

        // and it is filed with the rest of that exercise's history rather than under a key of
        // its own, which is the only symptom a missed row would ever have shown
        val link = ExerciseLink(exerciseUid, phone.exerciseId)
        assertEquals(5, formsOfExercise<StrengthSet>(events, link, TYPE_STRENGTH_SET).size)
    }

    @Test
    fun `the backfill keeps one exercise under one key rather than splitting its history`() =
        runTest {
            val phone = writeVersion7()

            val repo = ActivityRepository(openCurrent())
            val events = repo.allEvents()

            /*
             * THE POINT OF DOING THIS AT UPGRADE TIME. The reducers group by identity where an
             * entry has one and by number where it does not. Leave the old entries alone and
             * the exercise appears twice — half its history under "id:N", half under a uid —
             * with the records computed over half the sets and nothing looking broken.
             */
            val link = ExerciseLink(repo.exercise(phone.exerciseId)!!.uid, phone.exerciseId)
            assertEquals(4, formsOfExercise<StrengthSet>(events, link, TYPE_STRENGTH_SET).size)
            assertEquals(1, buildSession(events, "2026-07-05").groups.size)
        }

    @Test
    fun `an entry pointing at an exercise that is gone keeps its number and gets no identity`() =
        runTest {
            val phone = writeVersion7()

            // an entry naming the exercise that was deleted before the upgrade: there is no
            // identity to give it, and inventing one would attach this history to whatever is
            // created next
            val stray = strengthSetOf(
                exercise = xyz.oleolegka.gachimuchi.domain.ExerciseRef(
                    id = phone.goneExerciseId, name = "Overhead press", form = ExerciseForm.STRENGTH,
                ),
                opDate = "2026-07-04", reps = 5, weightKg = 40.0,
            )
            writeExtraEventAtVersion7(stray)

            val repo = ActivityRepository(openCurrent())
            val migrated = repo.allEvents()
                .mapNotNull { formFromEventOrNull(it.type, it.payload) }
                .single { it.exerciseId == phone.goneExerciseId }

            assertNull(migrated.exerciseUid)
            assertEquals(phone.goneExerciseId, migrated.exerciseId)
        }

    /** Appends one more event to the version 7 file already on disk, without upgrading it. */
    private suspend fun writeExtraEventAtVersion7(form: xyz.oleolegka.gachimuchi.domain.ActivityForm) =
        writeExtraRowAtVersion7(form.type, form.toPayload())

    /**
     * The same, for a payload spelled out by hand.
     *
     * Needed wherever the shape of the payload is the thing under test: every form class
     * writes all the keys it knows about, so a fixture built from one can only ever produce
     * TODAY's shape. An old phone's rows are missing the keys that did not exist yet, and that
     * difference is not cosmetic — a key holding null and a key that is absent read the same
     * to a careless check and differently to a correct one.
     */
    private suspend fun writeExtraRowAtVersion7(
        type: String,
        payload: String,
        ts: String = "2026-07-04T10:00:00",
    ): Long {
        val v7 = Room.databaseBuilder(context, SchemaV7Database::class.java, dbName).build()
        opened = v7
        val id = v7.catalog().insertEvent(EventEntityV7(ts = ts, type = type, payload = payload))
        v7.close()
        opened = null
        return id
    }

    @Test
    fun `the migrated workout folds out of the journal with all of its sets`() = runTest {
        val phone = writeVersion7()

        val repo = ActivityRepository(openCurrent())
        val workout = buildWorkout(repo.allEvents(), phone.workoutStartId)

        assertNotNull("the workout was lost in the upgrade", workout)
        assertEquals(1, workout!!.setCount)
        assertEquals(listOf(phone.exerciseId), workout.exercises.map { it.exerciseId })

        // the other three sets were written outside any workout and stay outside it
        assertEquals(1, setsOutsideWorkouts(repo.allEvents(), "2026-07-05").size)
    }

    // --- version 10 -> 11: a workout names its plan by identity ------------------------------

    /** The [WorkoutStarted] payload of one event, as the domain reads it back. */
    private fun startedPayload(events: List<JournalEvent>, id: Long): WorkoutStarted =
        payloadJson.decodeFromString(events.single { it.id == id }.payload)

    @Test
    fun `a workout started from a plan comes out naming that plan by identity`() = runTest {
        val phone = writeVersion7()

        val repo = ActivityRepository(openCurrent())
        val slotUid = repo.allSlots().single().uid

        val started = startedPayload(repo.allEvents(), phone.workoutStartId)
        assertEquals(slotUid, started.slotUid)
        // the number is kept beside it rather than replaced, so a build older than version 11
        // can still read this journal
        assertEquals(phone.slotId, started.slotId)
    }

    @Test
    fun `the backfilled plan link is the one the day's cards are decided by`() = runTest {
        val phone = writeVersion7()

        val repo = ActivityRepository(openCurrent())
        val workout = buildWorkout(repo.allEvents(), phone.workoutStartId)!!

        /*
         * THE POINT OF DOING THIS AT UPGRADE TIME. The readers compare plans through SlotLink,
         * which prefers identities whenever both sides have one. A start event left holding
         * only a number would be compared against a slot that now has a uid, and would keep
         * being matched by the number — right on this phone and wrong the moment either side
         * of the comparison has travelled.
         */
        val slot = repo.allSlots().single()
        assertTrue("the migrated workout lost the plan it was started from", workout.slot!!.matches(slot.link))
        assertEquals(slot.uid, workout.slot!!.uid)
    }

    @Test
    fun `a workout started off-plan is left naming no plan at all`() = runTest {
        writeVersion7()
        val strayId = writeExtraRowAtVersion7(
            TYPE_WORKOUT_STARTED,
            """{"op_date":"2026-07-04"}""",
            ts = "2026-07-04T18:00:00",
        )

        val repo = ActivityRepository(openCurrent())

        val started = startedPayload(repo.allEvents(), strayId)
        assertNull(started.slotUid)
        assertNull(started.slotId)
        assertNull(buildWorkout(repo.allEvents(), strayId)!!.slot)
    }

    // --- version 11 -> 12: a workout keeps the name it was started under ---------------------

    @Test
    fun `a workout started from a plan comes out carrying that plan's name`() = runTest {
        val phone = writeVersion7()

        val repo = ActivityRepository(openCurrent())

        assertEquals("Gym", startedPayload(repo.allEvents(), phone.workoutStartId).name)
    }

    @Test
    fun `the backfilled name is what the card shows, and renaming the plan no longer moves it`() =
        runTest {
            val phone = writeVersion7()

            val repo = ActivityRepository(openCurrent())
            val workout = buildWorkout(repo.allEvents(), phone.workoutStartId)!!
            assertEquals("Gym", workout.name)

            /*
             * WHY THE BACKFILL IS NOT OPTIONAL. The screens stop asking the plan what a
             * workout is called the moment this ships, so a start event left without a
             * snapshot is a workout that loses its name on upgrade. And with the snapshot in
             * place, editing the plan afterwards leaves the fact alone — which is the whole
             * reason for the field.
             */
            val slot = repo.allSlots().single()
            repo.saveSlot(slot.copy(name = "Powerlifting").toDraft(), id = slot.id)

            assertEquals("Powerlifting", repo.allSlots().single().name)
            assertEquals("Gym", buildWorkout(repo.allEvents(), phone.workoutStartId)!!.name)
        }

    @Test
    fun `a workout that named no plan is left nameless rather than given one`() = runTest {
        writeVersion7()
        val strayId = writeExtraRowAtVersion7(
            TYPE_WORKOUT_STARTED,
            """{"op_date":"2026-07-04"}""",
            ts = "2026-07-04T18:00:00",
        )

        val repo = ActivityRepository(openCurrent())

        assertNull(startedPayload(repo.allEvents(), strayId).name)
        assertNull(buildWorkout(repo.allEvents(), strayId)!!.name)
    }

    // --- version 12 -> 13: the catalog can say an exercise is one-sided ----------------------

    /** What a version 12 phone was left holding, so the assertions can name it afterwards. */
    private data class PhoneV12(val exerciseId: Long, val goneId: Long, val hangId: Long)

    /**
     * A version 12 database with a hangboard exercise, one hang logged against it, and a gap
     * at the top of the catalog left by a deleted exercise.
     *
     * The gap is the point of the second row: `exercises` is REBUILT by this migration, and a
     * rebuilt AUTOINCREMENT table restarts its counter at the highest surviving id.
     */
    private suspend fun writeVersion12(): PhoneV12 {
        val v12 = Room.databaseBuilder(context, SchemaV12Database::class.java, dbName).build()
        opened = v12

        val exerciseId = v12.catalog().insertExercise(
            ExerciseEntityV12(
                name = "One-arm hang 20 mm", form = ExerciseForm.HOLD.code,
                createdAt = "2026-08-01T10:00:00", edgeMm = 20.0,
                protocolWorkSec = 7.0, protocolRestSec = 3.0,
                defaultRestSec = 180, ledByProtocol = false,
            )
        )
        val goneId = v12.catalog().insertExercise(
            ExerciseEntityV12(
                name = "Deleted later", form = ExerciseForm.HOLD.code,
                createdAt = "2026-08-01T10:05:00",
            )
        )
        v12.catalog().deleteExercise(goneId)

        val hang = """{"activity":"One-arm hang 20 mm","added_kg":-15.0,"own_weight":true,""" +
            """"exercise_id":$exerciseId,"op_date":"2026-08-02",""" +
            """"activity_key":"one arm hang 20 mm"}"""
        val hangId = v12.catalog().insertEvent(
            EventEntityV15(ts = "2026-08-02T10:00:00", type = xyz.oleolegka.gachimuchi.domain.TYPE_HOLD_SET, payload = hang)
        )

        v12.close()
        opened = null
        return PhoneV12(exerciseId, goneId, hangId)
    }

    @Test
    fun `every exercise on the phone comes through the rebuild, two-handed`() = runTest {
        val phone = writeVersion12()

        val db = openCurrent()
        val exercise = db.exercises().byId(phone.exerciseId)

        assertNotNull("the catalog row was lost in the rebuild", exercise)
        // the edge folds into the name at MIGRATION_17_18, unconditionally
        assertEquals("One-arm hang 20 mm 20mm", exercise!!.name)
        assertEquals(180, exercise.defaultRestSec)
        assertEquals(false, exercise.ledByProtocol)
        assertTrue("the identity did not survive the rebuild", isUid(exercise.uid))

        /*
         * False, and it is a TRUE statement rather than a placeholder: nothing in the catalog
         * was one-sided before there was a way to say so. Marking it next week is new
         * information, not a correction of this.
         */
        assertFalse(exercise.oneSided)
    }

    @Test
    fun `an id handed out before the catalog rebuild is never handed out again`() = runTest {
        val phone = writeVersion12()

        val db = openCurrent()
        val fresh = db.exercises().insert(
            xyz.oleolegka.gachimuchi.data.db.ExerciseEntity(
                name = "Added after the upgrade", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-08-03T10:00:00",
            )
        )

        // the journal outlives the catalog, so a reissued id would silently re-attach the
        // entries of the deleted exercise to this new one
        assertTrue(
            "the rebuilt catalog reused id ${phone.goneId}",
            fresh > phone.goneId,
        )
    }

    @Test
    fun `the one-sided column passes Room's schema check on the next open`() = runTest {
        writeVersion12()

        openCurrent().close()
        opened = null

        val again = openCurrent()
        assertEquals(1, again.exercises().all().size)
    }

    @Test
    fun `a hang logged before sides existed keeps its history and names no side`() = runTest {
        val phone = writeVersion12()

        val repo = ActivityRepository(openCurrent())
        val events = repo.allEvents()
        val hang = events.single { it.id == phone.hangId }
        val form = formFromEventOrNull(hang.type, hang.payload) as xyz.oleolegka.gachimuchi.domain.HoldSet

        // the payload is untouched by this migration: no side is written into history that
        // nobody recorded
        assertNull(form.side)
        assertEquals(-15.0, form.addedKg!!, 1e-9)

        // and while the exercise stays two-handed the record reads exactly as it always did
        val records = xyz.oleolegka.gachimuchi.domain.holdRecord(
            xyz.oleolegka.gachimuchi.domain.readActivities(events),
            ExerciseLink.ofId(phone.exerciseId),
        )
        assertEquals(1, records.size)
        assertNull(records.single().side)
    }

    @Test
    fun `marking an exercise one-sided reads its sideless history as both hands`() = runTest {
        val phone = writeVersion12()

        val repo = ActivityRepository(openCurrent())
        repo.setOneSided(phone.exerciseId, true)
        assertTrue(repo.exercise(phone.exerciseId)!!.oneSided)

        /*
         * THE POINT OF THE FLAG BEING A COLUMN AND THE SIDE BEING A PAYLOAD FIELD. Turning it on
         * cannot rewrite the sets already logged, and it does not: the payload still names no
         * hand. What the flag changes is how they are READ. The owner's ruling (2026-08-11) is
         * that work done before the tick was symmetric, so each hand is credited with it — one
         * record per hand, and no third record of unknown side, which is what used to draw a
         * third column on the statistics.
         */
        val records = xyz.oleolegka.gachimuchi.domain.holdRecord(
            xyz.oleolegka.gachimuchi.domain.readActivities(repo.allEvents()),
            ExerciseLink.ofId(phone.exerciseId),
            oneSided = true,
        )
        assertEquals(2, records.size)
        assertEquals(
            listOf(xyz.oleolegka.gachimuchi.domain.HoldSide.LEFT, xyz.oleolegka.gachimuchi.domain.HoldSide.RIGHT),
            records.map { it.side },
        )
        assertTrue(records.none { it.text.contains("side not recorded") })
    }

    // --- version 13 -> 14: the share of body weight, and the snapshots behind it -------------

    /** A version 12 phone whose owner does pull-ups and sometimes stands on the scales. */
    private data class PhoneWithScales(
        val exerciseId: Long,
        val beforeTheScales: Long,
        val betweenWeighIns: Long,
    )

    /**
     * A version 12 database holding a body-weight history that straddles two weigh-ins, plus
     * one set from before the scales were ever used.
     *
     * The dates are the point. The backfill has to match a set to what the scales said ON OR
     * BEFORE ITS OWN DAY, and a fixture where every set postdates every weigh-in would pass
     * just as happily with a migration that stamped the latest reading onto everything.
     */
    private suspend fun writeVersion12WithScales(): PhoneWithScales {
        val v12 = Room.databaseBuilder(context, SchemaV12Database::class.java, dbName).build()
        opened = v12

        val exerciseId = v12.catalog().insertExercise(
            ExerciseEntityV12(
                name = "Pull-ups", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-06-01T10:00:00",
            )
        )

        fun pullUp(day: String) =
            """{"exercise":"Pull-ups","reps":8,"own_weight":true,""" +
                """"exercise_id":$exerciseId,"op_date":"$day","exercise_key":"pull ups"}"""

        fun weighIn(kg: Double, day: String) = """{"weight_kg":$kg,"op_date":"$day"}"""

        val beforeTheScales = v12.catalog().insertEvent(
            EventEntityV15(ts = "2026-06-01T10:00:00", type = TYPE_STRENGTH_SET, payload = pullUp("2026-06-01"))
        )
        v12.catalog().insertEvent(
            EventEntityV15(
                ts = "2026-07-01T08:00:00",
                type = xyz.oleolegka.gachimuchi.domain.TYPE_BODYWEIGHT,
                payload = weighIn(72.0, "2026-07-01"),
            )
        )
        val betweenWeighIns = v12.catalog().insertEvent(
            EventEntityV15(ts = "2026-07-15T10:00:00", type = TYPE_STRENGTH_SET, payload = pullUp("2026-07-15"))
        )
        v12.catalog().insertEvent(
            EventEntityV15(
                ts = "2026-08-01T08:00:00",
                type = xyz.oleolegka.gachimuchi.domain.TYPE_BODYWEIGHT,
                payload = weighIn(69.0, "2026-08-01"),
            )
        )

        v12.close()
        opened = null
        return PhoneWithScales(exerciseId, beforeTheScales, betweenWeighIns)
    }

    /** The [StrengthSet] one event carries, as the domain reads it back. */
    private fun strengthPayload(events: List<JournalEvent>, id: Long): StrengthSet =
        formFromEventOrNull(TYPE_STRENGTH_SET, events.single { it.id == id }.payload) as StrengthSet

    @Test
    fun `a set logged before the upgrade is stamped with what the scales said on its own day`() =
        runTest {
            val phone = writeVersion12WithScales()

            val repo = ActivityRepository(openCurrent())
            val events = repo.allEvents()

            // 72 kg and not 69: the set is from July, and the August weigh-in had not happened
            assertEquals(72.0, strengthPayload(events, phone.betweenWeighIns).bodyweightKg!!, 1e-9)
        }

    @Test
    fun `a set older than every weigh-in is left without a snapshot`() = runTest {
        val phone = writeVersion12WithScales()

        val repo = ActivityRepository(openCurrent())

        /*
         * There is no honest number for it. Reaching forward to the first later weigh-in would
         * be claiming to know what somebody weighed before they had ever weighed themselves,
         * and the set stays worth nothing on the tonnage chart — exactly as it was.
         */
        assertNull(strengthPayload(repo.allEvents(), phone.beforeTheScales).bodyweightKg)
    }

    @Test
    fun `the share column arrives empty, and until it is filled in nothing moves`() = runTest {
        val phone = writeVersion12WithScales()

        val repo = ActivityRepository(openCurrent())
        assertNull(repo.exercise(phone.exerciseId)!!.bodyweightShare)

        // no share, so the chart still counts reps, which is what it counted yesterday
        val series = xyz.oleolegka.gachimuchi.domain.volumeSeries(
            xyz.oleolegka.gachimuchi.domain.readActivities(repo.allEvents()),
            ExerciseLink.ofId(phone.exerciseId),
            ExerciseForm.STRENGTH,
        )!!
        assertEquals("Reps", series.spec.label)
    }

    @Test
    fun `filling in the share turns the backfilled history into tonnage, not a wall of zeros`() =
        runTest {
            val phone = writeVersion12WithScales()

            val repo = ActivityRepository(openCurrent())
            repo.setBodyweightShare(phone.exerciseId, 1.0)
            assertEquals(1.0, repo.exercise(phone.exerciseId)!!.bodyweightShare!!, 1e-9)

            /*
             * WHY THE BACKFILL IS NOT OPTIONAL. Without it this chart switches from counting
             * reps to counting kilograms and every day before the upgrade draws as zero — a
             * history that reads as nothing followed by a wall, which is worse than the flat
             * rep count it replaced.
             */
            val series = xyz.oleolegka.gachimuchi.domain.volumeSeries(
                xyz.oleolegka.gachimuchi.domain.readActivities(repo.allEvents()),
                ExerciseLink.ofId(phone.exerciseId),
                ExerciseForm.STRENGTH,
                1.0,
            )!!
            assertEquals("Volume, reps x weight", series.spec.label)

            val july = series.points.single { it.opDate == "2026-07-15" }
            assertEquals(72.0 * 8, july.value, 1e-9)

            // and the set from before the scales is still honestly worth nothing
            val june = series.points.single { it.opDate == "2026-06-01" }
            assertEquals(0.0, june.value, 1e-9)
        }

    @Test
    fun `a set written after the upgrade is stamped without anybody asking`() = runTest {
        writeVersion12WithScales()

        val repo = ActivityRepository(openCurrent())
        val exercise = repo.allExercises().single { it.name == "Pull-ups" }
        val id = repo.record(
            strengthSetOf(
                exercise = exercise.toRef(), opDate = "2026-08-05", reps = 6, ownWeight = true,
            ),
            attachToWorkout = false,
        )

        // stamped in the repository rather than on the screen: one method sees every write
        assertEquals(69.0, strengthPayload(repo.allEvents(), id).bodyweightKg!!, 1e-9)
    }

    @Test
    fun `the share column passes Room's schema check on the next open`() = runTest {
        writeVersion12WithScales()

        openCurrent().close()
        opened = null

        val again = openCurrent()
        assertEquals(1, again.exercises().all().size)
    }

    @Test
    fun `a workout whose plan has been deleted keeps its number and gets no identity`() = runTest {
        val phone = writeVersion7()
        // a plan that is gone: there is no identity to give this workout, and minting one
        // would be a claim about a plan this database has never held
        val strayId = writeExtraRowAtVersion7(
            TYPE_WORKOUT_STARTED,
            """{"op_date":"2026-07-04","slot_id":${phone.slotId + 99}}""",
            ts = "2026-07-04T18:00:00",
        )

        val repo = ActivityRepository(openCurrent())

        val started = startedPayload(repo.allEvents(), strayId)
        assertNull(started.slotUid)
        assertEquals(phone.slotId + 99, started.slotId)
    }

    // --- version 14 -> 15: the identity becomes a constraint, and rows can be hidden --------

    /** What a version 14 phone was left holding, so the assertions can name it afterwards. */
    private data class PhoneV14(
        val hangsId: Long,
        val twinId: Long,
        val benchId: Long,
        val setOnTwinId: Long,
    )

    /**
     * A version 14 database whose catalog holds a pair of rows that version 15 would refuse:
     * the same name, form, edge and protocol, twice.
     *
     * It is written through a version 14 database on purpose. The pair cannot be created
     * through today's app at all — which is the point of the whole change — so the only honest
     * way to ask "what does the upgrade do with one" is to write it as the old schema could,
     * and a hand-edited or restored catalog is exactly where such a pair comes from.
     *
     * A hang is logged against the SECOND of the two, because that is the row the upgrade will
     * mark, and "the marked row keeps its own history" is the thing worth proving.
     */
    private suspend fun writeVersion14(): PhoneV14 {
        val v14 = Room.databaseBuilder(context, SchemaV14Database::class.java, dbName).build()
        opened = v14

        val hangsId = v14.catalog().insertExercise(
            ExerciseEntityV14(
                name = "Hangs", form = ExerciseForm.HOLD.code, createdAt = "2026-08-01T10:00:00",
                edgeMm = 20.0, protocolWorkSec = 7.0, protocolRestSec = 3.0,
                defaultRestSec = 180, ledByProtocol = false, oneSided = true,
                bodyweightShare = 1.0,
            )
        )
        // the same exercise again, spelled differently — one identity, two rows
        val twinId = v14.catalog().insertExercise(
            ExerciseEntityV14(
                name = "HANGS", form = ExerciseForm.HOLD.code, createdAt = "2026-08-01T10:05:00",
                edgeMm = 20.0, protocolWorkSec = 7.0, protocolRestSec = 3.0,
            )
        )
        val benchId = v14.catalog().insertExercise(
            ExerciseEntityV14(
                name = "Bench press", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-08-01T10:10:00",
            )
        )

        val hang = """{"activity":"hangs","added_kg":10.0,"own_weight":true,""" +
            """"exercise_id":$twinId,"op_date":"2026-08-02","activity_key":"hangs"}"""
        val setOnTwinId = v14.catalog().insertEvent(
            EventEntityV15(
                ts = "2026-08-02T10:00:00",
                type = xyz.oleolegka.gachimuchi.domain.TYPE_HOLD_SET,
                payload = hang,
            )
        )

        v14.close()
        opened = null
        return PhoneV14(hangsId, twinId, benchId, setOnTwinId)
    }

    /**
     * The upgrade must not fail on a catalog it disagrees with, and must not silently make the
     * disagreement go away either. Both rows survive; the later one is marked so the index can
     * exist at all.
     *
     * The names asserted here are the ones AFTER the whole chain, not after 14 -> 15 alone: both
     * rows carry an edge, and MIGRATION_17_18 folds it into every name a few steps later, which
     * is exactly the "(2)" riding along into the folded name that MIGRATION_17_18's own KDoc
     * accepts as a narrow, harmless quirk of a multi-hop upgrade rather than something to
     * engineer around.
     */
    @Test
    fun `two catalog rows claiming one identity both come through, the later one marked`() = runTest {
        val phone = writeVersion14()

        val db = openCurrent()
        val kept = db.exercises().byId(phone.hangsId)
        val marked = db.exercises().byId(phone.twinId)

        assertNotNull("the first row was lost", kept)
        assertNotNull("the duplicate was deleted rather than kept", marked)
        assertEquals("the row that was there first keeps its name", "Hangs 20mm", kept!!.name)
        assertEquals("HANGS (2) 20mm", marked!!.name)
        assertNotEquals(kept.identityKey, marked.identityKey)
        assertEquals(3, db.exercises().all().size)
    }

    /**
     * The reason renaming was chosen over merging: nothing about the history moves. The hang
     * logged against the marked row is still the marked row's, and the row it was logged
     * against still has the uid it had.
     */
    @Test
    fun `the marked row keeps its identity and everything logged against it`() = runTest {
        val phone = writeVersion14()

        val db = openCurrent()
        val repo = ActivityRepository(db)
        val marked = db.exercises().byId(phone.twinId)!!

        assertTrue("the uid must not be reissued by a rebuild", isUid(marked.uid))
        val sets = holdSetsOfExercise(repo.allEvents(), marked.toRef().link)
        assertEquals("the set left the row it was logged against", 1, sets.size)
        assertEquals(10.0, sets.single().addedKg!!, 1e-9)
        // and it did not land on the row that kept the name
        assertTrue(holdSetsOfExercise(repo.allEvents(), db.exercises().byId(phone.hangsId)!!.toRef().link).isEmpty())
    }

    /**
     * Everything the catalog knew before the rebuild is still there afterwards — the edge
     * included, though by the time the chain reaches today's version it has moved from its own
     * column into the name (MIGRATION_17_18), which is why the name asserted here is not the
     * one [writeVersion14] wrote.
     */
    @Test
    fun `every column of a catalog row survives the rebuild, and none of them is hidden`() = runTest {
        val phone = writeVersion14()

        val db = openCurrent()
        val exercise = db.exercises().byId(phone.hangsId)!!
        val ref = ActivityRepository(db).toRef(exercise)

        assertEquals("Hangs 20mm", exercise.name)
        assertEquals(7.0, ref.workSec!!, 1e-9)
        assertEquals(3.0, ref.restSec!!, 1e-9)
        assertEquals(180, exercise.defaultRestSec)
        assertEquals(false, exercise.ledByProtocol)
        assertTrue(exercise.oneSided)
        assertEquals(1.0, exercise.bodyweightShare!!, 1e-9)
        /*
         * False, and it is a TRUE statement rather than a placeholder: nothing in the catalog
         * was hidden before there was a way to hide it.
         */
        assertFalse(exercise.hidden)
        val programUid = db.programs().programById(exercise.protocolProgramId!!)!!.uid
        assertEquals(
            exerciseIdentityKey("Hangs 20mm", ExerciseForm.HOLD.code, programUid),
            exercise.identityKey,
        )
    }

    /**
     * A catalog with no duplicates in it must come through with nobody's name touched. The
     * marking is a last resort, and a phone that never needed it should not be able to tell
     * this migration ran.
     */
    @Test
    fun `a catalog with nothing to resolve comes through with every name as it was`() = runTest {
        val phone = writeVersion14()

        val bench = openCurrent().exercises().byId(phone.benchId)!!

        assertEquals("Bench press", bench.name)
        assertEquals(exerciseIdentityKey("Bench press", ExerciseForm.STRENGTH.code), bench.identityKey)
    }

    @Test
    fun `the identity index is unique after the upgrade, and the database says so itself`() = runTest {
        writeVersion14()

        val db = openCurrent()
        val failure = runCatching {
            db.exercises().insert(
                xyz.oleolegka.gachimuchi.data.db.ExerciseEntity(
                    name = "bench press", form = ExerciseForm.STRENGTH.code,
                    createdAt = "2026-08-07T10:00:00",
                )
            )
        }.exceptionOrNull()

        assertTrue(
            "expected the upgraded database to refuse a duplicate, got $failure",
            failure is android.database.sqlite.SQLiteConstraintException,
        )
    }

    @Test
    fun `an id handed out before the identity rebuild is never handed out again`() = runTest {
        val phone = writeVersion14()

        val db = openCurrent()
        val fresh = db.exercises().insert(
            xyz.oleolegka.gachimuchi.data.db.ExerciseEntity(
                name = "Front squat", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-08-07T10:00:00",
            )
        )

        assertTrue("the rebuilt table restarted its counter", fresh > phone.benchId)
    }

    @Test
    fun `the identity and hidden columns pass Room's schema check on the next open`() = runTest {
        writeVersion14()

        openCurrent().close()
        opened = null

        val again = openCurrent()
        assertEquals(3, again.exercises().all().size)
    }

    // --- version 15 -> 16: the day becomes a column and the time becomes a moment -----------

    /** What a version 15 phone was left holding, so the assertions can name the rows. */
    private data class PhoneV15(
        val setId: Long,
        val backdatedId: Long,
        val cancelId: Long,
        val brokenId: Long,
    )

    /**
     * A version 15 journal with one of each kind of row the upgrade has to have an answer for:
     * a set logged on the day it happened, a set typed up a fortnight late, a reversal that is
     * about no training day at all, and a payload that will not parse.
     */
    private suspend fun writeVersion15(): PhoneV15 {
        val v15 = Room.databaseBuilder(context, SchemaV15Database::class.java, dbName).build()
        opened = v15

        fun set(day: String) =
            """{"exercise":"Bench press","reps":5,"weight_kg":80.0,""" +
                """"op_date":"$day","exercise_key":"bench press"}"""

        val setId = v15.events().insert(
            EventEntityV15(ts = "2026-08-06T10:00:00", type = TYPE_STRENGTH_SET, payload = set("2026-08-06"))
        )
        // written on the 6th, about the 1st: the two facts the column has to keep apart
        val backdatedId = v15.events().insert(
            EventEntityV15(ts = "2026-08-06T21:30:00", type = TYPE_STRENGTH_SET, payload = set("2026-08-01"))
        )
        val cancelId = v15.events().insert(
            EventEntityV15(
                ts = "2026-08-06T22:00:00", type = TYPE_SET_CANCEL,
                payload = """{"cancels":$setId}""",
            )
        )
        val brokenId = v15.events().insert(
            EventEntityV15(ts = "2026-08-06T23:00:00", type = TYPE_STRENGTH_SET, payload = "{not json")
        )

        v15.close()
        opened = null
        return PhoneV15(setId, backdatedId, cancelId, brokenId)
    }

    private suspend fun rowById(db: AppDatabase, id: Long) = db.events().byId(id)!!

    @Test
    fun `the day inside the payload becomes a column, and the day written on stays out of it`() =
        runTest {
            val phone = writeVersion15()
            val db = openCurrent()

            assertEquals("2026-08-06", rowById(db, phone.setId).opDate)
            // the backfill takes the day the training belongs to, never the day it was typed up
            assertEquals("2026-08-01", rowById(db, phone.backdatedId).opDate)
            assertEquals("2026-08-06T21:30:00", rowById(db, phone.backdatedId).ts)
        }

    @Test
    fun `a row that is about no training day gets no day, and a broken payload costs only itself`() =
        runTest {
            val phone = writeVersion15()
            val db = openCurrent()

            assertNull("a reversal belongs to no training day", rowById(db, phone.cancelId).opDate)
            val broken = rowById(db, phone.brokenId)
            assertNull("nothing may be invented for a payload nobody can read", broken.opDate)
            assertEquals("the row itself must survive", "{not json", broken.payload)
            // and its write time is still known: the timestamp is a column, not part of the mess
            assertNotNull(broken.tsUtc)
        }

    /**
     * The instant and the offset are filled in from the local clock and the device's zone, and
     * the pair is REVERSIBLE: applying the offset to the instant gives back exactly the string
     * that was stored, whatever zone this test happens to run in.
     *
     * That round trip is the assertion worth making. Comparing against a hand-written UTC string
     * would only pass in the zone it was written for, and comparing against the app's own
     * conversion would be the code agreeing with itself.
     */
    @Test
    fun `the write time becomes an instant plus the offset it was written at`() = runTest {
        val phone = writeVersion15()
        val db = openCurrent()

        val row = rowById(db, phone.setId)
        val utc = row.tsUtc!!
        val offset = row.tzOffsetMin!!

        assertTrue("the instant must say it is one", utc.endsWith("Z"))
        assertEquals("fixed width, or text ordering is a lie", 20, utc.length)

        val backToLocal = java.time.LocalDateTime.parse(utc.dropLast(1))
            .plusMinutes(offset.toLong())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        assertEquals(row.ts, backToLocal)
    }

    /** Every row comes through, and the reducers read the upgraded journal as they always did. */
    @Test
    fun `the journal survives, and reading it by date still finds what is in it`() = runTest {
        writeVersion15()
        val db = openCurrent()
        val repo = ActivityRepository(db)

        assertEquals(4, db.events().all().size)
        val events = repo.allEvents()
        // the cancelled set is gone from the reading, the unreadable row is skipped, and the
        // backdated one is found under the day it belongs to rather than the day it was written
        assertEquals(
            listOf("2026-08-01"),
            readActivities(events).map { it.opDate },
        )
        assertEquals(1, readActivities(events, dateFrom = "2026-08-01", dateTo = "2026-08-01").size)
        assertTrue(readActivities(events, dateFrom = "2026-08-02").isEmpty())
    }

    /** A row this build writes carries all three, so nothing has to be inferred twice. */
    @Test
    fun `a set written after the upgrade carries its day and its instant`() = runTest {
        writeVersion15()
        val db = openCurrent()
        val repo = ActivityRepository(db)

        val id = repo.record(
            strengthSetOf(
                ExerciseRef(id = 1, name = "Bench press", form = ExerciseForm.STRENGTH),
                opDate = "2026-08-07", reps = 5, weightKg = 80.0,
            ),
            attachToWorkout = false,
        )

        val row = rowById(db, id)
        assertEquals("2026-08-07", row.opDate)
        assertNotNull(row.tsUtc)
        assertNotNull(row.tzOffsetMin)
    }

    @Test
    fun `the time columns pass Room's schema check on the next open`() = runTest {
        writeVersion15()

        openCurrent().close()
        opened = null

        val again = openCurrent()
        assertEquals(4, again.events().all().size)
    }

    // --- version 16 -> 17: the length of one hold, backfilled from the protocol it was under ---

    private data class PhoneV16(val protocolLedId: Long, val plankId: Long)

    /**
     * A version 16 journal with two hold sets nobody had a chance to state a length for: one
     * under a work:rest protocol (the length IS the protocol's work half, §12-A), and one with
     * no protocol at all — a plank, which has never had anything to fall back to.
     *
     * Neither payload carries `hold_sec` — this is what every hold set has looked like since
     * the field existed and nothing wrote it, which is the defect [MIGRATION_16_17] closes.
     */
    private suspend fun writeVersion16(): PhoneV16 {
        val v16 = Room.databaseBuilder(context, SchemaV16Database::class.java, dbName).build()
        opened = v16

        val protocolLedId = v16.events().insert(
            EventEntityV16(
                ts = "2026-07-01T10:00:00", type = TYPE_HOLD_SET,
                payload = """{"activity":"Hangs 20 mm","reps":6,"work_sec":7.0,"rest_sec":3.0,""" +
                    """"op_date":"2026-07-01","activity_key":"hangs 20 mm"}""",
            )
        )
        val plankId = v16.events().insert(
            EventEntityV16(
                ts = "2026-07-01T10:05:00", type = TYPE_HOLD_SET,
                payload = """{"activity":"Plank","op_date":"2026-07-01","activity_key":"plank"}""",
            )
        )

        v16.close()
        opened = null
        return PhoneV16(protocolLedId, plankId)
    }

    /** The [xyz.oleolegka.gachimuchi.domain.HoldSet] one event carries, as the domain reads it back. */
    private fun holdPayload(events: List<JournalEvent>, id: Long) =
        formFromEventOrNull(TYPE_HOLD_SET, events.single { it.id == id }.payload)
            as xyz.oleolegka.gachimuchi.domain.HoldSet

    @Test
    fun `a protocol-led hold is given the length its own snapshot always stated`() = runTest {
        val phone = writeVersion16()

        val repo = ActivityRepository(openCurrent())
        val hold = holdPayload(repo.allEvents(), phone.protocolLedId)

        // the work half of the protocol IS the length of one hold under it (§12-A) — nothing
        // invented, just finally written down
        assertEquals(7.0, hold.holdSec!!, 1e-9)
        assertEquals(7.0, hold.workSec!!, 1e-9)

        // and the record axis this always blocked can fire on it now
        val record = xyz.oleolegka.gachimuchi.domain.evaluateHoldRecord(
            emptyList(), hold,
        )
        assertNull("the FIRST hold of an exercise is a baseline, not a record over nothing", record)
    }

    @Test
    fun `a hold with no protocol snapshot is left exactly as it was - there is nothing honest to invent`() =
        runTest {
            val phone = writeVersion16()

            val repo = ActivityRepository(openCurrent())
            val plank = holdPayload(repo.allEvents(), phone.plankId)

            assertNull(plank.holdSec)
            assertNull(plank.workSec)
        }

    @Test
    fun `a hold set written after the upgrade is unaffected - the entry card states its own length`() =
        runTest {
            writeVersion16()

            val repo = ActivityRepository(openCurrent())
            val exercise = repo.ensureExercise(
                "Hangs 20 mm", xyz.oleolegka.gachimuchi.domain.ExerciseForm.HOLD,
                workSec = 7.0, restSec = 3.0,
            )
            val id = repo.record(
                xyz.oleolegka.gachimuchi.domain.holdSetOf(
                    exercise = repo.exercise(exercise)!!.toRef(),
                    opDate = "2026-08-07", reps = 6, holdSec = 12.0,
                ),
                attachToWorkout = false,
            )

            // a length the user actually typed in is never overwritten by the protocol's own
            assertEquals(12.0, holdPayload(repo.allEvents(), id).holdSec!!, 1e-9)
        }

    // --- version 17 -> 18: the edge leaves the identity, folded into the name first ---------

    /** What a version 17 phone was left holding, so the assertions can name the rows. */
    private data class PhoneV17(
        val hangsId: Long,
        val setOnHangsId1: Long,
        val setOnHangsId2: Long,
        val benchId: Long,
        val plainId: Long,
        val edgedId: Long,
    )

    /**
     * A version 17 catalog with: a hangboard exercise with an edge and real history against it
     * (standing in for the owner's own fingerboard rows this migration must not corrupt), a
     * plain strength exercise with no edge to leave untouched, and a pair that collides only
     * AFTER the fold — one row already named the way the other's edge is about to make it look.
     */
    private suspend fun writeVersion17(): PhoneV17 {
        val v17 = Room.databaseBuilder(context, SchemaV17Database::class.java, dbName).build()
        opened = v17

        val hangsId = v17.catalog().insertExercise(
            ExerciseEntityV17(
                name = "Hangs", form = ExerciseForm.HOLD.code, createdAt = "2026-08-06T10:00:00",
                edgeMm = 20.0, protocolWorkSec = 7.0, protocolRestSec = 3.0,
            )
        )
        val hang1 = """{"activity":"hangs","added_kg":10.0,"own_weight":true,"work_sec":7.0,""" +
            """"rest_sec":3.0,"edge_mm":20.0,"exercise_id":$hangsId,"op_date":"2026-08-06",""" +
            """"activity_key":"hangs"}"""
        val setOnHangsId1 = v17.catalog().insertEvent(
            EventEntityV16(
                ts = "2026-08-06T10:00:00", type = TYPE_HOLD_SET, payload = hang1,
            )
        )
        val hang2 = """{"activity":"hangs","added_kg":12.5,"own_weight":true,"work_sec":7.0,""" +
            """"rest_sec":3.0,"edge_mm":20.0,"exercise_id":$hangsId,"op_date":"2026-08-08",""" +
            """"activity_key":"hangs"}"""
        val setOnHangsId2 = v17.catalog().insertEvent(
            EventEntityV16(
                ts = "2026-08-08T10:00:00", type = TYPE_HOLD_SET, payload = hang2,
            )
        )

        // an ordinary strength exercise: no edge, nothing about it should move
        val benchId = v17.catalog().insertExercise(
            ExerciseEntityV17(
                name = "Bench press", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-08-01T10:00:00",
            )
        )

        // the collision the fold creates: a row already called "Front lever 25mm" with no edge
        // of its own, and a row called "Front lever" whose edge folds to the same words
        val plainId = v17.catalog().insertExercise(
            ExerciseEntityV17(
                name = "Front lever 25mm", form = ExerciseForm.HOLD.code,
                createdAt = "2026-08-01T09:00:00", protocolWorkSec = 5.0, protocolRestSec = 5.0,
            )
        )
        val edgedId = v17.catalog().insertExercise(
            ExerciseEntityV17(
                name = "Front lever", form = ExerciseForm.HOLD.code,
                createdAt = "2026-08-01T09:05:00", edgeMm = 25.0,
                protocolWorkSec = 5.0, protocolRestSec = 5.0,
            )
        )

        v17.close()
        opened = null
        return PhoneV17(hangsId, setOnHangsId1, setOnHangsId2, benchId, plainId, edgedId)
    }

    /**
     * The scenario the migration exists for: the owner's own fingerboard rows, an edge on file
     * and real sets logged against the row before the upgrade. The edge must not simply vanish
     * — it is folded into the name — and every set must still be this row's after the column
     * that used to carry it is gone.
     */
    @Test
    fun `a hangboard exercise with an edge and real history survives the upgrade`() = runTest {
        val phone = writeVersion17()

        val db = openCurrent()
        val repo = ActivityRepository(db)
        val hangs = db.exercises().byId(phone.hangsId)!!

        assertEquals("the edge is folded into the name, not dropped", "Hangs 20mm", hangs.name)
        val hangsProgramUid = db.programs().programById(hangs.protocolProgramId!!)!!.uid
        assertEquals(
            exerciseIdentityKey("Hangs 20mm", ExerciseForm.HOLD.code, hangsProgramUid),
            hangs.identityKey,
        )

        val sets = holdSetsOfExercise(repo.allEvents(), repo.toRef(hangs).link)
        assertEquals("both sets logged before the upgrade are still this row's", 2, sets.size)
        assertEquals(
            setOf(10.0, 12.5),
            sets.map { it.addedKg }.toSet(),
        )
    }

    /** An exercise with no edge at all is untouched in name, and simply gets the new key. */
    @Test
    fun `an exercise with no edge is untouched in name and gets the new identity key`() = runTest {
        val phone = writeVersion17()

        val bench = openCurrent().exercises().byId(phone.benchId)!!

        assertEquals("Bench press", bench.name)
        assertEquals(
            exerciseIdentityKey("Bench press", ExerciseForm.STRENGTH.code),
            bench.identityKey,
        )
    }

    /**
     * The collision the fold itself can create: constructed deliberately, per the rule that
     * every row with an edge is folded unconditionally, not only the ones already about to
     * clash. Both rows survive, the later one is renamed by [freeExerciseName] exactly as
     * [MIGRATION_14_15]'s own duplicates are, and each keeps its own history.
     */
    @Test
    fun `a collision created by the fold is resolved the same way MIGRATION_14_15's duplicates are`() =
        runTest {
            val phone = writeVersion17()

            val db = openCurrent()
            val plain = db.exercises().byId(phone.plainId)!!
            val edged = db.exercises().byId(phone.edgedId)!!

            assertEquals("the row that was there first keeps its name", "Front lever 25mm", plain.name)
            assertEquals(
                "the later row is folded and then marked, the same as an ordinary duplicate",
                "Front lever 25mm (2)",
                edged.name,
            )
            assertNotEquals(plain.identityKey, edged.identityKey)
            // both rows share ONE protocol program (same work/rest pair, see MIGRATION_18_19's
            // own KDoc on grouping), so both identity keys resolve against the same program uid
            val sharedProgramUid = db.programs().programById(plain.protocolProgramId!!)!!.uid
            assertEquals(plain.protocolProgramId, edged.protocolProgramId)
            assertEquals(
                exerciseIdentityKey("Front lever 25mm", ExerciseForm.HOLD.code, sharedProgramUid),
                plain.identityKey,
            )
            assertEquals(
                exerciseIdentityKey("Front lever 25mm (2)", ExerciseForm.HOLD.code, sharedProgramUid),
                edged.identityKey,
            )
        }

    @Test
    fun `the identity index is still unique after the edge leaves it`() = runTest {
        writeVersion17()

        val db = openCurrent()
        val failure = runCatching {
            db.exercises().insert(
                xyz.oleolegka.gachimuchi.data.db.ExerciseEntity(
                    name = "bench press", form = ExerciseForm.STRENGTH.code,
                    createdAt = "2026-08-09T10:00:00",
                )
            )
        }.exceptionOrNull()

        assertTrue(
            "expected the upgraded database to refuse a duplicate, got $failure",
            failure is android.database.sqlite.SQLiteConstraintException,
        )
    }

    @Test
    fun `the catalog without edge_mm passes Room's schema check on the next open`() = runTest {
        writeVersion17()

        openCurrent().close()
        opened = null

        val again = openCurrent()
        assertEquals(4, again.exercises().all().size)
    }

    // --- MIGRATION_18_19: the protocol becomes a program reference ------------------------

    private data class PhoneV18(
        val hangsId: Long,
        val setOnHangsId1: Long,
        val setOnHangsId2: Long,
        val repeatersAId: Long,
        val repeatersBId: Long,
        val benchId: Long,
        val zeroNullId: Long,
        val zeroWorkId: Long,
    )

    /**
     * A version 18 catalog with: a hangboard exercise carrying a real protocol and real
     * hold-set history (the scenario this migration exists for), a pair of exercises that share
     * one protocol VALUE PAIR (the duplicate-program case), a plain strength exercise with no
     * protocol at all, and a pair that collides only AFTER the reshaping.
     *
     * The last pair is the interesting one, so it is spelled out here rather than only in the
     * test that uses it. Under schema 18, `num(null)` is `""` and `num(0.0)` is `"0.0"`, so the
     * OLD key strings differ ("Zero|1||" against "Zero|1|0.0|5.0") and both rows are legal
     * under the OLD unique index. [MIGRATION_18_19] requires `work > 0` for a row to count as
     * having a protocol at all (see its own KDoc) — a stored ZERO does not qualify, same as a
     * stored NULL — so NEITHER of these two rows gets a program, both resolve to
     * `programUid = null`, and their NEW keys collide on name and form alone.
     */
    private suspend fun writeVersion18(): PhoneV18 {
        val v18 = Room.databaseBuilder(context, SchemaV18Database::class.java, dbName).build()
        opened = v18

        val hangsId = v18.catalog().insertExercise(
            ExerciseEntityV18(
                name = "Hangs", form = ExerciseForm.HOLD.code, createdAt = "2026-08-06T10:00:00",
                protocolWorkSec = 7.0, protocolRestSec = 3.0,
            )
        )
        val hang1 = """{"activity":"hangs","added_kg":10.0,"own_weight":true,"work_sec":7.0,""" +
            """"rest_sec":3.0,"hold_sec":7.0,"reps":6,"exercise_id":$hangsId,"op_date":"2026-08-06",""" +
            """"activity_key":"hangs"}"""
        val setOnHangsId1 = v18.catalog().insertEvent(
            EventEntityV16(
                ts = "2026-08-06T10:00:00", type = TYPE_HOLD_SET, payload = hang1,
            )
        )
        val hang2 = """{"activity":"hangs","added_kg":12.5,"own_weight":true,"work_sec":7.0,""" +
            """"rest_sec":3.0,"hold_sec":8.0,"reps":5,"exercise_id":$hangsId,"op_date":"2026-08-08",""" +
            """"activity_key":"hangs"}"""
        val setOnHangsId2 = v18.catalog().insertEvent(
            EventEntityV16(
                ts = "2026-08-08T10:00:00", type = TYPE_HOLD_SET, payload = hang2,
            )
        )

        // two rows that end up sharing ONE protocol program: the identical (work, rest) pair
        val repeatersAId = v18.catalog().insertExercise(
            ExerciseEntityV18(
                name = "Repeaters A", form = ExerciseForm.HOLD.code, createdAt = "2026-08-01T09:00:00",
                protocolWorkSec = 10.0, protocolRestSec = 5.0,
            )
        )
        val repeatersBId = v18.catalog().insertExercise(
            ExerciseEntityV18(
                name = "Repeaters B", form = ExerciseForm.HOLD.code, createdAt = "2026-08-01T09:05:00",
                protocolWorkSec = 10.0, protocolRestSec = 5.0,
            )
        )

        // an ordinary strength exercise: no protocol, nothing about it should move
        val benchId = v18.catalog().insertExercise(
            ExerciseEntityV18(
                name = "Bench press", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-08-01T10:00:00",
            )
        )

        val zeroNullId = v18.catalog().insertExercise(
            ExerciseEntityV18(
                name = "Zero", form = ExerciseForm.STRENGTH.code, createdAt = "2026-08-01T09:00:00",
            )
        )
        val zeroWorkId = v18.catalog().insertExercise(
            ExerciseEntityV18(
                name = "Zero", form = ExerciseForm.STRENGTH.code, createdAt = "2026-08-01T09:05:00",
                protocolWorkSec = 0.0, protocolRestSec = 5.0,
            )
        )

        v18.close()
        opened = null
        return PhoneV18(
            hangsId, setOnHangsId1, setOnHangsId2, repeatersAId, repeatersBId, benchId,
            zeroNullId, zeroWorkId,
        )
    }

    /** The raw `payload` column of the named events, read without going through any reducer. */
    private fun rawPayloads(db: RoomDatabase, ids: List<Long>): Map<Long, String> {
        val out = HashMap<Long, String>()
        db.openHelper.readableDatabase
            .query("SELECT `id`, `payload` FROM `events` WHERE `id` IN (${ids.joinToString(",")})")
            .use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getString(1) }
        return out
    }

    /**
     * The primary scenario, verbatim from the task this migration was written for: a protocol
     * exercise with real hold-set history survives the upgrade with its event payloads and its
     * TUT (time-under-tension) computation IDENTICAL to before.
     *
     * Proved two ways, deliberately not one: the stored payload STRINGS are byte-identical
     * (nothing rewrites an event — only `exercises` and the new `programs` rows change), and
     * [holdSecondsUnderTension] — the function that actually reads `hold_sec`/`reps` off those
     * payloads for the TUT chart — produces the same numbers reading the upgraded database that
     * it would reading the one before the upgrade.
     */
    @Test
    fun `a protocol exercise with real hold-set history keeps its payloads and TUT unchanged`() = runTest {
        val phone = writeVersion18()
        val ids = listOf(phone.setOnHangsId1, phone.setOnHangsId2)

        val before = Room.databaseBuilder(context, SchemaV18Database::class.java, dbName).build()
        val payloadsBefore = rawPayloads(before, ids)
        val setsBefore = ids.map { id -> payloadJson.decodeFromString<HoldSet>(payloadsBefore.getValue(id)) }
        val tutBefore = setsBefore.map { holdSecondsUnderTension(it) }
        before.close()

        val db = openCurrent()
        val payloadsAfter = rawPayloads(db, ids)
        assertEquals("no event payload is rewritten by this migration", payloadsBefore, payloadsAfter)

        val repo = ActivityRepository(db)
        val hangsLink = repo.toRef(db.exercises().byId(phone.hangsId)!!).link
        val setsAfter = holdSetsOfExercise(repo.allEvents(), hangsLink)
            .sortedBy { it.opDate }
        val tutAfter = setsAfter.map { holdSecondsUnderTension(it) }

        assertEquals("time-under-tension must read the same before and after", tutBefore, tutAfter)
        assertEquals(listOf(42.0, 40.0), tutAfter)
    }

    /** The protocol is folded into a new library program, and the exercise points at it. */
    @Test
    fun `a protocol is folded into a new program, and the exercise's link points at it`() = runTest {
        val phone = writeVersion18()

        val db = openCurrent()
        val hangs = db.exercises().byId(phone.hangsId)!!
        assertNotNull("a protocol exercise must gain a protocol_program_id", hangs.protocolProgramId)

        val program = ProgramRepository(db).programById(hangs.protocolProgramId!!)!!
        assertEquals("Hangs protocol", program.name)
        assertEquals("Protocols", program.category)
        assertEquals(1, program.groups.size)
        assertEquals(1, program.groups.single().blocks.size)
        assertEquals(1, program.groups.single().repeats)
        val block = program.groups.single().blocks.single()
        assertEquals(1, block.repeats)
        assertEquals(7, block.workSec)
        assertEquals(3, block.restSec)

        assertEquals(
            exerciseIdentityKey("Hangs", ExerciseForm.HOLD.code, program.uid),
            hangs.identityKey,
        )
    }

    /** Two exercises sharing one (work, rest) pair end up pointing at the SAME program. */
    @Test
    fun `two exercises with the identical protocol pair share one program, not two`() = runTest {
        val phone = writeVersion18()

        val db = openCurrent()
        val a = db.exercises().byId(phone.repeatersAId)!!
        val b = db.exercises().byId(phone.repeatersBId)!!

        assertNotNull(a.protocolProgramId)
        assertEquals(
            "the second exercise with the identical pair must point at the SAME program",
            a.protocolProgramId,
            b.protocolProgramId,
        )

        val matching = ProgramRepository(db).allPrograms().filter { program ->
            val block = program.groups.singleOrNull()?.blocks?.singleOrNull()
            block?.workSec == 10 && block?.restSec == 5
        }
        assertEquals("exactly one program must exist for this pair, not one per exercise", 1, matching.size)
        assertNotEquals("the two exercises must not collide with each other", a.identityKey, b.identityKey)
    }

    /** An exercise with no protocol at all is untouched: no program, no link, identity null. */
    @Test
    fun `an exercise with no protocol at all gets no program and its identity says so`() = runTest {
        val phone = writeVersion18()

        val db = openCurrent()
        val bench = db.exercises().byId(phone.benchId)!!

        assertNull(bench.protocolProgramId)
        assertEquals(0, ProgramRepository(db).allPrograms().count { it.exerciseId == phone.benchId })
        assertEquals(
            exerciseIdentityKey("Bench press", ExerciseForm.STRENGTH.code, null),
            bench.identityKey,
        )
    }

    /**
     * The collision the reshaping itself can create — see [writeVersion18]'s own KDoc for why a
     * stored zero and a stored null were distinct identities under schema 18 and are not under
     * schema 19. Both rows survive, the later one renamed by [freeExerciseName] exactly as
     * [MIGRATION_14_15]'s own duplicates are, and neither is silently merged into the other.
     */
    @Test
    fun `a collision created by the identity reshaping is resolved via freeExerciseName`() = runTest {
        val phone = writeVersion18()

        val db = openCurrent()
        val first = db.exercises().byId(phone.zeroNullId)!!
        val second = db.exercises().byId(phone.zeroWorkId)!!

        assertNull(first.protocolProgramId)
        assertNull(second.protocolProgramId)
        assertEquals("the row that was there first keeps its name", "Zero", first.name)
        assertEquals(
            "the later row is marked, the same as an ordinary duplicate",
            "Zero (2)",
            second.name,
        )
        assertNotEquals(first.identityKey, second.identityKey)
        assertEquals(
            exerciseIdentityKey("Zero", ExerciseForm.STRENGTH.code, null),
            first.identityKey,
        )
        assertEquals(
            exerciseIdentityKey("Zero (2)", ExerciseForm.STRENGTH.code, null),
            second.identityKey,
        )
    }

    @Test
    fun `the identity index is still unique after the protocol becomes a program reference`() = runTest {
        writeVersion18()

        val db = openCurrent()
        val failure = runCatching {
            db.exercises().insert(
                xyz.oleolegka.gachimuchi.data.db.ExerciseEntity(
                    name = "bench press", form = ExerciseForm.STRENGTH.code,
                    createdAt = "2026-08-09T10:00:00",
                )
            )
        }.exceptionOrNull()

        assertTrue(
            "expected the upgraded database to refuse a duplicate, got $failure",
            failure is android.database.sqlite.SQLiteConstraintException,
        )
    }

    @Test
    fun `the catalog with protocol_program_id instead of the two columns passes Room's schema check`() =
        runTest {
            writeVersion18()

            openCurrent().close()
            opened = null

            val again = openCurrent()
            assertEquals(6, again.exercises().all().size)
        }

    // --- version 20 -> 21: a correction becomes a whole new row instead of a patch -----------
    //
    // The exhaustive scenarios (two amendments, amend-then-delete, delete-then-undo, the actual
    // rest) are in domain/AmendmentMigrationTest.kt, against the pure function directly - fast,
    // and where the real acceptance criterion (fold before == fold after) is checked. What is
    // worth proving here, through Room, is narrower and different: that the raw SQL wiring in
    // MIGRATION_20_21 actually reads the table it is given and writes rows the app can open.

    /**
     * A version 20 phone with one strength set, corrected once, exactly the shape
     * [SchemaV20Database]'s doc explains reusing the current entity for is safe to build by
     * hand.
     */
    private suspend fun writeVersion20WithLegacyAmendment(): String {
        val v20 = Room.databaseBuilder(context, SchemaV20Database::class.java, dbName).build()
        opened = v20

        val set = EventEntityV16(
            ts = "2026-08-06T10:00:00", type = TYPE_STRENGTH_SET,
            payload = """{"exercise":"Bench press","reps":5,"weight_kg":60.0,"exercise_id":1,""" +
                """"op_date":"2026-08-06","exercise_key":"bench press"}""",
        )
        v20.events().insert(set)
        v20.events().insert(
            EventEntityV16(
                ts = "2026-08-06T11:00:00", type = "entry_amended",
                payload = """{"target_uid":"${set.uid}","fields":{"weight_kg":65.0}}""",
            )
        )
        v20.close()
        opened = null
        return set.uid
    }

    @Test
    fun `a legacy amendment reads as a corrected set after the upgrade, through the real repository`() = runTest {
        val originalUid = writeVersion20WithLegacyAmendment()

        val db = openCurrent()
        val repo = ActivityRepository(db)
        val events = repo.allEvents()

        val set = readActivities(events).single().form as StrengthSet
        assertEquals(65.0, set.weightKg!!, 1e-9)
        assertEquals(5, set.reps)
        // the original row is untouched forever - still in the table, superseded rather than gone
        assertEquals(2, readActivities(events, includeDeleted = true).size)
        assertTrue(
            "the original's own uid must not be the live one any more",
            readActivities(events).none { it.uid == originalUid },
        )
    }

    @Test
    fun `an entry nobody ever corrected costs the upgrade nothing`() = runTest {
        val v20 = Room.databaseBuilder(context, SchemaV20Database::class.java, dbName).build()
        opened = v20
        v20.events().insert(
            EventEntityV16(
                ts = "2026-08-06T10:00:00", type = TYPE_STRENGTH_SET,
                payload = """{"exercise":"Bench press","reps":5,"weight_kg":60.0,"exercise_id":1,""" +
                    """"op_date":"2026-08-06","exercise_key":"bench press"}""",
            )
        )
        v20.close()
        opened = null

        val db = openCurrent()
        assertEquals("nothing appended for a row nobody amended", 1, db.events().count())
    }
}
