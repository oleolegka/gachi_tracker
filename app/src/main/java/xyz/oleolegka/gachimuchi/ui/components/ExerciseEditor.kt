package xyz.oleolegka.gachimuchi.ui.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ExerciseEdit
import xyz.oleolegka.gachimuchi.data.ExercisePictureOutcome
import xyz.oleolegka.gachimuchi.data.ExercisePictureStore
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.firstBlock
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicture

/**
 * Correcting a catalog exercise, taking one out of the pickers, removing one for good, or
 * adding one that is not about any workout at all.
 *
 * ── Why this exists at all ──────────────────────────────────────────────────────
 * A name typed into the entry card became a catalog row and then could never be touched
 * again: `ExerciseDao.update` existed and was called from nowhere, so a typo, or a protocol
 * entered as 7:30 instead of 7:3, was permanent. On the one screen where the
 * identity is displayed as facts about the exercise, there was no way to correct those facts.
 *
 * ── Why it writes through a repository of its own ──────────────────────────────
 * Every other action in this app arrives as a callback assembled in `ui/GachiApp.kt`, which
 * this change is not allowed to touch, and the screen that hosts this is reached from there
 * and nowhere else. So the editor opens the process-wide database itself — the same singleton
 * the ViewModel's repository is built on, so a write here reaches the catalog flow the screens
 * observe, and the corrected name appears everywhere without anything being told to refresh.
 *
 * That is a deviation and is written down as one: when `GachiApp` is next open for editing,
 * these two should become callbacks like everything else. The precedent it follows is the
 * settings tab, which reaches for its stores the same way.
 *
 * ── Why [create] does not go through `MainViewModel.createExercise` ─────────────
 * [xyz.oleolegka.gachimuchi.ui.MainViewModel.createExercise] always points the entry card at
 * the row it just made — right for every existing caller, which is either logging or planning
 * and has somewhere for the new row to go. A row added on its own has nowhere to go: the next
 * "Add" on Today would find that exercise sitting in `MainViewModel.activeExerciseId` and open
 * the entry card on it, which is exactly the workout-shaped side effect a plain catalog entry
 * must not have. [create] calls `ensureExercise` directly, the same way [edit], [toggleHidden]
 * and [delete] already reach past the ViewModel for their own writes.
 */
class ExerciseEditor internal constructor(
    /** Opens the correction dialog for this exercise. */
    val edit: (ExerciseEntity) -> Unit,
    /** Hides it from the pickers, or brings it back. */
    val toggleHidden: (ExerciseEntity) -> Unit,
    /**
     * Removes it from everywhere — the catalog and its own history — see
     * [xyz.oleolegka.gachimuchi.data.ActivityRepository.deleteExercise]. The caller is the one
     * that knows how many entries are about to go and confirms with the person before this is
     * reached; there is no confirmation in here to keep in step with a second one.
     */
    val delete: (ExerciseEntity) -> Unit,
    /**
     * Adds a catalog row and stops there — no active exercise, no navigation, nothing logged.
     * Goes through [xyz.oleolegka.gachimuchi.data.ActivityRepository.ensureExercise], the same
     * find-or-create used by every other caller, so a name that already exists is quietly
     * reused rather than duplicated.
     */
    val create: (name: String, form: ExerciseForm, workSec: Double?, restSec: Double?) -> Unit,
)

