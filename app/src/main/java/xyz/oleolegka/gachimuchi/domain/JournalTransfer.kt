package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The whole journal as ONE file: writing it out, reading it back, and merging it into a
 * database that may already hold some of it.
 *
 * ── Why this exists at all ──────────────────────────────────────────────────────
 * The training journal lives in exactly one place, a SQLite file in this app's private
 * storage on one phone. There is no cloud copy, no Google backup on the device this is built
 * for, and `adb backup` has not reached app data since the target SDK moved on. Losing the
 * phone loses the history, and the history is the only thing in here that cannot be typed in
 * again.
 *
 * ── One file, not two ────────────────────────────────────────────────────────────
 * This used to be two exports: an opaque JSON that restored and a read-only CSV that did not.
 * It is one CSV now, a table, because that is what both of them were describing — a table has
 * rows that matter for restoring (the whole journal, live and dead) and rows that are nicer to
 * read (the current picture), and the fix for having two files is a FLAG, not a second file.
 * [CSV_HEADER]'s own KDoc is where the flag ([col current_version]) and the rule that goes with
 * it live.
 *
 * ── Every row carries its payload OPAQUE, and that is the central decision ──────
 * A journal event's `payload` travels THROUGH, unparsed and unvalidated, exactly as the
 * journal holds it — nothing here knows what a strength set contains. That is what makes the
 * format survive the app: a form growing a field is not a change to the exporter, the
 * importer, or the file's shape.
 *
 * The catalog, the plan and the programs are reference data, not payloads with a home
 * elsewhere, but they get the SAME treatment: a whole row is written as one JSON object into
 * its own `payload` cell rather than spread across columns — see the header KDoc for the
 * defect that spreading them used to cause, twice in one day, and why this is the fix.
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
 * training, such a reversal can name an unrelated row. Every reversal this app has written
 * since version 9 carries the uid and is unaffected.
 *
 * ── Read the file whole, or refuse it whole ─────────────────────────────────────
 * A half-restored journal is a history with holes in it that nobody can spot, on a device
 * where there is nothing to compare it against. [readJournalFile] never throws and returns a
 * sentence worth showing.
 */

/** The MIME type an export is written and read as. */
const val JOURNAL_FILE_MIME = "text/csv"

/**
 * The shape of the file, folded into the name of its first column rather than kept as a
 * separate field — see [CSV_HEADER]'s KDoc for why a version marker earns its own column
 * name instead of a line above the header a spreadsheet would show as extra data.
 *
 * ── v1 -> v2: `tz_offset_min` joins the structural columns ──────────────────────
 * A v1 file said an event's `written_at` as a local wall clock alone, so a journal exported
 * abroad and restored at home silently took on home's offset — see [PortableEvent.tzOffsetMin]
 * for the fix. The bump does not by itself make an older file unreadable: [STRUCTURAL_COLUMNS]
 * lists `tz_offset_min` as one a restore reads, but [REQUIRED_STRUCTURAL_COLUMNS] leaves it out
 * of what a file MUST carry, so a v1 file missing the column still loads, falling back to
 * exactly the v1 behaviour rather than being refused for having been written before the column
 * existed.
 */
private const val CSV_VERSION = 2
private const val KIND_COLUMN_PREFIX = "gachimuchi_journal_v"
private val KIND_COLUMN = "$KIND_COLUMN_PREFIX$CSV_VERSION"

private const val KIND_META = "meta"
private const val KIND_SETTINGS = "settings"
private const val KIND_EXERCISE = "exercise"
private const val KIND_SLOT = "slot"
private const val KIND_PROGRAM = "program"
private const val KIND_EVENT = "event"

