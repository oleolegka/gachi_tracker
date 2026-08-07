package xyz.oleolegka.gachimuchi.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.FloorStore
import xyz.oleolegka.gachimuchi.domain.RestFloor
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.bootReference
import xyz.oleolegka.gachimuchi.domain.floorCue
import xyz.oleolegka.gachimuchi.domain.floorSummaryText
import xyz.oleolegka.gachimuchi.domain.releaseFloors
import xyz.oleolegka.gachimuchi.domain.settleFloors
import xyz.oleolegka.gachimuchi.domain.startFloor
import xyz.oleolegka.gachimuchi.domain.withFloor

/**
 * The rest floors as a running thing: stored, woken up, sounded and handed to the screen.
 *
 * The rules are not here. What may sound, when, and in which order is pure arithmetic in
 * domain/Floors.kt, tested on the JVM; this file is the part that cannot be — a preference
 * file, an alarm, a vibrator and a coroutine — and it is deliberately dumb. Every method
 * below funnels into [advance], which asks the domain what is owed and obeys.
 *
 * ── Who owns whom ───────────────────────────────────────────────────────────────
 * [TimerController] owns an instance of this and drives it. Not the other way round, and
 * not side by side, because of one race that nothing else closes:
 *
 *   A floor asks "is a conductor running?" AT THE INSTANT IT IS ABOUT TO SOUND. It cannot
 *   know that a conductor is about to start a hundred milliseconds later. If the two objects
 *   were peers, the user pressing Start on a protocol while a rest was maturing would get the
 *   floor's beep on top of the conductor's first "get ready" — the exact collision this whole
 *   feature is arranged to avoid.
 *
 * So the conductor's start passes through [conductorStarted] BEFORE it touches its own state,
 * and every method here that can produce a noise is `@Synchronized` on this object. By the
 * time a run exists, the floors have already been muted and their alarm taken down. That is a
 * requirement on the caller, stated here because it is invisible from the call site.
 *
 * ── Three ways this gets woken ──────────────────────────────────────────────────
 * 1. A COROUTINE, while the process is alive: sleeps until the next moment the domain names
 *    and no longer. It is the normal path and the accurate one.
 * 2. An EXACT ALARM, for when the process is not alive: [TimerReceiver] rebuilds the
 *    controller and calls [onAlarm]. See [armAlarm] for how far this can be trusted.
 * 3. [refresh], when a screen comes back. A floor is a "not before", so a signal noticed on
 *    resume is still worth having — up to the lateness window, past which the domain resolves
 *    it in silence.
 *
 * There is NO foreground service and NO wake lock for floors, unlike the conductor. A rest
 * between sets is minutes long and being told late costs nothing, so the machinery that
 * exists to make a ten-second interval land on the beat would be all cost and no benefit
 * here. That is the same asymmetry the whole feature is built on.
 */