@Composable
fun rememberExerciseEditor(): ExerciseEditor {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { ActivityRepository(AppDatabase.get(context)) }
    // resolves the protocol to prefill Work/Rest with — the dialog still asks for two plain
    // numbers (see its own KDoc), and this is where those numbers come from now that the
    // catalog row itself no longer carries them
    val programRepo = remember(context) { ProgramRepository(AppDatabase.get(context)) }
    val programs by programRepo.programs.collectAsState(initial = emptyList())
    val pictureStore = remember(context) { ExercisePictureStore.get(context) }

    var editing by remember { mutableStateOf<ExerciseEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    editing?.let { exercise ->
        val program = exercise.protocolProgramId?.let { id -> programs.firstOrNull { it.id == id } }
        EditExerciseDialog(
            exercise = exercise,
            program = program,
            pictureStore = pictureStore,
            onDismiss = { editing = null },
            onSave = { name, oneSided ->
                scope.launch {
                    val result = repo.editExercise(exercise.id, name)
                    /*
                     * The flag is its own column and its own write: correcting a name and
                     * declaring the exercise one-handed are different claims, and neither may
                     * rewrite the other.
                     *
                     * It goes AFTER the identity edit and only when it took. A refused edit
                     * tells the user the exercise "was left as it was", and that sentence has
                     * to be true of the whole dialog and not only of the name.
                     *
                     * The dialog itself closes here too, and only here - not the instant Save
                     * is tapped. Closing on tap used to say "saved" before the write was even
                     * attempted, so a name refused as taken looked identical to one that went
                     * through: the dialog was already gone, the flag had silently not been
                     * written, and the "Not saved" alert that followed talked about the name
                     * alone. Staying open on every other outcome keeps the toggle exactly as
                     * typed - on screen, not yet true - until it is.
                     */
                    if (result is ExerciseEdit.Saved) {
                        if (oneSided != exercise.oneSided) repo.setOneSided(exercise.id, oneSided)
                        editing = null
                    }
                    message = when (result) {
                        is ExerciseEdit.Saved -> null
                        is ExerciseEdit.Blank -> "An exercise needs a name."
                        is ExerciseEdit.Gone -> "That exercise is no longer in the catalog."
                        is ExerciseEdit.Taken ->
                            "\"${result.name}\" already has that name and protocol, and an " +
                                "exercise is those together. Two rows claiming to be the same " +
                                "exercise would split its history in half, so this one was " +
                                "left as it was."
                    }
                }
            },
            /*
             * Immediate, unlike the name/side/share above: there is no "Save" to gate this on,
             * the same way adding a picture to the celebration gallery is immediate rather than
             * part of a form. `editing` is updated to the new value on success so the dialog's
             * own preview reflects the change right away, without waiting for it to be reopened.
             */
            onPickPicture = { uri ->
                scope.launch {
                    when (val outcome = pictureStore.add(uri)) {
                        is ExercisePictureOutcome.Added -> {
                            val previous = exercise.pictureId
                            repo.setPicture(exercise.id, outcome.pictureId)
                            previous?.let { pictureStore.remove(it) }
                            editing = exercise.copy(pictureId = outcome.pictureId)
                        }
                        ExercisePictureOutcome.TooBig -> message = "Too large (over 16 MB). Not attached."
                        ExercisePictureOutcome.Unreadable -> message = "That picture could not be read."
                    }
                }
            },
            onRemovePicture = {
                exercise.pictureId?.let { previous ->
                    scope.launch {
                        repo.setPicture(exercise.id, null)
                        pictureStore.remove(previous)
                        editing = exercise.copy(pictureId = null)
                    }
                }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Not saved") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    return remember(repo) {
        ExerciseEditor(
            edit = { editing = it },
            toggleHidden = { exercise ->
                scope.launch { repo.setHidden(exercise.id, !exercise.hidden) }
            },
            delete = { exercise ->
                scope.launch { repo.deleteExercise(exercise) }
            },
            create = { name, form, workSec, restSec ->
                scope.launch { repo.ensureExercise(name, form, workSec, restSec) }
            },
        )
    }
}

/**
 * The correction itself: the name — the protocol is shown, never asked for.
 *
 * ── And one thing that is not a correction ─────────────────────────────────────
 * "One side at a time" is a statement about the exercise that nothing else in the app could
 * make: the column was being read — by the records block — while no control anywhere wrote
 * it, so the feature was dead on arrival. It sits in the correction dialog because this is
 * the one screen that shows an exercise as facts about itself; it is not a typo repair, and
 * the sentence under it says so. It is also asked at CREATION now — see
 * `ui/screens/ExercisePicker.kt` — which is where the owner first missed it.
 *
 * ── "How much of you it lifts" is gone ─────────────────────────────────────────
 * A percent field wrote `bodyweight_share` here and nothing else in the app ever asked for
 * it. Owner, 2026-08-11: "I never asked for it, I do not want it anywhere". The column and
 * every reader of it stay exactly as they were (dropping a column is a schema migration for
 * no gain) — it simply has no writer any more and stays empty, so a set logged at your own
 * body weight is worth no tonnage. That consequence was accepted out loud: "never mind the
 * tonnage, let it be a bit wrong".
 *
 * ── The form is shown and cannot be changed ────────────────────────────────────
 * It decides the shape of the payload every set of this exercise was written in, and changing
 * it would leave a history in one shape being read by screens expecting another. It is on
 * screen as a statement of fact, with the sentence that says why — a control that is simply
 * absent invites the same question every time.
 *
 * ── The protocol is shown and cannot be changed either ─────────────────────────
 * The owner's rule: "such a thing cannot happen: it breaks the statistics. If yesterday it was
 * one protocol and today another, that is a NEW exercise." Work/Rest used to be an editable
 * pair here, resolved through the same lookup [ActivityRepository.ensureExercise] uses for a
 * NEW exercise and repointed onto this row as a "correction" — which is exactly the hole this
 * screen now closes: the row's `identity_key` includes the protocol, so silently repointing it
 * moved a running exercise onto a different protocol under the SAME identity, with every set
 * already logged staying put underneath. The fields below are a fact, not an input, on the
 * same footing the form already was. An exercise with no protocol stays with none: "no
 * protocol" is itself part of what this row is, and cannot be added after the fact any more
 * than a protocol can be corrected.
 */
@Composable
private fun EditExerciseDialog(
    exercise: ExerciseEntity,
    /** The resolved protocol program [exercise.protocolProgramId] names, or null for none. */
    program: WorkoutProgram?,
    pictureStore: ExercisePictureStore,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit,
    /** Picked from the camera or the gallery — see [rememberExerciseEditor] for what this does
     *  with it (an immediate write, not part of [onSave]). */
    onPickPicture: (Uri) -> Unit,
    /** Takes the picture away. A no-op if the exercise has none — the button offering it is
     *  simply absent in that case, see below. */
    onRemovePicture: () -> Unit,
) {
    val hold = exercise.form == ExerciseForm.HOLD.code
    val lifted = hold || exercise.form == ExerciseForm.STRENGTH.code
    val protocolBlock = program?.firstBlock()
    var name by remember(exercise.id) { mutableStateOf(exercise.name) }
    var oneSided by remember(exercise.id) { mutableStateOf(exercise.oneSided) }

    val takePhoto = rememberCameraCapture(onPickPicture)
    val pickFromGallery = rememberSinglePicturePicker(onPickPicture)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                 * time, says which rack or which pulldown this row means. Above the form fields
                 * because it is the one thing on this dialog that is recognised rather than
                 * read.
                 */
                Text("Picture", style = MaterialTheme.typography.labelSmall)
                val picture = rememberPicture(
                    exercise.pictureId?.let { pictureStore.fileOf(it) },
                    EDIT_DIALOG_PICTURE_MAX_PX,
                )
                picture?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null, // decoration: the Name field above already names this exercise
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = takePhoto) { Text("Camera") }
                    OutlinedButton(onClick = pickFromGallery) { Text("Gallery") }
                    if (exercise.pictureId != null) {
                        TextButton(onClick = onRemovePicture) { Text("Remove") }
                    }
                }
                if (hold) {
                    Text(
                        "Protocol (work : rest, seconds)",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        if (protocolBlock != null) {
                            "${protocolBlock.workSec} : ${protocolBlock.restSec}"
                        } else {
                            "None"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Fixed. The protocol is part of what makes this exercise the exercise " +
                            "it is; changing it would put today's sets under yesterday's " +
                            "history. Trained on a different protocol now? Create it as a new " +
                            "exercise - it starts a history of its own.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (lifted) {
                    /*
                     * The one switch here that is not a correction of a typo.
                     *
                     * It is what splits the record per side, and until it existed anywhere in
                     * the UI the whole per-side feature was unreachable: the entry form asks
                     * for a side only when the exercise claims to need one, and nothing could
                     * make it claim that. Found from the phone, 2026-08-08.
                     *
                     * GATED ON [lifted], not on [hold]: a hangboard hang was never the only
                     * thing trained one limb at a time — a pistol squat and a one-arm row are
                     * the same asymmetry on a [StrengthSet] — and the mechanism underneath
                     * (two workout cards, a rest floor per card, a record per side) never cared
                     * which form the exercise was. Only this switch did, and that was the gap.
                     */
                    FilterChip(
                        selected = oneSided,
                        onClick = { oneSided = !oneSided },
                        label = { Text("One side at a time") },
                    )
                    Text(
                        "Each side keeps its own record, and every set of this exercise is " +
                            "asked which side it was.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    "The form stays ${ExerciseForm.fromCodeOrTick(exercise.form).title.lowercase()}: " +
                        "it decides the shape every set of this exercise was written in, and " +
                        "changing it would leave that history unreadable.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), oneSided) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The form of a row, degrading to a check-in rather than throwing — see `ExerciseEntity.toRef`. */
private fun ExerciseForm.Companion.fromCodeOrTick(code: Int): ExerciseForm =
    runCatching { fromCode(code) }.getOrDefault(ExerciseForm.TICK)

/** How large a decode this dialog's own preview asks for — bigger than the picker's row
 *  thumbnail (it is the only thing on screen here), still nowhere near the full file. */
private const val EDIT_DIALOG_PICTURE_MAX_PX = 240
