package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * What a finished run is worth writing down.
 *
 * ── The gap this closes ─────────────────────────────────────────────────────────
 * The timer counted four sets of six hangs and then forgot them; the journal was waiting
 * to be told the same four sets by hand. That is the one seam in the app where the two
 * halves that should know about each other did not, and it is exactly the moment — phone
 * in hand, fingers ruined — when typing four rows is least likely to happen. So a run that
 * came FROM a catalog exercise ends with an offer to log what it counted.
 *
 * ── An offer, never a write ─────────────────────────────────────────────────────
 * Nothing here writes anything. These functions turn a run into a PROPOSAL — a list of
 * sets with rep counts — which the screen shows, the user corrects (the last set is the
 * one that is usually short), and only then does the ordinary journal write happen through
 * the ordinary repository. A timer that silently logged sets you did not do would poison
 * the only record of what was actually trained, and the personal records computed from it.
 *
 * ── Every run, now that a rest is not one ───────────────────────────────────────
 * [RunOrigin] used to carry a third value, REST, for the case that had to be kept out of the
 * offer: a rest between sets was itself a run — a one-step program with an exercise_id and a
 * single WORK step — so nothing about its shape distinguished it, and without the recorded
 * origin the app would have offered to log a two-minute pause as a set of one.
 *
 * That value is gone because the thing it described is gone. A rest between sets is a FLOOR
 * now (domain/Floors.kt): several run at once, none of them is a run, and none of them ever
 * reaches this file. What is left in [RunOrigin] is the distinction that still does work —
 * whether the run knows which exercise it trained.
 *
 * The offer used to be narrower still — only [RunOrigin.EXERCISE], a program generated on
 * the spot from a catalog row. That silently excluded the ordinary case: a protocol saved
 * in the editor and run from the timer tab, which is how a hangboard session is actually
 * started. A whole session of 7:3 repeaters ran, counted twenty-four hangs, and offered
 * nothing. So a run of a saved program now offers too, and [RunOutcome.exerciseId] is
 * allowed to be null — the offer then asks which exercise it was, once, and the answer is
 * remembered on the program.
 *
 * ── Holds only, and why ─────────────────────────────────────────────────────────
 * [programFromExercise] can only build a program from an exercise that carries a work:rest
 * protocol, and §12-A puts protocols on hold exercises. There the mapping is exact: one
 * work step = one hang, one group repeat = one set. For a strength exercise the same
 * mapping would be a lie — a 30-second work step is a whole set of unknown reps, not one
 * rep — so [holdSetsFromRun] refuses anything but [ExerciseForm.HOLD] rather than
 * inventing numbers.
 *
 * ── Known limitation, stated rather than hidden ─────────────────────────────────
 * Completion is judged by POSITION, not by presence: a step the run moved past counts as
 * done. Skipping forward with the Skip button therefore counts the skipped efforts as
 * completed, because the runner keeps no per-step record of how each one ended (see
 * domain/Runner.kt — the state is one index and one end moment, which is what makes it
 * survive process death). The proposal being editable is what covers this: the numbers are
 * shown before anything is written.
 */

/**
 * Where a run came from, and therefore whether it knows which exercise it trained.
 *
 * A third value, REST, was removed along with the single-rest run it described — see the
 * note at the top of this file. An old snapshot on disk that still names it fails to parse
 * and is dropped by the store, which is the same handling any format change gets and costs
 * at most one interrupted countdown on the update that introduces it.
 */
enum class RunOrigin {
    /** A saved program, run from the timer screen. Belongs to no exercise. */
    PROGRAM,

    /** Generated from a catalog exercise ([programFromExercise]): the case that can be logged. */
    EXERCISE,
}

/**
 * One set as the run performed it.
 *
 * [plannedReps] travels alongside [reps] so the offer can show "3 of 6" and the user can
 * see at a glance which set was cut short. [restAfterSec] is the pause the program actually
 * put between this set and the next one — known exactly, unlike the pause the session feed
 * has to derive from the gap between two writes, which is why it is worth recording.
 *
 * [incomplete] is the same mark [LoadedSet.incomplete] is everywhere else — "the reps happened
 * but the target was not carried through" — and it lives here rather than being asked for
 * separately once the sets are written, because the run knows nothing about which effort fell
 * short; only the person who was on the bar does, and
 * [xyz.oleolegka.gachimuchi.ui.components.RunLogDialog] is where they say so, one row at a
 * time, the same dialog where they already correct the rep count. OFF for every set
 * the timer produces: whether an effort was carried through is not something the timer counted
 * and not something worth guessing at.
 */
