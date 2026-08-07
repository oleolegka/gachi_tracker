package xyz.oleolegka.gachimuchi.timer

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The foreground service. Deliberately almost empty.
 *
 * Its entire job is to be a reason for the system not to freeze the process: the run
 * itself lives in [TimerController], which works identically whether this service is up
 * or not. That split is what makes the timer testable — the counting, the transitions and
 * the recovery from process death are plain Kotlin, and this file has no logic to get
 * wrong.
 *
 * ── One service, two claimants ──────────────────────────────────────────────────
 * It is up while EITHER a protocol run or a rest between sets is still counting, which is
 * the single question [TimerController.serviceNeeded] answers. There is deliberately not a
 * second service for the rests: a foreground service owns a notification, so two of them
 * would mean two permanent entries in the shade for one app doing one thing, and they would
 * take turns removing each other's.
 *
 * What that costs is that the two share a notification, and sharing needs an order of
 * precedence. It is: THE RUN WINS. While a run exists the notification is the run's, and the
 * rests do not draw. See TimerNotifications.ID_RUNNING and FloorController.refreshNotification
 * for the whole of it.
 *
 * The lifecycle that follows from having two claimants is the part worth being careful about:
 * the service is NOT taken down when a run ends, only when nothing at all is left. A run
 * ending while rests are still counting used to be a stop followed immediately by a start,
 * and the start would have been refused — Android 12 and later do not allow a foreground
 * service to be started from the background, which is exactly where a program finishing with
 * the phone in a pocket happens to be.
 *
 * ── Why FOREGROUND_SERVICE_SPECIAL_USE ──────────────────────────────────────────
 * From Android 14 every foreground service must declare a type, and the list is a list of
 * things the platform recognises. A workout timer is none of them:
 *
 *  - `shortService` is capped at three minutes and cannot be extended. A rest between
 *    heavy sets is routinely longer than that and a hangboard program is much longer, so
 *    the timer would be killed mid-count. That cap alone rules it out.
 *  - `mediaPlayback`, `health`, `dataSync`, `location` and the rest would be false
 *    declarations made to borrow someone else's exemptions.
 *  - `systemExempted` is for privileged apps; the platform's own Clock uses it and a
 *    sideloaded app cannot.
 *
 * `specialUse` is the category the platform provides for exactly this — a legitimate
 * long-running job that does not fit the other buckets — and it comes with a manifest
 * property stating what the use is. Google Play reviews that string; this app is not
 * distributed through Play, and the string is honest regardless.
 *
 * ── What this service does NOT do ───────────────────────────────────────────────
 * It holds no wake lock, schedules no alarms and counts nothing. All three belong to the
 * controller, because all three must keep working in the window after the process is
 * resurrected by the alarm and before any service exists.
 */
class TimerService : Service() {

    private lateinit var controller: TimerController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onCreate() {
        super.onCreate()
        controller = TimerController.get(this)
        TimerNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /*
         * startForeground MUST be called, even when there is nothing left to show.
         *
         * The run — or the last rest — can end in the gap between startForegroundService and
         * this callback: a rest of a few seconds, or Stop pressed straight away. Bailing out
         * with stopSelf alone leaves a service that was promised to the system and never
         * delivered, and the platform answers that with a crash a few seconds later. So an
         * empty start still goes foreground, with a placeholder, and is then taken down
         * properly.
         */
        val notification = controller.currentNotification()
            ?: TimerNotifications.alert(this, "Timer", singleStep = true)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    TimerNotifications.ID_RUNNING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(TimerNotifications.ID_RUNNING, notification)
            }
        }.onFailure {
            // Android 12+ refuses a foreground start from the background. Degrade instead of
            // crashing: a run keeps counting on its alarm and its wake lock, and a rest keeps
            // counting on its alarm alone — which is what a rest had before it was given a
            // service at all, so what is lost here is the improvement and not the feature.
            stopSelf()
            return START_NOT_STICKY
        }

        if (!controller.serviceNeeded()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        watchForEnd()

        /*
         * NOT sticky. A restarted service would come back with a null intent and no run to
         * show, and the state that matters is already on disk: whatever resurrects the
         * process (the alarm, or the user opening the app) rebuilds the run from the
         * snapshot. A service that restarts itself into an empty state would only produce
         * an empty notification.
         */
        return START_NOT_STICKY
    }

    /**
     * Takes the service down as soon as NEITHER claimant needs it any more.
     *
     * Both flows are watched, and the test is the controller's own, so there is one
     * definition of "needed" rather than a copy here that could disagree with it. A run
     * ending is no longer sufficient on its own — that is the whole change — and neither is
     * the last rest maturing while a protocol is still going.
     *
     * This is a backstop rather than the mechanism: [TimerController.syncService] stops the
     * service on every path that ends something. It stays because the two disagreeing is
     * survivable in one direction only — a service that outlives its reason is a battery cost
     * and a complaint from the platform, and this is the half that cannot be forgotten at a
     * call site.
     */
    private fun watchForEnd() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            combine(controller.run, controller.floors.floors) { _, _ -> controller.serviceNeeded() }
                .collectLatest { needed ->
                    if (!needed) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** Nothing binds to this: the controller is a process-wide object, not a binder API. */
    override fun onBind(intent: Intent?): IBinder? = null
}