class FloorController internal constructor(
    context: Context,
    /**
     * Shared with [TimerController] rather than a second instance, because a `ToneGenerator`
     * is an open audio track on the alarm stream and there is no reason for the app to hold
     * two. Nothing here calls `prime` or `release`: the track's lifetime belongs to the
     * conductor, which is the party that needs its first beep to be instant. A floor's tone
     * may therefore pay for building the generator and arrive a few tens of milliseconds
     * late — which is precisely the kind of lateness a "not before" can afford, and the
     * vibration, which [Signals] fires first, is unaffected either way.
     */
    private val signals: Signals,
    private val settings: StateFlow<TimerSettings>,
    /** How the summary is said out loud, or a no-op. See [conductorStopped]. */
    private val announce: (String) -> Unit,
) {

    private val app = context.applicationContext
    private val store = FloorStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _floors = MutableStateFlow<List<RestFloor>>(emptyList())

    /**
     * Every floor the app is keeping, live and spent alike, for the screen to draw.
     *
     * Spent ones stay: "Bench has been ready for 2:30" is the answer this feature exists to
     * give, and it is only sayable by a floor that was not thrown away the moment it matured
     * (see `FloorProgress.overdueMs`). They are dropped by [prune] once they are old enough
     * to be about a different session.
     *
     * The list does not change as time passes — a floor stores the moment it is ready at, not
     * a countdown — so a screen drawing a progress bar needs its own ticking clock reading
     * (`rememberTickingNow` in ui/components/TimerParts.kt) and this flow only for the facts.
     */
    val floors: StateFlow<List<RestFloor>> = _floors.asStateFlow()

    private val _summary = MutableStateFlow<String?>(null)

    /**
     * The one line about the floors that matured while a conductor had them muted, waiting
     * to be shown. Null when there is nothing to say. See [conductorStopped].
     */
    val summary: StateFlow<String?> = _summary.asStateFlow()

    /**
     * Whether a conductor is running, as this object believes.
     *
     * A believed flag rather than a read of [TimerController.run], and that is the point: the
     * moment that has to be covered is the one BEFORE a run exists, when start has been
     * pressed and the snapshot has not been built yet. Reading the run there would answer
     * "no conductor" and let a floor through.
     *
     * A PAUSED conductor still counts as running, which falls out of this for free — the
     * conductor does not stop when it pauses, so nothing calls [conductorStopped]. That is
     * the intended rule and not an accident: a pause is somebody standing with the phone in
     * their hand in the middle of a set, and a rest beep is no more welcome then than it was
     * a second earlier.
     */
    private var conductorRunning = false

    private var loop: Job? = null

    init {
        // loaded, but NOT acted on: nothing is settled or armed until TimerController has
        // restored its own run and can say whether a conductor is up. See [takeUp].
        _floors.value = store.load()
    }

    // --- what the app calls ---------------------------------------------------------------

    /**
     * Starts a rest for [exerciseId], replacing whatever was running for it.
     *
     * Replacing without asking is right for the same reason it is right in
     * [TimerController.start]: a second set of the same exercise means the previous rest was
     * superseded by an event that has already happened. `withFloor` is what keeps at most one
     * floor per exercise.
     */
    @Synchronized
    fun start(exerciseId: Long, exerciseName: String, orderedMs: Long) {
        val now = SystemClock.elapsedRealtime()
        publish(
            _floors.value.withFloor(
                startFloor(exerciseId, exerciseName, orderedMs, now, System.currentTimeMillis())
            )
        )
        advance()
    }

    /** Takes one floor off the list, by hand. */
    @Synchronized
    fun dismiss(exerciseId: Long) {
        publish(_floors.value.filterNot { it.exerciseId == exerciseId })
        advance()
    }

    /** Takes all of them off — what switching the timer off means for the floors. */
    @Synchronized
    fun clearAll() {
        publish(emptyList())
        _summary.value = null
        advance()
    }

    /** The summary was read. */
    fun clearSummary() {
        _summary.value = null
    }

    /** The backstop alarm fired. */
    @Synchronized
    fun onAlarm() = advance()

    /** A screen came back, or something else wants the clock re-read. */
    @Synchronized
    fun refresh() = advance()

    // --- what TimerController calls ---------------------------------------------------------

    /**
     * Picks the stored floors up, once, when the process is new.
     *
     * ── The order is the whole reason this is a separate entry point ────────────
     * Settle first, ask for a cue second, arm third. Reversed, a floor carried onto this
     * boot's clock would be handed to the alarm at a moment computed against the PREVIOUS
     * boot's clock, which after a restart is a large negative number: the alarm fires
     * immediately, the floor is resolved for being late, and the log shows a rest that
     * "expired" in the same millisecond the phone came up. Nothing crashes and nothing is
     * obviously wrong, which is what makes it worth writing down.
     *
     * ── And settling happens ONCE, here, not on every tick ──────────────────────
     * `settleFloors` compares boot references, and a boot reference is `wall minus
     * monotonic` — so anything that moves the WALL clock without moving the monotonic one
     * looks exactly like a restart. An NTP correction of more than the five seconds
     * `isRunStale` tolerates is such a thing, and it happens mid-workout on a phone that has
     * just found a network. Re-settling on every wake-up would then re-derive every live
     * floor from the wall clock, moving rests that were counting perfectly well; settling
     * only when the process is new confines the wall clock to the one question it is the
     * only witness to. It is also what the domain describes: "floors as a fresh process must
     * take them up".
     *
     * [conductorRunning] comes from the caller because at construction time this object has
     * no way to know: a run may be about to be restored from disk, and a floor that matured
     * while the phone was off must not beep over the run that is being rebuilt around it.
     */
    @Synchronized
    internal fun takeUp(conductorRunning: Boolean) {
        this.conductorRunning = conductorRunning
        _floors.value = settleFloors(
            _floors.value,
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis(),
            currentBootRef(),
        )
        advance()
    }

    /**
     * A conductor is about to take over. Called BEFORE the run exists — see the note on
     * ownership at the top of this file.
     *
     * Idempotent, because the conductor replaces a run with another without stopping in
     * between and this must not be a second event when it does.
     */
    @Synchronized
    fun conductorStarted() {
        if (conductorRunning) return
        conductorRunning = true
        // takes the floor alarm and the floor loop down: while a conductor runs the domain
        // reports no wake moment at all, and the conductor holds a wake-up of its own
        advance()
    }

    /**
     * The conductor has stopped. Every floor already ready is resolved AT ONCE, in silence,
     * and comes back as one line instead of as the burst of beeps that were held back.
     *
     * ── Re-arming here is mandatory, not tidiness ──────────────────────────────
     * While a conductor runs, `nextFloorSignalMs` returns null by design — the floors have NO
     * alarm during that time. If this method returned without the [advance] at the end, the
     * floors that are still counting would have nothing left to wake them: the coroutine dies
     * with the process, and there would be no alarm behind it. They would simply go quiet for
     * good, and only on sessions that happened to include a protocol run. That is the single
     * easiest thing to get wrong in this file.
     *
     * ── The summary is spoken AFTER the conductor's own announcement ────────────
     * The caller fires the finish announcement before it gets here (see `apply` in
     * TimerController), and this asks to be appended rather than to flush — `Speaker.speak`
     * explains why the order has to be stated rather than left to whichever thread wins.
     */
    @Synchronized
    fun conductorStopped() {
        if (!conductorRunning) return
        conductorRunning = false

        val now = SystemClock.elapsedRealtime()
        val release = releaseFloors(_floors.value, now)
        // the RETURNED list, always: it carries the marks that stop these floors sounding
        // later, and dropping it would resurrect them on the next call and after a restart
        publish(release.floors)

        floorSummaryText(release.ready)?.let { text ->
            _summary.value = text
            announce(text)
        }

        advance()
    }

    // --- the machinery ----------------------------------------------------------------------

    /**
     * The single write path: ask the domain what is owed, sound at most one thing, store,
     * re-arm, re-sleep. Everything above funnels through here.
     *
     * Reads the MONOTONIC clock only. The wall clock is touched in exactly two places in this
     * file — stamping a new floor, and [takeUp] — for the reason the domain gives: it is the
     * one clock a user or an NTP sync can move.
     *
     * The signal goes before the store for the reason spelled out in `TimerController.apply`:
     * [publish] is a synchronous SharedPreferences `commit()`, a disk write, and queueing a
     * beep behind it on the same thread is how a signal ends up arriving after the moment it
     * announces. For a floor that would be survivable; there is still no reason to do it.
     */
    private fun advance() {
        val now = SystemClock.elapsedRealtime()

        // 1. ask: at most one floor may sound, and this says which and when to look again
        val cue = floorCue(prune(_floors.value, now), conductorRunning, now)

        if (cue.signal != null) signals.floor(settings.value)

        // 2. store what the cue resolved. NOT optional: `floorCue` hands back a NEW list with
        // the floor it just spent marked, and a caller that keeps the old one gets a floor
        // that signals on every single call, for ever, and comes back from the dead after a
        // process restart having already been heard.
        publish(cue.floors)

        // 3. and only now the alarm, at a moment the cue computed from that same list
        armAlarm(cue.wakeAtMs)
        restartLoop(cue.wakeAtMs)
    }

    private fun publish(floors: List<RestFloor>) {
        _floors.value = floors
        runCatching { store.save(floors) }
    }

    /**
     * Drops floors that are too old to be about this session.
     *
     * Without it the list only ever grows: a floor is kept after it has sounded so the screen
     * can say how long the bench has been free, and nothing else ever removes one except a
     * new rest for the same exercise. Six hours later "ready for 5:47:12" is not an answer to
     * anything, and the row is in the way of the workout happening now.
     *
     * Only RESOLVED floors are dropped, and only by their own moment of readiness, so this
     * cannot silence a rest that is still counting — including the pathological one a wall
     * clock moved forward would produce, which the domain resolves rather than sounds.
     */
    private fun prune(floors: List<RestFloor>, now: Long): List<RestFloor> =
        floors.filterNot { it.signalled && now - it.readyAtMs > FLOOR_MAX_AGE_MS }

    private fun currentBootRef(): Long =
        bootReference(System.currentTimeMillis(), SystemClock.elapsedRealtime())

    /**
     * Sleeps until the next moment the domain named, then asks again.
     *
     * Nothing is decided here: the loop obeys `wakeAtMs`, exactly as the conductor's loop
     * obeys `timerCue`. The cap on a single sleep is what makes an over-long wait
     * self-correcting — a device that was suspended for two hours wakes, finds the moment
     * long past, and the lateness rule in the domain settles it in silence.
     */
    private fun restartLoop(wakeAtMs: Long?) {
        loop?.cancel()
        loop = null
        if (wakeAtMs == null) return
        loop = scope.launch {
            delay((wakeAtMs - SystemClock.elapsedRealtime()).coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS))
            advance()
        }
    }

    /**
     * The floors' own [PendingIntent] — a DIFFERENT request code and a different action from
     * the conductor's.
     *
     * ── This is not belt and braces, it is the bug ──────────────────────────────
     * `TimerController.cancelAlarm` calls `AlarmManager.cancel` with a PendingIntent built
     * from request code 7001 and `ACTION_ALARM`, and it does so on EVERY application of run
     * state — which is every step boundary, every pause, every nudge, several times a minute
     * during a 7:3 protocol. `AlarmManager.cancel` matches on the request code plus
     * `Intent.filterEquals` (action, data, type, component, categories), and nothing else.
     * A floor alarm built the same way would therefore be cancelled by the conductor over and
     * over, and the failure would be invisible in every test that does not involve both at
     * once: the floors would simply never fire on any session that contained a protocol run.
     *
     * Both halves are made distinct because either one alone would be enough and neither is
     * self-documenting. The test that holds this down is in FloorControllerTest — a floor
     * alarm armed, `TimerController.stop()` called, and the alarm still standing.
     */
    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        app,
        FLOOR_ALARM_REQUEST,
        Intent(app, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_FLOOR_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Arms the backstop at [triggerAtMs], on the same monotonic clock the floors are
     * expressed in, or takes it down when there is nothing left to wake for.
     *
     * ── The Doze quota is now shared, and it is worth saying what that costs ────
     * `setExactAndAllowWhileIdle` is throttled in deep Doze to roughly ONE firing per APP per
     * nine minutes. Per app, not per alarm — so this is not a second budget, it is a second
     * claimant on the one the conductor already spends. What that means in practice:
     *
     *  - The conductor is the one that cannot tolerate lateness (its whole job is landing on
     *    the beat) and it already holds a wake lock and a foreground service while it runs, so
     *    in the case where the two compete the conductor is not relying on the alarm anyway.
     *    Floors, meanwhile, hold neither, and a floor arriving nine minutes late is a floor
     *    that the lateness rule resolves in silence — the rest is simply reported as overdue
     *    on the screen instead of being announced. Degraded, not broken.
     *  - Several floors do NOT mean several alarms. The domain hands back ONE moment for the
     *    whole set of them, staggering the rest, so a superset with four rests running costs
     *    exactly one alarm at a time.
     *  - The device is only in deep Doze when it has been still and unplugged for a long
     *    while, which is not what a phone in a gym bag between sets is doing.
     *
     * As with the conductor, this is honestly a SECOND LINE. The first line is the coroutine
     * above, and it works whenever the process is alive. Falls back to the inexact variant
     * when the exact-alarm permission is missing rather than throwing.
     */
    private fun armAlarm(triggerAtMs: Long?) {
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = alarmIntent()
        runCatching { manager.cancel(intent) }
        if (triggerAtMs == null) return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, intent)
            }
        }
    }

    companion object {
        /**
         * The floors' alarm request code. NOT `TimerController.ALARM_REQUEST` (7001) — see
         * [alarmIntent] for what sharing it would do.
         */
        private const val FLOOR_ALARM_REQUEST = 7002

        /** Never sleep longer than this without re-checking against the clock. */
        private const val MAX_SLEEP_MS = 60_000L

        /** Floor on a sleep, so a wake moment already in the past cannot become a spin. */
        private const val MIN_SLEEP_MS = 4L

        /**
         * How long a resolved floor is kept for its "ready for ..." line before [prune] drops
         * it. Long enough to cover the whole of any session, short enough that yesterday's
         * bench is not on the screen this morning.
         */
        private const val FLOOR_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