@Serializable
data class CompletedSet(
    @SerialName("set_number") val setNumber: Int,
    @SerialName("reps") val reps: Int,
    @SerialName("planned_reps") val plannedReps: Int,
    @SerialName("work_sec") val workSec: Int,
    @SerialName("rest_after_sec") val restAfterSec: Int?,
    @SerialName("incomplete") val incomplete: Boolean = false,
)

/**
 * The sets a run got through.
 *
 * [endedAtIndex] is the step the run was standing on when it stopped; everything before it
 * ran to the end. When [finished] is true the whole list ran and the index is ignored.
 * Sets that produced nothing are left out entirely — stopping during the lead-in offers
 * nothing at all, which is the right amount to offer.
 */
fun completedSets(steps: List<WorkoutStep>, endedAtIndex: Int, finished: Boolean): List<CompletedSet> {
    if (steps.isEmpty()) return emptyList()
    val doneThrough = if (finished) steps.size else endedAtIndex.coerceIn(0, steps.size)

    class Bucket {
        var planned = 0
        var done = 0
        var workSec = 0
        var firstIndex = -1
        var lastIndex = -1
    }

    // one bucket per (group, repeat of that group) — which for a program generated from an
    // exercise is exactly one bucket per set
    val buckets = LinkedHashMap<String, Bucket>()
    steps.forEachIndexed { index, step ->
        if (step.kind != StepKind.WORK) return@forEachIndexed
        val bucket = buckets.getOrPut("${step.groupName}#${step.groupRepeat}") { Bucket() }
        if (bucket.planned == 0) {
            bucket.firstIndex = index
            bucket.workSec = step.durationSec
        }
        bucket.planned++
        bucket.lastIndex = index
        if (index < doneThrough) bucket.done++
    }

    val ordered = buckets.values.toList()
    val out = ArrayList<CompletedSet>()
    ordered.forEachIndexed { position, bucket ->
        if (bucket.done <= 0) return@forEachIndexed
        val next = ordered.getOrNull(position + 1)
        // the pause is only known when it fully elapsed, i.e. when the run reached the
        // first effort of the next set; a run stopped inside the pause reports nothing
        val rest = next
            ?.takeIf { doneThrough >= it.firstIndex }
            ?.let { steps.subList(bucket.lastIndex + 1, it.firstIndex).sumOf { step -> step.durationSec } }
        out += CompletedSet(
            setNumber = position + 1,
            reps = bucket.done,
            plannedReps = bucket.planned,
            workSec = bucket.workSec,
            restAfterSec = rest?.takeIf { it > 0 },
        )
    }
    return out
}

/**
 * How a run ended, and what it would put in the journal.
 *
 * ── It is written to disk, and that is a reversal ───────────────────────────────
 * This used to live in memory only, on the argument that an offer is a conversation about
 * what just happened and one that outlived the process would surface out of context. The
 * phone in a pocket proved the argument backwards: a run ends with the screen off, Android
 * gets around to killing the app before it is looked at again, and the session the user
 * actually did is gone with no trace and no way to get it back. A slightly stale offer is a
 * recoverable annoyance; a lost session is not.
 *
 * What makes the stale case honest is [endedAtWallMs] and [opDate]. The offer says when the
 * run ended rather than pretending it was a moment ago, and the sets are written under the
 * date the run HAPPENED on — an evening session confirmed the next morning belongs to the
 * evening. Past [OUTCOME_MAX_AGE_MS] it is dropped: at that distance the numbers are a
 * guess about a day already written.
 */
