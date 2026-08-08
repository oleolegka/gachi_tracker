package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The whole journal as a file: writing it out, reading it back, and merging it into a
 * database that may already hold some of it.
 *
 * ── Why this exists at all ──────────────────────────────────────────────────────
 * The training journal lives in exactly one place, a SQLite file in this app's private
 * storage on one phone. There is no cloud copy, no Google backup on the device this is built
 * for, and `adb backup` has not reached app data since the target SDK moved on. Losing the
 * phone loses the history, and the history is the only thing in here that cannot be typed in
 * again. Programs already had a file of their own (domain/ProgramTransfer.kt); this is the
 * same idea applied to the part that actually matters.
 *
 * ── The payload travels OPAQUE, and that is the central decision ────────────────
 * An event is a `type` and a JSON payload, and this file writes the payload THROUGH,
 * unparsed and unvalidated, exactly as the journal holds it. Nothing here knows what a
 * strength set contains. That is what makes the format survive the app: forms grow fields
 * (a warm-up flag, which hand, the body weight at the time) and every one of them would
 * otherwise be a change to the exporter, a change to the importer, and a file version bump
 * — and a field somebody forgot to add would be silently dropped out of the one copy of the
 * history that exists. An opaque payload cannot lose a field it has never heard of.
 *
 * The cost is stated rather than hidden: this file cannot validate a payload, so a corrupt
 * payload is exported and restored corrupt. That is the right way round for a backup, whose
 * job is to reproduce what was there and not to improve on it.
 *
 * ── Everything refers to everything else by uid ─────────────────────────────────
 * Row numbers are absent from this file by design (see [newUid]): they count how many rows
 * one phone has written and mean nothing anywhere else. Every link the app cares about is
 * already said in uids, so the events, the catalog, the plan and the programs all cross-refer
 * by uid here and can be merged into any database at all.
 *
 * ONE HOLE IS LEFT, and it is inside the payloads this file refuses to look into: a
 * `set_cancel` written before schema version 9 names the set it reverses by ROW NUMBER only.
 * Restored into a fresh database in journal order the numbers land back where they were (the
 * journal is append-only, so it has no gaps), but merged into a database that already holds
 * training, such a reversal can name an unrelated row. It is spelled out in
 * `docs/journal-file-format.md`; every reversal this app has written since version 9 carries
 * the uid and is unaffected.
 *
 * ── Read the file whole, or refuse it whole ─────────────────────────────────────
 * Same rule as the program file, for a stronger reason: a half-restored journal is a history
 * with holes in it that nobody can spot, on a device where there is nothing to compare it
 * against. [readJournalFile] never throws and returns a sentence worth showing.
 */

/** Marks a file as this app's journal export. Any other value is somebody else's file. */
const val JOURNAL_FILE_FORMAT = "gachimuchi.journal"

/** The shape of the file. Bumped only when the shape changes; see [PROGRAM_FILE_VERSION]. */
const val JOURNAL_FILE_VERSION = 1

/** The MIME type an export is written and read as. */
const val JOURNAL_FILE_MIME = "application/json"

/**
 * The envelope. Every section is optional on the way in so that a file carrying only a
 * journal, or only a catalog, still reads — a backup of an app that has no plan in it yet
 * writes an empty list, and refusing it for that would be absurd.
 */
@Serializable
data class JournalFile(
    @SerialName("format") val format: String,
    @SerialName("version") val version: Int,
    /** ISO date the file was written, for the reader's benefit only. Never parsed back. */
    @SerialName("exported_at") val exportedAt: String = "",
    /**
     * Which installation wrote the file (data/DeviceStore.kt). Decoration and provenance: it
     * tells two files of the same journal apart. Never restored — a restored copy is a new
     * installation and says so with its own id.
     */
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("events") val events: List<PortableEvent> = emptyList(),
    @SerialName("exercises") val exercises: List<PortableExercise> = emptyList(),
    @SerialName("slots") val slots: List<PortableSlot> = emptyList(),
    @SerialName("programs") val programs: List<PortableProgramRow> = emptyList(),
    @SerialName("settings") val settings: PortableSettings? = null,
)

