package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.oleolegka.gachimuchi.BuildConfig
import xyz.oleolegka.gachimuchi.data.DeviceStore
import xyz.oleolegka.gachimuchi.data.GalleryStore
import xyz.oleolegka.gachimuchi.domain.CelebrationMode
import xyz.oleolegka.gachimuchi.domain.CelebrationPicture
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicture
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicturePicker
import xyz.oleolegka.gachimuchi.ui.components.rememberJournalTransfer
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * The settings tab: the celebration pictures, the backup, and at the foot of it the two
 * things that identify this copy of the app — which installation it is and which build it is.
 *
 * The backup is here rather than on a screen of its own because it is read once, decided
 * once, and then done from memory; and because "this app keeps no other copy of your journal"
 * belongs next to the id and the version, which is where somebody looks when they are working
 * out what this installation actually is.
 *
 * It exists as a tab of its own rather than as a section of another screen because the
 * settings that are still to come (and the timer's own, which live on the programs tab next
 * to the thing they configure) need somewhere to land.
 *
 * The timer's settings deliberately stay where they are. They are read while looking at a
 * countdown; these are read once and then rarely again.
 *
 * There was a "Demo data" section here, writing and removing about ninety days of invented
 * training. It is gone: it existed so that no screen would ever be seen empty, it once wrote
 * synthetic sets into the user's own exercises, and it cost a column in three tables plus a
 * removal routine full of rules about what a delete is allowed to touch — all of that inside
 * the one app whose only claim is that its journal is true. An empty screen that says what
 * to do is a better first impression than a full one that is a lie.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val gallery = remember(context) { GalleryStore.get(context) }
    val deviceId = remember(context) { DeviceStore(context).deviceId }
    val pictures by gallery.pictures.collectAsStateWithLifecycle()
    val mode by gallery.mode.collectAsStateWithLifecycle()
    val colors = LocalGachiColors.current

    var note by remember { mutableStateOf<String?>(null) }
    val pick = rememberPicturePicker(gallery) { note = it.message() }
    val journal = rememberJournalTransfer()

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
                "Backup",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = journal.export) { Text("Export the journal") }
                OutlinedButton(onClick = journal.restore) { Text("Restore") }
            }
        }

        item {
            /*
             * Said plainly because the situation is: this app has no other copy of anything.
             * The journal is in one database on one phone, the phone it is built for has no
             * Google backup, and adb backup has not taken app data since this target SDK. A
             * user who does not know that has no reason to press the button.
             */
            Text(
                "The journal, the exercises, the plan, the programs and these settings, as one " +
                    "JSON file. Nothing else keeps a copy of them - not the phone, not a cloud - " +
                    "so this file is the only thing standing between a lost phone and a lost " +
                    "history. Keep it somewhere that is not this phone. The celebration pictures " +
                    "are not in it. Restoring merges a file into what is here and can be done " +
                    "twice without doubling anything.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        item {
            Text(
                "This device",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            /*
             * Shown rather than kept hidden because it is the thing an exported journal will
             * be stamped with, and the only way to tell two of this app's files apart once
             * there are two phones. There is nothing to do with it here on purpose: it is not
             * editable (an id that can be changed is not an id) and it is not a setting.
             */
            Text(
                deviceId,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }

        item {
            Text(
                "Names this installation, and nothing outside it. It is not tied to the phone " +
                    "and does not survive the app being removed.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        item {
            Text(
                "This build",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            /*
             * The version, shown because there is nowhere else to read it. The app is not on
             * a store; updates arrive through Obtainium, which installs whatever the latest
             * release is without saying afterwards what that was. Both numbers are here
             * because they answer different questions: the NAME is what a release is called
             * and what a bug report should quote, the CODE is what the updater compares to
             * decide whether there is anything to install.
             */
            Text(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * One celebration mode as a row: the button, the name, and what choosing it means.
 *
 * ── Selectable, not merely clickable ────────────────────────────────────────────
 * The whole row is the target and the `RadioButton` inside it takes no click of its own, so
 * the row has to say what the button would have said. `Modifier.selectable` with
 * [Role.RadioButton] publishes BOTH the role and the chosen state into semantics; a plain
 * `clickable` publishes neither, and then which mode is on is only painted. A screen reader
 * reading these rows would announce three identical buttons and never say which one is
 * already picked — and a test could not tell either, which is how it stayed that way.
 */
@Composable
private fun ModeRow(selected: Boolean, title: String, hint: String, onSelect: () -> Unit) {
    val colors = LocalGachiColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
