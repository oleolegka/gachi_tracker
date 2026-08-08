package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Dragging a row of a [androidx.compose.foundation.lazy.LazyColumn] to another place in it.
 *
 * ── There is nothing in Compose to call for this ────────────────────────────────
 * A lazy list draws a window onto a list it does not own, so "the item is now third" is not
 * something the list can be told — the caller has to hold the order, and the only thing missing
 * is the arithmetic that turns a finger travelling down the screen into "it belongs there now".
 * That arithmetic is what this file is, and it is deliberately the whole of it: nothing here
 * knows WHAT is being reordered, only where the finger has got to.
 *
 * ── The slots are measured once, when the card is picked up ─────────────────────
 * [start] takes a snapshot of where every visible row is, and every decision afterwards is made
 * against that snapshot and the distance travelled. Nothing re-reads the list's layout to decide
 * a move, and that is the point rather than an optimisation:
 *
 *  - a decision made against the LIVE layout is a decision made against a layout that already
 *    reflects the previous swap, so the card is compared to the slot it has just been given and
 *    the answer flips back and forth between two arrangements;
 *  - a lazy list re-measures on a frame, and pointer events arrive between frames. Reading the
 *    layout mid-gesture means reading whatever the last frame happened to leave, which is a
 *    different answer depending on how busy the phone is.
 *
 * So the question asked is the simple one: given where the card started and how far the finger
 * has gone, WHICH SLOT is the middle of the card in now. The answer is an index, the whole order
 * follows from it, and it is the same answer however many times it is asked.
 *
 * Beyond the ends it CLAMPS — a card dragged past the bottom of the list belongs last, not
 * nowhere. That also covers the case a rule based on overlap gets wrong: pointer events are
 * coalesced, so a quick flick arrives as one jump of several hundred pixels, and a card that
 * jumps clean over its neighbour has still passed it.
 *
 * ── What it deliberately does not do ────────────────────────────────────────────
 * It does not SCROLL. A card dragged to the bottom edge of the screen stops there, and a list
 * longer than the window can only be rearranged within one screenful. The autoscroll that would
 * fix it is a second timing loop, and the lists this is used on — the exercises of one workout —
 * are a handful of cards long. If that changes, this is where it goes.
 *
 * It also only knows about VISIBLE rows, for the same reason: a row that was off screen when the
 * card was picked up has no measured slot to compare a finger against.
 */
@Stable
class ReorderState internal constructor(private val listState: LazyListState) {

    /**
     * Keys of the draggable rows, in the order they are being drawn right now.
     *
     * A LAMBDA and not a list, because the order changes under the finger and the answer has to
     * be the current one at the moment a pointer event arrives — not the one that was true when
     * the composition that installed this gesture last ran. A captured list is right whenever a
     * frame has been drawn between two pointer events and wrong when one has not, which is a
     * difference no caller should have to know about.
     */
    internal var keys: () -> List<String> = { emptyList() }

    /** Told the whole order the card in hand now implies. The caller owns the list. */
    internal var onOrder: (List<String>) -> Unit = {}

    /** The row in the hand, or null when nothing is being dragged. */
    var draggedKey: String? by mutableStateOf(null)
        private set

    /** Where the rows were when the card was picked up: the top of each, in list order. */
    private var slots: List<Pair<Int, Int>> = emptyList()

    /** The order the rows were in at that moment — what a new arrangement is built out of. */
    private var picked: List<String> = emptyList()

    private var startOffset = 0
    private var startSize = 0

    /** How far the finger has travelled since, signed. Read while drawing, so it is state. */
    private var travelled by mutableFloatStateOf(0f)

    /**
     * Picks the row up and measures the list around it.
     *
     * Does nothing when the row is not on screen, which cannot happen from a long press on the
     * row itself and would otherwise leave the arithmetic with no origin to measure from.
     */
    fun start(key: String) {
        val order = keys()
        val visible = listState.layoutInfo.visibleItemsInfo.filter { it.key in order }
        val info = visible.firstOrNull { it.key == key } ?: return
        slots = visible.map { it.offset to it.size }
        picked = visible.map { it.key as String }
        startOffset = info.offset
        startSize = info.size
        travelled = 0f
        draggedKey = key
    }

    /** Puts the row down. The caller decides what to do with the order it has been left with. */
    fun stop() {
        draggedKey = null
        travelled = 0f
        slots = emptyList()
        picked = emptyList()
    }

    /** Moves the row by [deltaY] pixels and states the order that now implies. */
    fun drag(deltaY: Float) {
        val key = draggedKey ?: return
        travelled += deltaY
        val from = picked.indexOf(key)
        if (from < 0) return

        val centre = startOffset + travelled + startSize / 2f
        // the last slot whose middle the card's middle has got past, and the first slot when it
        // has got past none of them — which is the clamp at the top of the list
        val to = slots.indexOfLast { (offset, size) -> offset + size / 2f <= centre }.coerceAtLeast(0)
        if (to == from) return

        val wanted = picked.moved(from, to)
        if (wanted != keys()) onOrder(wanted)
    }

    /**
     * How far the row keyed [key] should be drawn from where the list has just put it.
     *
     * Zero for every row but the one in the hand — the others move because the LIST moves them,
     * which is what makes the gap open up under the card rather than being animated here.
     *
     * This one reads the LIVE layout, unlike every decision above, and for the opposite reason:
     * it answers "where is this card being drawn this frame", so the layout of this frame is
     * exactly the right thing to measure against.
     */
    fun offsetOf(key: String): Float {
        if (key != draggedKey) return 0f
        val here = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
        return startOffset + travelled - (here ?: startOffset)
    }
}

/**
 * A [ReorderState] over [listState], told on every composition what it is looking at.
 *
 * [keys] and [onOrder] are written through on each pass rather than captured once, because both
 * describe a list that changes under the finger — the order being previewed IS the thing being
 * dragged. Doing it in a [SideEffect] keeps the writes out of composition itself, where a write
 * to state read by the same composition is the recomposition loop this app has been bitten by
 * before.
 */
@Composable
fun rememberReorderState(
    listState: LazyListState,
    keys: () -> List<String>,
    onOrder: (List<String>) -> Unit,
): ReorderState {
    val state = remember(listState) { ReorderState(listState) }
    SideEffect {
        state.keys = keys
        state.onOrder = onOrder
    }
    return state
}

/** [this] with the item at [from] taken out and put back at [to]. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