/**
 * The columns a restore is ALLOWED to read, in the order they carry the most identity first.
 *
 * ── RESTORE READS ONLY THESE COLUMNS, AND THE PAYLOAD, AND NEVER A DERIVED ONE ──────
 * THIS IS THE RULE THE WHOLE FILE IS BUILT AROUND. [DERIVED_COLUMNS] exist so a person can
 * open this file and understand a row without decoding its `payload`; [readJournalFile] must
 * never look at one of them for anything that ends up in the database. That is what lets a
 * derived column be renamed, reordered or added to freely, in any later change, without a
 * restore silently changing behaviour — the exact opposite of what used to happen: a reference
 * table exported column by column, where a column added to the entity and forgotten in the
 * exporter came back as its default, silently, on every future backup. It happened twice in
 * one day. [PortableExerciseCoverageTest] guards the entity side of that; this rule is the
 * other half, and it is why the catalog, the plan and the programs are written as one JSON
 * object per row (see the file KDoc) rather than as columns at all — there is then nothing
 * here left to forget.
 *
 * The first column's NAME is the format marker and the version, together: a file whose first
 * column is not named `gachimuchi_journal_vN` was not written by this app, or was written by
 * a build old or new enough that this one should not guess at it. Cell VALUES in that column
 * are the row [kind] — `event`, `exercise`, `slot`, `program`, `settings` or `meta`.
 *
 * `uid` is read for an `event` row, which carries no uid inside its own payload. For every
 * other kind the payload already carries its own `uid` (it is one of [PortableExercise],
 * [PortableSlot], [PortableProgramRow] or [PortableSettings]'s own fields) and that is the one
 * actually used; the `uid` cell in those rows is written for a human filtering the file, not
 * read back by anything.
 *
 * `tz_offset_min` is read for an `event` row alongside `written_at`: minutes east of UTC at the
 * moment the row was written, the same fact [xyz.oleolegka.gachimuchi.data.db.EventEntity.tzOffsetMin]
 * holds — see [PortableEvent.tzOffsetMin] for why it has to be a column here rather than
 * something a restore reconstructs from the device. There is no separate UTC column: the
 * instant is `written_at` read against this offset, and storing it a second time would be
 * storing arithmetic rather than a fact. It is the one column here that a v1 file (see
 * [CSV_VERSION]) does not have, which is why it is absent from [REQUIRED_STRUCTURAL_COLUMNS].
 */
private val STRUCTURAL_COLUMNS = listOf(
    KIND_COLUMN, "uid", "event_type", "written_at", "tz_offset_min", "happened_at", "workout_uid",
    "author_id", "payload",
)

/**
 * The columns a file MUST carry to be accepted at all — every [STRUCTURAL_COLUMNS] entry except
 * `tz_offset_min`, which a v1 file was written before this column existed and therefore never
 * has. Checked in [readJournalFile]; [STRUCTURAL_COLUMNS] itself stays the list a restore is
 * ALLOWED to read; this is the narrower list of what it can insist on finding.
 */
private val REQUIRED_STRUCTURAL_COLUMNS = STRUCTURAL_COLUMNS - "tz_offset_min"

/**
 * FOR THE EYE. NEVER READ BY A RESTORE — see [STRUCTURAL_COLUMNS].
 *
 * `current_version` is the flag the owner of this format asked for in place of a second file:
 * `true` for a row that is what the app currently says (an event still live once every
 * deletion and correction is applied — see [journalView] — or any row of a kind the app does
 * not keep old versions of at all, which is every kind but `event`). Filtering the file on
 * this column alone reproduces "what the app shows now"; filtering on nothing at all
 * reproduces the whole history, corrections and deletions included. Both readings live in one
 * file because a restore needs the second and a person mostly wants the first.
 *
 * `name`, `date`, `workout`, `exercise`, `form` and the value columns after it are the same
 * idea `journalCsv` used to be on its own: a resolved, human name in place of a uid, and a
 * form's own fields laid out so a spreadsheet's filters and sums work on them directly. They
 * are computed off the CURRENT (corrected) reading of a row, not the raw payload the
 * `payload` column carries — so a corrected set's derived columns show the correction, while
 * its `payload` column, for the row that recorded the correction itself, still carries
 * exactly what that row said. Only [ActivityForm]-shaped event rows fill most of these; a
 * catalog/slot/program/settings/meta row leaves them blank except `name`.
 */
