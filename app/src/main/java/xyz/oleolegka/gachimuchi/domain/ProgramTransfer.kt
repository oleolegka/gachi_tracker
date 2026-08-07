package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Programs as a file: writing them out, reading them back, and refusing everything else.
 *
 * A program is the one thing in this app that is neither a fact nor a preference but
 * WORK — a protocol typed in by hand, block by block, and worth as much as the training
 * it schedules. Facts live in the journal and will one day sync; preferences are cheap to
 * set again. A program is neither, so it gets the plain-file treatment: export it, put it
 * in a backup, send it to someone, read it back on the next phone.
 *
 * ── The file is not the database ────────────────────────────────────────────────
 * [PortableProgram] deliberately does NOT reuse [WorkoutProgram]: that type carries a row
 * id, which is meaningless outside the device that assigned it and actively harmful in a
 * shared file (an id in the file would invite an import that overwrites program 3 on
 * whatever phone reads it). Groups and blocks ARE reused as they are, because they are
 * pure data with no identity of their own.
 *
 * ── The version field, and what it is for ───────────────────────────────────────
 * [PROGRAM_FILE_VERSION] describes the SHAPE of the file, not the app. It exists so that a
 * future change of shape has somewhere to be recognised: a file from a newer version is
 * refused with a sentence that says so, instead of being half-read into a program with
 * silently missing steps. Reading is otherwise lenient in one direction only — unknown
 * keys are ignored, so a v1 build can read a v1 file written by a later build that added
 * optional fields, which is what `ignoreUnknownKeys` buys.
 *
 * The format is described for humans in `docs/program-file-format.md`.
 *
 * ── Reading is validation, not trust ────────────────────────────────────────────
 * Everything that arrives from a file is someone else's data: a truncated download, a text
 * editor's idea of JSON, another app's export that happens to have a `programs` key. So
 * [readProgramFile] returns a [ProgramImport] and never throws, and it checks the numbers
 * as well as the shape — a block of 0 seconds or a repeat count of 100000 parses perfectly
 * well and would produce a program that cannot be run. The reason string is written to be
 * shown to the user as it is.
 */

/** Marks a file as this app's program export. Any other value is somebody else's file. */
const val PROGRAM_FILE_FORMAT = "gachimuchi.programs"

/** The shape of the file. Bumped only when the shape changes; see the note above. */
const val PROGRAM_FILE_VERSION = 1

/** The MIME type an export is written and read as. */
const val PROGRAM_FILE_MIME = "application/json"

/** Ceiling on a repeat count in an imported file — above this it is a typo, not a program. */
const val MAX_IMPORT_REPEATS = 999

/** The envelope: what the file is, followed by the programs it carries. */
@Serializable
data class ProgramFile(
    @SerialName("format") val format: String,
    @SerialName("version") val version: Int,
    @SerialName("programs") val programs: List<PortableProgram>,
    /** ISO date the file was written, for the reader's benefit only. Never parsed back. */
    @SerialName("exported_at") val exportedAt: String = "",
)

/**
 * A program as it travels: everything [WorkoutProgram] has except the two things that mean
 * nothing on another phone.
 *
 * The row id is one (see the note above). The link to a catalog exercise is the other: it
 * is a local row id, and carrying it to a device where that id is a different exercise -
 * or nothing at all - would attach somebody's hangs to somebody's squats. An imported
 * program arrives unlinked and asks once, which is the same thing a program typed in by
 * hand does.
 *
 * [category] DOES travel, because it is text the user wrote and it means the same thing
 * everywhere. It is optional on the way in, so a file written before categories existed
 * still reads.
 */
@Serializable
data class PortableProgram(
    @SerialName("name") val name: String,
    @SerialName("groups") val groups: List<ProgramGroup>,
    @SerialName("prepare_sec") val prepareSec: Int = PREPARE_DEFAULT_SEC,
    @SerialName("category") val category: String = "",
)

/**
 * Indented, with every field spelled out even when it equals its default: the file is
 * meant to be readable and editable in a text editor, which is half of why programs are
 * exported at all. Unknown keys are ignored on the way in — see the note on versioning.
 */
