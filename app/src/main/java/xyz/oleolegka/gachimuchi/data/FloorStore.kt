package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.content.SharedPreferences
import xyz.oleolegka.gachimuchi.domain.RestFloor
import xyz.oleolegka.gachimuchi.domain.payloadJson

/**
 * The rest floors, in SharedPreferences, so they outlive the process that started them.
 *
 * ── Same store, same reasoning as the run snapshot ──────────────────────────────
 * Everything said at the top of data/TimerStore.kt applies unchanged: this is written from
 * a BroadcastReceiver that may be the only thing alive in a freshly resurrected process,
 * with milliseconds before the receiver returns, so the write has to be synchronous and the
 * API has to be callable without a coroutine scope. `commit()` rather than `apply()`.
 *
 * ── Why floors are persisted when a run is not resumed ──────────────────────────
 * A run from a previous boot is thrown away, because its state is a schedule of monotonic
 * readings that no longer mean anything. A floor is one sentence — not before moment T —
 * written down on both clocks, so a restart destroys one copy and leaves the other; see
 * `carriedAcrossReboot` in domain/Floors.kt. Floors therefore survive both process death
 * and a reboot, and this file is what makes the first of those true.
 *
 * ── Kept apart from the timer's preference file on purpose ──────────────────────
 * A different file, not a different key in "timer". The floors and the run are written by
 * different owners at different moments (a floor is written when a set is logged, the run
 * at every step boundary), and sharing a preference file means every floor write drags the
 * whole run blob through a re-serialise on the same lock. Nothing about the timer's
 * settings needs to be visible here either.
 */
private const val PREFS_NAME = "floors"
private const val KEY_FLOORS = "floors"

class FloorStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Writes the whole list synchronously. An empty list is stored as nothing at all. */
    fun save(floors: List<RestFloor>) {
        if (floors.isEmpty()) {
            clear()
            return
        }
        prefs.edit().putString(KEY_FLOORS, payloadJson.encodeToString(floors)).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY_FLOORS).commit()
    }

    /**
     * The stored floors, or an empty list when there are none or they cannot be read.
     *
     * A blob that fails to parse is dropped rather than propagated, for the same reason the
     * run snapshot is: it can only happen when the format changed under an app update, and
     * throwing on every launch until the user clears the app data is a far worse failure
     * than losing a couple of rest countdowns.
     */
    fun load(): List<RestFloor> {
        val raw = prefs.getString(KEY_FLOORS, null) ?: return emptyList()
        return runCatching { payloadJson.decodeFromString<List<RestFloor>>(raw) }
            .onFailure { clear() }
            .getOrDefault(emptyList())
    }
}
