package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import xyz.oleolegka.gachimuchi.ui.label
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 * ── It asks rather than disappearing ────────────────────────────────────────────
 * This used to close itself, without a word, whenever the run's exercise could not be
 * resolved or was not a hold. Combined with a timer that only offered runs generated from a
 * catalog exercise, that produced the failure this dialog exists to prevent: a full session
 * counted, and silence. So there is now exactly one way for this dialog to go away without
 * being answered, and that is the user closing it.
 *
 * When the run does not know which exercise it was, it asks — once — and the answer is
 * remembered on the program (ui/MainViewModel.kt), so the same protocol never asks twice.
 * When the catalog has nothing to file the run under, it says so and still shows the
 * numbers, which at least lets them be typed in by hand before they are forgotten.
 *
 * ── Holds only, and why that is a limit and not a bug ───────────────────────────
 * A hold is the one form whose sets map onto timed efforts one for one (§12-A). For a
 * strength set a 30-second work step is a set of unknown reps, so it is not offered as a
 * choice rather than being written as a guess.
 */
@Composable
fun RunLogDialog(
    outcome: RunOutcome,
    /** The exercise the run says it was, when the catalog still has it. */
    exercise: ExerciseRef?,
    /** Everything the run could be filed under: the hold exercises in the catalog. */
    candidates: List<ExerciseRef>,
    /** The added weight last used for an exercise, so the offer arrives filled in. */
    lastAddedKg: (Long) -> Double?,
    nowWallMs: Long,
    onLog: (ExerciseRef, List<CompletedSet>, Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current

    // the linked exercise is only a starting point; a link to something that is not a hold
    // is a link that cannot be written, so it falls through to the chooser
    var chosen by remember(outcome) {
        mutableStateOf(exercise?.takeIf { it.form == ExerciseForm.HOLD })
    }
    var sets by remember(outcome) { mutableStateOf(outcome.sets) }
    var weight by remember(outcome, chosen) {
        mutableStateOf(chosen?.let { lastAddedKg(it.id) }?.let { formatNumber(it) }.orEmpty())
    }
    val live = sets.count { it.reps > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (chosen == null) "Write this run down" else "Write this run down?") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    // side-suffixed the same way a card names itself (WorkoutLogScreen), so an
                    // offer from the left card and one from the right read as two sessions and
                    // not as the same run asked about twice
                    (chosen?.name ?: outcome.programName).let { name ->
                        outcome.sideOf?.let { "$name - ${it.label()}" } ?: name
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    runSummaryLine(sets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )

                // a run answered later must say so rather than pretending it just happened
                if (!outcome.isFresh(nowWallMs)) {
                    Text(
                        "This run ended ${endedAtLabel(outcome.endedAtWallMs)}, and its sets " +
                            "will be written under ${outcome.opDate}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
                if (outcome.interrupted) {
                    Text(
                        "The run was stopped part-way, so only what it actually got " +
                            "through is offered.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }

                if (chosen == null) {
                    ExerciseChoice(candidates = candidates) { chosen = it }
                } else {
                    /*
                     * WHAT IS BEING ASKED, said in words before the first stepper.
                     *
                     * The owner's report on this form was "it is offering me to do something
                     * with sets, some pluses, I have no idea what this is". Everything on the
                     * row was true and none of it was named: "Set 1", a bare number, and "of
                     * 6". So the number gets a heading that says what it counts, and the form
                     * gets one line saying why it is being shown at all — read after a
                     * hangboard session, with the fingers that just did it.
                     */
                    HorizontalDivider(
                        color = colors.grid,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                    )
                    Text(
                        "Efforts held in each set",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        "This is what the schedule counted. Turn a set down if you came off " +
                            "early - it cannot go above what was planned, because the count " +
                            "has already run out.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )

                    sets.forEach { set ->
                        SetRow(
                            set = set,
                            onRepsChange = { reps ->
                                sets = sets.map {
                                    if (it.setNumber == set.setNumber) it.copy(reps = reps) else it
                                }
                            },
                            onIncompleteChange = { incomplete ->
                                sets = sets.map {
                                    if (it.setNumber == set.setNumber) it.copy(incomplete = incomplete) else it
                                }
                            },
                        )
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
                    if (outcome.programId != 0L && outcome.exerciseId != chosen?.id) {
                        Text(
                            "\"${outcome.programName}\" will be linked to this exercise, so " +
                                "next time it offers the sets straight away.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkMuted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            chosen?.let { target ->
                TextButton(
                    enabled = live > 0,
                    onClick = { onLog(target, sets, parseNumber(weight)?.takeIf { it != 0.0 }) },
                ) { Text(if (live == 1) "Log 1 set" else "Log $live sets") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not this time") } },
    )
}

/**
 * The "which exercise was that" step.
 *
 * An empty list is the interesting case and is spelled out rather than left blank: a run of
 * a program on a phone whose catalog has no hold exercise in it cannot be written down, and
 * the user needs to know that now — while the numbers are still on the screen — rather than
 * to be shown a dialog with no buttons that do anything.
 */
@Composable
private fun ExerciseChoice(candidates: List<ExerciseRef>, onPick: (ExerciseRef) -> Unit) {
    val colors = LocalGachiColors.current
    HorizontalDivider(color = colors.grid, modifier = Modifier.padding(vertical = 6.dp))

    if (candidates.isEmpty()) {
        Text(
            "There is no hold exercise in the catalog to file this under. Create one (name " +
                "and work:rest) and this program can be logged next time - the numbers " +
                "above are what it counted.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        return
    }

    Text(
        "Which exercise was this?",
        style = MaterialTheme.typography.labelSmall,
        color = colors.inkMuted,
    )
    candidates.forEach { candidate ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(candidate) }
                .heightIn(min = 48.dp)
                .padding(vertical = 6.dp),
        ) {
            Text(candidate.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    candidate.protocol?.let {
                        append("${formatNumber(it.first)}:${formatNumber(it.second)}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
        }
    }
}

/**
 * One set of the offer.
 *
 * The number in the middle is HOW MANY EFFORTS WERE HELD inside this set, and the heading
 * above the rows is what says so — on its own the row read "Set 1", a bare figure and "of 6",
 * which is what the owner could not decode.
 *
 * The "+" stops at [CompletedSet.plannedReps] and not at a global maximum. A schedule is
 * strict about this: the run counted down a fixed number of efforts and ended, so a set can
 * come out SHORT of what was planned and can never come out over it. The button is disabled
 * at the ceiling rather than silently ignoring the tap, so the limit is visible before it is
 * hit.
 *
 * Also here: the rep count, correctable the same way it always was, and — new —
 * whether THIS set was carried through. Per row and not once for the whole offer, because a
 * fingerboard session is six hangs and falling off on the fourth says nothing about the other
 * five: see [xyz.oleolegka.gachimuchi.domain.holdSetsFromRun], which is what turns
 * [onIncompleteChange] into the same [xyz.oleolegka.gachimuchi.domain.LoadedSet.incomplete] mark
 * the rest of the app sets by hand.
 */
@Composable
private fun SetRow(set: CompletedSet, onRepsChange: (Int) -> Unit, onIncompleteChange: (Boolean) -> Unit) {
    val colors = LocalGachiColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Set ${set.setNumber}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            RepButton("-", enabled = set.reps > 0) {
                onRepsChange((set.reps - 1).coerceAtLeast(0))
            }
            Text(
                set.reps.toString(),
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            RepButton("+", enabled = set.reps < set.plannedReps) {
                onRepsChange((set.reps + 1).coerceAtMost(set.plannedReps))
            }
            Text(
                "of ${set.plannedReps} planned",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
        }
        RunSetIncompleteChip(set.incomplete) { onIncompleteChange(!set.incomplete) }
    }
}

/**
 * The "not completed" toggle for one row of the offer — the same fact
 * [xyz.oleolegka.gachimuchi.ui.screens.IncompleteChip] sets when logging by hand and
 * [xyz.oleolegka.gachimuchi.ui.components.EntryEditor]'s own toggle corrects afterwards, styled
 * to match both. A third copy of the same small [FilterChip] rather than a shared one, on the
 * same footing those two already stand on — see either of their own KDoc for the same choice
 * made before this one existed.
 */
@Composable
private fun RunSetIncompleteChip(selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text("Not completed") },
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

@Composable
private fun RepButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        contentPadding = PaddingValues(0.dp),
        shape = MaterialTheme.shapes.medium,
    ) { Text(label) }
}

/** "at 19:42" — the plain fact, so a late offer is not mistaken for a fresh one. */
private fun endedAtLabel(wallMs: Long): String =
    if (wallMs <= 0) {
        "earlier"
    } else {
        "at " + Instant.ofEpochMilli(wallMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }

/*
 * The ceiling on a set used to be a global constant (99) and is now [CompletedSet.plannedReps]
 * — see [SetRow]. Fewer efforts than the schedule called for is the ordinary case; MORE is not
 * a case at all, because the run that produced this offer is over and the count it was given
 * has already run out. The constant is gone rather than lowered: any number that is not the
 * planned one is a number this form cannot justify.
 */
