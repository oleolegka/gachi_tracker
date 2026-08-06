package xyz.oleolegka.gachimuchi.ui.celebrate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import xyz.oleolegka.gachimuchi.data.GalleryStore
import xyz.oleolegka.gachimuchi.domain.CelebrationCue
import xyz.oleolegka.gachimuchi.domain.shouldCelebrate
import java.io.File

/**
 * Wraps the app and flashes a picture up when a set is written down.
 *
 * ── Why it wraps everything instead of living on a screen ───────────────────────
 * Sets are logged on the logging screen, but the celebration belongs to no screen: it has
 * to appear over whatever is in front at the time, and it must not be part of a screen
 * that another change might reshape. So it sits above the whole app, reads the cues the
 * ViewModel emits, and touches nothing else.
 *
 * ── Why it does not take the touch ──────────────────────────────────────────────
 * There is no scrim, no dialog and nothing clickable in the overlay. A picture that has to
 * be dismissed is a picture that interrupts a workout — this one draws itself in a corner,
 * lets every tap through to the screen underneath (Compose only delivers a pointer event
 * to something that asked for one), and takes itself away after a second and a half. The
 * user can keep logging straight through it and never once aim around it.
 */

/** How long a picture stays. A record earns the longer one; both are short on purpose. */
private const val SHOW_SET_MS = 1500L
private const val SHOW_RECORD_MS = 2400L

/** Enough for the overlay at its largest; a full-size decode of a phone photo is not. */
private const val DECODE_MAX_PX = 720

@Composable
fun CelebrationHost(cues: Flow<CelebrationCue>, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val gallery = remember(context) { GalleryStore.get(context) }

    // `card` outlives `visible` so the picture is still there to animate away with
    var card by remember { mutableStateOf<Shown?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(cues, gallery) {
        cues.collect { cue ->
            // both questions are asked at the moment of the set, not in advance: the mode
            // and the gallery can have changed since the last one
            if (!shouldCelebrate(gallery.mode.value, cue.isRecord)) return@collect
            val picture = gallery.pick(cue.isRecord) ?: return@collect // empty gallery: silence
            card = Shown(cue, gallery.fileOf(picture))
            visible = true
        }
    }

    // keyed on the serial, so a second celebration restarts the clock instead of
    // inheriting the leftover of the first
    LaunchedEffect(card?.cue?.serial) {
        val current = card ?: return@LaunchedEffect
        delay(if (current.cue.isRecord) SHOW_RECORD_MS else SHOW_SET_MS)
        if (card?.cue?.serial == current.cue.serial) visible = false
    }

    Box(Modifier.fillMaxSize()) {
        content()

        val bitmap = rememberPicture(card?.file, DECODE_MAX_PX)
        AnimatedVisibility(
            visible = visible && bitmap != null,
            enter = fadeIn(tween(160)) +
                scaleIn(tween(240), initialScale = 0.82f) +
                slideInVertically(tween(240)) { -it / 5 },
            exit = fadeOut(tween(320)) + scaleOut(tween(320), targetScale = 0.92f),
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null, // decoration: a screen reader has the record line already
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(132.dp).clip(RoundedCornerShape(16.dp)),
                    )
                }
                card?.cue?.text?.let { text ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = 3.dp,
                        modifier = Modifier.padding(top = 6.dp).widthIn(max = 168.dp),
                    ) {
                        Text(
                            text,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The picture currently on screen (or on its way off it) and the cue that brought it. */
private data class Shown(val cue: CelebrationCue, val file: File)
