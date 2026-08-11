package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.oleolegka.gachimuchi.BuildConfig
import xyz.oleolegka.gachimuchi.data.DeviceStore
import xyz.oleolegka.gachimuchi.data.GalleryStore
import xyz.oleolegka.gachimuchi.domain.CelebrationMode
import xyz.oleolegka.gachimuchi.domain.CelebrationPicture
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicture
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicturePicker
import xyz.oleolegka.gachimuchi.ui.components.ActionMenu
import xyz.oleolegka.gachimuchi.ui.components.ConfirmRemoveDialog
import xyz.oleolegka.gachimuchi.ui.components.EyebrowStyle
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.ItemAction
import xyz.oleolegka.gachimuchi.ui.components.WarningNotice
import xyz.oleolegka.gachimuchi.ui.components.rememberJournalTransfer
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

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

    Column(modifier.fillMaxWidth()) {
        /*
         * THE TITLE IS A BAR, not the first row of the list.
         *
         * It used to scroll away with everything else, so a reader who had got as far as the
         * build number was looking at a screen with no name on it. It is the one thing here
         * that is about the screen rather than about a setting, and it stays put.
         */
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = Spacing.Block, vertical = Spacing.Inset)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider(color = colors.border)

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = Spacing.Block, end = Spacing.Block,
                top = Spacing.Block, bottom = Spacing.Cards,
            ),
            // 24 between sections, which is what a gap between CARDS means on this scale. The
            // screen used to be a flat list of Texts and Rows 8 dp apart, with the four sections
            // told apart only by a hand-typed top margin on whichever element happened to be
            // first (8 / 16 / 16 / 16) - grouping by accident, and grouping the wrong things:
            // the only real cards on it were the picture rows.
            verticalArrangement = Arrangement.spacedBy(Spacing.Cards),
        ) {
            item {
                // the count belongs to the section it counts, not beside the button that adds
                // to it - "none yet" next to "Add pictures" read as a caption for the button
                SettingsBlock(sectionTitle("Celebration", pictures.size, "picture")) {
                    CelebrationModes(mode, gallery)
                }
            }

            item {
                SettingsBlock("Pictures") {
                    if (pictures.isEmpty()) {
                        /*
                         * Rule 6: an empty state names what is empty and what follows from it.
                         * The three sentences that used to be here explained the system picker
                         * and the app's private copy - true, and not what somebody staring at
                         * an empty gallery is asking.
                         */
                        Column(Modifier.padding(Spacing.Inset)) {
                            Text(
                                "No pictures yet",
                                fontSize = TextSize.Body,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Until there is at least one, nothing is ever shown.",
                                fontSize = TextSize.Meta,
                                color = colors.inkSecondary,
                                modifier = Modifier.padding(top = Spacing.Tight),
                            )
                        }
                    } else {
                        pictures.forEachIndexed { index, picture ->
                            if (index > 0) HorizontalDivider(color = colors.grid)
                            PictureRow(
                                picture = picture,
                                gallery = gallery,
                                onDelete = { gallery.remove(picture.id) },
                                onToggleRecord = {
                                    gallery.setForRecords(picture.id, !picture.forRecords)
                                },
                            )
                        }
                    }
                }
            }

            note?.let { text ->
                // A filled notice and not `warning`-coloured type: on this plane that colour
                // measures 1.74:1, and this is the line that says the pictures did not arrive.
                item { WarningNotice(text) }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
                    OutlinedButton(
                        onClick = pick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = TapTarget),
                    ) {
                        Text("Add pictures")
                    }
                    Text(
                        // one phrase (rule 5). The degenerate case - no star anywhere - is
                        // stated where it actually applies, below.
                        "Starred are used for records, the rest for ordinary sets.",
                        fontSize = TextSize.Meta,
                        color = colors.inkSecondary,
                    )
                    if (pictures.isNotEmpty() && pictures.none { it.forRecords }) {
                        Text(
                            "None are starred, so every set draws from all of them.",
                            fontSize = TextSize.Meta,
                            color = colors.inkMuted,
                        )
                    }
                }
            }

            item {
                SettingsBlock("Backup") { BackupFacts() }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
                    /*
                     * Rule 3 and rule 7 together. These were two identical outlined buttons
                     * eight dp apart: the frequent harmless one and the rare one that appends
                     * somebody else's file to the journal. Now they differ in weight and stand
                     * on separate rows, and the consequence of the rare one is written under it.
                     */
                    Button(
                        onClick = journal.export,
                        modifier = Modifier.fillMaxWidth().heightIn(min = TapTarget),
                    ) {
                        Text("Export the journal")
                    }
                    OutlinedButton(
                        onClick = journal.restore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = TapTarget),
                    ) {
                        Text("Restore from a file")
                    }
                    Text(
                        "Merges a file into what is here; doing it twice doubles nothing.",
                        fontSize = TextSize.Meta,
                        color = colors.inkSecondary,
                    )
                }
            }

            item {
                SettingsBlock("This installation") {
                    /*
                     * A LABEL FIRST, then the value. Both of these used to be a monospaced
                     * line with a paragraph underneath explaining what had just been read -
                     * the reader met the value before being told whose it was.
                     */
                    IdentityRow(
                        label = "Installation id",
                        value = deviceId,
                        hint = "Stamped on an exported journal. Not tied to the phone, and " +
                            "gone if the app is removed.",
                    )
                    HorizontalDivider(color = colors.grid)
                    IdentityRow(
                        label = "Version",
                        value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        hint = "The name goes in a bug report; the code is what the updater " +
                            "compares.",
                    )
                }
            }
        }
    }
}

