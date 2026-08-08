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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ExerciseEdit
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.parseNumber

/**
 * Correcting a catalog exercise, and taking one out of the pickers.
 *
 * ── Why this exists at all ──────────────────────────────────────────────────────
 * A name typed into the entry card became a catalog row and then could never be touched
 * again: `ExerciseDao.update` existed and was called from nowhere, so a typo, a wrong edge or
 * a protocol entered as 7:30 instead of 7:3 was permanent. On the one screen where the
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
 */
class ExerciseEditor internal constructor(
    /** Opens the correction dialog for this exercise. */
    val edit: (ExerciseEntity) -> Unit,
    /** Hides it from the pickers, or brings it back. */
    val toggleHidden: (ExerciseEntity) -> Unit,
)

@Composable
fun rememberExerciseEditor(): ExerciseEditor {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { ActivityRepository(AppDatabase.get(context)) }

    var editing by remember { mutableStateOf<ExerciseEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    editing?.let { exercise ->
        EditExerciseDialog(
            exercise = exercise,
            onDismiss = { editing = null },
            onSave = { name, edge, work, rest, oneSided ->
                editing = null
                scope.launch {
                    // the side flag is its own column and its own write: correcting a name
                    // and declaring the exercise one-handed are different claims
                    if (oneSided != exercise.oneSided) repo.setOneSided(exercise.id, oneSided)
                    val result = repo.editExercise(exercise.id, name, edge, work, rest)
                    message = when (result) {
                        is ExerciseEdit.Saved -> null
                        is ExerciseEdit.Blank -> "An exercise needs a name."
                        is ExerciseEdit.Gone -> "That exercise is no longer in the catalog."
                        is ExerciseEdit.Taken ->
                            "\"${result.name}\" already has that name, edge and protocol, and " +
                                "an exercise is those three together. Two rows claiming to be " +
                                "the same exercise would split its history in half, so this " +
                                "one was left as it was."
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
        )
    }
}

/**
 * The correction itself: the name, and for a hold the edge and the work:rest protocol.
 *
 * ── The form is shown and cannot be changed ────────────────────────────────────
 * It decides the shape of the payload every set of this exercise was written in, and changing
 * it would leave a history in one shape being read by screens expecting another. It is on
 * screen as a statement of fact, with the sentence that says why — a control that is simply
 * absent invites the same question every time.
 *
 * The numbers follow exactly the rule the creation form uses: positive or not filled in, and
 * the protocol is a pair or nothing. See `CreateExerciseForm` in ui/screens/ExercisePicker.kt
 * for why a zero is treated as an empty field rather than as a zero.
 */
@Composable
private fun EditExerciseDialog(
    exercise: ExerciseEntity,
    onDismiss: () -> Unit,
    onSave: (String, Double?, Double?, Double?, Boolean) -> Unit,
) {
    val hold = exercise.form == ExerciseForm.HOLD.code
    var name by remember(exercise.id) { mutableStateOf(exercise.name) }
    var edge by remember(exercise.id) { mutableStateOf(exercise.edgeMm.asField()) }
    var work by remember(exercise.id) { mutableStateOf(exercise.protocolWorkSec.asField()) }
    var rest by remember(exercise.id) { mutableStateOf(exercise.protocolRestSec.asField()) }
    var oneSided by remember(exercise.id) { mutableStateOf(exercise.oneSided) }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = edge, onValueChange = { edge = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Edge, mm") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        OutlinedTextField(
                            value = work, onValueChange = { work = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Work, s") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        OutlinedTextField(
                            value = rest, onValueChange = { rest = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Rest, s") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
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
                Text(
                    "This corrects what the catalog SAYS. The sets already logged stay with " +
                        "this exercise, and the ones recorded before the correction still carry " +
                        "the edge and protocol they were written with. An exercise you have " +
                        "genuinely moved to another edge is a different exercise - create it " +
                        "instead, and it starts its own history from today.",
                    style = MaterialTheme.typography.labelSmall,
                )
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
                onClick = {
                    val w = if (hold) parseNumber(work)?.takeIf { it > 0 } else exercise.protocolWorkSec
                    val r = if (hold) parseNumber(rest)?.takeIf { it > 0 } else exercise.protocolRestSec
                    val pair = if (w != null && r != null) w to r else null
                    onSave(
                        name.trim(),
                        if (hold) parseNumber(edge)?.takeIf { it > 0 } else exercise.edgeMm,
                        pair?.first,
                        pair?.second,
                        oneSided,
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

/** The form of a row, degrading to a check-in rather than throwing — see `ExerciseEntity.toRef`. */
private fun ExerciseForm.Companion.fromCodeOrTick(code: Int): ExerciseForm =
    runCatching { fromCode(code) }.getOrDefault(ExerciseForm.TICK)
