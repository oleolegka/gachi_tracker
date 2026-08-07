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
import xyz.oleolegka.gachimuchi.domain.formsOfExercise
import xyz.oleolegka.gachimuchi.domain.formFromEventOrNull
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.TYPE_STRENGTH_SET
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
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
        assertEquals("Hangs 20 mm", exercises.single().name)
        assertEquals(20.0, exercises.single().edgeMm!!, 1e-9)

        assertEquals(1, v2.slots().all().size)
    }

    @Test
    fun `the program tables exist after the upgrade and accept a program`() = runTest {
        writeVersion1()

        val v2 = openCurrent()
        val repo = ProgramRepository(v2)

        assertEquals(0, repo.count())
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
        assertEquals(0, again.programs().countPrograms())
    }

    @Test
    fun `a fresh install creates the current version directly, without any migration`() = runTest {
        val fresh = openCurrent()
        assertEquals(0, fresh.events().count())
        assertEquals(0, fresh.programs().countPrograms())
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
        val again = openCurrent()
        assertEquals(1, again.programs().countPrograms())
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
        assertEquals("Hangs 20 mm", exercise.name)
        assertEquals(20.0, exercise.edgeMm!!, 1e-9)
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
        assertEquals("Hangs 20 mm", seeded.name)
        assertEquals(20.0, seeded.edgeMm!!, 1e-9)

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
        assertEquals(20.0, exercise.edgeMm!!, 1e-9)
        assertEquals(1, current.slots().all().size)
        assertTrue(current.slots().allExercises().isEmpty())
        assertEquals(0, current.programs().countPrograms())
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
}
