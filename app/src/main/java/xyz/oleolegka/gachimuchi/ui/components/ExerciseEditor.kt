package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.round
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ExerciseEdit
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.firstBlock
import xyz.oleolegka.gachimuchi.domain.parseNumber

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

    var editing by remember { mutableStateOf<ExerciseEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    editing?.let { exercise ->
        val program = exercise.protocolProgramId?.let { id -> programs.firstOrNull { it.id == id } }
        EditExerciseDialog(
            exercise = exercise,
            program = program,
            onDismiss = { editing = null },
            onSave = { name, oneSided, share ->
                editing = null
                scope.launch {
                    val result = repo.editExercise(exercise.id, name)
                    /*
                     * The two flags are their own columns and their own writes: correcting a
                     * name, declaring the exercise one-handed and saying what share of you it
                     * lifts are different claims, and none of them may rewrite the others.
                     *
                     * They go AFTER the identity edit and only when it took. A refused edit
                     * tells the user the exercise "was left as it was", and that sentence has
                     * to be true of the whole dialog and not only of the name.
                     */
                    if (result is ExerciseEdit.Saved) {
                        if (oneSided != exercise.oneSided) repo.setOneSided(exercise.id, oneSided)
                        if (share != exercise.bodyweightShare) {
                            repo.setBodyweightShare(exercise.id, share)
                        }
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
 * ── And two things that are not corrections ────────────────────────────────────
 * "One hand at a time" and "how much of you it lifts" are statements about the exercise that
 * nothing else in the app could make. Both columns were being read — by the records block and
 * by the volume chart — while no control anywhere wrote them, so both features were dead on
 * arrival. They sit in the correction dialog because this is the one screen that shows an
 * exercise as facts about itself; they are not typo repairs, and the sentences under them say
 * so.
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
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Double?) -> Unit,
) {
    val hold = exercise.form == ExerciseForm.HOLD.code
    val lifted = hold || exercise.form == ExerciseForm.STRENGTH.code
    val protocolBlock = program?.firstBlock()
    var name by remember(exercise.id) { mutableStateOf(exercise.name) }
    var oneSided by remember(exercise.id) { mutableStateOf(exercise.oneSided) }
    var percent by remember(exercise.id) { mutableStateOf(exercise.bodyweightShare.asPercentField()) }

    val share = percentAsShare(percent)
    // typed something that is not a share of one body: refused rather than dropped on the
    // floor, since a number that vanishes on save is how this column stayed empty for months
    val percentBad = percent.isNotBlank() && share == null

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
                if (hold) {
                    /*
                     * The one switch here that is not a correction of a typo.
                     *
                     * It is what splits the record per hand, and until it existed anywhere in
                     * the UI the whole per-side feature was unreachable: the entry form asks
                     * for a hand only when the exercise claims to need one, and nothing could
                     * make it claim that. Found from the phone, 2026-08-08.
                     */
                    FilterChip(
                        selected = oneSided,
                        onClick = { oneSided = !oneSided },
                        label = { Text("One hand at a time") },
                    )
                    Text(
                        "Each hand keeps its own record, and every set of this exercise is " +
                            "asked which hand it was.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (lifted) {
                    /*
                     * The other thing here that is not a correction of a typo, and the same
                     * kind of hole the switch above was: `bodyweight_share` is computed with,
                     * exported, migrated and tested, and NO control in the app ever wrote it.
                     * A set done with your own body weight is therefore worth no tonnage at
                     * all, and a week of pull-ups draws as a week of doing nothing. Found from
                     * the phone, 2026-08-08.
                     *
                     * Asked as a PERCENT although the column is a share in (0, 1]: nobody
                     * thinks of a push-up as "0.65 of a person". The conversion is the one
                     * place this can go wrong, so it is a single pair of functions at the
                     * bottom of this file and the field refuses anything outside 0..100
                     * instead of storing a hundredth of what was meant.
                     */
                    OutlinedTextField(
                        value = percent,
                        onValueChange = { percent = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = percentBad,
                        label = { Text("How much of you it lifts, %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Text(
                        if (percentBad) {
                            "Between 0 and 100, or empty. A share of one body cannot be more " +
                                "than the whole of it."
                        } else if (hold) {
                            "A pull-up holds all of you: 100. A push-up, roughly two thirds: " +
                                "65 - a rough figure going around, not one anybody measured " +
                                "here. Leave it empty and nothing changes. On a hold it is " +
                                "recorded but changes no chart today: the impulse a hang is " +
                                "measured in counts the whole of you regardless of this."
                        } else {
                            "A pull-up lifts all of you: 100. A push-up, roughly two thirds: " +
                                "65 - a rough figure going around, not one anybody measured " +
                                "here. Until this is filled in, sets logged as your own body " +
                                "weight are worth NOTHING on the volume chart. Leave it empty " +
                                "and that stays exactly as it is today."
                        },
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
                enabled = name.isNotBlank() && !percentBad,
                onClick = {
                    onSave(
                        name.trim(),
                        oneSided,
                        if (lifted) share else exercise.bodyweightShare,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A stored number as the text of a field: "20" rather than "20.0", and empty for absent.
 *
 * Whole numbers are written whole because that is how they were typed, and a dialog that
 * opens showing "20.0" in a field the user entered as "20" reads as the app having changed
 * something.
 */
private fun Double?.asField(): String = when {
    this == null -> ""
    this == toLong().toDouble() -> toLong().toString()
    else -> toString()
}

/**
 * A typed percentage as the stored share, or null for "not said".
 *
 * Null covers both an empty field and a number that cannot be a share of one body. The upper
 * bound is the same one `usableShare` applies when reading, and it is enforced HERE as well
 * so that the refusal happens where the person can see it: a 150 accepted into the column
 * would be silently ignored by every chart afterwards, which is the failure this whole change
 * is about. Zero is refused for the same reason it is on read — "this exercise lifts none of
 * you" is not a thing anybody means; they mean they have not said.
 *
 * This and [asPercentField] are internal rather than private so that the pair can be tested on
 * its own: they are the only arithmetic between what is typed and what every chart later reads,
 * and a factor of a hundred lost here would look exactly like the bug being fixed.
 */
internal fun percentAsShare(text: String): Double? =
    parseNumber(text)?.let { it / 100.0 }?.takeIf { it > 0.0 && it <= 1.0 }

/**
 * The stored share as the text of a percent field: 0.65 opens as "65", not "65.00000000000001".
 *
 * Rounded to two decimal places of a percent, which is finer than anybody's estimate of what
 * fraction of themselves a push-up lifts and coarse enough that the multiplication's own error
 * never reaches the field. A value imported at a finer precision is shown rounded and saved as
 * what is shown — the field is not allowed to display one number and keep another.
 */
internal fun Double?.asPercentField(): String =
    this?.let { round(it * 10_000.0) / 100.0 }.asField()

/** The form of a row, degrading to a check-in rather than throwing — see `ExerciseEntity.toRef`. */
private fun ExerciseForm.Companion.fromCodeOrTick(code: Int): ExerciseForm =
    runCatching { fromCode(code) }.getOrDefault(ExerciseForm.TICK)
