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
         * The run can end in the gap between startForegroundService and this callback — a
         * rest of a few seconds, or Stop pressed straight away. Bailing out with stopSelf
         * alone leaves a service that was promised to the system and never delivered, and
         * the platform answers that with a crash a few seconds later. So an empty run
         * still goes foreground, with a placeholder, and is then taken down properly.
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
            // Android 12+ refuses a foreground start from the background; the controller
            // keeps counting on its alarm and wake lock, so degrade instead of crashing
            stopSelf()
            return START_NOT_STICKY
        }

        if (controller.run.value == null) {
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

    /** Takes the service down as soon as the controller reports the run is over. */
    private fun watchForEnd() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            controller.run.collectLatest { snapshot ->
                if (snapshot == null) {
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
