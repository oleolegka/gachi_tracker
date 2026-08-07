package xyz.oleolegka.gachimuchi.domain

import kotlin.math.roundToInt

/**
 * Personal record detection — a port of `bot/records.py` (decisions.md §5) with the
 * §12-A amendment.
 *
 * A record is NOT a stored field but a REDUCER over the journal: pure functions compare
 * a new set against the PREVIOUS sets of the same exercise (aggregated by exercise_id, not
 * by the name written on the entry). The database schema is untouched by any of this.
 *
 * WHAT IS COMPUTED:
 * - strength: (a) estimated 1RM by the Epley formula (max est1rm) and (b) the best
 *   weight at THAT SAME rep count. Strictly greater than the previous best, otherwise
 *   it is not a record. The first weighted set of an exercise is a baseline, not a
 *   record (no need to be noisy);
 * - holds: §12-A — edge and protocol are fixed by the exercise itself, so the tracked
 *   variable and the record are ADDED WEIGHT ([HoldSet.addedKg]). The "hung longer"
 *   axis ([HoldSet.holdSec]) is kept as a fallback for unweighted holds (a plank),
 *   where the weight never changes.
 *
 * WARM-UPS TAKE NO PART IN ANY OF IT. A set marked [StrengthSet.warmup] is neither judged
 * as a record nor allowed to be the thing a record is measured against, on both sides of
 * every comparison here. The empty bar must not hold the personal best, and it must not
 * become the baseline that silences the first real set either.
 *
 * KNOWN SIMPLIFICATIONS (no whitewashing):
 * - strength records are computed ONLY for sets with an absolute weight. Body-weight
 *   and body-weight-plus-added sets take no part in the strength comparison — max reps
 *   at body weight (mentioned in §5) is NOT implemented here, such a set simply yields
 *   no note;
 * - cardio (best pace / farther / longer) and duration are NOT implemented, same as in
 *   the Python version;
 * - axis (b) for strength and both hold axes only fire when a comparable set exists
 *   AMONG the previous ones. The first appearance of a metric is a baseline, not a
 *   "record over nothing".
 */

