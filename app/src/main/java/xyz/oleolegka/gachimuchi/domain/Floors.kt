package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rest floors: the countdowns that may all run at once, and the rules for when they are
 * allowed to make a noise.
 *
 * ── Two kinds of countdown, told apart by who is in charge ───────────────────────
 * A superset is several exercises interleaved — the bench rests while the abs work — so
 * the app needs several rests running side by side. But not everything that counts down
 * can be parallel, and the dividing line is not length:
 *
 *   A FLOOR says "not before". It is the rest between sets of anything: the app is telling
 *   you that you MAY go, and being told late costs nothing. Any number of floors can run
 *   at once, and any one of them can be delayed, muted or summarised after the fact
 *   without damage. That is this file.
 *
 *   A CONDUCTOR says "now". It is the protocol INSIDE a set — seven seconds on, three
 *   seconds off, six times — where a late instruction ruins the set it was meant to time.
 *   There is at most one conductor at any moment and it owns the screen and the speaker.
 *   That is domain/Runner.kt driven by timer/TimerController.kt, and this file does not
 *   touch it.
 *
 * Everything that follows falls out of that asymmetry: a floor's signal may be moved, and
 * a conductor's may not, so whenever the two would collide it is the floor that yields.
 *
 * ── Why the signals need rules at all ───────────────────────────────────────────
 * A [android.media.ToneGenerator] plays one tone at a time and a [android.os.Vibrator]
 * plays one waveform at a time — starting a second one cuts the first off mid-note. That is
 * not hypothetical: the countdown ticks used to silence the step-boundary beep on 7:3
 * repeaters, twenty-four times a session, until `timerCue` was made to keep them apart
 * (domain/Runner.kt). Parallel floors reintroduce exactly that hazard, several times over,
 * so the same treatment is applied here: what may sound, and when, is decided by pure
 * arithmetic that is testable on the JVM, and the caller only obeys.
 *
 * ── Time, and which clock ───────────────────────────────────────────────────────
 * Every "now" is a reading passed in from outside; no clock is read in this file. The
 * running arithmetic is `SystemClock.elapsedRealtime` throughout, and a floor stores the
 * MOMENT IT IS READY AT rather than "seconds left", for the reason spelled out at the top of
 * domain/Runner.kt: a decrementing counter is a lie as soon as the process is frozen, and a
 * floor spends most of its life frozen in a pocket.
 *
 * The wall clock is consulted at exactly one point — [carriedAcrossReboot], where the
 * monotonic clock has been reset out from under a floor and the wall clock is the only
 * remaining witness to when the rest was due. Nowhere else, because it is the one clock a
 * user or an NTP sync can move.
 */

/**
 * How far apart two floor signals are pushed when they would otherwise land together.
 *
 * Two seconds is enough for a boundary beep (350 ms) and its buzz (620 ms of waveform) to
 * finish with room to spare, and short enough that the second floor is still answering the
 * moment the user is standing in. The delay is free precisely because a floor is a "not
 * before": being told two seconds late that a rest is over changes nothing.
 */
const val FLOOR_STAGGER_MS = 2_000L

/**
 * How late a moment may be and still be worth a noise — for a floor here, and for a step
 * boundary in timer/TimerController.kt, which reads this same constant.
 *
 * ONE RULE, ONE HOME. It used to be two: this, and a private copy in the timer, with a
 * comment on each asking the other to stay equal. Nothing checked, and nothing would have —
 * the two numbers being different is not a compile error, not a test failure and not
 * observable on a phone until the day somebody tunes one of them and a rest starts gonging
 * an hour late while the step boundaries stay quiet.
 *
 * The reason is the same on both sides: the path that sounds a signal is also the path a
 * process woken by an alarm runs, AND the path the app runs when it is simply opened. An
 * alarm arrives within a fraction of a second of the moment; a person opening the app arrives
 * minutes or hours after it, and a phone that gongs on the alarm stream for a rest that ended
 * before dinner is the bug this number exists to prevent. Five seconds sits well above the
 * worst case of the mechanism that has to keep working (an exact alarm waking a dead process:
 * a broadcast plus a controller construction, under a second even cold) and far below the
 * shortest gap that could plausibly be a person picking the phone up.
 *
 * Past the window a floor is still resolved — marked as dealt with, silently, so it does not
 * sit pending forever.
 */
