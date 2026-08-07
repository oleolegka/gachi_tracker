package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Activity domain layer on top of the append-only event journal — a port of `bot/domain.py`.
 *
 * Every domain record is a journal event: (ts, type, payload JSON, ...). An activity
 * form defines the `type` string and the `payload` schema. There are six forms
 * (decisions.md §3): strength_set, hold_set, duration, tick, cardio, bodyweight.
 *
 * ── Payload compatibility with the server ───────────────────────────────────────
 * JSON field names are snake_case and match the Python `asdict(form)` output ONE FOR
 * ONE (@SerialName on every field). This is not cosmetic: the app journal and the bot
 * journal have to be readable by the same code once sync happens. Renaming a field
 * here breaks that future exchange.
 *
 * ── op_date is separate from ts ─────────────────────────────────────────────────
 * `ts` is the honest write time (append-only audit trail); `op_date` is the day the
 * activity belongs to (ISO "YYYY-MM-DD") and lives IN THE PAYLOAD rather than in its
 * own column. For backdated entries the two diverge, and they are two different facts.
 * The price: filtering by date range scans payloads (there is no index); at personal
 * scale that is negligible.
 *
 * ── Granularity of set-based forms ──────────────────────────────────────────────
 * ONE EVENT = ONE SET. "Exercise" and "workout" are derived groupings (see [Journal]),
 * not stored records.
 */

// --- event type strings (one per form), identical to bot/domain.py ---
const val TYPE_STRENGTH_SET = "strength_set"
const val TYPE_HOLD_SET = "hold_set"
const val TYPE_DURATION = "duration"
const val TYPE_TICK = "tick"
const val TYPE_CARDIO = "cardio"
const val TYPE_BODYWEIGHT = "bodyweight"

/**
 * Cancelling a set is NOT an activity form but a reversing journal event. The journal
 * is append-only and nothing may be deleted: a cancellation is a separate event that
 * the reducers exclude (payload = {"cancels": <event_id>}).
 */
const val TYPE_SET_CANCEL = "set_cancel"

/**
 * "A workout has begun". Like [TYPE_SET_CANCEL] this is a SERVICE event and not an activity
 * form: it records no training, so it is absent from [ACTIVITY_TYPES] and every reducer that
 * folds sets ignores it.
 *
 * THE EVENT'S OWN ID IS THE WORKOUT. There is no `workouts` table and no separate identifier
 * to keep in step with the journal — a workout is a point in an append-only log, and
 * everything else about it (which exercises, in which order, with which sets) is derived from
 * the rows that point back at that id. The same reasoning the journal itself rests on: state
 * that is derived cannot drift out of sync with the events it was derived from.
 *
 * There is deliberately NO closing event. A workout is over when the next one starts or when
 * the day ends, and asking a person mid-gym to press "finish" reliably is asking for a
 * journal full of workouts that were never closed. The cost is stated in
 * [xyz.oleolegka.gachimuchi.domain.openWorkout], which has to define "the one in progress"
 * without being told.
 */
const val TYPE_WORKOUT_STARTED = "workout_started"

/**
 * "This exercise is part of that workout, and this is the rest I want between its sets."
 *
 * Exists so that an exercise can be IN a workout before it has a single set — the user adds
 * three exercises when they walk in, and the screen has to show three empty blocks. A set is
 * the only other evidence that an exercise belongs to a workout, and waiting for one would
 * mean the list cannot be built in advance, which is the whole feature.
 *
 * Also the record of the chosen rest. That is not the same fact as the pause the timestamps
 * later reveal (see [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.defaultRestSec]): this
 * is what was decided, at the moment it was decided, and it stays true even for a workout
 * where every rest ran long.
 */
const val TYPE_WORKOUT_EXERCISE_ADDED = "workout_exercise_added"

