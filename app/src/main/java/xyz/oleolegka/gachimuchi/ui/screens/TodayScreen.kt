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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.ActivityEvent
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.evaluateHoldRecord
import xyz.oleolegka.gachimuchi.domain.evaluateStrengthRecord
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.displayName
import xyz.oleolegka.gachimuchi.ui.fmtDay
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * Today (§12-C): what was done today plus today's records.
 *
 * Records are recomputed by reducing the journal: for every entry made today, the
 * PREVIOUS sets of the same exercise (strictly earlier in journal order) are taken and
 * compared by the domain functions — exactly what the bot does on write. Neither side
 * has a stored "record" field.
 */
@Composable
fun TodayScreen(
    state: UiState,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onReseed: () -> Unit = {},
) {
    val iso = today.toString()
    val colors = LocalGachiColors.current

    val todays = remember(state.events, iso) {
        readActivities(state.events, dateFrom = iso, dateTo = iso)
    }
    val records = remember(state.events, iso) { todaysRecords(state, iso) }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(fmtDay(today), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (todays.isEmpty()) "no entries yet" else "entries: ${todays.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
                TextButton(onClick = onReseed) { Text("Demo data") }
            }
        }

        if (records.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Records today", style = MaterialTheme.typography.titleMedium, color = colors.good)
                        records.forEach { (name, text) ->
                            Text("$name — $text", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        items(todays, key = { it.id }) { ev ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text(ev.form.displayName(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        ev.form.summaryLine(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
            }
        }

        if (todays.isEmpty()) {
            item {
                Text(
                    "Nothing recorded today yet. A workout logging screen is the next step; " +
                        "for now the data comes from the demo history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Records set on the given day: (exercise, phrase).
 *
 * An honest simplification: today's own sets count as "previous" for the later sets of
 * the same day — exactly as in the bot. Sets without an exercise_id (written before the
 * catalog existed) are skipped: there is nothing to compare them against.
 */
private fun todaysRecords(state: UiState, iso: String): List<Pair<String, String>> {
    val all = readActivities(state.events)
    val out = mutableListOf<Pair<String, String>>()
    for ((index, ev) in all.withIndex()) {
        if (ev.opDate != iso) continue
        val prior: List<ActivityEvent> = all.subList(0, index)
        when (val form = ev.form) {
            is StrengthSet -> {
                val id = form.exerciseId ?: continue
                val priorSets = prior.mapNotNull { (it.form as? StrengthSet)?.takeIf { s -> s.exerciseId == id } }
                evaluateStrengthRecord(priorSets, form.weightKg, form.reps)
                    ?.let { out += form.exercise to it.text }
            }

            is HoldSet -> {
                val id = form.exerciseId ?: continue
                val priorHolds = prior.mapNotNull { (it.form as? HoldSet)?.takeIf { h -> h.exerciseId == id } }
                evaluateHoldRecord(priorHolds, form)?.let { out += form.activity to it.text }
            }

            else -> Unit
        }
    }
    // one note per exercise — the last one (the strongest of the day)
    return out.groupBy { it.first }.map { (name, hits) -> name to hits.last().second }
}
