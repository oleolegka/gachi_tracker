package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.CompletedSet
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.RunOutcome
import xyz.oleolegka.gachimuchi.domain.formatClock
import xyz.oleolegka.gachimuchi.domain.formatNumber
import xyz.oleolegka.gachimuchi.domain.parseNumber
import xyz.oleolegka.gachimuchi.domain.runSummaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * "You just did this. Write it down?"
 *
 * ── The numbers arrive filled in and stay editable ──────────────────────────────
 * The timer knows exactly how many efforts it counted, so nothing is asked that it can
 * answer — but the last set is the one that habitually falls short of what was planned
 * (three hangs of six, and the fourth is a drop), and only the person on the bar knows
 * that. So every set carries a stepper, and a set taken down to zero is simply not
 * written.
 *
 * ── It refuses rather than guesses ──────────────────────────────────────────────
 * A run only reaches this dialog when it was generated from a catalog exercise
 * (domain/RunLog.kt). If that exercise cannot be found any more, or is not a hold — the
 * only form whose sets map onto timed efforts one for one — the offer closes itself
 * instead of writing a shape of set the exercise does not have.
 */
@Composable
fun RunLogDialog(
    outcome: RunOutcome,
    exercise: ExerciseRef?,
    suggestedAddedKg: Double?,
    onLog: (List<CompletedSet>, Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current

    if (exercise == null || exercise.form != ExerciseForm.HOLD) {
        LaunchedEffect(outcome) { onDismiss() }
        return
    }

    var sets by remember(outcome) { mutableStateOf(outcome.sets) }
    var weight by remember(outcome) {
        mutableStateOf(suggestedAddedKg?.let { formatNumber(it) }.orEmpty())
    }
    val live = sets.count { it.reps > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log this run?") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(exercise.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    runSummaryLine(sets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
                if (outcome.interrupted) {
                    Text(
                        "The run was stopped part-way, so only what it actually got " +
                            "through is offered.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }

                sets.forEach { set ->
                    SetRow(set) { reps ->
                        sets = sets.map { if (it.setNumber == set.setNumber) it.copy(reps = reps) else it }
                    }
                }

                StepperField(
                    label = "Added weight, kg (empty for none)",
                    value = weight,
                    onValueChange = { weight = it },
                    steps = listOf(1.0, 5.0),
                    modifier = Modifier.padding(top = 4.dp),
                    placeholder = "0",
                )

                sets.firstOrNull { it.restAfterSec != null }?.restAfterSec?.let { rest ->
                    Text(
                        "The pause between sets is written as ${formatClock(rest)} - the " +
                            "program counted it, so it does not have to be guessed from the " +
                            "gap between entries.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = live > 0,
                onClick = { onLog(sets, parseNumber(weight)?.takeIf { it > 0 }) },
            ) { Text(if (live == 1) "Log 1 set" else "Log $live sets") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not this time") } },
    )
}

@Composable
private fun SetRow(set: CompletedSet, onChange: (Int) -> Unit) {
    val colors = LocalGachiColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Set ${set.setNumber}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        RepButton("-") { onChange((set.reps - 1).coerceAtLeast(0)) }
        Text(
            set.reps.toString(),
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        RepButton("+") { onChange((set.reps + 1).coerceAtMost(MAX_REPS_PER_SET)) }
        Text(
            "of ${set.plannedReps}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkMuted,
        )
    }
}

@Composable
private fun RepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        contentPadding = PaddingValues(0.dp),
        shape = MaterialTheme.shapes.medium,
    ) { Text(label) }
}

/** A hangboard set is a handful of efforts; anything past this is a stuck finger. */
private const val MAX_REPS_PER_SET = 99
