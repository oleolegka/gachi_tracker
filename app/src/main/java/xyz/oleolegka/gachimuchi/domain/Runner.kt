package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The countdown itself: pure arithmetic over a flattened program and an injected "now".
 *
 * ── Why the state stores an END MOMENT and never "seconds left" ─────────────────
 * A field called "remaining" is only true while something decrements it, which means it
 * is a lie the moment the process is frozen, killed or the screen goes off. The state
 * here stores [RunState.stepEndAtMs] — the reading of a monotonic clock at which the
 * current step is over — so the remaining time is DERIVED from the clock every time it is
 * asked for. Nothing has to be running for the timer to stay correct.
 *
 * That is not a theoretical point. The app this feature is modelled on (Just Another
 * Workout Timer) counts elapsed seconds by incrementing a counter inside a one-second
 * callback; it has a long-standing open bug where the timer silently falls behind real
 * time as soon as the screen is off, reproducible on stock Pixels, closed as unfixable.
 * Counting callbacks and counting time are different things, and only one of them
 * survives the phone being put in a pocket.
 *
 * ── Catching up, not skipping ahead ─────────────────────────────────────────────
 * [settleRun] takes a state that may be arbitrarily stale and walks it forward through
 * however many step boundaries have passed. Coming back after four minutes away lands on
 * the step the workout is genuinely on, with the right amount left in it.
 *
 * ── The clock is monotonic, so it does not survive a reboot ─────────────────────
 * All times here are `SystemClock.elapsedRealtime` milliseconds — a count since boot,
 * immune to the wall clock being changed or to time zones. The cost is that the numbers
 * mean nothing after a restart, which is why a persisted run carries a boot reference
 * ([bootReference]) and is thrown away when it no longer matches. Resuming a rest between
 * sets across a reboot would be meaningless anyway: rebooting takes longer than the rest.
 */

/** The live position of a run. Meaningless without the step list it refers to. */
@Serializable
data class RunState(
    /** Index into the flattened step list. */
    @SerialName("step_index") val stepIndex: Int = 0,
    /** The clock is only moving while this is true. */
    @SerialName("running") val running: Boolean = false,
    /** Monotonic reading at which the current step ends. Meaningful while [running]. */
    @SerialName("step_end_at_ms") val stepEndAtMs: Long = 0,
    /** What was left of the current step when it was paused. Meaningful while not [running]. */
    @SerialName("paused_left_ms") val pausedLeftMs: Long = 0,
    /** The program ran to its end. Terminal: only a reset or a new start leaves this. */
    @SerialName("finished") val finished: Boolean = false,
)

/** Where a run stands, for the screens and the notification to branch on. */
enum class RunPhase { RUNNING, PAUSED, FINISHED }

fun RunState.phase(): RunPhase = when {
    finished -> RunPhase.FINISHED
    running -> RunPhase.RUNNING
    else -> RunPhase.PAUSED
}

/** Starts a program from its first step. An empty step list produces a finished run. */
fun startRun(steps: List<WorkoutStep>, now: Long): RunState =
    if (steps.isEmpty()) {
        RunState(finished = true)
    } else {
        RunState(stepIndex = 0, running = true, stepEndAtMs = now + steps[0].durationMs)
    }

/**
 * Walks a possibly stale state forward to [now].
 *
 * Every boundary is measured from the PREVIOUS boundary rather than from [now], so the
 * steps stay aligned to the schedule the run started on and rounding never accumulates:
 * catching up on twenty missed Tabata intervals lands exactly where an uninterrupted run
 * would have.
 *
 * Steps of zero length cannot occur ([flatten] drops them), so the loop always advances.
 */
fun settleRun(steps: List<WorkoutStep>, state: RunState, now: Long): RunState {
    if (!state.running || state.finished || steps.isEmpty()) return state
    var current = state
    while (now >= current.stepEndAtMs) {
        val next = current.stepIndex + 1
        if (next > steps.lastIndex) {
            return current.copy(
                stepIndex = steps.lastIndex,
                running = false,
                finished = true,
                pausedLeftMs = 0,
            )
        }
        current = current.copy(
            stepIndex = next,
            stepEndAtMs = current.stepEndAtMs + steps[next].durationMs,
        )
    }
    return current
}

/** Milliseconds left in the current step, never negative. */
fun stepRemainingMs(steps: List<WorkoutStep>, state: RunState, now: Long): Long {
    if (steps.isEmpty() || state.finished) return 0
    val settled = settleRun(steps, state, now)
    if (settled.finished) return 0
    return if (settled.running) (settled.stepEndAtMs - now).coerceAtLeast(0) else settled.pausedLeftMs.coerceAtLeast(0)
}

/** Milliseconds left in the whole program: the rest of this step plus every step after it. */
fun totalRemainingMs(steps: List<WorkoutStep>, state: RunState, now: Long): Long {
    if (steps.isEmpty() || state.finished) return 0
    val settled = settleRun(steps, state, now)
    if (settled.finished) return 0
    val later = steps.drop(settled.stepIndex + 1).sumOf { it.durationMs }
    return stepRemainingMs(steps, settled, now) + later
}

/** The step the run is on right now, or null once it has finished. */
fun currentStep(steps: List<WorkoutStep>, state: RunState, now: Long): WorkoutStep? {
    if (steps.isEmpty()) return null
    val settled = settleRun(steps, state, now)
    if (settled.finished) return null
    return steps.getOrNull(settled.stepIndex)
}

