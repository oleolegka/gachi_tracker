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
 *
 * ── The rest floors are the one thing here that IS redrawn on a clock ───────────
 * [floors] shows several countdowns at once ("Bench ready · Abs 1:20") and the platform
 * chronometer can only render one time, so that line is text the app writes and therefore
 * text the app has to rewrite. It is redrawn at most once a second and only when the text
 * actually changed — the pacing lives in timer/FloorController.restartNotifyLoop, which is
 * the only thing in the app that posts [ID_RUNNING] on behalf of the floors.
 *
 * The cost is bounded and stated plainly: one wakeup a second, only while a rest is counting
 * AND no conductor is running, and never while the CPU is suspended, because the floors take
 * no wake lock (see FloorController). A phone in a pocket with the screen off is not running
 * this loop; a phone being looked at is, which is the only time the line is read.
 */
object TimerNotifications {

    const val CHANNEL_RUNNING = "timer_running"
    const val CHANNEL_ALERT = "timer_alert"

    /**
     * The foreground service's notification. Stable, because the service is tied to it.
     *
     * ── One id, two possible authors, and the order of precedence ───────────────
     * There is exactly ONE foreground service (timer/TimerService.kt) and a foreground
     * service has exactly one notification, so the conductor and the rest floors share this
     * id rather than each having their own. They cannot both draw it, and the rule is that
     * the CONDUCTOR WINS: while a run exists this notification is the run's, and the floors
     * neither post nor cancel it. See [floors] for why that is not a loss.
     */
    const val ID_RUNNING = 1001

    /** The "it is over" alert. A separate id so it survives the ongoing one being removed. */
    const val ID_ALERT = 1002

    /**
     * The line about rests that matured while a conductor had them muted.
     *
     * Its own id because its lifetime is its own: it is posted at the moment a protocol ends
     * and stays until it is read, which is neither the service's lifetime nor the ongoing
     * notification's. Putting it in [ID_RUNNING] would mean it vanished the instant the last
     * rest matured — which, for a summary produced when everything already matured, is
     * immediately.
     */
    const val ID_FLOOR_SUMMARY = 1003

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val running = NotificationChannel(
            CHANNEL_RUNNING,
            "Timer running",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description =
                "The live countdown while a workout timer is running, and the rests " +
                    "between sets while they are counting."
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
     * The ongoing notification for the rest floors, when no conductor is running.
     *
     * ── Why this exists at all, when a rest is only a "not before" ──────────────
     * It is not primarily a display. It is the notification of the FOREGROUND SERVICE that
     * now stays up while any rest is still counting, and the service is there so that the
     * process is not frozen and the rest's own coroutine — the accurate first line — actually
     * gets to run. The platform requires a foreground service to show something; given that
     * something has to be shown, it may as well be the answer to the question the user would
     * otherwise unlock the phone for.
     *
     * [line] comes from `floorNotificationLine` in domain/FloorLines.kt and is the whole
     * content: "Bench ready · Abs 1:20". It is the TITLE rather than the body because it is
     * the only thing here worth reading, and a collapsed notification shows the title.
     *
     * ── One action, and only when it has something to act on ────────────────────
     * [dismissLabel] is null when nothing is ready, and then there is no button. The button
     * clears every ready rest at once, which is the only thing anybody wants to do to a rest
     * that is over from a lock screen; anything finer needs the list, and the list is in the
     * app. Deliberately no "add a minute" and no "stop": lengthening a rest is a decision
     * made while looking at the numbers, and there is nothing to stop — a floor that is over
     * is over whether or not it is on the screen.
     */
    fun floors(context: Context, line: String, dismissLabel: String?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(line)
            .setSubText("Rest")
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (dismissLabel != null) {
            builder.addAction(
                NotificationCompat.Action(
                    0,
                    dismissLabel,
                    action(context, TimerReceiver.ACTION_FLOOR_DISMISS, 6),
                )
            )
        }
        return builder.build()
    }

    /**
     * "Bench has been ready for 1:20" — what matured while a protocol had the room.
     *
     * Not ongoing, and on the LOW channel rather than the alert one. It is posted in the same
     * second as the conductor's own "Workout finished", and two heads-up banners arriving
     * together is how both of them end up unread; this one is the lesser of the two and waits
     * in the shade instead of competing for the screen. Silent for the reason the whole file
     * is silent: the app makes its own noises, and this particular message is by definition
     * the one that was decided not to be worth a noise (see `releaseFloors`).
     */
    fun floorSummary(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(text)
            .setSubText("Rest")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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

    /**
     * Takes down the CONDUCTOR'S notifications. Not the floors' summary, which is not the
     * conductor's to remove: a run ending is the very thing that produces it.
     *
     * [ID_RUNNING] is included even though the floors may want it back a moment later, and
     * they take it back themselves — see `FloorController.conductorStopped`. Leaving the
     * conductor's content up while it drew a run that no longer exists would be worse than
     * the flicker.
     */
    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(ID_RUNNING) }
        runCatching { manager.cancel(ID_ALERT) }
    }
}