/** All domain activity types — the default read filter (service events are skipped). */
val ACTIVITY_TYPES = listOf(
    TYPE_STRENGTH_SET, TYPE_HOLD_SET, TYPE_DURATION, TYPE_TICK, TYPE_CARDIO, TYPE_BODYWEIGHT,
)

/**
 * Form class of a catalog exercise. The Int codes match Python's `flow.FORM_*` and are
 * written straight into the `exercises.form` column.
 *
 * §3 (as refined by §8.3c): an exercise has EXACTLY ONE form, chosen when it is
 * created; whether a metric is present in the input never changes it.
 */
enum class ExerciseForm(val code: Int, val eventType: String, val title: String) {
    STRENGTH(1, TYPE_STRENGTH_SET, "Strength"),
    HOLD(2, TYPE_HOLD_SET, "Holds"),
    CARDIO(3, TYPE_CARDIO, "Cardio"),
    DURATION(4, TYPE_DURATION, "Duration"),
    TICK(5, TYPE_TICK, "Check-in"),
    BODYWEIGHT(6, TYPE_BODYWEIGHT, "Body weight");

    companion object {
        fun fromCode(code: Int): ExerciseForm =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("unknown form code: $code")
    }
}

/**
 * Normalizes an activity name into a dictionary key — a port of `bot/parser.norm_phrase`.
 *
 * The Cyrillic YO -> YE folding is kept for parity with the bot (the same phrase must
 * produce the same key in both journals). The two characters are written as escapes so
 * that the sources stay ASCII-only.
 */
fun normPhrase(raw: String?): String? {
    if (raw == null) return null
    val cleaned = raw.lowercase()
        .replace('\u0451', '\u0435')
        .map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }
        .joinToString("")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ")
    return cleaned.ifBlank { null }
}

private fun requirePos(name: String, v: Int): Int {
    require(v > 0) { "$name: expected a positive integer, got $v" }
    return v
}

private fun requirePosOrNull(name: String, v: Double?): Double? {
    if (v == null) return null
    require(v > 0) { "$name: expected a positive number or null, got $v" }
    return v
}

private fun requireKey(name: String, raw: String): String =
    normPhrase(raw) ?: throw IllegalArgumentException("$name: name is empty after normalization ($raw)")

/** Common interface of all forms: event type plus the activity date. */
sealed interface ActivityForm {
    val type: String
    val opDate: String

    /** Normalized exercise/activity key; body weight has none. */
    val key: String? get() = null

    /** Id of the canonical catalog exercise (§11); null for body weight and legacy records. */
    val exerciseId: Long? get() = null
}

/**
 * A strength set, weight x reps. ONE EVENT = ONE SET.
 *
 * [weightKg] is the absolute weight of the implement and is incompatible with
 * [ownWeight]; [addedKg] is weight added TO YOUR OWN BODY WEIGHT and only makes sense
 * together with [ownWeight]; [restAfterSec] is the rest AFTER the set, i.e. the pause
 * BETWEEN sets (§3): a strength set has no intra-set protocol, so there is nothing to
 * confuse it with.
 */
@Serializable
data class StrengthSet(
    @SerialName("exercise") val exercise: String,
    @SerialName("reps") val reps: Int,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("added_kg") val addedKg: Double? = null,
    @SerialName("own_weight") val ownWeight: Boolean = false,
    @SerialName("exercise_id") override val exerciseId: Long? = null,
    @SerialName("rest_after_sec") val restAfterSec: Double? = null,
    @SerialName("op_date") override val opDate: String,
    @SerialName("exercise_key") val exerciseKey: String = requireKey("exercise", exercise),
) : ActivityForm {
    override val type: String get() = TYPE_STRENGTH_SET
    override val key: String get() = exerciseKey

    init {
        requirePos("reps", reps)
        requirePosOrNull("weight_kg", weightKg)
        requirePosOrNull("added_kg", addedKg)
        requireIsoDate(opDate)
        require(!(weightKg != null && ownWeight)) {
            "weight_kg and own_weight are incompatible: either an implement or your own body weight"
        }
        require(!(addedKg != null && !ownWeight)) {
            "added_kg (added weight) only makes sense with own_weight=true"
        }
    }
}

