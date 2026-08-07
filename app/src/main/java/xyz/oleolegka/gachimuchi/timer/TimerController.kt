package xyz.oleolegka.gachimuchi.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.TimerStore
import xyz.oleolegka.gachimuchi.domain.NUDGE_SEC
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.RunOutcome
import xyz.oleolegka.gachimuchi.domain.RunPhase
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.SIGNAL_LATENESS_MS
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.WorkoutStep
import xyz.oleolegka.gachimuchi.domain.adjustStep
import xyz.oleolegka.gachimuchi.domain.bootReference
import xyz.oleolegka.gachimuchi.domain.currentStep
import xyz.oleolegka.gachimuchi.domain.flatten
import xyz.oleolegka.gachimuchi.domain.isRunStale
import xyz.oleolegka.gachimuchi.domain.pauseRun
import xyz.oleolegka.gachimuchi.domain.phase
import xyz.oleolegka.gachimuchi.domain.previousStep
import xyz.oleolegka.gachimuchi.domain.resumeRun
import xyz.oleolegka.gachimuchi.domain.runOutcome
import xyz.oleolegka.gachimuchi.domain.salvagedOutcome
import xyz.oleolegka.gachimuchi.domain.settleRun
import xyz.oleolegka.gachimuchi.domain.skipStep
import xyz.oleolegka.gachimuchi.domain.startRun
import xyz.oleolegka.gachimuchi.domain.stepRemainingMs
import xyz.oleolegka.gachimuchi.domain.timerCue
import xyz.oleolegka.gachimuchi.domain.totalRemainingMs

/**
 * The one object that owns a running timer, for the whole process.
 *
 * Everything that can change a run goes through here — the screens, the notification
 * buttons and the alarm receiver alike — and each change does the same four things in the
 * same order: settle the state against the clock, persist it, re-arm the wakeup, redraw
 * the notification. Having one path means there is no way for the notification, the
 * screen and the alarm to end up describing three different timers.
 *
 * ── Three mechanisms, because one is not enough ─────────────────────────────────
 *
 * 1. A FOREGROUND SERVICE keeps the process from being frozen. Without one, Android
 *    suspends the app within about twenty seconds of the screen going off — that is the
 *    documented, reproducible-on-stock-Pixels failure of the app this feature is modelled
 *    on, which has no service and counts callbacks instead of time. There is exactly ONE,
 *    and the rests between sets now keep it up too: see [serviceNeeded] and [syncService].
 * 2. A PARTIAL WAKE LOCK keeps the CPU from suspending while a run is live. A foreground
 *    service stops the process being killed; it does not stop the device sleeping, and a
 *    Tabata interval is ten seconds long. The lock is taken only while the clock is
 *    actually moving and is released on pause, stop and finish, and it carries a timeout
 *    so a bug cannot pin the CPU awake indefinitely.
 * 3. An EXACT ALARM at the end of the current step is the backstop for the case the other
 *    two fail to hold: it fires the receiver, which rebuilds this controller from the
 *    persisted snapshot and signals. Honest caveat: in deep Doze the platform throttles
 *    `setExactAndAllowWhileIdle` to roughly one firing per app every nine minutes, so this
 *    backstop is dependable for a three-minute rest and NOT dependable for the individual
 *    intervals of a Tabata. It is a second line, not the first.
 *
 * ── What the countdown is computed from ─────────────────────────────────────────
 * [SystemClock.elapsedRealtime] only — monotonic, unaffected by time zones or the user
 * changing the clock, and it keeps running while the device is asleep. Nothing counts
 * ticks; see domain/Runner.kt.
 *
 * ── This is the CONDUCTOR, and it is not the only countdown any more ────────────
 * Exactly one run exists at a time and it owns the screen, the speaker and the wake lock,
 * because its instructions are orders that are ruined by being late. The rests BETWEEN sets
 * are a different kind of thing — several at once, one per exercise, each merely saying "not
 * before" — and they live in [floors], which this class owns and drives. domain/Floors.kt
 * states the asymmetry in full. The points where the two meet are all in this file: a run
 * starting mutes the floors, a run ending releases them, the two must never arm the same
 * alarm, and — since the rests were given the same reliability as the run — they SHARE the
 * one foreground service and the one notification that comes with it. The conductor wins
 * both: it takes the notification while it runs, and gives it back when it stops.
 *
 * The one thing the rests do NOT share is the wake lock. FloorController says why, at
 * length: a rest tolerates arriving a second late, and holding the CPU for every rest of a
 * ninety-minute session is not a second-order cost.
 */
