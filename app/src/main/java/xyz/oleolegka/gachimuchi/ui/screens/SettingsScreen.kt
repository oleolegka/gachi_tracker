package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.oleolegka.gachimuchi.data.GalleryStore
import xyz.oleolegka.gachimuchi.data.seed.demoInventory
import xyz.oleolegka.gachimuchi.data.seed.keptExercisesNote
import xyz.oleolegka.gachimuchi.domain.CelebrationMode
import xyz.oleolegka.gachimuchi.domain.CelebrationPicture
import xyz.oleolegka.gachimuchi.ui.DemoPrompt
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicture
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicturePicker
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * The settings tab: the celebration pictures, and the demo data.
 *
 * It exists as a tab of its own rather than as a section of another screen because the
 * settings that are still to come (and the timer's own, which live on the timer screen
 * next to the thing they configure) need somewhere to land.
 *
 * The timer's settings deliberately stay where they are. They are read while looking at a
 * countdown; these are read once and then rarely again.
 *
 * ── Why the demo data is HERE and behind two taps ───────────────────────────────
 * It was a bare button on the Today screen and it ran on first launch. Both were wrong for
 * the same reason: writing ninety days of invented sets into a real journal is a
 * destructive act, and so is deleting them again, and neither belongs one tap from the
 * screen used during a workout. Settings is where a thing is done deliberately, and both
 * directions state what they are about to do — with counts, not reassurances — before they
 * do it.
 */
@Composable
fun SettingsScreen(
    demo: DemoActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val gallery = remember(context) { GalleryStore.get(context) }
    val pictures by gallery.pictures.collectAsStateWithLifecycle()
    val mode by gallery.mode.collectAsStateWithLifecycle()
    val colors = LocalGachiColors.current

    var note by remember { mutableStateOf<String?>(null) }
    val pick = rememberPicturePicker(gallery) { note = it.message() }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        // the same 8/24 the other tabs use — see TimerScreen
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        item {
            Text(
                "Celebration",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    ModeRow(
                        selected = mode == CelebrationMode.EVERY_SET,
                        title = "On every set",
                        hint = "A picture each time a set is written down.",
                        onSelect = { gallery.setMode(CelebrationMode.EVERY_SET) },
                    )
                    ModeRow(
                        selected = mode == CelebrationMode.RECORDS_ONLY,
                        title = "On records only",
                        hint = "Only when the set beat everything before it.",
                        onSelect = { gallery.setMode(CelebrationMode.RECORDS_ONLY) },
                    )
                    ModeRow(
                        selected = mode == CelebrationMode.OFF,
                        title = "Off",
                        hint = "The pictures stay, nothing is shown.",
                        onSelect = { gallery.setMode(CelebrationMode.OFF) },
                    )
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = pick) { Text("Add pictures") }
                Text(
                    if (pictures.isEmpty()) "none yet" else "${pictures.size} in the gallery",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }
        }

        note?.let { text ->
            item {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.warning)
            }
        }

        item {
            Text(
                if (pictures.isEmpty()) {
                    "No pictures yet. Pictures are chosen with the system picker, and the app " +
                        "keeps its own copy of each one — moving or deleting the original later " +
                        "changes nothing here. Until there is at least one, nothing is ever shown."
                } else {
                    "Starred pictures are saved for records: an ordinary set draws from the " +
                        "rest. Star none and every set draws from all of them."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        items(pictures, key = { it.id }) { picture ->
            PictureRow(
                picture = picture,
                gallery = gallery,
                onDelete = { gallery.remove(picture.id) },
                onToggleRecord = { gallery.setForRecords(picture.id, !picture.forRecords) },
            )
        }

        item {
            Text(
                "Demo data",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            Text(
                "Ninety days of made-up training, so the charts and the records have " +
                    "something to draw. It is not yours and it never was: it goes into the " +
                    "same journal as your own records, marked, and can be taken back out " +
                    "from here.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = demo.askWrite,
                    enabled = !demo.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Add demo data") }
                OutlinedButton(
                    onClick = demo.askRemove,
                    enabled = !demo.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Remove demo data") }
            }
        }

        demo.note?.let { text ->
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = demo.dismissNote) { Text("OK") }
                }
            }
        }
    }

    demo.prompt?.let { prompt ->
        DemoConfirmDialog(prompt = prompt, onConfirm = demo.confirm, onDismiss = demo.dismissPrompt)
    }
}

/** What the settings screen needs in order to offer the demo data, and nothing more. */
data class DemoActions(
    val prompt: DemoPrompt?,
    val note: String?,
    val busy: Boolean,
    val askWrite: () -> Unit,
    val askRemove: () -> Unit,
    val confirm: () -> Unit,
    val dismissPrompt: () -> Unit,
    val dismissNote: () -> Unit,
)

/**
 * The question asked before either direction runs.
 *
 * The removal branch describes the ACTUAL PLAN — the counts were worked out from the
 * database before this dialog appeared, and the same plan is what runs when it is confirmed.
 * A confirmation that promises "only demo data" while the code decides afterwards what that
 * means is not a confirmation, it is a formality; this one can be checked against what the
 * screens look like a moment later.
 */
@Composable
private fun DemoConfirmDialog(prompt: DemoPrompt, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalGachiColors.current
    val plan = (prompt as? DemoPrompt.Remove)?.plan
    val nothingToDo = plan != null && plan.isEmpty

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    prompt is DemoPrompt.Write -> "Add demo data?"
                    nothingToDo -> "No demo data found"
                    else -> "Remove demo data?"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    prompt is DemoPrompt.Write -> {
                        Text(
                            "This writes about ninety days of invented training into your " +
                                "journal, along with the exercises and the planned sessions " +
                                "it needs.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Your own records are not touched, and \"Remove demo data\" " +
                                "takes all of it back out again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkMuted,
                        )
                    }

                    nothingToDo -> Text(
                        "Nothing on this phone looks like demo data, so there is nothing to " +
                            "remove.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> {
                        Text(
                            "This removes ${demoInventory(plan!!)}.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Records you entered yourself are not touched.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkMuted,
                        )
                        keptExercisesNote(plan).takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (nothingToDo) {
                TextButton(onClick = onDismiss) { Text("Close") }
            } else {
                TextButton(onClick = onConfirm) {
                    Text(if (prompt is DemoPrompt.Write) "Add it" else "Remove it")
                }
            }
        },
        dismissButton = {
            if (!nothingToDo) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ModeRow(selected: Boolean, title: String, hint: String, onSelect: () -> Unit) {
    val colors = LocalGachiColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the whole row is the target; the button itself takes no click of its own
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
    }
}

@Composable
private fun PictureRow(
    picture: CelebrationPicture,
    gallery: GalleryStore,
    onDelete: () -> Unit,
    onToggleRecord: () -> Unit,
) {
    val colors = LocalGachiColors.current
    // small: this is a 56 dp thumbnail, and decoding a phone photo at full size for it
    // would cost about as much memory as the rest of the app put together
    val thumbnail = rememberPicture(gallery.fileOf(picture), maxPx = 160)

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp),
                    )
                } else {
                    // the copy is there but will not decode: say so instead of an empty box
                    Text("?", style = MaterialTheme.typography.titleMedium, color = colors.inkMuted)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    if (picture.forRecords) "For records" else "For any set",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (picture.addedAt.isNotEmpty()) {
                    Text(
                        "added ${picture.addedAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
            }
            IconButton(onClick = onToggleRecord) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (picture.forRecords) "Use for any set" else "Save for records",
                    tint = if (picture.forRecords) colors.accent else colors.inkMuted,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove this picture")
            }
        }
    }
}
