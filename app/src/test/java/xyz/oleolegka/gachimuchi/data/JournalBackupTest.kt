package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.domain.CelebrationMode
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.JournalFile
import xyz.oleolegka.gachimuchi.domain.JournalImport
import xyz.oleolegka.gachimuchi.domain.PortableSettings
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TYPE_DURATION
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.bodyweightOf
import xyz.oleolegka.gachimuchi.domain.csvRowsByColumn
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.portableSettings
import xyz.oleolegka.gachimuchi.domain.readJournalFile
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.strengthSetsOfExercise
import xyz.oleolegka.gachimuchi.domain.tickOf
import java.time.LocalDate
import java.util.TimeZone

/**
 * The backup against a real database: out of Room into a file, back into Room, and again.
 *
 * ── What is actually being defended ─────────────────────────────────────────────
 * That the file is a COPY OF THE HISTORY and not an approximation of it. The strongest
 * statement of that available without comparing the two databases row by row is the one this
 * class opens with: export, restore into an empty database, export again, and demand the two
 * files be the same text. Anything a restore invents, drops, reorders or rounds shows up as a
 * diff, including in the payloads — which is where every training fact actually lives and
 * which the format deliberately never parses.
 *
 * The second property is idempotence. A backup gets restored twice by somebody who is not sure
 * whether they already did, and the honest answer to that has to be "nothing happened", not a
 * journal with every set in it twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalBackupTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var programs: ProgramRepository
    private lateinit var backup: JournalBackup

    /** Preferences without the process-wide stores behind them; see [BackupSettings]. */
    private class FakeSettings(var value: PortableSettings) : BackupSettings {
        override fun read(): PortableSettings = value
        override fun write(settings: PortableSettings) {
            value = settings
        }
    }

    private lateinit var settings: FakeSettings

    @Before
    fun setUp() {
        db = freshDb()
        repo = ActivityRepository(db)
        programs = ProgramRepository(db)
        settings = FakeSettings(
            portableSettings(
                timer = TimerSettings(defaultRestSec = 210, speak = true),
                timerEnabled = true,
                celebration = CelebrationMode.EVERY_SET,
            )
        )
        backup = JournalBackup(db, settings)
    }

    @After
    fun tearDown() = db.close()

    private fun freshDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val days: List<String> =
        (1..4).map { LocalDate.now().minusDays(it.toLong()).toString() }.reversed()

    /**
     * A small but complete phone: several days of training in four forms, a workout with an
     * exercise added to it and a set cancelled inside it, a catalog, a plan with a composition,
     * and a program linked to an exercise.
     *
     * Every kind of cross-record link the app has is in here on purpose — the whole risk of
     * this feature is a link that survives the trip in name only.
     */
    private suspend fun writeAPhone() {
        val benchId = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH, defaultRestSec = 150)
        val hangsId = repo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        val boulderId = repo.ensureExercise("Bouldering gym", ExerciseForm.TICK)
        // a catalog row whose every optional column is set, and nothing logged against it: the
        // reference tables are carried column by column rather than opaquely, so a column added
        // to `exercises` and forgotten here would come back as its default and say nothing
        val oneArmId = repo.ensureExercise(
            "One-arm hang", ExerciseForm.HOLD,
            workSec = 10.0, restSec = 5.0, defaultRestSec = 240,
        )
        repo.setOneSided(oneArmId, true)
        repo.setBodyweightShare(oneArmId, 0.65)
        repo.setLedByProtocol(oneArmId, false)
        val bench = repo.toRef(repo.exercise(benchId)!!)
        val hangs = repo.toRef(repo.exercise(hangsId)!!)
        val boulder = repo.toRef(repo.exercise(boulderId)!!)

        for ((index, day) in days.withIndex()) {
            repeat(3) { set ->
                repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0 + index + set * 2.5))
            }
            repo.record(holdSetOf(hangs, day, addedKg = 5.0 + index * 0.5, reps = 6, holdSec = 7.0))
            repo.record(tickOf(boulder, day))
            repo.record(bodyweightOf(day, weightKg = 74.0 + index * 0.1))
        }

        // a workout, with an exercise added to it and a set logged inside it and then cancelled
        val workoutId = repo.startWorkout(name = "Evening gym")
        repo.addExerciseToWorkout(workoutId, benchId, restSec = 180)
        val setId = repo.record(strengthSetOf(bench, LocalDate.now().toString(), reps = 5, weightKg = 80.0))
        repo.cancelSet(setId)

        repo.saveSlot(
            SlotDraft(
                name = "Gym",
                timeText = "18:30",
                repeatRule = REPEAT_WEEKLY,
                anchorDate = days.first(),
                exercises = listOf(
                    PlannedExercise(exerciseId = benchId, restSec = 150),
                    PlannedExercise(exerciseId = hangsId),
                ),
            )
        )

        programs.save(
            WorkoutProgram(
                name = "Hangboard repeaters 7:3",
                prepareSec = 15,
                exerciseId = hangsId,
                category = "Hangboard",
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
    }

    private fun accepted(text: String): JournalFile {
        val parsed = readJournalFile(text)
        assertTrue("the app's own export was refused: $parsed", parsed is JournalImport.Loaded)
        return (parsed as JournalImport.Loaded).file
    }

    /**
     * The central one. Note what the comparison is: the FILE TEXT, not a summary of it — a
     * restore that lost a payload key, reordered the journal, or turned 72.5 into 72.5000001
     * cannot pass this by rewriting the same summary.
     */
    @Test
    fun `export, restore into an empty database, export again - the same file comes out`() = runTest {
        writeAPhone()
        val first = backup.export("2026-08-07", "device-1")

        val other = freshDb()
        try {
            val restored = JournalBackup(other, FakeSettings(PortableSettings()))
            val report = restored.restore(accepted(first))

            assertTrue("a full restore must write the journal", report.eventsAdded > 0)
            assertEquals(0, report.eventsAlreadyHere)
            assertEquals(4, report.exercisesAdded)
            assertEquals(1, report.slotsAdded)
            // three: the hand-written "Hangboard repeaters 7:3", plus the two minimal programs
            // ensureExercise folded "Hangs" and "One-arm hang"'s own protocols into
            assertEquals(3, report.programsAdded)
            assertEquals(0, report.plannedLinesSkipped)
            assertTrue(report.notes.isEmpty())

            assertEquals(first, restored.export("2026-08-07", "device-1"))

            // and said once in the open, because the file text agreeing is a proof that reads
            // as an accident: every optional column of a catalog row came back as it was
            val oneArm = other.exercises().all().single { it.name == "One-arm hang" }
            val oneArmRef = ActivityRepository(other).toRef(oneArm)
            assertEquals(10.0, oneArmRef.workSec!!, 1e-9)
            assertEquals(5.0, oneArmRef.restSec!!, 1e-9)
            assertEquals(240, oneArm.defaultRestSec)
            assertEquals(false, oneArm.ledByProtocol)
            assertTrue(oneArm.oneSided)
            assertEquals(0.65, oneArm.bodyweightShare!!, 1e-9)
        } finally {
            other.close()
        }
    }

    /**
     * The property somebody's history depends on the day they are not sure whether they
     * already restored. Not "roughly the same number of rows" — not one row.
     */
    @Test
    fun `restoring the same file twice adds nothing the second time`() = runTest {
        writeAPhone()
        val text = backup.export()

        val other = freshDb()
        try {
            val restored = JournalBackup(other, FakeSettings(PortableSettings()))
            val first = restored.restore(accepted(text))
            val countsAfterFirst = counts(other)

            val second = restored.restore(accepted(text))

            assertEquals(0, second.eventsAdded)
            assertEquals(0, second.exercisesAdded)
            assertEquals(0, second.slotsAdded)
            assertEquals(0, second.programsAdded)
            assertTrue("a second import must add nothing at all", !second.addedAnything)

            assertEquals(first.eventsAdded, second.eventsAlreadyHere)
            assertEquals(first.exercisesAdded, second.exercisesAlreadyHere)
            assertEquals(countsAfterFirst, counts(other))
        } finally {
            other.close()
        }
    }

    /** Row counts of every table a restore writes to. */
    private suspend fun counts(database: AppDatabase): List<Int> = listOf(
        database.events().all().size,
        database.exercises().all().size,
        database.slots().all().size,
        database.slots().allExercises().size,
        database.programs().allPrograms().size,
        database.programs().allGroups().size,
        database.programs().allBlocks().size,
    )

    /**
     * Restoring into a phone that has been used since the backup was taken. The journal is a
     * union: what was here stays, what the file adds arrives, and the two sets of events do not
     * disturb each other.
     */
    @Test
    fun `a backup merges into a database that already holds training`() = runTest {
        writeAPhone()
        val text = backup.export()
        val fileEvents = accepted(text).events.size

        val other = freshDb()
        try {
            val otherRepo = ActivityRepository(other)
            // training that only this phone has: a different exercise, on a day of its own
            val squatId = otherRepo.ensureExercise("Squat", ExerciseForm.STRENGTH)
            val squat = otherRepo.exercise(squatId)!!.toRef()
            repeat(4) { otherRepo.record(strengthSetOf(squat, days.first(), reps = 3, weightKg = 100.0)) }

            val restored = JournalBackup(other, FakeSettings(PortableSettings()))
            val report = restored.restore(accepted(text))

            assertEquals(fileEvents, report.eventsAdded)
            assertEquals(fileEvents + 4, other.events().all().size)
            // the local exercise stayed and the file's four arrived beside it
            assertEquals(4, report.exercisesAdded)
            assertEquals(5, other.exercises().all().size)

            // and both histories read back whole, each under its own exercise
            val events = otherRepo.allEvents()
            assertEquals(4, strengthSetsOfExercise(events, otherRepo.exercise(squatId)!!.toRef().link).size)
            val benchLink = other.exercises().all().single { it.name == "Bench press" }.toRef().link
            assertEquals(3 * days.size, strengthSetsOfExercise(events, benchLink).size)
        } finally {
            other.close()
        }
    }

    /**
     * The seam, pinned so that it cannot change silently: two phones that invented the same
     * exercise keep ONE catalog row, the one that was already here. The report says so, and
     * the sets that arrive naming the file's key are the cost — see [JournalBackup] and
     * `docs/journal-file-format.md`.
     */
    @Test
    fun `an exercise invented on both phones is merged into the key already here`() = runTest {
        writeAPhone()
        val text = backup.export()

        val other = freshDb()
        try {
            val otherRepo = ActivityRepository(other)
            val localBench = otherRepo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
            val localUid = otherRepo.exercise(localBench)!!.uid

            val report = JournalBackup(other, null).restore(accepted(text))

            // three of the file's four exercises are new; the bench press is not
            assertEquals(3, report.exercisesAdded)
            assertEquals(1, report.exercisesMergedByIdentity)
            assertEquals(4, other.exercises().all().size)
            assertEquals(localUid, other.exercises().all().single { it.name == "Bench press" }.uid)
            assertTrue(
                "a merged key is not something to keep quiet about",
                report.notes.any { it.contains("different key") },
            )
        } finally {
            other.close()
        }
    }

    @Test
    fun `the plan and the program come back pointing at the right exercises`() = runTest {
        writeAPhone()
        val text = backup.export()

        val other = freshDb()
        try {
            JournalBackup(other, null).restore(accepted(text))
            val otherRepo = ActivityRepository(other)

            val slot = otherRepo.allSlots().single()
            assertEquals("Gym", slot.name)
            assertEquals("18:30", slot.atTime)
            assertEquals(2, slot.exercises.size)
            val named = slot.exercises.map { line ->
                other.exercises().all().single { it.id == line.exerciseId }.name
            }
            assertEquals(listOf("Bench press", "Hangs"), named)
            assertEquals(listOf(150, null), slot.exercises.map { it.restSec })

            // not the only program any more: ensureExercise now folds each hold exercise's own
            // protocol into a library program too (see ActivityRepository's find-or-create
            // logic), so "Hangboard repeaters 7:3" is picked out by name among the three
            val program = ProgramRepository(other).allPrograms().single { it.name == "Hangboard repeaters 7:3" }
            assertEquals("Hangboard repeaters 7:3", program.name)
            assertEquals("Hangboard", program.category)
            assertEquals(15, program.prepareSec)
            assertNotNull("the program's exercise link has to survive", program.exerciseId)
            assertEquals(
                "Hangs",
                other.exercises().all().single { it.id == program.exerciseId }.name,
            )
        } finally {
            other.close()
        }
    }

    /**
     * A workout is a point in the journal that other rows name, and a cancellation is another.
     * Both are said in uids, so both have to come out of a restore still pointing at the same
     * rows — a set that came back uncancelled would be a lift the history claims was done.
     */
    @Test
    fun `workouts and cancellations survive with their links intact`() = runTest {
        writeAPhone()
        val text = backup.export()
        val before = readActivities(repo.allEvents())

        val other = freshDb()
        try {
            JournalBackup(other, null).restore(accepted(text))
            val otherRepo = ActivityRepository(other)
            val after = readActivities(otherRepo.allEvents())

            // the cancelled set is excluded on both sides, and everything else is there
            assertEquals(before.size, after.size)
            assertEquals(before.map { it.uid }, after.map { it.uid })
            assertEquals(before.map { it.form }, after.map { it.form })

            val workout = otherRepo.currentWorkout()
            assertNotNull("the workout has to come back", workout)
            assertEquals("Evening gym", workout!!.name)
            // the rows recorded inside it still name it
            val rows = otherRepo.allEvents().filter { it.workoutUid != null }
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it.workoutUid == workout.uid })
        } finally {
            other.close()
        }
    }

    @Test
    fun `the settings in the file are applied, and a file without them changes nothing`() = runTest {
        writeAPhone()
        val text = backup.export()

        val landing = FakeSettings(PortableSettings())
        val other = freshDb()
        try {
            val report = JournalBackup(other, landing).restore(accepted(text))

            assertTrue(report.settingsApplied)
            assertEquals(210, landing.value.defaultRestSec)
            assertTrue(landing.value.speak)
            assertTrue(landing.value.timerEnabled)
            assertEquals(CelebrationMode.EVERY_SET.code, landing.value.celebrationMode)
        } finally {
            other.close()
        }

        // an export taken without a settings gateway carries none, and restoring it is silent
        val bare = JournalBackup(db, null).export()
        assertNull(accepted(bare).settings)
        val untouched = FakeSettings(PortableSettings(defaultRestSec = 99))
        val third = freshDb()
        try {
            val report = JournalBackup(third, untouched).restore(accepted(bare))
            assertTrue(!report.settingsApplied)
            assertEquals(99, untouched.value.defaultRestSec)
        } finally {
            third.close()
        }
    }

    /**
     * The one link the schema still keeps as a bare row number. A planned line pointing at an
     * exercise that is not in the file cannot be restored — the column will not take a null and
     * inventing an exercise would be a lie about what the session is — so it is dropped AND
     * counted. A silent drop here is a plan that looks complete and is not.
     */
    @Test
    fun `a planned line naming an exercise the file does not carry is dropped and reported`() = runTest {
        writeAPhone()
        val file = accepted(backup.export())
        val gutted = file.copy(
            exercises = file.exercises.filter { it.name != "Hangs" },
            programs = emptyList(), // the program names it too, and that is a different note
        )

        val other = freshDb()
        try {
            val report = JournalBackup(other, null).restore(gutted)

            assertEquals(1, report.plannedLinesSkipped)
            assertEquals(1, ActivityRepository(other).allSlots().single().exercises.size)
            assertTrue(report.notes.any { it.contains("planned line") })
        } finally {
            other.close()
        }
    }

    @Test
    fun `a program naming an exercise the file does not carry arrives unlinked and says so`() = runTest {
        writeAPhone()
        val file = accepted(backup.export())
        val gutted = file.copy(exercises = file.exercises.filter { it.name != "Hangs" })

        val other = freshDb()
        try {
            val report = JournalBackup(other, null).restore(gutted)

            // three programs still arrive (the library is not filtered by whether its exercise
            // survived the gutting), and TWO of them named "Hangs" — the hand-written
            // "Hangboard repeaters 7:3" and the one ensureExercise folded "Hangs"'s own protocol
            // into — so both come back unlinked; "One-arm hang"'s own protocol program is
            // unaffected, since that exercise was not removed
            assertEquals(3, report.programsAdded)
            val unlinked = ProgramRepository(other).allPrograms().filter { it.exerciseId == null }
            assertEquals(2, unlinked.size)
            assertTrue(report.notes.any { it.contains("unlinked") })
        } finally {
            other.close()
        }
    }

    /**
     * A program whose name is taken by a DIFFERENT program is marked rather than merged over,
     * exactly as the program file does it: the copy on the phone may have been edited since the
     * backup was taken, and overwriting a hand-tuned protocol with an older one cannot be undone.
     */
    @Test
    fun `a program arriving under a name already taken is kept apart`() = runTest {
        writeAPhone()
        val text = backup.export()

        val other = freshDb()
        try {
            ProgramRepository(other).save(
                WorkoutProgram(
                    name = "Hangboard repeaters 7:3",
                    groups = listOf(ProgramGroup("g", listOf(ProgramBlock("Hang", workSec = 5)))),
                )
            )
            JournalBackup(other, null).restore(accepted(text))

            // four now, not two: the phone's export also carries the minimal programs
            // ensureExercise folded "Hangs" and "One-arm hang"'s own protocols into, which name
            // nothing this phone already has and arrive unremarkably — the point of this test is
            // only the two that DO clash by name
            val names = ProgramRepository(other).allPrograms().map { it.name }
            assertEquals(
                "the local hand-tuned program must not be overwritten",
                1,
                names.count { it == "Hangboard repeaters 7:3" },
            )
            assertTrue(
                "the clashing import is kept apart rather than merged over it",
                "Hangboard repeaters 7:3 (imported)" in names,
            )
            assertTrue("Hangs protocol" in names)
            assertTrue("One-arm hang protocol" in names)
            assertEquals(4, names.size)
        } finally {
            other.close()
        }
    }

    // --- THE acceptance test: export, wipe the database, restore ---------------------------

    /**
     * The test the owner of this feature said would be checked first, done literally: export,
     * erase the very database this phone was using, restore the same file back into it, and
     * the state has to match field for field. Not "restore into a fresh database elsewhere" —
     * the test above already covers that — but wiping the database this phone actually had and
     * rebuilding it from nothing but the file.
     */
    @Test
    fun `THE circular test - export, wipe the database, restore, and the same file comes back out`() = runTest {
        writeAPhone()
        val exported = backup.export("2026-08-07", "device-1")
        assertTrue("the phone actually wrote something worth losing", db.events().all().isNotEmpty())

        db.clearAllTables()
        assertEquals("the database really is empty now", 0, db.events().all().size)
        assertEquals(0, db.exercises().all().size)

        val report = backup.restore(accepted(exported))
        assertTrue("a wipe-and-restore has to write the journal back", report.eventsAdded > 0)

        assertEquals(exported, backup.export("2026-08-07", "device-1"))
    }

    // --- historicity: deleted, superseded and corrected rows survive the trip ---------------

    /**
     * A cancelled set is not this app hiding something from itself: it is written as an event
     * like any other, and the old, JSON-and-CSV-as-two-files design used to leave it out of the
     * read-only CSV entirely — a table that could not answer "what did this used to say" at
     * all. Both rows travel now; the flag is what tells them apart.
     */
    @Test
    fun `a cancelled set travels in the file, marked as not the current version`() = runTest {
        val benchId = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val bench = repo.toRef(repo.exercise(benchId)!!)
        val today = LocalDate.now().toString()

        val keptId = repo.record(strengthSetOf(bench, today, reps = 5, weightKg = 60.0))
        val cancelledId = repo.record(strengthSetOf(bench, today, reps = 5, weightKg = 65.0))
        repo.cancelSet(cancelledId)

        val text = backup.export()
        val keptUid = db.events().byId(keptId)!!.uid
        val cancelledUid = db.events().byId(cancelledId)!!.uid

        val rows = csvRowsByColumn(text).associateBy { it["uid"] }
        assertEquals("true", rows.getValue(keptUid)["current_version"])
        assertEquals("false", rows.getValue(cancelledUid)["current_version"])

        // and both survive a full wipe and restore, the cancellation included
        db.clearAllTables()
        backup.restore(accepted(text))
        val allEvents = db.events().all().map { it.toJournalEvent() }
        assertEquals(2, allEvents.count { it.uid == keptUid || it.uid == cancelledUid })

        val live = readActivities(allEvents).map { it.uid }
        assertTrue(keptUid in live)
        assertTrue("a restored phone must still know the cancelled set is not current", cancelledUid !in live)

        val withHistory = readActivities(allEvents, includeDeleted = true).map { it.uid }
        assertTrue("but the cancellation itself is not lost", cancelledUid in withHistory)
    }

    /**
     * A correction (the full-version model — see domain/Amendments.kt) writes a whole new row
     * and marks the old one superseded. Both travel; the derived `reps` column shows the
     * correction only on the row that is now current, and the round trip carries every version.
     */
    @Test
    fun `a corrected entry - the old row is marked superseded, the new one current, and the fix shows`() = runTest {
        val benchId = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val bench = repo.toRef(repo.exercise(benchId)!!)
        val today = LocalDate.now().toString()

        val originalId = repo.record(strengthSetOf(bench, today, reps = 5, weightKg = 60.0))
        val originalUid = db.events().byId(originalId)!!.uid
        val newId = repo.amendEntry(originalId, strengthSetOf(bench, today, reps = 8, weightKg = 60.0))
        assertNotNull("the correction has to actually write a new row", newId)
        val newUid = db.events().byId(newId!!)!!.uid

        val text = backup.export()
        val rows = csvRowsByColumn(text).associateBy { it["uid"] }

        assertEquals("false", rows.getValue(originalUid)["current_version"])
        assertEquals("true", rows.getValue(newUid)["current_version"])
        // the derived column, for the eye: the row that is IN FORCE now shows 8 reps
        assertEquals("8", rows.getValue(newUid)["reps"])
        // WHEN IT HAPPENED is inherited by the correction from the row it replaces, not
        // re-stamped to the moment of the correction itself - see writeNewVersion's own KDoc
        assertEquals(rows.getValue(originalUid)["happened_at"], rows.getValue(newUid)["happened_at"])

        db.clearAllTables()
        backup.restore(accepted(text))
        assertEquals(text, backup.export())

        val restoredNew = db.events().all().single { it.uid == newUid }
        assertEquals(8, (readActivities(listOf(restoredNew.toJournalEvent())).single().form as StrengthSet).reps)
    }

    /**
     * WHEN THE TRAINING HAPPENED, separate from WHEN THE ROW WAS WRITTEN — the one column the
     * old JSON envelope carried into every restore as null, silently, because nothing in it
     * ever read [EventEntity.occurredTs]. Built by hand rather than through the repository:
     * nothing public backdates an entry outside of a correction, and a correction's own
     * inheritance is already the previous test's job.
     */
    @Test
    fun `time executed survives distinctly from time written, through a wipe and restore`() = runTest {
        val id = db.events().insert(
            EventEntity(
                ts = "2026-08-07T18:00:00",
                type = TYPE_DURATION,
                payload = """{"op_date":"2026-08-07","activity":"Stretching","duration_sec":600,"activity_key":"stretching"}""",
                occurredTs = "2026-08-07T15:00:00",
            )
        )
        val stored = db.events().byId(id)!!
        assertNotEquals("the fixture has to actually separate the two moments", stored.ts, stored.occurredTs)

        val text = backup.export()
        db.clearAllTables()
        backup.restore(accepted(text))

        val restored = db.events().all().single { it.uid == stored.uid }
        assertEquals(stored.ts, restored.ts)
        assertEquals(stored.occurredTs, restored.occurredTs)
        assertNotEquals(restored.ts, restored.occurredTs)
    }

    // --- ACCEPTANCE: the timezone offset (schema version 2 of the file) --------------------

    /**
     * THE acceptance test this feature exists for: a journal exported in one zone and restored
     * in another keeps every event's OWN offset rather than taking on the restoring device's —
     * which is exactly the loss domain/JournalTransfer.kt's v1 -> v2 KDoc describes. Two fixed
     * offsets rather than named zones with daylight saving, so nothing about which day a set
     * falls on can confound the one fact under test here.
     */
    @Test
    fun `an event's timezone offset survives being exported in one zone and restored in another`() = runTest {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok")) // UTC+7, no daylight saving
            val id = db.events().insert(
                EventEntity(
                    ts = "2026-08-07T20:00:00",
                    type = TYPE_DURATION,
                    payload = """{"op_date":"2026-08-07","activity":"Stretching","duration_sec":600,"activity_key":"stretching"}""",
                    tsUtc = "2026-08-07T13:00:00Z",
                    tzOffsetMin = 420,
                )
            )
            val stored = db.events().byId(id)!!
            val text = backup.export()

            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow")) // UTC+3, no daylight saving
            val other = freshDb()
            try {
                JournalBackup(other, null).restore(accepted(text))
                val restored = other.events().all().single { it.uid == stored.uid }

                assertEquals("the local wall clock the event was written with", stored.ts, restored.ts)
                assertEquals(
                    "the restoring device's own zone (+180) must not overwrite the file's own offset (+420)",
                    420, restored.tzOffsetMin,
                )
                assertEquals("2026-08-07T13:00:00Z", restored.tsUtc)
            } finally {
                other.close()
            }
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    /**
     * The other half of the acceptance criteria: a v1 file — written before `tz_offset_min`
     * existed, so the column is absent from every row rather than blank on one — is not refused
     * for it (see JournalTransferTest.kt's own pin of that at the format level) and restores by
     * FALLING BACK to exactly what a restore did before this feature: the device doing the
     * restore. Honest, and not a crash — the property this test actually defends.
     */
    @Test
    fun `a v1 file with no tz_offset_min column restores by falling back to the device's own zone`() = runTest {
        val id = db.events().insert(
            EventEntity(
                ts = "2026-08-07T20:00:00",
                type = TYPE_DURATION,
                payload = """{"op_date":"2026-08-07","activity":"Stretching","duration_sec":600,"activity_key":"stretching"}""",
            )
        )
        val stored = db.events().byId(id)!!
        val text = backup.export()

        val lines = text.split("\n")
        val header = splitCsvRow(lines.first()).toMutableList()
        val colIndex = header.indexOf("tz_offset_min")
        assertTrue("the column has to exist in a normal export to strip it", colIndex >= 0)
        header.removeAt(colIndex)
        header[0] = "gachimuchi_journal_v1" // what a v1 file's own version marker says
        val v1 = (
            listOf(joinCsvRow(header)) +
                lines.drop(1).map { line ->
                    if (line.isBlank()) line else joinCsvRow(splitCsvRow(line).toMutableList().apply { removeAt(colIndex) })
                }
            ).joinToString("\n")

        db.clearAllTables()
        val report = backup.restore(accepted(v1))
        assertTrue("a v1 file must not be refused", report.eventsAdded > 0)

        val restored = db.events().all().single { it.uid == stored.uid }
        assertEquals(stored.ts, restored.ts)
        assertNotNull(
            "the fallback still resolves an offset, off the device's own zone, exactly as every restore " +
                "did before this feature",
            restored.tzOffsetMin,
        )
    }
}

/**
 * A real (quote-aware) CSV row splitter/joiner, the same rule `csvField` in domain/
 * JournalTransfer.kt uses — duplicated here rather than exposed, because only a TEST needs to
 * mangle an exported file this literally, to simulate a generation of it that predates a column.
 */
private fun splitCsvRow(line: String): List<String> {
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                cur.append('"')
                i++
            }
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> {
                cells.add(cur.toString())
                cur.clear()
            }
            else -> cur.append(c)
        }
        i++
    }
    cells.add(cur.toString())
    return cells
}

private fun joinCsvRow(cells: List<String>): String = cells.joinToString(",") { cell ->
    if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + cell.replace("\"", "\"\"") + "\""
    } else {
        cell
    }
}
