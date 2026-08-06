package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.ui.LogReceipt
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * "That went in." The receipt for sets written from a finished run.
 *
 * ── Why this exists at all ──────────────────────────────────────────────────────
 * The write was silent. The offer closed, the journal changed somewhere off screen, and
 * the only way to find out whether anything had happened was to go and look — which,
 * standing in a gym with ruined fingers, nobody does. Two full sessions were run in the
 * belief that the timer had recorded nothing.
 *
 * The cost of that doubt is not a bad feeling, it is duplicate data: a session believed
 * unlogged gets typed in again, and the journal then says twice the training actually done,
 * with the personal records to match. So the write states itself, by name and by count, and
 * it does so as a dialog rather than a passing message — this happens once per session, not
 * once per set, and it is worth the tap.
 *
 * ── Undo, because a confirmation without one is just an announcement ────────────
 * The numbers in the offer are a proposal the timer made, and proposals are sometimes
 * wrong. Undo appends reversing events (§ the journal is append-only), so taking a write
 * back is itself recorded rather than being a hole in the history.
 */
@Composable
fun LogReceiptDialog(
    receipt: LogReceipt,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current
    val sets = if (receipt.setCount == 1) "1 set" else "${receipt.setCount} sets"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (receipt.setCount == 0) "Nothing was written" else "Logged $sets") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (receipt.setCount == 0) {
                    /*
                     * Reachable when every set in the offer was edited down to zero, and
                     * also when the exercise turned out not to be a hold. Saying so is the
                     * whole point of this dialog: the one outcome that must never be silent
                     * is the one where nothing happened.
                     */
                    Text(
                        "The run was not written to the journal - every set in the offer was " +
                            "empty. Nothing was changed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                } else {
                    Text(
                        "${receipt.exerciseName} - $sets on ${receipt.opDate}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                    Text(
                        "They are in the journal now and count towards records like any set " +
                            "entered by hand.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Good") } },
        dismissButton = {
            if (receipt.setCount > 0) TextButton(onClick = onUndo) { Text("Undo") }
        },
    )
}
