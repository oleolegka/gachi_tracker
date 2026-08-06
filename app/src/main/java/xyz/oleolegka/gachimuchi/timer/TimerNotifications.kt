package xyz.oleolegka.gachimuchi.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import xyz.oleolegka.gachimuchi.MainActivity
import xyz.oleolegka.gachimuchi.R
import xyz.oleolegka.gachimuchi.domain.NUDGE_SEC
import xyz.oleolegka.gachimuchi.domain.RunPhase
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.WorkoutStep
import xyz.oleolegka.gachimuchi.domain.ceilSeconds
import xyz.oleolegka.gachimuchi.domain.formatClock

/**
 * The notification the timer lives in while the phone is in a pocket.
 *
 * ── Two channels, and why they are the importance they are ─────────────────────
 * [CHANNEL_RUNNING] is IMPORTANCE_LOW: it carries a countdown that changes constantly and
 * must never make a sound or push a heads-up banner in front of anything. [CHANNEL_ALERT]
 * is IMPORTANCE_HIGH so that the end of the workout can break through, but it is created
 * SILENT — no channel sound, no channel vibration.
 *
 * That last part looks backwards and is deliberate. A channel's sound and vibration
 * belong to the user and cannot be changed by the app after the channel exists, which
 * would make the in-app "sound" and "vibration" switches decorative. So the channel stays
 * quiet and the app produces the signal itself (timer/Signals.kt) on the alarm stream,
 * where it is audible with the ringer on silent — something a notification channel could
 * not achieve either.
 *
 * ── The countdown ticks without the app running ─────────────────────────────────
 * The ongoing notification uses the platform's own countdown chronometer
 * ([NotificationCompat.Builder.setUsesChronometer] with [setChronometerCountDown]) rather
 * than being rewritten once a second. The system draws the seconds; the app posts the
 * notification once per STEP. That removes a per-second wakeup for the whole workout and,
 * more importantly, means the displayed time cannot drift away from the truth if the app
 * is frozen.
 *
 * The trade-off, stated plainly: the chronometer counts against the wall clock, so if the
 * clock is changed mid-rest the displayed number jumps. What actually fires the signal is
 * the monotonic clock, so the countdown would be wrong while the outcome stayed right.
 * Some third-party notification shades also ignore the chronometer flag and will show a
 * static time instead; the controls still work.
 */
object TimerNotifications {

    const val CHANNEL_RUNNING = "timer_running"
    const val CHANNEL_ALERT = "timer_alert"

    /** The foreground service's notification. Stable, because the service is tied to it. */
    const val ID_RUNNING = 1001

    /** The "it is over" alert. A separate id so it survives the ongoing one being removed. */
    const val ID_ALERT = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val running = NotificationChannel(
            CHANNEL_RUNNING,
            "Timer running",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "The live countdown while a workout timer is running."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        val alert = NotificationChannel(
            CHANNEL_ALERT,
            "Timer finished",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description =
                "Fires when a rest or a workout program is over. The sound and vibration " +
                    "are produced by the app on the alarm stream, so this channel is silent."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannel(running)
        manager.createNotificationChannel(alert)
    }

    /** Tapping the notification returns to the app rather than starting a second copy. */
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(context: Context, command: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TimerReceiver::class.java).setAction(command),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The ongoing notification for a live run.
     *
     * [singleStep] switches the middle button: a plain rest between sets wants "+30 s",
     * because lengthening the pause is the only thing anyone does to it, while a program
     * with steps ahead of it wants "Skip".
     */
    fun running(
        context: Context,
        programName: String,
        step: WorkoutStep,
        stepRemainingMs: Long,
        totalRemainingMs: Long,
        phase: RunPhase,
        singleStep: Boolean,
    ): Notification {
        val running = phase == RunPhase.RUNNING
        val title = buildString {
            append(if (step.kind == StepKind.WORK) step.name else step.name)
            step.blockPosition?.let { append("  ").append(it) }
        }
        val detail = buildString {
            if (!running) append("Paused - ")
            append(formatClock(ceilSeconds(stepRemainingMs)))
            step.groupPosition?.let { append("  ").append(it) }
            if (!singleStep) {
                append("  ").append(formatClock(ceilSeconds(totalRemainingMs))).append(" left")
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(detail)
            .setSubText(programName)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // let the system draw the seconds, so the app does not have to wake up for them
        if (running) {
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + stepRemainingMs)
                .setShowWhen(true)
        }

        builder.addAction(
            if (running) {
                NotificationCompat.Action(0, "Pause", action(context, TimerReceiver.ACTION_PAUSE, 1))
            } else {
                NotificationCompat.Action(0, "Resume", action(context, TimerReceiver.ACTION_RESUME, 2))
            }
        )
        builder.addAction(
            if (singleStep) {
                NotificationCompat.Action(
                    0, "+$NUDGE_SEC s", action(context, TimerReceiver.ACTION_ADD, 3),
                )
            } else {
                NotificationCompat.Action(0, "Skip", action(context, TimerReceiver.ACTION_SKIP, 4))
            }
        )
        builder.addAction(NotificationCompat.Action(0, "Stop", action(context, TimerReceiver.ACTION_STOP, 5)))

        return builder.build()
    }

    /**
     * The "it is over" notification. Not ongoing and self-cancelling on tap: it has done
     * its job the moment it is seen, and an alert that has to be dismissed by hand is one
     * more thing to do between sets.
     */
    fun alert(context: Context, programName: String, singleStep: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(if (singleStep) "Rest is over" else "Workout finished")
            .setContentText(programName)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    /** True when the app may actually show any of this (Android 13+ asks for permission). */
    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(ID_RUNNING) }
        runCatching { manager.cancel(ID_ALERT) }
    }
}
