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
 * a refusal that does not happen, and a merge that decides two exercises are one.
 */
class JournalTransferTest {

    private fun event(
        uid: String,
        payload: String = """{"op_date":"2026-08-07","activity":"Stretching","duration_sec":600,"activity_key":"stretching"}""",
        type: String = TYPE_DURATION,
        workoutUid: String? = null,
    ) = PortableEvent(
        uid = uid,
        ts = "2026-08-07T18:00:00",
        type = type,
        payload = payloadToElement(payload),
        workoutUid = workoutUid,
    )

    private fun exercise(
        uid: String,
        name: String = "Bench press",
        form: Int = ExerciseForm.STRENGTH.code,
        edge: Double? = null,
        work: Double? = null,
        rest: Double? = null,
    ) = PortableExercise(
        uid = uid,
        name = name,
        form = form,
        createdAt = "2026-08-01T09:00:00",
        edgeMm = edge,
        protocolWorkSec = work,
        protocolRestSec = rest,
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
        events: List<PortableEvent> = emptyList(),
        exercises: List<PortableExercise> = emptyList(),
        slots: List<PortableSlot> = emptyList(),
        programs: List<PortableProgramRow> = emptyList(),
        settings: PortableSettings? = null,
    ) = writeJournalFile(events, exercises, slots, programs, settings, "2026-08-07", "device-1")

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

    @Test
    fun `a file written by this app reads back as exactly what went into it`() {
        val events = listOf(event("0198c2f0-0000-7000-8000-000000000001"))
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

        val file = loaded(fileOf(events, exercises, slots, programs, settings))

        assertEquals(JOURNAL_FILE_FORMAT, file.format)
        assertEquals(JOURNAL_FILE_VERSION, file.version)
        assertEquals("2026-08-07", file.exportedAt)
        assertEquals("device-1", file.deviceId)
        assertEquals(events, file.events)
        assertEquals(exercises, file.exercises)
        assertEquals(slots, file.slots)
        assertEquals(programs, file.programs)
        assertEquals(settings, file.settings)
    }

    /**
     * The property the whole format is built around: a payload goes through untouched.
     *
     * The fixture carries a key no form in this build has ever heard of, which is what the
     * next change to a set form looks like from here. An exporter that decoded payloads into
     * typed forms would drop it, and the drop would be invisible until somebody restored a
     * backup and found the field gone.
     */
    @Test
    fun `a payload with fields this build has never heard of survives the trip`() {
        val payload = """{"op_date":"2026-08-07","exercise":"Bench press","reps":5,""" +
            """"weight_kg":72.5,"warm_up":true,"hand":"left","bodyweight_kg":74.3}"""
        val file = loaded(fileOf(listOf(event("0198c2f0-0000-7000-8000-000000000001", payload, TYPE_STRENGTH_SET))))

        assertEquals(payload, elementToPayload(file.events.single().payload))
    }

    /**
     * A row this app could not have written, and would rather keep than tidy away: a payload
     * that is not JSON at all. It rides as a string and comes back as the same bytes.
     */
    @Test
    fun `a payload that is not JSON is carried verbatim rather than dropped`() {
        val broken = "{this was truncated mid-writ"
        val file = loaded(fileOf(listOf(event("0198c2f0-0000-7000-8000-000000000001", broken))))

        assertEquals(broken, elementToPayload(file.events.single().payload))
    }

    @Test
    fun `an empty file is refused, and so is one that is not JSON`() {
        assertTrue(rejection("").contains("empty"))
        assertTrue(rejection("not a file at all").contains("could not be read"))
    }

    @Test
    fun `another app's file is refused by name`() {
        val reason = rejection(writeProgramFile(emptyList()))
        assertTrue(reason, reason.contains(PROGRAM_FILE_FORMAT))
        assertTrue(reason, reason.contains(JOURNAL_FILE_FORMAT))
    }

    @Test
    fun `a file from a newer version of the app is refused with both numbers`() {
        val newer = fileOf(listOf(event("0198c2f0-0000-7000-8000-000000000001")))
            .replace("\"version\": $JOURNAL_FILE_VERSION", "\"version\": ${JOURNAL_FILE_VERSION + 1}")
        val reason = rejection(newer)

        assertTrue(reason, reason.contains("${JOURNAL_FILE_VERSION + 1}"))
        assertTrue(reason, reason.contains("$JOURNAL_FILE_VERSION"))
    }

    @Test
    fun `a version that is not a version is refused`() {
        val zero = fileOf().replace("\"version\": $JOURNAL_FILE_VERSION", "\"version\": 0")
        assertTrue(rejection(zero).contains("not a version"))
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
        val odd = fileOf(slots = listOf(slot("0198c2ef-0000-7000-8000-00000000000b"))).replace(
            "\"repeat_rule\": \"$REPEAT_WEEKLY\"", "\"repeat_rule\": \"fortnightly\"",
        )
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
     * And the half that must NOT merge. §12-A: the edge and the protocol are part of what a
     * hangboard exercise IS, so two rows that share a name and differ in either are two
     * histories. The form is in there too, for the reason [ExerciseIdentity] gives.
     */
    @Test
    fun `a different edge, protocol or form keeps two histories apart`() {
        val stored = listOf(exercise("0198c2ef-0000-7000-8000-000000000001", "Hangs", ExerciseForm.HOLD.code, edge = 20.0, work = 7.0, rest = 3.0))

        val otherEdge = exercise("0198c2ef-0000-7000-8000-000000000002", "Hangs", ExerciseForm.HOLD.code, edge = 15.0, work = 7.0, rest = 3.0)
        val otherProtocol = exercise("0198c2ef-0000-7000-8000-000000000003", "Hangs", ExerciseForm.HOLD.code, edge = 20.0, work = 10.0, rest = 5.0)
        val otherForm = exercise("0198c2ef-0000-7000-8000-000000000004", "Hangs", ExerciseForm.DURATION.code, edge = 20.0, work = 7.0, rest = 3.0)

        val merge = mergeExercises(listOf(otherEdge, otherProtocol, otherForm), stored)

        assertEquals(3, merge.toInsert.size)
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