/** The floor for anything a finger has to hit. */
private val TapTarget = 48.dp

/** "Celebration · 3 pictures" — a section that has a count says it in its own title. */
private fun sectionTitle(name: String, count: Int, noun: String): String = when (count) {
    0 -> name
    1 -> "$name · 1 $noun"
    else -> "$name · $count ${noun}s"
}

/**
 * A section: its label, and its card.
 *
 * The label is the eyebrow of the rest of the app ([SectionHeader]'s type, 11 sp, tracked
 * caps) so that "what section am I in" is answered the same way here as on the overview.
 */
@Composable
private fun SettingsBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalGachiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
        Text(title.uppercase(), style = EyebrowStyle, color = colors.inkMuted)
        GachiCard(Modifier.fillMaxWidth(), content = content)
    }
}

/** The three celebration modes, one card, hairlines between them. */
@Composable
private fun CelebrationModes(mode: CelebrationMode, gallery: GalleryStore) {
    val colors = LocalGachiColors.current
    ModeRow(
        selected = mode == CelebrationMode.EVERY_SET,
        title = "On every set",
        hint = "A picture each time a set is written down.",
        onSelect = { gallery.setMode(CelebrationMode.EVERY_SET) },
    )
    HorizontalDivider(color = colors.grid)
    ModeRow(
        selected = mode == CelebrationMode.RECORDS_ONLY,
        title = "On records only",
        hint = "Only when the set beat everything before it.",
        onSelect = { gallery.setMode(CelebrationMode.RECORDS_ONLY) },
    )
    HorizontalDivider(color = colors.grid)
    ModeRow(
        selected = mode == CelebrationMode.OFF,
        title = "Off",
        hint = "The pictures stay, nothing is shown.",
        onSelect = { gallery.setMode(CelebrationMode.OFF) },
    )
}

/**
 * What the backup is, as one sentence and four facts.
 *
 * ── Why this was the worst paragraph in the app ─────────────────────────────────
 * It was eight lines of unbroken prose in the MUTED colour, and the sentence buried in the
 * middle of it was the one that matters: nothing else keeps a copy. The most important thing
 * the screen has to say was set in the faintest type it has, in the position a reader skips.
 * Rule 5: an explanation is not a paragraph.
 *
 * Nothing was dropped. The lead is the reason the button exists; the four facts are what goes
 * in, what does not, what opens it and where to keep it. What a restore does is written under
 * the restore button, which is where the question is actually asked.
 */
