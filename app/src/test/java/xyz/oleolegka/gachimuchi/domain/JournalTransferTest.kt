package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup file as a FORMAT: what it accepts, what it turns away, and what it does when a
 * file's catalog meets a catalog that is already here.
 *
 * Nothing in this class touches a database — the round trip through Room is
 * `data/JournalBackupTest.kt`. What is pinned here is the half that can be got wrong quietly:
 * a refusal that does not happen, a merge that decides two exercises are one, and — the
 * property this format is built around — a restore that would have read a column it must not.
 */
class JournalTransferTest {

    private var nextId = 1L

    private fun event(
        uid: String,
        payload: String = """{"op_date":"2026-08-07","activity":"Stretching","duration_sec":600,"activity_key":"stretching"}""",
        type: String = TYPE_DURATION,
        ts: String = "2026-08-07T18:00:00",
        workoutUid: String? = null,
        occurredTs: String? = null,
    ) = JournalEvent(nextId++, ts, 1, 1, type, payload, uid = uid, workoutUid = workoutUid, occurredTs = occurredTs)

    private fun exercise(
        uid: String,
        name: String = "Bench press",
        form: Int = ExerciseForm.STRENGTH.code,
        /** The protocol program's uid, or null for no protocol — see [PortableExercise]. */
        programUid: String? = null,
    ) = PortableExercise(
        uid = uid,
        name = name,
        form = form,
        createdAt = "2026-08-01T09:00:00",
        protocolProgramUid = programUid,
    )

    private fun program(uid: String, name: String = "Tabata 20:10") = PortableProgramRow(
        uid = uid,
        name = name,
        groups = listOf(
            ProgramGroup(name = "Tabata", blocks = listOf(ProgramBlock("Work", workSec = 20, restSec = 10, repeats = 8))),
        ),
        createdAt = "2026-08-01T09:00:00",
    )

    private fun slot(uid: String, exercises: List<PortablePlannedExercise> = emptyList()) = PortableSlot(
        uid = uid,
        name = "Gym",
        atTime = "18:30",
        repeatRule = REPEAT_WEEKLY,
        anchorDate = "2026-08-06",
        createdAt = "2026-08-01T09:00:00",
        exercises = exercises,
    )

    private fun fileOf(
        events: List<JournalEvent> = emptyList(),
        catalog: List<CatalogRow> = emptyList(),
        exercises: List<PortableExercise> = emptyList(),
        slots: List<PortableSlot> = emptyList(),
        programs: List<PortableProgramRow> = emptyList(),
        settings: PortableSettings? = null,
    ) = writeJournalFile(events, catalog, exercises, slots, programs, settings, "2026-08-07", "device-1")

    private fun loaded(text: String): JournalFile {
        val parsed = readJournalFile(text)
        assertTrue("expected the file to load, got $parsed", parsed is JournalImport.Loaded)
        return (parsed as JournalImport.Loaded).file
    }

    private fun rejection(text: String): String {
        val parsed = readJournalFile(text)
        assertTrue("expected the file to be refused, got $parsed", parsed is JournalImport.Rejected)
        return (parsed as JournalImport.Rejected).reason
    }

    // --- the envelope --------------------------------------------------------------------

    @Test
    fun `a file written by this app reads back as exactly what went into it`() {
        val ev = event("0198c2f0-0000-7000-8000-000000000001", occurredTs = "2026-08-07T17:55:00")
        val exercises = listOf(exercise("0198c2ef-0000-7000-8000-00000000000a"))
        val slots = listOf(
            slot(
                "0198c2ef-0000-7000-8000-00000000000b",
                listOf(
                    PortablePlannedExercise(
                        uid = "0198c2ef-0000-7000-8000-00000000000c",
                        exerciseUid = "0198c2ef-0000-7000-8000-00000000000a",
                        restSec = 150,
                    )
                ),
            )
        )
        val programs = listOf(program("0198c2ef-0000-7000-8000-00000000000d"))
        val settings = PortableSettings(defaultRestSec = 210, speak = true, timerEnabled = true)

        val file = loaded(fileOf(listOf(ev), exercises = exercises, slots = slots, programs = programs, settings = settings))

        assertEquals("2026-08-07", file.exportedAt)
        assertEquals("device-1", file.deviceId)
        assertEquals(
            listOf(PortableEvent(ev.uid, ev.ts, ev.type, ev.payload, ev.workoutUid, ev.authorId, ev.occurredTs)),
            file.events,
        )
        assertEquals(exercises, file.exercises)
        assertEquals(slots, file.slots)
        assertEquals(programs, file.programs)
        assertEquals(settings, file.settings)
    }