const val SIGNAL_LATENESS_MS = 5_000L

/**
 * One rest running against one exercise.
 *
 * At most one floor per exercise is expected — [withFloor] is what keeps that true, and
 * starting a second rest for the same exercise means the first one was superseded.
 *
 * [exerciseName] is denormalised onto the floor on purpose, the same way [RunSnapshot]
 * carries `programName`: the summary line may have to be produced by a process that an alarm
 * has just woken from the dead, and reaching into the database to find out what to call a
 * floor at that moment is work this can simply avoid.
 */
@Serializable
data class RestFloor(
    @SerialName("exercise_id") val exerciseId: Long,
    /** What to call it in a signal or a summary. */
    @SerialName("exercise_name") val exerciseName: String,
    /** Monotonic reading at which the rest is over. Everything is derived from this. */
    @SerialName("ready_at_ms") val readyAtMs: Long,
    /** [bootReference] when the floor was started; see [isFromPreviousBoot]. */
    @SerialName("boot_ref") val bootRef: Long,
    /** The length that was asked for, kept so a progress bar has a denominator. */
    @SerialName("ordered_ms") val orderedMs: Long,
    /** Wall clock at the start, for showing when the rest began across a clock change. */
    @SerialName("started_at_wall_ms") val startedAtWallMs: Long,
    /**
     * Whether this floor's moment has been dealt with — sounded, summarised, or passed over
     * in silence for being too late. A resolved floor is still shown; it just has nothing
     * left to say.
     */
    @SerialName("signalled") val signalled: Boolean = false,
    /**
     * Which card this rest belongs to, for an exercise trained one limb at a time — one of
     * [HoldSide]'s codes, or null for every exercise that is not.
     *
     * A floor is "at most one per exercise" — see [withFloor] — and that used to mean a superset
     * of the left and right hand cannot both be resting: marking the right hand's set replaced
     * the left hand's still-running countdown, because both wrote to the same exerciseId. This
     * is what makes the two independent: the identity a floor is kept and replaced by is
     * (exerciseId, side) together, not exerciseId alone.
     */
    @SerialName("side") val side: String? = null,
)

/** A floor of [orderedMs] started now, stamped with both clocks. */
fun startFloor(
    exerciseId: Long,
    exerciseName: String,
    orderedMs: Long,
    nowElapsed: Long,
    nowWall: Long,
    side: String? = null,
): RestFloor = RestFloor(
    exerciseId = exerciseId,
    exerciseName = exerciseName,
    readyAtMs = nowElapsed + orderedMs,
    bootRef = bootReference(nowWall, nowElapsed),
    orderedMs = orderedMs,
    startedAtWallMs = nowWall,
    side = side,
)

/**
 * [floor] added, replacing any floor already running for the same CARD — the same exercise
 * AND the same side. Two cards of one one-sided exercise therefore keep two floors, each
 * replaced only by a fresh rest of its own side; see [RestFloor.side].
 */
fun List<RestFloor>.withFloor(floor: RestFloor): List<RestFloor> =
    filterNot { it.exerciseId == floor.exerciseId && it.side == floor.side } + floor

// --- the rest a floor actually measured -------------------------------------------------

