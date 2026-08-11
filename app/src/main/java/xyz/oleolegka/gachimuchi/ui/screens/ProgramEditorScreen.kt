package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.PREPARE_DEFAULT_SEC
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.formatClock
import xyz.oleolegka.gachimuchi.domain.totalSec
import xyz.oleolegka.gachimuchi.domain.workStepCount
import xyz.oleolegka.gachimuchi.ui.components.ActionMenu
import xyz.oleolegka.gachimuchi.ui.components.EyebrowStyle
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.ItemAction
import xyz.oleolegka.gachimuchi.ui.components.MissingNote
import xyz.oleolegka.gachimuchi.ui.components.StepperField
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * Building a program: name, lead-in, and a list of groups each holding a list of blocks.
 *
 * ── Edited as one value, saved in one go ────────────────────────────────────────
 * The whole [WorkoutProgram] is held in screen state and written once on Save. Nothing is
 * persisted while typing, so backing out of a half-finished edit leaves the stored
 * program exactly as it was, and the summary line at the top can recompute the real
 * length after every keystroke.
 *
 * ── The numbers are steppers, not text fields ───────────────────────────────────
 * Same reason as the logging screen: seven seconds becomes eight with one tap and no
 * keyboard. The text field is still there underneath for the cases a stepper is slow at.
 *
 * ── ONE Save, at the bottom (`app-next/program-editor.html`, rule 1) ────────────
 * There used to be two — a `TextButton` in the top bar and a full-width button as the last
 * item of the list — and they were not even called the same thing: "Use" up there against
 * "Use this schedule" down here, which is one action wearing two names on one screen. The
 * one that survives is the bottom one, PINNED rather than scrolled to: this screen is longer
 * than a phone, the thumb rests at the bottom, and a Save you have to scroll to the end of a
 * form to reach is a Save that gets missed on a long program. The top bar keeps only the way
 * out.
 *
 * ── The reason it is grey is written down ───────────────────────────────────────
 * `name.isNotBlank() && totalSec() > 0` used to be enforced in silence. [MissingNote] above
 * the button names whichever half is missing. The CONDITION is unchanged — this says what it
 * already said, out loud.
 *
 * ── [locked]: a schedule that has been trained on, opened for a look ────────────
 * True when a set has been recorded by some exercise whose protocol [initial] is — the same
 * question [xyz.oleolegka.gachimuchi.data.ProgramRepository.isFrozen] asks, computed from the
 * same domain function (`domain/ScheduleFreeze.kt`) before the screen ever opens. Locked, the
 * lead-in and every group and block are shown as READ TEXT rather than fields: "such a thing
 * cannot happen: it breaks the statistics. If yesterday it was one protocol and today another,
 * that is a NEW exercise" is the owner's own words for why this screen must not be the second
 * door onto the same mistake the exercise editor already closed
 * (`ui/screens/EditExerciseScreen.kt`).
 *
 * NOT locked merely by being somebody's schedule (§18.19, superseding §18.9). A schedule with
 * no sets against it yet has no history to put out of step, so a wrong number in it is a typo
 * and gets to be corrected. Sharing matters here: twins point at one schedule on purpose, so
 * the question is asked of ALL the exercises pointing at it, never of one.
 *
 * NOT locked: the name, the category and the exercise link. None of the three is part of an
 * identity keyed on the program's uid (`domain/Catalog.kt`'s `ExerciseIdentity`), and a name a
 * migration generated ("Hangs 20mm protocol", identical across five unrelated exercises) is
 * exactly the kind of thing worth being able to fix on a program already in use.
 *
 * This is UI convenience, not the enforcement — [xyz.oleolegka.gachimuchi.data.
 * ProgramRepository.save] refuses the content rewrite on its own, live, whatever this screen
 * showed. Locked here only means nobody is shown a control that would then do nothing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProgramEditorScreen(
    initial: WorkoutProgram?,
    /** Hold exercises this program could be logged as; the link is what makes it loggable. */
    candidates: List<ExerciseRef>,
    /** Headings already in use, so the same one is not spelled two ways. */
    categories: List<String>,
    /** Whether [initial] has had a set recorded against it — see the KDoc above. */
    locked: Boolean = false,
    /**
     * This editor is building the SCHEDULE of an exercise that does not exist yet — opened in
     * a dialog from the create form (§18.15), not from the library.
     *
     * Two things change, and neither is cosmetic in the way it looks. The words become the
     * ones the create form uses ("Schedule", "Use this schedule"), because a form that spent
     * three cards explaining what a schedule is must not hand over to a screen calling the
     * same thing a program. And the "Logs as" field disappears: there is no exercise to link
     * to yet, and the link is implied — this schedule is being built FOR the exercise being
     * created — so the field could only offer a wrong answer or an empty list explaining that
     * no hold exercise exists, to somebody in the middle of creating one.
     */
    asSchedule: Boolean = false,
    /**
     * The exercise this schedule is being built for, named in the top bar ("for Hangs 20 mm").
     *
     * Presentation only, and only in [asSchedule]. This screen arrives as a full-window dialog
     * OVER the modal create sheet — three windows deep — and the arrow in its top bar is the
     * only thing on it that says where leaving lands. Naming the exercise is what turns that
     * arrow from "back somewhere" into "back to the form you were filling in"; the honest fix
     * for the stack itself is not a layout one (`app-next/program-editor.html`, section C).
     */
    forExercise: String? = null,
    onSave: (WorkoutProgram) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalGachiColors.current
    var program by remember {
        mutableStateOf(
            initial ?: WorkoutProgram(
                name = "New program",
                prepareSec = PREPARE_DEFAULT_SEC,
                groups = listOf(
                    ProgramGroup(
                        name = "Set",
                        blocks = listOf(ProgramBlock(name = "Work", workSec = 30, restSec = 30)),
                    )
                ),
            )
        )
    }

    val thing = if (asSchedule) "schedule" else "program"
    val needsName = program.name.isBlank()
    val needsEffort = program.totalSec() <= 0
    val saveEnabled = !needsName && !needsEffort

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                asSchedule -> "Schedule"
                                initial == null -> "New program"
                                else -> "Edit program"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            programSummary(program, forExercise.takeIf { asSchedule }),
                            fontSize = TextSize.Caption,
                            color = colors.inkSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (asSchedule) {
                                "Back to the exercise being created"
                            } else {
                                "Discard the changes"
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            SaveBar(
                label = when {
                    locked -> "Save the name and category"
                    asSchedule -> "Use this schedule"
                    else -> "Save"
                },
                enabled = saveEnabled,
                missing = when {
                    saveEnabled -> null
                    needsName && needsEffort ->
                        "A $thing needs a name and at least one effort longer than zero seconds."
                    needsName -> "Give it a name."
                    else -> "A $thing needs at least one effort longer than zero seconds."
                },
                onSave = { onSave(program) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(horizontal = Spacing.Block),
            verticalArrangement = Arrangement.spacedBy(Spacing.Block),
        ) {
            item {
                OutlinedTextField(
                    value = program.name,
                    onValueChange = { program = program.copy(name = it) },
                    label = { Text(if (asSchedule) "Schedule name" else "Program name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.Line),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }

            if (locked) {
                item { LockedNotice() }
                item { ReadValue(label = "Get ready", value = "${program.prepareSec} s") }
            } else {
                item {
                    SecondsField(
                        label = "Get ready, s",
                        value = program.prepareSec,
                        onValueChange = { program = program.copy(prepareSec = it) },
                        steps = listOf(5.0, 10.0),
                    )
                }
            }

            item {
                CategoryField(
                    value = program.category,
                    known = categories,
                    onValueChange = { program = program.copy(category = it) },
                )
            }

            // absent, not empty, while the exercise it belongs to is still being created
            // — see [asSchedule]
            if (!asSchedule) {
                item {
                    ExerciseLinkField(
                        candidates = candidates,
                        selectedId = program.exerciseId,
                        onSelect = { program = program.copy(exerciseId = it) },
                    )
                }
            }

            itemsIndexedGroups(program) { index, group ->
                // 16 from the arrangement plus 8 of its own is the 24 the scale asks for
                // between cards; the fields above are 16 apart, being one block each.
                Box(Modifier.padding(top = Spacing.Line)) {
                    if (locked) {
                        LockedGroupCard(index = index, group = group)
                    } else {
                        GroupCard(
                            index = index,
                            group = group,
                            canRemove = program.groups.size > 1,
                            onChange = { updated ->
                                program = program.copy(
                                    groups = program.groups.toMutableList().also { it[index] = updated }
                                )
                            },
                            onRemove = {
                                program = program.copy(
                                    groups = program.groups.toMutableList().also { it.removeAt(index) }
                                )
                            },
                        )
                    }
                }
            }

            if (!locked) {
                item {
                    OutlinedButton(
                        onClick = {
                            program = program.copy(
                                groups = program.groups + ProgramGroup(
                                    name = "Set ${program.groups.size + 1}",
                                    blocks = listOf(ProgramBlock(name = "Work", workSec = 30, restSec = 30)),
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Add a group") }
                }
            }

            item { Spacer(Modifier.height(Spacing.Cards)) }
        }
    }
}

/**
 * The one confirm, pinned under the list, with the reason it is grey above it.
 *
 * A `Surface` rather than a bare Column so that the list scrolling underneath does not show
 * through the button: this bar is the one part of the screen that never moves.
 */
@Composable
private fun SaveBar(
    label: String,
    enabled: Boolean,
    /** One line naming what is missing, or null when nothing is. */
    missing: String?,
    onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.Block, vertical = Spacing.Inset),
            verticalArrangement = Arrangement.spacedBy(Spacing.Line),
        ) {
            if (missing != null) MissingNote(missing)
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(label) }
        }
    }
}

/** How long a group runs BY ITSELF, in the words the top bar uses for the whole program. */
private fun groupSummary(group: ProgramGroup): String {
    /*
     * Computed by wrapping the group in a program of its own and asking the SAME [flatten]
     * the timer counts down — not by multiplying the numbers on screen, which is how a total
     * drifts away from the run it claims to describe.
     *
     * In isolation, which is a deliberate reading and not the only possible one: the group's
     * own `restAfterSec` is the gap BEFORE THE NEXT GROUP, and flatten drops a trailing rest,
     * so it is left out of this number. The mock-up (`app-next/program-editor.html`, section
     * A) counts that gap into the first group instead and gets 15:00 where this gets 12:57.
     * Isolation answers "how long is this group", which is the question the number sits inside
     * the group's own card to answer; the other reading answers "how much of the program is
     * this group's share", and two groups' shares then depend on the order they are in.
     */
    val alone = WorkoutProgram(name = "", groups = listOf(group), prepareSec = 0)
    val efforts = alone.workStepCount()
    val head = if (efforts == 1) "1 effort" else "$efforts efforts"
    return if (efforts == 0) head else "$head · ${formatClock(alone.totalSec())}"
}

/**
 * The line under the screen's title: how much there is of this, and what it is for.
 *
 * "N efforts · m:ss", one separator, the same middle dot every other total on these two
 * screens uses (rule 4). The clock is left off a program that has no time in it at all,
 * because "0:00" beside "no efforts yet" is the same nothing said twice.
 */
private fun programSummary(program: WorkoutProgram, forExercise: String?): String {
    val efforts = program.workStepCount()
    val parts = mutableListOf(
        when (efforts) {
            0 -> "no efforts yet"
            1 -> "1 effort"
            else -> "$efforts efforts"
        }
    )
    val total = program.totalSec()
    if (total > 0) parts += formatClock(total)
    if (!forExercise.isNullOrBlank()) parts += "for $forExercise"
    return parts.joinToString(" · ")
}

/**
 * The heading this program is filed under on the timer tab.
 *
 * A text field with the headings already in use offered as chips. Free text alone would
 * grow "Hangboard", "hangboard" and "Hang board" into three sections; a fixed list would
 * mean guessing what someone trains. Chips plus a field is the pair that avoids both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryField(value: String, known: List<String>, onValueChange: (String) -> Unit) {
    val colors = LocalGachiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Category") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        if (known.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
                verticalArrangement = Arrangement.spacedBy(Spacing.Line),
            ) {
                known.forEach { candidate ->
                    FilterChip(
                        selected = value.trim().equals(candidate, ignoreCase = true),
                        onClick = {
                            onValueChange(if (value.trim().equals(candidate, true)) "" else candidate)
                        },
                        label = { Text(candidate) },
                    )
                }
            }
        }
        Text(
            "Same category, same heading on the timer tab. Empty for none.",
            fontSize = TextSize.Caption,
            color = colors.inkMuted,
        )
    }
}

/**
 * Which catalog exercise this program trains, if it is exactly one.
 *
 * This is the field that decides whether finishing the program offers to write the sets
 * into the journal, so it says so out loud rather than sitting there as an unexplained
 * dropdown. Only holds are offered: a hold is the one form whose sets map onto timed
 * efforts one for one (§12-A).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseLinkField(
    candidates: List<ExerciseRef>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    val colors = LocalGachiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
        Text("Logs as".uppercase(), style = EyebrowStyle, color = colors.inkMuted)
        if (candidates.isEmpty()) {
            Text(
                "No hold exercise in the catalog yet, so there is nothing to link to.",
                fontSize = TextSize.Meta,
                color = colors.inkMuted,
            )
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
            verticalArrangement = Arrangement.spacedBy(Spacing.Line),
        ) {
            candidates.forEach { candidate ->
                FilterChip(
                    selected = selectedId == candidate.id,
                    onClick = { onSelect(if (selectedId == candidate.id) null else candidate.id) },
                    label = { Text(candidate.name) },
                )
            }
        }
        Text(
            if (selectedId == null) {
                "Not linked: finishing this program will ask which exercise it was, once."
            } else {
                "Finishing this program offers to log its sets under this exercise."
            },
            fontSize = TextSize.Caption,
            color = colors.inkMuted,
        )
    }
}

/** Small helper so the group loop reads as a list rather than as index arithmetic. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedGroups(
    program: WorkoutProgram,
    content: @Composable (Int, ProgramGroup) -> Unit,
) {
    program.groups.forEachIndexed { index, group ->
        item(key = "group-$index") { content(index, group) }
    }
}

/**
 * Why the content controls below are missing — see [ProgramEditorScreen]'s own KDoc on
 * [locked][ProgramEditorScreen] for the rule this states in one screen-facing sentence.
 *
 * A padlock, a heading and ONE sentence, where it used to be a four-line paragraph at the
 * 11 sp floor (rule 5). What the paragraph also said — that the name and the category are
 * still free — is not a sentence any more: those two are still fields, and everything else
 * is not, which the eye reads without being told.
 */
@Composable
private fun LockedNotice() {
    val colors = LocalGachiColors.current
    GachiCard(Modifier.fillMaxWidth(), background = colors.recessed) {
        Row(
            Modifier.padding(Spacing.Inset),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Inset),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null, // the heading beside it says the same thing
                tint = colors.inkSecondary,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                Text(
                    "Timing is fixed",
                    fontSize = TextSize.Body,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Sets have been recorded against it. To train other timings, create " +
                        "another exercise.",
                    fontSize = TextSize.Meta,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}

/**
 * One fact that is read rather than edited: what it is, and what it says.
 *
 * The two sizes are the point — 13 for the label and 17 for the value — because on the
 * paragraph this replaces both were the 11 sp floor, so nothing about the type said which
 * was the number.
 */
@Composable
private fun ReadValue(label: String, value: String) {
    val colors = LocalGachiColors.current
    GachiCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.Inset),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Text(label, fontSize = TextSize.Meta, color = colors.inkSecondary)
            Text(value, fontSize = TextSize.Title, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * A group's content, read rather than edited — see [LockedNotice].
 *
 * Laid out as a table of "effort — what it does" rather than as a sentence per block: the
 * line used to be "Hang: 7s work, 3s rest, x6", which packs three values, two units and
 * three different separators into one run of text.
 */
@Composable
private fun LockedGroupCard(index: Int, group: ProgramGroup) {
    val colors = LocalGachiColors.current
    GachiCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.Inset),
            verticalArrangement = Arrangement.spacedBy(Spacing.Line),
        ) {
            GroupHeader(index = index, group = group, menu = null)
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            group.blocks.forEach { block ->
                HorizontalDivider(color = colors.grid)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(block.name, fontSize = TextSize.Body, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${block.workSec} s",
                            fontSize = TextSize.Body,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            buildString {
                                append("rest after ${block.restSec} s")
                                if (block.repeats > 1) append(" · x${block.repeats}")
                            },
                            fontSize = TextSize.Caption,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }
            if (group.repeats > 1) {
                HorizontalDivider(color = colors.grid)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Whole group", fontSize = TextSize.Body, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "x${group.repeats}",
                            fontSize = TextSize.Body,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${formatClock(group.restBetweenRepeatsSec)} between repeats",
                            fontSize = TextSize.Caption,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The card of one group: which one it is, how long it runs, and the way to remove it.
 *
 * ── Removal moved into a menu (rule 3) ──────────────────────────────────────────
 * "Remove group" used to be a `TextButton` in the same `Row` as the label "Group", eight dp
 * from the field the name is typed into, and the block below it had its own "Remove" eight dp
 * from ANOTHER field — two ranks of destruction, named almost identically, both flush against
 * the controls used constantly. Both are now entries in [ActionMenu], which draws a
 * destructive entry in the critical colour behind a divider, and both are reachable only by
 * a deliberate second tap.
 */
@Composable
private fun GroupCard(
    index: Int,
    group: ProgramGroup,
    canRemove: Boolean,
    onChange: (ProgramGroup) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalGachiColors.current
    GachiCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.Inset),
            verticalArrangement = Arrangement.spacedBy(Spacing.Block),
        ) {
            GroupHeader(
                index = index,
                group = group,
                menu = if (canRemove) {
                    listOf(ItemAction("Remove the group", destructive = true, onClick = onRemove))
                } else {
                    null
                },
            )

            OutlinedTextField(
                value = group.name,
                onValueChange = { onChange(group.copy(name = it)) },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )

            group.blocks.forEachIndexed { blockIndex, block ->
                EffortBlock(
                    index = blockIndex,
                    block = block,
                    canRemove = group.blocks.size > 1,
                    onChange = { updated ->
                        onChange(
                            group.copy(
                                blocks = group.blocks.toMutableList().also { it[blockIndex] = updated }
                            )
                        )
                    },
                    onRemove = {
                        onChange(
                            group.copy(
                                blocks = group.blocks.toMutableList().also { it.removeAt(blockIndex) }
                            )
                        )
                    },
                )
            }

            OutlinedButton(
                onClick = {
                    onChange(
                        group.copy(
                            blocks = group.blocks + ProgramBlock(name = "Work", workSec = 30, restSec = 30)
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Add an effort") }

            HorizontalDivider(color = colors.grid)
            Text("Whole group".uppercase(), style = EyebrowStyle, color = colors.inkMuted)
            CountField(
                label = "Repeats",
                value = group.repeats,
                onValueChange = { onChange(group.copy(repeats = it)) },
            )
            SecondsField(
                label = "Between repeats, s",
                value = group.restBetweenRepeatsSec,
                onValueChange = { onChange(group.copy(restBetweenRepeatsSec = it)) },
                steps = listOf(15.0, 60.0),
            )
            SecondsField(
                label = "Before next group, s",
                value = group.restAfterSec,
                onValueChange = { onChange(group.copy(restAfterSec = it)) },
                steps = listOf(15.0, 60.0),
            )
        }
    }
}

/**
 * "GROUP 2 — 10 efforts · 7:30", and the menu if there is anything in it.
 *
 * The total is the addition the mock-up asked for: the top of the screen has always said how
 * long the whole program runs, and the group — which is the thing whose repeats are actually
 * being turned up and down — said nothing at all.
 */
@Composable
private fun GroupHeader(index: Int, group: ProgramGroup, menu: List<ItemAction>?) {
    val colors = LocalGachiColors.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Text("Group ${index + 1}".uppercase(), style = EyebrowStyle, color = colors.inkMuted)
            Text(groupSummary(group), fontSize = TextSize.Caption, color = colors.inkSecondary)
        }
        if (menu != null) {
            Box {
                IconButton(onClick = { open = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Actions for group ${index + 1}",
                    )
                }
                ActionMenu(open, { open = false }, group.name.ifBlank { "Group ${index + 1}" }, menu)
            }
        }
    }
}

/**
 * One timed effort of a group: its own hairline, its own number, its own menu.
 *
 * ── Why it is called an EFFORT ──────────────────────────────────────────────────
 * The field used to be labelled "Exercise", and in this app an exercise is a catalog row with
 * a history and a record behind it — not a line in a program. The same word in two senses on
 * neighbouring screens is the worst economy available. The domain had the right word already:
 * `workStepCount` is documented as "how many efforts is this".
 *
 * ── Why the line above it ───────────────────────────────────────────────────────
 * A group's blocks used to be plain columns one after another, so two or three of them were a
 * ribbon of nine to twelve fields with nothing marking where one effort ended and the next
 * began.
 */
@Composable
private fun EffortBlock(
    index: Int,
    block: ProgramBlock,
    canRemove: Boolean,
    onChange: (ProgramBlock) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalGachiColors.current
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Line),
    ) {
        HorizontalDivider(color = colors.grid)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Effort ${index + 1}".uppercase(),
                style = EyebrowStyle,
                color = colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
            if (canRemove) {
                Box {
                    IconButton(onClick = { open = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Actions for effort ${index + 1}",
                        )
                    }
                    ActionMenu(
                        open,
                        { open = false },
                        block.name.ifBlank { "Effort ${index + 1}" },
                        listOf(
                            ItemAction("Remove the effort", destructive = true, onClick = onRemove)
                        ),
                    )
                }
            }
        }
        OutlinedTextField(
            value = block.name,
            onValueChange = { onChange(block.copy(name = it)) },
            label = { Text("Effort name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        SecondsField(
            label = "Work, s",
            value = block.workSec,
            onValueChange = { onChange(block.copy(workSec = it)) },
            steps = listOf(1.0, 10.0),
        )
        SecondsField(
            label = "Rest after, s",
            value = block.restSec,
            onValueChange = { onChange(block.copy(restSec = it)) },
            steps = listOf(1.0, 10.0),
        )
        CountField(
            label = "Repeats",
            value = block.repeats,
            onValueChange = { onChange(block.copy(repeats = it)) },
        )
    }
}

/**
 * An integer field on top of the logging screen's stepper.
 *
 * The value is kept as text while it is being edited so that clearing the field does not
 * snap it back to zero under the cursor; an unparseable value reads as zero, which for
 * every field here means "none", not "invalid".
 */
@Composable
private fun SecondsField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    steps: List<Double>,
) {
    var text by remember(value) { mutableStateOf(if (value == 0) "" else value.toString()) }
    StepperField(
        label = label,
        value = text,
        onValueChange = {
            text = it
            onValueChange(it.filter(Char::isDigit).toIntOrNull() ?: 0)
        },
        steps = steps,
        decimal = false,
    )
}

@Composable
private fun CountField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    StepperField(
        label = label,
        value = text,
        onValueChange = {
            text = it
            onValueChange((it.filter(Char::isDigit).toIntOrNull() ?: 1).coerceAtLeast(1))
        },
        steps = listOf(1.0),
        decimal = false,
    )
}
