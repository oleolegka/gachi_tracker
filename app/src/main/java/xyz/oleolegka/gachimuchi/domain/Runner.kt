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
    /**
     * Steps the run LEFT WITHOUT LETTING THEM RUN OUT — the Skip button, and nothing else.
     *
     * ── Why the position alone could not answer this ────────────────────────────
     * Everything else in this state is derived from the clock, which is what makes a run
     * survive its own process: an index and an end moment are enough to say where a run is,
     * however long ago it was written. They are NOT enough to say how it got there. A run
     * standing on step 7 either counted its way through the first six or jumped over some of
     * them, and after the fact those two are the same index. So completion was judged by
     * position — a step the run had moved past counted as done — and skipping forward wrote
     * efforts into the journal that nobody made (§18.20).
     *
     * There is no arithmetic that recovers this. Skipping restarts the step it lands on from
     * `now` ([restartAt]), so the schedule's alignment — the one quantity that could have
     * betrayed a jump — is broken by the jump itself, and it is equally broken by a pause and
     * by the +/- 30 s buttons, which are ordinary use. The knowledge has to be RECORDED at the
     * moment the skip happens, and it has to be recorded HERE, in the persisted state, because
     * the run whose numbers are being judged may well be read back by a different process
     * (see [RunSnapshot] and domain/RunLog.kt).
     *
     * ── Why the negative form ───────────────────────────────────────────────────
     * A set of the steps that DID count would be the more direct statement, but it defaults to
     * empty, and a snapshot written by the previous build has no field at all — every run in
     * flight during the update would then read as "nothing was completed" and lose a session
     * that really happened. Recording the exception instead makes an old snapshot read exactly
     * as it did before: at worst the old overstatement, for one run, once.
     *
     * Emptied per step by [settleRun] as the clock genuinely carries the run through it, so a
     * step that was skipped, reopened with the Back button and then held to its end counts
     * again. What this does NOT record is a step cut short with the minus button — see
     * [adjustStep].
     */
    @SerialName("skipped") val skipped: Set<Int> = emptySet(),
)

/** Where a run stands, for the screens and the notification to branch on. */
enum class RunPhase { RUNNING, PAUSED, FINISHED }

/** How many whole seconds at the end of a step get a countdown tick. */
const val TICK_SECONDS = 3

/**
 * What the countdown loop must do at this instant, and when it has to look again.
 *
 * ── Why this is a value and not a loop full of arithmetic ───────────────────────
 * The signals used to be decided inside the coroutine that sleeps between them, which made
 * them untestable on the JVM and hid a bug that only shows up on SHORT steps (see
 * [timerCue]). Pulling the decision out leaves the coroutine with two jobs — sleep, and do
 * what it is told — and puts the timing under the same kind of test as the countdown.
 */
data class TimerCue(
    /** The current step is over: settle the run and signal the next one. */
    val boundary: Boolean,
    /** The whole second the countdown is standing on and should tick, or null for silence. */
    val tickSecond: Int?,
    /** The monotonic moment the loop must wake at next. Never in the past. */
    val wakeAtMs: Long,
)

/**
 * How much room a countdown tick must leave after a boundary signal.
 *
 * A boundary is the longest thing the app plays: 620 ms of vibration waveform for a step
 * that starts work, 350 ms of tone (timer/Signals.kt). Both the tone generator and the
 * vibrator do ONE thing at a time, so a tick inside that window does not mix with the
 * boundary — it replaces it, and what the user gets is a 40 ms tap where the "go" was.
 *
 * The value is pinned between two bounds and both of them matter:
 *
 *  - ABOVE 620 ms, or it does not actually cover the waveform it exists to protect.
 *  - BELOW 1000 ms, so that a step entered ON TIME behaves exactly as it always has. A tick
 *    then sits a whole number of seconds after the step began, so the only tick this rule
 *    can refuse is the one at zero — which is the same tick `second < durationSec` used to
 *    refuse. The generalisation costs nothing in the ordinary case; see [tickAllowed].
 */
const val BOUNDARY_GUARD_MS = 700L

/**
 * How far past its own moment a countdown tick may still be made.
 *
 * ── A late tick is not a tick, it is the next one arriving early ────────────────
 * The loop sleeps to the exact moment a tick is due, but a sleep can overrun: the process is
 * descheduled, the device suspends for a moment, the audio stack takes its time. The tick was
 * fired anyway, at whatever moment the loop happened to wake, and the second it announced was
 * still whichever second the clock was standing in — so a wake-up 900 ms late produced "two"
 * 900 ms late and then "one" 100 ms after it. Two 40 ms taps a tenth of a second apart are
 * not two ticks; the vibrator plays one waveform at a time, so the first is cut off by the
 * second and what comes out is a stutter. That is a countdown that DOUBLES rather than one
 * that counts.
 *
 * A quarter of a second is the whole tolerance, which keeps any two ticks at least 750 ms
 * apart — well clear of the 90 ms tone and 40 ms tap they are made of. A tick that cannot be
 * made inside it is dropped rather than crowded onto the next one: three taps with the middle
 * one missing is a countdown that is short, and a countdown that stutters is a countdown that
 * is wrong.
 */
