package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing

/**
 * Asking what to call something, where the honest answer is often "nothing".
 *
 * ── Blank is an answer, not a failure to answer (§14.3) ─────────────────────────
 * The confirm button is ENABLED with the field empty, and pressing it hands back null. A
 * workout has never needed a name to exist — the card falls back to the time of day — and the
 * one thing this dialog must not do is stand between somebody and the start of a session
 * because they have not thought of a word yet. So the note under the field says what happens
 * if it is left alone, rather than telling the user off for leaving it alone.
 *
 * That also makes CLEARING a name work with no second control: emptying the field and
 * confirming is how a workout goes back to being shown by its time.
 *
 * ── It is not a text field with a validator ─────────────────────────────────────
 * Nothing about a name can be wrong, so there is nothing to validate and no error state to
 * draw. Whitespace is trimmed and a name of nothing but spaces is the same as no name, decided
 * here so that neither the caller nor the journal has to think about it twice.
 *
 * ── [suggestions] is a shortcut into the field, nothing more (§13.9) ────────────
 * Picking one just sets [draft] to that string — this dialog knows nothing about what a name
 * matching a past workout DOES (that is [xyz.oleolegka.gachimuchi.ui.MainViewModel.beginDraft]'s
 * concern, once [onConfirm] hands the plain text back up). Typing the same word by hand, without
 * ever opening the list, reaches the exact same string and the exact same outcome — the dropdown
 * saves a retype, it is not a second way of asking for one.
 */
@Composable
fun NameDialog(
    title: String,
    label: String,
    /** What the field starts with — empty for something not yet named. */
    initial: String,
    confirmLabel: String,
    /** What happens if the field is left empty, said in the dialog rather than discovered. */
    note: String,
    /** The trimmed name, or null when the field was left blank. */
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Names offered from a dropdown beside the field — empty for every caller but "start a
     * workout" (see [xyz.oleolegka.gachimuchi.ui.components.DayActions.startWorkout]), which is
     * the only question a past NAME answers anything beyond itself for. A rename dialog passes
     * none: renaming is not "start like", and offering the same list there would suggest it is.
     */
    suggestions: List<String> = emptyList(),
) {
    val colors = LocalGachiColors.current
    var draft by remember(initial) { mutableStateOf(initial) }
    var menuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
                Box {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(label) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        trailingIcon = if (suggestions.isEmpty()) {
                            null
                        } else {
                            {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Start like a past workout")
                                }
                            }
                        },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        suggestions.forEach { pastName ->
                            DropdownMenuItem(
                                text = { Text(pastName) },
                                onClick = {
                                    draft = pastName
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft.trim().takeIf { it.isNotEmpty() }) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
