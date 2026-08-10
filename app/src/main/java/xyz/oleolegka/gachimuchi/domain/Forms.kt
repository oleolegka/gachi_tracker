package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
 *
 * SUPERSEDED BY [TYPE_ENTRY_DELETED], which says the same thing about any event rather than
 * only about a set. This one is still written by nothing and read by everything: every journal
 * on every phone is full of it, and [journalView] treats the two as one word for one act.
 */
const val TYPE_SET_CANCEL = "set_cancel"

/**
 * SUPERSEDED, the same way [TYPE_SET_CANCEL] is: nothing writes this any more.
 *
 * ── What this used to be, kept for the readers still standing on it ─────────────
 * "That event's values were wrong; here are the right ones" — a PATCH: the payload carried
 * only the keys being changed, and [journalView] laid them over the original in place, the
 * original row's own identity never moving. [ActivityRepository.amendEntry] now writes a
 * correction the other way round instead: a whole new row, of the row's own type, carrying
 * every field — and marks the old row superseded with [TYPE_ENTRY_DELETED]
 * ([EntryDeleted.successorUid]). See `domain/Amendments.kt`'s header for why: a single line of
 * the journal is now meant to be a complete, self-sufficient statement of what was true when
 * it was written, and a patch that only makes sense laid over another row was the opposite of
 * that.
 *
 * This type string, [EntryAmended] and [AMENDMENT_PROTECTED_KEYS] all stay, because a journal
 * written before this change is full of rows shaped exactly like this, and
 * [xyz.oleolegka.gachimuchi.domain.journalView] still has to read them correctly. The one-time
 * migration (schema version 21) converts every LIVE one it finds into the new shape; this
 * reader is the fallback for whatever that migration cannot see — a merged journal, a restored
 * backup written by an older build — so that a bug in the migration means a row read the old
 * way rather than a row silently misread.
 */
const val TYPE_ENTRY_AMENDED = "entry_amended"

/**
 * "That event should not be there at all" — or, carrying [EntryDeleted.successorUid], "that
 * event is superseded; this other one is what to read instead."
 *
 * The successor to [TYPE_SET_CANCEL] and the same act, with two differences that are the whole
 * point of adding it. It names ANY event — a workout started by accident, an exercise added to
 * the wrong block — where the old one was only ever written against a set. And it names its
 * target by IDENTITY ONLY (see [EntryDeleted]).
 *
 * It applies to itself, which is what makes undoing an undo work: a deletion that has itself
 * been deleted stops hiding anything, and whatever it was hiding comes back — INCLUDING a
 * successor named by it, which stops being current at the same moment and is itself dead again,
 * see [journalView] for the fold that turns a pile of these into an answer.
 */
const val TYPE_ENTRY_DELETED = "entry_deleted"

/**
 * "That EXERCISE should not be there at all." [TYPE_ENTRY_DELETED] one level up: about a
 * catalog row rather than about one journal row.
 *
 * ── Why this is an event and not a second [ExerciseEntity.hidden] ───────────────
 * Hiding already answers "keep this out of the pickers", and it is deliberately not what
 * deletion means (the owner's own words): a hidden exercise's history stays fully readable,
 * everywhere, on purpose. Deletion asks for the opposite — nothing about the exercise readable
 * anywhere, including the history — and the journal already has exactly one mechanism for "this
 * should not be read any more": an event that says so, folded by [journalView]. Adding a second
 * boolean column next to [ExerciseEntity.hidden] would be a second way of hiding something,
 * answering to nobody but itself; this is the first way, pointed at the catalog instead of at
 * one row.
 *
 * ── One event hides the exercise's own row AND every entry about it ─────────────
 * [journalView] does not stop at the catalog row this names. Every set, "added" and "finished"
 * row that names the SAME exercise (by [ExerciseDeleted.link], matched the way
 * [ExerciseLink.matches] always is) folds dead along with it, in the same pass — see the class
 * KDoc there for the cascade. That is what makes this land in "the same filter" every other
 * deletion already goes through, rather than a second mechanism that only the catalog list
 * would have to know about.
 *
 * ── Undone the same way as everything else ───────────────────────────────────────
 * This is a row with a uid like any other, so [TYPE_ENTRY_DELETED] can name IT — there is no
 * dedicated "restore exercise" event, for the same reason there is no dedicated "restore entry"
 * one.
 *
 * Nothing is erased anywhere: the catalog row is untouched forever, and every entry the cascade
 * hides stays exactly as it was written. Only what is read changes.
 */
