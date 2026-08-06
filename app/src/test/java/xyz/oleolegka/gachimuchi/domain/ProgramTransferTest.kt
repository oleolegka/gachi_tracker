package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Programs as files: what goes out, what is allowed back in, and what is refused.
 *
 * Two different jobs are tested here and they fail in different ways. The round trip
 * catches a program that comes back subtly different — a dropped repeat count is a
 * shorter workout that nothing on screen would flag. The refusals catch the opposite
 * failure: a file that should never have been read taking the app down or, worse, landing
 * as a program with steps of zero seconds that the timer flies through.
 *
 * The literal v1 fixture at the bottom is the actual contract. If a @SerialName is ever
 * renamed, every file exported before that rename stops loading, and this is the test that
 * says so instead of a user discovering it with their only copy.
 */
class ProgramTransferTest {

    private val repeaters = WorkoutProgram(
        id = 17, // a row id, which must NOT survive the trip
        name = "Hangboard repeaters 7:3",
        prepareSec = 15,
        groups = listOf(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock(name = "Hang", workSec = 7, restSec = 3, repeats = 6)),
                repeats = 4,
                restBetweenRepeatsSec = 180,
            )
        ),
    )

    private val circuit = WorkoutProgram(
        id = 4,
        name = "Circuit",
        prepareSec = 10,
        groups = listOf(
            ProgramGroup(
                name = "A",
                blocks = listOf(
                    ProgramBlock(name = "Pull", workSec = 40, restSec = 20, repeats = 3),
                    ProgramBlock(name = "Push", workSec = 40, restSec = 20, repeats = 3),
                ),
                repeats = 2,
                restBetweenRepeatsSec = 90,
                restAfterSec = 120,
            ),
            ProgramGroup(
                name = "B",
                blocks = listOf(ProgramBlock(name = "Core", workSec = 60, restSec = 30, repeats = 2)),
            ),
        ),
    )

    private fun loaded(text: String): List<WorkoutProgram> =
        (readProgramFile(text) as ProgramImport.Loaded).programs

    private fun rejection(text: String): String =
        (readProgramFile(text) as ProgramImport.Rejected).reason

    // --- the round trip ------------------------------------------------------------------

    @Test
    fun `a program survives the trip through a file unchanged, right down to the repeats`() {
        val back = loaded(writeProgramFile(listOf(repeaters))).single()

        assertEquals(repeaters.name, back.name)
        assertEquals(repeaters.prepareSec, back.prepareSec)
        assertEquals(repeaters.groups, back.groups)
        // the same expansion, which is the only thing that actually matters at the bar
        assertEquals(repeaters.flatten(), back.copy(id = repeaters.id).flatten())
        assertEquals(repeaters.totalSec(), back.totalSec())
    }

    @Test
    fun `several programs, groups and blocks keep their order`() {
        val back = loaded(writeProgramFile(listOf(repeaters, circuit)))

        assertEquals(listOf("Hangboard repeaters 7:3", "Circuit"), back.map { it.name })
        assertEquals(listOf("A", "B"), back[1].groups.map { it.name })
        assertEquals(listOf("Pull", "Push"), back[1].groups.first().blocks.map { it.name })
        assertEquals(circuit.totalSec(), back[1].totalSec())
    }

    @Test
    fun `the row id does not travel and an imported program is always a new row`() {
        val text = writeProgramFile(listOf(repeaters, circuit))

        assertFalse("the file must not carry database ids", text.contains("\"id\""))
        assertTrue(loaded(text).all { it.id == 0L })
    }

    @Test
    fun `the file says what it is and which shape it has`() {
        val text = writeProgramFile(listOf(repeaters), exportedAt = "2026-08-06")

        assertTrue(text.contains("\"format\": \"$PROGRAM_FILE_FORMAT\""))
        assertTrue(text.contains("\"version\": $PROGRAM_FILE_VERSION"))
        assertTrue(text.contains("\"exported_at\": \"2026-08-06\""))
        // written to be read and edited by hand, so: indented, and nothing left implicit
        assertTrue(text.contains("\n  "))
        assertTrue(text.contains("\"prepare_sec\": 15"))
        assertTrue(text.contains("\"rest_between_repeats_sec\": 180"))
    }

    // --- versioning ----------------------------------------------------------------------

    @Test
    fun `a file written by a newer version is refused with the reason, not half read`() {
        val text = writeProgramFile(listOf(repeaters)).replace(
            "\"version\": $PROGRAM_FILE_VERSION", "\"version\": ${PROGRAM_FILE_VERSION + 1}",
        )

        val reason = rejection(text)
        assertTrue(reason.contains("newer version"))
        assertTrue(reason.contains("${PROGRAM_FILE_VERSION + 1}"))
    }

    @Test
    fun `keys this build does not know are ignored rather than fatal`() {
        // what a later build would add: a field on the envelope and one on a block
        val text = writeProgramFile(listOf(repeaters))
            .replace("\"format\"", "\"written_by\": \"gachimuchi 0.9\",\n  \"format\"")
            .replace("\"work_sec\"", "\"cue\": \"three fingers\",\n            \"work_sec\"")

        val back = loaded(text).single()
        assertEquals(repeaters.groups, back.groups)
    }

    @Test
    fun `a version 1 file written today still loads exactly as it did`() {
        // NOT generated by the exporter on purpose: this is the format contract, spelled out
        val fixture = """
            {
              "format": "gachimuchi.programs",
              "version": 1,
              "exported_at": "2026-08-06",
              "programs": [
                {
                  "name": "Tabata 20:10",
                  "groups": [
                    {
                      "name": "Tabata",
                      "blocks": [
                        { "name": "Work", "work_sec": 20, "rest_sec": 10, "repeats": 8 }
                      ],
                      "repeats": 1,
                      "rest_between_repeats_sec": 0,
                      "rest_after_sec": 0
                    }
                  ],
                  "prepare_sec": 10
                }
              ]
            }
        """.trimIndent()

        val program = loaded(fixture).single()
        assertEquals("Tabata 20:10", program.name)
        assertEquals(10, program.prepareSec)
        assertEquals(8, program.workStepCount())
        assertEquals(10 + 160 + 70, program.totalSec())
    }

    // --- refusals ------------------------------------------------------------------------

    @Test
    fun `broken json is refused instead of crashing`() {
        assertTrue(rejection("{\"format\": \"gachimuchi.programs\", \"version\": 1,").isNotBlank())
        assertTrue(rejection("this is not json at all").isNotBlank())
        // a truncated download: valid json, half a program
        assertTrue(rejection(writeProgramFile(listOf(repeaters)).substring(0, 120)).isNotBlank())
    }

    @Test
    fun `an empty file is refused with a sentence of its own`() {
        assertTrue(rejection("").contains("empty"))
        assertTrue(rejection("   \n ").contains("empty"))
    }

    @Test
    fun `somebody else's json is refused by name`() {
        val other = """{"format": "someotherapp.workouts", "version": 1, "programs": []}"""
        assertTrue(rejection(other).contains("someotherapp.workouts"))

        // valid json with a programs key, but no format field at all
        val untagged = """{"programs": [{"name": "x", "groups": []}]}"""
        assertTrue(rejection(untagged).isNotBlank())
    }

    @Test
    fun `a file with no programs in it is refused rather than importing nothing`() {
        val empty = """{"format": "$PROGRAM_FILE_FORMAT", "version": $PROGRAM_FILE_VERSION, "programs": []}"""
        assertTrue(rejection(empty).contains("no programs"))
    }

    @Test
    fun `numbers that parse but cannot be run are refused, naming the program`() {
        fun file(program: String) =
            """{"format": "$PROGRAM_FILE_FORMAT", "version": $PROGRAM_FILE_VERSION, "programs": [$program]}"""

        val zeroWork = file(
            """{"name": "Broken", "groups": [{"name": "g", "blocks": [{"name": "b", "work_sec": 0}]}]}"""
        )
        assertTrue(rejection(zeroWork).contains("Broken"))

        val absurdRepeats = file(
            """{"name": "Typo", "groups": [{"name": "g", "blocks": [{"name": "b", "work_sec": 7, "repeats": 100000}]}]}"""
        )
        assertTrue(rejection(absurdRepeats).contains("Typo"))

        val emptyGroup = file("""{"name": "Hollow", "groups": [{"name": "g", "blocks": []}]}""")
        assertTrue(rejection(emptyGroup).contains("Hollow"))

        val noGroups = file("""{"name": "Nothing", "groups": []}""")
        assertTrue(rejection(noGroups).contains("Nothing"))

        val nameless = file("""{"name": "  ", "groups": [{"name": "g", "blocks": [{"name": "b", "work_sec": 7}]}]}""")
        assertTrue(rejection(nameless).contains("no name"))
    }

    @Test
    fun `one bad program refuses the whole file rather than importing the rest silently`() {
        val text = writeProgramFile(listOf(repeaters, circuit)).replace("\"work_sec\": 60", "\"work_sec\": 0")

        assertTrue(readProgramFile(text) is ProgramImport.Rejected)
    }

    // --- names ---------------------------------------------------------------------------

    @Test
    fun `a free name is left alone and a taken one is marked instead of overwriting`() {
        assertEquals("Tabata", uniqueProgramName("Tabata", listOf("Repeaters")))
        assertEquals("Tabata (imported)", uniqueProgramName("Tabata", listOf("Tabata")))
        assertEquals(
            "Tabata (imported 2)",
            uniqueProgramName("Tabata", listOf("Tabata", "Tabata (imported)")),
        )
        // case and stray spaces are the same name to a person, so they are here too
        assertEquals("Tabata (imported)", uniqueProgramName(" Tabata ", listOf("tabata")))
    }

    @Test
    fun `importing the same file twice produces two programs, not one overwritten`() {
        val incoming = loaded(writeProgramFile(listOf(repeaters, circuit)))

        val first = withUniqueNames(incoming, existingNames = emptyList())
        val second = withUniqueNames(incoming, existingNames = first.map { it.name })

        assertEquals(listOf("Hangboard repeaters 7:3", "Circuit"), first.map { it.name })
        assertEquals(listOf("Hangboard repeaters 7:3 (imported)", "Circuit (imported)"), second.map { it.name })
        // and the programs themselves are untouched by the renaming
        assertEquals(first.first().groups, second.first().groups)
    }

    @Test
    fun `two programs of the same name inside one file do not collapse into each other`() {
        val twins = listOf(repeaters, repeaters.copy(id = 0))

        val placed = withUniqueNames(twins, existingNames = listOf("Hangboard repeaters 7:3"))

        assertEquals(2, placed.map { it.name }.toSet().size)
        assertFalse(placed.any { it.name == "Hangboard repeaters 7:3" })
    }
}
