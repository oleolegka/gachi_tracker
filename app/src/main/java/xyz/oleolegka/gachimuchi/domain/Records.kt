package xyz.oleolegka.gachimuchi.domain

import kotlin.math.roundToInt

/**
 * Personal record detection — a port of `bot/records.py` (decisions.md §5) with the
 * §12-A amendment.
 *
 * A record is NOT a stored field but a REDUCER over the journal: pure functions compare
 * a new set against the PREVIOUS sets of the same exercise (aggregated by exercise_id,
 * which merges aliases). The database schema is untouched by any of this.
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
 */
fun evaluateStrengthRecord(priorSets: List<StrengthSet>, weight: Double?, reps: Int?): RecordHit? {
    if (weight == null || reps == null) return null
    val weighted = priorSets.filter { it.weightKg != null }
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
    val priorWeights = priorHolds.mapNotNull { it.addedKg }
    val priorSeconds = priorHolds.mapNotNull { it.holdSec }

    val weight = hold.addedKg
    if (weight != null && priorWeights.isNotEmpty() && weight > priorWeights.max()) {
        val prev = priorWeights.max()
        return RecordHit(
            RecordHit.Axis.HOLD_WEIGHT, weight, prev,
            "added weight ${fmtNum(weight)} kg (was ${fmtNum(prev)})",
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
    val exerciseId: Long,
    val axis: RecordHit.Axis,
    val value: Double,
    val opDate: String,
    val text: String,
)

/**
 * The record of a strength exercise over the whole history: the maximum estimated 1RM
 * and the date it was set (§12-C: a record ALWAYS comes with a date). Returns null if
 * there were no weighted sets.
 */
fun strengthRecord(sets: List<ActivityEvent>, exerciseId: Long): ExerciseRecord? {
    val weighted = sets.mapNotNull { ev ->
        (ev.form as? StrengthSet)?.takeIf { it.exerciseId == exerciseId && it.weightKg != null }
            ?.let { it to ev.opDate }
    }
    if (weighted.isEmpty()) return null
    val (best, day) = weighted.maxBy { est1rm(it.first.weightKg!!, it.first.reps) }
    val value = est1rm(best.weightKg!!, best.reps)
    return ExerciseRecord(
        exerciseId, RecordHit.Axis.EST_1RM, value, day,
        "1RM ${fmtNum(value)} kg (${fmtNum(best.weightKg)}×${best.reps})",
    )
}

/**
 * The record of a hold exercise over the whole history: MAXIMUM ADDED WEIGHT (§12-A)
 * and its date. If the history carries no weight at all (a plank), the maximum hold in
 * seconds is used instead.
 */
fun holdRecord(sets: List<ActivityEvent>, exerciseId: Long): ExerciseRecord? {
    val mine = sets.mapNotNull { ev ->
        (ev.form as? HoldSet)?.takeIf { it.exerciseId == exerciseId }?.let { it to ev.opDate }
    }
    if (mine.isEmpty()) return null
    val withWeight = mine.filter { it.first.addedKg != null }
    if (withWeight.isNotEmpty()) {
        val (best, day) = withWeight.maxBy { it.first.addedKg!! }
        return ExerciseRecord(
            exerciseId, RecordHit.Axis.HOLD_WEIGHT, best.addedKg!!, day,
            "added weight ${fmtNum(best.addedKg)} kg",
        )
    }
    val withSeconds = mine.filter { it.first.holdSec != null }
    if (withSeconds.isEmpty()) return null
    val (best, day) = withSeconds.maxBy { it.first.holdSec!! }
    return ExerciseRecord(
        exerciseId, RecordHit.Axis.HOLD_SECONDS, best.holdSec!!, day,
        "hold ${fmtNum(best.holdSec)} s",
    )
}
