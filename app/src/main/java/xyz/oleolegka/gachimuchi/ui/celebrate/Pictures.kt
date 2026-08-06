package xyz.oleolegka.gachimuchi.ui.celebrate

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.oleolegka.gachimuchi.data.AddOutcome
import xyz.oleolegka.gachimuchi.data.GalleryStore
import java.io.File
import kotlin.math.max

/**
 * Loading and choosing the user's pictures — the two pieces of Android plumbing the
 * celebration needs, kept out of the screens.
 *
 * There is no image-loading library here on purpose. Coil would be the obvious dependency
 * and it would earn its place in an app that loads pictures from a network into long
 * lists; this one shows one picture at a time out of its own folder, and the whole of what
 * a loader would do for it is the twenty lines below.
 */

/** How many pictures one trip to the picker may bring back. */
private const val MAX_PICK = 30

/**
 * Decodes a picture down to roughly [maxPx] on its longer side.
 *
 * Downsampling is not an optimisation here, it is the difference between working and not:
 * a modern phone photo decodes to something like 200 MB of bitmap, which is several times
 * the heap this app is allowed. `inSampleSize` does the scaling inside the decoder, so the
 * full-size bitmap never exists.
 *
 * Returns null for anything that does not decode — a file that is not an image, a corrupt
 * one, or one that ran the heap out anyway. The caller shows nothing in that case, which
 * is the same thing it does when the gallery is empty.
 */
fun decodeScaled(file: File, maxPx: Int): ImageBitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(file.path, bounds) }
    val longest = max(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= maxPx) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(file.path, options)?.asImageBitmap() }.getOrNull()
}

/** [decodeScaled] off the main thread, re-run when the file or the size changes. */
@Composable
fun rememberPicture(file: File?, maxPx: Int): ImageBitmap? =
    produceState<ImageBitmap?>(null, file?.path, maxPx) {
        value = file?.let { withContext(Dispatchers.IO) { decodeScaled(it, maxPx) } }
    }.value

/** What one trip to the picker did, in numbers the screen can turn into one sentence. */
data class PickResult(val added: Int, val tooBig: Int, val unreadable: Int) {
    val failed: Int get() = tooBig + unreadable

    /** Null when everything worked — nothing needs saying then, the list itself grew. */
    fun message(): String? = when {
        failed == 0 -> null
        added == 0 && unreadable == 0 -> "Too large (over 16 MB each): $tooBig"
        added == 0 && tooBig == 0 -> "Could not be read: $unreadable"
        else -> "Added $added, skipped $failed (too large: $tooBig, unreadable: $unreadable)"
    }
}

/**
 * The system photo picker, wired to copy whatever comes back into the gallery.
 *
 * PickVisualMedia is the point of the whole arrangement: it is the system's own picker,
 * running in the system's own process, and it hands back exactly the files that were
 * tapped. No READ_MEDIA_IMAGES, no folder to point at, nothing to grant. On a phone
 * without the modern picker the androidx contract falls back to ACTION_OPEN_DOCUMENT,
 * which is permission-free in the same way — worth knowing, because that fallback is what
 * a GrapheneOS phone with no Google services is likely to be using.
 *
 * Returns a lambda that opens it; [onResult] is called once the copies are on disk.
 */
@Composable
fun rememberPicturePicker(gallery: GalleryStore, onResult: (PickResult) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult // cancelled: say nothing
        scope.launch {
            var added = 0
            var tooBig = 0
            var unreadable = 0
            uris.forEach { uri ->
                when (gallery.add(uri)) {
                    is AddOutcome.Added -> added++
                    AddOutcome.TooBig -> tooBig++
                    AddOutcome.Unreadable -> unreadable++
                }
            }
            onResult(PickResult(added, tooBig, unreadable))
        }
    }
    val request = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }
    return { launcher.launch(request) }
}
