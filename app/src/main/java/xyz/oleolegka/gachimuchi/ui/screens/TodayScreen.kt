package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.RecordHit
import xyz.oleolegka.gachimuchi.domain.SessionGroup
import xyz.oleolegka.gachimuchi.domain.ValueFormat
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.EmptyState
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.RecordBadge
import xyz.oleolegka.gachimuchi.ui.components.SectionHeader
import xyz.oleolegka.gachimuchi.ui.components.StatCard
import xyz.oleolegka.gachimuchi.ui.fmtDelta
import xyz.oleolegka.gachimuchi.ui.fmtShortDay
import xyz.oleolegka.gachimuchi.ui.fmtValueParts
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today (§12-C): what was done today, and the records that were set doing it.
 *
 * ── One card per exercise, not per "workout" ────────────────────────────────────
 * The mock-up groups the day into named sessions ("Gym, 18:20 - 19:35" holding four
 * exercises). The data model has no such entity and deliberately so: `domain/Session.kt`
 * defines a session as "everything recorded on this date", with no start event, no finish
 * event and no name. Inventing a grouping here would mean guessing where one workout ended
 * and the next began from timestamps alone, and guessing wrong on the day someone trains
 * twice. So a card is one EXERCISE, its header carries the real time range of its entries,
 * and the visual shape of the mock-up survives while the claim it makes stays true.
 *
 * Records are recomputed by reducing the journal, the same code path the logging screen
 * uses — the two screens cannot disagree about what counts as a record.
 */
@Composable
fun TodayScreen(
    state: UiState,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val iso = today.toString()
    val colors = LocalGachiColors.current
    val session = remember(state.events, iso) { buildSession(state.events, iso) }
    val records = remember(session) { todaysRecords(session) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        // no allowance for the log button here: the scaffold reserves it for every tab
        contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            /*
             * The date, and nothing beside it.
             *
             * There used to be a "Demo data" button here, one tap from the primary screen,
             * with no confirmation, which poured synthetic sets straight into the journal —
             * and because the catalog deduplicates by name, those sets landed on the user's
             * own exercises. The one screen that answers "what did I do today" was the one
             * screen that could make that answer untrue by accident. It now lives in
             * Settings, behind a question, with a way back out.
             */
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    today.format(weekdayDateFormat),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(" - ${today.year}", fontSize = 13.sp, color = colors.inkSecondary)
            }
        }

        if (records.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    SectionHeader("Records today", "date = today")
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        records.forEach { record ->
                            val (number, unit) = fmtValueParts(record.value, record.format)
                            StatCard(
                                label = record.label,
                                value = number,
                                unit = unit,
                                delta = record.delta,
                                `when` = fmtShortDay(today),
                                badge = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("What was logged", loggedNote(session.groups.size, session.setCount))
        }

        if (session.isEmpty) {
            item {
                // the hint names the button by its label, so looking for it is reading
                // rather than searching
                EmptyState(
                    title = "Nothing logged today",
                    hint = "Just did a set? Tap \"Log a set\" at the bottom of the screen. " +
                        "Everything you record lands here straight away.",
                )
            }
        }

        items(session.groups.size, key = { session.groups[it].groupKey }) { index ->
            val group = session.groups[index]
            SessionCard(group, state.formOf(group.exerciseId))
        }
    }
}

/** A card of one exercise: header with the form dot and time range, then its sets. */
@Composable
private fun SessionCard(group: SessionGroup, form: ExerciseForm?) {
    val colors = LocalGachiColors.current
    GachiCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(form?.let { colors.forForm(it) } ?: colors.inkMuted)
            )
            Text(
                group.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(timeRange(group), fontSize = 11.sp, color = colors.inkMuted)
        }
        HorizontalDivider(color = colors.grid)

        group.sets.forEachIndexed { index, set ->
            if (index > 0) HorizontalDivider(color = colors.grid)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    set.form.summaryLine(),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (set.record != null) RecordBadge(null)
                Text("set ${index + 1}", fontSize = 10.sp, color = colors.inkMuted)
            }
        }
    }
}

/** "HH:mm - HH:mm" over the group's entries, or a single time when there is only one. */
private fun timeRange(group: SessionGroup): String {
    val times = group.sets.mapNotNull { it.ts.substringAfter('T', "").take(5).takeIf { t -> t.length == 5 } }
    if (times.isEmpty()) return ""
    val first = times.min()
    val last = times.max()
    return if (first == last) first else "$first - $last"
}

private fun loggedNote(groups: Int, sets: Int): String {
    if (groups == 0) return "nothing yet"
    val e = if (groups == 1) "exercise" else "exercises"
    val s = if (sets == 1) "entry" else "entries"
    return "$groups $e - $sets $s"
}

private val weekdayDateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

/** A record set today, ready for a stat card. */
private data class TodayRecord(
    val label: String,
    val value: Double,
    val format: ValueFormat,
    val delta: String?,
)

/**
 * Records set on the given day.
 *
 * An honest simplification carried over from the bot: today's own earlier sets count as
 * "previous" for the later sets of the same day. Sets without an exercise_id (written
 * before the catalog existed) are skipped — there is nothing to compare them against.
 * One note per exercise: the last, which is the strongest of the day.
 */
private fun todaysRecords(session: xyz.oleolegka.gachimuchi.domain.Session): List<TodayRecord> =
    session.groups.mapNotNull { group ->
        val hit = group.sets.lastOrNull { it.record != null }?.record ?: return@mapNotNull null
        val format = when (hit.axis) {
            RecordHit.Axis.HOLD_SECONDS -> ValueFormat.SECONDS
            else -> ValueFormat.KILOGRAMS
        }
        TodayRecord(
            label = "${axisLabel(hit.axis)} - ${group.name}",
            value = hit.value,
            format = format,
            delta = fmtDelta(hit.value - hit.previous, format),
        )
    }

private fun axisLabel(axis: RecordHit.Axis): String = when (axis) {
    RecordHit.Axis.EST_1RM -> "Estimated 1RM"
    RecordHit.Axis.WEIGHT_AT_REPS -> "Best weight at these reps"
    RecordHit.Axis.HOLD_WEIGHT -> "Most weight hung"
    RecordHit.Axis.HOLD_SECONDS -> "Longest hold"
}
