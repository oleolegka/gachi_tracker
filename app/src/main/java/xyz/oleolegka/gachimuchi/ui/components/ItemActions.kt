package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
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
 * A long press that PICKS THE ITEM UP instead of opening the menu.
 *
 * ── One gesture, two meanings, told apart by whether the finger moved ───────────
 * The long press was already spoken for: it is how the menu is raised, and the menu is how an
 * exercise is taken out of a workout. Reordering was asked for on the same gesture, and the
 * honest reading is that they are the same intention at different lengths — "I want to do
 * something with this card" followed by either a move or a release. So the press lifts the card,
 * and letting go without having moved it is what raises the menu. Nothing is lost: every action
 * the menu had is still one long press away, and the press that used to open it still does.
 *
 * The alternative was a "Reorder" entry inside the menu, turning the drag on. It is more certain
 * — there is no threshold to get wrong — and it costs a tap on every reorder, on a screen whose
 * whole layout is an argument about taps between sets. The threshold is [DRAG_INTENT_SLOP], and
 * it is deliberately small: below it a finger has not moved, and above it nothing else on this
 * card was ever going to happen.
 *
 * The menu keeps a written way to reorder as well (WorkoutLogScreen offers "Move up" and "Move
 * down"), which is not a fallback for indecisive fingers but the version that works with a
 * screen reader, where a drag has nothing to report.
 */
@Immutable
data class ItemDrag(
    /** The long press has fired and the card is now in the hand. */
    val onStart: () -> Unit,
    /** The finger has moved this many pixels down the screen since the last report. */
    val onDrag: (deltaY: Float) -> Unit,
    /** Released after moving: this is where it goes. */
    val onDrop: () -> Unit,
    /** Released without moving, or taken away by the system. Put everything back. */
    val onCancel: () -> Unit,
)

/** Below this, a finger has not moved and the long press meant the menu. */
private val DRAG_INTENT_SLOP = 6.dp

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
 *
 * [drag] hands the long press to something else — see [ItemDrag]. The tap is still
 * `combinedClickable`'s, and its long press is still declared (as an empty one) so that a press
 * held and released does not ALSO fire the tap: what that press meant is decided below, by the
 * detector that knows whether the finger moved.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemActions(
    title: String,
    actions: List<ItemAction>,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    drag: ItemDrag? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val colors = LocalGachiColors.current
    var open by remember { mutableStateOf(false) }

    val slopPx = with(LocalDensity.current) { DRAG_INTENT_SLOP.toPx() }
    // read at gesture time rather than captured, so a drag in flight is not cut off by the
    // recomposition that the drag itself causes
    val current by rememberUpdatedState(drag)
    val travelled = remember { mutableFloatStateOf(0f) }

    val press = if (onTap == null && actions.isEmpty() && drag == null) {
        Modifier
    } else {
        Modifier
            .combinedClickable(
                onClick = { onTap?.invoke() },
                onLongClick = when {
                    drag != null -> ({ /* the drag detector below decides what it meant */ })
                    actions.isEmpty() -> null
                    else -> ({ open = true })
                },
                onLongClickLabel = "Actions for $title",
            )
            .then(
                if (drag == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                travelled.floatValue = 0f
                                current?.onStart?.invoke()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                travelled.floatValue += abs(amount.y)
                                current?.onDrag?.invoke(amount.y)
                            },
                            onDragEnd = {
                                if (travelled.floatValue >= slopPx) {
                                    current?.onDrop?.invoke()
                                } else {
                                    current?.onCancel?.invoke()
                                    if (actions.isNotEmpty()) open = true
                                }
                            },
                            onDragCancel = { current?.onCancel?.invoke() },
                        )
                    }
                }
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