class TimerController internal constructor(context: Context) {
    /*
     * The constructor is internal rather than private so that tests can build a fresh
     * controller instead of sharing the process-wide one. A test hook that reset the
     * singleton would be worse: it would exist only for tests and would be one more way
     * for production code to end up with two controllers fighting over one notification.
     * Application code uses [get] and never this.
     */

    private val app = context.applicationContext
    private val store = TimerStore(app)
    private val signals = Signals(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val speaker = Speaker(app)

    /**
     * The parallel rests. Owned here rather than standing beside this class, because the one
     * race a floor cannot close by itself is a conductor starting while a floor is deciding
     * whether to sound — see the ownership note at the top of FloorController.
     *
     * Declared after [signals] and [speaker] so the property initialisers run in an order
     * that has both of them built by the time this one asks for them.
     */
    val floors = FloorController(
        context = app,
        signals = signals,
        settings = store.settings,
        // said out loud only when announcements are on, and APPENDED rather than flushed, so
        // it queues behind "Done" instead of erasing it. See Speaker.speak.
        announce = { text -> if (store.settings.value.speak) speaker.speak(text, replacing = false) },
        // the rests can now keep the service up by themselves, but they never start or stop
        // it: only this class can see both claimants at once. See [serviceNeeded].
        syncService = { syncService(mayStart = true) },
    )

    val settings get() = store.settings
    val enabled get() = store.enabled

    private val _run = MutableStateFlow<RunSnapshot?>(null)

    /** The live run, or null when nothing is counting. */
    val run: StateFlow<RunSnapshot?> = _run.asStateFlow()

    private val _outcome = MutableStateFlow<RunOutcome?>(null)

    /**
     * The last run that ended with something worth writing into the journal, waiting for the
     * screen to offer it (domain/RunLog.kt). Everything but a rest between sets lands here.
     *
     * PERSISTED, unlike in the first version of this feature. A run ends with the phone in a
     * pocket; by the time the screen is looked at, Android may well have killed the process,
     * and an offer that lived in memory went with it — taking the only record of a session
     * that actually happened. The stored copy carries the moment the run ended so a late
     * offer can say so, and the date it ended on so its sets are written under the right day.
     */
    val outcome: StateFlow<RunOutcome?> = _outcome.asStateFlow()

    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The step the last signal was fired for, so a redraw cannot double-signal. */
    private var signalledStep: Int = -1

    /** Whole seconds remaining at the last countdown tick, so each second ticks once. */
    private var lastTickSecond: Int = -1

    /**
     * Bumped by anything that makes the audio engine worth keeping or worth dropping, so a
     * deferred release cannot tear down the engine a newer run has started using.
     */
    private var signalEra: Int = 0

    init {
        restore()
        /*
         * The floors are taken up only now, and with an answer to "is a conductor running?"
         * that is only knowable once [restore] has finished. Doing it inside FloorController's
         * own constructor would mean settling and arming against a process that is about to
         * rebuild a run from disk — and a floor that matured while the phone was off would
         * beep over the protocol being restored around it.
         */
        floors.takeUp(conductorRunning = _run.value != null)
    }

    // --- what the screens call ---------------------------------------------------------

    fun setEnabled(value: Boolean) {
        store.setEnabled(value)
        if (value) {
            prepareSpeech()
        } else {
            stop()
            // and the rests go with it: a timer switched off that keeps buzzing about a bench
            // twenty minutes later is the switch not meaning what it says
            floors.clearAll()
        }
    }

    /**
     * Finds out whether this device can speak at all.
     *
     * Called when the timer screen is opened and when the timer is switched on, NOT when
     * the announcement setting is turned on — the setting cannot honestly be offered
     * before the answer is known, and on a phone without Google services the answer is
     * often no. Binding an engine is cheap and happens once.
     */
    fun prepareSpeech() = speaker.prepare()

    fun updateSettings(settings: xyz.oleolegka.gachimuchi.domain.TimerSettings) {
        store.update(settings)
        if (settings.speak) speaker.prepare()
        refresh()
    }

    /**
     * Starts [program]. Any run already in progress is replaced without ceremony — asking
     * "are you sure" between sets is worse than the occasional lost countdown, and the
     * thing that was running was almost certainly a rest that has been superseded.
     */
    fun start(
        program: WorkoutProgram,
        exerciseId: Long? = null,
        origin: RunOrigin = RunOrigin.PROGRAM,
    ) {
        val steps = program.flatten()
        if (steps.isEmpty()) return
        /*
         * FIRST, before any state exists. A floor checks whether a conductor is running at
         * the instant it is about to sound, and it has no way to see one that starts a
         * hundred milliseconds later — so the mute has to be in place before this method
         * builds anything. Doing it after the snapshot, or leaving FloorController to notice
         * the run appearing, both leave that window open.
         */
        floors.conductorStarted()
        val now = SystemClock.elapsedRealtime()
        signalledStep = -1
        lastTickSecond = -1
        // an offer from the previous run is stale the moment a new one starts
        clearOutcome()
        // cancel any pending teardown and open the audio track NOW, so the first boundary
        // pays for a beep and not for building the thing that beeps
        signalEra++
        scope.launch { signals.prime() }
        val snapshot = RunSnapshot(
            programId = program.id,
            programName = program.name,
            steps = steps,
            state = startRun(steps, now),
            bootRef = currentBootRef(),
            exerciseId = exerciseId,
            origin = origin,
        )
        NotificationManagerCompat.from(app).cancel(TimerNotifications.ID_ALERT)
        if (store.settings.value.speak) speaker.prepare()
        apply(snapshot, startService = true)
        announceStep(snapshot)
    }

    fun pause() = mutate { snapshot, now ->
        snapshot.copy(state = pauseRun(snapshot.steps, snapshot.state, now))
    }

    fun resume() = mutate { snapshot, now ->
        snapshot.copy(state = resumeRun(snapshot.steps, snapshot.state, now))
    }

    fun skip() = mutate(announce = true) { snapshot, now ->
        snapshot.copy(state = skipStep(snapshot.steps, snapshot.state, now))
    }

    fun previous() = mutate(announce = true) { snapshot, now ->
        snapshot.copy(state = previousStep(snapshot.steps, snapshot.state, now))
    }

    fun nudge(deltaSec: Int = NUDGE_SEC) = mutate { snapshot, now ->
        snapshot.copy(state = adjustStep(snapshot.steps, snapshot.state, now, deltaSec * 1000L))
    }

    /**
     * Ends the run and takes everything down with it: service, lock, alarm, notification.
     *
     * A run stopped by hand still produces an offer, built from the part that DID run —
     * three sets out of four is the ordinary way a hangboard session ends, and it is worth
     * as much in the journal as a complete one.
     */
    fun stop() {
        _run.value?.let { keepOutcome(it) }
        loop?.cancel()
        loop = null
        _run.value = null
        store.clearRun()
        cancelAlarm()
        releaseWakeLock()
        releaseSignalsAfterTheirTail()
        TimerNotifications.cancelAll(app)
        // before the service is touched, so the floors are released against a state where no
        // conductor exists any more AND so that they have said whether they still need it
        floors.conductorStopped()
        syncService(mayStart = false)
    }

    /** Re-reads the clock and redraws, without changing anything. */
    fun refresh() = mutate { snapshot, _ -> snapshot }

    /** The offer was answered (either way). Nothing is written here. */
    fun clearOutcome() {
        _outcome.value = null
        store.clearOutcome()
    }

    // --- what the service and the receiver call ----------------------------------------

    /**
     * Handles the backstop alarm: settles the state, which is what turns "the alarm went
     * off" into "the step, or the whole program, is over", and signals accordingly.
     */
    fun onAlarm() = mutate { snapshot, _ -> snapshot }

    /**
     * The notification the service must show right now, or null when nothing needs showing.
     *
     * The run first and the rests second, which is the same precedence the shade follows
     * while both are alive: one service, one notification, and the conductor wins.
     */
    fun currentNotification() =
        _run.value?.let { notificationFor(it) } ?: floors.currentNotification()

    /**
     * Whether the one foreground service still has a reason to exist.
     *
     * Either claimant is enough. A run needs it so the process is not frozen mid-protocol;
     * a rest needs it so the process is alive to run its own countdown instead of leaving the
     * whole feature standing on a Doze-throttled alarm (see the top of FloorController).
     *
     * Read by [TimerService] as well as by [syncService], because the service has to be able
     * to answer the question for itself: it may be started for a run that ends in the gap
     * before `onStartCommand` runs, and "is anything left" is not the same question as "was
     * there something when I was started".
     */
    internal fun serviceNeeded(): Boolean = _run.value != null || floors.needsService()

    // --- the machinery -----------------------------------------------------------------

    private fun currentBootRef(): Long =
        bootReference(System.currentTimeMillis(), SystemClock.elapsedRealtime())

    /**
     * Rebuilds a run left behind by a killed process.
     *
     * A snapshot from a previous boot is discarded rather than resumed: its end moments
     * are readings of a clock that no longer exists, so resuming would count down from an
     * arbitrary number. A rest between sets does not outlive a reboot in any case.
     *
     * Discarded, but no longer thrown away in silence — see [salvageAcrossReboot] for the
     * sets that were already done by the time the device went down.
     */
    private fun restore() {
        restoreOutcome()
        val saved = store.loadRun() ?: return
        if (isRunStale(saved.bootRef, currentBootRef())) {
            store.clearRun()
            salvageAcrossReboot(saved)
            return
        }
        signalledStep = saved.state.stepIndex
        val now = SystemClock.elapsedRealtime()
        apply(saved.copy(state = settleRun(saved.steps, saved.state, now)), startService = false)
    }

    /**
     * Keeps what a run got through before the device restarted.
     *
     * The snapshot itself cannot be resumed and is thrown away — that part was always right.
     * What was wrong was throwing away the SETS with it: the part that had already been
     * completed is a count of steps the run moved past, which owes nothing to the monotonic
     * clock and survives a reboot intact. A phone that ran out of battery on the last set of
     * a hangboard session used to lose the whole session without saying so.
     *
     * An offer restored from disk wins over this one. That offer is a run that genuinely
     * ended and was recorded as ending; this is a reconstruction of one that was interrupted,
     * and replacing the former with the latter would trade a fact for an estimate.
     */
    private fun salvageAcrossReboot(saved: RunSnapshot) {
        if (_outcome.value != null) return
        val outcome = salvagedOutcome(saved)
        if (!outcome.offersLogging) return
        if (outcome.isExpired(System.currentTimeMillis())) return
        _outcome.value = outcome
        runCatching { store.saveOutcome(outcome) }
    }

    /**
     * Picks up an offer left behind by a process that did not survive to show it.
     *
     * Unlike a run, an offer is expressed in WALL time and therefore does survive a reboot —
     * what it cannot survive is age. Past a day it is dropped: by then the day it belongs to
     * has been written by hand or not at all, and proposing sets against it would be the app
     * guessing about history rather than recording it.
     */
    private fun restoreOutcome() {
        val saved = store.loadOutcome() ?: return
        if (saved.isExpired(System.currentTimeMillis())) {
            store.clearOutcome()
            return
        }
        _outcome.value = saved
    }

    private inline fun mutate(
        announce: Boolean = false,
        transform: (RunSnapshot, Long) -> RunSnapshot,
    ) {
        val current = _run.value ?: return
        val now = SystemClock.elapsedRealtime()
        val settled = current.copy(state = settleRun(current.steps, current.state, now))
        val next = transform(settled, now)
        apply(next.copy(state = settleRun(next.steps, next.state, now)), startService = false)
        if (announce) announceStep(_run.value ?: return)
    }

    /**
     * The single write path: fire whatever signal the change implies, store the state,
     * re-arm the alarm and the wake lock, redraw the notification and restart the ticking
     * loop. Every command above funnels through here.
     *
     * ── The signal goes first, and that is the fix, not a tidy-up ───────────────
     * It used to come after `store.saveRun`, which is a SYNCHRONOUS SharedPreferences
     * `commit()` — a disk write — and before that after nothing at all only because the
     * notification came later still. Every step boundary therefore queued its beep behind
     * however long the write took, on the same thread. On a seven-second interval that is
     * invisible; on a three-second one, with the phone busy, it is the difference between a
     * signal on the beat and a signal that arrives after the step it announces has started.
     * Persisting matters, but it does not matter within the same fifty milliseconds, and the
     * beep does.
     */
    private fun apply(snapshot: RunSnapshot, startService: Boolean) {
        val stamped = snapshot.copy(bootRef = currentBootRef())
        val phase = stamped.state.phase()

        if (phase == RunPhase.FINISHED) {
            _run.value = stamped
            if (isMomentNow(stamped.state.stepEndAtMs)) fireFinish(stamped) else finishQuietly()
            keepOutcome(stamped)
            store.clearRun()
            cancelAlarm()
            releaseWakeLock()
            loop?.cancel()
            loop = null
            _run.value = null
            /*
             * After [fireFinish], deliberately. That is where "Done" is spoken, and it is
             * spoken with a flush; the floor summary asks to be appended, so putting the
             * release afterwards is what fixes the order instead of leaving it to whichever
             * of the two reaches the engine first.
             *
             * And BEFORE the service is taken down, which it did not use to be. The rests can
             * now be the reason the service is up, and stopping it here only to have the
             * floors ask for it back a line later would need a foreground start from the
             * background — refused since Android 12. So the service is asked once, at the end,
             * when both halves have had their say.
             */
            floors.conductorStopped()
            syncService(mayStart = false)
            return
        }

        _run.value = stamped
        maybeSignalBoundary(stamped)
        store.saveRun(stamped)
        scheduleAlarm(stamped)
        if (phase == RunPhase.RUNNING) acquireWakeLock(stamped) else releaseWakeLock()
        postNotification(stamped)
        syncService(mayStart = startService)
        restartLoop()
    }

    /**
     * Remembers what a run got through, and writes it down. A rest between sets and a run
     * that completed no effort leave the offer untouched rather than raising a dialog with
     * nothing in it; everything else is a workout that happened.
     */
    private fun keepOutcome(snapshot: RunSnapshot) {
        /*
         * The wall clock and the day are NOT read here. They are derived inside runOutcome
         * from the snapshot's own boot reference and the moment its last step ended, because
         * this function does not run when the run ends — it runs when the run is noticed to
         * have ended, and those are the same instant only when the app happened to be open.
         * The case that matters is the opposite one: the process is killed with the phone in
         * a pocket, and the outcome is materialised on the next launch, which may be the
         * following morning. Reading the clock here filed an evening session under the next
         * day and told the user it had just finished.
         */
        val outcome = runOutcome(snapshot = snapshot, now = SystemClock.elapsedRealtime())
        if (!outcome.offersLogging) return
        // memory first, disk second, and the disk write cannot take the offer down with it:
        // this runs on the path that ends a run, and an offer that exists is worth more than
        // an offer that is durable
        _outcome.value = outcome
        runCatching { store.saveOutcome(outcome) }
    }

    /**
     * Whether [momentMs] — a monotonic reading of when something happened — is close enough
     * to now that announcing it out loud still means anything.
     *
     * ── Why a signal needs a time test at all ───────────────────────────────────
     * The path that rebuilds this controller from disk is BOTH the recovery path and the
     * backstop path. When the process is killed mid-run, the exact alarm fires at the step
     * boundary, wakes a fresh process, and the rebuilt controller settling the stored state
     * is what produces the beep — that is the whole design of the backstop, so the signal
     * cannot simply be suppressed on restore.
     *
     * But the same rebuild happens when the user opens the app hours later, and it used to
     * gong and vibrate then too, on the alarm stream, at full volume, through a phone set to
     * silent, for a workout that had ended before dinner. The difference between the two is
     * not which code path ran, it is HOW LATE the moment is. Anything the alarm delivers
     * arrives within a fraction of a second of the boundary; anything a person opening an app
     * discovers is minutes or hours old.
     *
     * A moment of zero or in the future counts as now: that is a run being driven by hand
     * (start, skip, back), where the user is holding the phone and the signal is the answer
     * to a button they just pressed.
     *
     * The window itself is [SIGNAL_LATENESS_MS] in domain/Floors.kt, and it is shared rather
     * than copied: the rest floors apply the identical rule for the identical reason, and two
     * copies of one number is a divergence waiting for the first person who tunes it.
     */
    private fun isMomentNow(momentMs: Long): Boolean {
        if (momentMs <= 0) return true
        return SystemClock.elapsedRealtime() - momentMs <= SIGNAL_LATENESS_MS
    }

    /**
     * Fires the boundary signal once per step, whoever caused the step to change — as long
     * as the step began just now rather than while the process was dead ([isMomentNow]).
     */
    private fun maybeSignalBoundary(snapshot: RunSnapshot) {
        val index = snapshot.state.stepIndex
        if (index == signalledStep) return
        signalledStep = index
        lastTickSecond = -1
        val step = snapshot.steps.getOrNull(index) ?: return
        val startedAt = snapshot.state.stepEndAtMs - step.durationMs
        if (!isMomentNow(startedAt)) return
        signals.boundary(store.settings.value, step.kind)
    }

    /**
     * Ends a run that turns out to have finished a while ago: the same tidying as
     * [fireFinish] without the announcement. No tone, no vibration, and no "finished"
     * notification either — the offer to log the run is the honest way to raise a session
     * that ended hours ago, and it is raised anyway.
     */
    private fun finishQuietly() {
        releaseSignalsAfterTheirTail()
        NotificationManagerCompat.from(app).cancel(TimerNotifications.ID_RUNNING)
    }

    private fun fireFinish(snapshot: RunSnapshot) {
        val settings = store.settings.value
        signals.finish(settings)
        if (settings.speak) speaker.speak("Done")
        releaseSignalsAfterTheirTail()
        if (TimerNotifications.canPost(app)) {
            runCatching {
                NotificationManagerCompat.from(app).notify(
                    TimerNotifications.ID_ALERT,
                    TimerNotifications.alert(app, snapshot.programName, snapshot.steps.size == 1),
                )
            }
        }
        NotificationManagerCompat.from(app).cancel(TimerNotifications.ID_RUNNING)
    }

    private fun announceStep(snapshot: RunSnapshot) {
        if (!store.settings.value.speak) return
        val step = currentStep(snapshot.steps, snapshot.state, SystemClock.elapsedRealtime()) ?: return
        speaker.speak(
            when (step.kind) {
                StepKind.PREPARE -> "Get ready"
                StepKind.REST -> "Rest"
                StepKind.WORK -> step.name
            }
        )
    }

    /**
     * Wakes at the next thing that matters — the end of the step, or a second the countdown
     * has to tick — rather than once a second for the whole workout. Between those moments
     * the coroutine is simply not scheduled.
     *
     * The loop decides nothing: `timerCue` (domain/Runner.kt) reads the same monotonic clock
     * the countdown is expressed in and says what is due and when to look again. All this
     * does is sleep and obey, which is why the timing of the signals — including the case
     * this used to get wrong, a step shorter than the countdown window — is testable on the
     * JVM rather than only audible on a phone.
     */
    private fun restartLoop() {
        loop?.cancel()
        val snapshot = _run.value ?: return
        if (!snapshot.state.running) return
        loop = scope.launch {
            while (isActive) {
                val current = _run.value ?: return@launch
                if (!current.state.running) return@launch
                val settings = store.settings.value
                val cue = timerCue(
                    steps = current.steps,
                    state = current.state,
                    countdownTicks = settings.countdownTicks,
                    now = SystemClock.elapsedRealtime(),
                )

                if (cue.boundary) {
                    mutate { snap, _ -> snap }
                    announceStep(_run.value ?: return@launch)
                    return@launch
                }

                cue.tickSecond?.let { second ->
                    if (second != lastTickSecond) {
                        lastTickSecond = second
                        signals.tick(settings)
                    }
                }

                delay((cue.wakeAtMs - SystemClock.elapsedRealtime()).coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS))
            }
        }
    }