/** Number for a record phrase: rounded to 0.1 with the trailing zero trimmed. */
internal fun fmtNum(x: Double): String {
    val r = (x * 10).roundToInt() / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

/**
 * How one added-weight number reads in a record phrase.
 *
 * A negative added weight is ASSISTANCE (see [StrengthSet.addedKg]), and "added weight
 * -20 kg" hands the reader a sign to work out at the moment they are least inclined to. The
 * sign stays in the record's VALUE, where anything comparing records needs it; only the
 * sentence is turned around.
 */
internal fun addedWeightPhrase(kg: Double): String = when {
    kg < 0 -> "assistance ${fmtNum(-kg)} kg"
    // reachable only in a history that also carries assistance: an unassisted hold is stored
    // as no added weight at all, and it enters the axis as a zero (see [evaluateHoldRecord])
    kg == 0.0 -> "no assistance"
    else -> "added weight ${fmtNum(kg)} kg"
}

/**
 * "This much now, that much before" for the added-weight axis.
 *
 * Two numbers of the SAME sign are phrased once and compared bare, which is both shorter and
 * how the app has always read; a pair that straddles zero is spelled out on both sides,
 * because "added weight 2 kg (was 5)" would be a flat lie about a lifter who has just come
 * off the band.
 */
internal fun addedWeightRecordText(now: Double, previous: Double): String = when {
    now >= 0 && previous >= 0 -> "added weight ${fmtNum(now)} kg (was ${fmtNum(previous)})"
    now < 0 && previous < 0 -> "assistance ${fmtNum(-now)} kg (was ${fmtNum(-previous)})"
    else -> "${addedWeightPhrase(now)} (was ${addedWeightPhrase(previous)})"
}

/**
 * Estimated 1RM by Epley: `weight * (1 + reps/30)`. A practical signal of strength
 * progress without ever testing a true one-rep max (decisions.md §5).
 */
fun est1rm(weight: Double, reps: Int): Double = weight * (1 + reps / 30.0)

/** A broken record: a short phrase for the UI plus the machine-readable axis and numbers. */
data class RecordHit(
    val axis: Axis,
    val value: Double,
    val previous: Double,
    val text: String,
) {
    enum class Axis { EST_1RM, WEIGHT_AT_REPS, HOLD_WEIGHT, HOLD_SECONDS }
}

/**
 * Whether a new set (weight x reps) breaks a strength record relative to the PREVIOUS
 * sets of the same exercise (taken as of before the write). The 1RM axis wins — one
 * note per set. Returns null if no axis was broken or if there are no weighted sets
 * among the previous ones.
 *
 * [warmup] describes the NEW set and defaults to false. It is a separate parameter rather
 * than a whole [StrengthSet] because the set being judged has never been passed as one here
 * — the caller holds a weight and a rep count typed into a card, and may be judging a set
 * that has not been written yet.
 */
fun evaluateStrengthRecord(
    priorSets: List<StrengthSet>,
    weight: Double?,
    reps: Int?,
    warmup: Boolean = false,
): RecordHit? {
    if (weight == null || reps == null) return null
    if (warmup) return null
    val weighted = priorSets.filter { !it.warmup && it.weightKg != null }
    if (weighted.isEmpty()) return null // the first weighted set is a baseline, stay quiet

    val new1rm = est1rm(weight, reps)
    val best1rm = weighted.maxOf { est1rm(it.weightKg!!, it.reps) }
    if (new1rm > best1rm) {
        return RecordHit(
            RecordHit.Axis.EST_1RM, new1rm, best1rm,
            "estimated 1RM ${fmtNum(new1rm)} kg (was ${fmtNum(best1rm)})",
        )
    }
    val sameReps = weighted.filter { it.reps == reps }.mapNotNull { it.weightKg }
    if (sameReps.isNotEmpty()) {
        val bestAtReps = sameReps.max()
        if (weight > bestAtReps) {
            return RecordHit(
                RecordHit.Axis.WEIGHT_AT_REPS, weight, bestAtReps,
                "best weight at $reps reps: ${fmtNum(weight)} kg (was ${fmtNum(bestAtReps)})",
            )
        }
    }
    return null
}

/**
 * Whether a new hold set breaks a record relative to the PREVIOUS sets of THE SAME
 * exercise (§12-A: one exercise = one edge and one protocol, which makes comparing
 * weights honest). Added weight wins; the seconds axis is for unweighted holds.
 */
fun evaluateHoldRecord(priorHolds: List<HoldSet>, hold: HoldSet): RecordHit? {
    if (hold.warmup) return null
    // ONE HAND'S HISTORY IS THE ONLY THING THIS HAND COMPETES WITH. On a fingerboard the two
    // sides are years apart in strength, and comparing across them would mean the weaker hand
    // never breaks a record while the stronger one breaks every one it is told about.
    val working = priorHolds.filter { !it.warmup && it.sideOf == hold.sideOf }
    val priorSeconds = working.mapNotNull { it.holdSec }

    // A hold with nothing added is a real point on the added-weight axis, at zero, and it has
    // to be counted as one now that the axis has a NEGATIVE half: hanging clean beats hanging
    // off a band, so a history of assisted hangs must not let the next assisted hang be
    // announced as a record over a day the band was not needed.
    //
    // Gated on somebody having stated an added weight at all, because otherwise every plank
    // in the journal would join an axis it has no business on and the seconds fallback below
    // — the only axis an unweighted hold has — would never be reached.
    val weighedAxis = hold.addedKg != null || working.any { it.addedKg != null }
    val priorWeights = if (weighedAxis) working.map { it.addedKg ?: 0.0 } else emptyList()

    val weight = if (weighedAxis) hold.addedKg ?: 0.0 else null
    if (weight != null && priorWeights.isNotEmpty() && weight > priorWeights.max()) {
        val prev = priorWeights.max()
        return RecordHit(
            RecordHit.Axis.HOLD_WEIGHT, weight, prev,
            addedWeightRecordText(weight, prev),
        )
    }
    val seconds = hold.holdSec
    if (seconds != null && priorSeconds.isNotEmpty() && seconds > priorSeconds.max()) {
        val prev = priorSeconds.max()
        return RecordHit(
            RecordHit.Axis.HOLD_SECONDS, seconds, prev,
            "hold ${fmtNum(seconds)} s (was ${fmtNum(prev)})",
        )
    }
    return null
}

/** The current record of an exercise — a reducer over the WHOLE history (for dashboards, not for "just broke it"). */
data class ExerciseRecord(
    val exercise: ExerciseLink,
    val axis: RecordHit.Axis,
    val value: Double,
    val opDate: String,
    val text: String,
    /**
     * Which side this record belongs to, for work trained one limb at a time ([HoldSide]).
     *
     * Null means the record covers the exercise as a whole — either because it is two-handed
     * work, or because the sets it was computed from named no side. Those two are told apart
     * by [sideMissing], not by this field.
     */
    val side: HoldSide? = null,
    /**
     * THE SETS BEHIND THIS RECORD SHOULD HAVE NAMED A SIDE AND DID NOT.
     *
     * A defect in the data, reported rather than papered over. It means one of two things,
     * and both are the same mistake: the exercise is marked one-sided and some of its sets
     * predate that (or were logged without picking a hand), or the exercise is not marked
     * one-sided yet some of its sets do name a hand.
     *
     * The honest reading of such a record is "this is the best of some sets, and nobody knows
     * which hand did them" — which is emphatically NOT "the best of both hands together". A
     * screen showing this flag should say so; silently folding these sets in with the
     * two-handed ones would let a left-hand best be reported as the exercise's best.
     */
    val sideMissing: Boolean = false,
)

/**
 * The record of a strength exercise over the whole history: the maximum estimated 1RM
 * and the date it was set (§12-C: a record ALWAYS comes with a date). Returns null if
 * there were no weighted sets.
 */
fun strengthRecord(sets: List<ActivityEvent>, exercise: ExerciseLink): ExerciseRecord? {
    val weighted = sets.mapNotNull { ev ->
        (ev.form as? StrengthSet)
            ?.takeIf { it.exerciseLink()?.matches(exercise) == true && it.weightKg != null && !it.warmup }
            ?.let { it to ev.opDate }
    }
    if (weighted.isEmpty()) return null
    val (best, day) = weighted.maxBy { est1rm(it.first.weightKg!!, it.first.reps) }
    val value = est1rm(best.weightKg!!, best.reps)
    return ExerciseRecord(
        exercise, RecordHit.Axis.EST_1RM, value, day,
        "1RM ${fmtNum(value)} kg (${fmtNum(best.weightKg)}×${best.reps})",
    )
}

/**
 * The records of a hold exercise over the whole history: MAXIMUM ADDED WEIGHT (§12-A) and
 * its date, or the longest hold in seconds where the history carries no weight at all.
 *
 * ── A LIST, because one exercise can hold more than one record ──────────────────
 * Work done one limb at a time has a record PER SIDE: the left hand's best added weight is
 * not in competition with the right hand's, and merging them reports the stronger hand as
 * though it were the exercise. So the answer is one record per side that was trained, in a
 * fixed order (left, right, then the sets that named no side).
 *
 * An exercise with no sides anywhere in its history and [oneSided] false gets exactly one
 * record, as it always did — that is the two-handed case and it is the common one.
 *
 * ── The defect case is reported, not resolved ───────────────────────────────────
 * [oneSided] is what the CATALOG says about the exercise; the sides are what the SETS say.
 * When the two disagree — a one-sided exercise with sets that named no hand, or a two-handed
 * exercise with sets that did — the sets that named nothing get their own record, flagged
 * with [ExerciseRecord.sideMissing]. They are not folded into either hand and they are not
 * called "both": nobody knows which hand did them, and inventing an answer here would put a
 * left-hand best on the exercise as a whole.
 */
fun holdRecord(
    sets: List<ActivityEvent>,
    exercise: ExerciseLink,
    oneSided: Boolean = false,
): List<ExerciseRecord> {
    val mine = sets.mapNotNull { ev ->
        (ev.form as? HoldSet)?.takeIf { it.exerciseLink()?.matches(exercise) == true && !it.warmup }
            ?.let { it to ev.opDate }
    }
    if (mine.isEmpty()) return emptyList()

    // the history is judged per side as soon as EITHER source says sides exist here: the
    // catalog flag, or a set that named one. A flag set today must split a history logged
    // before it, and a side logged on an exercise nobody has flagged is still a side.
    val bySide = mine.any { it.first.sideOf != null } || oneSided
    if (!bySide) return listOfNotNull(holdRecordOf(mine, exercise, side = null, sideMissing = false))

    val buckets = mine.groupBy { it.first.sideOf }
    // a fixed order, with the sets that named nothing last: a screen showing these should not
    // have the defect at the top, and it must not have it silently first among equals either
    val order: List<HoldSide?> = listOf(HoldSide.LEFT, HoldSide.RIGHT, null)
    return order.mapNotNull { side ->
        val ofSide = buckets[side] ?: return@mapNotNull null
        holdRecordOf(ofSide, exercise, side = side, sideMissing = side == null)
    }
}

/** One side's (or the whole exercise's) best hold: the added-weight axis, else seconds. */
private fun holdRecordOf(
    mine: List<Pair<HoldSet, String>>,
    exercise: ExerciseLink,
    side: HoldSide?,
    sideMissing: Boolean,
): ExerciseRecord? {
    fun label(text: String): String = when {
        sideMissing -> "$text (side not recorded)"
        side != null -> "$text (${side.code})"
        else -> text
    }

    if (mine.any { it.first.addedKg != null }) {
        // every hold of the exercise competes, the clean ones at zero — see [evaluateHoldRecord]
        // for why a null has to be a point on this axis and not an absence from it
        val (best, day) = mine.maxBy { it.first.addedKg ?: 0.0 }
        val value = best.addedKg ?: 0.0
        return ExerciseRecord(
            exercise, RecordHit.Axis.HOLD_WEIGHT, value, day,
            label(addedWeightPhrase(value)), side, sideMissing,
        )
    }
    val withSeconds = mine.filter { it.first.holdSec != null }
    if (withSeconds.isEmpty()) return null
    val (best, day) = withSeconds.maxBy { it.first.holdSec!! }
    return ExerciseRecord(
        exercise, RecordHit.Axis.HOLD_SECONDS, best.holdSec!!, day,
        label("hold ${fmtNum(best.holdSec)} s"), side, sideMissing,
    )
}