private val DERIVED_COLUMNS = listOf(
    "current_version", "name", "date", "workout", "exercise", "form", "side",
    "weight_kg", "added_kg", "own_weight", "reps", "hold_sec", "rest_after_sec", "warmup",
    "incomplete", "bodyweight_kg", "duration_sec", "distance_m", "pace_sec_per_km",
)

/** internal rather than private so a test can name a column instead of a bare index. */
internal val CSV_HEADER = STRUCTURAL_COLUMNS + DERIVED_COLUMNS

/**
 * A byte order mark ahead of the header, because the file is meant to be opened as a
 * spreadsheet as much as read as text and the exercise/workout names in it are not
 * constrained to ASCII. Without it, a spreadsheet that guesses the wrong encoding turns those
 * names to mojibake; every reader that does not care about a BOM ignores it.
 */
private const val UTF8_BOM = "﻿"

/**
 * What the file says about ITSELF: when it was written and by which installation.
 *
 * Decoration and provenance, same as the rest of this envelope used to carry directly:
 * [exportedAt] and [deviceId] tell two files of the same journal apart and are never restored
 * — a restored copy is a new installation and says so with its own id.
 */
@Serializable
private data class ExportMeta(
    @SerialName("exported_at") val exportedAt: String = "",
    @SerialName("device_id") val deviceId: String = "",
)

/**
 * The envelope, once a file has been read and accepted. Every section defaults empty so that a
 * backup of an app that has no plan in it yet, or one taken without a settings gateway, still
 * reads.
 */
data class JournalFile(
    /** Decoration: where the file says it came from. Never consulted by a restore. */
    val exportedAt: String = "",
    val deviceId: String = "",
    val events: List<PortableEvent> = emptyList(),
    val exercises: List<PortableExercise> = emptyList(),
    val slots: List<PortableSlot> = emptyList(),
    val programs: List<PortableProgramRow> = emptyList(),
    val settings: PortableSettings? = null,
)

/**
 * One journal row, exactly as a restore reads it off an `event` row of the file — see
 * [STRUCTURAL_COLUMNS].
 *
 * [payload] is the event's own payload text, left alone: not even parsed into a structured
 * form, because nothing between the file and the database needs to look inside it — see the
 * file KDoc's "every row carries its payload opaque".
 *
 * [occurredTs] is new here (this row used to lose it silently): the moment the row's OWN
 * training happened, as opposed to [ts], the moment it was WRITTEN — see
 * [xyz.oleolegka.gachimuchi.data.db.EventEntity.occurredTs]. Null carries through as null
 * rather than being defaulted to [ts], because a row that never had the column is a different
 * fact from a row whose training happened exactly when it was written.
 *
 * [tzOffsetMin] is minutes east of UTC at the moment [ts] was written (Moscow is 180), the same
 * fact [xyz.oleolegka.gachimuchi.data.db.EventEntity.tzOffsetMin] holds. THIS IS THE FIX the
 * format was missing: a v1 file said only the local wall clock, so a journal exported abroad
 * and restored at home silently took on home's offset — see
 * [xyz.oleolegka.gachimuchi.data.JournalBackup.restore], which now resolves [ts] against THIS
 * offset rather than the restoring device's zone. Null for a row a v1 file carried (the column
 * did not exist yet) or whose own `ts` never resolved to an offset in the first place; either
 * way a restore falls back to the device's zone, which is the only information left to use.
 */
data class PortableEvent(
    val uid: String,
    val ts: String,
    val type: String,
    val payload: String,
    val workoutUid: String? = null,
    val authorId: Long = 1,
    val occurredTs: String? = null,
    val tzOffsetMin: Int? = null,
)

