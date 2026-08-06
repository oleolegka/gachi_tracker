package xyz.oleolegka.gachimuchi.domain

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
 * ── Only runs generated from an exercise ────────────────────────────────────────
 * [RunOrigin] is what tells them apart, and it has to be recorded when the run starts
 * rather than guessed afterwards: a rest between sets is also "a program with an
 * exercise_id" (see [restProgram]), and its single step is also a WORK step. Guessing from
 * the shape would offer to log a rest as a set of one.
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

/** Where a run came from. Decides whether finishing it is worth offering to log. */
enum class RunOrigin {
    /** A saved program, run from the timer screen. Belongs to no exercise. */
    PROGRAM,

    /** Generated from a catalog exercise ([programFromExercise]): the case that can be logged. */
    EXERCISE,

    /** A single pause between sets ([restProgram]). Nothing to log — the set is already written. */
    REST,
}

/**
 * One set as the run performed it.
 *
 * [plannedReps] travels alongside [reps] so the offer can show "3 of 6" and the user can
 * see at a glance which set was cut short. [restAfterSec] is the pause the program actually
 * put between this set and the next one — known exactly, unlike the pause the session feed
 * has to derive from the gap between two writes, which is why it is worth recording.
 */
data class CompletedSet(
    val setNumber: Int,
    val reps: Int,
    val plannedReps: Int,
    val workSec: Int,
    val restAfterSec: Int?,
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

/** How a run ended, and what it would put in the journal. Held in memory only. */
data class RunOutcome(
    val programName: String,
    val origin: RunOrigin,
    val exerciseId: Long?,
    /** The run was stopped by hand rather than reaching its end. */
    val interrupted: Boolean,
    val sets: List<CompletedSet>,
) {
    /**
     * Whether this run is worth interrupting the user about. A rest, a program that belongs
     * to no exercise, and a run that completed nothing are all silent.
     */
    val offersLogging: Boolean
        get() = origin == RunOrigin.EXERCISE && exerciseId != null && sets.isNotEmpty()
}

/** Reads a run — live or just ended — as an outcome, settling its state against [now] first. */
fun runOutcome(snapshot: RunSnapshot, now: Long): RunOutcome {
    val settled = settleRun(snapshot.steps, snapshot.state, now)
    return RunOutcome(
        programName = snapshot.programName,
        origin = snapshot.origin,
        exerciseId = snapshot.exerciseId,
        interrupted = !settled.finished,
        sets = completedSets(snapshot.steps, settled.stepIndex, settled.finished),
    )
}

/**
 * The journal entries a set of [CompletedSet]s becomes — one [HoldSet] per set, exactly
 * the value the entry card would have built (§3: one event = one set).
 *
 * Sets edited down to zero reps are dropped: that is how the offer says "this one did not
 * happen". The pause is written on every set but the last, because there is no pause after
 * the last one, and an empty [ExerciseRef] form other than a hold yields nothing at all.
 */
fun holdSetsFromRun(
    exercise: ExerciseRef,
    opDate: String,
    sets: List<CompletedSet>,
    addedKg: Double? = null,
): List<HoldSet> {
    if (exercise.form != ExerciseForm.HOLD) return emptyList()
    val live = sets.filter { it.reps > 0 }
    return live.mapIndexed { index, set ->
        holdSetOf(
            exercise = exercise,
            opDate = opDate,
            addedKg = addedKg,
            reps = set.reps,
            restAfterSec = if (index < live.lastIndex) set.restAfterSec?.toDouble() else null,
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
