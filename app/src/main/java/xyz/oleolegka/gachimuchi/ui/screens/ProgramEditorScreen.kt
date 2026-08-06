package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramEditorScreen(
    initial: WorkoutProgram?,
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
                            if (initial == null) "New program" else "Edit program",
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
                    ) { Text("Save") }
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
                    label = { Text("Program name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }

            item {
                SecondsField(
                    label = "Get ready before the first effort, seconds",
                    value = program.prepareSec,
                    onValueChange = { program = program.copy(prepareSec = it) },
                    steps = listOf(5.0, 10.0),
                )
            }

            itemsIndexedGroups(program) { index, group ->
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

            item {
                Button(
                    onClick = { onSave(program) },
                    enabled = program.name.isNotBlank() && program.totalSec() > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text("Save") }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
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
