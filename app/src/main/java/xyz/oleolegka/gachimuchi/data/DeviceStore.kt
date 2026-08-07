package xyz.oleolegka.gachimuchi.data

import android.content.Context
import xyz.oleolegka.gachimuchi.domain.newUid

/**
 * The identity of THIS INSTALLATION — one id, minted the first time it is asked for and kept
 * for as long as the app stays installed.
 *
 * ── What it is for ──────────────────────────────────────────────────────────────
 * Rows now name themselves (`uid`, schema version 8), which is what makes a union of two
 * journals possible at all. This answers the other half of the question: WHICH journal a row
 * came from. An export carries it, so a file can say where it was written; a merge can use it
 * to tell "the same row arriving twice" from "two rows that happen to look alike"; and a
 * conflict that has to be reported to a human can name the device rather than a number.
 *
 * ── What it is NOT ──────────────────────────────────────────────────────────────
 * It is not a device fingerprint and deliberately not derived from one. `ANDROID_ID`, the
 * advertising id and the build serial are all identifiers of a PHONE, they are shared with
 * other apps or survive an uninstall, and none of that is wanted here — this app has no
 * business being able to recognise a person's hardware. A random uuid in the app's own
 * preferences is scoped exactly right: it dies with the app's data and means nothing outside
 * it.
 *
 * It is also not a user id. One person with two phones has two of these, which is the point.
 *
 * ── Why SharedPreferences and not the database ──────────────────────────────────
 * The database is the thing that gets exported, merged and one day restored from somewhere
 * else. An id that says "this installation" must not travel inside it, or a restored backup
 * would claim to be the device it was taken from. Preferences are the local, non-travelling
 * store, which is what this needs to be.
 */
private const val PREFS_NAME = "device"
private const val KEY_DEVICE_ID = "device_id"

class DeviceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The id of this installation, minting it on first use.
     *
     * Written with `commit()` rather than `apply()`, and that is the whole reason this is not
     * a one-liner. `apply()` returns before the value reaches disk; if the process dies in
     * that window the next call mints a SECOND id, and rows written before and after the crash
     * would claim to come from two different devices. The write happens once in the lifetime
     * of an install, so paying for it synchronously costs nothing worth measuring.
     */
    val deviceId: String
        get() = synchronized(this) {
            prefs.getString(KEY_DEVICE_ID, null) ?: newUid().also {
                prefs.edit().putString(KEY_DEVICE_ID, it).commit()
            }
        }
}