/**
 * A hold / hang / hangboard set. ONE EVENT = ONE SET.
 *
 * §12-A (SUPERSEDES form 2 of §3): hangboard identity = name + EDGE (mm) + PROTOCOL
 * (work:rest), so "Hangs 20 mm · 7:3" and "Hangs 15 mm · 7:3" are DIFFERENT catalog
 * exercises, and the tracked variable — the personal record — is ADDED WEIGHT
 * ([addedKg]). Edge and protocol therefore live as attributes of the exercise
 * ([xyz.oleolegka.gachimuchi.data.db.ExerciseEntity]).
 *
 * The [edgeMm]/[workSec]/[restSec] fields are KEPT here as a snapshot of the exercise
 * attributes at the time of the set — exactly the payload keys the Python bot writes
 * (otherwise the two journals would drift apart). Identity is NOT derived from them:
 * it comes from [exerciseId]. If an exercise's edge is ever corrected, the snapshot in
 * old events still shows how it was recorded back then.
 *
 * [restAfterSec] is the pause BETWEEN sets; [workSec]/[restSec] are the protocol
 * WITHIN a set. Different quantities, independent of each other.
 */
@Serializable
data class HoldSet(
    @SerialName("activity") val activity: String,
    @SerialName("reps") val reps: Int? = null,
    @SerialName("hold_sec") val holdSec: Double? = null,
    @SerialName("work_sec") val workSec: Double? = null,
    @SerialName("rest_sec") val restSec: Double? = null,
    @SerialName("edge_mm") val edgeMm: Double? = null,
    @SerialName("added_kg") val addedKg: Double? = null,
    @SerialName("own_weight") val ownWeight: Boolean = false,
    @SerialName("exercise_id") override val exerciseId: Long? = null,
    @SerialName("rest_after_sec") val restAfterSec: Double? = null,
    @SerialName("op_date") override val opDate: String,
    @SerialName("activity_key") val activityKey: String = requireKey("activity", activity),
) : ActivityForm {
    override val type: String get() = TYPE_HOLD_SET
    override val key: String get() = activityKey

    init {
        reps?.let { requirePos("reps", it) }
        requirePosOrNull("hold_sec", holdSec)
        requirePosOrNull("work_sec", workSec)
        requirePosOrNull("rest_sec", restSec)
        requirePosOrNull("edge_mm", edgeMm)
        requirePosOrNull("added_kg", addedKg)
        requireIsoDate(opDate)
        require((workSec == null) == (restSec == null)) {
            "a work:rest protocol is set as a pair — both work_sec and rest_sec are required"
        }
    }
}

/**
 * Total duration (Emil hangs "10 minutes", timed stretching).
 * §3: duration is BARE total time; protocols and sets belong to [HoldSet].
 */
@Serializable
data class Duration(
    @SerialName("activity") val activity: String,
    @SerialName("duration_sec") val durationSec: Int,
    @SerialName("exercise_id") override val exerciseId: Long? = null,
    @SerialName("op_date") override val opDate: String,
    @SerialName("activity_key") val activityKey: String = requireKey("activity", activity),
) : ActivityForm {
    override val type: String get() = TYPE_DURATION
    override val key: String get() = activityKey

    init {
        requirePos("duration_sec", durationSec)
        requireIsoDate(opDate)
    }
}

/** A bare tick with no metrics (stretching, bouldering gym). The statistic is frequency. */
@Serializable
data class Tick(
    @SerialName("activity") val activity: String,
    @SerialName("exercise_id") override val exerciseId: Long? = null,
    @SerialName("op_date") override val opDate: String,
    @SerialName("activity_key") val activityKey: String = requireKey("activity", activity),
) : ActivityForm {
    override val type: String get() = TYPE_TICK
    override val key: String get() = activityKey

    init {
        requireIsoDate(opDate)
    }
}