/**
 * The rest to record as ACTUALLY MEASURED, or null when there is nothing honest to write.
 *
 * A floor's [startedAtWallMs] is the wall-clock moment the PREVIOUS set of this exercise was
 * recorded — the only reading in the app of when a rest genuinely began, as opposed to
 * [xyz.oleolegka.gachimuchi.domain.secondsBetween]'s guess from the gap between two write
 * times. `nowWallMs - startedAtWallMs` is therefore the true pause, known exactly, the moment
 * the NEXT set of the same exercise is recorded — see [xyz.oleolegka.gachimuchi.data.ActivityRepository.recordActualRest],
 * the caller, for how it lands on the set that earned it.
 *
 * ── Rest or break? The same line the DERIVED gap already draws ──────────────────
 * A floor left running for an hour is still arithmetically exact — the wall clock does not
 * lie — but an hour is not a rest between sets of one exercise, it is the workout being put
 * down and picked back up. [MAX_REST_SEC] already answers this question for the gap
 * [secondsBetween] derives, on exactly this reasoning (domain/Session.kt), and a MEASURED
 * gap past it is the same break wearing a more precise number. Past the cutoff this returns
 * null, and the set stays with whatever [secondsBetween] would already say about it — which,
 * past twenty minutes, is also null. One rule for what counts as a rest, not two that could
 * one day disagree about the same twenty-minute line.
 *
 * ── A negative reading is a clock, not a measurement ─────────────────────────────
 * [startedAtWallMs] is a wall-clock reading (see the note at the top of this file: it is the
 * one clock a user or an NTP sync can move), and [nowWallMs] is read moments later on a
 * device that could have had its clock moved backwards in between. A negative gap is not a
 * rest that ran in reverse; it is that clock having moved, and null is the honest answer for
 * a number this function cannot vouch for.
 */
fun RestFloor.actualRestSec(nowWallMs: Long): Double? {
    val elapsedSec = (nowWallMs - startedAtWallMs) / 1000.0
    return elapsedSec.takeIf { it in 0.0..MAX_REST_SEC }
}

// --- what a floor looks like right now -------------------------------------------------

/**
 * A floor as a screen needs it at one instant.
 *
 * [overdueMs] is the field this exists for. "Ready" on its own is the least useful thing a
 * rest timer can say: between sets people miss the moment constantly, and the difference
 * between "ready" and "ready, and you have been standing here for two and a half minutes"
 * is the difference between a timer that reports and a timer that is worth reading.
 */
data class FloorProgress(
    val ready: Boolean,
    /** Milliseconds still to wait. Zero once ready. */
    val remainingMs: Long,
    /** Milliseconds spent ready. Zero until then. */
    val overdueMs: Long,
    /**
     * How much of the ordered rest is behind, 0..1, for the progress bar. Float rather than
     * Double because it is consumed by a Compose progress indicator and nothing else.
     */
    val fraction: Float,
)

/**
 * Where the floor stands at [now].
 *
 * The bar fills to the end and STOPS: an overrun is reported as [overdueMs], never as a
 * fraction above one, because a bar that keeps growing past its track says nothing a
 * duration does not say better. A floor ordered with no length at all reads as complete
 * rather than as an empty bar — there was nothing to wait for.
 */
fun RestFloor.progressAt(now: Long): FloorProgress {
    val remaining = (readyAtMs - now).coerceAtLeast(0)
    val overdue = (now - readyAtMs).coerceAtLeast(0)
    val fraction = if (orderedMs <= 0) {
        1f
    } else {
        ((orderedMs - remaining).toFloat() / orderedMs.toFloat()).coerceIn(0f, 1f)
    }
    return FloorProgress(
        ready = now >= readyAtMs,
        remainingMs = remaining,
        overdueMs = overdue,
        fraction = fraction,
    )
}

// --- surviving a reboot ----------------------------------------------------------------

/**
 * Whether this floor was started before the device restarted, which makes [readyAtMs] a
 * reading of a clock that no longer exists.
 *
 * Deliberately the same test, with the same tolerance, that a persisted run gets
 * ([isRunStale]): one notion of "a different boot" for the whole app, so a floor and the
 * conductor can never disagree about whether the machine has been restarted underneath them.
 */
fun RestFloor.isFromPreviousBoot(currentBootRef: Long): Boolean =
    isRunStale(bootRef, currentBootRef)