/**
 * One journal row, with the payload left alone.
 *
 * [payload] is a [JsonElement] rather than a String so that the file stays readable: a
 * backup that has to be opened in a text editor at the worst possible moment should not be
 * a wall of escaped quotes. It is still opaque — nothing here decodes it into a form.
 *
 * A payload that is not JSON at all (a corrupt row; this app cannot write one, the bot's
 * journal one day might) is carried as a JSON STRING and restored verbatim, so a backup
 * never quietly drops the one damaged row it was taken to preserve.
 *
 * The local row number is deliberately absent, and so is `space_id`: one is meaningless off
 * the phone that assigned it, the other has exactly one value ([LOCAL_SPACE_ID] on the data
 * side) until this app grows profiles.
 */
@Serializable
data class PortableEvent(
    @SerialName("uid") val uid: String,
    @SerialName("ts") val ts: String,
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: JsonElement,
    /** The workout this row was recorded during, by identity. Null for "outside any workout". */
    @SerialName("workout_uid") val workoutUid: String? = null,
    /** Mirrors the local author column; there is one author until this app grows profiles. */
    @SerialName("author_id") val authorId: Long = 1,
)

/**
 * A catalog exercise. Everything about it travels — including [defaultRestSec] and
 * [ledByProtocol], which are preferences rather than identity but are answers the user gave
 * and would otherwise have to give again on a fresh phone.
 *
 * ── This list is spelled out, and that is a standing obligation ─────────────────
 * The catalog, the plan and the programs are reference data with columns, not payloads, so
 * unlike an event they cannot be carried through blind. A COLUMN ADDED TO `exercises` AND NOT
 * ADDED HERE IS A COLUMN THAT DOES NOT SURVIVE A RESTORE, silently — the file loads, the
 * exercise comes back, and one thing about it is quietly the default. Every field is optional
 * with the same default the entity has, so that a file written before a column existed still
 * reads and lands on the same value a fresh row would.
 */
@Serializable
data class PortableExercise(
    @SerialName("uid") val uid: String,
    @SerialName("name") val name: String,
    /** Form code, the values of [ExerciseForm]. */
    @SerialName("form") val form: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edge_mm") val edgeMm: Double? = null,
    @SerialName("protocol_work_sec") val protocolWorkSec: Double? = null,
    @SerialName("protocol_rest_sec") val protocolRestSec: Double? = null,
    @SerialName("default_rest_sec") val defaultRestSec: Int? = null,
    @SerialName("led_by_protocol") val ledByProtocol: Boolean? = null,
    /** Whether a set of this exercise is done one side at a time (schema version 13). */
    @SerialName("one_sided") val oneSided: Boolean = false,
    /** How much of the body weight this exercise actually lifts (schema version 14). */
    @SerialName("bodyweight_share") val bodyweightShare: Double? = null,
    /**
     * Whether the exercise is kept out of the pickers (schema version 15).
     *
     * It travels for the same reason the rest of the preferences do: hiding half a catalog is
     * work, and a restore that handed back every abandoned exercise would be handing back the
     * mess the hiding was for. It is not part of the identity, so a hidden row and a shown one
     * still merge into each other.
     */
    @SerialName("hidden") val hidden: Boolean = false,
)

/** A plan slot with the session it is meant to consist of. */
@Serializable
data class PortableSlot(
    @SerialName("uid") val uid: String,
    @SerialName("name") val name: String,
    @SerialName("at_time") val atTime: String? = null,
    @SerialName("repeat_rule") val repeatRule: String,
    @SerialName("anchor_date") val anchorDate: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("exercises") val exercises: List<PortablePlannedExercise> = emptyList(),
)

/**
 * One exercise planned into a slot.
 *
 * [exerciseUid] is the one link in the whole app that is stored as a bare row number
 * (`slot_exercises.exercise_id`) and has to be translated on the way out. It is nullable
 * because a plan can point at a catalog row that is no longer there; such a line is
 * exported as unresolved and counted, not quietly dropped.
 */
@Serializable
data class PortablePlannedExercise(
    @SerialName("uid") val uid: String,
    @SerialName("exercise_uid") val exerciseUid: String? = null,
    @SerialName("rest_sec") val restSec: Int? = null,
)

