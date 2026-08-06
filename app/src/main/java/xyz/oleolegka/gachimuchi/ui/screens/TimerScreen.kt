package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.NUDGE_SEC
import xyz.oleolegka.gachimuchi.domain.REST_PRESETS_SEC
import xyz.oleolegka.gachimuchi.domain.RunPhase
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.ceilSeconds
import xyz.oleolegka.gachimuchi.domain.currentStep
import xyz.oleolegka.gachimuchi.domain.formatClock
import xyz.oleolegka.gachimuchi.domain.nextStep
import xyz.oleolegka.gachimuchi.domain.phase
import xyz.oleolegka.gachimuchi.domain.stepRemainingMs
import xyz.oleolegka.gachimuchi.domain.totalRemainingMs
import xyz.oleolegka.gachimuchi.domain.totalSec
import xyz.oleolegka.gachimuchi.domain.workStepCount
import xyz.oleolegka.gachimuchi.ui.components.TimerActions
import xyz.oleolegka.gachimuchi.ui.components.TimerUiState
import xyz.oleolegka.gachimuchi.ui.components.isEffort
import xyz.oleolegka.gachimuchi.ui.components.rememberProgramTransfer
import xyz.oleolegka.gachimuchi.ui.components.rememberTickingNow
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * The timer tab: what is running, what can be run, and what the timer is allowed to do.
 *
 * One scrolling screen rather than a section with its own navigation. There are only
 * three things here and they are read in this order — the live run first, because when
 * anything is running that is the only thing that matters; then the programs; then the
 * settings, which are visited once and then never again.
 */
