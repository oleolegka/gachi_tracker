package xyz.oleolegka.gachimuchi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AliasDao
import xyz.oleolegka.gachimuchi.data.db.AliasEntity
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.EventDao
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseDao
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.SlotDao
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.toPayload

/**
 * Version 1 of the schema, exactly as it shipped: the four tables that existed before the
 * timer, and nothing else.
 *
 * Declared as a real Room database so that ROOM generates the old DDL. Writing those
 * CREATE TABLE statements by hand would mean the migration is tested against a schema
 * invented here, which is precisely the schema that cannot be wrong.
 */
@Database(
    entities = [EventEntity::class, ExerciseEntity::class, AliasEntity::class, SlotEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SchemaV1Database : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun exercises(): ExerciseDao
    abstract fun aliases(): AliasDao
    abstract fun slots(): SlotDao
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

        val exerciseId = v1.exercises().insert(
            ExerciseEntity(
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
            EventEntity(ts = "2026-08-01T10:00:00", type = set.type, payload = set.toPayload())
        )
        v1.aliases().upsert(AliasEntity(key = "bench", value = exerciseId))
        v1.slots().insert(
            SlotEntity(
                name = "Gym", atTime = "19:00", repeatRule = "weekly",
                anchorDate = "2026-08-01", createdAt = "2026-08-01T09:00:00",
            )
        )
        v1.close()
        opened = null
    }

    private fun openVersion2(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .also { opened = it }

    @Test
    fun `the journal, the catalog, the aliases and the slots all survive the upgrade`() = runTest {
        writeVersion1()

        val v2 = openVersion2()

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

        val v2 = openVersion2()
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
        openVersion2().also { it.events().count() }.close()
        opened = null

        // the second open takes the "already at the right version" path, where Room
        // compares the database against the entity definitions and refuses a mismatch
        val again = openVersion2()
        assertEquals(1, again.events().count())
        assertEquals(0, again.programs().countPrograms())
    }

    @Test
    fun `a fresh install creates version 2 directly, without any migration`() = runTest {
        val fresh = openVersion2()
        assertEquals(0, fresh.events().count())
        assertEquals(0, fresh.programs().countPrograms())
    }
}