/**
 * An interval program, as the backup carries it — which is NOT [PortableProgram] from the
 * program file.
 *
 * Two differences, both deliberate. It has a uid, because a backup is restored repeatedly
 * into the same database and must not grow a fourth copy of "Tabata 20:10" each time; the
 * program file has none, because sending someone a protocol is always a new program on their
 * phone. And it keeps the link to a catalog exercise, said as a uid — the program file drops
 * that link because a row number means nothing on another phone, which is exactly the problem
 * a uid does not have.
 */
@Serializable
data class PortableProgramRow(
    @SerialName("uid") val uid: String,
    @SerialName("name") val name: String,
    @SerialName("groups") val groups: List<ProgramGroup>,
    @SerialName("prepare_sec") val prepareSec: Int = PREPARE_DEFAULT_SEC,
    @SerialName("category") val category: String = "",
    /** The catalog exercise this program trains, by identity, when it is exactly one. */
    @SerialName("exercise_uid") val exerciseUid: String? = null,
    /** Order on the timer tab. Kept so a restored list reads the way it was arranged. */
    @SerialName("position") val position: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
)

/**
 * The preferences, which are cheap to set again and are carried anyway: on the day a phone
 * is replaced, "which of these nine switches did I have on" is exactly the question nobody
 * can answer.
 *
 * Held as its own type rather than reusing [TimerSettings] because a file format and a
 * runtime value have different obligations — every field here is optional with the same
 * default the app has, so a file written before a setting existed still reads.
 */
@Serializable
data class PortableSettings(
    @SerialName("default_rest_sec") val defaultRestSec: Int = TimerSettings().defaultRestSec,
    @SerialName("auto_start_rest") val autoStartRest: Boolean = TimerSettings().autoStartRest,
    @SerialName("adapt_rest_to_exercise") val adaptRestToExercise: Boolean = TimerSettings().adaptRestToExercise,
    @SerialName("prepare_sec") val prepareSec: Int = TimerSettings().prepareSec,
    @SerialName("sound") val sound: Boolean = TimerSettings().sound,
    @SerialName("vibrate") val vibrate: Boolean = TimerSettings().vibrate,
    @SerialName("countdown_ticks") val countdownTicks: Boolean = TimerSettings().countdownTicks,
    @SerialName("speak") val speak: Boolean = TimerSettings().speak,
    @SerialName("default_sets") val defaultSets: Int = TimerSettings().defaultSets,
    /** Whether the timer has been switched on at all — it gates the notification permission. */
    @SerialName("timer_enabled") val timerEnabled: Boolean = false,
    /** Celebration mode as its stored code; see [CelebrationMode]. */
    @SerialName("celebration_mode") val celebrationMode: Int = CelebrationMode.RECORDS_ONLY.code,
)

fun PortableSettings.toTimerSettings(): TimerSettings = TimerSettings(
    defaultRestSec = defaultRestSec,
    autoStartRest = autoStartRest,
    adaptRestToExercise = adaptRestToExercise,
    prepareSec = prepareSec,
    sound = sound,
    vibrate = vibrate,
    countdownTicks = countdownTicks,
    speak = speak,
    defaultSets = defaultSets,
)

fun portableSettings(
    timer: TimerSettings,
    timerEnabled: Boolean,
    celebration: CelebrationMode,
): PortableSettings = PortableSettings(
    defaultRestSec = timer.defaultRestSec,
    autoStartRest = timer.autoStartRest,
    adaptRestToExercise = timer.adaptRestToExercise,
    prepareSec = timer.prepareSec,
    sound = timer.sound,
    vibrate = timer.vibrate,
    countdownTicks = timer.countdownTicks,
    speak = timer.speak,
    defaultSets = timer.defaultSets,
    timerEnabled = timerEnabled,
    celebrationMode = celebration.code,
)

/**
 * Indented, with every field spelled out: a backup is read by a human exactly once, in the
 * situation where everything else has already gone wrong, and it should be legible then.
 * Unknown keys are ignored on the way in, so a v1 file written by a later build that added an
 * optional field still loads.
 */
