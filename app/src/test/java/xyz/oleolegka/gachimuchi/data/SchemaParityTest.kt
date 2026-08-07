package xyz.oleolegka.gachimuchi.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase

/**
 * THE SCHEMA A PHONE ENDS UP WITH MUST BE THE SCHEMA A FRESH INSTALL CREATES.
 *
 * Room does not check this, and that is the gap. On an upgrade Room verifies the database
 * against an IDENTITY HASH derived from the entity definitions, and that hash is computed
 * from the columns, their affinities, their nullability and the indices — it does NOT include
 * a column's DEFAULT clause. So a migration that writes `ADD COLUMN x TEXT NOT NULL DEFAULT
 * ''` (which SQLite forces on any non-null column added to a populated table) leaves an
 * upgraded phone with a default that a fresh install has never had, and every check in the
 * app stays green while two users run two different databases.
 *
 * A default is not cosmetic here. It decides what an INSERT that omits the column writes,
 * which is exactly the kind of difference that shows up years later as "it works on my phone".
 *
 * ── What this test does ─────────────────────────────────────────────────────────
 * Builds a database at version 1, walks it up through every migration, and compares it column
 * by column and index by index against a database Room created from today's entities. The
 * comparison is `PRAGMA table_info` (name, affinity, NOT NULL, default, primary key) and the
 * `sqlite_master` entry of every index, so a drift in any of those fails here.
 *
 * It is deliberately NOT written as a list of expected columns. A hand-written expectation is
 * a third description of the schema that has to be maintained alongside the entities and the
 * migrations, and it would go stale exactly as quietly as the thing it is meant to catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemaParityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "schema-parity-test.db"
    private var opened: RoomDatabase? = null

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(dbName)
    }

    /** One column of a table, as SQLite itself describes it. */
    private data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKey: Int,
    )

    /** One index, by name and by the statement that created it (null for an implicit one). */
    private data class IndexDef(val name: String, val sql: String?)

    /** The whole shape of a database: its tables, their columns, and their indices. */
    private data class Shape(
        val tables: List<String>,
        val columns: Map<String, List<Column>>,
        val indices: Map<String, List<IndexDef>>,
    )

    /*
     * Tables nobody declares and nobody migrates. `sqlite_sequence` is created by SQLite
     * itself the first time an AUTOINCREMENT row is written, so whether it exists depends on
     * whether the database has ever held data rather than on its schema; `android_metadata` is
     * the platform's locale marker. Comparing either would compare history, not shape.
     */
    private val notPartOfTheSchema = setOf("sqlite_sequence", "android_metadata")

    private fun shapeOf(db: SupportSQLiteDatabase): Shape {
        val tables = ArrayList<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (name !in notPartOfTheSchema && !name.startsWith("sqlite_")) tables += name
            }
        }

        val columns = HashMap<String, List<Column>>()
        val indices = HashMap<String, List<IndexDef>>()
        for (table in tables) {
            val ofTable = ArrayList<Column>()
            db.query("PRAGMA table_info(`$table`)").use { c ->
                while (c.moveToNext()) {
                    ofTable += Column(
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        type = c.getString(c.getColumnIndexOrThrow("type")),
                        notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) != 0,
                        defaultValue = c.getString(c.getColumnIndexOrThrow("dflt_value")),
                        primaryKey = c.getInt(c.getColumnIndexOrThrow("pk")),
                    )
                }
            }
            columns[table] = ofTable

            val ofIndices = ArrayList<IndexDef>()
            db.query(
                "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? " +
                    "ORDER BY name",
                arrayOf<Any>(table),
            ).use { c ->
                while (c.moveToNext()) ofIndices += IndexDef(c.getString(0), c.getString(1))
            }
            indices[table] = ofIndices
        }
        return Shape(tables, columns, indices)
    }

    /** Opens at the current version, running whatever migrations the file needs, and reads it. */
    private fun readCurrent(): Shape {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .also { opened = it }
        val shape = shapeOf(db.openHelper.writableDatabase)
        db.close()
        opened = null
        return shape
    }

    /**
     * The shape of a database that has walked the whole chain, and the shape of one Room has
     * just created. Same file name, one after the other, so the two go through the same
     * builder and the same platform.
     */
    private suspend fun migratedAndFresh(writeOldVersion: suspend () -> Unit): Pair<Shape, Shape> {
        context.deleteDatabase(dbName)
        writeOldVersion()
        val migrated = readCurrent()

        context.deleteDatabase(dbName)
        val fresh = readCurrent()
        return migrated to fresh
    }

    private fun assertSameShape(migrated: Shape, fresh: Shape) {
        assertEquals("the set of tables differs", fresh.tables, migrated.tables)
        for (table in fresh.tables) {
            assertEquals(
                "columns of `$table` differ between a migrated database and a fresh one",
                fresh.columns[table],
                migrated.columns[table],
            )
            assertEquals(
                "indices of `$table` differ between a migrated database and a fresh one",
                fresh.indices[table],
                migrated.indices[table],
            )
        }
    }

    /** A version 1 database with nothing in it — the empty walk through every migration. */
    private fun writeEmptyVersion1() {
        val v1 = Room.databaseBuilder(context, SchemaV1Database::class.java, dbName).build()
        opened = v1
        // Room creates the file lazily; asking for the connection is what materialises it
        v1.openHelper.writableDatabase
        v1.close()
        opened = null
    }

    @Test
    fun `a database walked up from version 1 has the same shape as a fresh install`() = runTest {
        val (migrated, fresh) = migratedAndFresh { writeEmptyVersion1() }
        assertSameShape(migrated, fresh)
    }

    /**
     * The same walk on a database with ROWS in it.
     *
     * Worth its own test because the two are not the same code path in SQLite: `ALTER TABLE
     * ADD COLUMN` against a populated table refuses a NOT NULL column without a default, so a
     * migration that has to add one is forced into a default that a fresh install will not
     * have. An empty database would let a `CREATE TABLE`-shaped shortcut past.
     */
    @Test
    fun `a database walked up with data in it has the same shape as a fresh install`() = runTest {
        val (migrated, fresh) = migratedAndFresh { writeVersion1WithData() }
        assertSameShape(migrated, fresh)
    }

    private suspend fun writeVersion1WithData() {
        val v1 = Room.databaseBuilder(context, SchemaV1Database::class.java, dbName).build()
        opened = v1
        val exerciseId = v1.catalog().insertExercise(
            ExerciseEntityV3(
                name = "Bench press",
                form = xyz.oleolegka.gachimuchi.domain.ExerciseForm.STRENGTH.code,
                createdAt = "2026-05-01T10:00:00",
            )
        )
        v1.events().insert(
            EventEntityV4(
                ts = "2026-05-01T10:00:00",
                type = xyz.oleolegka.gachimuchi.domain.TYPE_STRENGTH_SET,
                payload = """{"exercise":"Bench press","reps":5,"weight_kg":80.0,""" +
                    """"op_date":"2026-05-01","exercise_key":"bench press"}""",
            )
        )
        v1.catalog().insertAlias(AliasEntityV3(key = "bench", value = exerciseId))
        v1.catalog().insertSlot(
            SlotEntityV3(
                name = "Gym", atTime = "18:00", repeatRule = "weekly",
                anchorDate = "2026-05-04", createdAt = "2026-05-01T09:00:00",
            )
        )
        v1.close()
        opened = null
    }

    /**
     * The guard on the guard: the comparison has to be able to SEE a difference.
     *
     * A parity test that compares two things it reads the same way can pass because both
     * readings are empty. This one manufactures a divergence of exactly the kind the file is
     * about — a default clause on a column, which Room's identity hash ignores — and insists
     * the comparison rejects it.
     */
    @Test
    fun `the comparison notices a default clause that only one of the two has`() = runTest {
        context.deleteDatabase(dbName)
        writeEmptyVersion1()
        val migrated = readCurrent()

        context.deleteDatabase(dbName)
        val fresh = readCurrent()

        val tampered = fresh.copy(
            columns = fresh.columns.mapValues { (table, cols) ->
                if (table == "slots") {
                    cols.map { if (it.name == "name") it.copy(defaultValue = "''") else it }
                } else {
                    cols
                }
            }
        )

        val failure = runCatching { assertSameShape(migrated, tampered) }.exceptionOrNull()
        assertTrue(
            "a default clause present on one side and absent on the other went unnoticed",
            failure is AssertionError,
        )
    }
}
