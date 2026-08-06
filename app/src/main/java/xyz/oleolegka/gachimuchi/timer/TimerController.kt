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
import xyz.oleolegka.gachimuchi.domain.settleRun
import xyz.oleolegka.gachimuchi.domain.skipStep
import xyz.oleolegka.gachimuchi.domain.startRun
import xyz.oleolegka.gachimuchi.domain.stepRemainingMs
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
 *    on, which has no service and counts callbacks instead of time.
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
    val settings get() = store.settings
    val enabled get() = store.enabled

    private val _run = MutableStateFlow<RunSnapshot?>(null)

    /** The live run, or null when nothing is counting. */
    val run: StateFlow<RunSnapshot?> = _run.asStateFlow()

    private val _outcome = MutableStateFlow<RunOutcome?>(null)

    /**
     * The last run that ended with something worth writing into the journal, waiting for the
     * screen to offer it (domain/RunLog.kt). Only runs generated from a catalog exercise
     * ever land here; a rest and a plain program leave it null.
     *
     * Deliberately NOT persisted. An offer is a conversation about what just happened, and
     * one that survives the process being killed would surface hours later, out of context,
     * proposing sets the user has long since forgotten doing. Losing it with the process is
     * the right failure — the journal is still exactly what was confirmed.
     */
    val outcome: StateFlow<RunOutcome?> = _outcome.asStateFlow()

    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The step the last signal was fired for, so a redraw cannot double-signal. */
    private var signalledStep: Int = -1

    /** Whole seconds remaining at the last countdown tick, so each second ticks once. */
    private var lastTickSecond: Int = -1

    init {
        restore()
    }

    // --- what the screens call ---------------------------------------------------------

    fun setEnabled(value: Boolean) {
        store.setEnabled(value)
        if (value) prepareSpeech() else stop()
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
        val now = SystemClock.elapsedRealtime()
        signalledStep = -1
        lastTickSecond = -1
        // an offer from the previous run is stale the moment a new one starts
        _outcome.value = null
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
        signals.release()
        TimerNotifications.cancelAll(app)
        app.stopService(Intent(app, TimerService::class.java))
    }

    /** Re-reads the clock and redraws, without changing anything. */
    fun refresh() = mutate { snapshot, _ -> snapshot }

    /** The offer was answered (either way). Nothing is written here. */
    fun clearOutcome() {
        _outcome.value = null
    }

    // --- what the service and the receiver call ----------------------------------------

    /**
     * Handles the backstop alarm: settles the state, which is what turns "the alarm went
     * off" into "the step, or the whole program, is over", and signals accordingly.
     */
    fun onAlarm() = mutate { snapshot, _ -> snapshot }

    /** The notification the service must show right now, or null when there is no run. */
    fun currentNotification() = _run.value?.let { notificationFor(it) }

    // --- the machinery -----------------------------------------------------------------

    private fun currentBootRef(): Long =
        bootReference(System.currentTimeMillis(), SystemClock.elapsedRealtime())

    /**
     * Rebuilds a run left behind by a killed process.
     *
     * A snapshot from a previous boot is discarded rather than resumed: its end moments
     * are readings of a clock that no longer exists, so resuming would count down from an
     * arbitrary number. A rest between sets does not outlive a reboot in any case.
     */
    private fun restore() {
        val saved = store.loadRun() ?: return
        if (isRunStale(saved.bootRef, currentBootRef())) {
            store.clearRun()
            return
        }
        signalledStep = saved.state.stepIndex
        val now = SystemClock.elapsedRealtime()
        apply(saved.copy(state = settleRun(saved.steps, saved.state, now)), startService = false)
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
     * The single write path: store the state, fire whatever signal the change implies,
     * re-arm the alarm and the wake lock, redraw the notification and restart the ticking
     * loop. Every command above funnels through here.
     */
    private fun apply(snapshot: RunSnapshot, startService: Boolean) {
        val stamped = snapshot.copy(bootRef = currentBootRef())
        val phase = stamped.state.phase()

        if (phase == RunPhase.FINISHED) {
            keepOutcome(stamped)
            _run.value = stamped
            store.clearRun()
            cancelAlarm()
            releaseWakeLock()
            loop?.cancel()
            loop = null
            fireFinish(stamped)
            _run.value = null
            app.stopService(Intent(app, TimerService::class.java))
            return
        }

        _run.value = stamped
        store.saveRun(stamped)
        maybeSignalBoundary(stamped)
        scheduleAlarm(stamped)
        if (phase == RunPhase.RUNNING) acquireWakeLock(stamped) else releaseWakeLock()
        postNotification(stamped)
        if (startService) startService()
        restartLoop()
    }

    /**
     * Remembers what a run got through, but only when it is worth offering: a rest, a
     * program belonging to no exercise, and a run that completed no effort all leave the
     * offer untouched rather than raising a dialog with nothing in it.
     */
    private fun keepOutcome(snapshot: RunSnapshot) {
        val outcome = runOutcome(snapshot, SystemClock.elapsedRealtime())
        if (outcome.offersLogging) _outcome.value = outcome
    }

    /** Fires the boundary signal once per step, whoever caused the step to change. */
    private fun maybeSignalBoundary(snapshot: RunSnapshot) {
        val index = snapshot.state.stepIndex
        if (index == signalledStep) return
        signalledStep = index
        lastTickSecond = -1
        val step = snapshot.steps.getOrNull(index) ?: return
        signals.boundary(store.settings.value, step.kind)
    }

    private fun fireFinish(snapshot: RunSnapshot) {
        val settings = store.settings.value
        signals.finish(settings)
        if (settings.speak) speaker.speak("Done")
        signals.release()
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
     * Wakes at the next thing that matters — the end of the step, or the start of the
     * final few seconds where the countdown ticks — rather than once a second for the
     * whole workout. Between boundaries the coroutine is simply not scheduled.
     */
    private fun restartLoop() {
        loop?.cancel()
        val snapshot = _run.value ?: return
        if (!snapshot.state.running) return
        loop = scope.launch {
            while (isActive) {
                val current = _run.value ?: return@launch
                if (!current.state.running) return@launch
                val now = SystemClock.elapsedRealtime()
                val remaining = stepRemainingMs(current.steps, current.state, now)

                if (remaining <= 0) {
                    mutate { snap, _ -> snap }
                    announceStep(_run.value ?: return@launch)
                    return@launch
                }

                val settings = store.settings.value
                if (settings.countdownTicks && remaining <= TICK_WINDOW_MS) {
                    val second = ((remaining + 999) / 1000).toInt()
                    if (second != lastTickSecond && second in 1..TICK_SECONDS) {
                        lastTickSecond = second
                        signals.tick(settings)
                    }
                    delay(((remaining - 1) % 1000 + 1).coerceAtLeast(20))
                } else {
                    val until = if (settings.countdownTicks) remaining - TICK_WINDOW_MS else remaining
                    delay(until.coerceIn(20, MAX_SLEEP_MS))
                }
            }
        }
    }

    // --- Android plumbing --------------------------------------------------------------

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
        private const val ALARM_REQUEST = 7001
        private const val WAKE_LOCK_TAG = "gachimuchi:workout-timer"

        /** The countdown ticks over the last three seconds of a step. */
        private const val TICK_SECONDS = 3
        private const val TICK_WINDOW_MS = TICK_SECONDS * 1000L

        /** Never sleep longer than this without re-checking against the clock. */
        private const val MAX_SLEEP_MS = 30_000L

        /** Even a very long program cannot hold the CPU for more than this. */
        private const val MAX_WAKE_LOCK_MS = 3 * 60 * 60 * 1000L

        @Volatile
        private var instance: TimerController? = null

        fun get(context: Context): TimerController = instance ?: synchronized(this) {
            instance ?: TimerController(context).also { instance = it }
        }
    }
}
