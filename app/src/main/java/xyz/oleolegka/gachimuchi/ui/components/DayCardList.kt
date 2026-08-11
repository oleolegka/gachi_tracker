package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.DayCard
import xyz.oleolegka.gachimuchi.domain.DayCardAction
import xyz.oleolegka.gachimuchi.domain.DayCardKind
import xyz.oleolegka.gachimuchi.domain.DayCardRecord
import xyz.oleolegka.gachimuchi.domain.DayCards
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize
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
 * A planned card carries its edit and delete ONLY where those actions belong. Today is the
 * screen you stand in the gym with; rewriting the schedule from it is not a thing anyone
 * does mid-set. [DayActions] leaves both null there and no menu is drawn.
 *
 * Where they ARE drawn they are one kebab, not a pencil and a bin: the bin sat eight points
 * from "Start", and the price of the mis-tap was a deleted plan (rule 3 of
 * `design-system/app-next/SYSTEM.md`).
 *
 * ── A long press acts on the card (§14.1) ───────────────────────────────────────
 * A WORKOUT card answers a long press with its own menu, which is where removing it lives —
 * see [ItemActions] for why the gesture rather than a bin on the row, and why removal is
 * behind one more question.
 *
 * A SINGLE card answers it too, and that is a correction rather than an extension (§23.A1).
 * The argument used to be that "delete these five" is not what anyone means by long pressing
 * one card, so its entries were removed one at a time from the breakdown. On the phone that
 * came out as an object with no way to delete it: emptying it by hand made it vanish as a
 * side effect, which is not the same thing as a delete and reads as the app losing track. A
 * card is an object, and every object of the log is removed the same way.
 *
 * A PLANNED card answers it on the CALENDAR and nowhere else, because that is the only
 * screen that hands in editing and deleting it. It is the same menu the three dots open —
 * one menu, two ways to it — which is exactly the arrangement every other card here has.
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

    /**
     * Go back into the workout being composed — the one the DRAFT card stands for.
     *
     * Takes no id because a draft has none: it is not a row, there is at most one, and the
     * ViewModel that holds it is the thing that knows which day it is for.
     */
    val resumeDraft: () -> Unit,

    /**
     * Throw the composed-but-not-started workout away. ASKED FOR, never a side effect of
     * leaving a screen — that was §23.A3.
     */
    val discardDraft: () -> Unit,

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

    /**
     * Remove a SINGLE-entry card whole — every row it stands for, in one act, and the rest
     * countdown its exercise may still be running with them.
     *
     * The rows travel from the card ([DayCard.entryIds]) because the grouping that put them on
     * one card is the domain's, not the screen's; [exerciseId] is null for a card that names no
     * catalog exercise (a weigh-in), which has no countdown to stop and is deleted just the same.
     */
    val deleteSingleEntries: (eventIds: List<Long>, exerciseId: Long?) -> Unit,

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
    /**
     * Names offered by "Start a workout" as a shortcut to composing like a past session (§13.9)
     * — see [xyz.oleolegka.gachimuchi.domain.pastWorkoutNames]. Empty is a perfectly ordinary
     * state (nothing has ever been named yet) and simply draws the dialog with no dropdown.
     */
    pastWorkoutNames: List<String> = emptyList(),
    /**
     * Plan a session ON THIS DAY, or null where planning does not belong.
     *
     * Null in two different circumstances, and that is deliberate: Today never plans (the
     * schedule is edited on the calendar, §12-B), and the calendar itself passes null for a
     * day already gone, because a plan cannot be made after the fact (§20.1). Both come out
     * as the same thing on screen — the item is simply not in the menu — which is the state
     * the owner reads: a control that is offered and then refused says the app plans in the
     * past and loses the plan.
     */
    onPlanSession: (() -> Unit)? = null,
) {
    val colors = LocalGachiColors.current

    /** Whether the question "what shall this one be called" is on screen. */
    var naming by remember(day.date) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Cards)) {
        if (day.isEmpty) {
            /*
             * ONE SENTENCE, and no plaque around it. The dashed box repeated in a second voice
             * what the section heading above already says, and its dashes (the axis colour) were
             * all but invisible in the dark theme — a frame drawn around nothing. What is left
             * says what is empty, which is the whole job of an empty state, and the button under
             * it is the only thing on the screen to press.
             */
            Text(
                if (day.canRecord) {
                    "Nothing planned or recorded for today."
                } else {
                    // a day that has not happened cannot have "nothing recorded" held
                    // against it, so it is not told that it does. It is also only ever drawn
                    // on the calendar (Today is never in the future), which is why the
                    // sentence can name the way in that sits under this list — an item
                    // of the Add menu since the redraw, no longer a button of its own
                    "Nothing planned for this day. Add a planned session below and it " +
                        "appears here."
                },
                fontSize = TextSize.Body,
                color = colors.inkSecondary,
                modifier = Modifier.padding(top = Spacing.Line),
            )
        }

        day.cards.forEach { card -> DayCardRow(card, date, actions) }

        /*
         * A day ahead cannot be recorded on but can be planned for, so the button is drawn
         * for either reason — and each of the two halves of the menu decides itself whether
         * it is there. A future day where the button was gated on `canRecord` alone would
         * have no way in at all, which is what the old separate "Plan a session" button was
         * quietly covering for.
         */
        if (day.canRecord || onPlanSession != null) {
            AddMenuButton(
                onWorkout = { naming = true }.takeIf { day.canRecord },
                onSingleEntry = { actions.logSingleEntry(date) }.takeIf { day.canRecord },
                onPlanSession = onPlanSession,
                // an empty day has exactly one thing to do on it, so the one button says so by
                // being filled; with cards above it, it is the quiet second option it always was
                filled = day.isEmpty,
                /*
                 * There is at most ONE draft in the app, so starting a second workout would
                 * replace the one being composed — silently, which is the very thing §23.A3
                 * rules out. While its card is on this day the item is offered and refused
                 * rather than hidden: a menu that changes shape is harder to read than one
                 * that says why.
                 */
                workoutEnabled = day.cards.none { it.kind == DayCardKind.DRAFT },
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
            suggestions = pastWorkoutNames,
        )
    }
}

