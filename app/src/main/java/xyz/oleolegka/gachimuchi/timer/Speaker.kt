package xyz.oleolegka.gachimuchi.timer

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Spoken step names — an optional extra, never a requirement.
 *
 * ── The whole reason this class is defensive ────────────────────────────────────
 * This app is built for a phone running GrapheneOS with no Google services, and the
 * speech engine that most Android phones have is a Play component. There may therefore be
 * NO TTS ENGINE ON THE DEVICE AT ALL. Announcements are consequently:
 *
 *  - checked for, not assumed: [status] only becomes [SpeechStatus.READY] after an engine
 *    has initialised AND has reported that it has English;
 *  - silent when absent: [speak] is a no-op, nothing throws, nothing is queued, and the
 *    timer keeps counting down with tones and vibration exactly as before;
 *  - never a prompt: the app does not send the user to install an engine, does not fire
 *    ACTION_INSTALL_TTS_DATA, and does not nag. The settings screen states in one line
 *    that no engine was found and the toggle is disabled.
 *
 * ── The manifest side of it ─────────────────────────────────────────────────────
 * Since Android 11, an app cannot even SEE a TTS engine without declaring the matching
 * `<queries>` element, so the manifest carries one. Without it the check would report "no
 * engine" on a device that has one.
 *
 * ── Speech goes out on the alarm stream too ─────────────────────────────────────
 * For the same reason the tones do: at the gym the phone is on silent, and an
 * announcement on the media or notification stream would not be heard.
 */
enum class SpeechStatus {
    /** Not asked yet. */
    UNKNOWN,

    /** Initialising — the engine binds asynchronously. */
    STARTING,

    /** An engine is present and speaks English. */
    READY,

    /** No engine, or one that has no English. Announcements stay off and stay quiet. */
    UNAVAILABLE,
}

class Speaker(context: Context) {

    private val app = context.applicationContext
    private var engine: TextToSpeech? = null

    private val _status = MutableStateFlow(SpeechStatus.UNKNOWN)
    val status: StateFlow<SpeechStatus> = _status.asStateFlow()

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Binds the engine if it has not been tried yet. Safe to call repeatedly and from any
     * thread; the result arrives on [status].
     */
    fun prepare() {
        if (_status.value != SpeechStatus.UNKNOWN) return
        _status.value = SpeechStatus.STARTING
        runCatching {
            engine = TextToSpeech(app) { initStatus ->
                val ready = initStatus == TextToSpeech.SUCCESS && hasEnglish()
                _status.value = if (ready) SpeechStatus.READY else SpeechStatus.UNAVAILABLE
                if (!ready) shutdown()
            }
        }.onFailure {
            // a missing or broken engine must not take the timer with it
            _status.value = SpeechStatus.UNAVAILABLE
        }
    }

    private fun hasEnglish(): Boolean {
        val tts = engine ?: return false
        val result = runCatching {
            tts.setAudioAttributes(audioAttributes)
            tts.setLanguage(Locale.ENGLISH)
        }.getOrNull() ?: return false
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * Says [text], or does nothing at all.
     *
     * [QUEUE_FLUSH] on purpose: a step boundary makes the previous announcement obsolete,
     * and a queue that backs up would have the phone narrating the set before last while
     * the current one is already half over.
     */
    fun speak(text: String) {
        if (_status.value != SpeechStatus.READY) return
        runCatching { engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text) }
    }

    fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
    }
}
