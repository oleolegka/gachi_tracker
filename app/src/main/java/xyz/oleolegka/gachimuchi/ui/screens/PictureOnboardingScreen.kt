package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.oleolegka.gachimuchi.data.GalleryStore
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicturePicker
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * First launch, once: an offer to bring some pictures in.
 *
 * It is an offer and it says so. Skipping is a button of equal weight, the app is fully
 * usable without a single picture, and the same screen's content lives on in the settings
 * tab — so this is a shortcut, not a gate. It is shown before anything else purely because
 * the celebration is invisible until the gallery has something in it, and a feature nobody
 * is told about is a feature nobody finds.
 */
@Composable
fun PictureOnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val gallery = remember(context) { GalleryStore.get(context) }
    val pictures by gallery.pictures.collectAsStateWithLifecycle()
    val colors = LocalGachiColors.current

    var note by remember { mutableStateOf<String?>(null) }
    val pick = rememberPicturePicker(gallery) { note = it.message() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Your own pictures", style = MaterialTheme.typography.headlineSmall)
            Text(
                "When a set goes into the journal, the app can flash one of your pictures " +
                    "over the screen — more of them, and for longer, when the set was a record.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "None ship with the app: you choose them from your phone with the system " +
                    "picker, and the app copies each one into its own storage. No permission " +
                    "is asked for, no folder is read, and the originals are never touched " +
                    "again. Add them now or later in Settings — either way the app works.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
                modifier = Modifier.padding(top = 8.dp),
            )

            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.warning,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (pictures.isNotEmpty()) {
                Text(
                    "${pictures.size} added.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Row(
                Modifier.padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pictures.isEmpty()) {
                    Button(onClick = pick) { Text("Choose pictures") }
                    TextButton(onClick = onDone) { Text("Skip") }
                } else {
                    Button(onClick = onDone) { Text("Done") }
                    OutlinedButton(onClick = pick) { Text("Add more") }
                }
            }
        }
    }
}
