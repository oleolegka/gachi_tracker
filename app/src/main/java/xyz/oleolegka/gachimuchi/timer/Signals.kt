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
 * That ordering is now literal: the vibrator is asked FIRST in every method here. The
 * vibrator call is a binder message that returns immediately; the tone can involve opening
 * an audio track, which on a cold audio stack takes long enough to be felt. Buzzing first
 * means a problem on the audio side can make the tone late but can no longer make the buzz
 * late with it — two channels, and the important one does not wait for the other.
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
 *
 * ── One tone at a time, so two are never asked for at once ──────────────────────
 * A [ToneGenerator] plays a single tone: `startTone` while something is sounding cuts the
 * previous one off, and [Vibrator] behaves the same way with waveforms. This class does not
 * try to hide that — mixing a tick into a boundary beep would only produce a noise neither
 * of them is. Instead the CALLER never asks for two at the same instant; that rule lives
 * with the countdown, in `timerCue` (domain/Runner.kt), where it can be tested.
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
     * Created ahead of time by [prime] and kept for the whole run: constructing a
     * [ToneGenerator] opens an audio track, which is slow enough to be audible as a delay if
     * it happens at a boundary — and it used to, because the generator was built lazily on
     * the first tone and thrown away at the end of every run.
     *
     * Nullable and every use is guarded: ToneGenerator throws when the audio hardware is
     * busy, and a timer must not die because something else held the alarm stream.
     */
    private var tones: ToneGenerator? = null

    /**
     * Set when construction has already failed once, so a boundary does not pay for a fresh
     * attempt every time. Cleared by [prime], i.e. once per run: a device that was busy when
     * the last run started deserves another try, but not twenty-four more within one run.
     */
    private var toneUnavailable = false

    /**
     * Builds the audio engine now, so that the first boundary does not.
     *
     * Called when a run starts. Safe to call repeatedly and safe to call off the main
     * thread; the synchronization is here because the countdown loop and the caller that
     * starts a run are different threads and both can reach the generator.
     */
    @Synchronized
    fun prime() {
        toneUnavailable = false
        tones()
    }

    @Synchronized
    private fun tones(): ToneGenerator? {
        tones?.let { return it }
        if (toneUnavailable) return null
        val built = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME) }.getOrNull()
        if (built == null) toneUnavailable = true else tones = built
        return built
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
        if (!settings.countdownTicks) return
        if (settings.vibrate) vibrate(longArrayOf(0, 40))
        if (settings.sound) tone(ToneGenerator.TONE_PROP_BEEP, 90)
    }

    /**
     * A step boundary. The pattern differs by what is STARTING, so that "go" and "stop"
     * are distinguishable through a pocket without taking the phone out.
     */
    fun boundary(settings: TimerSettings, starting: StepKind) {
        when (starting) {
            StepKind.WORK -> {
                if (settings.vibrate) vibrate(longArrayOf(0, 250, 120, 250))
                if (settings.sound) tone(ToneGenerator.TONE_PROP_BEEP2, 350)
            }

            StepKind.REST, StepKind.PREPARE -> {
                if (settings.vibrate) vibrate(longArrayOf(0, 400))
                if (settings.sound) tone(ToneGenerator.TONE_PROP_BEEP, 250)
            }
        }
    }

    /**
     * A rest floor coming due: the bench is free.
     *
     * A shape of its own rather than a reuse of [boundary], because the two mean opposite
     * kinds of thing and are heard in the same room. A boundary is an ORDER from the
     * conductor — go now, stop now — and it lands while you are mid-set. A floor is a
     * PERMISSION, and it lands while you are standing around. Borrowing the "start work"
     * pattern for it would train the one reflex a workout timer must not train: moving
     * because a noise happened, without knowing which noise.
     *
     * Deliberately the quietest of the three patterns — two short taps and one short tone,
     * 400 ms of waveform against the boundary's 620 — and comfortably inside
     * `FLOOR_STAGGER_MS` (domain/Floors.kt), which is what guarantees that two floors coming
     * due together do not talk over each other.
     */
    fun floor(settings: TimerSettings) {
        if (settings.vibrate) vibrate(longArrayOf(0, 150, 100, 150))
        if (settings.sound) tone(ToneGenerator.TONE_PROP_PROMPT, 300)
    }

    /** The end of the whole program: longer and unlike any boundary within it. */
    fun finish(settings: TimerSettings) {
        if (settings.vibrate) vibrate(longArrayOf(0, 500, 200, 500, 200, 700))
        if (settings.sound) tone(ToneGenerator.TONE_PROP_ACK, FINISH_TONE_MS)
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

    /**
     * Frees the audio track.
     *
     * Releasing a [ToneGenerator] stops whatever it is playing, so this must NOT be called
     * in the same breath as a tone — which is what used to happen at the end of a program:
     * the three-quarter-second finishing chime was cut off a millisecond into itself. The
     * caller defers it past [SIGNAL_TAIL_MS] instead (see TimerController).
     */
    @Synchronized
    fun release() {
        runCatching { tones?.release() }
        tones = null
        toneUnavailable = false
    }

    companion object {
        /** Out of 100. Loud, because the phone is across the room or in a bag. */
        private const val TONE_VOLUME = 90

        /** Length of the end-of-program chime. */
        const val FINISH_TONE_MS = 750

        /** How long to leave the engine alive after the last signal, so nothing is cut off. */
        const val SIGNAL_TAIL_MS = 2_000L
    }
}
