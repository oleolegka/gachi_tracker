package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
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
 *
 * ── The 2026-08-11 redraw, and what it was answering ────────────────────────────
 * The owner, looking at this form: "it is offering me to do something with sets, some
 * pluses, I have no idea what this is". Four things were wrong at once and each is
 * answered here (`design-system/app-next/run-log.html`):
 *
 *  1. The weight field was hemmed in by four 50dp buttons and had less width left than any
 *     one of them. The buttons now sit UNDERNEATH it, a quarter of the width each — see
 *     [StepperField]'s `stacked`, which is the layout [TimeField] already used.
 *  2. Four identical disabled "Not completed" chips, one per set, took half the height of
 *     the list. They are a column of checkboxes now, under one heading that says the words
 *     once.
 *  3. Everything the run had to disclaim — that it ended an hour ago, which day it will be
 *     written under, that it was cut short, what rest it counted — was scattered above and
 *     below the sets in 11sp grey, so the last two read as a footnote to the weight buttons.
 *     They are one recessed block of facts under the name.
 *  4. The number of sets was said three times over (title, summary, button). It is said
 *     once, on the button, where it is also live: take a set down to zero and it drops.
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
    val nothingToFileUnder = chosen == null && candidates.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    // named for what is happening, because nothing here can be answered
                    nothingToFileUnder -> "Nowhere to write this run"
                    chosen == null -> "Write this run down"
                    else -> "Write this run down?"
                }
            )
        },
        text = {
            /*
             * The middle scrolls between the fixed title and the fixed buttons. With four
             * sets this dialog is taller than a small phone, and before the redraw the
             * whole of it scrolled — including the question at the top and, on a short
             * window, the buttons that answer it. The two hairlines are the only thing on
             * a still picture that says there is more; on the phone the inertia says it.
             */
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = colors.grid)
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.Block),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Block),
                ) {
                    RunHead(outcome, chosen, sets)
                    RunFacts(outcome, sets, nowWallMs)

                    if (chosen == null) {
                        ExerciseChoice(candidates = candidates) { chosen = it }
                    } else {
                        SetsBlock(
                            sets = sets,
                            onSetsChange = { sets = it },
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
                            BlockHeading("Added weight, kg")
                            StepperField(
                                label = null,
                                value = weight,
                                onValueChange = { weight = it },
                                steps = listOf(1.0, 5.0),
                                placeholder = "0",
                                stacked = true,
                                fieldDescription = "Added weight, kg",
                            )
                        }

                        if (outcome.programId != 0L && outcome.exerciseId != chosen?.id) {
                            Text(
                                "\"${outcome.programName}\" will be linked to this exercise.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkMuted,
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.grid)
            }
        },
        confirmButton = {
            chosen?.let { target ->
                /*
                 * FILLED, against a text button to decline: the good news must not be the
                 * paler of the two (SYSTEM.md rule 7). It also carries the only statement
                 * of how many sets are about to be written, and it is live — a set turned
                 * down to zero is not written, and the button says so before the tap.
                 */
                Button(
                    enabled = live > 0,
                    onClick = { onLog(target, sets, parseNumber(weight)?.takeIf { it != 0.0 }) },
                ) { Text(if (live == 1) "Write down 1 set" else "Write down $live sets") }
            }
        },
        dismissButton = {
            // there is nothing to decline when the catalog cannot hold this run at all
            TextButton(onClick = onDismiss) {
                Text(if (nothingToFileUnder) "Close" else "Not this time")
            }
        },
    )
}

/** Which run this is, and what it came to — the two lines the whole offer hangs off. */
@Composable
private fun RunHead(outcome: RunOutcome, chosen: ExerciseRef?, sets: List<CompletedSet>) {
    val colors = LocalGachiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(
            // side-suffixed the same way a card names itself (WorkoutLogScreen), so an
            // offer from the left card and one from the right read as two sessions and
            // not as the same run asked about twice
            (chosen?.name ?: outcome.programName).let { name ->
                outcome.sideOf?.let { "$name - ${it.label()}" } ?: name
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            runSummaryLine(sets),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
    }
}

/**
 * Everything this offer has to disclaim, in one recessed block.
 *
 * They were four separate grey lines in two places before, two of them below the weight
 * field, where they read as a note about the weight buttons rather than about the run. They
 * are all the same kind of statement — a fact about the run that is not being asked about —
 * so they are one block, immediately under the name they are facts about. Absent entirely
 * when the run has nothing to disclaim, which is the ordinary case of answering straight
 * away.
 */
@Composable
private fun RunFacts(outcome: RunOutcome, sets: List<CompletedSet>, nowWallMs: Long) {
    val colors = LocalGachiColors.current
    val rest = sets.firstOrNull { it.restAfterSec != null }?.restAfterSec
    val facts = buildList {
        // a run answered later must say so rather than pretending it just happened
        if (!outcome.isFresh(nowWallMs)) {
            add("Ended ${endedAtLabel(outcome.endedAtWallMs)}, written under ${outcome.opDate}")
        }
        if (outcome.interrupted) {
            add("Stopped part-way - only what it got through is offered")
        }
        if (rest != null) {
            add("Rest between sets ${formatClock(rest)}, counted by the program")
        }
    }
    if (facts.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.recessed, RoundedCornerShape(Radius.Small))
            .padding(Spacing.Inset),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        facts.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
    }
}

/** The heading of one block of the offer: the size a card title is, one step above its text. */
@Composable
private fun BlockHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * The sets, one row each, under one heading that says what the number in them counts.
 *
 * Two rows per set became one. The "not completed" mark used to be a chip of its own
 * underneath each row — four identical chips reading the same two words, taking half the
 * height of the list to say something about none of them. The words are now the heading of
 * a column of checkboxes, said once.
 */