@Serializable
data class RunOutcome(
    @SerialName("program_name") val programName: String,
    @SerialName("origin") val origin: RunOrigin,
    @SerialName("exercise_id") val exerciseId: Long?,
    /** The saved program this run came from, or 0 when it was generated on the spot. */
    @SerialName("program_id") val programId: Long = 0,
    /** The run was stopped by hand rather than reaching its end. */
    @SerialName("interrupted") val interrupted: Boolean,
    @SerialName("sets") val sets: List<CompletedSet>,
    /** Wall clock at the moment the run ended. Zero when it was never recorded. */
    @SerialName("ended_at_wall_ms") val endedAtWallMs: Long = 0,
    /** ISO date the run ended on — the date its sets are written under. */
    @SerialName("op_date") val opDate: String = "",
    /**
     * Which hand the run trained, carried over from [RunSnapshot.side] so the offer can still
     * answer the question after the process that started the run is long gone — an outcome is
     * PERSISTED and may be confirmed the morning after (see the type doc above). Null for an
     * exercise with only one card and for a run with no card of its own (the timer tab).
     */
    @SerialName("side") val side: String? = null,
) {
    /**
     * Whether this run is worth interrupting the user about. A run that completed no effort
     * at all is silent; anything else is a workout that happened, whether or not it knows
     * which exercise it was.
     *
     * This used to also exclude [RunOrigin.REST], which was the whole reason the origin was
     * recorded. Rests are floors now and never become a run, so the count of completed
     * efforts is the only test left.
     */
    val offersLogging: Boolean
        get() = sets.isNotEmpty()

    /** [side] read as the domain compares it — see [HoldSet.sideOf], the same idea for a set. */
    val sideOf: HoldSide? get() = HoldSide.fromCode(side)

    /** Whether the offer is about something that just happened, or about a run found later. */
    fun isFresh(nowWallMs: Long): Boolean =
        endedAtWallMs <= 0 || nowWallMs - endedAtWallMs < FRESH_OUTCOME_MS

    fun isExpired(nowWallMs: Long): Boolean =
        endedAtWallMs > 0 && nowWallMs - endedAtWallMs > OUTCOME_MAX_AGE_MS
}

/** Past this the offer stops calling itself "just now" and states the time the run ended. */
const val FRESH_OUTCOME_MS = 10 * 60 * 1000L

/** Past this a stored offer is dropped rather than raised against a day already written. */
const val OUTCOME_MAX_AGE_MS = 24 * 60 * 60 * 1000L

/**
 * The monotonic moment a run ENDED, as opposed to the moment anyone noticed.
 *
 * A run that ran itself out ended at the end of its last step, which [settleRun] leaves in
 * [RunState.stepEndAtMs] — a reading that may be hours old by the time it is read, because
 * the process can be killed mid-run and the end only discovered when the app is next opened.
 * A run that was stopped, skipped or paused out of existence ends now, because "now" is when
 * the user did that.
 *
 * The cap at [now] matters for the second case: a run stopped by hand is standing inside a
 * step whose end moment is in the FUTURE, and taking that at face value would file the
 * session under a moment that has not happened yet.
 */
fun runEndedAtMs(steps: List<WorkoutStep>, state: RunState, now: Long): Long {
    val settled = settleRun(steps, state, now)
    return if (settled.finished) settled.stepEndAtMs.coerceAtMost(now) else now
}

/**
 * Reads a run — live or just ended — as an outcome, settling its state against [now] first.
 *
 * ── Where the day comes from, and why it is not "today" ─────────────────────────
 * [now] is monotonic and cannot name a day, so the wall clock has to come from somewhere.
 * It comes from the run itself: [RunSnapshot.bootRef] is wall-minus-monotonic, so
 * `bootRef + endedAt` is the WALL MOMENT THE RUN ENDED, not the moment this function was
 * called. Those are the same thing in the ordinary case and hours apart in the case this
 * exists for — an evening session whose process Android killed, materialised when the phone
 * is unlocked the next morning. Reading the clock here filed that session under the wrong
 * day, silently, in the one record the app is for; it also made the offer claim the run had
 * just ended, so neither the "this run ended at HH:MM" warning nor the twenty-four hour cut
 * off could ever fire on the path they were written for.
 *
 * A snapshot with no boot reference (bootRef = 0, which only happens in tests and in
 * hand-built values) yields no wall time and no date, and the offer reads as "when this
 * happened is not known" — the same as before any of this was recorded.
 */
fun runOutcome(
    snapshot: RunSnapshot,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): RunOutcome {
    val settled = settleRun(snapshot.steps, snapshot.state, now)
    val endedAtWallMs = wallMomentOf(snapshot.bootRef, runEndedAtMs(snapshot.steps, snapshot.state, now))
    return RunOutcome(
        programName = snapshot.programName,
        origin = snapshot.origin,
        exerciseId = snapshot.exerciseId,
        programId = snapshot.programId,
        interrupted = !settled.finished,
        sets = completedSets(snapshot.steps, settled.stepIndex, settled.finished),
        endedAtWallMs = endedAtWallMs,
        opDate = isoDateOf(endedAtWallMs, zone),
        side = snapshot.side,
    )
}