    /**
     * The property the whole format is built around: a payload goes through untouched, and it
     * is never even parsed — see [PortableEvent]'s own KDoc.
     */
    @Test
    fun `a payload with fields this build has never heard of survives the trip`() {
        val payload = """{"op_date":"2026-08-07","exercise":"Bench press","reps":5,""" +
            """"weight_kg":72.5,"warm_up":true,"hand":"left","bodyweight_kg":74.3}"""
        val file = loaded(fileOf(listOf(event("0198c2f0-0000-7000-8000-000000000001", payload, TYPE_STRENGTH_SET))))

        assertEquals(payload, file.events.single().payload)
    }

    /**
     * A row this app could not have written, and would rather keep than tidy away: a payload
     * that is not JSON at all, with a comma, a quote and a newline of its own — exactly what
     * the CSV escaping exists for. It rides as a string and comes back as the same bytes.
     */
    @Test
    fun `a payload that is not JSON, and is not even valid CSV content, is carried verbatim`() {
        val broken = "{this was truncated, mid-\"writ\nsecond line"
        val file = loaded(fileOf(listOf(event("0198c2f0-0000-7000-8000-000000000001", broken))))

        assertEquals(broken, file.events.single().payload)
    }

    @Test
    fun `an empty file is refused, and so is one that is not a journal backup at all`() {
        assertTrue(rejection("").contains("empty"))
        assertTrue(rejection("not a file at all").contains("journal backup"))
    }

    @Test
    fun `another app's file is refused, generically - this format cannot open a foreign one to read its name`() {
        val reason = rejection(writeProgramFile(emptyList()))
        assertTrue(reason, reason.contains("journal backup"))
    }

    @Test
    fun `a file from a newer version of the app is refused with both numbers`() {
        val newer = fileOf(listOf(event("0198c2f0-0000-7000-8000-000000000001")))
            .replaceFirst(CSV_HEADER[0], "gachimuchi_journal_v${CSV_VERSION_FOR_TEST + 1}")
        val reason = rejection(newer)

        assertTrue(reason, reason.contains("${CSV_VERSION_FOR_TEST + 1}"))
        assertTrue(reason, reason.contains("$CSV_VERSION_FOR_TEST"))
    }

    @Test
    fun `a version that is not a version is refused`() {
        val bad = fileOf().replaceFirst(CSV_HEADER[0], "gachimuchi_journal_vfortnightly")
        assertTrue(rejection(bad).contains("not a version"))
    }

    /**
     * Identity is what the merge stands on, so a file that cannot keep its own identities
     * straight is refused before any of it is written. Two rows under one uid would make "have
     * I got this one already" unanswerable, and every import of that file would add rows again.
     */
    @Test
    fun `two rows sharing a uid, or a row without one, are refused`() {
        val twice = fileOf(
            listOf(
                event("0198c2f0-0000-7000-8000-000000000001"),
                event("0198c2f0-0000-7000-8000-000000000001"),
            )
        )
        assertTrue(rejection(twice).contains("0198c2f0-0000-7000-8000-000000000001"))

        assertTrue(rejection(fileOf(listOf(event("")))).contains("no uid"))
        // and the check reaches into a slot's composition, which has identities of its own
        val lines = listOf(
            PortablePlannedExercise(uid = "0198c2ef-0000-7000-8000-00000000000c"),
            PortablePlannedExercise(uid = "0198c2ef-0000-7000-8000-00000000000c"),
        )
        assertTrue(rejection(fileOf(slots = listOf(slot("0198c2ef-0000-7000-8000-00000000000b", lines)))).contains("planned line"))
    }