@OptIn(ExperimentalSerializationApi::class) // prettyPrintIndent, stable in behaviour if not in name
val journalFileJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * A stored payload as it goes into the file, and back.
 *
 * The pair is exact in both directions for anything this app writes: [payloadJson] is what
 * wrote the string in the first place, kotlinx keeps object keys in the order they were
 * parsed, and numbers keep the literal they arrived as. So export -> import -> export
 * reproduces the file byte for byte, which is the property the round-trip test pins.
 */
fun payloadToElement(payload: String): JsonElement =
    runCatching { journalFileJson.parseToJsonElement(payload) }.getOrElse { JsonPrimitive(payload) }

fun elementToPayload(element: JsonElement): String =
    if (element is JsonPrimitive && element.isString) element.content
    else payloadJson.encodeToString(JsonElement.serializer(), element)

/** The text of a backup file. [exportedAt] and [deviceId] are decoration. */
fun writeJournalFile(
    events: List<PortableEvent>,
    exercises: List<PortableExercise>,
    slots: List<PortableSlot>,
    programs: List<PortableProgramRow>,
    settings: PortableSettings?,
    exportedAt: String = "",
    deviceId: String = "",
): String = journalFileJson.encodeToString(
    JournalFile(
        format = JOURNAL_FILE_FORMAT,
        version = JOURNAL_FILE_VERSION,
        exportedAt = exportedAt,
        deviceId = deviceId,
        events = events,
        exercises = exercises,
        slots = slots,
        programs = programs,
        settings = settings,
    )
)

/** What came out of a file: a journal to merge, or a sentence explaining why not. */
sealed interface JournalImport {
    data class Loaded(val file: JournalFile) : JournalImport

    /** [reason] is user-facing text, already a whole sentence. */
    data class Rejected(val reason: String) : JournalImport
}

/**
 * Reads a backup file. Never throws: every failure comes back as [JournalImport.Rejected].
 *
 * What is checked here is the SHAPE and the cross-references — that rows have identities,
 * that the identities are unique, that a form code and a repeat rule are ones this build can
 * render, that a program can actually be run. What is deliberately not checked is the inside
 * of an event payload; see the note at the top of this file.
 */
fun readJournalFile(text: String): JournalImport {
    if (text.isBlank()) return JournalImport.Rejected("The file is empty.")

    val file = runCatching { journalFileJson.decodeFromString<JournalFile>(text) }.getOrNull()
        ?: return JournalImport.Rejected(
            "This file could not be read as a journal backup. A backup is JSON with a " +
                "\"format\" and a \"version\" field; this file has neither, or it is damaged."
        )

    if (file.format != JOURNAL_FILE_FORMAT) {
        return JournalImport.Rejected(
            "This file was written by something else (its format is \"${file.format}\", and a " +
                "journal backup is \"$JOURNAL_FILE_FORMAT\")."
        )
    }
    if (file.version < 1) {
        return JournalImport.Rejected("The file declares format version ${file.version}, which is not a version.")
    }
    if (file.version > JOURNAL_FILE_VERSION) {
        return JournalImport.Rejected(
            "The file was written by a newer version of the app (format version ${file.version}; " +
                "this build reads up to $JOURNAL_FILE_VERSION). Restoring it here could drop " +
                "whatever that version added. Update the app and try again."
        )
    }
    problemWithContents(file)?.let { return JournalImport.Rejected(it) }
    return JournalImport.Loaded(file)
}

/**
 * What is wrong with the contents of a backup, or null when nothing is.
 *
 * Uniqueness of uids is checked first and hardest, because it is what the whole merge stands
 * on: two rows sharing an identity would make "have I got this one already" unanswerable, and
 * the second import of such a file would keep adding rows forever.
 */
