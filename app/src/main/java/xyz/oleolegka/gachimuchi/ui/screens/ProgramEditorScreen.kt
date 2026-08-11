package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import xyz.oleolegka.gachimuchi.ui.components.StepperField
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

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
 * ── [locked]: an existing exercise's protocol, opened for a look ────────────────
 * True when some exercise's protocol currently IS [initial] — see
 * [xyz.oleolegka.gachimuchi.data.ProgramRepository.isReferenced], which this is computed from
 * before the screen ever opens. Locked, the lead-in and every group and block are shown as
 * READ TEXT rather than fields: "such a thing cannot happen: it breaks the statistics. If
 * yesterday it was one protocol and today another, that is a NEW exercise" is the owner's own
 * words for why this screen must not be the second door onto the same mistake the exercise
 * editor already closed (`ui/components/ExerciseEditor.kt`'s `EditExerciseDialog`).
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
    /** Whether [initial] is some exercise's protocol right now — see the KDoc above. */
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
                            "${program.workStepCount()} efforts   " +
                                "${formatClock(program.totalSec())} total",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Discard the changes")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(program) },
                        enabled = program.name.isNotBlank() && program.totalSec() > 0,
                    ) { Text(if (asSchedule) "Use" else "Save") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = program.name,
                    onValueChange = { program = program.copy(name = it) },
                    label = { Text(if (asSchedule) "Schedule name" else "Program name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }

            if (locked) {
                item { LockedNotice() }
                item {
                    Text(
                        "Get ready before the first effort: ${program.prepareSec} s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
            } else {
                item {
                    SecondsField(
                        label = "Get ready before the first effort, seconds",
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
                if (locked) {
                    LockedGroupCard(group)
                } else {
                    GroupCard(
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

            item {
                Button(
                    onClick = { onSave(program) },
                    enabled = program.name.isNotBlank() && program.totalSec() > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(if (asSchedule) "Use this schedule" else "Save") }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Category (empty for none)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        if (known.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            "Programs with the same category are grouped together and can be collapsed.",
            style = MaterialTheme.typography.labelSmall,
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Logs as", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        if (candidates.isEmpty()) {
            Text(
                "There is no hold exercise in the catalog yet. Create one and this program " +
                    "can write its sets into the journal when it finishes.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            style = MaterialTheme.typography.labelSmall,
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
 */
@Composable
private fun LockedNotice() {
    val colors = LocalGachiColors.current
    Card(Modifier.fillMaxWidth()) {
        Text(
            "This program is a running exercise's protocol, so its timing is fixed: " +
                "changing it here would change the exercise's history along with it. Rename " +
                "it or move it to another category freely - to change the timing, create a " +
                "new exercise with the protocol you want.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** A group's content, read rather than edited — see [LockedNotice]. */
@Composable
private fun LockedGroupCard(group: ProgramGroup) {
    val colors = LocalGachiColors.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            group.blocks.forEach { block ->
                Text(
                    "${block.name}: ${block.workSec}s work, ${block.restSec}s rest" +
                        if (block.repeats > 1) ", x${block.repeats}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (group.repeats > 1) {
                Text(
                    "Repeated x${group.repeats}, ${group.restBetweenRepeatsSec}s between",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: ProgramGroup,
    canRemove: Boolean,
    onChange: (ProgramGroup) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalGachiColors.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Group", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                if (canRemove) TextButton(onClick = onRemove) { Text("Remove group") }
            }

            OutlinedTextField(
                value = group.name,
                onValueChange = { onChange(group.copy(name = it)) },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )

            group.blocks.forEachIndexed { index, block ->
                BlockRow(
                    block = block,
                    canRemove = group.blocks.size > 1,
                    onChange = { updated ->
                        onChange(
                            group.copy(
                                blocks = group.blocks.toMutableList().also { it[index] = updated }
                            )
                        )
                    },
                    onRemove = {
                        onChange(
                            group.copy(
                                blocks = group.blocks.toMutableList().also { it.removeAt(index) }
                            )
                        )
                    },
                )
            }

            TextButton(
                onClick = {
                    onChange(
                        group.copy(
                            blocks = group.blocks + ProgramBlock(name = "Work", workSec = 30, restSec = 30)
                        )
                    )
                }
            ) { Text("Add an exercise to this group") }

            CountField(
                label = "Repeat the whole group",
                value = group.repeats,
                onValueChange = { onChange(group.copy(repeats = it)) },
            )
            SecondsField(
                label = "Rest between those repeats, seconds",
                value = group.restBetweenRepeatsSec,
                onValueChange = { onChange(group.copy(restBetweenRepeatsSec = it)) },
                steps = listOf(15.0, 60.0),
            )
            SecondsField(
                label = "Rest before the next group, seconds",
                value = group.restAfterSec,
                onValueChange = { onChange(group.copy(restAfterSec = it)) },
                steps = listOf(15.0, 60.0),
            )
        }
    }
}

@Composable
private fun BlockRow(
    block: ProgramBlock,
    canRemove: Boolean,
    onChange: (ProgramBlock) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedTextField(
                value = block.name,
                onValueChange = { onChange(block.copy(name = it)) },
                label = { Text("Exercise") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            if (canRemove) TextButton(onClick = onRemove) { Text("Remove") }
        }
        SecondsField(
            label = "Work, seconds",
            value = block.workSec,
            onValueChange = { onChange(block.copy(workSec = it)) },
            steps = listOf(1.0, 10.0),
        )
        SecondsField(
            label = "Rest after it, seconds",
            value = block.restSec,
            onValueChange = { onChange(block.copy(restSec = it)) },
            steps = listOf(1.0, 10.0),
        )
        CountField(
            label = "Repeat this exercise",
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