/** What comes after the current step — the run screen shows it so nothing is a surprise. */
fun nextStep(steps: List<WorkoutStep>, state: RunState, now: Long): WorkoutStep? {
    if (steps.isEmpty()) return null
    val settled = settleRun(steps, state, now)
    if (settled.finished) return null
    return steps.getOrNull(settled.stepIndex + 1)
}

/** Freezes the clock, keeping what was left of the current step. A no-op when not running. */
fun pauseRun(steps: List<WorkoutStep>, state: RunState, now: Long): RunState {
    val settled = settleRun(steps, state, now)
    if (!settled.running || settled.finished) return settled
    return settled.copy(
        running = false,
        pausedLeftMs = (settled.stepEndAtMs - now).coerceAtLeast(0),
    )
}

/** Restarts the clock, pushing the end of the current step out by whatever was left. */
fun resumeRun(steps: List<WorkoutStep>, state: RunState, now: Long): RunState {
    if (state.finished || state.running || steps.isEmpty()) return state
    return state.copy(running = true, stepEndAtMs = now + state.pausedLeftMs)
}

/**
 * Jumps to the beginning of the next step. Pausedness is preserved: skipping while paused
 * leaves the run paused at the top of the next step, which is what "look ahead without
 * losing my place" means.
 */
fun skipStep(steps: List<WorkoutStep>, state: RunState, now: Long): RunState {
    if (steps.isEmpty() || state.finished) return state
    val settled = settleRun(steps, state, now)
    if (settled.finished) return settled
    val next = settled.stepIndex + 1
    if (next > steps.lastIndex) {
        return settled.copy(stepIndex = steps.lastIndex, running = false, finished = true, pausedLeftMs = 0)
    }
    return restartAt(steps, settled, next, now)
}

/**
 * Goes back to the beginning of the previous step, or restarts the current one when
 * already on the first.
 *
 * Deliberately NOT the "restart the current step if more than two seconds in, otherwise go
 * back" rule that music players use. Under a bar, "back" means "that rep did not count,
 * give me the previous step again", and a control whose meaning depends on how long you
 * hesitated before pressing it is a control you cannot trust.
 */
fun previousStep(steps: List<WorkoutStep>, state: RunState, now: Long): RunState {
    if (steps.isEmpty()) return state
    val settled = settleRun(steps, state, now)
    val target = if (settled.finished) steps.lastIndex else (settled.stepIndex - 1).coerceAtLeast(0)
    return restartAt(steps, settled.copy(finished = false), target, now)
}

/** Moves to [index] and starts that step from the top, keeping the running/paused mode. */
private fun restartAt(steps: List<WorkoutStep>, state: RunState, index: Int, now: Long): RunState {
    val duration = steps[index].durationMs
    return state.copy(
        stepIndex = index,
        finished = false,
        stepEndAtMs = if (state.running) now + duration else 0,
        pausedLeftMs = if (state.running) 0 else duration,
    )
}

/**
 * Lengthens or shortens the CURRENT step by [deltaMs] (the +/- 30 s buttons).
 *
 * The floor is zero, not one second: shortening a rest below what is left of it means
 * "I am done resting", and the step ends at once rather than refusing the press. The
 * step's own duration in the program is untouched — the change applies to this run only,
 * and a later repeat of the same block is the length it was written as.
 */
fun adjustStep(steps: List<WorkoutStep>, state: RunState, now: Long, deltaMs: Long): RunState {
    if (steps.isEmpty() || state.finished) return state
    val settled = settleRun(steps, state, now)
    if (settled.finished) return settled
    return if (settled.running) {
        settled.copy(stepEndAtMs = (settled.stepEndAtMs + deltaMs).coerceAtLeast(now))
    } else {
        settled.copy(pausedLeftMs = (settled.pausedLeftMs + deltaMs).coerceAtLeast(0))
    }
}

// --- surviving process death ----------------------------------------------------------

/**
 * The reference that tells one boot from another: wall clock minus monotonic clock, i.e.
 * roughly the wall time the device booted at. It stays constant (within clock jitter)
 * across a session and jumps after a restart.
 */
fun bootReference(wallMs: Long, elapsedMs: Long): Long = wallMs - elapsedMs

/**
 * Whether a persisted run refers to a boot that is over — in which case its monotonic end
 * moments mean nothing and it must be discarded rather than resumed at a wrong offset.
 *
 * The tolerance absorbs ordinary wall-clock drift (NTP nudging the clock by a second or
 * two mid-session). A deliberate clock change larger than that will also read as a reboot
 * and throw the run away; losing a rest timer is the right way to be wrong here.
 */
fun isRunStale(savedBootRef: Long, currentBootRef: Long, toleranceMs: Long = 5_000): Boolean =
    kotlin.math.abs(currentBootRef - savedBootRef) > toleranceMs

/** Everything needed to rebuild a run after the process was killed. */
@Serializable
data class RunSnapshot(
    @SerialName("program_id") val programId: Long,
    @SerialName("program_name") val programName: String,
    @SerialName("steps") val steps: List<WorkoutStep>,
    @SerialName("state") val state: RunState,
    /** [bootReference] at the moment of saving; see [isRunStale]. */
    @SerialName("boot_ref") val bootRef: Long,
    /** The exercise the run was started from, when it was generated from one. */
    @SerialName("exercise_id") val exerciseId: Long? = null,
)