    @Test
    fun `an event with no type and an exercise with an unknown form are refused`() {
        val typeless = fileOf(listOf(event("0198c2f0-0000-7000-8000-000000000001", type = "")))
        assertTrue(rejection(typeless).contains("no type"))

        val unknownForm = fileOf(exercises = listOf(exercise("0198c2ef-0000-7000-8000-00000000000a", form = 99)))
        val reason = rejection(unknownForm)
        assertTrue(reason, reason.contains("99") && reason.contains("newer version"))
    }

    @Test
    fun `a plan with a repeat rule this build cannot follow is refused`() {
        val odd = fileOf(slots = listOf(slot("0198c2ef-0000-7000-8000-00000000000b").copy(repeatRule = "fortnightly")))
        assertTrue(rejection(odd).contains("fortnightly"))
    }

    /**
     * The program bounds are not re-implemented here — the file goes through the same
     * validator the program file uses, so a program that cannot be run cannot arrive by the
     * back door either.
     */
    @Test
    fun `a program that cannot be run takes the whole file down with it`() {
        val impossible = program("0198c2ef-0000-7000-8000-00000000000d").copy(
            groups = listOf(ProgramGroup(name = "g", blocks = listOf(ProgramBlock("Hang", workSec = 0)))),
        )
        assertTrue(rejection(fileOf(programs = listOf(impossible))).contains("out of range"))

        val nameless = program("0198c2ef-0000-7000-8000-00000000000d").copy(name = "  ")
        assertTrue(rejection(fileOf(programs = listOf(nameless))).contains("no name"))
    }

    @Test
    fun `a row not the same width as the header is refused`() {
        val text = fileOf(listOf(event("0198c2f0-0000-7000-8000-000000000001")))
        val mangled = text.lines().let { lines ->
            (lines.take(1) + lines.drop(1).map { if (it.isBlank()) it else it.substringBeforeLast(",") })
                .joinToString("\n")
        }
        assertTrue(rejection(mangled).contains("column"))
    }

    // --- THE rule: a restore never reads a derived column -----------------------------------

    /**
     * The property the owner of this format asked to be defended in the code, in capital
     * letters — this is the test that stands behind the capital letters. Every DERIVED cell
     * (see [CSV_HEADER]) is scrambled after a normal export; what a restore reads back must not
     * move by one character, because a restore is not allowed to have looked at them at all.
     */
    @Test
    fun `scrambling every derived column changes nothing a restore reads`() {
        val ev = event(
            "0198c2f0-0000-7000-8000-000000000001",
            """{"op_date":"2026-08-07","exercise":"Bench press","reps":5,"weight_kg":72.5,"exercise_id":9,"exercise_key":"bench press"}""",
            TYPE_STRENGTH_SET,
        )
        val catalog = listOf(CatalogRow(id = 9, uid = "0198c2ef-9999-7000-8000-000000000009", name = "Bench press", form = ExerciseForm.STRENGTH.code, createdAt = "2026-01-01T00:00:00"))
        val original = fileOf(listOf(ev), catalog = catalog)

        // a real CSV split/join, not a naive split(","): the payload cells here carry commas
        // and quotes of their own, exactly what quoting exists for
        val lines = original.split("\n")
        val header = splitCsvLine(lines.first())
        val derivedStart = header.indexOf("current_version")
        assertTrue("the derived block has to start somewhere", derivedStart > 0)
        val scrambled = (
            listOf(lines.first()) +
                lines.drop(1).map { line ->
                    val cells = splitCsvLine(line).toMutableList()
                    for (i in derivedStart until cells.size) cells[i] = "SCRAMBLED-$i"
                    joinCsvLine(cells)
                }
            ).joinToString("\n")

        assertEquals(loaded(original), loaded(scrambled))
    }

