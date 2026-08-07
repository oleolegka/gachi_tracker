package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
) {
    val colors = LocalGachiColors.current
    var draft by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                )
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
