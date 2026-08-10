package xyz.oleolegka.gachimuchi.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * Choosing ONE picture for something that is not the celebration gallery — today, an
 * exercise's own picture (see `ui/components/ExerciseEditor.kt`). The gallery's own picker
 * ([xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicturePicker]) picks several at once and
 * copies straight into [xyz.oleolegka.gachimuchi.data.GalleryStore]; this picks one and hands
 * the raw `uri` back, because what happens to the bytes next differs by caller (a NEW
 * exercise picture replaces an old one and has to know its id to delete it).
 */

/** The camera-capture cache folder — must match `res/xml/file_paths.xml`'s `camera` entry. */
private const val CAMERA_CACHE_DIR = "camera"

/** The FileProvider authority declared in `AndroidManifest.xml` (`${applicationId}.files`). */
private const val FILE_PROVIDER_SUFFIX = ".files"

/**
 * The system photo picker, single image — see
 * [xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicturePicker] for why this needs no
 * permission. Returns a lambda that opens it; [onResult] fires with the picked `uri`, or is not
 * called at all if the picker was cancelled.
 */
@Composable
fun rememberSinglePicturePicker(onResult: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onResult(uri)
    }
    val request = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }
    return { launcher.launch(request) }
}

/**
 * The system camera app, photographing straight into a file this app owns.
 *
 * ── Why this needs no CAMERA permission ─────────────────────────────────────────
 * This never touches the Camera2 API: `ActivityResultContracts.TakePicture` launches
 * `MediaStore.ACTION_IMAGE_CAPTURE`, which hands the whole job — viewfinder, shutter, the
 * permission that comes with owning it — to whatever camera app is installed, the same way
 * "share" hands a file to whatever messenger is installed. Nothing here is a camera; it is an
 * app asking one to take one picture and hand back a file.
 *
 * ── Why the destination is a FileProvider uri and not a bare file:// path ─────────
 * A `file://` uri has been illegal to hand to another app since Android 7 (the same rule
 * `data/ProgramFiles.kt`'s `share` is written around); a `content://` one from THIS app's own
 * provider is what grants the camera app write access to one file in `cache/camera` for the
 * duration of the capture and nothing else. The temp file is deleted once the copy into
 * [xyz.oleolegka.gachimuchi.data.ExercisePictureStore] has happened, successful or not — there
 * is no reason to keep two copies of a picture already copied into permanent storage.
 *
 * Returns a lambda that opens the camera; [onResult] fires with the captured file's own uri
 * once a photo comes back, or is not called at all if the capture was cancelled or failed.
 */
@Composable
fun rememberCameraCapture(onResult: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    // a State, not a bare local: the capture crosses a trip out to another app and back, which
    // recomposes this composable in between, and a bare `var` would hand the launch lambda's
    // file to a `pending` that the callback fired against a LATER composition can no longer see
    val pending = remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pending.value
        pending.value = null
        if (captured && file != null) {
            onResult(FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file))
        }
        file?.delete()
    }
    return {
        val dir = File(context.cacheDir, CAMERA_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        pending.value = file
        launcher.launch(FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file))
    }
}