private fun problemWithContents(file: JournalFile): String? {
    duplicateOrBlankUid(file.events.map { it.uid }, "event")?.let { return it }
    duplicateOrBlankUid(file.exercises.map { it.uid }, "exercise")?.let { return it }
    duplicateOrBlankUid(file.slots.map { it.uid }, "plan slot")?.let { return it }
    duplicateOrBlankUid(file.slots.flatMap { slot -> slot.exercises.map { it.uid } }, "planned line")
        ?.let { return it }
    duplicateOrBlankUid(file.programs.map { it.uid }, "program")?.let { return it }

    for (event in file.events) {
        if (event.type.isBlank()) return "An event in the file (${event.uid}) has no type."
        if (event.ts.isBlank()) return "An event in the file (${event.uid}) has no timestamp."
    }

    for (exercise in file.exercises) {
        if (exercise.name.isBlank()) return "An exercise in the file (${exercise.uid}) has no name."
        // an unknown code is a form this build cannot draw an entry card for; restoring it
        // would put a row in the catalog that every screen has to guess about
        runCatching { ExerciseForm.fromCode(exercise.form) }.getOrElse {
            return "\"${exercise.name}\" has form code ${exercise.form}, which this build does " +
                "not know. The file was probably written by a newer version of the app."
        }
    }

    for (slot in file.slots) {
        if (slot.name.isBlank()) return "A plan slot in the file (${slot.uid}) has no name."
        if (slot.repeatRule !in KNOWN_REPEAT_RULES) {
            return "The plan \"${slot.name}\" repeats by the rule \"${slot.repeatRule}\", which " +
                "this build does not know."
        }
        if (slot.anchorDate.isBlank()) return "The plan \"${slot.name}\" has no starting date."
    }

    for (program in file.programs) {
        // the same bounds the program file applies, and for the same reason: a file did not
        // come from the editor, and a program that cannot be run is worse than no program
        programProblem(
            PortableProgram(
                name = program.name,
                groups = program.groups,
                prepareSec = program.prepareSec,
                category = program.category,
            )
        )?.let { return it }
    }
    return null
}

private val KNOWN_REPEAT_RULES = setOf(REPEAT_NONE, REPEAT_DAILY, REPEAT_WEEKLY)

private fun duplicateOrBlankUid(uids: List<String>, what: String): String? {
    val seen = HashSet<String>(uids.size)
    for (uid in uids) {
        if (uid.isBlank()) return "A $what in the file has no uid, so it cannot be told apart " +
            "from any other. The file was not written by this app, or it has been edited."
        if (!seen.add(uid)) {
            return "The file carries two rows with the same $what uid ($uid). It cannot be " +
                "merged without deciding which of them is the real one."
        }
    }
    return null
}

// --- merging a file into what is already stored ----------------------------------------

/**
 * What an import did, in numbers.
 *
 * Every section counts both halves — what was written and what was already here — because
 * "0 added" reads as a failure until it is next to "412 already here", which is what a second
 * import of the same file is supposed to look like. [notes] carries everything that did not
 * fit a counter and must not be swallowed: an exercise that turned out to be one this phone
 * already had under another key, a planned line naming an exercise the file did not carry.
 */
data class ImportReport(
    val eventsAdded: Int = 0,
    val eventsAlreadyHere: Int = 0,
    val exercisesAdded: Int = 0,
    val exercisesAlreadyHere: Int = 0,
    val exercisesMergedByIdentity: Int = 0,
    val slotsAdded: Int = 0,
    val slotsAlreadyHere: Int = 0,
    val plannedLinesSkipped: Int = 0,
    val programsAdded: Int = 0,
    val programsAlreadyHere: Int = 0,
    val settingsApplied: Boolean = false,
    val notes: List<String> = emptyList(),
) {
    /** Whether the import wrote anything at all. A second import of one file writes nothing. */
    val addedAnything: Boolean
        get() = eventsAdded > 0 || exercisesAdded > 0 || slotsAdded > 0 || programsAdded > 0

    /** The report as the sentences shown to the user, one line each. */
    fun lines(): List<String> = buildList {
        add("Journal: $eventsAdded added, $eventsAlreadyHere already here.")
        add("Exercises: $exercisesAdded added, $exercisesAlreadyHere already here.")
        add("Plan: $slotsAdded added, $slotsAlreadyHere already here.")
        add("Programs: $programsAdded added, $programsAlreadyHere already here.")
        if (settingsApplied) add("Settings taken from the file.")
        addAll(notes)
    }
}