const val TYPE_EXERCISE_DELETED = "exercise_deleted"

/**
 * Payload keys a legacy PATCH amendment was not allowed to carry — everything that said WHICH
 * thing an event was about, as opposed to what happened.
 *
 * ── Legacy-only, kept for [journalView]'s reading of old [TYPE_ENTRY_AMENDED] rows ─────
 * A full-version correction has nothing of this to protect: [ActivityRepository.amendEntry]
 * writes every field of the new row together, so there is no "the rest of the payload" left
 * unguarded for a stray key to corrupt, and moving a set to another exercise this way is
 * exactly the "delete it and log it again, correctly" act [TYPE_ENTRY_AMENDED]'s own KDoc
 * always asked for — no longer a special case to refuse. This list is therefore no longer
 * consulted anywhere a NEW row is written; it lives on only inside [EntryAmended.allowedFields]
 * and [journalView]'s merge of an old-style patch, so that a journal still carrying one from
 * before this change reads exactly as it always did.
 *
 * The exercise keys are the reason this list existed ([TYPE_ENTRY_AMENDED] explains it). The
 * name keys rode along because they are the exercise said in words, and the `*_key` pair
 * because they are computed from the names and would go stale the moment a name moved without
 * them. The workout keys were the same argument one level up: which workout a block belongs to
 * is not a value of the block.
 */
val AMENDMENT_PROTECTED_KEYS: Set<String> = setOf(
    "exercise_id", "exercise_uid", "exercise", "exercise_key",
    "activity", "activity_key",
    "workout_id", "workout_uid",
)

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
 * Which side of the body a set was done with, for work trained ONE LIMB AT A TIME.
 *
 * ── Why this is worth a field of its own ────────────────────────────────────────
 * A climber on a fingerboard hangs one arm at a time, and the ASYMMETRY is the thing the
 * work is for: the weaker hand is what the session is about, and the number that matters is
 * how far apart the two are. The same is true off the fingerboard — a one-arm row, a pistol
 * squat, a single-leg deadlift — and folding both sides into one history answers the wrong
 * question everywhere it happens: it reports the stronger side and hides the gap that the
 * training exists to close.
 *
 * So a record is per (exercise, side): the left side has its own best and the right side has
 * its own, and neither can take the other's — see [LoadedSet.sideOf].
 *
 * ── The side is on the SET, the one-sidedness is on the EXERCISE ────────────────
 * Which side this particular set was done with is a fact about the set. Whether the exercise
 * is done one limb at a time is a fact about the exercise, and it has to be knowable BEFORE a
 * set exists — see [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.oneSided].
 *
 * The codes are the strings stored in the payload; nothing else may be stored there. A value
 * that is neither is a corrupt row and is refused by both forms that carry it
 * ([LoadedSet.side]), which costs that row (the readers skip what will not parse — see
 * [formFromEventOrNull]). That is deliberate: quietly accepting an unknown side would file it
 * with the sets that named no side at all, which is the one answer that is certainly wrong.
 */