const val TICK_LATENESS_MS = 250L

/**
 * Whether the tick for "[second] seconds left" may be sounded in a step that ends at
 * [stepEndAtMs], began at [stepStartAtMs], and whose boundary signal actually went out at
 * [boundaryAtMs].
 *
 * ── The rule, and why it is expressed against the SIGNAL rather than the step ───
 * A tick is only allowed once the boundary signal it would otherwise cut off has had
 * [BOUNDARY_GUARD_MS] to play.
 *
 * This used to be written as `second < stepDurationSec`, which is the same statement under
 * one assumption: that the step's boundary was signalled AT THE MOMENT THE STEP BEGAN. On a
 * three second rest the "three" tick would land exactly on the start of the step, cutting
 * the boundary beep off — the missing "next step" signal on 7:3 repeaters, once per rest.
 *
 * The assumption does not always hold, and that is this fix. A boundary can be NOTICED late:
 * the exact alarm is delivered a second after the moment, or the countdown coroutine
 * oversleeps, and the run is settled onto a step that is already part-way through. The
 * boundary signal is then fired late too (it is still worth firing — see
 * `SIGNAL_LATENESS_MS`), and the step is entered with less than its own length left. On a
 * three second rest entered with 1.8 s left, "two" is due 200 ms BEFORE the boundary signal
 * that has just gone out, so the loop fires it immediately and the rest silences its own
 * boundary again — by a different route, on the sessions where the phone was busy. Measuring
 * from the moment the signal was really made closes both routes with one rule.
 *
 * [boundaryAtMs] is null when this step's boundary was not signalled at all (a fresh start, a
 * run rebuilt from disk, a boundary passed over for being stale). The step's own start is
 * then the honest floor, which reproduces the old behaviour exactly.
 */
private fun tickAllowed(
    second: Int,
    stepEndAtMs: Long,
    stepStartAtMs: Long,
    boundaryAtMs: Long?,
    countdownTicks: Boolean,
): Boolean {
    if (!countdownTicks || second !in 1..TICK_SECONDS) return false
    // a boundary moment before the step began belongs to an earlier step and protects nothing
    val guardFrom = maxOf(boundaryAtMs ?: stepStartAtMs, stepStartAtMs)
    return (stepEndAtMs - second * 1000L) - guardFrom >= BOUNDARY_GUARD_MS
}

/**
 * Reads the clock and says what the countdown owes the user right now.
 *
 * Everything is derived from [now] against [RunState.stepEndAtMs], the same monotonic
 * reading the countdown itself is expressed in — the signals are not a second timeline that
 * can drift away from the first one. A run that is paused, finished or empty owes nothing
 * and asks to be woken immediately, because it is a caller's mistake to be looping at all.
 *
 * [boundaryAtMs] is the monotonic moment the CURRENT step's boundary signal was actually
 * sounded, or null when it was not. It is the caller's one piece of memory in an otherwise
 * stateless decision, and [tickAllowed] says what it is for.
 */
