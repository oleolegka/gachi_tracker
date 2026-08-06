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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.ActivityEvent
import xyz.oleolegka.gachimuchi.domain.ActivityRef
import xyz.oleolegka.gachimuchi.domain.DayState
import xyz.oleolegka.gachimuchi.domain.DayStatus
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.SlotOccurrence
import xyz.oleolegka.gachimuchi.domain.activeDays
import xyz.oleolegka.gachimuchi.domain.activitiesByDay
import xyz.oleolegka.gachimuchi.domain.planVsFact
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.repeatBadge
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.DashedNote
import xyz.oleolegka.gachimuchi.ui.components.DeleteSlotDialog
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.SectionHeader
import xyz.oleolegka.gachimuchi.ui.components.SlotEditorDialog
import xyz.oleolegka.gachimuchi.ui.displayName
import xyz.oleolegka.gachimuchi.ui.fmtMonth
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.weekdayShort
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Calendar (§12-B): a compact month navigator over a scrolling agenda of days.
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
 * The dots under a date are ACTIVITIES, coloured by form and capped at three — a row of
 * eight dots stops being countable, and the count is the only thing the dots are for.
 *
 * ── Editing happens on the selected day, in a dialog ────────────────────────────
 * "Plan a session" sits under the agenda and always means "on the day above it", so the
 * day never has to be picked twice; each planned row carries its own pencil and bin. The
 * editor is [SlotEditorDialog] and the confirmation [DeleteSlotDialog] — both are dialogs
 * so the grid stays visible behind them, which is the context for "which day is this".
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
    modifier: Modifier = Modifier,
    // no default: a screen whose Save quietly does nothing is worse than one that fails
    // to compile, and there is exactly one caller
    onSaveSlot: (SlotDraft, Long?) -> Unit,
    onDeleteSlot: (Long) -> Unit,
) {
    val colors = LocalGachiColors.current
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

    val days: List<DayStatus> = remember(state.events, state.slots, gridStart, gridEnd, today) {
        val active = activeDays(state.events, gridStart.toString(), gridEnd.toString())
        planVsFact(state.slots, active, gridStart, gridEnd, today)
    }
    val activities = remember(state.events, gridStart, gridEnd) {
        activitiesByDay(state.events, gridStart.toString(), gridEnd.toString())
    }
    val formOf = remember(state.exercises) { { id: Long? -> state.formOf(id) } }

    val selectedDate = remember(selected) { runCatching { LocalDate.parse(selected) }.getOrDefault(today) }
    val selectedStatus = remember(days, selected) { days.firstOrNull { it.day == selected } }
    val selectedFacts = remember(state.events, selected) {
        readActivities(
            state.events, dateFrom = selected, dateTo = selected,
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
                activities = activities,
                formOf = formOf,
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
                DayAgenda(
                    status = selectedStatus,
                    facts = selectedFacts,
                    formOf = formOf,
                    onEdit = { editing = SlotEditorTarget(it, selectedDate) },
                    onDelete = { deleting = it },
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
    activities: Map<String, List<ActivityRef>>,
    formOf: (Long?) -> ExerciseForm?,
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
                            DayCell(
                                status = status,
                                dots = activities[status.day].orEmpty(),
                                formOf = formOf,
                                inMonth = LocalDate.parse(status.day).month == month.month,
                                isToday = status.day == today.toString(),
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
                LegendItem("unplanned", colors.inkMuted)
                LegendItem("dots = activities", colors.forForm(ExerciseForm.STRENGTH))
            }
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
 * One day: the number, and up to three dots for the activities logged on it.
 *
 * The day's plan/fact STATE is not colour on the cell — it is the status word in the
 * agenda below. Painting five states onto a 40 dp square would need five colours nobody
 * can decode without a legend they cannot see while looking at the grid.
 */
@Composable
private fun DayCell(
    status: DayStatus,
    dots: List<ActivityRef>,
    formOf: (Long?) -> ExerciseForm?,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    val date = LocalDate.parse(status.day)
    Box(
        modifier
            .aspectRatio(1f / 1.06f)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (isSelected) Modifier.background(colors.accent) else Modifier)
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
            Row(
                Modifier.height(6.dp).padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dots.take(3).forEach { activity ->
                    val form = formOf(activity.exerciseId)
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(form?.let { colors.forForm(it) } ?: colors.inkMuted)
                    )
                }
            }
        }
    }
}

/** The agenda of the selected day: the plan, its status, and what was actually recorded. */
@Composable
private fun DayAgenda(
    status: DayStatus?,
    facts: List<ActivityEvent>,
    formOf: (Long?) -> ExerciseForm?,
    onEdit: (Slot) -> Unit,
    onDelete: (Slot) -> Unit,
) {
    val colors = LocalGachiColors.current
    val occurrences = status?.occurrences.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (occurrences.isEmpty() && facts.isEmpty()) {
            DashedNote("Nothing planned and nothing recorded")
            return@Column
        }

        occurrences.forEach { occurrence ->
            SlotRow(
                occurrence = occurrence,
                state = status?.state ?: DayState.PLAN,
                onEdit = { onEdit(occurrence.slot) },
                onDelete = { onDelete(occurrence.slot) },
            )
        }

        if (facts.isEmpty() && occurrences.isNotEmpty()) {
            DashedNote("Planned, but nothing recorded yet")
        }

        facts.forEach { event ->
            val form = formOf(event.form.exerciseId)
            AgendaRow(
                eyebrow = form?.title?.uppercase() ?: "ACTIVITY",
                accent = form?.let { colors.forForm(it) } ?: colors.inkMuted,
                name = event.form.displayName(),
                meta = event.form.summaryLine(),
                statusLabel = if (occurrences.isEmpty()) "unplanned" else "done",
                statusColor = if (occurrences.isEmpty()) colors.inkMuted else colors.goodText,
            )
        }
    }
}