@OptIn(ExperimentalSerializationApi::class) // prettyPrintIndent, stable in behaviour if not in name
val programFileJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun WorkoutProgram.toPortable(): PortableProgram =
    PortableProgram(name = name, groups = groups, prepareSec = prepareSec, category = category)

/** Back to a program with NO id: an imported program is always a new row, never an update. */
fun PortableProgram.toProgram(): WorkoutProgram = WorkoutProgram(
    id = 0,
    name = name.trim(),
    groups = groups,
    prepareSec = prepareSec,
    category = category.trim(),
)

/** The text of an export file. [exportedAt] is decoration; leave it empty if unknown. */
fun writeProgramFile(programs: List<WorkoutProgram>, exportedAt: String = ""): String =
    programFileJson.encodeToString(
        ProgramFile(
            format = PROGRAM_FILE_FORMAT,
            version = PROGRAM_FILE_VERSION,
            programs = programs.map { it.toPortable() },
            exportedAt = exportedAt,
        )
    )

/** What came out of a file: programs to add, or a sentence explaining why not. */
sealed interface ProgramImport {
    data class Loaded(val programs: List<WorkoutProgram>) : ProgramImport

    /** [reason] is user-facing text, already a whole sentence. */
    data class Rejected(val reason: String) : ProgramImport
}

/**
 * Reads an export file. Never throws: every failure comes back as [ProgramImport.Rejected]
 * with a reason worth showing.
 *
 * A file is taken whole or not at all. Importing the three good programs out of five and
 * saying nothing about the other two would leave the user believing they have a copy of
 * something they do not — worse than refusing the file and letting them look at it.
 */
fun readProgramFile(text: String): ProgramImport {
    if (text.isBlank()) return ProgramImport.Rejected("The file is empty.")

    val file = runCatching { programFileJson.decodeFromString<ProgramFile>(text) }.getOrNull()
        ?: return ProgramImport.Rejected(
            "This file could not be read as a program export. Programs are exported as " +
                "JSON with a \"format\" and a \"version\" field; this file has neither, or " +
                "it is damaged."
        )

    if (file.format != PROGRAM_FILE_FORMAT) {
        return ProgramImport.Rejected(
            "This file was written by something else (its format is \"${file.format}\", " +
                "and programs are \"$PROGRAM_FILE_FORMAT\")."
        )
    }
    if (file.version < 1) {
        return ProgramImport.Rejected("The file declares format version ${file.version}, which is not a version.")
    }
    if (file.version > PROGRAM_FILE_VERSION) {
        return ProgramImport.Rejected(
            "The file was written by a newer version of the app (format version " +
                "${file.version}; this build reads up to $PROGRAM_FILE_VERSION). Update the " +
                "app and try again."
        )
    }
    if (file.programs.isEmpty()) return ProgramImport.Rejected("The file carries no programs.")

    for (program in file.programs) {
        programProblem(program)?.let { return ProgramImport.Rejected(it) }
    }
    return ProgramImport.Loaded(file.programs.map { it.toProgram() })
}

/**
 * What is wrong with one program in a file, or null when nothing is.
 *
 * The bounds are the ones the editor itself enforces, applied again here because a file
 * did not come from the editor. A number outside them is not "unusual", it is a program
 * that cannot be run: a work step of zero seconds expands to nothing at all, and a repeat
 * count in the thousands expands into a list [flatten] has to truncate.
 *
 * Not private, because the journal backup carries programs too (domain/JournalTransfer.kt) and
 * they arrive from a file for exactly the same reasons. One validator or two is the difference
 * between one definition of "a program that can be run" and two that drift.
 */
