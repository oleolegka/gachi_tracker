package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.activeDays
import xyz.oleolegka.gachimuchi.domain.bodyweightSeries
import xyz.oleolegka.gachimuchi.domain.holdRecord
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.strengthRecord
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.fmtDay
import xyz.oleolegka.gachimuchi.ui.fmtKg
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * Overview: a summary of the journal — activity over the last month, body weight and
 * per-exercise records.
 *
 * There are DELIBERATELY no charts here (that is a separate step): the screen shows
 * numbers actually computed from the journal rather than placeholders. The streak was
 * dropped per §12-C. A record always comes with a DATE (§12-C) — no bare badges.
 */
@Composable
fun OverviewScreen(state: UiState, today: LocalDate, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    val monthAgo = today.minusDays(29)

    val active = remember(state.events, today) {
        activeDays(state.events, monthAgo.toString(), today.toString())
    }
    val totalEvents = remember(state.events) { readActivities(state.events).size }
    val weights = remember(state.events) { bodyweightSeries(state.events) }
    val records = remember(state.events, state.exercises) { exerciseRecords(state) }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Overview",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("Active days", "${active.size}", "over the last 30 days", Modifier.weight(1f))
                StatTile("Journal entries", "$totalEvents", "all time", Modifier.weight(1f))
            }
        }

        item {
            val last = weights.lastOrNull()
            val first = weights.firstOrNull()
            val delta = if (last != null && first != null) last.weightKg - first.weightKg else null
            StatTile(
                title = "Body weight",
                value = last?.let { fmtKg(it.weightKg) } ?: "no data",
                hint = when {
                    last == null -> "no weigh-ins yet"
                    delta == null -> "first weigh-in"
                    else -> "${if (delta > 0) "+" else ""}${fmtKg(delta)} since the first entry, " +
                        "last on ${fmtDay(LocalDate.parse(last.opDate))}"
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Text(
                "Personal records",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (records.isEmpty()) {
            item {
                Text(
                    "No records yet: they are computed from the journal, and the journal is empty.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }
        }

        items(records, key = { it.first.id }) { (exercise, line) ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
            }
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, hint: String, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            // hierarchy by size: the headline number large, captions small and secondary (§6)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Text(hint, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
        }
    }
}

/**
 * The record of every exercise over its whole history, with a date. Strength uses the
 * maximum estimated 1RM by Epley; holds use MAXIMUM ADDED WEIGHT (§12-A). Forms with no
 * record model (cardio, duration, check-in, body weight) never reach this list — that is
 * an honest gap, not "there are no records".
 */
private fun exerciseRecords(state: UiState): List<Pair<ExerciseEntity, String>> {
    val activities = readActivities(state.events)
    return state.exercises.mapNotNull { ex ->
        val record = when (runCatching { ExerciseForm.fromCode(ex.form) }.getOrNull()) {
            ExerciseForm.STRENGTH -> strengthRecord(activities, ex.id)
            ExerciseForm.HOLD -> holdRecord(activities, ex.id)
            else -> null
        } ?: return@mapNotNull null
        ex to "${record.text}, ${fmtDay(LocalDate.parse(record.opDate))}"
    }
}