/**
 * A planned session on the selected day, with the two things that can be done to it.
 *
 * The actions are on the ROW rather than behind a long press or a swipe: a long press is
 * invisible and a swipe would collide with the month grid's horizontal feel. The bin does
 * not delete — it opens [DeleteSlotDialog], because for a repeating slot the answer to
 * "what will this remove" is a sentence, not an icon.
 */
@Composable
private fun SlotRow(
    occurrence: SlotOccurrence,
    state: DayState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalGachiColors.current
    val (label, color) = when (state) {
        DayState.DONE -> "done" to colors.goodText
        DayState.MISS -> "missed" to colors.critical
        DayState.EXTRA -> "unplanned" to colors.inkMuted
        else -> "planned" to colors.inkMuted
    }
    AgendaRow(
        eyebrow = "PLAN",
        accent = colors.accent,
        name = occurrence.name,
        meta = listOfNotNull(occurrence.atTime, repeatBadge(occurrence.slot.repeatRule))
            .joinToString(" - "),
        statusLabel = label,
        statusColor = color,
    ) {
        RowAction(Icons.Filled.Edit, "Edit \"${occurrence.name}\"", colors.inkSecondary, onEdit)
        RowAction(Icons.Filled.Delete, "Delete \"${occurrence.name}\"", colors.critical, onDelete)
    }
}

@Composable
private fun RowAction(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * A row of the agenda: coloured spine, form eyebrow, name, meta, status word, and — for a
 * planned row — the actions on the end. A recorded fact has no actions here: the journal
 * is append-only and is edited on the logging screen, not in the calendar.
 */
@Composable
private fun AgendaRow(
    eyebrow: String,
    accent: Color,
    name: String,
    meta: String,
    statusLabel: String,
    statusColor: Color,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = LocalGachiColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .heightIn(min = 58.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                .background(accent)
        )
        Column(
            Modifier.weight(1f).padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                eyebrow,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = accent,
            )
            Text(
                name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (meta.isNotBlank()) {
                Text(meta, fontSize = 11.sp, color = colors.inkMuted)
            }
        }
        // the status is a WORD, so colour never has to be decoded on its own
        Text(
            statusLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = statusColor,
            modifier = Modifier.padding(end = if (actions == null) 12.dp else 4.dp),
        )
        if (actions != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 4.dp),
            ) { actions() }
        }
    }
}