/**
 * A catalog exercise, carried whole into one `payload` cell of an `exercise` row — see the
 * file KDoc for why this is JSON-per-row rather than column-per-field.
 *
 * ── This list is spelled out, and that is a standing obligation ─────────────────
 * The catalog, the plan and the programs are reference data with columns, not payloads, so
 * unlike an event they cannot be carried through blind. A COLUMN ADDED TO `exercises` AND NOT
 * ADDED HERE IS A COLUMN THAT DOES NOT SURVIVE A RESTORE, silently — the file loads, the
 * exercise comes back, and one thing about it is quietly the default. Every field is optional
 * with the same default the entity has, so that a file written before a column existed still
 * reads and lands on the same value a fresh row would. [PortableExerciseCoverageTest] pins
 * this list against [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity] directly.
 *
 * ── `edge_mm` used to be one of these fields, and is gone (schema version 18) ────
 * The hangboard edge is no longer part of the domain model at all — see `MIGRATION_17_18` in
 * `data/db/AppDatabase.kt`.
 *
 * ── `protocol_work_sec`/`protocol_rest_sec` used to be two of these fields, and are gone
 * (schema version 19) ─────────────────────────────────────────────────────────────
 * The protocol is now a REFERENCE to a library program rather than a bare work:rest pair — see
 * [ExerciseIdentity] for why. [protocolProgramUid] carries that reference the same way
 * [PortableProgramRow.exerciseUid] already carries the reverse link, by uid rather than by
 * local row number, so it survives a restore onto a different phone.
 *
 * ── `picture_id` is on [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity] and deliberately NOT
 * here (schema version 23) ────────────────────────────────────────────────────────
 * It names a file in [xyz.oleolegka.gachimuchi.data.ExercisePictureStore], and a file is not
 * something this format can carry — the same reason the celebration gallery's pictures are
 * not in this backup either. A restore therefore lands an exercise with no picture even when
 * the phone it came from had one; that is a real loss and not a bug.
 */
