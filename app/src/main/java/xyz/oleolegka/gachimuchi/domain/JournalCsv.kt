package xyz.oleolegka.gachimuchi.domain

/**
 * The journal as a CSV table — the other half of the two exports, and a different job from
 * domain/JournalTransfer.kt's JSON.
 *
 * ── JSON restores, CSV is read ───────────────────────────────────────────────────
 * The JSON export is opaque and append-only on the way back in: every event, live or dead,
 * travels so that restoring it reproduces the phone exactly. This is the opposite kind of
 * file. It has no way back in — there is no [readActivities]-shaped CSV import, and none is
 * planned — so it is free to show only what the app itself would show: [readActivities]
 * already drops a deleted entry and an entry of a deleted exercise, and already lays every
 * amendment over its target, and that is exactly the view wanted here. A row of the file is a
 * TRAINING FACT, read the way the app reads it, not a row of the append-only log underneath it.
 *
 * ── One row per set, not per event ──────────────────────────────────────────────
 * A correction ([TYPE_ENTRY_AMENDED]) or a deletion never gets its own row — it has already
 * been folded into the entry it corrects by the time this file sees it. Ten corrections to one
 * set are still one line here, the last word on each field.
 *
 * ── The columns, and why there are more of them than any one row fills ──────────
 * The six activity forms do not share a shape: a strength set has no [HoldSet.holdSec], a
 * check-in has no metric at all. Rather than inventing a merged "weight" column that would
 * have to guess which of [LoadedSet.addedKg] / a plain [StrengthSet.weightKg] a reader meant,
 * every field that can matter gets its own column, named after the payload key it comes from,
 * and a row leaves blank whatever its own form does not carry. A blank cell is the honest
 * answer for "this form has no such field" as much as for "nobody said" — nothing here invents
 * a zero to fill the gap.
 *
 * ── Numbers and dates are machine-readable first ─────────────────────────────────
 * Dates are the journal's own ISO `op_date` (`YYYY-MM-DD`); [Double.toString] on the JVM
 * always uses a decimal POINT regardless of the phone's locale, so a number never depends on
 * where the reader is. A spreadsheet opens either just fine; a script parsing the file does
 * too, which a locale-formatted number would not have allowed.
 *
 * ── Row order: by the day trained, not by the day written ───────────────────────
 * [readActivities] itself answers in journal (write) order, which is right for one exercise on
 * one day and wrong for a file spanning years: a set backdated last week would otherwise land
 * among today's rows. This file sorts by [ActivityEvent.opDate] first — the day the row is
 * ABOUT — and by journal order for ties, so two sets on one day still read in the order they
 * were actually done.
 */

/** The MIME type a CSV export is written as. */
const val JOURNAL_CSV_MIME = "text/csv"

/**
 * A byte order mark ahead of the header, because the file is meant to be opened as a
 * spreadsheet as much as read as text and the exercise/workout names in it are not
 * constrained to ASCII (see [normPhrase] for the Cyrillic this app already normalizes).
 * Without it, a spreadsheet that guesses the wrong encoding turns those names to mojibake;
 * every reader that does not care about a BOM ignores it.
 */
private const val UTF8_BOM = "﻿"

private val CSV_HEADER = listOf(
    "date", "workout", "exercise", "form", "side",
    "weight_kg", "added_kg", "own_weight", "reps",
    "hold_sec", "rest_after_sec", "warmup",
    "bodyweight_kg", "duration_sec", "distance_m", "pace_sec_per_km",
)

/** Event type -> the title [ExerciseForm] itself uses, e.g. "strength_set" -> "Strength". */
private val FORM_TITLE_BY_TYPE: Map<String, String> = ExerciseForm.entries.associate { it.eventType to it.title }