enum class HoldSide(val code: String) {
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun fromCode(code: String?): HoldSide? = entries.firstOrNull { it.code == code }
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

/**
 * A load the user actually stated: any non-zero finite number, sign included.
 *
 * This is the validator for ADDED WEIGHT and for nothing else. A negative value is
 * legitimate there and means the opposite of what the field's name suggests — see
 * [StrengthSet.addedKg] for the sign convention and why the field is not split in two.
 *
 * ZERO IS STILL REFUSED, exactly as [requirePosOrNull] refused it. "I added nothing" is
 * said by leaving the field out, and a stored zero would be a second way of saying the same
 * thing that every reader would then have to remember to treat as absent.
 *
 * The finiteness check is not decoration. `> 0` used to reject a NaN by accident, since
 * every comparison against one is false; `!= 0.0` accepts it, and a NaN in the journal
 * poisons every maximum and every sum it reaches.
 */
private fun requireNonZeroOrNull(name: String, v: Double?): Double? {
    if (v == null) return null
    require(v.isFinite()) { "$name: expected a finite number or null, got $v" }
    require(v != 0.0) { "$name: expected a non-zero number or null, got $v" }
    return v
}

private fun requireKey(name: String, raw: String): String =
    normPhrase(raw) ?: throw IllegalArgumentException("$name: name is empty after normalization ($raw)")

/**
 * Validates [LoadedSet.side]: one of [HoldSide]'s codes, or null. Shared by every form that
 * carries the field, so the two cannot drift into accepting different strings.
 */
private fun requireSideOrNull(side: String?): String? {
    require(side == null || HoldSide.fromCode(side) != null) {
        "side: expected one of ${HoldSide.entries.joinToString { it.code }} or null, got $side"
    }
    return side
}

/** Common interface of all forms: event type plus the activity date. */
sealed interface ActivityForm {
    val type: String
    val opDate: String

    /** Normalized exercise/activity key; body weight has none. */
    val key: String? get() = null

    /** Id of the canonical catalog exercise (§11); null for body weight and legacy records. */
    val exerciseId: Long? get() = null

    /**
     * IDENTITY of the canonical catalog exercise — the same link as [exerciseId], said in the
     * form that means something off this phone (schema version 10).
     *
     * Null for body weight (which names no exercise at all, by design) and for entries written
     * before version 10 that the migration could not resolve, which is exactly the set of
     * entries pointing at a catalog row that no longer exists.
     *
     * Never read directly: [exerciseLink] is the one funnel that decides which of the two a
     * given entry is judged by.
     */
    val exerciseUid: String? get() = null
}

/**
 * Common interface of the two forms that carry a WEIGHT — [StrengthSet] and [HoldSet]. The five
 * members below are identical in name and type on both (see each field's own doc, on either
 * form, for what it means); this interface exists so that code which only cares "is this set
 * loaded, and if so how much" can match ONE branch instead of two hand-paired `is StrengthSet,
 * is HoldSet ->` branches. The payoff is at the type checker: a future change to the weight
 * model that touches this interface turns every one of those call sites into a compile error
 * instead of a branch nobody remembered to update.
 */
sealed interface LoadedSet : ActivityForm {
    val addedKg: Double?
    val ownWeight: Boolean
    val bodyweightKg: Double?
    val warmup: Boolean
    val restAfterSec: Double?

    /**
     * Whether the set was NOT carried through as the working weight demanded — the hang held
     * for five seconds of a seven-second protocol, the last two reps of five never happened,
     * yet the weight on the bar is exactly what it says. See [StrengthSet.incomplete] for the
     * full story; declared here so both loaded forms carry it under one name.
     */
    val incomplete: Boolean

    /**
     * Which side of the body this set was done with — one of [HoldSide]'s codes, or null for
     * two-limbed work, or for a set that failed to say. See [HoldSide] for why this exists.
     *
     * USED TO LIVE ON [HoldSet] ALONE, because every one-sided exercise this app tracked
     * started life on a fingerboard. That was never a decision that a pistol squat or a
     * one-arm row do not have a side — nobody had asked the question yet. The mechanism this
     * leans on (two workout cards, a rest floor per card, a record per side) was already
     * general; only the field was narrower than it needed to be.
     *
     * A String rather than the enum because the payload is the stored format and it stays
     * readable by anything that does not know this app's Kotlin; [sideOf] is how the domain
     * asks the question.
     */
    val side: String?

