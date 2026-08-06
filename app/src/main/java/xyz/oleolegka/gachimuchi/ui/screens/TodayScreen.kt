package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import xyz.oleolegka.gachimuchi.domain.buildSession
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
        // room under the last card for the "Start workout" button, which floats above the list
        contentPadding = PaddingValues(bottom = 88.dp),
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
                    "Nothing recorded today yet. Start a workout with the button below and " +
                        "the entries will show up here.",
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
 * The detection itself lives in the session reducer, which is the same code the logging
 * screen shows a record badge from — the two screens cannot disagree about what counts
 * as a record.
 *
 * An honest simplification carried over from the bot: today's own sets count as
 * "previous" for the later sets of the same day. Sets without an exercise_id (written
 * before the catalog existed) are skipped — there is nothing to compare them against.
 * One note per exercise: the last, which is the strongest of the day.
 */
private fun todaysRecords(state: UiState, iso: String): List<Pair<String, String>> =
    buildSession(state.events, iso).groups.mapNotNull { group ->
        val hit = group.sets.lastOrNull { it.record != null }?.record ?: return@mapNotNull null
        group.name to hit.text
    }