    /**
     * The derived "side" column used to read off [HoldSet] alone; a strength set's own side
     * (see [LoadedSet.side]) reaches it too now, in the payload verbatim AND in this column.
     */
    @Test
    fun `a strength set's side reaches both the raw payload and the derived column`() {
        val ev = event(
            "0198c2f0-0000-7000-8000-000000000002",
            """{"op_date":"2026-08-07","exercise":"Pistol squat","reps":5,"weight_kg":40.0,""" +
                """"side":"left","exercise_key":"pistol squat"}""",
            TYPE_STRENGTH_SET,
        )
        val text = fileOf(listOf(ev))

        val lines = text.split("\n")
        val header = splitCsvLine(lines.first())
        // the meta row precedes it, so pick the event row by its own event_type rather than
        // assuming a position
        val row = splitCsvLine(lines.drop(1).first { it.contains(TYPE_STRENGTH_SET) })
        assertEquals("left", row[header.indexOf("side")])
        assertTrue("the raw payload carries the field verbatim as well", row[header.indexOf("payload")].contains("\"side\":\"left\""))
    }

    /** A real (quote-aware) CSV row splitter, the way any spreadsheet reads one. */
    private fun splitCsvLine(line: String): List<String> {
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

    /** The write side of [splitCsvLine] — mirrors [csvField]'s own escaping rule. */
    private fun joinCsvLine(cells: List<String>): String = cells.joinToString(",") { cell ->
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }
    }

    // --- the catalog merge --------------------------------------------------------------

    @Test
    fun `an exercise already here by uid is neither inserted nor aliased`() {
        val stored = listOf(exercise("0198c2ef-0000-7000-8000-00000000000a"))
        val merge = mergeExercises(stored, stored)

        assertEquals(emptyList<PortableExercise>(), merge.toInsert)
        assertEquals(emptyMap<String, String>(), merge.aliases)
        assertEquals(1, merge.alreadyHere)
    }

    @Test
    fun `an exercise this phone has never seen is inserted under its own key`() {
        val merge = mergeExercises(
            incoming = listOf(exercise("0198c2ef-0000-7000-8000-00000000000a", name = "Squat")),
            stored = listOf(exercise("0198c2ef-0000-7000-8000-00000000000b", name = "Bench press")),
        )

        assertEquals(listOf("Squat"), merge.toInsert.map { it.name })
        assertTrue(merge.aliases.isEmpty())
        assertEquals(0, merge.alreadyHere)
        assertEquals("0198c2ef-0000-7000-8000-00000000000a", merge.resolve("0198c2ef-0000-7000-8000-00000000000a"))
    }

    /**
     * The case the whole second pass exists for: two phones that invented "Bench press"
     * independently. One key is declared the main one and it is the one already here — see
     * [mergeExercises] for what that costs and why it is that way round.
     */
    @Test
    fun `the same exercise under another key is filed under the key already here`() {
        val merge = mergeExercises(
            incoming = listOf(exercise("0198c2ef-0000-7000-8000-0000000000ff", name = "  bench   PRESS ")),
            stored = listOf(exercise("0198c2ef-0000-7000-8000-00000000000a", name = "Bench press")),
        )

        assertTrue("nothing is inserted for an exercise that is already here", merge.toInsert.isEmpty())
        assertEquals(
            mapOf("0198c2ef-0000-7000-8000-0000000000ff" to "0198c2ef-0000-7000-8000-00000000000a"),
            merge.aliases,
        )
        assertEquals(
            "0198c2ef-0000-7000-8000-00000000000a",
            merge.resolve("0198c2ef-0000-7000-8000-0000000000ff"),
        )
    }

    /**
     * And the half that must NOT merge. §12-A: the protocol is part of what a hangboard
     * exercise IS, so two rows that share a name and differ in it are two histories. The form
     * is in there too, for the reason [ExerciseIdentity] gives.
     */
    @Test
    fun `a different protocol or form keeps two histories apart`() {
        val stored = listOf(
            exercise(
                "0198c2ef-0000-7000-8000-000000000001", "Hangs", ExerciseForm.HOLD.code,
                programUid = "0198c2ef-0000-7000-8000-0000000000e1",
            )
        )

        val otherProtocol = exercise(
            "0198c2ef-0000-7000-8000-000000000003", "Hangs", ExerciseForm.HOLD.code,
            programUid = "0198c2ef-0000-7000-8000-0000000000e2",
        )
        val otherForm = exercise(
            "0198c2ef-0000-7000-8000-000000000004", "Hangs", ExerciseForm.DURATION.code,
            programUid = "0198c2ef-0000-7000-8000-0000000000e1",
        )

        val merge = mergeExercises(listOf(otherProtocol, otherForm), stored)

        assertEquals(2, merge.toInsert.size)
        assertTrue("none of these is the stored exercise", merge.aliases.isEmpty())
    }