@Serializable
data class PortableExercise(
    @SerialName("uid") val uid: String,
    @SerialName("name") val name: String,
    /** Form code, the values of [ExerciseForm]. */
    @SerialName("form") val form: Int,
    @SerialName("created_at") val createdAt: String,
    /** The library program this exercise's protocol is, by uid — see the class KDoc above. */
    @SerialName("protocol_program_uid") val protocolProgramUid: String? = null,
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

/** A plan slot with the session it is meant to consist of, one `slot` row's whole payload. */
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
 * One exercise planned into a slot — nested inside [PortableSlot], never a row of its own,
 * because "the whole slot travels as one payload" is the point (see the file KDoc).
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
 * program file, and is one `program` row's whole payload.
 *
 * Two differences from the program file's shape, both deliberate. It has a uid, because a
 * backup is restored repeatedly into the same database and must not grow a fourth copy of
 * "Tabata 20:10" each time; the program file has none, because sending someone a protocol is
 * always a new program on their phone. And it keeps the link to a catalog exercise, said as a
 * uid — the program file drops that link because a row number means nothing on another phone,
 * which is exactly the problem a uid does not have.
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
 * can answer. One `settings` row's whole payload; at most one such row is meaningful.
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
 * Compact — a CSV cell is not the place for a JSON with its own newlines in it, unlike the old
 * whole-file JSON export this replaces. `ignoreUnknownKeys` and `encodeDefaults` are kept for
 * the same reasons as before: a v1 file written by a later build that added an optional field
 * still reads, and every field a class carries is written out explicitly rather than only the
 * ones that happen to differ from the default.
 */
private val csvPayloadJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// --- out ---------------------------------------------------------------------------

/**
 * The whole journal as CSV text.
 *
 * [events] and [catalog] are the raw material a Room read produces (see
 * [xyz.oleolegka.gachimuchi.data.JournalBackup.export]): EVERY event, live or dead, so that
 * the file is a copy of the history and not an approximation of it, and the catalog's CURRENT
 * names, used only to fill the derived `exercise`/`workout` columns — never the `payload`
 * column, which carries an event's own stored text untouched.
 */
fun writeJournalFile(
    events: List<JournalEvent>,
    catalog: List<CatalogRow>,
    exercises: List<PortableExercise>,
    slots: List<PortableSlot>,
    programs: List<PortableProgramRow>,
    settings: PortableSettings?,
    exportedAt: String = "",
    deviceId: String = "",
): String {
    val rows = ArrayList<List<String>>()
    rows += metaRow(exportedAt, deviceId)
    settings?.let { rows += settingsRow(it) }
    exercises.forEach { rows += exerciseRow(it) }
    slots.forEach { rows += slotRow(it) }
    programs.forEach { rows += programRow(it) }
    rows += eventRows(events, catalog)
    return buildCsvText(rows)
}

/** Every column in [CSV_HEADER]'s order, filled in from [cells] and blank where unmentioned. */
private fun row(vararg cells: Pair<String, String>): List<String> {
    val byName = cells.toMap()
    return CSV_HEADER.map { byName[it].orEmpty() }
}

private fun metaRow(exportedAt: String, deviceId: String): List<String> = row(
    KIND_COLUMN to KIND_META,
    "payload" to csvPayloadJson.encodeToString(ExportMeta(exportedAt, deviceId)),
)

private fun settingsRow(settings: PortableSettings): List<String> = row(
    KIND_COLUMN to KIND_SETTINGS,
    "name" to "Settings",
    "payload" to csvPayloadJson.encodeToString(settings),
)

private fun exerciseRow(exercise: PortableExercise): List<String> = row(
    KIND_COLUMN to KIND_EXERCISE,
    "uid" to exercise.uid,
    "name" to exercise.name,
    "payload" to csvPayloadJson.encodeToString(exercise),
)

private fun slotRow(slot: PortableSlot): List<String> = row(
    KIND_COLUMN to KIND_SLOT,
    "uid" to slot.uid,
    "name" to slot.name,
    "payload" to csvPayloadJson.encodeToString(slot),
)

private fun programRow(program: PortableProgramRow): List<String> = row(
    KIND_COLUMN to KIND_PROGRAM,
    "uid" to program.uid,
    "name" to program.name,
    "payload" to csvPayloadJson.encodeToString(program),
)

/** Event type -> the title [ExerciseForm] itself uses, e.g. "strength_set" -> "Strength". */
private val FORM_TITLE_BY_TYPE: Map<String, String> = ExerciseForm.entries.associate { it.eventType to it.title }

/**
 * One row per RAW journal event, in journal (write) order — not sorted by the day trained, on
 * purpose: restoring an event section has to hand out the same row numbers a fresh database
 * would (see the class KDoc's "row numbers are absent from this file by design"), which only
 * holds if the events land back in exactly the order they were written.
 */
private fun eventRows(events: List<JournalEvent>, catalog: List<CatalogRow>): List<List<String>> {
    val nameByUid = catalog.associate { it.uid to it.name }
    val nameById = catalog.associate { it.id to it.name }
    val workoutLabel = workoutLabeller(events)
    val view = journalView(events)
    return events.map { ev ->
        val state = view.stateOf(ev)
        val form = formFromEventOrNull(ev.type, state.payload)
        eventRow(ev, alive = !state.deleted, form = form, nameByUid = nameByUid, nameById = nameById, workoutLabel = workoutLabel)
    }
}

/**
 * One event's structural columns (its own raw fields) and derived columns (off [form], the
 * CURRENT reading of the row — see [DERIVED_COLUMNS]).
 */
private fun eventRow(
    ev: JournalEvent,
    alive: Boolean,
    form: ActivityForm?,
    nameByUid: Map<String, String>,
    nameById: Map<Long, String>,
    workoutLabel: (WorkoutRef?) -> String,
): List<String> {
    val loaded = form as? LoadedSet
    val strength = form as? StrengthSet
    val hold = form as? HoldSet
    val duration = form as? Duration
    val cardio = form as? Cardio
    val bodyweight = form as? Bodyweight

    val exerciseName = if (form == null || ev.type == TYPE_BODYWEIGHT) {
        ""
    } else {
        val link = form.exerciseLink()
        val resolved = link?.uid?.let(nameByUid::get) ?: link?.id?.let(nameById::get)
        resolved ?: form.activityName()
    }

    return row(
        KIND_COLUMN to KIND_EVENT,
        "uid" to ev.uid,
        "event_type" to ev.type,
        "written_at" to ev.ts,
        "tz_offset_min" to (ev.tzOffsetMin?.toString() ?: ""),
        "happened_at" to (ev.occurredTs ?: ""),
        "workout_uid" to (ev.workoutUid ?: ""),
        "author_id" to ev.authorId.toString(),
        "payload" to ev.payload,
        "current_version" to alive.toString(),
        "date" to (form?.opDate ?: ""),
        "workout" to workoutLabel(ev.workoutRef()),
        "exercise" to exerciseName,
        "form" to (FORM_TITLE_BY_TYPE[ev.type] ?: ""),
        "side" to (loaded?.sideOf?.code ?: ""),
        "weight_kg" to num(strength?.weightKg),
        "added_kg" to num(loaded?.addedKg),
        "own_weight" to bool(loaded?.ownWeight),
        "reps" to num(strength?.reps ?: hold?.reps),
        "hold_sec" to num(hold?.holdSec),
        "rest_after_sec" to num(loaded?.restAfterSec),
        "warmup" to bool(loaded?.warmup),
        "incomplete" to bool(loaded?.incomplete),
        "bodyweight_kg" to num(loaded?.bodyweightKg ?: bodyweight?.weightKg),
        "duration_sec" to num(duration?.durationSec ?: cardio?.durationSec),
        "distance_m" to num(cardio?.distanceM),
        "pace_sec_per_km" to num(cardio?.paceSecPerKm),
    )
}

/**
 * Resolves a [WorkoutRef] to the name to print, or "" for an entry recorded outside any
 * workout.
 *
 * The name is read off the LIVE [TYPE_WORKOUT_STARTED] row through [liveEvents] — the same
 * corrections and the same "deleted stays gone" rule [current_version] follows elsewhere, and
 * the same fallback the app's own screens use for one nobody named. A ref naming a workout
 * whose start row is no longer live still reads as "Workout" rather than as nothing: the entry
 * was recorded inside SOME workout, and blanking the column would misreport it as standalone.
 */
private fun workoutLabeller(events: List<JournalEvent>): (WorkoutRef?) -> String {
    val nameByUid = HashMap<String, String?>()
    val nameById = HashMap<Long, String?>()
    for (row in liveEvents(events)) {
        if (row.type != TYPE_WORKOUT_STARTED) continue
        val name = runCatching { payloadJson.decodeFromString<WorkoutStarted>(row.payload) }.getOrNull()?.name
        nameByUid[row.uid] = name
        nameById[row.id] = name
    }
    return { ref ->
        when {
            ref == null -> ""
            ref.uid != null -> if (ref.uid in nameByUid) nameByUid.getValue(ref.uid) ?: "Workout" else "Workout"
            ref.id != null -> if (ref.id in nameById) nameById.getValue(ref.id) ?: "Workout" else "Workout"
            else -> ""
        }
    }
}

private fun num(v: Double?): String = v?.toString() ?: ""
private fun num(v: Int?): String = v?.toString() ?: ""
private fun bool(v: Boolean?): String = v?.toString() ?: ""

/**
 * One CSV cell, quoted only when it has to be — a comma, a quote or a newline in an exercise
 * name (or inside a `payload`, which is JSON full of both) is the case this exists for, and
 * every embedded quote is doubled the way every CSV reader expects.
 */
private fun csvField(raw: String): String =
    if (raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + raw.replace("\"", "\"\"") + "\""
    } else {
        raw
    }

private fun buildCsvText(rows: List<List<String>>): String {
    val out = StringBuilder(UTF8_BOM)
    out.append(CSV_HEADER.joinToString(",")).append('\n')
    for (r in rows) out.append(r.joinToString(",") { csvField(it) }).append('\n')
    return out.toString()
}

// --- in ----------------------------------------------------------------------------

/** What came out of a file: a journal to merge, or a sentence explaining why not. */
sealed interface JournalImport {
    data class Loaded(val file: JournalFile) : JournalImport

    /** [reason] is user-facing text, already a whole sentence. */
    data class Rejected(val reason: String) : JournalImport
}

/**
 * A general-purpose CSV parser, because the payload cells here are JSON — full of commas and
 * quotes — and a `split(",")` would shred every row. Handles a doubled quote inside a quoted
 * field and a quoted field that spans a literal newline (this app never writes one — see
 * [csvPayloadJson]'s own KDoc — but a hand-edited file might, and refusing to read it would be
 * a worse failure than parsing it correctly).
 */
private fun parseCsv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var current = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    fun endField() {
        current.add(field.toString())
        field.setLength(0)
    }
    fun endRow() {
        endField()
        rows.add(current)
        current = ArrayList()
    }
    while (i < text.length) {
        val c = text[i]
        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    field.append('"')
                    i++
                } else {
                    inQuotes = false
                }
            } else {
                field.append(c)
            }
        } else {
            when (c) {
                '"' -> inQuotes = true
                ',' -> endField()
                '\r' -> Unit // \r\n is handled by the \n that follows; a bare \r is dropped
                '\n' -> endRow()
                else -> field.append(c)
            }
        }
        i++
    }
    if (field.isNotEmpty() || current.isNotEmpty()) endRow()
    return rows
}

