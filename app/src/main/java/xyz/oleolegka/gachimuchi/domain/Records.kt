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
 * - holds: §12-A — the protocol is fixed by the exercise itself, so the tracked
 *   variable and the record are ADDED WEIGHT ([HoldSet.addedKg]). The "hung longer"
 *   axis ([HoldSet.holdSec]) is kept as a fallback for unweighted holds (a plank),
 *   where the weight never changes.
 *
 * WARM-UPS TAKE NO PART IN ANY OF IT. A set marked [StrengthSet.warmup] is neither judged
 * as a record nor allowed to be the thing a record is measured against, on both sides of
 * every comparison here. The empty bar must not hold the personal best, and it must not
 * become the baseline that silences the first real set either.
 *
 * NEITHER DO SETS MARKED [StrengthSet.incomplete] — same exclusion, same both sides, for a
 * different reason: the weight was real, but it was not actually carried through, and a
 * record set by a rep that was not gotten or a hang that was let go early would tell the
 * lifter to chase a number they never actually held.
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

/**
 * Whether an earlier set is part of [judged]'s history — the live counterpart of [sideGroups],
 * kept next to it so the toast that announces a record and the card that displays one cannot
 * answer differently.
 *
 * Same side, obviously. Plus: a set that named NO side is work both sides did equally, so it
 * belongs to each hand's history — which is what stops "you broke your left-hand record" from
 * firing over a number the records card already credits to the left hand.
 *
 * NOT the other way round. A set that names no side is judged only against sets that name none
 * either: the whole point of one-sided work is that a hand is not the pair, and letting a
 * single strong hand set the bar for two-handed work would silence every two-handed record.
 */
private fun sharesSide(prior: HoldSide?, judged: HoldSide?): Boolean =
    prior == judged || (judged != null && prior == null)

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
 * [warmup] and [incomplete] describe the NEW set and both default to false. They are separate
 * parameters rather than a whole [StrengthSet] because the set being judged has never been
 * passed as one here — the caller holds a weight and a rep count typed into a card, and may be
 * judging a set that has not been written yet.
 *
 * [side] narrows the comparison to the SAME side, on the same grounds [evaluateHoldRecord]
 * already stands on: a pistol squat's two legs diverge in strength exactly the way a
 * fingerboard's two hands do, and comparing a new right-side set against the left side's
 * history would report a record the right side never actually broke. Null (the default) is a
 * set that named no side, which is every set of an exercise that is not one-sided. Which
 * earlier sets count as this side's history is [sharesSide] and nothing here.
 */
