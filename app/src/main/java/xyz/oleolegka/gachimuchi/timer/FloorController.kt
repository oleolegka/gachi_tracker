package xyz.oleolegka.gachimuchi.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
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
import xyz.oleolegka.gachimuchi.data.FloorStore
import xyz.oleolegka.gachimuchi.domain.RestFloor
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.bootReference
import xyz.oleolegka.gachimuchi.domain.dismissLabel
import xyz.oleolegka.gachimuchi.domain.floorCue
import xyz.oleolegka.gachimuchi.domain.floorNotificationLine
import xyz.oleolegka.gachimuchi.domain.floorSummaryText
import xyz.oleolegka.gachimuchi.domain.releaseFloors
import xyz.oleolegka.gachimuchi.domain.restsOver
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
 * ── The foreground service: shared with the conductor, and why floors get one ───
 * This used to say that floors have no service, on the grounds that a rest is a "not before"
 * and can afford to be late. That was true about the SIGNAL and wrong about the MECHANISM.
 * Without a service, Android freezes the process within about twenty seconds of the screen
 * going off, which takes wake-up 1 away entirely and leaves the whole feature standing on
 * wake-up 2 — an exact alarm whose deep-Doze quota is roughly one firing per APP per nine
 * minutes and is already spoken for by the conductor. A three-minute rest then depends on
 * winning that lottery. With a service the process stays alive, the coroutine is the first
 * line again, and the alarm goes back to being the backstop it was described as.
 *
 * It is the SAME service the conductor uses ([TimerService]), started and stopped by
 * [TimerController] and never by this class directly, because a foreground service owns a
 * notification and two services would fight over one:
 *
 *  - Either one alive is enough to keep it up. [needsService] is this half of the answer.
 *  - When a conductor starts while rests are counting, nothing about the service changes —
 *    it is already up. The NOTIFICATION changes hands: the conductor's run takes
 *    `ID_RUNNING` and the floors stop drawing (see [refreshNotification]).
 *  - When the conductor stops with rests still counting, the service stays up and the floors
 *    take the notification back in [conductorStopped]. When the LAST rest matures with a run
 *    still going, nothing happens at all: the service belongs to the run now.
 *  - When the last of both goes, [TimerController] takes the service down.
 *
 * ── No wake lock, and that is a decision rather than an omission ────────────────
 * The conductor holds a partial wake lock because a seven-second hang is ruined by a CPU
 * that suspends through it, and because the alarm quota cannot cover intervals that short.
 * Neither applies here, and the price is not the same either:
 *
 *  - A rest is minutes long, and ONE exact `ELAPSED_REALTIME_WAKEUP` alarm covers all the
 *    floors at once (the domain staggers them behind a single wake moment). That alarm wakes
 *    the device by itself — that is what the WAKEUP in its name is — so the CPU does not have
 *    to be held awake for the signal to arrive.
 *  - The cost is not three minutes of held CPU, it is the WHOLE SESSION: rests run
 *    back-to-back for an hour and a half, so a lock taken per rest is a lock held nearly
 *    continuously, with the screen off, for the entire workout. That is a permanent battery
 *    cost paid for a signal that by definition tolerates being late.
 *  - What is actually lost without it is precision: a signal may land seconds late when the
 *    device was suspended at the moment. A "not before" can afford exactly that, and the
 *    lateness window in domain/Floors.kt is what keeps a very late one from gonging at all.
 *
 * So: service yes, wake lock no. If a rest ever needs to land on the beat, it has stopped
 * being a floor and belongs to the conductor.
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
    /**
     * Asks [TimerController] to re-decide whether the one foreground service should be up.
     *
     * A callback rather than this class starting the service itself, because the answer is
     * "a run OR a floor needs it" and only the owner can see both halves. Called at the end
     * of [advance], so every path that can change [needsService] goes past it.
     */
    private val syncService: () -> Unit,
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

    /** Redraws the shade while a rest is counting. See [restartNotifyLoop]. */
    private var notifyLoop: Job? = null

    /**
     * When the shade was last actually written to, and what was written.
     *
     * The pair is the whole rate limit: nothing is posted twice within [NOTIFY_INTERVAL_MS]
     * and nothing is posted that says what is already there. Started far enough in the past
     * that the first post is never throttled.
     */
    private var lastNotifyAtMs = -NOTIFY_INTERVAL_MS
    private var lastNotifyLine: String? = null

    init {
        // loaded, but NOT acted on: nothing is settled or armed until TimerController has
        // restored its own run and can say whether a conductor is up. See [takeUp].
        _floors.value = store.load()
    }

    // --- what the app calls ---------------------------------------------------------------

    /**
     * Starts a rest for [exerciseId] (and, for a one-sided exercise's card, [side]), replacing
     * whatever was running for that same card.
     *
     * Replacing without asking is right for the same reason it is right in
     * [TimerController.start]: a second set of the same card means the previous rest was
     * superseded by an event that has already happened. `withFloor` is what keeps at most one
     * floor per card — see [RestFloor.side] for why a card is (exerciseId, side) and not
     * exerciseId alone: without it, marking the right hand's set would stop the left hand's
     * still-counting rest, because both would be "the" floor of the same exerciseId.
     */
    @Synchronized
    fun start(exerciseId: Long, exerciseName: String, orderedMs: Long, side: String? = null) {
        val now = SystemClock.elapsedRealtime()
        publish(
            _floors.value.withFloor(
                startFloor(exerciseId, exerciseName, orderedMs, now, System.currentTimeMillis(), side)
            )
        )
        advance()
    }

    /** Takes one floor off the list, by hand — the same (exerciseId, side) card [start] took. */
    @Synchronized
    fun dismiss(exerciseId: Long, side: String? = null) {
        publish(_floors.value.filterNot { it.exerciseId == exerciseId && it.side == side })
        advance()
    }

    /**
     * Takes off every rest that is already over, leaving the ones still counting.
     *
     * What the button on the notification does, and the only bulk operation offered there:
     * from a lock screen the useful question is "clear the ones I have dealt with", and
     * picking one of several by name needs the list, which is in the app.
     *
     * A floor that is over is removed rather than marked, so the sentence it could still say
     * ("has been ready for 2:30") goes with it. That is what dismissing means; the ones still
     * counting are untouched and keep their alarm.
     */
    @Synchronized
    fun dismissReady() {
        val now = SystemClock.elapsedRealtime()
        publish(_floors.value.filter { it.readyAtMs > now })
        advance()
    }

    /** Takes all of them off — what switching the timer off means for the floors. */
    @Synchronized
    fun clearAll() {
        publish(emptyList())
        _summary.value = null
        cancelSummaryNotification()
        advance()
    }

    /** The summary was read — on the screen or in the shade, and it goes from both. */
    @Synchronized
    fun clearSummary() {
        _summary.value = null
        cancelSummaryNotification()
    }

    /**
     * Whether the foreground service has to stay up for the rests.
     *
     * "At least one rest that has not yet had its moment" — which is exactly the set of
     * floors that still need a process to be alive at some point in the future. A floor that
     * has already been dealt with needs nothing: it is a line on a screen, read off disk by
     * whichever process happens to exist next.
     *
     * Read by [TimerController.serviceNeeded], which ORs it with "a run exists".
     */
    @Synchronized
    fun needsService(): Boolean = _floors.value.any { !it.signalled }

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
        /*
         * [lastNotifyLine] means "what we believe is in the shade", and from this moment that
         * belief is false: the conductor takes ID_RUNNING, and takes it down again when it
         * stops (TimerNotifications.cancelAll). Left standing, it would let a floor compare its
         * line after the run against what it posted before, find them equal, and decide there
         * was nothing to post into a shade that is by then empty.
         *
         * Not a complete answer on its own — the rate limit below can still hold the re-post
         * back for up to a second after a hand-off, and the tick loop is what delivers it. It
         * is here because a field that means one thing should not quietly start meaning
         * another.
         */
        lastNotifyLine = null
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
            // and into the shade, because the screen may not be looked at for another set:
            // this is the one thing about the floors that is news rather than a countdown
            postSummaryNotification(text)
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

        // 4. the shade, then the service. In that order so that a service coming up finds a
        // notification already drawn, and a service going down takes an emptied one with it.
        refreshNotification(now)
        restartNotifyLoop()
        syncService()
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

    // --- the shade ----------------------------------------------------------------------

    /**
     * The notification the rests would show right now, or null when they have nothing to
     * show or no right to show it.
     *
     * Public because [TimerService] asks for it: the service must hand `startForeground` a
     * notification, and when it is up for the rests rather than for a run this is the one.
     * Built fresh from the clock every time rather than cached — a cached countdown is a
     * countdown that is wrong by however long it sat in the cache.
     */
    @Synchronized
    fun currentNotification() = notificationFor(SystemClock.elapsedRealtime())

    private fun notificationFor(now: Long) = when {
        conductorRunning -> null
        !needsService() -> null
        else -> floorNotificationLine(_floors.value, now)?.let { line ->
            TimerNotifications.floors(
                context = app,
                line = line,
                dismissLabel = dismissLabel(restsOver(_floors.value, now)),
            )
        }
    }

    /**
     * Draws the rests into the shade, or takes them out of it.
     *
     * ── The three rules, in the order they are applied ──────────────────────────
     * 1. A RUNNING CONDUCTOR OWNS `ID_RUNNING`, so this neither posts nor cancels while one
     *    is up. It does not merely defer to the run — it does not draw the rests at all, and
     *    that is a decision worth stating. A second countdown on that notification could only
     *    be refreshed when the conductor redraws, which is once per STEP, so it would be
     *    stale by up to the length of a step; and refreshing it oftener would put a
     *    once-a-second wakeup on the notification the conductor deliberately draws with the
     *    platform's own chronometer to avoid exactly that. Nothing is lost by waiting: the
     *    floors are MUTED while a conductor runs, so nothing can happen to them that could be
     *    acted on, and the moment the run ends [conductorStopped] both redraws this and posts
     *    the summary of whatever matured in the meantime.
     * 2. NOTHING LEFT TO COUNT means the notification goes. It is the foreground service's
     *    notification and the service is coming down; a shade entry outliving the service it
     *    belongs to is the kind of thing the platform complains about. The honest cost: the
     *    last rest to mature buzzes and its line disappears in the same second, so "Bench has
     *    been ready for 2:30" is answered on the screen and not in the shade.
     * 3. AT MOST ONCE A SECOND, and never to say what is already there. The unchanged-text
     *    test is the cheaper of the two and comes first; a change that arrives too soon after
     *    the last one is simply skipped, because [restartNotifyLoop] is coming round again
     *    within the second anyway.
     */
    private fun refreshNotification(now: Long) {
        if (conductorRunning) return
        val line = if (needsService()) floorNotificationLine(_floors.value, now) else null
        if (line == null) {
            cancelOngoingNotification()
            return
        }
        if (line == lastNotifyLine) return
        if (now - lastNotifyAtMs < NOTIFY_INTERVAL_MS) return
        if (!TimerNotifications.canPost(app)) return
        // the channels are normally created by the service, and the service is normally
        // started by this same pass — a hair later than this. On a phone where the timer has
        // never run, the first post would land on a channel that does not exist yet and be
        // dropped, which self-heals a second later and looks like nothing at all.
        if (lastNotifyLine == null) TimerNotifications.ensureChannels(app)
        val posted = runCatching {
            NotificationManagerCompat.from(app).notify(
                TimerNotifications.ID_RUNNING,
                TimerNotifications.floors(
                    context = app,
                    line = line,
                    dismissLabel = dismissLabel(restsOver(_floors.value, now)),
                ),
            )
        }.isSuccess
        if (!posted) return
        lastNotifyLine = line
        lastNotifyAtMs = now
    }

    private fun cancelOngoingNotification() {
        if (lastNotifyLine == null) return
        lastNotifyLine = null
        runCatching { NotificationManagerCompat.from(app).cancel(TimerNotifications.ID_RUNNING) }
    }

    private fun postSummaryNotification(text: String) {
        if (!TimerNotifications.canPost(app)) return
        // as in [refreshNotification]: the channels belong to the service, and this can be
        // reached without one having started — a conductor whose foreground start was refused
        // still ends, and still owes this line
        TimerNotifications.ensureChannels(app)
        runCatching {
            NotificationManagerCompat.from(app)
                .notify(TimerNotifications.ID_FLOOR_SUMMARY, TimerNotifications.floorSummary(app, text))
        }
    }

    private fun cancelSummaryNotification() {
        runCatching { NotificationManagerCompat.from(app).cancel(TimerNotifications.ID_FLOOR_SUMMARY) }
    }

    /**
     * Keeps the shade in step with the clock while a rest is counting.
     *
     * This is the ONE thing in the app that redraws on a fixed cadence, and it is here
     * because it has to be: the line shows several countdowns at once and the platform's
     * chronometer can only draw one time, so the app writes the digits and must therefore
     * rewrite them. [NOTIFY_INTERVAL_MS] is the floor on how often, and [refreshNotification]
     * enforces it independently, so restarting this loop on every state change cannot make
     * the shade be written to faster.
     *
     * It stops itself the moment there is nothing counting or a conductor takes over, so its
     * lifetime is a rest and not a session. And it holds nothing awake: with no wake lock a
     * suspended device simply does not run it, which is the correct behaviour rather than a
     * gap — a notification nobody is looking at does not need to be right to the second, and
     * the first tick after the screen comes on reads the clock afresh.
     */
    private fun restartNotifyLoop() {
        notifyLoop?.cancel()
        notifyLoop = null
        if (conductorRunning || !needsService()) return
        notifyLoop = scope.launch {
            while (isActive) {
                delay(NOTIFY_INTERVAL_MS)
                if (!notifyTick()) return@launch
            }
        }
    }

    /** One redraw. False when there is no longer any reason to keep redrawing. */
    @Synchronized
    private fun notifyTick(): Boolean {
        if (conductorRunning || !needsService()) return false
        refreshNotification(SystemClock.elapsedRealtime())
        return true
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
     *    the beat) and it holds a wake lock and a foreground service while it runs, so in the
     *    case where the two compete the conductor is not relying on the alarm anyway.
     *  - Floors now hold the same foreground service (see the top of this file) and no wake
     *    lock. That is what demotes this alarm to a genuine second line: with the process
     *    kept alive, the first line is the coroutine above, which is exact and costs nothing.
     *    It used to be the other way round — no service meant a frozen process, which meant
     *    this quota-shared alarm was the ONLY line, and a comment here once said that was
     *    acceptable because a floor tolerates lateness. It tolerates lateness; it does not
     *    tolerate never arriving, which is what one firing per nine minutes shared with a
     *    running protocol amounts to.
     *  - A floor arriving nine minutes late is still resolved in silence by the lateness
     *    rule — the rest is reported as overdue on the screen instead of announced. Degraded,
     *    not broken, and now only in the case where the service could not be started at all.
     *  - Several floors do NOT mean several alarms. The domain hands back ONE moment for the
     *    whole set of them, staggering the rest, so a superset with four rests running costs
     *    exactly one alarm at a time.
     *  - The device is only in deep Doze when it has been still and unplugged for a long
     *    while, which is not what a phone in a gym bag between sets is doing.
     *
     * Falls back to the inexact variant when the exact-alarm permission is missing rather
     * than throwing.
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
         * The shortest gap between two writes to the notification shade.
         *
         * A second, because the line is a countdown in whole seconds and anything finer would
         * be the same text posted twice. Every post goes through this, including the ones a
         * state change asks for, so a burst — three rests started in the same breath — is one
         * write and not three.
         */
        internal const val NOTIFY_INTERVAL_MS = 1_000L

        /**
         * How long a resolved floor is kept for its "ready for ..." line before [prune] drops
         * it. Long enough to cover the whole of any session, short enough that yesterday's
         * bench is not on the screen this morning.
         */
        private const val FLOOR_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