/**
 * The whole journal as CSV text, exactly as [readActivities] reads it: live entries only,
 * amendments already applied.
 *
 * [catalog] resolves an entry's exercise to its CURRENT catalog name — the same name every
 * other screen shows, which can differ from the name the entry's own payload was written
 * with if the exercise has since been renamed. An entry whose exercise cannot be resolved
 * (a link naming no catalog row this phone holds) falls back to its own payload's name
 * instead of an empty cell, on the same "never invent, but never lose it either" rule as the
 * rest of this file.
 */
fun journalCsv(events: List<JournalEvent>, catalog: List<CatalogRow>): String {
    val nameByUid = catalog.associate { it.uid to it.name }
    val nameById = catalog.associate { it.id to it.name }
    val workoutLabel = workoutLabeller(events)

    val rows = readActivities(events)
        .sortedWith(compareBy({ it.opDate }, { it.id }))
        .map { csvRow(it, nameByUid, nameById, workoutLabel) }

    val out = StringBuilder(UTF8_BOM)
    out.append(CSV_HEADER.joinToString(",")).append('\n')
    for (row in rows) out.append(row.joinToString(",") { csvField(it) }).append('\n')
    return out.toString()
}

/**
 * What one [ActivityEvent] says, as the raw (unescaped) cells of [CSV_HEADER] in order.
 *
 * Body weight is the one form with no exercise at all ([ActivityForm.activityName] answers
 * "Body weight" for it, which belongs in the FORM column and would be a wrong and redundant
 * answer in the exercise one) — so its exercise cell is left blank rather than repeating the
 * form's own name.
 */
private fun csvRow(
    ev: ActivityEvent,
    nameByUid: Map<String, String>,
    nameById: Map<Long, String>,
    workoutLabel: (WorkoutRef?) -> String,
): List<String> {
    val form = ev.form
    val loaded = form as? LoadedSet
    val strength = form as? StrengthSet
    val hold = form as? HoldSet
    val duration = form as? Duration
    val cardio = form as? Cardio
    val bodyweight = form as? Bodyweight

    val exercise = if (ev.type == TYPE_BODYWEIGHT) {
        ""
    } else {
        val link = form.exerciseLink()
        val resolved = link?.uid?.let(nameByUid::get) ?: link?.id?.let(nameById::get)
        resolved ?: form.activityName()
    }

    return listOf(
        ev.opDate,
        workoutLabel(ev.workout),
        exercise,
        FORM_TITLE_BY_TYPE[ev.type] ?: ev.type,
        hold?.sideOf?.let { if (it == HoldSide.LEFT) "left" else "right" } ?: "",
        num(strength?.weightKg),
        num(loaded?.addedKg),
        bool(loaded?.ownWeight),
        num(strength?.reps ?: hold?.reps),
        num(hold?.holdSec),
        num(loaded?.restAfterSec),
        bool(loaded?.warmup),
        num(loaded?.bodyweightKg ?: bodyweight?.weightKg),
        num(duration?.durationSec ?: cardio?.durationSec),
        num(cardio?.distanceM),
        num(cardio?.paceSecPerKm),
    )
}

/**
 * Resolves a [WorkoutRef] to the name to print, or "" for an entry recorded outside any
 * workout.
 *
 * The name is read off the LIVE [TYPE_WORKOUT_STARTED] row through [liveEvents] — the same
 * corrections and the same "deleted stays gone" rule every other column here follows, and the
 * same fallback the app's own screens use for one nobody named (see `workout.name ?: "Workout"`
 * in WorkoutScreen.kt / WorkoutLogScreen.kt). A ref naming a workout whose start row is no
 * longer live (deleted, or absent from a merged-in journal) still reads as "Workout" rather
 * than as nothing: the entry was recorded inside SOME workout, and blanking the column would
 * misreport it as a standalone entry.
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
 * name is the case this exists for, and every embedded quote is doubled the way every CSV
 * reader expects.
 */
private fun csvField(raw: String): String =
    if (raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + raw.replace("\"", "\"\"") + "\""
    } else {
        raw
    }