    /** The side as the domain compares it; null both for "both sides" and for "not said". */
    val sideOf: HoldSide? get() = HoldSide.fromCode(side)
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
    /**
     * Weight carried ON TOP of your own body weight — and, when NEGATIVE, weight taken off it.
     *
     * ── The sign ───────────────────────────────────────────────────────────────
     * A minus means the load was REDUCED: a band, a counterweight or an assisted-pull-up
     * machine holding part of you up. "Pull-ups, -20 kg" is twenty kilograms of help, and it
     * is an ordinary way to train a pull-up nobody can do yet, not a data error.
     *
     * ONE SIGNED FIELD RATHER THAN TWO, and that is the decision worth defending. Assistance
     * and added weight are the same axis of the same exercise: a lifter works up through
     * -20, -10, 0 and out the other side to +5, and a separate `assistance_kg` column would
     * cut that one progression in half — two charts, two records, and a personal best that
     * resets on the day the band comes off. Anything comparing added weights therefore works
     * on negatives unchanged: -10 beats -15 because needing less help IS the improvement.
     *
     * Zero is not storable (see [requireNonZeroOrNull]): a clean body-weight set says so by
     * leaving the field out.
     */
    @SerialName("added_kg") override val addedKg: Double? = null,
    @SerialName("own_weight") override val ownWeight: Boolean = false,
    /**
     * WHAT YOU WEIGHED when this set was recorded, in kilograms, or null when nothing was
     * known — a snapshot taken from the last weigh-in on or before [opDate].
     *
     * ── Why the set carries the number instead of pointing at the scales ────────
     * Without it a body-weight set has no volume at all: a week of pull-ups shows up on the
     * tonnage chart as a week of doing nothing. With it, and with a share stated on the
     * exercise, the set is worth `share x body weight + added weight` per rep.
     *
     * A SNAPSHOT AND NOT A LOOKUP, which is the whole point. Reading the current weight at
     * chart-draw time would let every new weigh-in rewrite the past: lose three kilograms and
     * last year's pull-ups get cheaper overnight, on a chart whose whole job is to say
     * whether last year was harder than this one. What you lifted on a day is what you
     * weighed on that day, and that is a fact, so it is stored like one.
     *
     * Null stays legal and means the honest thing: nobody had stepped on the scales by then.
     * Such a set contributes nothing to tonnage, exactly as it did before this field existed.
     */
    @SerialName("bodyweight_kg") override val bodyweightKg: Double? = null,
    /**
     * Whether this was a WARM-UP set rather than a working one.
     *
     * ── What it changes, and what it deliberately does not ──────────────────────
     * A warm-up is excluded from VOLUME and from RECORDS, and from nothing else. The empty
     * bar is not competing with the working set for a personal best, and counting it into
     * the tonnage inflates a week of training with weight that was never the point.
     *
     * It stays in the day's feed and it stays in the count of ACTIVE DAYS (see
     * [xyz.oleolegka.gachimuchi.domain.activeDays]). Warming up IS training — a day spent
     * ramping up and then failing the working weight is not a day off, and a streak that
     * breaks over it would be lying about what happened.
     *
     * Defaulted to false, so every entry written before this field existed reads as a
     * working set — which is what it was, since there was no way to say otherwise.
     */
    @SerialName("warmup") override val warmup: Boolean = false,
    /**
     * Whether this set fell short of what it was attempted at — the reps were not all gotten,
     * at the same weight the set is otherwise recorded with.
     *
     * ── The app cannot tell this on its own, so it is asked ─────────────────────
     * The timer, the counter, the stepper — none of them know whether the last rep actually
     * locked out or was let go halfway. Only the lifter does, which is why this is a flag
     * SET BY HAND on the entry card (owner: "я просто хочу некую плашку, справился ли я с
     * упражнением или нет") and never inferred from anything else the app already tracks.
     *
     * ── What it changes, and what it deliberately does not ──────────────────────
     * Modelled exactly on [warmup]'s own split, with the axis it excludes turned around: an
     * incomplete set is kept OUT of records and stays IN volume and time under tension. The
     * weight was genuinely hung on the bar and the effort was genuinely spent — "the work got
     * done" is true regardless — but a rep count or a hold time that fell short of the target
     * must not become the number the app tells the lifter to beat, or a set that was a defeat
     * quietly starts reading as a personal best next time the exercise comes up. It also has
     * no bearing on [warmup]'s own two effects (active days, the feed) — the two flags answer
     * different questions and a set can carry either, both or neither.
     *
     * Defaulted to false, so every entry written before this field existed reads as having
     * carried the set through — there was no way to say otherwise, and the honest default is
     * "no mark", not "failed" (see [xyz.oleolegka.gachimuchi.domain.evaluateStrengthRecord]).
     */
    @SerialName("incomplete") override val incomplete: Boolean = false,
    /**
     * Which side this set was done with, for an exercise trained one limb at a time — a
     * pistol squat, a one-arm row, a single-leg deadlift. See [LoadedSet.side].
     */
    @SerialName("side") override val side: String? = null,
    @SerialName("exercise_id") override val exerciseId: Long? = null,
    @SerialName("exercise_uid") override val exerciseUid: String? = null,
    @SerialName("rest_after_sec") override val restAfterSec: Double? = null,
    @SerialName("op_date") override val opDate: String,
    @SerialName("exercise_key") val exerciseKey: String = requireKey("exercise", exercise),
) : LoadedSet {
    override val type: String get() = TYPE_STRENGTH_SET
    override val key: String get() = exerciseKey

    init {
        requirePos("reps", reps)
        requirePosOrNull("weight_kg", weightKg)
        requirePosOrNull("bodyweight_kg", bodyweightKg)
        requireNonZeroOrNull("added_kg", addedKg)
        requireSideOrNull(side)
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
 * §12-A (SUPERSEDES form 2 of §3): hangboard identity = name + PROTOCOL (work:rest), so
 * "Hangs" at 7:3 and "Hangs" at 10:5 are DIFFERENT catalog exercises, and the tracked
 * variable — the personal record — is ADDED WEIGHT ([addedKg]). The protocol therefore
 * lives as an attribute of the exercise ([xyz.oleolegka.gachimuchi.data.db.ExerciseEntity]).
 *
 * The [workSec]/[restSec] fields are KEPT here as a snapshot of the exercise's protocol at
 * the time of the set — exactly the payload keys the Python bot writes (otherwise the two
 * journals would drift apart). Identity is NOT derived from them: it comes from
 * [exerciseId]. If an exercise's protocol is ever corrected, the snapshot in old events
 * still shows how it was recorded back then.
 *
 * ── `edge_mm` used to live here too, and does not any more ──────────────────────
 * The hangboard edge (millimetres) was a climbing-specific value the app no longer models:
 * it has been folded into the exercise NAME instead (see `MIGRATION_17_18` in
 * `data/db/AppDatabase.kt`), and this class no longer has a field for it. `ignoreUnknownKeys`
 * on [journalFileJson] means an old backup that still carries an `"edge_mm"` key decodes
 * fine — the key is simply dropped on the way in — and this app never writes it again. A
 * separate Python bot is documented to read/write this same payload shape and may still
 * expect the key; that divergence is a known, accepted consequence and is out of scope here.
 *
 * [restAfterSec] is the pause BETWEEN sets; [workSec]/[restSec] are the protocol
 * WITHIN a set. Different quantities, independent of each other.
 *
 * [addedKg] IS SIGNED, and on a hangboard the negative half of the axis is the half most of
 * the training happens on: hanging a one-arm lockoff off a band that takes fifteen kilograms
 * of you is normal work, and it progresses towards zero. The convention and the reasoning are
 * on [StrengthSet.addedKg], which defines it once for both forms.
 */
@Serializable
data class HoldSet(
    @SerialName("activity") val activity: String,
    @SerialName("reps") val reps: Int? = null,
    @SerialName("hold_sec") val holdSec: Double? = null,
    @SerialName("work_sec") val workSec: Double? = null,
    @SerialName("rest_sec") val restSec: Double? = null,
    @SerialName("added_kg") override val addedKg: Double? = null,
    @SerialName("own_weight") override val ownWeight: Boolean = false,
    /** What you weighed when this hang was recorded — see [StrengthSet.bodyweightKg]. */
    @SerialName("bodyweight_kg") override val bodyweightKg: Double? = null,
    /** A ramp-up hang rather than a working one — see [StrengthSet.warmup]. */
    @SerialName("warmup") override val warmup: Boolean = false,
    /**
     * The hang that did not go the distance — held for less than [workSec] said, or short on
     * reps of the protocol — at an added weight that is otherwise recorded exactly as hung.
     * See [StrengthSet.incomplete] for the full story (this is the very case it was written
     * for: "провисел не 7 секунд, а смог только 5").
     */
    @SerialName("incomplete") override val incomplete: Boolean = false,
    /**
     * Which hand (or foot) this set was done with — see [LoadedSet.side].
     *
     * Null is the ordinary answer for two-handed work and the ordinary answer for every set
     * written before this field existed. On an exercise marked one-sided it is neither: it is
     * a set that failed to say which hand it was, and the reducers report that rather than
     * guessing (see [xyz.oleolegka.gachimuchi.domain.holdRecord]).
     */
    @SerialName("side") override val side: String? = null,
    @SerialName("exercise_id") override val exerciseId: Long? = null,
    @SerialName("exercise_uid") override val exerciseUid: String? = null,
    @SerialName("rest_after_sec") override val restAfterSec: Double? = null,
    @SerialName("op_date") override val opDate: String,
    @SerialName("activity_key") val activityKey: String = requireKey("activity", activity),
) : LoadedSet {
    override val type: String get() = TYPE_HOLD_SET
    override val key: String get() = activityKey

    init {
        requireSideOrNull(side)
        reps?.let { requirePos("reps", it) }
        requirePosOrNull("hold_sec", holdSec)
        requirePosOrNull("work_sec", workSec)
        requirePosOrNull("rest_sec", restSec)
        requirePosOrNull("bodyweight_kg", bodyweightKg)
        requireNonZeroOrNull("added_kg", addedKg)
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
    @SerialName("exercise_uid") override val exerciseUid: String? = null,
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
    @SerialName("exercise_uid") override val exerciseUid: String? = null,
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
    @SerialName("exercise_uid") override val exerciseUid: String? = null,
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

/**
 * Set reversal — the journal is append-only, so undoing a set is a new event naming the old
 * one rather than a delete.
 *
 * ── Two fields for one link, and the uid is the real one ────────────────────────
 * [cancelsUid] is the identity of the event being reversed ([JournalEvent.uid]); [cancels] is
 * the local row number it used to be said with. Both are written, and the readers believe the
 * uid — a reversal that travelled here from another journal would otherwise cancel whichever
 * unrelated row happened to have that number on this phone, which is a set silently
 * disappearing out of somebody's history.
 *
 * BOTH ARE NULLABLE, which is not sloppiness. A payload written before version 9 has only the
 * number, and one that arrives from a journal with no numbers at all has only the uid; a
 * reversal with NEITHER names nothing and is dropped by [journalView] rather than
 * throwing, on the same grounds as [formFromEventOrNull].
 */
@Serializable
data class SetCancel(
    @SerialName("cancels") val cancels: Long? = null,
    @SerialName("cancels_uid") val cancelsUid: String? = null,
)

/**
 * Payload of [TYPE_ENTRY_AMENDED]: which event, and the fields that were wrong.
 *
 * LEGACY — nothing writes this any more; see [TYPE_ENTRY_AMENDED]'s own KDoc. Kept so
 * [journalView] keeps reading a journal written before the full-version model correctly.
 *
 * ── The target is an identity and nothing else ──────────────────────────────────
 * Unlike [SetCancel] there is no row-number twin here, and that is deliberate rather than an
 * omission. The number exists on the older event because it predates uids and had to keep
 * working for rows written before them; nothing wrote an amendment except this app, and only
 * against a row that certainly had an identity. Offering a number as well would only offer a
 * way to amend the wrong entry on a phone that numbers its journal differently.
 *
 * [fields] is a fragment of the target's own payload — the same keys, the same spellings —
 * carrying only what changed. Keys in [AMENDMENT_PROTECTED_KEYS] are refused; see
 * [TYPE_ENTRY_AMENDED] for why, and [journalView] for how several of these fold together.
 */
@Serializable
data class EntryAmended(
    @SerialName("target_uid") val targetUid: String,
    @SerialName("fields") val fields: JsonObject,
) {
    init {
        require(targetUid.isNotBlank()) { "entry_amended: target_uid must name an event" }
    }

    /** The patch as it is allowed to be applied — see [AMENDMENT_PROTECTED_KEYS]. */
    val allowedFields: Map<String, JsonElement>
        get() = fields.filterKeys { it !in AMENDMENT_PROTECTED_KEYS }
}

/**
 * Payload of [TYPE_ENTRY_DELETED]: which event should stop being read, and — since the
 * full-version model replaced [TYPE_ENTRY_AMENDED] as the way to correct an entry — which one
 * replaces it, if any.
 *
 * By identity only, for the reason spelled out on [EntryAmended]. A deletion naming an event
 * that is not in this journal is inert rather than an error — the row it meant may simply not
 * have arrived yet, and a journal is a stream that can be read halfway.
 *
 * ── [successorUid], and why one event now covers both acts ──────────────────────
 * A plain removal ("this should not be there at all") and a correction ("the old values were
 * wrong; a new, whole row has the right ones") are the SAME fact from a reader's point of
 * view — the old row is no longer the one to read — and differ only in whether something
 * takes its place. So they share this one event: null means removed with nothing to replace
 * it, non-null names the row that does. [xyz.oleolegka.gachimuchi.domain.journalView] treats
 * both as "not alive" identically; the successor link exists purely so that a row can be read
 * as CURRENT or SUPERSEDED on its own, without consulting its neighbours — see
 * `domain/Amendments.kt`'s header for the model this event is half of.
 *
 * A correction no longer patches the original row in place ([EntryAmended] is kept only to
 * read journals written before this change): [ActivityRepository.amendEntry] writes the new,
 * whole row FIRST and then this event naming both, so the pair lands together and a reader
 * that only sees the first half of a partial write finds a row that simply has not been
 * corrected yet, rather than one that vanished.
 */
@Serializable
data class EntryDeleted(
    @SerialName("target_uid") val targetUid: String,
    @SerialName("successor_uid") val successorUid: String? = null,
) {
    init {
        require(targetUid.isNotBlank()) { "entry_deleted: target_uid must name an event" }
    }
}

/**
 * Payload of [TYPE_EXERCISE_DELETED]: which catalog exercise should stop being read anywhere.
 *
 * BOTH links, unlike [EntryDeleted] — the same reasoning [OrderedExercise] and
 * [WorkoutExerciseFinished] already give for carrying id and uid together: an entry written
 * before schema version 10 may name this exercise by its row number alone, with no uid to match
 * against, and it still has to fold dead along with everything logged about the exercise since.
 * Matched through [ExerciseLink.matches] like every other reference to one, never by comparing
 * the raw fields here directly.
 */
@Serializable
data class ExerciseDeleted(
    @SerialName("target_id") val targetId: Long? = null,
    @SerialName("target_uid") val targetUid: String? = null,
) {
    init {
        require(targetId != null || targetUid != null) {
            "exercise_deleted: an entry must name an exercise by uid or by id"
        }
    }
}

/** The two links read as one reference — see [ExerciseLink.matches]. */
fun ExerciseDeleted.link(): ExerciseLink = ExerciseLink(targetUid, targetId)

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
 * ── the plan it was started from ────────────────────────────────────────────────
 * The planned session this workout was started from, when it was started from one, and
 * nothing at all when it was not. Beyond saving a name to type, it is the exact answer to
 * "was the plan kept" — domain/Schedule.kt currently pairs plan with fact HEURISTICALLY, by
 * clock proximity and greedily, which is right most of the time and unfixably wrong when two
 * sessions sit close together on one day. A link the user made themselves needs no guessing.
 *
 * [slotUid] is the identity of the plan row ([xyz.oleolegka.gachimuchi.data.db.SlotEntity.uid])
 * and [slotId] is the local row number it used to be said with. Both are written and the
 * readers believe the uid, for the reason spelled out on [SetCancel]: two phones number their
 * plans independently, so a workout arriving from another journal would otherwise claim
 * whichever unrelated slot happened to hold that number here. Neither is read directly —
 * [Workout.slot] is the one funnel that turns the pair into a [SlotLink].
 *
 * BOTH ARE NULLABLE. A payload written before schema version 11 has only the number, one that
 * arrives from a journal with no numbers at all has only the uid, and a workout started
 * off-plan has neither — which is not a defect but the ordinary case.
 *
 * This IS what the calendar decides by now: domain/Schedule.kt closes a planned session with
 * the workout that names it and consults the clock only for training logged without pressing
 * start. domain/DayCards.kt reads it as well, to keep a plan and the workout started from it
 * from appearing as two cards.
 *
 * ── the name is a SNAPSHOT, not a lookup ────────────────────────────────────────
 * [name] is what this workout was called AT THE MOMENT IT WAS STARTED: copied off the plan
 * when it was started from one, typed in when the user named it themselves, and absent when
 * nobody named it (the card then shows the time of day, and a workout has never needed a name
 * to exist — §13).
 *
 * It used to be read from the plan live, every time a card was drawn, which meant renaming a
 * slot renamed every workout ever started from it, back through the whole history. That is
 * the one thing this journal is not allowed to do: THE PLAN IS EDITABLE AND THE FACTS ARE
 * NOT, and what a session was called on the day is a fact about that day. The link to the plan
 * stays, and it is now only a link — it answers "which plan was this", never "what is this
 * called".
 *
 * The cost, stated: a workout started before schema version 12 arrived here from another
 * journal carries no snapshot, and this app will not invent one for it from the plan. It is
 * shown by its time instead. The 11 -> 12 migration fills in the snapshot for the rows THIS
 * phone wrote, which is every row it can honestly say anything about.
 */
@Serializable
data class WorkoutStarted(
    @SerialName("op_date") val opDate: String,
    @SerialName("slot_id") val slotId: Long? = null,
    @SerialName("slot_uid") val slotUid: String? = null,
    @SerialName("name") val name: String? = null,
) {
    init {
        requireIsoDate(opDate)
    }
}

/**
 * Payload of [TYPE_WORKOUT_EXERCISE_ADDED].
 *
 * ── The workout is named twice, in the payload and in a column ──────────────────
 * The columns (`workout_id` since schema version 5, `workout_uid` since 9) are how this app
 * finds the rows of a workout in one query. The payload says the same thing again so that the
 * event is COMPLETE ON ITS OWN — an exported or merged journal is a stream of events, not a
 * table carrying this app's columns, and a row arriving without them still has to land in the
 * right workout.
 *
 * [workoutUid] is the identity; [workoutId] is the local row number it replaced and means
 * nothing off the phone that wrote it. Reads go through
 * [xyz.oleolegka.gachimuchi.domain.workoutRef], which consults the columns, then the uid here,
 * then the number — in ONE place, so that no reader gets to pick its own order.
 */
@Serializable
data class WorkoutExerciseAdded(
    @SerialName("workout_id") val workoutId: Long,
    @SerialName("exercise_id") val exerciseId: Long,
    @SerialName("rest_sec") val restSec: Int,
    @SerialName("workout_uid") val workoutUid: String? = null,
    /** Identity of the exercise being added — the same link as [exerciseId]. */
    @SerialName("exercise_uid") val exerciseUid: String? = null,
    /**
     * Which card this is, for an exercise trained one limb at a time
     * ([xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.oneSided]) — one of [HoldSide]'s codes.
     *
     * An exercise flagged one-sided gets TWO of these rows when it is put into a workout, one
     * per side, so that the workout carries two independent cards rather than one that asks
     * mid-set which hand a set belongs to. Null is the ordinary answer for every exercise that
     * is not one-sided, and also for every row written before this field existed — a workout
     * folded out of an old journal simply has one card for such an exercise, exactly as it
     * always did.
     */
    @SerialName("side") val side: String? = null,
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