/**
 * The same floor, carried onto the clock of the boot that is running now.
 *
 * ── Why a floor survives a restart when a conductor's run does not ──────────────
 * A run cannot be resumed because its state is a SCHEDULE INSIDE a set: a monotonic reading
 * for every step of it. After a restart those readings mean nothing, and "carry on from the
 * middle of a hang" is not a thing that can be done at all — which is why a stale run is
 * thrown away (timer/TimerController.restore).
 *
 * A floor is one sentence: NOT BEFORE MOMENT T. That sentence is written down twice — once
 * on the monotonic clock as [RestFloor.readyAtMs], which the restart killed, and once on the
 * wall clock as [RestFloor.startedAtWallMs] plus [RestFloor.orderedMs], which it did not.
 * Restoring the floor is therefore arithmetic and not a guess, so it is restored. It used to
 * be declared ready instead, and that was wrong in the one direction a "not before" can be
 * wrong in: a reboot can take less time than the rest it interrupted, and saying "you may go"
 * early is the only way this kind of timer does harm.
 *
 * ── The wall clock is the weak part of this, and it is clamped in one direction ─
 * It is the one clock a user or an NTP sync can move. Moved BACKWARDS, it makes the
 * remainder come out longer than the rest that was ever ordered — a countdown growing past
 * its own length — so the remainder is capped at [RestFloor.orderedMs]. Resting a few seconds
 * short of what was asked for is by a wide margin the cheaper of the two errors.
 *
 * Moved FORWARDS it cannot be clamped, because a forward jump is indistinguishable from time
 * genuinely having passed. It matures a floor early, and what stands between that and a wrong
 * signal is the lateness rule in [floorCue]: a floor matured by a jump of any real size is far
 * outside the window and is resolved without a sound.
 *
 * ── Nothing is decided about signalling here ────────────────────────────────────
 * A floor that turns out to have matured while the device was down comes back with its moment
 * in the past — possibly before this boot began, which is negative and honest — and [floorCue]
 * then applies to it exactly the rule it applies to every other late floor: silence if the
 * moment is stale, a signal if the machine happened to come up within seconds of it. One rule
 * in one place, rather than a second copy of it here.
 */
fun RestFloor.carriedAcrossReboot(
    nowElapsed: Long,
    nowWall: Long,
    currentBootRef: Long,
): RestFloor {
    val remaining = (startedAtWallMs + orderedMs - nowWall).coerceAtMost(orderedMs)
    return copy(readyAtMs = nowElapsed + remaining, bootRef = currentBootRef)
}

/**
 * Floors as a fresh process must take them up: anything left behind by an earlier boot is
 * carried onto the current clock ([carriedAcrossReboot]), everything else is untouched.
 *
 * Within one boot the monotonic reading is authoritative and is left exactly alone. It is the
 * better of the two clocks — nothing can move it — so the wall clock is consulted only when
 * the monotonic one has been reset out from under the floor.
 */
fun settleFloors(
    floors: List<RestFloor>,
    nowElapsed: Long,
    nowWall: Long,
    currentBootRef: Long,
): List<RestFloor> = floors.map {
    if (it.isFromPreviousBoot(currentBootRef)) {
        it.carriedAcrossReboot(nowElapsed, nowWall, currentBootRef)
    } else {
        it
    }
}

// --- when a floor is allowed to sound --------------------------------------------------

/**
 * Floors in the order their signals belong in: earliest ready first, and the smaller
 * exercise id breaks a tie so that two rests started in the same millisecond always sound
 * in the same order rather than in whatever order a list happened to be built.
 */
private fun floorOrder(floors: List<RestFloor>): List<Int> =
    floors.indices.sortedWith(compareBy({ floors[it].readyAtMs }, { floors[it].exerciseId }))