internal fun programProblem(program: PortableProgram): String? {
    val name = program.name.trim()
    if (name.isEmpty()) return "A program in the file has no name."
    if (program.prepareSec !in 0..MAX_STEP_SEC) {
        return "\"$name\": a lead-in of ${program.prepareSec} s is out of range."
    }
    if (program.groups.isEmpty()) return "\"$name\" has no groups in it."

    for (group in program.groups) {
        if (group.blocks.isEmpty()) return "\"$name\" has a group with nothing in it."
        if (group.repeats !in 1..MAX_IMPORT_REPEATS) {
            return "\"$name\": a group repeated ${group.repeats} times is out of range."
        }
        if (group.restBetweenRepeatsSec !in 0..MAX_STEP_SEC) {
            return "\"$name\": a rest of ${group.restBetweenRepeatsSec} s between sets is out of range."
        }
        if (group.restAfterSec !in 0..MAX_STEP_SEC) {
            return "\"$name\": a rest of ${group.restAfterSec} s after a group is out of range."
        }
        for (block in group.blocks) {
            if (block.workSec !in MIN_STEP_SEC..MAX_STEP_SEC) {
                return "\"$name\": an effort of ${block.workSec} s is out of range."
            }
            if (block.restSec !in 0..MAX_STEP_SEC) {
                return "\"$name\": a rest of ${block.restSec} s is out of range."
            }
            if (block.repeats !in 1..MAX_IMPORT_REPEATS) {
                return "\"$name\": an effort repeated ${block.repeats} times is out of range."
            }
        }
    }

    /*
     * ── And then the numbers together ───────────────────────────────────────────
     * Everything above judges one value at a time, and a program can be built entirely out
     * of reasonable values and still be impossible: a group repeated 40 times around 60
     * efforts is 2400 steps, and every number in it passes. [flatten] stops expanding at
     * [MAX_PROGRAM_STEPS] and says nothing about having done so — so the file imported, the
     * user was told "Imported 1 program", and what landed was a protocol quietly cut off
     * part way through. A truncated program is the exact failure this validator exists to
     * prevent; arriving at it by multiplication rather than by one bad field made no
     * difference to the person whose hangboard session stopped early.
     *
     * Two steps, because the cheap one guards the exact one. The effort count is arithmetic
     * and cannot blow up; only once it is known to be small is the program expanded for
     * real, which is the only way to count what [flatten] will actually emit (zero-length
     * steps dropped, adjacent rests merged, trailing rest removed) rather than an estimate.
     */
    val efforts = program.groups.sumOf { group ->
        group.repeats.toLong() * group.blocks.sumOf { it.repeats.toLong() }
    }
    if (efforts > MAX_PROGRAM_STEPS) {
        return "\"$name\" expands to $efforts efforts, more than the $MAX_PROGRAM_STEPS steps a " +
            "program can hold. The repeat counts multiply together - check them as a whole."
    }
    val steps = program.toProgram().flatten().size
    if (steps >= MAX_PROGRAM_STEPS) {
        return "\"$name\" expands to $steps steps, which is the most a program can hold. It would " +
            "be cut short rather than run to the end - shorten it and export it again."
    }
    return null
}

/**
 * A name that is free, given the ones already taken.
 *
 * An import NEVER replaces a program of the same name. The one on the phone may have been
 * edited since it was exported — the whole reason to keep programs is that they are
 * hand-tuned — and overwriting it with an older copy is the one outcome that cannot be
 * undone. So the arriving copy is marked instead, and both are there to compare.
 */
fun uniqueProgramName(name: String, taken: Collection<String>): String {
    val used = taken.map { it.trim().lowercase() }.toSet()
    val base = name.trim().ifEmpty { "Program" }
    if (base.lowercase() !in used) return base
    val first = "$base (imported)"
    if (first.lowercase() !in used) return first
    for (n in 2..MAX_IMPORT_REPEATS) {
        val candidate = "$base (imported $n)"
        if (candidate.lowercase() !in used) return candidate
    }
    return "$base (imported ${System.currentTimeMillis()})"
}

/**
 * Renames a batch so that nothing collides with what is stored AND nothing collides
 * within the batch — importing the same file twice in a row must not produce two programs
 * called the same thing.
 */
fun withUniqueNames(
    programs: List<WorkoutProgram>,
    existingNames: Collection<String>,
): List<WorkoutProgram> {
    val taken = existingNames.toMutableList()
    return programs.map { program ->
        val name = uniqueProgramName(program.name, taken)
        taken += name
        program.copy(name = name)
    }
}