fun evaluateStrengthRecord(
    priorSets: List<StrengthSet>,
    weight: Double?,
    reps: Int?,
    warmup: Boolean = false,
    side: HoldSide? = null,
    incomplete: Boolean = false,
): RecordHit? {
    if (weight == null || reps == null) return null
    if (warmup || incomplete) return null
    val weighted = priorSets.filter {
        !it.warmup && !it.incomplete && it.weightKg != null && sharesSide(it.sideOf, side)
    }
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
 * exercise (§12-A: one exercise = one protocol, which makes comparing weights honest).
 * Added weight wins; the seconds axis is for unweighted holds.
 */
fun evaluateHoldRecord(priorHolds: List<HoldSet>, hold: HoldSet): RecordHit? {
    if (hold.warmup || hold.incomplete) return null
    // THE OTHER HAND'S HISTORY IS NOT THIS HAND'S. On a fingerboard the two sides are years
    // apart in strength, and comparing across them would mean the weaker hand never breaks a
    // record while the stronger one breaks every one it is told about. Sets that named no hand
    // at all do count for this one — see [sharesSide].
    val working = priorHolds.filter { !it.warmup && !it.incomplete && sharesSide(it.sideOf, hold.sideOf) }
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
     * Null means the record covers the exercise AS A WHOLE, which happens for two-limbed work
     * and for nothing else now: a set that named no side is read as both sides having done the
     * same thing and joins each hand's own record (see [sideGroups]). There used to be a third
     * value here — a record built out of sets that should have named a hand and did not,
     * flagged as a defect — and the screens drew it as a third column beside the two hands.
     */
    val side: HoldSide? = null,
)

/** One side's slice of a history: the entries that count for it, and which side it is. */
private data class SideGroup<T>(val items: List<Pair<T, String>>, val side: HoldSide?)

/**
 * Splits a form's whole history into per-side groups, in the fixed order [holdRecord] and
 * [strengthRecord] both promise their readers: left, then right — shared so the two forms
 * cannot drift into ordering that promise differently.
 *
 * The history is judged per side as soon as EITHER source says sides exist here: the catalog
 * flag ([oneSided]), or a set that named one. A flag set today must split a history logged
 * before it, and a side logged on an exercise nobody has flagged is still a side. Short of
 * that, everything comes back as ONE group with side null — the ordinary two-limbed case.
 *
 * ── A SET THAT NAMED NO SIDE COUNTS FOR BOTH ────────────────────────────────────
 * It used to get a group of its own, reported as a defect ("side not recorded"), which is how
 * an exercise ticked "one limb at a time" TODAY ended up with three columns on its statistics:
 * left, right, and everything logged before the tick. The owner's ruling, 2026-08-11: "take the
 * third one away. Everything in the past without that tick was symmetric — the right and the
 * left were doing the same thing."
 *
 * So a sideless entry is not a hole in the data any more, it is a statement that both sides did
 * this, and it enters BOTH groups. There is no third group and no defect flag left to report.
 *
 * ── What this costs, said plainly ───────────────────────────────────────────────
 * Everything here is a MAXIMUM ([holdRecord], [strengthRecord], [heaviestSet] all reduce with
 * `maxBy`), and counting one entry twice cannot change a maximum. Were these groups ever summed
 * — volume, tonnage, impulse — the same entry would be added to both sides and the exercise's
 * total would double. Nothing sums them today: domain/Analytics.kt computes volume over the
 * whole exercise and never splits it by side at all. Anything that starts summing per side has
 * to deal with the overlap here first.
 */
private fun <T> sideGroups(
    mine: List<Pair<T, String>>,
    oneSided: Boolean,
    sideOfIt: (T) -> HoldSide?,
): List<SideGroup<T>> {
    val bySide = mine.any { sideOfIt(it.first) != null } || oneSided
    if (!bySide) return listOf(SideGroup(mine, null))

    // filtered rather than bucketed so that each group keeps the journal's own order, which is
    // what decides a tie between two equal bests (`maxBy` keeps the first, i.e. the earlier day)
    return listOf(HoldSide.LEFT, HoldSide.RIGHT).mapNotNull { side ->
        val ofSide = mine.filter { val its = sideOfIt(it.first); its == side || its == null }
        if (ofSide.isEmpty()) null else SideGroup(ofSide, side)
    }
}

/** "1RM 70 kg (65×3)", or the same with "(left)" appended. */
private fun sideLabel(text: String, side: HoldSide?): String =
    if (side != null) "$text (${side.code})" else text

/**
 * The records of a strength exercise over the whole history: the maximum estimated 1RM and
 * its date (§12-C: a record ALWAYS comes with a date). Returns an empty list if there were no
 * weighted sets.
 *
 * A LIST, one entry per side, on exactly the grounds [holdRecord] already stands on — see its
 * own KDoc for the fixed order and for [ExerciseRecord.sideMissing]. Records this axis alone;
 * [heaviestSet] is the second strength axis and is judged per side the same way.
 */
fun strengthRecord(
    sets: List<ActivityEvent>,
    exercise: ExerciseLink,
    oneSided: Boolean = false,
): List<ExerciseRecord> {
    val mine = sets.mapNotNull { ev ->
        (ev.form as? StrengthSet)
            ?.takeIf {
                it.exerciseLink()?.matches(exercise) == true && it.weightKg != null &&
                    !it.warmup && !it.incomplete
            }
            ?.let { it to ev.opDate }
    }
    if (mine.isEmpty()) return emptyList()
    return sideGroups(mine, oneSided) { it.sideOf }.map { (group, side) ->
        val (best, day) = group.maxBy { est1rm(it.first.weightKg!!, it.first.reps) }
        val value = est1rm(best.weightKg!!, best.reps)
        ExerciseRecord(
            exercise, RecordHit.Axis.EST_1RM, value, day,
            sideLabel("1RM ${fmtNum(value)} kg (${fmtNum(best.weightKg)}×${best.reps})", side),
            side,
        )
    }
}

/**
 * The heaviest single set of a strength exercise, with its date, per side. A second axis next
 * to the estimated 1RM: the 1RM record can be taken by a light-and-many set, and "the most I
 * have ever picked up" is a different question that lifters actually ask.
 */
fun heaviestSet(
    activities: List<ActivityEvent>,
    exercise: ExerciseLink,
    oneSided: Boolean = false,
): List<ExerciseRecord> {
    val mine = activities.mapNotNull { ev ->
        (ev.form as? StrengthSet)
            ?.takeIf {
                it.exerciseLink()?.matches(exercise) == true && it.weightKg != null &&
                    !it.warmup && !it.incomplete
            }
            ?.let { it to ev.opDate }
    }
    if (mine.isEmpty()) return emptyList()
    return sideGroups(mine, oneSided) { it.sideOf }.map { (group, side) ->
        val (best, day) = group.maxBy { it.first.weightKg!! }
        ExerciseRecord(
            exercise, RecordHit.Axis.WEIGHT_AT_REPS, best.weightKg!!, day,
            sideLabel("heaviest set ${fmtNum(best.weightKg)} kg x ${best.reps}", side),
            side,
        )
    }
}

/**
 * The records of a hold exercise over the whole history: MAXIMUM ADDED WEIGHT (§12-A) and
 * its date, or the longest hold in seconds where the history carries no weight at all.
 *
 * ── A LIST, because one exercise can hold more than one record ──────────────────
 * Work done one limb at a time has a record PER SIDE: the left hand's best added weight is
 * not in competition with the right hand's, and merging them reports the stronger hand as
 * though it were the exercise. So the answer is one record per side that was trained ([mine]
 * split by [sideGroups]).
 *
 * An exercise with no sides anywhere in its history and [oneSided] false gets exactly one
 * record, as it always did — that is the two-handed case and it is the common one.
 *
 * ── A set that named no hand belongs to both ────────────────────────────────────
 * [oneSided] is what the CATALOG says about the exercise; the sides are what the SETS say.
 * Where the two disagree — a one-sided exercise whose older sets predate the tick — those sets
 * are read as symmetric and counted for each hand, rather than kept apart as a third record of
 * unknown side. [sideGroups] holds the reasoning and the caveat.
 */
fun holdRecord(
    sets: List<ActivityEvent>,
    exercise: ExerciseLink,
    oneSided: Boolean = false,
): List<ExerciseRecord> {
    val mine = sets.mapNotNull { ev ->
        (ev.form as? HoldSet)
            ?.takeIf { it.exerciseLink()?.matches(exercise) == true && !it.warmup && !it.incomplete }
            ?.let { it to ev.opDate }
    }
    if (mine.isEmpty()) return emptyList()
    return sideGroups(mine, oneSided) { it.sideOf }.mapNotNull { (group, side) ->
        holdRecordOf(group, exercise, side)
    }
}

/** One side's (or the whole exercise's) best hold: the added-weight axis, else seconds. */
private fun holdRecordOf(
    mine: List<Pair<HoldSet, String>>,
    exercise: ExerciseLink,
    side: HoldSide?,
): ExerciseRecord? {
    if (mine.any { it.first.addedKg != null }) {
        // every hold of the exercise competes, the clean ones at zero — see [evaluateHoldRecord]
        // for why a null has to be a point on this axis and not an absence from it
        val (best, day) = mine.maxBy { it.first.addedKg ?: 0.0 }
        val value = best.addedKg ?: 0.0
        return ExerciseRecord(
            exercise, RecordHit.Axis.HOLD_WEIGHT, value, day,
            sideLabel(addedWeightPhrase(value), side), side,
        )
    }
    val withSeconds = mine.filter { it.first.holdSec != null }
    if (withSeconds.isEmpty()) return null
    val (best, day) = withSeconds.maxBy { it.first.holdSec!! }
    return ExerciseRecord(
        exercise, RecordHit.Axis.HOLD_SECONDS, best.holdSec!!, day,
        sideLabel("hold ${fmtNum(best.holdSec)} s", side), side,
    )
}
