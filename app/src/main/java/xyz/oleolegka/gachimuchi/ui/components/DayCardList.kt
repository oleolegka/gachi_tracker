package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.DayCard
import xyz.oleolegka.gachimuchi.domain.DayCardAction
import xyz.oleolegka.gachimuchi.domain.DayCardKind
import xyz.oleolegka.gachimuchi.domain.DayCards
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * The list of a day's cards — the one component behind both the Today tab and the day
 * picked on the calendar.
 *
 * Which cards there are, in which order, and what each one says is decided in
 * `domain/DayCards.kt` and tested there. This file only draws them, which is the whole
 * reason the split exists: two screens showing the same list must not be able to disagree
 * about it, and a Compose screen is the one part of this app nothing can test.
 *
 * ── One Add button, not two ─────────────────────────────────────────────────────
 * The screen already carries cards with their own actions ("Start", "Continue"). Putting
 * "Add workout" and "Add single entry" beside them as two more buttons makes four things to
 * read before anything can be done. So there is one button, and it asks which.
 *
 * ── Editing the plan is the calendar's job ──────────────────────────────────────
 * A planned card carries a pencil and a bin ONLY where those actions belong. Today is the
 * screen you stand in the gym with; rewriting the schedule from it is not a thing anyone
 * does mid-set, and a bin one mis-tap from "Start" is a bad trade. [DayActions] leaves both
 * null there and no icons are drawn.
 *
 * ── A long press acts on the card (§14.1) ───────────────────────────────────────
 * A WORKOUT card answers a long press with its own menu, which is where removing it lives —
 * see [ItemActions] for why the gesture rather than a bin on the row, and why removal is
 * behind one more question.
 *
 * The other three kinds answer nothing, and each for its own reason. A PLANNED card already
 * carries its two actions as icons where they belong, and giving it a second way to reach the
 * same pair would be the duplication this change is undoing rather than more of it. A SINGLE
 * card is a GROUP of entries, and "delete these five" is not a thing anyone means by long
 * pressing one card — its entries are removed one at a time from the breakdown the card opens.
 */
@Immutable
data class DayActions(
    /** Begin a workout from a planned session, on the given day. */
    val startFromPlan: (Long, LocalDate) -> Unit,

    /**
     * Begin a workout with no plan behind it, under [name] or under none.
     *
     * The name is NULLABLE and usually null: it is asked for on the way in, with the field
     * empty and the button already enabled, so that giving one is an option and never a toll
     * (§14.3). A workout with no name is shown by its time of day.
     */
    val startWorkout: (date: LocalDate, name: String?) -> Unit,

    /** Record something on its own, outside any workout. */
    val logSingleEntry: (LocalDate) -> Unit,

    /** Go back into the workout in progress. */
    val continueWorkout: (Long) -> Unit,

    /** Look inside a workout that is not running. */
    val openWorkout: (Long) -> Unit,

    /**
     * Look at what was done of one exercise ON THIS DAY — where a single-entry card leads.
     *
     * The DAY travels with the exercise, and that is the whole of the change §14.2 asked for:
     * this card used to open the all-time statistics of the exercise, which answers a question
     * nobody is asking at the moment they tap a card saying "3 entries". The charts are one tap
     * further on, from the breakdown.
     */
    val openExercise: (exerciseId: Long, date: LocalDate) -> Unit,

    /**
     * Remove a workout and everything recorded into it — behind the long press and behind a
     * confirmation, never on a tap.
     */
    val deleteWorkout: (Long) -> Unit,

    /** Name a workout that is already going, or clear its name with null. */
    val renameWorkout: (workoutId: Long, name: String?) -> Unit,

    /** The calendar's own two, absent everywhere else. */
    val editSlot: ((Long) -> Unit)? = null,
    val deleteSlot: ((Long) -> Unit)? = null,
)

/**
 * Draws [day]. Not a lazy list: a day holds two or three cards and is nested inside the
 * calendar's own scrolling list, where a second lazy column is not allowed anyway.
 */
