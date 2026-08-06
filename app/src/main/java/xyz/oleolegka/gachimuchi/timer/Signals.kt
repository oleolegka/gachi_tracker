package xyz.oleolegka.gachimuchi.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.TimerSettings

/**
 * The sound and the buzz at a step boundary.
 *
 * ── Vibration is the primary channel, not the fallback ──────────────────────────
 * The phone is in a pocket in a room with music playing and is very often on silent. A
 * timer that only beeps is a timer that is missed, so every signal vibrates when
 * vibration is on, and the tone is the addition rather than the other way round.
 *
 * ── Everything goes out on the ALARM stream ─────────────────────────────────────
 * [AudioManager.STREAM_ALARM], and vibration is tagged with the alarm usage. That is the
 * one channel Android does not silence when the ringer is set to silent, which is exactly
 * the state a phone is in at the gym. The reference app this feature follows learned the
 * same lesson the hard way: it originally played through the notification channel and its
 * users heard nothing until it was moved off it.
 *
 * The price is honest and worth stating: these signals come out at ALARM volume and
 * ignore the ringer switch. That is deliberate for a workout timer and it is also exactly
 * what will be startling if the timer is left running by accident.
 *
 * ── Why generated tones and no audio files ──────────────────────────────────────
 * [ToneGenerator] is a system service, so the app ships no sound assets, decodes nothing
 * and adds no dependency. The tones are the plain telephony ones; they are not pretty,
 * they are unmistakable, and they are three distinct shapes so that "three, two, one",
 * "that step is over" and "the workout is over" are told apart without looking.
 */
class Signals(context: Context) {

    private val app = context.applicationContext

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Created lazily and kept: constructing a [ToneGenerator] opens an audio track, which
     * is slow enough to be audible as a delay if done at every boundary. It is released
     * when the run ends ([release]).
     *
     * Nullable and every use is guarded: ToneGenerator throws when the audio hardware is
     * busy, and a timer must not die because something else held the alarm stream.
     */
    private var tones: ToneGenerator? = null

    private fun tones(): ToneGenerator? {
        tones?.let { return it }
        return runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
        }.getOrNull()?.also { tones = it }
    }

    /** The alarm-usage tag, so the vibration is not muted along with notifications. */
    private val vibrationAttributes: VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        } else {
            null
        }

    @Suppress("DEPRECATION")
    private val legacyAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** The last few seconds of a step: a short tap, deliberately small next to a boundary. */
    fun tick(settings: TimerSettings) {
        if (settings.countdownTicks) {
            if (settings.sound) tone(ToneGenerator.TONE_PROP_BEEP, 90)
            if (settings.vibrate) vibrate(longArrayOf(0, 40))
        }
    }

    /**
     * A step boundary. The pattern differs by what is STARTING, so that "go" and "stop"
     * are distinguishable through a pocket without taking the phone out.
     */
    fun boundary(settings: TimerSettings, starting: StepKind) {
        when (starting) {
            StepKind.WORK -> {
                if (settings.sound) tone(ToneGenerator.TONE_PROP_BEEP2, 350)
                if (settings.vibrate) vibrate(longArrayOf(0, 250, 120, 250))
            }

            StepKind.REST, StepKind.PREPARE -> {
                if (settings.sound) tone(ToneGenerator.TONE_PROP_BEEP, 250)
                if (settings.vibrate) vibrate(longArrayOf(0, 400))
            }
        }
    }

    /** The end of the whole program: longer and unlike any boundary within it. */
    fun finish(settings: TimerSettings) {
        if (settings.sound) tone(ToneGenerator.TONE_PROP_ACK, 750)
        if (settings.vibrate) vibrate(longArrayOf(0, 500, 200, 500, 200, 700))
    }

    private fun tone(type: Int, durationMs: Int) {
        runCatching { tones()?.startTone(type, durationMs) }
    }

    private fun vibrate(pattern: LongArray) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        runCatching {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && vibrationAttributes != null ->
                    device.vibrate(effect, vibrationAttributes)

                else -> {
                    @Suppress("DEPRECATION")
                    device.vibrate(effect, legacyAudioAttributes)
                }
            }
        }
    }

    /** Frees the audio track. Called when a run ends; the next run rebuilds it. */
    fun release() {
        runCatching { tones?.release() }
        tones = null
    }

    private companion object {
        /** Out of 100. Loud, because the phone is across the room or in a bag. */
        const val TONE_VOLUME = 90
    }
}