    /**
     * Lets the audio engine go once whatever it is playing has had time to finish.
     *
     * Releasing a [Signals] stops the tone mid-note, which is how the end-of-program chime
     * used to be cut off a millisecond into its three quarters of a second. The era counter
     * is what makes deferring safe: if a new run starts inside the delay, the pending
     * release belongs to a previous era and does nothing.
     */
    private fun releaseSignalsAfterTheirTail() {
        val era = ++signalEra
        scope.launch {
            delay(Signals.SIGNAL_TAIL_MS)
            if (era == signalEra) signals.release()
        }
    }

    // --- Android plumbing --------------------------------------------------------------

    /**
     * Brings the one foreground service up or takes it down, according to [serviceNeeded].
     *
     * ── [mayStart], and why starting is not symmetrical with stopping ───────────
     * Stopping is always allowed. STARTING one is refused by Android 12 and later when the
     * app is in the background, and two paths here run in exactly that situation: [restore],
     * rebuilding a run inside a process an alarm has just resurrected, and any [FloorController]
     * wake-up that arrives the same way. Those paths pass false — or, in the floors' case,
     * pass true and are simply refused, which the `runCatching` below swallows. Either way the
     * countdown keeps working on the alarm; what is lost is the service, which is the thing
     * that could not have been obtained anyway.
     *
     * ── Started unconditionally rather than on a transition ─────────────────────
     * `startForegroundService` on a service that is already up is one binder call and another
     * `onStartCommand`, which re-posts the same notification. Tracking "is it up" in a field
     * would save that and would go stale the first time the platform stopped the service
     * without telling this class — and a stale "it is up" means a rest counting with no
     * service at all, which is the failure this whole change exists to remove.
     *
     * ── No notification permission means no service, and that is not silent ─────
     * A foreground service must show a notification. Without POST_NOTIFICATIONS (Android 13+,
     * asked for once when the timer is switched on — `rememberTimerEnabler`) there is nothing
     * to show, so none is started. A rest started in that state behaves exactly as rests did
     * before this change: it counts in the process while the process lives, it is woken by the
     * exact alarm when it does not, and it may therefore be late or, past the lateness window,
     * silent. The permission is not asked for again here: the app asks once, at the moment the
     * timer is switched on, and a second dialog fired by logging a set is how a refusal
     * becomes permanent.
     */
    private fun syncService(mayStart: Boolean) {
        if (!serviceNeeded()) {
            runCatching { app.stopService(Intent(app, TimerService::class.java)) }
            return
        }
        if (!mayStart) return
        startService()
    }

