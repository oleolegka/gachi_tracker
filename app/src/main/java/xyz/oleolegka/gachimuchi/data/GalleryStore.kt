package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import xyz.oleolegka.gachimuchi.domain.CelebrationMode
import xyz.oleolegka.gachimuchi.domain.CelebrationPicture
import xyz.oleolegka.gachimuchi.domain.payloadJson
import xyz.oleolegka.gachimuchi.domain.pickPicture
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID

/**
 * The gallery of celebration pictures: the user's own images, the app's own copies.
 *
 * ── Why a copy and not a reference ──────────────────────────────────────────────
 * The picker hands out a content:// uri that is a permit, not an address: it is granted
 * to this process, it does not survive the original being moved, renamed or deleted, and
 * asking for a persistable grant would mean asking for a level of access to the user's
 * photos this app has no business having. So the bytes are copied into the app's own
 * folder at the moment they are chosen and nothing is ever remembered about where they
 * came from. The cost is disk space (bounded by [MAX_PICTURE_BYTES] per picture); the gain
 * is that a picture, once added, cannot break.
 *
 * No storage permission is involved anywhere in this: the system picker returns a uri for
 * exactly the files that were tapped, and nothing else is ever readable. The phone this
 * app is built for runs GrapheneOS, where the permission list is read.
 *
 * ── Why SharedPreferences for the index ─────────────────────────────────────────
 * The index is a handful of file names. Room would mean a schema, a migration and a DAO
 * for data that is regenerable — worse, it would mean the migration test suite growing a
 * table that has nothing to do with the journal. The FILES are the real store; the index
 * only adds the "for records" mark and the order, and [reconcile] rebuilds their agreement
 * on every start.
 */

/**
 * Per-picture ceiling. A 48 MP phone photo is around 20 MB; the point is not to be strict
 * but to keep a mistaken pick of a 500 MB video-shaped file from filling the phone.
 */
const val MAX_PICTURE_BYTES: Long = 16L * 1024 * 1024

private const val PREFS_NAME = "celebration"
private const val KEY_INDEX = "pictures"
private const val KEY_MODE = "mode"
private const val KEY_ONBOARDED = "onboarded"
private const val DIR_NAME = "celebration"

/** What came of adding one picture. Failures are named so the screen can say which one. */
sealed interface AddOutcome {
    data class Added(val picture: CelebrationPicture) : AddOutcome

    /** Bigger than [MAX_PICTURE_BYTES]; nothing was kept. */
    data object TooBig : AddOutcome

    /** The uri could not be read at all, or held no bytes. */
    data object Unreadable : AddOutcome
}

class GalleryStore internal constructor(context: Context) {
    /*
     * Internal rather than private so a test can build a fresh gallery instead of sharing
     * the process-wide one; application code uses [get] and never this. Same arrangement,
     * and the same reason, as TimerController.
     */

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Internal storage: not readable by other apps, and not swept up by media scanners. */
    val dir: File = File(appContext.filesDir, DIR_NAME)

    private val _pictures = MutableStateFlow(reconcile(readIndex()))
    val pictures: StateFlow<List<CelebrationPicture>> = _pictures.asStateFlow()

    private val _mode = MutableStateFlow(
        CelebrationMode.fromCode(prefs.getInt(KEY_MODE, CelebrationMode.RECORDS_ONLY.code))
    )
    val mode: StateFlow<CelebrationMode> = _mode.asStateFlow()