/**
 * One card: a coloured spine, the name, two lines of meta, an optional record, and the action.
 *
 * The kind is never said in colour alone — the spine follows it, but the first meta line
 * underneath carries the same information in words, which is the rule everywhere else in this
 * app. The DRAFT spine is dashed as well as coloured, because it shares its colour with the
 * plan and "assembled but not yet a fact" is exactly what a dashed line says.
 *
 * ── One way in, and a sign that there is a menu ─────────────────────────────────
 * A button appears only where it does something the card body does NOT. That is the plan, where
 * the body opens the plan and the button starts the workout. On a running workout and on a
 * draft the button used to call the very same handler as a tap on the card — two ways to do one
 * thing, and the word "Continue" printed twice on one screen — so it is gone and a chevron says
 * that the card opens. The three-dot button next to it opens the menu the long press has always
 * raised; the press still works, and now there is something on the card that says so.
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

        DayCardAction.RESUME -> ({ actions.resumeDraft() })

        DayCardAction.NONE -> null
    }
    val spine = when {
        // a plan whose hour went by with nothing recorded is not the same news as one still
        // ahead, and it was drawn in the same accent as one — see [DayCard.missed]
        card.missed -> colors.serious
        card.kind == DayCardKind.PLANNED -> colors.accent
        card.kind == DayCardKind.RUNNING -> colors.good
        card.kind == DayCardKind.DONE -> colors.inkSecondary
        card.kind == DayCardKind.SINGLE -> colors.inkMuted
        // the same accent a plan gets: both are things not done yet. Told apart by the DASH
        // below and by the word "draft" in the meta line — never by colour alone
        else -> colors.accent
    }

    /** The workout this card is about, for the actions a long press offers. */
    val workoutId = card.workoutId.takeIf {
        card.kind == DayCardKind.RUNNING || card.kind == DayCardKind.DONE
    }
    /** Whether this card can be removed whole, and under which label. */
    val deleteLabel: String? = when {
        workoutId != null -> "Delete workout"
        card.kind == DayCardKind.SINGLE && card.entryIds.isNotEmpty() ->
            if (card.entryIds.size == 1) "Delete entry" else "Delete these entries"

        card.kind == DayCardKind.DRAFT -> "Discard draft"
        else -> null
    }
    var confirmingDelete by remember(card.key) { mutableStateOf(false) }
    var renaming by remember(card.key) { mutableStateOf(false) }
    val menu = buildList {
        // the harmless one first: a menu whose top entry deletes is a menu that gets
        // dismissed rather than read
        if (workoutId != null) {
            add(ItemAction(if (card.workoutName == null) "Name it" else "Rename") { renaming = true })
        }
        /*
         * The plan's own two, and ONLY where the caller passes them: Today is the screen you
         * stand in the gym with, and rewriting the schedule mid-set is the calendar's job.
         *
         * They are entries of this menu rather than a pencil and a bin on the row, which is
         * how a planned card gets a long press it never had — the same actions, reached the
         * way every other card reaches its own.
         */
        val edit = actions.editSlot
        val delete = actions.deleteSlot
        val plannedSlot = card.slotId
        if (card.kind == DayCardKind.PLANNED && plannedSlot != null && edit != null && delete != null) {
            add(ItemAction("Edit plan") { edit(plannedSlot) })
            add(ItemAction("Delete plan", destructive = true) { delete(plannedSlot) })
        }
        deleteLabel?.let { add(ItemAction(it, destructive = true) { confirmingDelete = true }) }
    }

    ItemActions(
        title = card.title,
        actions = menu,
        onTap = onTap,
        modifier = Modifier.fillMaxWidth(),
    ) { press, openMenu ->
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Card))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.Card))
            .then(press)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spine(spine, dashed = card.kind == DayCardKind.DRAFT)
        Column(
            Modifier.weight(1f).padding(Spacing.Inset),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Text(
                card.title,
                fontSize = TextSize.Title,
                fontWeight = FontWeight.SemiBold,
                // a workout already done steps back a shade; everything else is at full ink
                color = if (card.kind == DayCardKind.DONE) {
                    colors.inkSecondary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(card.metaLine, fontSize = TextSize.Meta, color = colors.inkSecondary)
            if (card.detailLine.isNotEmpty()) {
                Text(card.detailLine, fontSize = TextSize.Meta, color = colors.inkMuted)
            }
            // a record is stated in words, never by colour alone — and it is no longer the
            // palest thing on the card, which is what a piece of good news must never be
            card.record?.let { RecordChip(it) }
        }

        Row(
            Modifier.padding(start = Spacing.Line, end = Spacing.Inset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
        ) {
            /*
             * A button ONLY where it does something the card body does not.
             *
             * That is the plan and nothing else: the card opens the plan, the button starts the
             * workout — deliberately two different acts since a plan for the evening became a
             * workout an hour early. On the running workout and on the draft the button called
             * the card's own handler, so it was a second way to do one thing, and it printed
             * "Continue" twice on one screen. Those two now show the chevron below.
             */
            val startSlot = card.slotId.takeIf { card.action == DayCardAction.START }
            if (startSlot != null) {
                Button(
                    onClick = { actions.startFromPlan(startSlot, date) },
                    shape = RoundedCornerShape(Radius.Small),
                    contentPadding = PaddingValues(horizontal = Spacing.Inset),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Start", fontSize = TextSize.Meta, fontWeight = FontWeight.SemiBold) }
            }
            /*
             * The same three dots on every card that has a menu, the plan included.
             *
             * On the calendar the plan's menu holds editing and deleting it, which used to be
             * a pencil and a BIN drawn here in the open — the bin eight points from "Start",
             * and the price of the mis-tap a deleted plan (rule 3). Behind the menu the
             * deletion is under a divider, in the critical colour, and still asks.
             */
            if (menu.isNotEmpty()) {
                RowIcon(Icons.Filled.MoreVert, "Actions for \"${card.title}\"", colors.inkMuted) {
                    openMenu()
                }
            }
            /*
             * Drawn only where a tap really leads somewhere. A weigh-in card names no
             * catalog exercise and so has no breakdown behind it; a chevron there would be
             * a promise the card cannot keep.
             */
            if (startSlot == null && onTap != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.inkMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
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

    if (confirmingDelete) {
        // the card's own two lines, so the dialog is unmistakably about the card that was
        // pressed and not about the one next to it
        val subject = listOf(card.title, card.detailLine).filter { it.isNotEmpty() }
            .joinToString(" - ")
        if (card.kind == DayCardKind.DRAFT) {
            ConfirmRemoveDialog(
                title = "Discard this draft?",
                subject = subject,
                // no REMOVAL_IS_REVERSIBLE here, and that is the honest difference: a deleted
                // entry is a row that can be brought back, a discarded draft was never written
                explanation = "It was never started, so nothing recorded goes with it - but " +
                    "the exercises picked for it are not kept anywhere and cannot be brought " +
                    "back.",
                confirmLabel = "Discard",
                onConfirm = {
                    confirmingDelete = false
                    actions.discardDraft()
                },
                onDismiss = { confirmingDelete = false },
            )
        } else if (workoutId != null) {
            ConfirmRemoveDialog(
                title = "Delete this workout?",
                subject = subject,
                explanation = "Everything recorded in it goes too - its sets stop counting " +
                    "towards volume, records and the streak. $REMOVAL_IS_REVERSIBLE",
                confirmLabel = "Delete",
                onConfirm = {
                    confirmingDelete = false
                    actions.deleteWorkout(workoutId)
                },
                onDismiss = { confirmingDelete = false },
            )
        } else {
            val n = card.entryIds.size
            ConfirmRemoveDialog(
                title = if (n == 1) "Delete this entry?" else "Delete these entries?",
                subject = subject,
                explanation = "${if (n == 1) "It stops" else "All $n stop"} counting towards " +
                    "volume, records and the streak, and any rest still counting for this " +
                    "exercise stops with them. $REMOVAL_IS_REVERSIBLE",
                confirmLabel = "Delete",
                onConfirm = {
                    confirmingDelete = false
                    actions.deleteSingleEntries(card.entryIds, card.exerciseId)
                },
                onDismiss = { confirmingDelete = false },
            )
        }
    }
}

/**
 * The coloured edge of a card, and — for a draft — a DASHED one.
 *
 * Drawn rather than filled because there is no dashed fill: the dash is a stroked line down the
 * middle of a four-point-wide box, which is the same four points of colour with gaps in it. A
 * draft is a plan that has been assembled and has not become a fact, and a broken line is what
 * that looks like without asking for a colour the palette does not have.
 */
@Composable
private fun Spine(color: Color, dashed: Boolean) {
    val shape = RoundedCornerShape(topStart = Radius.Card, bottomStart = Radius.Card)
    Box(
        Modifier
            .width(SPINE_WIDTH)
            .fillMaxHeight()
            .heightIn(min = 62.dp)
            .clip(shape)
            .drawBehind {
                if (!dashed) {
                    drawRect(color)
                } else {
                    val on = SPINE_DASH.toPx()
                    drawLine(
                        color = color,
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = size.width,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, on)),
                    )
                }
            }
    )
}

private val SPINE_WIDTH = 4.dp

/** The dash and the gap of a draft's spine — equal, so it reads as a broken line and not dots. */
private val SPINE_DASH = 6.dp

/**
 * The record of a card: a quiet word and a loud number, on a recessed chip.
 *
 * It used to be the palest line on the card — 11 sp in a fill colour — which broke the rule that
 * good news is never fainter than bad. The chip gives it a place of its own, the word stays
 * secondary, and the value is set at body size in full ink: after the title it is the darkest
 * thing here, which is what it deserves to be.
 */
@Composable
private fun RecordChip(record: DayCardRecord) {
    val colors = LocalGachiColors.current
    Row(
        Modifier
            .padding(top = Spacing.Tight)
            .clip(RoundedCornerShape(Radius.Small))
            .background(colors.recessed)
            .padding(horizontal = Spacing.Line, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            record.label,
            fontSize = TextSize.Meta,
            fontWeight = FontWeight.SemiBold,
            color = colors.goodText,
        )
        Text(
            record.value,
            fontSize = TextSize.Body,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** An icon that acts. 48 dp of target under an 18 dp drawing — the platform floor, not the mock's. */
@Composable
private fun RowIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * "Add", and then the question of what.
 *
 * The answers are not the same size of thing and the wording says so: a WORKOUT is the
 * session you are about to do, a SINGLE ENTRY is one exercise recorded on its own — the
 * stretching done in front of the television, which the model has always allowed and the
 * screens used not to show as anything in particular — and a PLANNED SESSION is not a record
 * at all but an appointment, which is why it sits below a divider.
 *
 * ── Why the plan is in here and not on a button of its own ──────────────────────
 * The calendar used to draw a second full-width button under this one, so a day offered
 * three ways to begin something: "Add" opening a menu of two, and "Plan a session" beside
 * it. Rule 1 of `design-system/app-next/SYSTEM.md` — one action, one button — and the
 * redraw's answer is one button and one menu of three. The cost, stated plainly, is that
 * this component is shared with Today and now has to be TOLD whether planning is on offer:
 * see `onPlanSession` on [DayCardList].
 *
 * Every lambda here is nullable and a null one is simply not in the menu. The alternative,
 * drawing a disabled item, is right for "a workout is already open" (a state that will pass,
 * and the reason is worth reading) and wrong for "this day is in the past" (a state that
 * never passes).
 */
@Composable
private fun AddMenuButton(
    onWorkout: (() -> Unit)?,
    onSingleEntry: (() -> Unit)?,
    onPlanSession: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** False while a draft is already being composed — see the call site. */
    workoutEnabled: Boolean = true,
    /** Filled rather than outlined, for the day where this is the only action on screen. */
    filled: Boolean = false,
) {
    val colors = LocalGachiColors.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        val shape = RoundedCornerShape(Radius.Small)
        val body: @Composable RowScope.() -> Unit = {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Add", fontSize = TextSize.Body, modifier = Modifier.padding(start = Spacing.Line))
            // the chevron says the button opens a menu rather than doing the thing
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.padding(start = Spacing.Tight).size(16.dp),
            )
        }
        val size = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        if (filled) {
            Button(onClick = { open = true }, shape = shape, modifier = size, content = body)
        } else {
            OutlinedButton(onClick = { open = true }, shape = shape, modifier = size, content = body)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onWorkout != null) {
                DropdownMenuItem(
                    text = {
                        Text(if (workoutEnabled) "Workout" else "Workout - one is already open")
                    },
                    enabled = workoutEnabled,
                    onClick = {
                        open = false
                        onWorkout()
                    },
                )
            }
            if (onSingleEntry != null) {
                DropdownMenuItem(
                    text = { Text("Single entry") },
                    onClick = {
                        open = false
                        onSingleEntry()
                    },
                )
            }
            if (onPlanSession != null) {
                // an appointment, not a record: the divider is the difference between the
                // two kinds of answer, not decoration
                if (onWorkout != null || onSingleEntry != null) {
                    HorizontalDivider(
                        color = colors.grid,
                        modifier = Modifier.padding(vertical = Spacing.Tight),
                    )
                }
                DropdownMenuItem(
                    text = { Text("Planned session") },
                    onClick = {
                        open = false
                        onPlanSession()
                    },
                )
            }
        }
    }
}