@Composable
fun DayCardList(
    day: DayCards,
    date: LocalDate,
    actions: DayActions,
    modifier: Modifier = Modifier,
) {
    /** Whether the question "what shall this one be called" is on screen. */
    var naming by remember(day.date) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (day.isEmpty) {
            /*
             * The note names the button underneath it rather than reporting a count of zero.
             * This is the first thing a new install shows, and it used to be able to lean on
             * demo data to make the day look inhabited; with that gone, an empty day is the
             * whole first impression and it has to say where training gets written down.
             */
            DashedNote(
                if (day.canRecord) {
                    "Nothing planned and nothing recorded. Start a workout below, or log a " +
                        "single entry."
                } else {
                    // a day that has not happened cannot have "nothing recorded" held
                    // against it, so it is not told that it does. It is also only ever drawn
                    // on the calendar (Today is never in the future), which is why the
                    // sentence can name the "Plan a session" button sitting under this list
                    "Nothing planned for this day. Plan a session below and it appears here."
                }
            )
        }

        day.cards.forEach { card -> DayCardRow(card, date, actions) }

        if (day.canRecord) {
            AddMenuButton(
                onWorkout = { naming = true },
                onSingleEntry = { actions.logSingleEntry(date) },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    if (naming) {
        NameDialog(
            title = "Start a workout",
            label = "Name (optional)",
            initial = "",
            confirmLabel = "Start",
            note = "Leave it empty and the card shows the time of day instead. It can be " +
                "named later from the card.",
            onConfirm = { name ->
                naming = false
                actions.startWorkout(date, name)
            },
            onDismiss = { naming = false },
        )
    }
}

/**
 * One card: a coloured spine, the name and its time, the subtitle that says what KIND of
 * card this is, an optional record line, and the action.
 *
 * The kind is never said in colour alone — the spine follows it, but the subtitle underneath
 * carries the same information in words, which is the rule everywhere else in this app.
 */
@Composable
private fun DayCardRow(card: DayCard, date: LocalDate, actions: DayActions) {
    val colors = LocalGachiColors.current
    val onTap: (() -> Unit)? = when (card.action) {
        /*
         * A tap on the BODY of a planned card opens the plan; only the button starts.
         *
         * It used to start from anywhere on the card, without a question, and that is how a
         * plan for the evening became a workout running an hour early: the user tapped it to
         * look inside and to add exercises, which is the one thing a plan invites. Reported
         * from the phone as "I did not want to start a workout, why did the app decide that I
         * did" (2026-08-08).
         *
         * Beginning something is a deliberate act and gets a deliberate target — the button
         * that says the word. The card itself is for looking, which is what a card is for
         * everywhere else on this screen.
         */
        DayCardAction.START ->
            card.slotId?.let { id -> actions.editSlot?.let { edit -> { edit(id) } } }

        DayCardAction.CONTINUE -> card.workoutId?.let { id -> { actions.continueWorkout(id) } }
        DayCardAction.OPEN -> when (card.kind) {
            DayCardKind.SINGLE -> card.exerciseId?.let { id -> { actions.openExercise(id, date) } }
            else -> card.workoutId?.let { id -> { actions.openWorkout(id) } }
        }

        DayCardAction.NONE -> null
    }
    val spine = when (card.kind) {
        DayCardKind.PLANNED -> colors.accent
        DayCardKind.RUNNING -> colors.good
        DayCardKind.DONE -> colors.inkSecondary
        DayCardKind.SINGLE -> colors.inkMuted
    }

    /** The workout this card is about, for the actions a long press offers. */
    val workoutId = card.workoutId.takeIf {
        card.kind == DayCardKind.RUNNING || card.kind == DayCardKind.DONE
    }
    var confirmingDelete by remember(card.key) { mutableStateOf(false) }
    var renaming by remember(card.key) { mutableStateOf(false) }
    val menu = if (workoutId == null) {
        emptyList()
    } else {
        listOf(
            // the harmless one first: a menu whose top entry deletes is a menu that gets
            // dismissed rather than read
            ItemAction(if (card.workoutName == null) "Name it" else "Rename") { renaming = true },
            ItemAction("Delete workout", destructive = true) { confirmingDelete = true },
        )
    }

    ItemActions(
        title = card.title,
        actions = menu,
        onTap = onTap,
        modifier = Modifier.fillMaxWidth(),
    ) { press ->
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .then(press),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .heightIn(min = 62.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                .background(spine)
        )
        Column(
            Modifier.weight(1f).padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (card.timeLabel.isNotEmpty()) {
                    Text(
                        card.timeLabel,
                        fontSize = 11.sp,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(card.subtitle, fontSize = 11.5.sp, color = colors.inkSecondary)
            // a record is stated in words, never by colour alone
            card.recordLine?.let {
                Text(it, fontSize = 11.sp, color = colors.good, fontWeight = FontWeight.Medium)
            }
        }

        val edit = actions.editSlot
        val delete = actions.deleteSlot
        val slotId = card.slotId
        if (card.kind == DayCardKind.PLANNED && slotId != null && edit != null && delete != null) {
            RowIcon(Icons.Filled.Edit, "Edit \"${card.title}\"", colors.inkSecondary) { edit(slotId) }
            RowIcon(Icons.Filled.Delete, "Delete \"${card.title}\"", colors.critical) { delete(slotId) }
        }

        /*
         * Only the two actions that BEGIN something get a button of their own; "open" is the
         * card itself, and a button saying "open" next to a card that opens is noise.
         *
         * The button carries its OWN handler rather than reusing the card's tap: for a plan
         * the two are deliberately different things now — the card opens the plan, the button
         * starts the workout.
         */
        val begin: Pair<String, () -> Unit>? = when (card.action) {
            DayCardAction.START ->
                card.slotId?.let { id -> "Start" to { actions.startFromPlan(id, date) } }

            DayCardAction.CONTINUE ->
                card.workoutId?.let { id -> "Continue" to { actions.continueWorkout(id) } }

            DayCardAction.OPEN, DayCardAction.NONE -> null
        }
        if (begin != null) {
            val (label, onBegin) = begin
            Button(
                onClick = onBegin,
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.padding(end = 8.dp).heightIn(min = 40.dp),
            ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        } else {
            Box(Modifier.width(12.dp))
        }
    }
    }

    if (renaming && workoutId != null) {
        NameDialog(
            title = if (card.workoutName == null) "Name this workout" else "Rename this workout",
            label = "Name (optional)",
            // empty for a workout nobody named, rather than the time range it is shown by:
            // the range is a fact about the day and was never a name to edit
            initial = card.workoutName.orEmpty(),
            confirmLabel = "Save",
            note = "Leave it empty and the card goes back to showing the time of day.",
            onConfirm = { name ->
                renaming = false
                actions.renameWorkout(workoutId, name)
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmingDelete && workoutId != null) {
        ConfirmRemoveDialog(
            title = "Delete this workout?",
            // the card's own two lines, so the dialog is unmistakably about the card that
            // was pressed and not about the one next to it
            subject = listOf(card.title, card.subtitle).filter { it.isNotEmpty() }
                .joinToString(" - "),
            explanation = "Everything recorded in it goes too - its sets stop counting " +
                "towards volume, records and the streak. $REMOVAL_IS_REVERSIBLE",
            confirmLabel = "Delete",
            onConfirm = {
                confirmingDelete = false
                actions.deleteWorkout(workoutId)
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun RowIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * "Add", and then the question of what.
 *
 * The two answers are not the same size of thing and the wording says so: a WORKOUT is the
 * session you are about to do, a SINGLE ENTRY is one exercise recorded on its own — the
 * stretching done in front of the television, which the model has always allowed and the
 * screens used not to show as anything in particular.
 */
@Composable
private fun AddMenuButton(
    onWorkout: () -> Unit,
    onSingleEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Add", modifier = Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Workout") },
                onClick = {
                    open = false
                    onWorkout()
                },
            )
            DropdownMenuItem(
                text = { Text("Single entry") },
                onClick = {
                    open = false
                    onSingleEntry()
                },
            )
        }
    }
}
