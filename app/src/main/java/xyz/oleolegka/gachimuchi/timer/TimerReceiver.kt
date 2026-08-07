package xyz.oleolegka.gachimuchi.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.oleolegka.gachimuchi.domain.NUDGE_SEC

/**
 * The way back into a process that may not exist.
 *
 * Both the backstop alarm and the notification buttons are delivered here rather than to
 * the service, and that is the point: a broadcast starts the process if it is gone,
 * whereas an intent aimed at a dead service would have to start a foreground service from
 * the background, which Android 12 and later refuse. Constructing [TimerController] in
 * [onReceive] rebuilds the run from the persisted snapshot, so a button pressed on a
 * notification left over from a killed process still does what it says.
 *
 * ── No wake lock is taken here ──────────────────────────────────────────────────
 * The system holds one for the duration of [onReceive], and everything done here is
 * synchronous: settle the state, fire the signal, redraw. The controller takes its own
 * lock for the stretch of time between boundaries, which is the part [onReceive] cannot
 * cover.
 *
 * Not exported, and every intent is addressed to this class explicitly, so nothing
 * outside the app can drive the timer.
 */
class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = TimerController.get(context)
        when (intent.action) {
            ACTION_ALARM -> controller.onAlarm()
            ACTION_FLOOR_ALARM -> controller.floors.onAlarm()
            ACTION_FLOOR_DISMISS -> controller.floors.dismissReady()
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_SKIP -> controller.skip()
            ACTION_ADD -> controller.nudge(NUDGE_SEC)
            ACTION_STOP -> controller.stop()
        }
    }

    companion object {
        private const val PREFIX = "xyz.oleolegka.gachimuchi.timer."

        /** The step boundary the exact alarm was armed for. */
        const val ACTION_ALARM = PREFIX + "ALARM"

        /**
         * A rest floor coming due. A separate action, and a separate request code on the far
         * side, because the conductor cancels its own alarm several times a minute and would
         * otherwise take the floors' with it — see FloorController.alarmIntent.
         */
        const val ACTION_FLOOR_ALARM = PREFIX + "FLOOR_ALARM"

        /**
         * The button on the rests' notification: clear every rest that is already over,
         * without unlocking the phone. A broadcast rather than a service intent for the same
         * reason as everything else here — the process may not exist by the time it is
         * pressed, and rebuilding the controller reads the floors back off disk.
         */
        const val ACTION_FLOOR_DISMISS = PREFIX + "FLOOR_DISMISS"
        const val ACTION_PAUSE = PREFIX + "PAUSE"
        const val ACTION_RESUME = PREFIX + "RESUME"
        const val ACTION_SKIP = PREFIX + "SKIP"
        const val ACTION_ADD = PREFIX + "ADD"
        const val ACTION_STOP = PREFIX + "STOP"
    }
}
