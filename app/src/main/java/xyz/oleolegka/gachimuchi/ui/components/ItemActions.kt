package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * ONE GESTURE for "what can I do with this": a long press on the thing itself.
 *
 * ── Why a long press rather than a bin on every row ─────────────────────────────
 * Removing training is destructive and it is rare. A visible affordance for it has to sit
 * next to the controls used twenty times an hour, where it is a mis-tap away from deleting a
 * set that actually happened — which is precisely the trade the workout screen was refusing
 * when it hid deletion one level inside the editor dialog. A long press costs nothing on
 * screen, cannot be hit by accident, and is the platform's own idiom for "act on this item".
 *
 * ── Why one component and not three menus ───────────────────────────────────────
 * The gesture is the point. A day card, an exercise inside a workout and a single set are
 * three different things, and if each grew its own menu they would drift: one would confirm
 * and another would not, one would say "Delete" and another "Remove". Here the press, the
 * heading, the ordering and the colour of a destructive entry are decided once. What each
 * caller supplies is the list of actions, which is the only part that is actually about the
 * thing being pressed.
 *
 * ── The menu says what it is about ──────────────────────────────────────────────
 * [title] is drawn as the first line. A menu raised by a long press appears next to the
 * finger and not attached to anything the eye can trace back, so a menu that only said
 * "Delete" would be a menu you cannot safely answer — on a list of four similar cards there
 * is no way to tell which one it belongs to.
 *
 * Removal itself is never done from here; every destructive action raises
 * [ConfirmRemoveDialog] first.
 */
@Immutable
data class ItemAction(
    val label: String,
    /** Drawn in the critical colour, and stated in words too — nothing here is colour alone. */
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Wraps [content] so that a long press on it raises [actions].
 *
 * [content] is handed the modifier carrying the gesture rather than having it applied for it,
 * because where the press belongs differs: a day card wants it under its own clip and border
 * so the ripple follows the rounded corner, a set line wants it on the whole row. A caller
 * that drops the modifier gets a card nothing can be done with, which is visible immediately.
 *
 * The modifier is EMPTY when there is neither a tap nor an action, so an element with nothing
 * to offer does not become clickable and does not announce a click action it cannot honour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemActions(
    title: String,
    actions: List<ItemAction>,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val colors = LocalGachiColors.current
    var open by remember { mutableStateOf(false) }

    val press = if (onTap == null && actions.isEmpty()) {
        Modifier
    } else {
        Modifier.combinedClickable(
            onClick = { onTap?.invoke() },
            onLongClick = if (actions.isEmpty()) null else ({ open = true }),
            onLongClickLabel = "Actions for $title",
        )
    }

    Box(modifier) {
        content(press)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkSecondary,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            )
            HorizontalDivider(color = colors.grid)
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            color = if (action.destructive) {
                                colors.critical
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

/**
 * The one more question asked before anything is removed.
 *
 * ── It names what disappears, not what is being pressed ─────────────────────────
 * "Are you sure?" is a question nobody can answer wrongly and nobody can answer well. What
 * makes a confirmation worth showing is [subject] — the entry, the exercise, the workout, in
 * the same words the screen behind it used — and [explanation], which says what STOPS COUNTING.
 * Deleting a workout takes its sets out of the volume, the records and the streak, and that
 * consequence is invisible from a dialog that only repeats the verb.
 *
 * ── And it says the journal keeps the row ───────────────────────────────────────
 * Because it does: removing is itself an event (domain/Amendments.kt), the original is never
 * taken out of the table, and deleting the deletion brings it back. That is the difference
 * between a button people are afraid of and one they will use.
 */
@Composable
fun ConfirmRemoveDialog(
    title: String,
    /** What is about to go, in the words the screen behind this dialog used for it. */
    subject: String,
    explanation: String,
    confirmLabel: String = "Remove",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(subject, style = MaterialTheme.typography.titleSmall)
                Text(
                    explanation,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}

/**
 * The sentence every removal in this app ends with.
 *
 * Kept in one place so the promise is the same one everywhere: it is the promise that makes
 * the button safe, and a screen that quietly left it out would be making a different one.
 */
const val REMOVAL_IS_REVERSIBLE: String =
    "The journal keeps the original rows - removing is itself an entry, so this can be undone " +
        "later rather than being the end of the evidence."