    /**
     * Whether the "add some pictures" screen has been through. It is set both by adding
     * and by skipping — the screen is an offer, and answering "no" is answering it.
     */
    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        _onboardingDone.value = true
    }

    fun setMode(mode: CelebrationMode) {
        prefs.edit().putInt(KEY_MODE, mode.code).apply()
        _mode.value = mode
    }

    /** The file a picture lives in. The only way anything outside this class finds it. */
    fun fileOf(picture: CelebrationPicture): File = File(dir, picture.id)

    /**
     * Copies what [uri] points at into the app's folder. Suspending and on the IO
     * dispatcher because it is a file copy of up to [MAX_PICTURE_BYTES].
     */
    suspend fun add(uri: Uri): AddOutcome = withContext(Dispatchers.IO) {
        val stream = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return@withContext AddOutcome.Unreadable
        stream.use { copyIn(it) }
    }

    /**
     * The copy itself, over an already-open stream.
     *
     * Nothing here checks that the bytes are an image. Deciding that would mean decoding
     * the file, and a decoder that says "yes" is not proof it will still say yes at
     * display time — the honest failure mode for a file that is not a picture is that it
     * never shows up in the overlay, which is also what happens to an image the decoder
     * runs out of memory on. Cheap to delete, impossible to crash on.
     */
    fun copyIn(input: InputStream): AddOutcome {
        if (!dir.exists() && !dir.mkdirs()) return AddOutcome.Unreadable
        val id = UUID.randomUUID().toString()
        val target = File(dir, id)
        var written = 0L
        try {
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    written += read
                    if (written > MAX_PICTURE_BYTES) {
                        target.delete()
                        return AddOutcome.TooBig
                    }
                    out.write(buffer, 0, read)
                }
            }
        } catch (_: IOException) {
            target.delete()
            return AddOutcome.Unreadable
        }
        if (written == 0L) {
            target.delete()
            return AddOutcome.Unreadable
        }
        val picture = CelebrationPicture(id = id, forRecords = false, addedAt = LocalDate.now().toString())
        writeIndex(_pictures.value + picture)
        return AddOutcome.Added(picture)
    }

    /** Marks a picture as one to keep for records, or unmarks it (see `pickPicture`). */
    fun setForRecords(id: String, forRecords: Boolean) {
        writeIndex(_pictures.value.map { if (it.id == id) it.copy(forRecords = forRecords) else it })
    }

    /**
     * Forgets a picture and deletes the copy. A file that refuses to be deleted still
     * leaves the index — [reconcile] will pick the orphan up on the next start rather than
     * leaving a picture the user removed on screen.
     */
    fun remove(id: String) {
        File(dir, id).delete()
        writeIndex(_pictures.value.filterNot { it.id == id })
    }

    /**
     * The picture for a set that has just been written, or null when this one should pass
     * silently — the mode is consulted by the caller, the pool by the domain.
     */
    fun pick(isRecord: Boolean): CelebrationPicture? = pickPicture(_pictures.value, isRecord)

    // --- the index ---------------------------------------------------------------------

    private fun readIndex(): List<CelebrationPicture> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        return runCatching { payloadJson.decodeFromString<List<CelebrationPicture>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun writeIndex(pictures: List<CelebrationPicture>) {
        prefs.edit().putString(KEY_INDEX, payloadJson.encodeToString(pictures)).apply()
        _pictures.value = pictures
    }

    /**
     * Makes the index and the folder agree, and it is the folder that wins.
     *
     * The two can drift apart the usual way — the process dies between the copy landing on
     * disk and the index being written — and in both directions the answer is to trust the
     * files: an index entry with no file would be a picture that never appears, and a file
     * with no index entry is a copy nobody can see, delete or ever get rid of.
     */
    private fun reconcile(indexed: List<CelebrationPicture>): List<CelebrationPicture> {
        val onDisk = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val kept = indexed.filter { it.id in onDisk }
        val known = kept.map { it.id }.toSet()
        onDisk.filterNot { it in known }.forEach { File(dir, it).delete() }
        if (kept.size != indexed.size) {
            prefs.edit().putString(KEY_INDEX, payloadJson.encodeToString(kept)).apply()
        }
        return kept
    }

    companion object {
        @Volatile
        private var instance: GalleryStore? = null

        /**
         * The one instance for the process. The settings screen, the onboarding screen and
         * the celebration overlay all read the same gallery, and they are in three
         * different places in the tree — passing the store down through every composable
         * between them would be a parameter on screens that have nothing to do with it.
         * Same reasoning as `TimerController.get`.
         */
        fun get(context: Context): GalleryStore =
            instance ?: synchronized(this) {
                instance ?: GalleryStore(context).also { instance = it }
            }
    }
}
