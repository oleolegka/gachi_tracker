package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.oleolegka.gachimuchi.domain.MAX_STEP_SEC
import xyz.oleolegka.gachimuchi.domain.MIN_STEP_SEC
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.payloadJson

/**
 * Timer preferences and the crash-surviving copy of the active run, in SharedPreferences.
 *
 * ── Why SharedPreferences and not Room, and not DataStore ───────────────────────
 * Both stores here are written from a BroadcastReceiver that may be the only thing alive
 * in a freshly resurrected process, with no coroutine scope to speak of and a few
 * milliseconds before the receiver returns. SharedPreferences has a synchronous
 * `commit()` that is done when it returns; DataStore is suspend-only and Room needs a
 * scope and a thread. For a dozen scalars and one small JSON blob that trade is the right
 * way round: the cost is a synchronous disk write on the main thread at step boundaries,
 * which is a handful of bytes into an already-loaded preference file.
 *
 * ── The run snapshot is the timer's memory ──────────────────────────────────────
 * It stores the step list and the END MOMENT of the current step, never "seconds left"
 * (see domain/Runner.kt). Together with the boot reference that is enough to rebuild an
 * accurate run after the process was killed, and enough to know when the state is from a
 * previous boot and must be dropped.
 */
private const val PREFS_NAME = "timer"

private const val KEY_DEFAULT_REST = "default_rest_sec"
private const val KEY_AUTO_START = "auto_start_rest"
private const val KEY_ADAPT = "adapt_rest"
private const val KEY_PREPARE = "prepare_sec"
private const val KEY_SOUND = "sound"
private const val KEY_VIBRATE = "vibrate"
private const val KEY_TICKS = "countdown_ticks"
private const val KEY_SPEAK = "speak"
private const val KEY_SETS = "default_sets"
private const val KEY_ENABLED = "timer_enabled"
private const val KEY_RUN = "run_snapshot"

class TimerStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<TimerSettings> = _settings.asStateFlow()

    /**
     * Whether the user has switched the timer on at all.
     *
     * Separate from the individual settings because it gates the notification permission
     * request: nothing asks for POST_NOTIFICATIONS until this is turned on deliberately,
     * so the first launch of the app never opens a system dialog the user did not ask for.
     */
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private fun read(): TimerSettings {
        val defaults = TimerSettings()
        return TimerSettings(
            defaultRestSec = prefs.getInt(KEY_DEFAULT_REST, defaults.defaultRestSec)
                .coerceIn(MIN_STEP_SEC, MAX_STEP_SEC),
            autoStartRest = prefs.getBoolean(KEY_AUTO_START, defaults.autoStartRest),
            adaptRestToExercise = prefs.getBoolean(KEY_ADAPT, defaults.adaptRestToExercise),
            prepareSec = prefs.getInt(KEY_PREPARE, defaults.prepareSec).coerceIn(0, MAX_STEP_SEC),
            sound = prefs.getBoolean(KEY_SOUND, defaults.sound),
            vibrate = prefs.getBoolean(KEY_VIBRATE, defaults.vibrate),
            countdownTicks = prefs.getBoolean(KEY_TICKS, defaults.countdownTicks),
            speak = prefs.getBoolean(KEY_SPEAK, defaults.speak),
            defaultSets = prefs.getInt(KEY_SETS, defaults.defaultSets).coerceIn(1, 99),
        )
    }

    fun update(settings: TimerSettings) {
        prefs.edit()
            .putInt(KEY_DEFAULT_REST, settings.defaultRestSec.coerceIn(MIN_STEP_SEC, MAX_STEP_SEC))
            .putBoolean(KEY_AUTO_START, settings.autoStartRest)
            .putBoolean(KEY_ADAPT, settings.adaptRestToExercise)
            .putInt(KEY_PREPARE, settings.prepareSec.coerceIn(0, MAX_STEP_SEC))
            .putBoolean(KEY_SOUND, settings.sound)
            .putBoolean(KEY_VIBRATE, settings.vibrate)
            .putBoolean(KEY_TICKS, settings.countdownTicks)
            .putBoolean(KEY_SPEAK, settings.speak)
            .putInt(KEY_SETS, settings.defaultSets.coerceIn(1, 99))
            .apply()
        _settings.value = read()
    }

    // --- the active run ---------------------------------------------------------------

    /**
     * Writes the run synchronously. `commit()` rather than `apply()`: the caller is often
     * a receiver about to return, after which the process may be frozen again, and an
     * asynchronous write that has not landed yet is a run that never happened.
     */
    fun saveRun(snapshot: RunSnapshot) {
        prefs.edit().putString(KEY_RUN, payloadJson.encodeToString(snapshot)).commit()
    }

    fun clearRun() {
        prefs.edit().remove(KEY_RUN).commit()
    }

    /**
     * The stored run, or null when there is none or it cannot be read.
     *
     * A snapshot that fails to parse is dropped rather than propagated: it can only happen
     * when the step format changed under an app update, and taking the timer screen down
     * on every launch until the user clears the app data is a far worse failure than
     * losing one rest countdown.
     */
    fun loadRun(): RunSnapshot? {
        val raw = prefs.getString(KEY_RUN, null) ?: return null
        return runCatching { payloadJson.decodeFromString<RunSnapshot>(raw) }
            .onFailure { clearRun() }
            .getOrNull()
    }
}
