package xyz.oleolegka.gachimuchi.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ExerciseEdit
import xyz.oleolegka.gachimuchi.data.ExercisePictureOutcome
import xyz.oleolegka.gachimuchi.data.ExercisePictureStore
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.firstBlock
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicture
import xyz.oleolegka.gachimuchi.ui.components.EyebrowStyle
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.dashedBorder
import xyz.oleolegka.gachimuchi.ui.components.rememberCameraCapture
import xyz.oleolegka.gachimuchi.ui.components.rememberSinglePicturePicker
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing

/**
 * Correcting a catalog exercise: the name, the picture, whether it is trained one side at a
 * time — and, stated rather than offered, the two things about it that cannot move.
 *
 * ── Why this is a SCREEN and was a dialog ──────────────────────────────────────
 * A dialog is a short question with two answers. This has a text field, a 96dp picture with
 * three buttons under it, a switch and four facts — and it had NO SCROLL, so on a phone the
 * bottom of it was simply not drawn and there was no way to reach it. As a screen the
 * scroll is free, "Save" and the way out sit in a top bar that is always visible, and the
 * keyboard no longer covers the thing being edited.
 *
 * The one behaviour that had to be restated in moving: the dialog "stayed open" when a save
 * was refused. A screen has nothing to stay open, so what it does instead is NOT LEAVE — the
 * same rule from the other side. See [onClose], which is called on a successful write and at
 * no other time.
 *
 * ── Why it writes through a repository of its own ──────────────────────────────
 * Every other action in this app arrives as a callback assembled in `ui/GachiApp.kt`, and
 * this screen instead opens the process-wide database itself — the same singleton the
 * ViewModel's repository is built on, so a write here reaches the catalog flow the screens
 * observe and the corrected name appears everywhere without anything being told to refresh.
 * That is a deviation, inherited from the dialog this replaces, and is written down as one:
 * when `GachiApp` is next open for editing, these should become callbacks like everything
 * else. The precedent it follows is the settings tab, which reaches for its stores the same
 * way.
 *
 * ── "How much of you it lifts" is gone ─────────────────────────────────────────
 * A percent field wrote `bodyweight_share` here and nothing else in the app ever asked for
 * it. Owner, 2026-08-11: "I never asked for it, I do not want it anywhere". The column and
 * every reader of it stay exactly as they were (dropping a column is a schema migration for
 * no gain) — it simply has no writer any more and stays empty, so a set logged at your own
 * body weight is worth no tonnage. That consequence was accepted out loud: "never mind the
 * tonnage, let it be a bit wrong".
 *
 * ── The form and the protocol are shown and cannot be changed ──────────────────
 * The form decides the shape of the payload every set of this exercise was written in, and
 * changing it would leave a history in one shape being read by screens expecting another.
 * The protocol is the owner's own rule: "such a thing cannot happen: it breaks the
 * statistics. If yesterday it was one protocol and today another, that is a NEW exercise" —
 * the row's `identity_key` includes it, so silently repointing it moved a running exercise
 * onto a different protocol under the SAME identity, with every set already logged staying
 * put underneath. Both used to carry a paragraph of their own, in the same 11sp as
 * everything else on the dialog; they are one card headed with a padlock now, and the two
 * paragraphs are one sentence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditExerciseScreen(
    exercise: ExerciseEntity,
    /** The resolved protocol program `exercise.protocolProgramId` names, or null for none. */
    program: WorkoutProgram?,
    /** Left on a refusal, called on a successful write. See the class KDoc. */
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { ActivityRepository(AppDatabase.get(context)) }
    val pictureStore = remember(context) { ExercisePictureStore.get(context) }

    val hold = exercise.form == ExerciseForm.HOLD.code
    val lifted = hold || exercise.form == ExerciseForm.STRENGTH.code
    val protocolBlock = program?.firstBlock()

    var name by remember(exercise.id) { mutableStateOf(exercise.name) }
    var oneSided by remember(exercise.id) { mutableStateOf(exercise.oneSided) }
    /*
     * The picture is held here rather than read off [exercise] on every frame because it is
     * written IMMEDIATELY, outside Save (the same way a picture added to the celebration
     * gallery is), and the preview has to follow the write without waiting for the catalog
     * flow to come round again.
     */
    var pictureId by remember(exercise.id) { mutableStateOf(exercise.pictureId) }
    var message by remember(exercise.id) { mutableStateOf<Refusal?>(null) }

    /*
     * IMMEDIATE, unlike the name and the side flag: there is no "Save" to gate a picture on,
     * the same way adding one to the celebration gallery is immediate rather than part of a
     * form. The preview follows the write straight away, from [pictureId] above.
     */
    val onPickPicture: (Uri) -> Unit = { uri ->
        scope.launch {
            when (val outcome = pictureStore.add(uri)) {
                is ExercisePictureOutcome.Added -> {
                    val previous = pictureId
                    repo.setPicture(exercise.id, outcome.pictureId)
                    previous?.let { pictureStore.remove(it) }
                    pictureId = outcome.pictureId
                }

                ExercisePictureOutcome.TooBig -> message = Refusal(
                    "Picture not attached",
                    "That one is over 16 MB. Pick a smaller one.",
                )

                ExercisePictureOutcome.Unreadable -> message = Refusal(
                    "Picture not attached",
                    "That picture could not be read.",
                )
            }
        }
    }
    val takePhoto = rememberCameraCapture(onPickPicture)
    val pickFromGallery = rememberSinglePicturePicker(onPickPicture)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Edit exercise", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    /*
                     * SAVE LIVES HERE, which is the whole of what moving off a dialog cost:
                     * a dialog gets its buttons for free at the bottom, a screen has to put
                     * them somewhere always visible, and the top bar is that place.
                     */
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            scope.launch {
                                val result = repo.editExercise(exercise.id, name.trim())
                                /*
                                 * THE SWITCH IS SAVED WHATEVER THE NAME DID, and that is the
                                 * whole of this block.
                                 *
                                 * The flag is its own column and its own write: correcting a
                                 * name and declaring the exercise one-handed are different
                                 * claims, and neither may rewrite the other. It used to be
                                 * written only when the name edit took, on the grounds that a
                                 * refusal should leave the whole screen untouched — and that
                                 * turned the refusal into a silent loss: the user flipped the
                                 * switch, was told the NAME was taken, gave up on the rename
                                 * and left, and the switch was back where it started. The
                                 * refusal is about identity (name plus protocol); which limb
                                 * the exercise is trained with is not part of it, so it has no
                                 * business being refused along with it. Keeping the screen
                                 * open only postpones the loss — it is still lost the moment
                                 * the rename is abandoned, which is the case being reported.
                                 *
                                 * [ExerciseEdit.Gone] is the one outcome that stops it: the
                                 * row is not in the catalog any more, so there is nothing left
                                 * to set a column on.
                                 *
                                 * The refusal then says so out loud — a dialog that talked
                                 * about the name while quietly writing a column would be the
                                 * same silence from the other side.
                                 */
                                val sideSaved = oneSided != exercise.oneSided &&
                                    result !is ExerciseEdit.Gone
                                if (sideSaved) repo.setOneSided(exercise.id, oneSided)
                                if (result is ExerciseEdit.Saved) onClose()
                                val alsoSide = if (sideSaved) " $SIDE_SAVED_ANYWAY" else ""
                                message = when (result) {
                                    is ExerciseEdit.Saved -> null
                                    is ExerciseEdit.Blank -> Refusal(
                                        "Name not saved",
                                        "An exercise needs a name.$alsoSide",
                                    )
                                    is ExerciseEdit.Gone -> Refusal(
                                        "Not saved",
                                        "That exercise is no longer in the catalog.",
                                    )
                                    is ExerciseEdit.Taken -> Refusal(
                                        "Name is taken",
                                        "\"${result.name}\" already exists at the same " +
                                            "protocol, and that pair is what an exercise is. " +
                                            "Pick another name, or correct that one instead." +
                                            alsoSide,
                                    )
                                }
                            }
                        },
                    ) { Text("Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.Block),
            verticalArrangement = Arrangement.spacedBy(Spacing.Cards),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Name") },
            )

            /*
             * The owner's own reason for the whole feature: "on different machines the same
             * weight feels very different" — a picture is here so that a glance at it, next
             * time, says which rack or which pulldown this row means.
             */
            GachiCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(Spacing.Inset),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Inset),
                ) {
                    CardLabel("Picture")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Inset),
                    ) {
                        val picture = rememberPicture(
                            pictureId?.let { pictureStore.fileOf(it) },
                            EDIT_PICTURE_MAX_PX,
                        )
                        if (picture != null) {
                            Image(
                                bitmap = picture,
                                // decoration: the Name field above already names this exercise
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(PICTURE_SIDE)
                                    .clip(RoundedCornerShape(Radius.Small)),
                            )
                        } else {
                            /*
                             * A dashed plate rather than nothing. The label "Picture" used to
                             * head a gap: the buttons beside it were the only clue that a
                             * picture was even possible, and nothing said where one would go.
                             */
                            Box(
                                modifier = Modifier
                                    .size(PICTURE_SIDE)
                                    .dashedBorder(colors.axis, Radius.Small),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "no picture",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.inkMuted,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = takePhoto) { Text("Camera") }
                            OutlinedButton(onClick = pickFromGallery) { Text("Gallery") }
                            if (pictureId != null) {
                                /*
                                 * PUSHED AWAY FROM THE OTHER TWO (SYSTEM.md rule 3): removing
                                 * the picture is not undoable and it used to sit 8dp from the
                                 * two buttons pressed all the time. It is also plain rather
                                 * than outlined, so it does not read as a third way to attach
                                 * one.
                                 */
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                    TextButton(
                                        onClick = {
                                            pictureId?.let { previous ->
                                                scope.launch {
                                                    repo.setPicture(exercise.id, null)
                                                    pictureStore.remove(previous)
                                                    pictureId = null
                                                }
                                            }
                                        },
                                    ) {
                                        Text("Remove", color = colors.inkSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (lifted) {
                /*
                 * The one control here that is not a correction of a typo.
                 *
                 * It is what splits the record per side, and until it existed anywhere in the
                 * UI the whole per-side feature was unreachable: the entry form asks for a
                 * side only when the exercise claims to need one, and nothing could make it
                 * claim that. Found from the phone, 2026-08-08.
                 *
                 * GATED ON [lifted], not on [hold]: a hangboard hang was never the only thing
                 * trained one limb at a time — a pistol squat and a one-arm row are the same
                 * asymmetry on a `StrengthSet` — and the mechanism underneath never cared
                 * which form the exercise was. Only this control did, and that was the gap.
                 *
                 * A SWITCH and not a filter chip: a chip says "narrow a list down by this",
                 * and this is a property of the exercise being turned on. The switch also
                 * leaves room on its row for the sentence that used to be a paragraph below.
                 */
                GachiCard(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.Inset),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Inset),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        ) {
                            Text(
                                "One side at a time",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Each side keeps its own record.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                            )
                        }
                        Switch(checked = oneSided, onCheckedChange = { oneSided = it })
                    }
                }
            }

            GachiCard(Modifier.fillMaxWidth(), background = colors.recessed) {
                Column(
                    Modifier.padding(Spacing.Inset),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Inset),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null, // the word "Fixed" beside it says this
                            tint = colors.inkMuted,
                            modifier = Modifier.size(14.dp),
                        )
                        CardLabel("Fixed")
                    }
                    if (hold) {
                        FixedFact(
                            label = "Protocol",
                            value = if (protocolBlock != null) {
                                "${protocolBlock.workSec} : ${protocolBlock.restSec}"
                            } else {
                                "None"
                            },
                            note = "work : rest, seconds",
                        )
                    }
                    FixedFact(
                        label = "Form",
                        value = ExerciseForm.fromCodeOrTick(exercise.form).title.lowercase(),
                        dot = colors.forForm(ExerciseForm.fromCodeOrTick(exercise.form)),
                    )
                    Text(
                        if (hold) {
                            "A different protocol or form is a different exercise - make a new one."
                        } else {
                            "A different form is a different exercise - make a new one."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
            }
        }
    }

    /*
     * The refusal, which IS a dialog: one sentence of what happened, one of what to do about
     * it. It used to be four lines about a history being split in half and no next step at
     * all, under a title ("Not saved") that talked about the whole form when what had not
     * been saved was exactly the name and the side flag.
     */
    message?.let { refusal ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(refusal.title) },
            text = { Text(refusal.text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

/** What a refusal says: the thing that happened, and what to do about it. */
private data class Refusal(val title: String, val text: String)

/**
 * The line a refusal adds when the side switch was moved and the name was not saved.
 *
 * Named after the switch's own label so the sentence points at a control on the screen behind
 * the dialog rather than at "a setting". Only ever added when the write actually happened —
 * see the Save handler for why the two are decided together.
 */
private const val SIDE_SAVED_ANYWAY =
    "\"One side at a time\" was saved anyway - it is not part of the name."

/** The eyebrow of a card on this screen: the label of a block, not a heading of its own. */
@Composable
private fun CardLabel(text: String) {
    Text(
        text.uppercase(),
        style = EyebrowStyle,
        color = LocalGachiColors.current.inkMuted,
    )
}

/**
 * One fact that cannot be edited: what it is, what it says, and — quietly — its unit.
 *
 * Three sizes for three roles, which is the point: the label is 13 and secondary, the value
 * is 17 and the heaviest thing in the card, the unit is the 11sp floor. On the dialog this
 * replaces, the label of a block and the paragraph explaining it were both 11sp, so nothing
 * about the type said which was which.
 */
@Composable
private fun FixedFact(
    label: String,
    value: String,
    note: String? = null,
    dot: Color? = null,
) {
    val colors = LocalGachiColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(FIXED_LABEL_WIDTH),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
        )
        if (dot != null) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(dot)
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
        }
    }
}

/** The form of a row, degrading to a check-in rather than throwing — see `ExerciseEntity.toRef`. */
private fun ExerciseForm.Companion.fromCodeOrTick(code: Int): ExerciseForm =
    runCatching { fromCode(code) }.getOrDefault(ExerciseForm.TICK)

/** How large a decode this screen's preview asks for — bigger than the picker's row thumbnail. */
private const val EDIT_PICTURE_MAX_PX = 240

/** The side of the picture, and of the plate that stands in for it when there is none. */
private val PICTURE_SIDE = 96.dp

/** How wide the label column of a fixed fact is, so the two values line up under each other. */
private val FIXED_LABEL_WIDTH = 88.dp