/**
 * The file as rows of column name -> cell text, header included only as the map keys.
 *
 * `internal`, not `private`, purely so a TEST can check what a DERIVED column actually holds
 * without a hand-rolled CSV reader of its own — [readJournalFile] itself never calls this; its
 * own `cell(name)` closures read only [STRUCTURAL_COLUMNS], which is the property under test
 * wherever this function is used.
 */
internal fun csvRowsByColumn(text: String): List<Map<String, String>> {
    val table = parseCsv(text.removePrefix(UTF8_BOM))
    if (table.isEmpty()) return emptyList()
    val header = table.first()
    return table.drop(1).filter { it.size == header.size }.map { cells -> header.zip(cells).toMap() }
}

/**
 * Reads a backup file. Never throws: every failure comes back as [JournalImport.Rejected].
 *
 * What is checked here is the SHAPE and the cross-references — that rows have identities,
 * that the identities are unique, that a form code and a repeat rule are ones this build can
 * render, that a program can actually be run. What is deliberately not checked is the inside
 * of an event payload; see the file KDoc's note at the top.
 */
fun readJournalFile(text: String): JournalImport {
    if (text.isBlank()) return JournalImport.Rejected("The file is empty.")

    val table = parseCsv(text.removePrefix(UTF8_BOM))
    if (table.isEmpty()) return JournalImport.Rejected("The file is empty.")

    val header = table.first()
    versionProblem(header.getOrNull(0).orEmpty())?.let { return JournalImport.Rejected(it) }

    val missing = REQUIRED_STRUCTURAL_COLUMNS.drop(1).filterNot { it in header }
    if (missing.isNotEmpty()) {
        return JournalImport.Rejected(
            "This file could not be read as a journal backup. It is missing the column(s) " +
                "${missing.joinToString()} a backup needs to be restored."
        )
    }
    val index = header.withIndex().associate { (i, name) -> name to i }

    val meta = ArrayList<ExportMeta>()
    val settingsRows = ArrayList<PortableSettings>()
    val exercises = ArrayList<PortableExercise>()
    val slots = ArrayList<PortableSlot>()
    val programs = ArrayList<PortableProgramRow>()
    val events = ArrayList<PortableEvent>()

    for ((offset, cells) in table.drop(1).withIndex()) {
        val lineNumber = offset + 2 // 1 for the header, 1 because humans count from one
        if (cells.size != header.size) {
            return JournalImport.Rejected(
                "Row $lineNumber has ${cells.size} column(s); the header has ${header.size}. " +
                    "The file is truncated or was edited by something that does not speak CSV."
            )
        }
        fun cell(name: String): String = cells[index.getValue(name)]

        // for the one structural column a v1 file never has ([REQUIRED_STRUCTURAL_COLUMNS]) —
        // "" rather than a throw, so a column absent from the header reads the same as a column
        // present but left blank
        fun cellOrBlank(name: String): String = index[name]?.let { cells[it] } ?: ""

        when (cells[0]) {
            KIND_META -> {
                val decoded = runCatching { csvPayloadJson.decodeFromString<ExportMeta>(cell("payload")) }.getOrNull()
                    ?: return JournalImport.Rejected("Row $lineNumber: the file's own metadata could not be read.")
                meta += decoded
            }

            KIND_SETTINGS -> {
                val decoded = runCatching { csvPayloadJson.decodeFromString<PortableSettings>(cell("payload")) }.getOrNull()
                    ?: return JournalImport.Rejected("Row $lineNumber: the settings could not be read.")
                settingsRows += decoded
            }

            KIND_EXERCISE -> {
                val decoded = runCatching { csvPayloadJson.decodeFromString<PortableExercise>(cell("payload")) }.getOrNull()
                    ?: return JournalImport.Rejected("Row $lineNumber: an exercise could not be read.")
                exercises += decoded
            }

            KIND_SLOT -> {
                val decoded = runCatching { csvPayloadJson.decodeFromString<PortableSlot>(cell("payload")) }.getOrNull()
                    ?: return JournalImport.Rejected("Row $lineNumber: a plan slot could not be read.")
                slots += decoded
            }

            KIND_PROGRAM -> {
                val decoded = runCatching { csvPayloadJson.decodeFromString<PortableProgramRow>(cell("payload")) }.getOrNull()
                    ?: return JournalImport.Rejected("Row $lineNumber: a program could not be read.")
                programs += decoded
            }

            KIND_EVENT -> events += PortableEvent(
                uid = cell("uid"),
                ts = cell("written_at"),
                type = cell("event_type"),
                payload = cell("payload"),
                workoutUid = cell("workout_uid").ifBlank { null },
                authorId = cell("author_id").toLongOrNull() ?: 1L,
                occurredTs = cell("happened_at").ifBlank { null },
                tzOffsetMin = cellOrBlank("tz_offset_min").toIntOrNull(),
            )

            else -> return JournalImport.Rejected(
                "Row $lineNumber has a row kind (\"${cells[0]}\") this build does not know. " +
                    "The file was not written by this app, or it has been edited."
            )
        }
    }

    val file = JournalFile(
        exportedAt = meta.firstOrNull()?.exportedAt.orEmpty(),
        deviceId = meta.firstOrNull()?.deviceId.orEmpty(),
        events = events,
        exercises = exercises,
        slots = slots,
        programs = programs,
        settings = settingsRows.firstOrNull(),
    )
    problemWithContents(file)?.let { return JournalImport.Rejected(it) }
    return JournalImport.Loaded(file)
}

