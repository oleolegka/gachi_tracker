package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.NUDGE_SEC
import xyz.oleolegka.gachimuchi.domain.RunPhase
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.ceilSeconds
import xyz.oleolegka.gachimuchi.domain.currentStep
import xyz.oleolegka.gachimuchi.domain.formatClock
import xyz.oleolegka.gachimuchi.domain.nextStep
import xyz.oleolegka.gachimuchi.domain.phase
import xyz.oleolegka.gachimuchi.domain.stepRemainingMs
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * The timer as it appears on the logging screen: ONE compact row above the entry card.
 *
 * The constraint that shapes it is that the entry card is pinned to the bottom and must
 * stay reachable by a thumb between sets. So the timer gets a single line and a progress
 * bar, never a card and never a dialog: the countdown is something you glance at, and the
 * thing you actually came to the screen to do is record the next set.
 *
 * The row has three shapes, and only one of them is ever on screen:
 *  - the timer has not been switched on, so it offers to switch on;
 *  - nothing is running, so it states what a rest for this exercise will be and, for a
 *    hangboard exercise, offers the whole protocol as a program;
 *  - something is running, so it counts down and carries the controls.
 *
 * ── The rest is not started from here any more ──────────────────────────────────
 * There used to be a "Start rest" button in the idle row. It started a one-step run on the
 * conductor, which is a single countdown, so a rest for the abs cancelled the rest on the
 * bench — and it asked the user to press a button for something the app already knows has
 * happened. Rests are now floors: one per exercise, started by recording the set, drawn as a
 * bar under that exercise's card. What survives here is the SENTENCE — how long the next
 * rest will be and where that number came from — because that is the part the user was
 * reading before pressing the button.
 */
@Composable
fun TimerBar(
    state: TimerUiState,
    actions: TimerActions,
    exercise: ExerciseRef?,
    onStartExerciseProgram: () -> Unit,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            when {
                !state.enabled -> Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Rest timer is off",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                    TextButton(onClick = onEnable) { Text("Turn on") }
                }

                state.run == null -> IdleRow(
                    state = state,
                    exercise = exercise,
                    onStartProgram = onStartExerciseProgram,
                )

                else -> RunningRow(state = state, actions = actions)
            }
        }
    }
}

/** No protocol is running: how long the next rest will be, and what a tap would start. */
@Composable
private fun IdleRow(
    state: TimerUiState,
    exercise: ExerciseRef?,
    onStartProgram: () -> Unit,
) {
    val colors = LocalGachiColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Rest ${formatClock(state.restSec)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                state.restSource,
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
        }
        /*
         * The one-tap integration: a hangboard exercise already carries its work:rest
         * protocol, so the whole session is a program with nothing left to ask. The button
         * names the numbers it is about to use, because starting a twenty-minute program
         * by accident is worse than reading four extra words.
         */
        if (exercise?.protocol != null) {
            TextButton(onClick = onStartProgram) {
                Text("Start ${exercise.protocol!!.first.toInt()}:${exercise.protocol!!.second.toInt()}")
            }
        }
    }
}

/** Something is counting: the time left, what is next, and the three controls. */
@Composable
private fun RunningRow(state: TimerUiState, actions: TimerActions) {
    val colors = LocalGachiColors.current
    val snapshot = state.run ?: return
    val phase = snapshot.state.phase()
    val now = rememberTickingNow(active = phase == RunPhase.RUNNING)

    val step = currentStep(snapshot.steps, snapshot.state, now) ?: return
    val remainingMs = stepRemainingMs(snapshot.steps, snapshot.state, now)
    val upcoming = nextStep(snapshot.steps, snapshot.state, now)
    val singleStep = snapshot.steps.size == 1

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatClock(ceilSeconds(remainingMs)),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            // work and rest never look alike, and the label below says which it is anyway
            color = if (step.kind.isEffort()) colors.accent else MaterialTheme.colorScheme.onSurface,
        )
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(
                if (phase == RunPhase.PAUSED) "${step.name} - paused" else step.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Text(
                buildString {
                    step.blockPosition?.let { append(it) }
                    step.groupPosition?.let { if (isNotEmpty()) append("  "); append(it) }
                    upcoming?.let { if (isNotEmpty()) append("  "); append("next: ${it.name}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
                maxLines = 1,
            )
        }

        TextButton(onClick = if (phase == RunPhase.RUNNING) actions.pause else actions.resume) {
            Text(if (phase == RunPhase.RUNNING) "Pause" else "Go")
        }
        // a single rest is only ever lengthened; a program is stepped through
        if (singleStep) {
            TextButton(onClick = { actions.nudge(NUDGE_SEC) }) { Text("+$NUDGE_SEC") }
        } else {
            TextButton(onClick = actions.skip) { Text("Skip") }
        }
        TextButton(onClick = actions.stop) { Text("Stop") }
    }

    // progress is decoration, so it is derived from the step and never from a stored value
    val fraction = if (step.durationMs > 0) {
        1f - (remainingMs.toFloat() / step.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        color = if (step.kind == StepKind.WORK) colors.accent else colors.inkMuted,
    )
}
