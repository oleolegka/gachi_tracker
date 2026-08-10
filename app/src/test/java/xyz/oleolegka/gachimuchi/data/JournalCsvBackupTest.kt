package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import java.time.LocalDate

/**
 * [JournalBackup.exportCsv] against a real database — the Room bridge that domain/JournalCsv.kt
 * itself cannot be tested through (it takes plain lists, on purpose).
 *
 * What is checked here is specifically the bridge: that the two tables it reads land in the
 * CSV at all, and that a cancelled set (a real Room round trip, not a hand-built [JournalEvent])
 * does not. The shape of the CSV itself — every column, every form, escaping — is
 * domain/JournalCsvTest.kt, which needs no database and runs far cheaper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalCsvBackupTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var backup: JournalBackup

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ActivityRepository(db)
        backup = JournalBackup(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a cancelled set does not reach the csv, a live one does, under its catalog name`() = runTest {
        val benchId = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val bench = repo.toRef(repo.exercise(benchId)!!)
        val today = LocalDate.now().toString()

        val keptId = repo.record(strengthSetOf(bench, today, reps = 5, weightKg = 60.0))
        val cancelledId = repo.record(strengthSetOf(bench, today, reps = 5, weightKg = 65.0))
        repo.cancelSet(cancelledId)
        repo.editExercise(benchId, "Bench press (barbell)")

        val csv = backup.exportCsv()
        val rows = csv.trim('﻿', '\n').split("\n").drop(1)

        assertEquals(1, rows.size)
        assertTrue(rows.single().contains("60.0"))
        assertFalse(csv.contains("65.0"))
        // the RENAMED catalog name, not "Bench press" the set was written under
        assertTrue(rows.single().startsWith("$today,,Bench press (barbell),Strength"))
        assertTrue(keptId > 0)
    }

    @Test
    fun `an empty phone still exports a valid, header-only csv`() = runTest {
        val csv = backup.exportCsv()
        val lines = csv.trim('﻿', '\n').split("\n")
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("date,workout,exercise,form"))
    }
}