/**
 * The moment each floor is allowed to sound at, indexed like [floors].
 *
 * A floor may sound no earlier than it is ready and no sooner than [FLOOR_STAGGER_MS] after
 * the floor before it, so a pile-up is spread out instead of being played on top of itself:
 * three rests ending together sound at t, t+2 s and t+4 s. This is the collision rule, and
 * it works because it is applied to the only kind of signal that can afford to wait.
 *
 * Floors that have already been dealt with keep their place in the chain. They occupy the
 * channel they sounded on, so a floor maturing a few hundred milliseconds after one that has
 * just beeped is still pushed clear of it. That costs nothing for a floor resolved long ago,
 * because the chain is anchored to real moments of readiness — including the moments of
 * floors carried across a restart, which land where they truly matured rather than at the
 * instant the restart happened.
 */
private fun dueMoments(floors: List<RestFloor>): LongArray {
    val due = LongArray(floors.size)
    var previous: Long? = null
    for (i in floorOrder(floors)) {
        val ready = floors[i].readyAtMs
        val earliest = previous?.plus(FLOOR_STAGGER_MS)
        val at = if (earliest != null && earliest > ready) earliest else ready
        due[i] = at
        previous = at
    }
    return due
}

/**
 * The next monotonic moment a floor has something to say, or null when none has.
 *
 * This is what the service layer sets its one exact alarm to. Null while a conductor is
 * running is the whole point rather than an omission: floors cannot sound then (see
 * [floorCue]), the conductor is already holding a wake-up of its own at a moment it cares
 * about far more, and a second alarm would only be a second thing to keep in step.
 */
fun nextFloorSignalMs(floors: List<RestFloor>, conductorRunning: Boolean): Long? {
    if (conductorRunning) return null
    val due = dueMoments(floors)
    return floors.indices.filter { !floors[it].signalled }.minOfOrNull { due[it] }
}

/**
 * What the floors owe at this instant, and when to look again.
 *
 * [signal] is at most ONE floor per call, never a list. Two signals at one instant is the
 * bug this file exists to avoid, and the caller cannot be trusted to space them out itself —
 * it is a coroutine or a broadcast receiver, and by the time it has played the first tone the
 * moment for the second has already passed. When a second floor is also due (which needs the
 * process to have overslept by more than the stagger but less than the lateness window), it
 * is left pending and [wakeAtMs] comes back [FLOOR_STAGGER_MS] out.
 *
 * [floors] is the list to store: whatever this call resolved is already marked on it. The
 * floor handed back in [signal] is the one to announce, in the shape the caller knew it —
 * its copy inside [floors] is the marked one.
 */
data class FloorCue(
    /** The floor to sound now, or null for silence. */
    val signal: RestFloor?,
    /** The floors after this cue, with everything it settled marked. */
    val floors: List<RestFloor>,
    /** The monotonic moment to look again, or null when nothing is waiting. */
    val wakeAtMs: Long?,
)

/**
 * Reads the clock and says which floor, if any, may make a noise.
 *
 * ── A running conductor mutes every floor ───────────────────────────────────────
 * Floors keep counting while [conductorRunning] — they are wall-independent arithmetic and
 * nothing about them stops — but none of them sounds. A beep announcing that the bench is
 * free, landing in the middle of a seven-second hang, is worse than useless: it arrives at
 * the one moment its reader cannot act on it, and it does so by cutting off the tone that
 * says the hang is over. Nothing is resolved while muted either, so nothing is lost; see
 * [releaseFloors] for what happens when the conductor stops.
 *
 * ── A floor nobody was there to hear is not sounded later ───────────────────────
 * A moment more than [SIGNAL_LATENESS_MS] old is resolved without a sound. The process
 * that reaches it is one that has been asleep for an hour, and a rest that ended an hour ago
 * is not news worth an alarm-volume tone.
 *
 * The same rule, with nothing added, is what handles a rest that ran out while the device was
 * off: [carriedAcrossReboot] puts such a floor back on this boot's clock at an old moment, and
 * old moments are silent. A restart that happened to finish within seconds of the rest ending
 * is the one case that still sounds, and it should.
 */
