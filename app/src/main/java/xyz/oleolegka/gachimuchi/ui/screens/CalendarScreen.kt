package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.DayDots
import xyz.oleolegka.gachimuchi.domain.DayStatus
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.SlotState
import xyz.oleolegka.gachimuchi.domain.activityStamps
import xyz.oleolegka.gachimuchi.domain.calendarDots
import xyz.oleolegka.gachimuchi.domain.DraftSummary
import xyz.oleolegka.gachimuchi.domain.dayCards
import xyz.oleolegka.gachimuchi.domain.journalInstanceCounts
import xyz.oleolegka.gachimuchi.domain.planVsFact
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.DayActions
import xyz.oleolegka.gachimuchi.ui.components.DayCardList
import xyz.oleolegka.gachimuchi.ui.components.DeleteSlotDialog
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.SectionHeader
import xyz.oleolegka.gachimuchi.ui.components.SlotEditorDialog
import xyz.oleolegka.gachimuchi.ui.fmtMonth
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.weekdayShort
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Calendar (§12-B, dots rework 2026-08-10): a compact month navigator over a scrolling
 * agenda of days.
 *
 * ── The grid is a navigator, not the workspace ──────────────────────────────────
 * The month is small and dense on purpose: it exists to jump to a day, and the day's
 * content lives in the agenda underneath, where there is room to read it. A grid big
 * enough to hold the content would fit two weeks on a phone.
 *
 * Weeks are FULL: the days of the neighbouring months are real days with real dots, muted
 * rather than blanked. A calendar that hides them makes the first and last week of every
 * month look like something is missing.
 *
 * ── The cell is a clock, not a verdict ────────────────────────────────────────────
 * It used to carry the day's own [xyz.oleolegka.gachimuchi.domain.DayState] as a coloured
 * wash — and had no honest answer for "two sessions today, one done and one missed": a day
 * is not one verdict, its SLOTS are (reported from the phone, 2026-08-10). The cell now
 * paints nothing but WHEN the day is — one hair lighter for today and what is still to come,
 * one hair darker for what has already happened — and every verdict moved onto the dots.
 *
 * ── A dot is one journal instance, coloured by what it says ─────────────────────
 * A dot is not "an activity" any more: a whole WORKOUT is one dot however many exercises it
 * holds, and a run of entries logged with no workout around it is one dot too — the same
 * units domain/DayCards.kt turns into cards (domain/Analytics.kt's `journalInstanceCounts`).
 * Every one of those is GREEN, whether or not it happened to close a plan — training that
 * was never on the plan still earns its dot. A slot the day's window has already closed with
 * nothing recorded against it draws a RED dot, and a slot still open draws a BLUE one; a slot
 * that IS closed draws no dot of its own, because the green instance that closed it already
 * did (domain/Schedule.kt's `calendarDots` has the full rule). Capped at six, and a seventh
 * does not disappear the way the old three-dot cap silently did — it is counted in a visible
 * "+N" instead.
 *
 * Every dot carries a thin RING. The bug this closes, verbatim from the phone: the selected
 * day's fill and a planned slot's colour are numerically the SAME blue (`accent`), and
 * without a ring a planned dot on the day you have tapped disappears into the cell under it.
 * The ring is not a patch for that one collision — it is what makes every dot legible
 * against every background this screen can put behind it, selection included.
 *
 * ── Every slot carries its own verdict ──────────────────────────────────────────
 * The rule is written out in domain/Schedule.kt and is decided by TIME: the morning gym
 * session can be done while the evening hangboard is still planned, and a session whose
 * time has not come yet is never shown as done.
 *
 * ── The day underneath is the same list Today shows ─────────────────────────────
 * It used to be an agenda of the plan followed by every exercise recorded that day, one row
 * per entry. It is now the SAME CARDS the Today tab draws (ui/components/DayCardList.kt,
 * built by domain/DayCards.kt), differing only in which date is asked for. Two screens
 * answering "what happened on this day" with two implementations is two answers, and the
 * day they disagree is the day the app stops being believed.
 *
 * That also removes the old restriction that a workout could only be logged on TODAY's
 * slots: a card on last Tuesday now starts a workout DATED last Tuesday (§13.6), rather
 * than opening an entry card that would have written today's date onto it.
 *
 * ── Editing happens on the selected day, in a dialog ────────────────────────────
 * "Plan a session" sits under the cards and always means "on the day above it", so the day
 * never has to be picked twice; each planned card carries its own pencil and bin, which
 * only the calendar passes in. The editor is [SlotEditorDialog] and the confirmation
 * [DeleteSlotDialog] — both are dialogs so the grid stays visible behind them, which is the
 * context for "which day is this".
 *
 * Nothing about occurrences is stored: an edit rewrites ONE master row and the whole
 * series moves with it, which is why the save handler also jumps the grid to the day that
 * was planned — a weekly slot moved to another weekday would otherwise quietly vanish from
 * the day the user was looking at.
 */
@Composable
fun CalendarScreen(
    state: UiState,
    today: LocalDate,
    dayActions: DayActions,
    modifier: Modifier = Modifier,
    // no default: a screen whose Save quietly does nothing is worse than one that fails
    // to compile, and there is exactly one caller
    onSaveSlot: (SlotDraft, Long?) -> Unit,
    onDeleteSlot: (Long) -> Unit,
    /** Lets the plan's exercise picker create a row it does not have yet. */
    onCreateExercise: ((new: NewExercise, then: (Long) -> Unit) -> Unit)? = null,
    /** The workout being composed, when there is one — see [DraftSummary]. */
    draft: DraftSummary? = null,
) {
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    // the editor and the confirmation are transient: a half-typed slot is not worth
    // carrying across a rotation, and a confirmation that survives one is worse than gone
    var editing by remember { mutableStateOf<SlotEditorTarget?>(null) }
    var deleting by remember { mutableStateOf<Slot?>(null) }

    val month = remember(monthOffset, today) {
        today.withDayOfMonth(1).plusMonths(monthOffset.toLong())
    }
    // full weeks: the Monday of the week holding the 1st .. the Sunday of the week holding the last
    val gridStart = remember(month) { month.minusDays(((month.dayOfWeek.value + 6) % 7).toLong()) }
    val gridEnd = remember(month) {
        val last = month.withDayOfMonth(month.lengthOfMonth())
        last.plusDays((7 - last.dayOfWeek.value).toLong())
    }

    /*
     * The verdicts depend on the CLOCK, not only on the date: a session at 20:00 is still
     * planned at noon and late by midnight. The clock is read again whenever the data or
     * the screen's own state changes, which covers every way of arriving here — but a
     * screen left open and untouched keeps the reading it was composed with. That is the
     * price of a pure function over a moving "now", and it is one recomposition away from
     * correct.
     */
    val now: LocalDateTime = remember(state.events, state.slots, monthOffset, selected) {
        LocalDateTime.now()
    }

    val days: List<DayStatus> = remember(state.events, state.slots, gridStart, gridEnd, now) {
        val stamps = activityStamps(state.events, gridStart.toString(), gridEnd.toString())
        planVsFact(state.slots, stamps, gridStart, gridEnd, now)
    }
    // one green dot per journal instance, plus a red or blue one for every slot [days] above
    // did not already mark DONE — see calendarDots' own KDoc for why a DONE slot draws none
    val dotsByDay: Map<String, DayDots> = remember(state.events, gridStart, gridEnd, days) {
        val instances = journalInstanceCounts(state.events, gridStart.toString(), gridEnd.toString())
        days.associate { it.day to calendarDots(it, instances[it.day] ?: 0) }
    }

    val selectedDate = remember(selected) { runCatching { LocalDate.parse(selected) }.getOrDefault(today) }
    val selectedDay = remember(state.events, state.slots, selectedDate, today, now, draft) {
        dayCards(state.events, state.slots, selectedDate, today, now, draft)
    }
    // the pencil and the bin belong to the calendar and only to the calendar; the same
    // component draws no icons on Today, where the two lambdas are left null
    val cardActions = remember(dayActions, state.slots, selectedDate) {
        dayActions.copy(
            editSlot = { id ->
                state.slots.firstOrNull { it.id == id }
                    ?.let { editing = SlotEditorTarget(it, selectedDate) }
            },
            deleteSlot = { id -> state.slots.firstOrNull { it.id == id }?.let { deleting = it } },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            MonthNavigator(
                month = month,
                days = days,
                dotsByDay = dotsByDay,
                today = today,
                selected = selected,
                onSelect = { selected = it },
                onPrevious = { monthOffset-- },
                onNext = { monthOffset++ },
            )
        }

        item {
            Column(Modifier.fillMaxWidth()) {
                SectionHeader("Selected day", fmtWeekdayDay(selectedDate))
                DayCardList(
                    day = selectedDay,
                    date = selectedDate,
                    actions = cardActions,
                    pastWorkoutNames = state.pastWorkoutNames,
                )
                PlanButton(
                    onClick = { editing = SlotEditorTarget(null, selectedDate) },
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
        }
    }

    editing?.let { target ->
        SlotEditorDialog(
            initial = target.slot,
            day = target.day,
            // the names already in the plan, most recent first: the second "Gym" is a tap
            suggestions = remember(state.slots) {
                state.slots.asReversed().map { it.name }.distinct().take(6)
            },
            today = today,
            // the catalog the editor's exercise picker searches through
            state = state,
            onCreateExercise = onCreateExercise,
            onSave = { draft ->
                onSaveSlot(draft, target.slot?.id)
                // follow the plan to where it landed, month included
                selected = draft.anchorDate
                runCatching { LocalDate.parse(draft.anchorDate) }.getOrNull()?.let { anchor ->
                    monthOffset = ChronoUnit.MONTHS.between(
                        today.withDayOfMonth(1), anchor.withDayOfMonth(1),
                    ).toInt()
                }
                editing = null
            },
            onDelete = {
                editing = null
                deleting = target.slot
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { slot ->
        DeleteSlotDialog(
            slot = slot,
            onConfirm = {
                onDeleteSlot(slot.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * What the editor is open on: an existing slot, or null for a new one on [day]. The two
 * cannot both be null-checked away — "new slot on the 5th" and "closed" would otherwise be
 * the same state and the dialog would never open for a new slot.
 */
private data class SlotEditorTarget(val slot: Slot?, val day: LocalDate)

/** The one way into the editor for a new slot; it always means "on the day above". */
@Composable
private fun PlanButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("Plan a session", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun MonthNavigator(
    month: LocalDate,
    days: List<DayStatus>,
    dotsByDay: Map<String, DayDots>,
    today: LocalDate,
    selected: String,
    onSelect: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = LocalGachiColors.current
    GachiCard(Modifier.fillMaxWidth(), radius = 18.dp) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous month",
                        tint = colors.inkSecondary,
                    )
                }
                Text(
                    fmtMonth(month),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next month",
                        tint = colors.inkSecondary,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                weekdayShort.forEach { label ->
                    Text(
                        label.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                        color = colors.inkMuted,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            for (week in 0 until (days.size + 6) / 7) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (index in 0 until 7) {
                        val status = days.getOrNull(week * 7 + index)
                        if (status == null) {
                            Box(Modifier.weight(1f))
                        } else {
                            val date = LocalDate.parse(status.day)
                            DayCell(
                                status = status,
                                dots = dotsByDay[status.day] ?: DayDots(emptyList(), 0),
                                inMonth = date.month == month.month,
                                isToday = status.day == today.toString(),
                                isPast = date.isBefore(today),
                                isSelected = status.day == selected,
                                onClick = { onSelect(status.day) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = colors.grid, modifier = Modifier.padding(top = 12.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LegendItem("done", colors.good)
                LegendItem("missed", colors.critical)
                LegendItem("planned", colors.accent)
            }
            Text(
                "A dot is a whole session or entry. Up to six a day; +N is the rest.",
                fontSize = 10.sp,
                color = colors.inkMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    val colors = LocalGachiColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, fontSize = 10.sp, color = colors.inkSecondary, maxLines = 1)
    }
}

/**
 * One day: the number, a background that says only WHEN the day is, and its dots.
 *
 * ── The background carries no verdict any more ────────────────────────────────
 * Just the two close shades the file header describes — [isPast] picks between them, and
 * neither is [status] at all, which is the whole point of the rework: nothing here reads
 * [DayStatus.state] any more, because a single tint had no way to say "one done, one missed".
 *
 * ── Two named tones, because a composited sliver of black was not visible ────────
 * This used to be `surface` for a day ahead and five percent black over `surface` for a day
 * gone. Five percent of the surface's OWN brightness is thirteen levels out of 255 in the
 * light theme and one and a half in the dark one, where the surface is already near black —
 * reported from the phone as two tones nobody can tell apart (2026-08-11), which in the dark
 * theme they very nearly were not.
 *
 * The pair is now stated per theme ([GachiColors.calendarAhead] / [GachiColors.calendarGone]),
 * each tone straddling its own surface, so "ahead is lighter" survives BOTH themes — the trap
 * a single alpha was chosen to dodge in the first place, and the one it dodged by making the
 * difference invisible instead.
 */
@Composable
private fun DayCell(
    status: DayStatus,
    dots: DayDots,
    inMonth: Boolean,
    isToday: Boolean,
    isPast: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    val date = LocalDate.parse(status.day)
    val cellBackground = if (isPast) colors.calendarGone else colors.calendarAhead
    Box(
        modifier
            .aspectRatio(1f / 1.06f)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            // the selection is the strongest signal on the grid and always wins
            .background(if (isSelected) colors.accent else cellBackground)
            .then(
                if (isToday && !isSelected) {
                    Modifier.border(1.5.dp, colors.accent, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                fontSize = 13.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isSelected -> Color.White
                    isToday -> colors.accent
                    // days of the neighbouring months are real days, just muted
                    inMonth -> MaterialTheme.colorScheme.onSurface
                    else -> colors.inkMuted.copy(alpha = 0.5f)
                },
            )
            DotRow(status.day, dots)
        }
    }
}

/**
 * Up to [xyz.oleolegka.gachimuchi.domain.MAX_CALENDAR_DOTS] coloured dots, plus a "+N" mark
 * when [DayDots.overflow] says more were left off.
 *
 * ── The ring around every dot, not only the ones that collide ────────────────────
 * The selected cell fills solid `accent`, and a PLANNED dot is also `accent` — same colour,
 * numerically. A ring drawn on every dot regardless still reads as a ring on a background
 * that already contrasts fine, so there is nothing to lose by not special-casing the
 * collision, and everything to lose by missing a background this screen adds later that
 * collides with green or red instead.
 *
 * A fixed-height row, always: with zero dots it is still as tall as it is with six, so the
 * day number sits at the same height in every cell of the grid.
 *
 * Each dot carries a [day]-qualified content description, which is what
 * ui/screens/CalendarScreenTest.kt reads to tell one day's dots from another's — there is
 * nothing visible on a dot to query by otherwise, and a colour is not text a test can assert.
 */
@Composable
private fun DotRow(day: String, dots: DayDots) {
    val colors = LocalGachiColors.current
    val ring = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Row(
        Modifier.heightIn(min = 9.dp).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dots.states.forEach { state ->
            Box(
                Modifier
                    .size(4.5.dp)
                    .clip(CircleShape)
                    .background(colors.forSlotState(state))
                    .border(0.6.dp, ring, CircleShape)
                    .semantics { contentDescription = "$day dot: ${dotDescription(state)}" }
            )
        }
        if (dots.overflow > 0) {
            Text(
                "+${dots.overflow}",
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                color = colors.inkMuted,
                modifier = Modifier
                    .padding(start = 1.dp)
                    .semantics { contentDescription = "$day: ${dots.overflow} more" },
            )
        }
    }
}

/** The word the legend uses for [state] — the same three [MonthNavigator] names underneath. */
private fun dotDescription(state: SlotState): String = when (state) {
    SlotState.DONE -> "done"
    SlotState.MISS -> "missed"
    SlotState.PLAN -> "planned"
}