@Composable
fun TimerScreen(
    state: TimerUiState,
    actions: TimerActions,
    programs: List<WorkoutProgram>,
    onRunProgram: (WorkoutProgram) -> Unit,
    onEditProgram: (WorkoutProgram?) -> Unit,
    onDeleteProgram: (Long) -> Unit,
    onImportPrograms: (List<WorkoutProgram>) -> Unit,
    onSettings: (TimerSettings) -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    // owns the pickers and the dialogs of exporting and importing; see ProgramTransfer.kt
    val transfer = rememberProgramTransfer(onImported = onImportPrograms)

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.enabled) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("The timer is off", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Turn it on to count rests between sets and to run interval " +
                                "programs with the screen off.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(onClick = onEnable, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Turn on the timer")
                        }
                    }
                }
            }
        }

        state.run?.let { item { RunPanel(state, actions) } }

        item {
            Text(
                "Programs",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        items(programs, key = { it.id }) { program ->
            ProgramCard(
                program = program,
                enabled = state.enabled,
                onRun = { onRunProgram(program) },
                onEdit = { onEditProgram(program) },
                onExport = { transfer.export(listOf(program)) },
                onDelete = { onDeleteProgram(program.id) },
            )
        }

        item {
            OutlinedButton(
                onClick = { onEditProgram(null) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("New program") }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = transfer.import,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Import from a file") }
                OutlinedButton(
                    onClick = { transfer.export(programs) },
                    enabled = programs.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Export all") }
            }
        }

        item { SettingsSection(state, onSettings, onEnable, onDisable) }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The live run, in the size you can read from the floor. */
@Composable
private fun RunPanel(state: TimerUiState, actions: TimerActions) {
    val colors = LocalGachiColors.current
    val snapshot = state.run ?: return
    val phase = snapshot.state.phase()
    val now = rememberTickingNow(active = phase == RunPhase.RUNNING)

    val step = currentStep(snapshot.steps, snapshot.state, now) ?: return
    val remainingMs = stepRemainingMs(snapshot.steps, snapshot.state, now)
    val upcoming = nextStep(snapshot.steps, snapshot.state, now)
    val singleStep = snapshot.steps.size == 1

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                snapshot.programName,
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatClock(ceilSeconds(remainingMs)),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (step.kind.isEffort()) colors.accent else MaterialTheme.colorScheme.onSurface,
                )
                Column(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                    Text(
                        if (phase == RunPhase.PAUSED) "${step.name} - paused" else step.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        buildString {
                            step.blockPosition?.let { append(it) }
                            step.groupPosition?.let { if (isNotEmpty()) append("  "); append(it) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }
            }

            LinearProgressIndicator(
                progress = {
                    if (step.durationMs > 0) {
                        1f - (remainingMs.toFloat() / step.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                color = if (step.kind == StepKind.WORK) colors.accent else colors.inkMuted,
            )

            Text(
                buildString {
                    upcoming?.let { append("Next: ${it.name} ${formatClock(it.durationSec)}") }
                    if (!singleStep) {
                        if (isNotEmpty()) append("     ")
                        append(
                            formatClock(ceilSeconds(totalRemainingMs(snapshot.steps, snapshot.state, now)))
                        )
                        append(" left in the program")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!singleStep) {
                    OutlinedButton(onClick = actions.previous, modifier = Modifier.weight(1f)) {
                        Text("Back")
                    }
                }
                OutlinedButton(onClick = { actions.nudge(-NUDGE_SEC) }, modifier = Modifier.weight(1f)) {
                    Text("-$NUDGE_SEC")
                }
                OutlinedButton(onClick = { actions.nudge(NUDGE_SEC) }, modifier = Modifier.weight(1f)) {
                    Text("+$NUDGE_SEC")
                }
                if (!singleStep) {
                    OutlinedButton(onClick = actions.skip, modifier = Modifier.weight(1f)) {
                        Text("Skip")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = if (phase == RunPhase.RUNNING) actions.pause else actions.resume,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text(if (phase == RunPhase.RUNNING) "Pause" else "Resume") }
                OutlinedButton(
                    onClick = actions.stop,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun ProgramCard(
    program: WorkoutProgram,
    enabled: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalGachiColors.current
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(Modifier.padding(12.dp)) {
            Text(program.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${program.workStepCount()} efforts   ${formatClock(program.totalSec())} total",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = onRun, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("Run")
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onExport) { Text("Export") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/**
 * The switches. Written as plain rows rather than a preference library: there are eight
 * of them and they need to explain themselves, which a generated preference screen does
 * badly.
 */
@Composable
private fun SettingsSection(
    state: TimerUiState,
    onChange: (TimerSettings) -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    val colors = LocalGachiColors.current
    val settings = state.settings

    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Timer settings", style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(color = colors.grid, modifier = Modifier.padding(vertical = 4.dp))

        Text(
            "Default rest between sets",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            REST_PRESETS_SEC.forEach { preset ->
                OutlinedButton(
                    onClick = { onChange(settings.copy(defaultRestSec = preset)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        formatClock(preset),
                        fontWeight = if (settings.defaultRestSec == preset) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        SettingRow(
            title = "Start the rest automatically",
            subtitle = "The countdown begins the moment a set is recorded.",
            checked = settings.autoStartRest,
        ) { onChange(settings.copy(autoStartRest = it)) }

        SettingRow(
            title = "Use the rest you actually took",
            subtitle = "Takes the length from the last session with this exercise " +
                "instead of the default. Currently offering ${formatClock(state.restSec)} " +
                "(${state.restSource}).",
            checked = settings.adaptRestToExercise,
        ) { onChange(settings.copy(adaptRestToExercise = it)) }

        SettingRow(
            title = "Vibrate",
            subtitle = "The main signal: the phone is usually on silent and in a pocket.",
            checked = settings.vibrate,
        ) { onChange(settings.copy(vibrate = it)) }

        SettingRow(
            title = "Sound",
            subtitle = "Tones on the alarm channel, so they are heard with the ringer on " +
                "silent - and so they are loud.",
            checked = settings.sound,
        ) { onChange(settings.copy(sound = it)) }

        SettingRow(
            title = "Count the last three seconds",
            subtitle = "A short tick before every change of step.",
            checked = settings.countdownTicks,
        ) { onChange(settings.copy(countdownTicks = it)) }

        /*
         * The speech switch is the one place the phone's lack of Google services shows
         * through, so it says so instead of being a toggle that silently does nothing.
         */
        SettingRow(
            title = "Announce the steps out loud",
            subtitle = if (state.speechAvailable) {
                "Says the name of each step as it starts."
            } else {
                "No speech engine was found on this device, so this stays off. " +
                    "The timer still uses tones and vibration."
            },
            checked = settings.speak && state.speechAvailable,
            enabled = state.speechAvailable,
        ) { onChange(settings.copy(speak = it)) }

        SettingRow(
            title = "The timer itself",
            subtitle = "Turning this off stops any run and hides the timer from the " +
                "logging screen.",
            checked = state.enabled,
        ) { wanted -> if (wanted) onEnable() else onDisable() }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalGachiColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