/**
 * What makes two catalog rows the same exercise when they do not share a uid.
 *
 * The rule itself is [ExerciseIdentity] in domain/Catalog.kt and is not restated here: the
 * merge, the row that gets created when an exercise is first logged, and the UNIQUE index in
 * the database all have to mean exactly the same thing by "the same exercise", and a second
 * definition living in the file format would be a second thing to keep in step.
 */
fun PortableExercise.identity(): ExerciseIdentity =
    exerciseIdentity(name, form, edgeMm, protocolWorkSec, protocolRestSec)

/**
 * The backup's view of a catalog row — see [CatalogRow] for why this is one of four narrow
 * views built off the one place that reads the entity, rather than a fifth place that reads it
 * again. [id] is dropped: it is local plumbing (domain/Catalog.kt), and the file refers to
 * everything by [CatalogRow.uid] instead.
 */
fun CatalogRow.toPortable(): PortableExercise = PortableExercise(
    uid = uid,
    name = name,
    form = form,
    createdAt = createdAt,
    edgeMm = edgeMm,
    protocolWorkSec = protocolWorkSec,
    protocolRestSec = protocolRestSec,
    defaultRestSec = defaultRestSec,
    ledByProtocol = ledByProtocol,
    oneSided = oneSided,
    bodyweightShare = bodyweightShare,
    hidden = hidden,
)

/**
 * How the catalog in a file lines up with the catalog on this phone.
 *
 * [aliases] maps a uid in the FILE to the uid already stored that it turned out to mean. It is
 * what the plan and the programs are translated through, and it is also the thing this merge
 * cannot do anything about beyond reporting: see [mergeExercises].
 */
data class ExerciseMerge(
    val toInsert: List<PortableExercise>,
    val aliases: Map<String, String>,
    val alreadyHere: Int,
) {
    /** The uid an incoming row should be filed under: its own, unless it is an alias. */
    fun resolve(uid: String): String = aliases[uid] ?: uid
}

/**
 * Lines a file's catalog up against the stored one: by uid first, then by identity.
 *
 * ── When the identity matches but the uid does not ──────────────────────────────
 * The row already on the phone keeps its uid and the file's row is filed under it. That
 * direction is chosen for the same reason the program import never overwrites a program: what
 * is on the phone is the copy the user has been using, and a file is by definition older than
 * or equal to it.
 *
 * IT IS NOT FREE, and the cost is real enough to be reported rather than buried. The sets
 * arriving in the same file name the exercise by the FILE's uid, inside payloads this format
 * refuses to rewrite, so they land in the journal pointing at a key the catalog no longer
 * holds. They are in the history and in the daily feed; they are absent from that exercise's
 * own records and charts. Unifying them properly needs the catalog to be able to carry more
 * than one key for one exercise, which is a schema change and a separate piece of work.
 *
 * This only happens when two devices invented the same exercise independently. A restore onto
 * an empty phone, and a re-import of a file this phone wrote, both go entirely through the uid
 * pass above and never reach here.
 */
fun mergeExercises(
    incoming: List<PortableExercise>,
    stored: List<PortableExercise>,
): ExerciseMerge {
    val storedUids = stored.mapTo(HashSet()) { it.uid }
    // first spelling wins, so a phone that already holds two rows of one identity keeps
    // pointing at the older of them rather than at whichever the map happened to see last
    val byIdentity = LinkedHashMap<ExerciseIdentity, String>()
    for (row in stored) byIdentity.putIfAbsent(row.identity(), row.uid)

    val toInsert = ArrayList<PortableExercise>()
    val aliases = LinkedHashMap<String, String>()
    var alreadyHere = 0
    for (row in incoming) {
        if (row.uid in storedUids) {
            alreadyHere++
            continue
        }
        val sameThing = byIdentity[row.identity()]
        if (sameThing != null) {
            aliases[row.uid] = sameThing
            continue
        }
        // registered as it is added, so two rows of one identity inside a single file collapse
        // into one insert instead of two catalog entries that then need merging again
        byIdentity[row.identity()] = row.uid
        toInsert += row
    }
    return ExerciseMerge(toInsert, aliases, alreadyHere)
}