/**
 * What a run that a REBOOT ended is worth writing down.
 *
 * A snapshot from a previous boot cannot be resumed — its end moments are readings of a
 * clock that no longer exists — but the sets it already got through are not readings of
 * anything. They are a count of work steps the run moved past, and that count survives a
 * restart perfectly well. Throwing the whole snapshot away, which is what used to happen,
 * silently lost the finished part of a session to a battery running flat.
 *
 * Two deliberate conservatisms, because this is reconstruction and not a record:
 * - the step the run was standing on is NOT counted. The device could have gone down at any
 *   point inside it, and counting an effort that may not have happened is the failure this
 *   whole feature is built to avoid;
 * - the run is dated from the START of that step — the last moment it is known to have been
 *   alive — rather than from where the step would have ended.
 *
 * [RunSnapshot.bootRef] is from the old boot and that is exactly right here: it was
 * wall-minus-monotonic while those monotonic numbers still meant something, so it converts
 * them back to wall time across the restart.
 */
fun salvagedOutcome(snapshot: RunSnapshot, zone: ZoneId = ZoneId.systemDefault()): RunOutcome {
    val index = snapshot.state.stepIndex.coerceIn(0, maxOf(0, snapshot.steps.lastIndex))
    val stepStart = snapshot.state.stepEndAtMs - (snapshot.steps.getOrNull(index)?.durationMs ?: 0L)
    val endedAtWallMs = wallMomentOf(snapshot.bootRef, stepStart)
    return RunOutcome(
        programName = snapshot.programName,
        origin = snapshot.origin,
        exerciseId = snapshot.exerciseId,
        programId = snapshot.programId,
        interrupted = true,
        sets = completedSets(snapshot.steps, endedAtIndex = snapshot.state.stepIndex, finished = false),
        endedAtWallMs = endedAtWallMs,
        opDate = isoDateOf(endedAtWallMs, zone),
        side = snapshot.side,
    )
}

/** Wall time from a boot reference and a monotonic reading; 0 when either is unusable. */
private fun wallMomentOf(bootRef: Long, elapsedMs: Long): Long {
    if (bootRef <= 0 || elapsedMs < 0) return 0
    return bootRef + elapsedMs
}

private fun isoDateOf(wallMs: Long, zone: ZoneId): String =
    if (wallMs <= 0) "" else Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate().toString()

/**
 * The journal entries a set of [CompletedSet]s becomes — one [HoldSet] per set, exactly
 * the value the entry card would have built (§3: one event = one set).
 *
 * Sets edited down to zero reps are dropped: that is how the offer says "this one did not
 * happen". The pause is written on every set but the last, because there is no pause after
 * the last one, and an empty [ExerciseRef] form other than a hold yields nothing at all.
 *
 * [CompletedSet.workSec] is written as [HoldSet.holdSec] on every set, not left for the
 * catalog's protocol snapshot to stand in for later. It is the ONE source that is exact here —
 * the run counted this many seconds of this many hangs, not "whatever the exercise is set to
 * today" — and unlike the entry card, this path always knows it: there is no length left for
 * the user to type in.
 *
 * [side] is stamped on every set exactly as the entry card stamps it — the answer to "which
 * card was this run started from", not asked again here. A run with no side (the ordinary
 * two-handed case, or one started from the timer tab rather than a card) leaves it null, the
 * same as an entry card with nothing to ask.
 *
 * [CompletedSet.incomplete] is carried onto its own [HoldSet] and nothing else's — a fingerboard
 * session is six hangs and a lifter who fell off the fourth said so about the fourth, not about
 * the other five (see [xyz.oleolegka.gachimuchi.ui.components.RunLogDialog]).
 */
fun holdSetsFromRun(
    exercise: ExerciseRef,
    opDate: String,
    sets: List<CompletedSet>,
    addedKg: Double? = null,
    side: HoldSide? = null,
): List<HoldSet> {
    if (exercise.form != ExerciseForm.HOLD) return emptyList()
    val live = sets.filter { it.reps > 0 }
    return live.mapIndexed { index, set ->
        holdSetOf(
            exercise = exercise,
            opDate = opDate,
            addedKg = addedKg,
            reps = set.reps,
            holdSec = set.workSec.toDouble(),
            restAfterSec = if (index < live.lastIndex) set.restAfterSec?.toDouble() else null,
            incomplete = set.incomplete,
            side = side,
        )
    }
}

/** "3 sets - 6 + 6 + 3 efforts of 7 s", the line the offer is read from. */
fun runSummaryLine(sets: List<CompletedSet>): String {
    val live = sets.filter { it.reps > 0 }
    if (live.isEmpty()) return "Nothing was completed."
    val counts = live.joinToString(" + ") { it.reps.toString() }
    val setWord = if (live.size == 1) "set" else "sets"
    val effortWord = if (live.sumOf { it.reps } == 1) "effort" else "efforts"
    return "${live.size} $setWord - $counts $effortWord of ${live.first().workSec} s"
}