fun timerCue(
    steps: List<WorkoutStep>,
    state: RunState,
    countdownTicks: Boolean,
    now: Long,
    boundaryAtMs: Long? = null,
): TimerCue {
    if (steps.isEmpty() || state.finished || !state.running) {
        return TimerCue(boundary = false, tickSecond = null, wakeAtMs = now)
    }
    val settled = settleRun(steps, state, now)
    /*
     * ── A boundary is the state having MOVED, not the clock being past a number ───
     * This used to read `now >= settled.stepEndAtMs`, which cannot be true: [settleRun] walks
     * forward until exactly that is false, so the only way through was `finished` — the end of
     * the whole program. Every boundary INSIDE a program therefore reported `false` here, and
     * the countdown loop consequently never advanced the run at all. It went on ticking
     * correctly against a state frozen on the first step, because this function settles
     * internally before working out the ticks, so nothing looked wrong from the outside.
     *
     * What that cost: the loop is described everywhere as the first line and the exact alarm
     * as a backstop, and it was the other way round. The ALARM was the only thing that ever
     * moved a run from one step to the next or made a boundary signal. A backstop that is
     * really the only mechanism is a single point of failure, and on 7:3 the alarms it hangs
     * on are three and seven seconds apart — the shortest, most easily throttled, most easily
     * coalesced alarms the platform has to deliver. When one arrives late the boundary is
     * late; when it arrives more than SIGNAL_LATENESS_MS late into a seven second hang, the
     * lateness rule drops it in silence and the instruction to hang is simply never given.
     *
     * The right test is whether the run has passed a boundary since the state was written.
     */
    if (settled.finished || settled.stepIndex != state.stepIndex) {
        return TimerCue(boundary = true, tickSecond = null, wakeAtMs = now)
    }

    val end = settled.stepEndAtMs
    val start = end - steps[settled.stepIndex].durationMs
    // the second a countdown would be showing: 2500 ms left reads as "3"
    val second = ((end - now + 999) / 1000).toInt()

    fun allowed(candidate: Int) = tickAllowed(candidate, end, start, boundaryAtMs, countdownTicks)

    // how far past its own moment the tick for this second would be made. Zero when the loop
    // woke when it meant to; up to a second when it overslept. See [TICK_LATENESS_MS].
    val lateness = second * 1000L - (end - now)

    val due = second.takeIf { allowed(it) && lateness <= TICK_LATENESS_MS }
    // the next tick is the one below the current second, but never above the window: a step
    // entered with twenty seconds left owes its first tick at three, not at nineteen
    val nextTick = minOf(second - 1, TICK_SECONDS).takeIf { allowed(it) }

    return TimerCue(
        boundary = false,
        tickSecond = due,
        wakeAtMs = if (nextTick != null) end - nextTick * 1000L else end,
    )
}

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
    var skipped = state.skipped
    while (now >= current.stepEndAtMs) {
        /*
         * Passing this line means the clock ran out ON the step the run was standing on, which
         * is the one thing [RunState.skipped] is the absence of. It is cleared rather than
         * merely not set, because the Back button can reopen a step that was skipped a minute
         * ago: held to its end the second time, it was held, and the mark from the first
         * attempt would go on suppressing an effort that happened.
         */
        if (current.stepIndex in skipped) skipped = skipped - current.stepIndex
        val next = current.stepIndex + 1
        if (next > steps.lastIndex) {
            return current.copy(
                stepIndex = steps.lastIndex,
                running = false,
                finished = true,
                pausedLeftMs = 0,
                skipped = skipped,
            )
        }
        current = current.copy(
            stepIndex = next,
            stepEndAtMs = current.stepEndAtMs + steps[next].durationMs,
            skipped = skipped,
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
 *
 * The step being jumped out of is MARKED ([RunState.skipped]) rather than left to be judged by
 * the position the run ends up in. It did not run its course, so §18.20 says it did not happen,
 * and the journal must not be offered an effort nobody made. The mark is made on the way out of
 * the step, here, because this is the only moment at which the difference between "counted
 * through" and "jumped over" exists at all.
 */
fun skipStep(steps: List<WorkoutStep>, state: RunState, now: Long): RunState {
    if (steps.isEmpty() || state.finished) return state
    val settled = settleRun(steps, state, now)
    if (settled.finished) return settled
    val marked = settled.copy(skipped = settled.skipped + settled.stepIndex)
    val next = marked.stepIndex + 1
    if (next > steps.lastIndex) {
        // skipping the last step ends the run, and the run then reports `finished` — which is
        // exactly the reading that would otherwise count this step. Marked before that happens.
        return marked.copy(stepIndex = steps.lastIndex, running = false, finished = true, pausedLeftMs = 0)
    }
    return restartAt(steps, marked, next, now)
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
 *
 * ── It does NOT mark the step, and that is a limit worth stating ────────────────
 * Cutting a WORK step short this way ends it through the clock, so [settleRun] carries the run
 * past it and the effort is counted — at its PLANNED length, since that is the only length a
 * step has ([CompletedSet.workSec]). §18.20 closes the Skip button, which is the route that
 * silently overstated whole sets; this one overstates a single effort by however much was taken
 * off it, and closing it properly means recording what each effort actually lasted, which is
 * the part §18.20 deliberately defers. Until then the honest reading is: minus on a rest is
 * ordinary use, minus on a hang is the user shortening their own record.
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
    /**
     * What kind of run this is, recorded at the start because it cannot be recovered from
     * the steps afterwards: a rest between sets is also a one-step program carrying an
     * exercise id. It decides whether finishing offers to write sets into the journal
     * (domain/RunLog.kt). Defaulted, so a snapshot written by an older build still reads.
     */
    @SerialName("origin") val origin: RunOrigin = RunOrigin.PROGRAM,
    /**
     * Which hand this run trains, for a one-sided exercise started FROM ITS CARD — the same
     * information the manual entry form is handed by [xyz.oleolegka.gachimuchi.ui.screens.WorkoutLogActions.startProtocolSet].
     * A protocol-led exercise draws two cards and a tap on either used to start the identical
     * run, so the sets it produced could not say which hand had trained; this is what closes
     * that gap. Null both for an exercise that has only one card and for a run started from
     * the timer tab, which is not any one card's tap and has no side to report.
     *
     * Stored as the payload code ([HoldSide.code]), the same convention as [OrderedExercise.side]
     * and [HoldSet.side], so a run and the set it becomes agree byte for byte on what a side is.
     */
    @SerialName("side") val side: String? = null,
)