    private fun startService() {
        if (!TimerNotifications.canPost(app)) return
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, TimerService::class.java))
        }
    }

    private fun postNotification(snapshot: RunSnapshot) {
        if (!TimerNotifications.canPost(app)) return
        runCatching {
            NotificationManagerCompat.from(app)
                .notify(TimerNotifications.ID_RUNNING, notificationFor(snapshot))
        }
    }

    private fun notificationFor(snapshot: RunSnapshot) = TimerNotifications.running(
        context = app,
        programName = snapshot.programName,
        step = snapshot.steps.getOrNull(snapshot.state.stepIndex) ?: snapshot.steps.first(),
        stepRemainingMs = stepRemainingMs(snapshot.steps, snapshot.state, SystemClock.elapsedRealtime()),
        totalRemainingMs = totalRemainingMs(snapshot.steps, snapshot.state, SystemClock.elapsedRealtime()),
        phase = snapshot.state.phase(),
        singleStep = snapshot.steps.size == 1,
    )

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        app,
        ALARM_REQUEST,
        Intent(app, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Arms the backstop at the end of the current step, on the same monotonic clock the
     * state is expressed in ([AlarmManager.ELAPSED_REALTIME_WAKEUP]) so no conversion can
     * introduce an error.
     *
     * Falls back to the inexact variant when the exact-alarm permission is missing rather
     * than throwing: a late backstop is worth more than a crash, and the wake lock and the
     * service are what carry the normal case anyway.
     */
    private fun scheduleAlarm(snapshot: RunSnapshot) {
        cancelAlarm()
        if (!snapshot.state.running) return
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = snapshot.state.stepEndAtMs
        val intent = alarmIntent()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intent)
            }
        }
    }

    private fun cancelAlarm() {
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.cancel(alarmIntent()) }
    }

    /**
     * Holds the CPU for the rest of the program plus a minute of slack, capped. The
     * timeout is the safety net: if the process is killed while holding the lock the
     * system releases it, and if a bug leaves a run stuck the lock still expires.
     */
    private fun acquireWakeLock(snapshot: RunSnapshot) {
        val remaining = totalRemainingMs(snapshot.steps, snapshot.state, SystemClock.elapsedRealtime())
        val timeout = (remaining + 60_000).coerceAtMost(MAX_WAKE_LOCK_MS)
        val manager = app.getSystemService(PowerManager::class.java) ?: return
        releaseWakeLock()
        runCatching {
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(timeout)
                wakeLock = this
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        /**
         * The conductor's alarm. [cancelAlarm] fires against it on every application of run
         * state, so anything else in this app that arms an alarm must NOT use this code —
         * see FloorController.alarmIntent, which explains what sharing it would cost.
         */
        private const val ALARM_REQUEST = 7001
        private const val WAKE_LOCK_TAG = "gachimuchi:workout-timer"

        /** Never sleep longer than this without re-checking against the clock. */
        private const val MAX_SLEEP_MS = 30_000L

        /**
         * Floor on a sleep, so that a wake moment already in the past cannot turn the loop
         * into a spin. Four milliseconds is under a frame and far under anything audible.
         */
        private const val MIN_SLEEP_MS = 4L

        /** Even a very long program cannot hold the CPU for more than this. */
        private const val MAX_WAKE_LOCK_MS = 3 * 60 * 60 * 1000L

        @Volatile
        private var instance: TimerController? = null

        fun get(context: Context): TimerController = instance ?: synchronized(this) {
            instance ?: TimerController(context).also { instance = it }
        }
    }
}