fun floorCue(floors: List<RestFloor>, conductorRunning: Boolean, now: Long): FloorCue {
    if (conductorRunning) return FloorCue(signal = null, floors = floors, wakeAtMs = null)

    val due = dueMoments(floors)
    val settled = floors.toMutableList()
    var signal: RestFloor? = null
    for (i in floorOrder(floors)) {
        val floor = floors[i]
        if (floor.signalled || due[i] > now) continue
        if (now - due[i] > SIGNAL_LATENESS_MS) {
            settled[i] = floor.copy(signalled = true)
            continue
        }
        // one tone at a time: the rest of the backlog is re-spaced rather than played over
        if (signal != null) continue
        signal = floor
        settled[i] = floor.copy(signalled = true)
    }

    var wake = nextFloorSignalMs(settled, conductorRunning = false)
    if (signal != null && wake != null) wake = maxOf(wake, now + FLOOR_STAGGER_MS)
    return FloorCue(signal = signal, floors = settled, wakeAtMs = wake)
}

// --- coming out of the mute ------------------------------------------------------------

/** One floor that came due while nobody could be told, and how long it has been waiting. */
data class ReadyFloor(val floor: RestFloor, val readyForMs: Long)

/** The floors to store, and what to say about the ones that matured out of earshot. */
data class FloorRelease(val ready: List<ReadyFloor>, val floors: List<RestFloor>)

/**
 * Ends the mute a conductor imposed: every floor already ready is resolved AT ONCE, in
 * silence, and reported as one summary.
 *
 * ── Why a summary and not a burst of beeps ──────────────────────────────────────
 * The alternative is firing the signals that were held back, and it is indefensible. A set
 * ends and the phone plays two or three separate tones for rests that finished at some point
 * during it — the user cannot tell which is which, cannot tell them from the tone that ended
 * the set, and learns nothing they could not read. One line saying "Bench has been ready for
 * 1:20" carries strictly more than the beeps do and interrupts nothing.
 *
 * Readiness is measured from [RestFloor.readyAtMs] and not from the staggered moment: the
 * stagger exists only to keep tones apart, and there are no tones here.
 *
 * The caller decides when this runs — it is the moment a conductor stops. Calling it while
 * one is still running would silence floors that should still be allowed to sound.
 */
fun releaseFloors(floors: List<RestFloor>, now: Long): FloorRelease {
    val ready = ArrayList<ReadyFloor>()
    val settled = floors.toMutableList()
    for (i in floorOrder(floors)) {
        val floor = floors[i]
        if (floor.signalled || floor.readyAtMs > now) continue
        settled[i] = floor.copy(signalled = true)
        ready.add(ReadyFloor(floor = floor, readyForMs = now - floor.readyAtMs))
    }
    return FloorRelease(ready = ready, floors = settled)
}

/**
 * Whole seconds a floor has been ready, rounded DOWN — the opposite of a countdown, which
 * rounds up ([ceilSeconds]). Time already spent is reported as the amount that has certainly
 * passed; a rest ready for 1:20.9 has not been ready for 1:21.
 */
fun ReadyFloor.readyForSec(): Int = (readyForMs / 1000).coerceAtLeast(0).toInt()

/**
 * The summary line for [ready], or null when there is nothing to summarise.
 *
 * "Bench has been ready for 1:20", and with more than one, "Bench has been ready for 1:20,
 * Abs for 0:30" — the verb is stated once and carries across the list, which is how the
 * sentence is read out loud anyway. A floor that matured in the same second the conductor
 * stopped drops the duration rather than claiming it has been ready for 0:00.
 */
fun floorSummaryText(ready: List<ReadyFloor>): String? {
    if (ready.isEmpty()) return null
    val head = ready.first()
    val headSec = head.readyForSec()
    val opening = if (headSec <= 0) {
        "${head.floor.exerciseName} is ready"
    } else {
        "${head.floor.exerciseName} has been ready for ${formatClock(headSec)}"
    }
    return ready.drop(1).fold(opening) { line, item ->
        val sec = item.readyForSec()
        val tail = if (sec <= 0) item.floor.exerciseName else "${item.floor.exerciseName} for ${formatClock(sec)}"
        "$line, $tail"
    }
}
