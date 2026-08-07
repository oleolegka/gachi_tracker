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
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AliasDao
import xyz.oleolegka.gachimuchi.data.db.AliasEntity
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.COLUMN_SEEDED
import xyz.oleolegka.gachimuchi.data.db.EventDao
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseDao
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.LOCAL_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.db.LOCAL_SPACE_ID
import xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_EXERCISE_ADDED
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
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
 * preferences of version 5. The aliases and the slots did not change between 4 and 5, so
 * their CURRENT entities are the version 4 snapshot and no class is declared for them.
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
    @ColumnInfo(name = COLUMN_SEEDED) val seeded: Boolean = false,
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
}

/**
 * Putting a slot into a database that has no `slot_exercises` table.
 *
 * A SNAPSHOT OF THE DAO, not of the entity, and the split is the point. The `slots` table
 * itself did not change in version 6 — only a child table was added — so [SlotEntity] is
 * still the right shape for an old database and the rule above allows reusing it. The
 * current [SlotDao] is not: its composition queries name `slot_exercises`, and Room verifies
 * every @Query against the entities of the database it is declared on AT COMPILE TIME, so
 * hanging it off a schema without that table does not compile.
 */
@Dao
interface LegacySlotDao {
    @Insert
    suspend fun insert(slot: SlotEntity): Long
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
        ProgramEntity::class,
        ProgramGroupEntity::class,
        ProgramBlockEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class SchemaV3Database : RoomDatabase() {
    abstract fun events(): LegacyEventDao
    abstract fun catalog(): LegacyCatalogDao
}

/**
 * Version 4: the demo-seed mark is on the catalog, and nothing knows about workouts yet.
 * The journal has no `workout_id` and the catalog no rest preference — this is the phone the
 * 4 -> 5 migration actually runs on, and the only one that matters in practice, since it is
 * the version currently installed.
 *
 * The aliases and the slots are the CURRENT entities: those two tables did not change in
 * version 5, so a snapshot of them would be a copy that can only rot.
 */
@Database(
    entities = [
        EventEntityV4::class,
        ExerciseEntityV4::class,
        AliasEntity::class,
        SlotEntity::class,
        ProgramEntity::class,
        ProgramGroupEntity::class,
        ProgramBlockEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class SchemaV4Database : RoomDatabase() {
    abstract fun events(): LegacyEventDao
    abstract fun catalog(): LegacyCatalogDaoV4
    abstract fun aliases(): AliasDao
    abstract fun slots(): LegacySlotDao
}

/**
 * Version 5: the workout link is on the journal and the rest preferences are on the catalog,
 * and a slot is still nothing but a name, a time and a rule — no composition. This is the
 * phone the 5 -> 6 migration actually runs on.
 *
 * EVERY ENTITY HERE IS THE CURRENT ONE, and under the rule at the top of this file that is
 * allowed only because version 6 changed no existing table: it added `slot_exercises` and
 * touched nothing else. The moment a column is added to any of these, this database has to
 * grow a snapshot class for that table or it stops testing anything. The DAO is a different
 * matter and [LegacySlotDao] says why.
 */
@Database(
    entities = [
        EventEntity::class,
        ExerciseEntity::class,
        AliasEntity::class,
        SlotEntity::class,
        ProgramEntity::class,
        ProgramGroupEntity::class,
        ProgramBlockEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class SchemaV5Database : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun exercises(): ExerciseDao
    abstract fun aliases(): AliasDao
    abstract fun slots(): LegacySlotDao
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
    fun `the journal, the catalog, the aliases and the slots all survive the upgrade`() = runTest {
        writeVersion1()

        val v2 = openCurrent()

        val events = v2.events().all()
        assertEquals(1, events.size)
        assertTrue(events.single().payload.contains("Bench press"))

        val exercises = v2.exercises().all()
        assertEquals(1, exercises.size)
        assertEquals("Hangs 20 mm", exercises.single().name)
        assertEquals(20.0, exercises.single().edgeMm!!, 1e-9)

        assertNotNull(v2.aliases().byKey("bench"))
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

    @Test
    fun `everything already on the phone comes through the mark as the user's own`() = runTest {
        writeVersion3()

        val v4 = openCurrent()

        // the rows are all still there
        assertEquals(1, v4.events().count())
        assertEquals(1, v4.slots().all().size)
        val exercise = v4.exercises().all().single()
        assertEquals("Bench press", exercise.name)

        /*
         * And every one of them reads as "not the seed's". That default is the whole safety
         * property of this migration: the mark exists only to authorise DELETION, so a row
         * whose origin is unknown has to come through as unmarked. Guessing the other way
         * would have armed the remove button against records nobody can get back.
         */
        assertFalse(exercise.seeded)
        assertFalse(v4.slots().all().single().seeded)
        assertFalse(v4.aliases().byKey("bench")!!.seeded)
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
        v4.aliases().upsert(AliasEntity(key = "bench", value = exerciseId))
        v4.slots().insert(
            SlotEntity(
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
        assertNotNull(v5.aliases().byKey("bench"))

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

        val exerciseId = v5.exercises().insert(
            ExerciseEntity(
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
        v5.events().insert(
            EventEntity(ts = "2026-07-01T10:00:00", type = set.type, payload = set.toPayload())
        )
        v5.aliases().upsert(AliasEntity(key = "bench", value = exerciseId))
        val slotId = v5.slots().insert(
            SlotEntity(
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

    @Test
    fun `a phone that skipped every release in between still arrives intact`() = runTest {
        // 1 -> 6 in one go. Nobody upgrades one version at a time, and a chain that only ever
        // gets tested link by link is a chain whose middle is untested.
        writeVersion1()

        val v6 = openCurrent()

        assertEquals(1, v6.events().count())
        assertTrue(v6.events().all().single().workoutId == null)
        val exercise = v6.exercises().all().single()
        assertFalse(exercise.seeded)
        assertNull(exercise.defaultRestSec)
        assertEquals(20.0, exercise.edgeMm!!, 1e-9)
        assertNotNull(v6.aliases().byKey("bench"))
        assertEquals(1, v6.slots().all().size)
        assertTrue(v6.slots().allExercises().isEmpty())
        assertEquals(0, v6.programs().countPrograms())
    }
}
