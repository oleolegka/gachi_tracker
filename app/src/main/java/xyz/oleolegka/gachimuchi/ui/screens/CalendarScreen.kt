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
import androidx.compose.material3.TextButton
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
import xyz.oleolegka.gachimuchi.domain.FACT_TYPES
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.SlotState
import xyz.oleolegka.gachimuchi.domain.SlotStatus
import xyz.oleolegka.gachimuchi.domain.activitiesByDay
import xyz.oleolegka.gachimuchi.domain.activityStamps
import xyz.oleolegka.gachimuchi.domain.normPhrase
import xyz.oleolegka.gachimuchi.domain.offersLogging
import xyz.oleolegka.gachimuchi.domain.planVsFact
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.repeatBadge
import xyz.oleolegka.gachimuchi.ui.LocalOpenLogging
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
import java.time.LocalDateTime
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
 * eight dots stops being countable, and the count is the only thing the dots are for. The
 * cell's own tint is the day's PLAN verdict, which is a summary of its slots: done, missed
 * or still outstanding. The two say different things — dots are what happened, the tint is
 * what it means against the plan — and the legend under the grid names both.
 *
 * ── Every slot carries its own verdict ──────────────────────────────────────────
 * The rule is written out in domain/Schedule.kt and is decided by TIME: the morning gym
 * session can be done while the evening hangboard is still planned, and a session whose
 * time has not come yet is never shown as done. A session that is not done yet carries a
 * "Log" button, because a plan one cannot act on is a list of reproaches. That button is a
 * WORD next to two icons: the row already carries a pencil and a bin, and "log a workout"
 * one mis-tap away from "delete the plan" is not a trade worth making.
 *
 * The button appears on TODAY's sessions only. The logging screen writes entries for
 * today, so offering it on last Tuesday's missed slot would quietly record the workout on
 * the wrong day — the very class of bug the per-slot status exists to remove.
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
                    today = today,
                    exerciseNamed = { state.exerciseNamed(it) },
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
            // the catalog the editor's exercise picker searches through
            state = state,
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
                LegendItem("planned", colors.accent)
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
 * One day: the number, a plan verdict, and up to three dots for the activities logged on it.
 *
 * The verdict is a WASH of the state's colour rather than the colour itself — the number
 * has to stay readable, and a saturated square would shout louder than the dots, which are
 * the finer information. Only the three verdicts the legend names are painted: an
 * unplanned day is a plain cell with dots on it, which is exactly what "activity, no plan"
 * looks like, and an empty day is a plain cell with nothing.
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
    val wash = when (status.state) {
        DayState.DONE, DayState.MISS, DayState.PLAN -> colors.forDayState(status.state).copy(alpha = 0.16f)
        DayState.EXTRA, DayState.EMPTY -> null
    }
    Box(
        modifier
            .aspectRatio(1f / 1.06f)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                when {
                    // the selection is the strongest signal on the grid and always wins
                    isSelected -> Modifier.background(colors.accent)
                    wash != null -> Modifier.background(wash)
                    else -> Modifier
                }
            )
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

/**
 * The agenda of the selected day: every planned session with its OWN verdict, and what was
 * actually recorded.
 *
 * A recorded entry is labelled by whether it closed a slot ([DayStatus.closedByActivityIds]),
 * not by whether the day happened to have a plan at all: on a day with two slots and one
 * workout, the workout says "done" and the slot it did not reach says "missed".
 */
@Composable
private fun DayAgenda(
    status: DayStatus?,
    facts: List<ActivityEvent>,
    formOf: (Long?) -> ExerciseForm?,
    today: LocalDate,
    exerciseNamed: (String) -> Long?,
    onEdit: (Slot) -> Unit,
    onDelete: (Slot) -> Unit,
) {
    val colors = LocalGachiColors.current
    val openLogging = LocalOpenLogging.current
    val slots = status?.slots.orEmpty()
    val closed = status?.closedByActivityIds.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (slots.isEmpty() && facts.isEmpty()) {
            DashedNote("Nothing planned and nothing recorded")
            return@Column
        }

        slots.forEach { slotStatus ->
            val slot = slotStatus.occurrence.slot
            SlotRow(
                status = slotStatus,
                // the rule lives in the domain: logging writes today's entries (offersLogging)
                onLog = if (slotStatus.offersLogging(today)) {
                    { openLogging(exerciseNamed(slotStatus.name)) }
                } else {
                    null
                },
                onEdit = { onEdit(slot) },
                onDelete = { onDelete(slot) },
            )
        }

        if (facts.isEmpty() && slots.isNotEmpty()) {
            DashedNote("Planned, but nothing recorded yet")
        }

        facts.forEach { event ->
            val form = formOf(event.form.exerciseId)
            // a weigh-in is not training (FACT_TYPES), so it is never judged against a plan
            val judged = event.type in FACT_TYPES
            AgendaRow(
                eyebrow = form?.title?.uppercase() ?: "ACTIVITY",
                accent = form?.let { colors.forForm(it) } ?: colors.inkMuted,
                name = event.form.displayName(),
                meta = event.form.summaryLine(),
                statusLabel = when {
                    event.id in closed -> "done"
                    !judged -> "logged"
                    else -> "unplanned"
                },
                statusColor = if (event.id in closed) colors.goodText else colors.inkMuted,
            )
        }
    }
}

/**
 * A planned session on the selected day, with its verdict and the things that can be done
 * to it.
 *
 * The actions are on the ROW rather than behind a long press or a swipe: a long press is
 * invisible and a swipe would collide with the month grid's horizontal feel. The bin does
 * not delete — it opens [DeleteSlotDialog], because for a repeating slot the answer to
 * "what will this remove" is a sentence, not an icon.
 *
 * "Log" is TEXT while edit and delete are icons, and that asymmetry is the point: three
 * icons of the same size in a row put "record a workout" a thumb's width from "delete the
 * plan", and the two are much too different to be one mis-tap apart.
 */
@Composable
private fun SlotRow(
    status: SlotStatus,
    onLog: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalGachiColors.current
    val (label, color) = when (status.state) {
        SlotState.DONE -> "done" to colors.goodText
        SlotState.MISS -> "missed" to colors.critical
        SlotState.PLAN -> "planned" to colors.inkMuted
    }
    AgendaRow(
        eyebrow = "PLAN",
        accent = colors.accent,
        name = status.name,
        meta = listOfNotNull(status.atTime, repeatBadge(status.slot.repeatRule))
            .joinToString(" - "),
        statusLabel = label,
        statusColor = color,
    ) {
        if (onLog != null) {
            TextButton(onClick = onLog, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("Log", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        RowAction(Icons.Filled.Edit, "Edit \"${status.name}\"", colors.inkSecondary, onEdit)
        RowAction(Icons.Filled.Delete, "Delete \"${status.name}\"", colors.critical, onDelete)
    }
}

/**
 * The catalog exercise called exactly what the slot is called, if there is one.
 *
 * A slot is a SESSION ("Gym", "Hangboard") and the model has no slot -> exercise link, so
 * this is a lucky coincidence rather than a feature: normally it finds nothing and the
 * logging screen opens with the exercise still to be picked. Only the same name counts,
 * after the normalization the catalog itself uses — anything looser would point the entry
 * card at an exercise the user did not plan.
 */
private fun UiState.exerciseNamed(name: String): Long? {
    val want = normPhrase(name) ?: return null
    return exercises.firstOrNull { normPhrase(it.name) == want }?.id
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
