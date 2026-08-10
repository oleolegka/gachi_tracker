package xyz.oleolegka.gachimuchi.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm

/**
 * `MIGRATION_22_23` (schema version 23, `exercises.picture_id`) — the ALTER TABLE itself is
 * already exercised generically by [SchemaParityTest], which walks every migration and compares
 * the result column by column against a fresh install. What that test cannot show is that an
 * exercise a real phone already has comes through with a picture-less answer, and that the new
 * column actually holds what is written to it — both checked here, reusing
 * [SchemaV1Database]'s fixtures the way `MigrationTest`'s own tests do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExercisePictureMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "exercise-picture-migration-test.db"
    private var opened: RoomDatabase? = null

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(dbName)
    }

    private suspend fun writeVersion1(): Long {
        val v1 = Room.databaseBuilder(context, SchemaV1Database::class.java, dbName).build()
        opened = v1
        val id = v1.catalog().insertExercise(
            ExerciseEntityV3(
                name = "Lat pulldown", form = ExerciseForm.STRENGTH.code,
                createdAt = "2026-08-01T10:00:00",
            )
        )
        v1.close()
        opened = null
        return id
    }

    private fun openCurrent(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .also { opened = it }

    @Test
    fun `an exercise from before the column existed comes through with no picture`() = runTest {
        val id = writeVersion1()

        val exercise = openCurrent().exercises().byId(id)

        assertNull("nothing had a picture before there was a column for one", exercise!!.pictureId)
    }

    @Test
    fun `the picture id can be written after the upgrade and reads back`() = runTest {
        val id = writeVersion1()
        val db = openCurrent()

        db.exercises().setPictureId(id, "a-picture-file-name")

        assertEquals("a-picture-file-name", db.exercises().byId(id)!!.pictureId)
    }

    @Test
    fun `the picture column passes Room's schema check on the next open`() = runTest {
        writeVersion1()

        openCurrent().also { it.exercises().all() }.close()
        opened = null

        val again = openCurrent()
        assertEquals(1, again.exercises().all().size)
    }
}