    /**
     * Two rows of one identity INSIDE a single file collapse into one insert. A file like that
     * is not this app's own export, but it is what a hand-merged pair of backups looks like,
     * and importing it must not create the duplicate the merge exists to prevent.
     */
    @Test
    fun `one identity twice in a file is inserted once and the second is aliased to it`() {
        val first = exercise("0198c2ef-0000-7000-8000-000000000001", name = "Squat")
        val second = exercise("0198c2ef-0000-7000-8000-000000000002", name = "squat")

        val merge = mergeExercises(listOf(first, second), stored = emptyList())

        assertEquals(listOf(first), merge.toInsert)
        assertEquals(
            "0198c2ef-0000-7000-8000-000000000001",
            merge.resolve("0198c2ef-0000-7000-8000-000000000002"),
        )
    }

    // --- the report ---------------------------------------------------------------------

    @Test
    fun `the report says both halves of every section, and swallows nothing`() {
        val report = ImportReport(
            eventsAdded = 0,
            eventsAlreadyHere = 412,
            exercisesAdded = 2,
            exercisesAlreadyHere = 6,
            exercisesMergedByIdentity = 1,
            slotsAdded = 1,
            plannedLinesSkipped = 3,
            programsAlreadyHere = 4,
            settingsApplied = true,
            notes = listOf("something did not fit"),
        )
        val lines = report.lines()

        assertTrue(lines.any { it.contains("0 added") && it.contains("412 already here") })
        assertTrue(lines.any { it.startsWith("Exercises: 2 added, 6 already here") })
        assertTrue(lines.any { it.startsWith("Plan: 1 added") })
        assertTrue(lines.any { it.startsWith("Programs: 0 added, 4 already here") })
        assertTrue(lines.any { it.contains("Settings") })
        assertTrue(lines.contains("something did not fit"))

        assertTrue(report.addedAnything)
        assertTrue(!ImportReport(eventsAlreadyHere = 412).addedAnything)
    }

    @Test
    fun `settings survive the trip in both directions`() {
        val settings = portableSettings(
            timer = TimerSettings(defaultRestSec = 240, speak = true, defaultSets = 6),
            timerEnabled = true,
            celebration = CelebrationMode.EVERY_SET,
        )
        val back = loaded(fileOf(settings = settings)).settings

        assertEquals(settings, back)
        assertEquals(240, back!!.toTimerSettings().defaultRestSec)
        assertTrue(back.toTimerSettings().speak)
        assertEquals(6, back.toTimerSettings().defaultSets)
        assertEquals(CelebrationMode.EVERY_SET, CelebrationMode.fromCode(back.celebrationMode))
    }

    @Test
    fun `a file with nothing in it is legal and carries no settings`() {
        val file = loaded(fileOf())

        assertTrue(file.events.isEmpty() && file.exercises.isEmpty())
        assertTrue(file.slots.isEmpty() && file.programs.isEmpty())
        assertNull(file.settings)
    }

    /** The identity of a row is the row's own uid unless the merge said otherwise. */
    @Test
    fun `resolving a uid nobody aliased returns the same string`() {
        val merge = ExerciseMerge(emptyList(), emptyMap(), 0)
        val uid = "0198c2ef-0000-7000-8000-000000000001"

        assertSame(uid, merge.resolve(uid))
    }
}

/** Mirrors the private `CSV_VERSION` in domain/JournalTransfer.kt for the version-guard tests. */
private const val CSV_VERSION_FOR_TEST = 1