@Composable
private fun BackupFacts() {
    val colors = LocalGachiColors.current
    Column(
        Modifier.padding(Spacing.Inset),
        verticalArrangement = Arrangement.spacedBy(Spacing.Inset),
    ) {
        Text(
            "This phone keeps the only copy of your journal.",
            fontSize = TextSize.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
            listOf(
                "One CSV file: the journal, the exercises, the plan, the programs and these " +
                    "settings.",
                "Celebration pictures and exercise pictures are not in it.",
                "A spreadsheet opens it; a restore reads the same file back.",
                "Keep it somewhere that is not this phone.",
            ).forEach { fact ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Line)) {
                    Box(
                        Modifier
                            .padding(top = Spacing.Line)
                            .size(Spacing.Tight)
                            .clip(RoundedCornerShape(Spacing.Tight))
                            .background(colors.axis)
                    )
                    Text(
                        fact,
                        fontSize = TextSize.Meta,
                        color = colors.inkSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** A label, the value it names, and one line saying what the value is for. */
@Composable
private fun IdentityRow(label: String, value: String, hint: String) {
    val colors = LocalGachiColors.current
    Column(
        Modifier.fillMaxWidth().padding(Spacing.Inset),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Text(
            label,
            fontSize = TextSize.Caption,
            fontWeight = FontWeight.SemiBold,
            color = colors.inkMuted,
        )
        Text(value, fontSize = TextSize.Meta, fontFamily = FontFamily.Monospace)
        Text(hint, fontSize = TextSize.Meta, color = colors.inkMuted)
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
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(Spacing.Inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = Spacing.Inset)) {
            Text(title, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
            /*
             * Meta and not the caption floor. This line is the whole content of the choice —
             * "On every set" and "On records only" are told apart by nothing else — so it is
             * an explanation being weighed, not a label on something already understood.
             */
            Text(
                hint,
                fontSize = TextSize.Meta,
                color = colors.inkSecondary,
                modifier = Modifier.padding(top = Spacing.Tight),
            )
        }
    }
}

/**
 * One picture of the gallery: the thumbnail, what it is kept for, and the two things that can
 * be done with it.
 *
 * ── The bin left the row ────────────────────────────────────────────────────────
 * It used to be an `IconButton` immediately after the star: a toggle pressed casually and,
 * eight dp away, the button that deletes the file. SYSTEM.md rule 3 — a destructive action
 * does not stand flush against a frequent one — so removal moved behind the three-dot menu
 * the rest of the app already uses for exactly this, and it now asks first.
 *
 * ── And the confirmation does not borrow the journal's promise ──────────────────
 * Every other removal in this app can say "the journal keeps the original rows". This one
 * cannot: `GalleryStore.remove` deletes the copied file off the disk, and nothing brings it
 * back. The dialog says that instead of the comforting sentence, because the comforting
 * sentence would be false here.
 */
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
    val kept = if (picture.forRecords) "For records" else "For any set"
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(Spacing.Line),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(Radius.Small))
                .background(colors.recessed),
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
                Text("?", fontSize = TextSize.Title, color = colors.inkMuted)
            }
        }
        Column(Modifier.weight(1f).padding(start = Spacing.Inset)) {
            Text(kept, fontSize = TextSize.Body)
            if (picture.addedAt.isNotEmpty()) {
                Text(
                    "added ${picture.addedAt}" + if (thumbnail == null) " · will not open" else "",
                    fontSize = TextSize.Meta,
                    color = colors.inkMuted,
                )
            }
        }
        IconButton(onClick = onToggleRecord, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (picture.forRecords) "Use for any set" else "Save for records",
                tint = if (picture.forRecords) colors.accent else colors.inkMuted,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Actions for this picture",
                    tint = colors.inkMuted,
                )
            }
            ActionMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                title = kept,
                actions = listOf(
                    ItemAction("Remove this picture", destructive = true) { confirming = true },
                ),
            )
        }
    }

    if (confirming) {
        ConfirmRemoveDialog(
            title = "Remove this picture?",
            subject = kept,
            explanation = "The app deletes its own copy of the file. Unlike everything else " +
                "here this cannot be undone - if the original is gone from the phone too, " +
                "the picture is gone.",
            onConfirm = {
                confirming = false
                onDelete()
            },
            onDismiss = { confirming = false },
        )
    }
}
