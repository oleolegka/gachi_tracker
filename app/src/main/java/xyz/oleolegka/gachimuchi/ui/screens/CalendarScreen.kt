package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.DayState
import xyz.oleolegka.gachimuchi.domain.DayStatus
import xyz.oleolegka.gachimuchi.domain.activeDays
import xyz.oleolegka.gachimuchi.domain.planVsFact
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.displayName
import xyz.oleolegka.gachimuchi.ui.fmtDay
import xyz.oleolegka.gachimuchi.ui.fmtMonth
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.weekdayShort
import java.time.LocalDate

/**
 * Calendar (§12-B): a monthly grid of FULL WEEKS (days of the neighbouring months are
 * real days, not empty cells) plus the agenda of the selected day.
 *
 * The day status is ternary — done / missed / unplanned — plus "planned" and "empty".
 * It is computed by the domain function planVsFact at DAY granularity, not per slot
 * (see the caveat in Schedule.kt): a day with two slots and one workout counts as fully
 * done.
 *
 * Slot editing is NOT here yet — the plan is displayed but not editable; that is the
 * next step, together with the workout logging screen.
 */
@Composable
fun CalendarScreen(state: UiState, today: LocalDate, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    var monthOffset by rememberSaveable { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(today.toString()) }

    val month = remember(monthOffset, today) { today.withDayOfMonth(1).plusMonths(monthOffset.toLong()) }
    // full weeks: from the Monday of the week holding the 1st to the Sunday of the week
    // holding the last day of the month
    val gridStart = remember(month) { month.minusDays(((month.dayOfWeek.value + 6) % 7).toLong()) }
    val gridEnd = remember(month) {
        val last = month.withDayOfMonth(month.lengthOfMonth())
        last.plusDays((7 - last.dayOfWeek.value).toLong())
    }

    val days: List<DayStatus> = remember(state.events, state.slots, gridStart, gridEnd, today) {
        val active = activeDays(state.events, gridStart.toString(), gridEnd.toString())
        planVsFact(state.slots, active, gridStart, gridEnd, today)
    }
    val byDay = remember(days) { days.associateBy { it.day } }

    val selectedEvents = remember(state.events, selected) {
        readActivities(state.events, dateFrom = selected, dateTo = selected)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { monthOffset-- }) { Text("Back") }
                Text(fmtMonth(month), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { monthOffset++ }) { Text("Forward") }
            }
        }

        item {
            Row(Modifier.fillMaxWidth()) {
                weekdayShort.forEach { d ->
                    Text(
                        d,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        items(count = (days.size + 6) / 7) { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val idx = week * 7 + i
                    val status = days.getOrNull(idx)
                    if (status == null) {
                        Box(Modifier.weight(1f))
                    } else {
                        DayCell(
                            status = status,
                            inMonth = LocalDate.parse(status.day).month == month.month,
                            isToday = status.day == today.toString(),
                            isSelected = status.day == selected,
                            onClick = { selected = status.day },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            val status = byDay[selected]
            Column(Modifier.padding(top = 12.dp)) {
                Text(fmtDay(LocalDate.parse(selected)), style = MaterialTheme.typography.titleMedium)
                Text(
                    stateLabel(status?.state ?: DayState.EMPTY),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.forDayState(status?.state ?: DayState.EMPTY),
                )
            }
        }

        item {
            val status = byDay[selected]
            if (status != null && status.occurrences.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Plan", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                        status.occurrences.forEach { occ ->
                            Text(
                                listOfNotNull(occ.atTime, occ.name).joinToString("  "),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }

        item {
            if (selectedEvents.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Actual", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                        selectedEvents.forEach { ev ->
                            Text(
                                "${ev.form.displayName()} — ${ev.form.summaryLine()}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    status: DayStatus,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    val date = LocalDate.parse(status.day)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.surface else colors.plane)
            .border(
                width = if (isToday) 2.dp else 1.dp,
                color = if (isToday) colors.accent else colors.grid,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                // days of neighbouring months are shown for real, just muted
                color = if (inMonth) MaterialTheme.colorScheme.onSurface else colors.inkMuted,
            )
            // the status colour is repeated as a label in the agenda below — colour alone never carries meaning
            if (status.state != DayState.EMPTY) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(colors.forDayState(status.state))
                )
            }
        }
    }
}

private fun stateLabel(state: DayState): String = when (state) {
    DayState.DONE -> "Done: there was a plan and there was activity"
    DayState.MISS -> "Missed: there was a plan, but no activity"
    DayState.PLAN -> "Planned"
    DayState.EXTRA -> "Unplanned: activity without a slot"
    DayState.EMPTY -> "Nothing planned and nothing recorded"
}