@Composable
private fun SetsBlock(sets: List<CompletedSet>, onSetsChange: (List<CompletedSet>) -> Unit) {
    val colors = LocalGachiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
        BlockHeading("Efforts held in each set")
        Text(
            "The timer counted these; turn a set down if you came off early.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            ColumnLabel("SET", Modifier.weight(1f))
            ColumnLabel("NOT COMPLETED")
        }
        Column {
            sets.forEach { set ->
                HorizontalDivider(color = colors.grid)
                SetRow(
                    set = set,
                    onRepsChange = { reps ->
                        onSetsChange(
                            sets.map {
                                if (it.setNumber == set.setNumber) it.copy(reps = reps) else it
                            }
                        )
                    },
                    onIncompleteChange = { incomplete ->
                        onSetsChange(
                            sets.map {
                                if (it.setNumber == set.setNumber) {
                                    it.copy(incomplete = incomplete)
                                } else {
                                    it
                                }
                            }
                        )
                    },
                )
            }
            HorizontalDivider(color = colors.grid)
        }
    }
}

/** The name of a column of the table: the floor of the type scale, and the only caps here. */
@Composable
private fun ColumnLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = LocalGachiColors.current.inkMuted,
    )
}

/**
 * One set of the offer, in one row: which set, what it held, and whether it was carried
 * through.
 *
 * The number in the middle is HOW MANY EFFORTS WERE HELD inside this set, and the heading
 * above the rows is what says so — on its own the row read "Set 1", a bare figure and "of 6",
 * which is what the owner could not decode. The ceiling now travels WITH the number, as
 * "4/6", because that is where it is looked at.
 *
 * The "+" stops at [CompletedSet.plannedReps] and not at a global maximum. A schedule is
 * strict about this: the run counted down a fixed number of efforts and ended, so a set can
 * come out SHORT of what was planned and can never come out over it. The button is disabled
 * at the ceiling rather than silently ignoring the tap, so the limit is visible before it is
 * hit.
 *
 * The checkbox is per row and not once for the whole offer, because a fingerboard session is
 * six hangs and falling off on the fourth says nothing about the other five: see
 * [xyz.oleolegka.gachimuchi.domain.holdSetsFromRun], which is what turns [onIncompleteChange]
 * into the same [xyz.oleolegka.gachimuchi.domain.LoadedSet.incomplete] mark the rest of the
 * app sets by hand.
 */
@Composable
private fun SetRow(
    set: CompletedSet,
    onRepsChange: (Int) -> Unit,
    onIncompleteChange: (Boolean) -> Unit,
) {
    val colors = LocalGachiColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Text(
            "Set ${set.setNumber}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        RepButton("-", enabled = set.reps > 0) {
            onRepsChange((set.reps - 1).coerceAtLeast(0))
        }
        Row(
            modifier = Modifier.width(44.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                set.reps.toString(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "/${set.plannedReps}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
        }
        RepButton("+", enabled = set.reps < set.plannedReps) {
            onRepsChange((set.reps + 1).coerceAtMost(set.plannedReps))
        }
        Checkbox(
            checked = set.incomplete,
            onCheckedChange = onIncompleteChange,
            modifier = Modifier.semantics {
                contentDescription = "Set ${set.setNumber} not completed"
            },
        )
    }
}

/**
 * The "which exercise was that" step.
 *
 * A list of buttons, marked as one: a row is 56dp, separated from its neighbours, and
 * carries the chevron that says it leads somewhere. Before the redraw these were plain
 * lines of text with nothing about them to suggest they could be tapped at all.
 *
 * An empty list is the interesting case and is spelled out rather than left blank: a run of
 * a program on a phone whose catalog has no hold exercise in it cannot be written down, and
 * the user needs to know that now — while the numbers are still on the screen — rather than
 * to be shown a dialog with no buttons that do anything.
 */
@Composable
private fun ExerciseChoice(candidates: List<ExerciseRef>, onPick: (ExerciseRef) -> Unit) {
    val colors = LocalGachiColors.current

    if (candidates.isEmpty()) {
        Text(
            "There is no hold exercise in the catalog to file this under. Create one - name " +
                "and work:rest - and this program can be logged next time.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
        BlockHeading("Which exercise was this?")
        Text(
            "Asked once - the answer is remembered on the program.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
        )
        Column {
            candidates.forEach { candidate ->
                HorizontalDivider(color = colors.grid)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(candidate) }
                        .heightIn(min = 56.dp)
                        .padding(vertical = Spacing.Line),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Inset),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    ) {
                        Text(candidate.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            // an empty protocol used to be an empty line: buildString on a
                            // null pair wrote nothing, and the row carried a blank 11sp gap
                            // where the answer should have been
                            candidate.protocol?.let {
                                "${formatNumber(it.first)}:${formatNumber(it.second)}"
                            } ?: "no protocol",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null, // decoration: the row's own name is the label
                        tint = colors.inkMuted,
                    )
                }
            }
            HorizontalDivider(color = colors.grid)
        }
    }
}

@Composable
private fun RepButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        contentPadding = PaddingValues(0.dp),
        shape = MaterialTheme.shapes.small,
    ) { Text(label) }
}

/** "19:42" — the plain fact, so a late offer is not mistaken for a fresh one. */
private fun endedAtLabel(wallMs: Long): String =
    if (wallMs <= 0) {
        "earlier"
    } else {
        Instant.ofEpochMilli(wallMs)
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