/**
 * Cardio. At least one of [distanceM] / [durationSec] / [paceSecPerKm] must be given
 * (otherwise this is a [Tick]). The domain does NOT compute derived values (time from
 * pace and distance) — it stores exactly what was provided.
 */
@Serializable
data class Cardio(
    @SerialName("activity") val activity: String,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("duration_sec") val durationSec: Int? = null,
    @SerialName("pace_sec_per_km") val paceSecPerKm: Double? = null,
    @SerialName("exercise_id") override val exerciseId: Long? = null,
    @SerialName("op_date") override val opDate: String,
    @SerialName("activity_key") val activityKey: String = requireKey("activity", activity),
) : ActivityForm {
    override val type: String get() = TYPE_CARDIO
    override val key: String get() = activityKey

    init {
        requirePosOrNull("distance_m", distanceM)
        durationSec?.let { requirePos("duration_sec", it) }
        requirePosOrNull("pace_sec_per_km", paceSecPerKm)
        requireIsoDate(opDate)
        require(distanceM != null || durationSec != null || paceSecPerKm != null) {
            "cardio: at least one of distance_m, duration_sec or pace_sec_per_km is required"
        }
    }
}

/** Body weight — a plain time series. The event carries neither a name nor an exercise_id. */
@Serializable
data class Bodyweight(
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("op_date") override val opDate: String,
) : ActivityForm {
    override val type: String get() = TYPE_BODYWEIGHT

    init {
        require(weightKg > 0) { "bodyweight: weight_kg must be greater than zero, got $weightKg" }
        requireIsoDate(opDate)
    }
}

/** Set reversal: payload = {"cancels": id}. */
@Serializable
data class SetCancel(@SerialName("cancels") val cancels: Long)

/**
 * Payload of [TYPE_WORKOUT_STARTED].
 *
 * ── Why op_date is here and not read off the event's `ts` ───────────────────────
 * The two are different facts, exactly as they are for every activity form: `ts` is when the
 * row was written, [opDate] is the day the training belongs to. They diverge whenever a
 * workout is entered AFTER THE FACT, and that is not an edge case here — the history that
 * predates this app is going to be typed in by hand, one past day at a time. Such a workout
 * has to know its own day from the moment it is created, before it contains a single set,
 * because an empty workout has nothing else to be dated by.
 *
 * A BACKDATED WORKOUT IS SILENT. Nothing in it starts a countdown — no rest timer, no
 * interval run, no alarm. A rest that finished a fortnight ago is not something to wait out,
 * and a timer going off while somebody types up old notes on the sofa is pure noise. The
 * timers live elsewhere (timer/); this is the rule they are expected to honour, written down
 * where the date is defined rather than where it happens to be read.
 *
 * ── slot_id ─────────────────────────────────────────────────────────────────────
 * The planned session this workout was started from ([Slot.id]), when it was started from
 * one, and null when it was not. Beyond saving a name to type, it is the exact answer to
 * "was the plan kept" — domain/Schedule.kt currently pairs plan with fact HEURISTICALLY, by
 * clock proximity and greedily, which is right most of the time and unfixably wrong when two
 * sessions sit close together on one day. A link the user made themselves needs no guessing.
 *
 * NOTE, no whitewashing: writing this field does not by itself change how the calendar
 * decides anything. domain/Schedule.kt still matches by time and does not look at it yet;
 * switching it over is a separate change with its own verification. Until then the field is
 * recorded and exposed ([Workout.slotId]) and nothing consumes it.
 */
@Serializable
data class WorkoutStarted(
    @SerialName("op_date") val opDate: String,
    @SerialName("slot_id") val slotId: Long? = null,
) {
    init {
        requireIsoDate(opDate)
    }
}