/** The version guard, folded into the name of the header's first column — see [KIND_COLUMN]. */
private fun versionProblem(firstColumn: String): String? {
    if (!firstColumn.startsWith(KIND_COLUMN_PREFIX)) {
        return "This file could not be read as a journal backup. A backup's first column is " +
            "named \"$KIND_COLUMN_PREFIX<number>\"; this file has no such column, so it was not " +
            "written by this app, or it is damaged."
    }
    val version = firstColumn.removePrefix(KIND_COLUMN_PREFIX).toIntOrNull()
    if (version == null || version < 1) {
        return "The file declares format version " +
            "\"${firstColumn.removePrefix(KIND_COLUMN_PREFIX)}\", which is not a version."
    }
    if (version > CSV_VERSION) {
        return "The file was written by a newer version of the app (format version $version; " +
            "this build reads up to $CSV_VERSION). Restoring it here could drop whatever that " +
            "version added. Update the app and try again."
    }
    return null
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
    exerciseIdentity(name, form, protocolProgramUid)

/**
 * The backup's view of a catalog row — see [CatalogRow] for why this is one of four narrow
 * views built off the one place that reads the entity, rather than a fifth place that reads it
 * again. [id] is dropped: it is local plumbing (domain/Catalog.kt), and the file refers to
 * everything by [CatalogRow.uid] instead.
 *
 * [protocolProgramUid] is resolved by the caller (see [xyz.oleolegka.gachimuchi.data.toPortable]
 * — this function is a pure mapping over what it is handed, same as [toRef]).
 */
fun CatalogRow.toPortable(protocolProgramUid: String? = null): PortableExercise = PortableExercise(
    uid = uid,
    name = name,
    form = form,
    createdAt = createdAt,
    protocolProgramUid = protocolProgramUid,
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
