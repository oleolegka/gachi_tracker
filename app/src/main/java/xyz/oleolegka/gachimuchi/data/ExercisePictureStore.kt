package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Where a picture attached to an exercise lives: files in the app's own folder, copied in from
 * whatever the camera or the gallery picker handed back.
 *
 * ── The same arrangement [GalleryStore] already uses, and the same reason ──────────
 * A `content://` uri from the system photo picker or from the camera app is a permit to read
 * ONE FILE ONCE, not an address — it does not survive the original being moved, renamed or
 * deleted, and it is not something this app should hold onto. So the bytes are copied into this
 * folder the moment a picture is chosen and nothing is remembered about where they came from.
 * See [GalleryStore]'s own KDoc for the fuller version of this argument; it is not repeated
 * mechanism by mechanism here because it is the same one.
 *
 * ── Why this is not a second [GalleryStore] ─────────────────────────────────────
 * The gallery keeps its own index (a JSON list in `SharedPreferences`) because nothing else
 * knows which files belong to it. A picture here has an owner from the moment it exists: the
 * catalog row that names it in `exercises.picture_id`
 * ([xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.pictureId]), which Room already keeps and
 * already observes. A second index of the same fact would be one more copy that can drift from
 * the first — so there is none. [fileOf] is a pure name lookup, not a read of any list, and this
 * class holds no state and needs no `StateFlow`: the exercise's own row IS the state.
 *
 * One consequence worth saying out loud: unlike [GalleryStore.reconcile], nothing here notices a
 * file whose owning row was never written because the process died between the copy landing and
 * the column being set. That copy is a few megabytes leaked on disk, never a dangling reference —
 * [add] returns the new id and the caller writes it to the row in the very next line ([edit] the
 * only caller), so the window is one suspend call wide. Accepted rather than answered with a
 * reconcile pass of its own, which would mean walking the whole catalog on every start to guard
 * against a crash landing in one specific half-second.
 */
class ExercisePictureStore internal constructor(context: Context) {
    /*
     * Internal rather than private so a test can build a fresh store instead of sharing the
     * process-wide one — see [GalleryStore] for the same arrangement and the same reason.
     */

    private val appContext = context.applicationContext

    /** Internal storage: not readable by other apps, and not swept up by media scanners. */
    val dir: File = File(appContext.filesDir, DIR_NAME)

    /** The file a picture lives in — the only way anything outside this class finds it. */
    fun fileOf(pictureId: String): File = File(dir, pictureId)

    /**
     * Copies what [uri] points at into this folder. Suspending and on the IO dispatcher for the
     * same reason [GalleryStore.add] is: a file copy of up to [MAX_PICTURE_BYTES].
     */
    suspend fun add(uri: Uri): ExercisePictureOutcome = withContext(Dispatchers.IO) {
        val stream = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return@withContext ExercisePictureOutcome.Unreadable
        stream.use { copyIn(it) }
    }

    /**
     * The copy itself, over an already-open stream — see [GalleryStore.copyIn] for why nothing
     * here checks that the bytes are an image.
     */
    fun copyIn(input: InputStream): ExercisePictureOutcome {
        if (!dir.exists() && !dir.mkdirs()) return ExercisePictureOutcome.Unreadable
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
                        return ExercisePictureOutcome.TooBig
                    }
                    out.write(buffer, 0, read)
                }
            }
        } catch (_: IOException) {
            target.delete()
            return ExercisePictureOutcome.Unreadable
        }
        if (written == 0L) {
            target.delete()
            return ExercisePictureOutcome.Unreadable
        }
        return ExercisePictureOutcome.Added(id)
    }

    /** Deletes a picture's file. Deleting the exercise's OWN row is a different question — see
     *  [xyz.oleolegka.gachimuchi.data.ActivityRepository.deleteExercise]'s own KDoc for why that
     *  is a journal event and never touches this folder at all. */
    fun remove(pictureId: String) {
        File(dir, pictureId).delete()
    }

    companion object {
        private const val DIR_NAME = "exercise_pictures"

        @Volatile
        private var instance: ExercisePictureStore? = null

        /** The one instance for the process — see [GalleryStore.get] for the same arrangement. */
        fun get(context: Context): ExercisePictureStore =
            instance ?: synchronized(this) {
                instance ?: ExercisePictureStore(context).also { instance = it }
            }
    }
}

/** What came of adding one picture — the same three answers [AddOutcome] gives for the gallery. */
sealed interface ExercisePictureOutcome {
    /** [pictureId] is the file's new name — pass it to
     *  [xyz.oleolegka.gachimuchi.data.ActivityRepository.setPicture]. */
    data class Added(val pictureId: String) : ExercisePictureOutcome

    /** Bigger than [MAX_PICTURE_BYTES]; nothing was kept. */
    data object TooBig : ExercisePictureOutcome

    /** The uri could not be read at all, or held no bytes. */
    data object Unreadable : ExercisePictureOutcome
}