/**
 * Payload of [TYPE_WORKOUT_EXERCISE_ADDED].
 *
 * [workoutId] duplicates the `workout_id` COLUMN this event is written with, and the
 * duplication is on purpose: the column is local (schema version 5) while the payload is the
 * format meant to survive a trip through the bot's journal, which has no such column. Reads
 * take the column and fall back to this field, so a row that arrives without one still lands
 * in the right workout.
 */
@Serializable
data class WorkoutExerciseAdded(
    @SerialName("workout_id") val workoutId: Long,
    @SerialName("exercise_id") val exerciseId: Long,
    @SerialName("rest_sec") val restSec: Int,
) {
    init {
        // zero is a legitimate answer ("go straight into the next set"); a negative one is
        // a corrupt row, and the readers skip rows that will not parse rather than throwing
        require(restSec >= 0) { "rest_sec: expected a non-negative number of seconds, got $restSec" }
    }
}

internal fun requireIsoDate(d: String): String {
    val ok = Regex("""^\d{4}-\d{2}-\d{2}$""").matches(d)
    require(ok) { "op_date: expected the YYYY-MM-DD format, got $d" }
    return d
}

/** Payload JSON: unknown keys are ignored — the bot's journal may run ahead of the app. */
val payloadJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Form -> payload string to be written into the journal. */
fun ActivityForm.toPayload(): String = when (this) {
    is StrengthSet -> payloadJson.encodeToString(this)
    is HoldSet -> payloadJson.encodeToString(this)
    is Duration -> payloadJson.encodeToString(this)
    is Tick -> payloadJson.encodeToString(this)
    is Cardio -> payloadJson.encodeToString(this)
    is Bodyweight -> payloadJson.encodeToString(this)
}

/** Form as a JsonObject — convenient for tests and debugging. */
fun ActivityForm.toJsonObject(): JsonObject = when (this) {
    is StrengthSet -> payloadJson.encodeToJsonElement(this) as JsonObject
    is HoldSet -> payloadJson.encodeToJsonElement(this) as JsonObject
    is Duration -> payloadJson.encodeToJsonElement(this) as JsonObject
    is Tick -> payloadJson.encodeToJsonElement(this) as JsonObject
    is Cardio -> payloadJson.encodeToJsonElement(this) as JsonObject
    is Bodyweight -> payloadJson.encodeToJsonElement(this) as JsonObject
}

/**
 * Rebuilds a form from (type, payload) — a port of `domain.form_from_event`.
 * Re-validates the payload through the init blocks (idempotent).
 * Throws [IllegalArgumentException] if the type is not a domain form.
 */
/**
 * [formFromEvent] for a row that is allowed to be rubbish: an unreadable payload comes back
 * as null instead of throwing.
 *
 * The journal is append-only and its rows are validated on the way in, so in a journal this
 * app wrote by itself nothing here can fail. It is not the only writer any more: entries are
 * meant to be exchanged with the bot, and a file that arrives truncated, hand-edited or
 * written by a newer schema puts ONE unreadable row in the middle of years of good ones.
 * Every reducer folds the whole journal, so a throw on that row took out the four screens
 * built on top of them — the app would not open, on the one device holding the history.
 * Skipping the row loses the row; throwing loses the app.
 */
fun formFromEventOrNull(type: String, payload: String): ActivityForm? =
    runCatching { formFromEvent(type, payload) }.getOrNull()

fun formFromEvent(type: String, payload: String): ActivityForm = when (type) {
    TYPE_STRENGTH_SET -> payloadJson.decodeFromString<StrengthSet>(payload)
    TYPE_HOLD_SET -> payloadJson.decodeFromString<HoldSet>(payload)
    TYPE_DURATION -> payloadJson.decodeFromString<Duration>(payload)
    TYPE_TICK -> payloadJson.decodeFromString<Tick>(payload)
    TYPE_CARDIO -> payloadJson.decodeFromString<Cardio>(payload)
    TYPE_BODYWEIGHT -> payloadJson.decodeFromString<Bodyweight>(payload)
    else -> throw IllegalArgumentException("event $type is not a domain activity form")
}
