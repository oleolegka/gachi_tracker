package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import xyz.oleolegka.gachimuchi.domain.programSections
import xyz.oleolegka.gachimuchi.domain.stepRemainingMs
import xyz.oleolegka.gachimuchi.domain.totalRemainingMs
import xyz.oleolegka.gachimuchi.domain.totalSec
import xyz.oleolegka.gachimuchi.domain.workStepCount
import xyz.oleolegka.gachimuchi.ui.components.EmptyState
import xyz.oleolegka.gachimuchi.ui.components.ItemAction
import xyz.oleolegka.gachimuchi.ui.components.ItemActions
import xyz.oleolegka.gachimuchi.ui.components.TimerActions
import xyz.oleolegka.gachimuchi.ui.components.TimerUiState
import xyz.oleolegka.gachimuchi.ui.components.isEffort
import xyz.oleolegka.gachimuchi.ui.components.rememberProgramTransfer
import xyz.oleolegka.gachimuchi.ui.components.rememberTickingNow
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * The programs tab: the LIBRARY of programs — what is kept, how it is filed, how it goes in
 * and out of a file — plus what is running and what the timer is allowed to do.
 *
 * ── A store, not a starting line ────────────────────────────────────────────────
 * A protocol is started from the exercise card inside the workout being done, on the day it
 * is trained, because that is where the app knows what the run should be logged against.
 * This tab used to be called Timer and read like the place a session begins, which sent
 * people here to start training and left them looking at a list of everything they own.
 * So the tab, the heading and the empty state all say the same thing now: this is where
 * programs are written, filed, sent and fetched.
 *
 * The Run button on each card stays, and it is not a contradiction: running a program
 * straight from the library is the right thing for the one that belongs to no exercise —
 * a Tabata, a warm-up — and for trying out something just edited.
 *
 * ── Two kinds of thing live here, and they are not mixed ───────────────────────
 * Programs the owner wrote or imported, and the SCHEDULES the app generated for single hold
 * exercises. Only the first kind is his filing; the second is machinery that happens to be
 * stored the same way, is frozen the moment the exercise starts using it, and read as junk
 * left in the library when it sat between a Tabata and a set of repeaters. So schedules come
 * last, under one heading of their own, each row naming the exercise it belongs to
 * (decisions §18.15, `domain/Program.kt`'s programSections).
 *
 * One scrolling screen rather than a section with its own navigation. There are only three
 * things here and they are read in this order — the live run first, because when anything is
 * running that is the only thing that matters; then the programs; then the settings, which
 * are visited once and then never again.
 */
@Composable
fun TimerScreen(
    state: TimerUiState,
    actions: TimerActions,
    programs: List<WorkoutProgram>,
    /** Catalog names by exercise id, so a linked program can say what it trains. */
    exerciseNames: Map<Long, String>,
    /**
     * The exercises whose protocol a program IS, by program id — the schedules of decisions
     * §18.15. Keys are what gets filed under its own heading; values are the names the row
     * says it belongs to (more than one when twins share a schedule). Empty map means no
     * schedule section at all, which is what a library of hand-written programs looks like.
     */
    scheduleOwners: Map<Long, List<String>>,
    /**
     * Of [scheduleOwners], the ones that are FROZEN — a set has been recorded against them, so
     * their times are history now and the card offers no delete (§18.19). A schedule not in
     * here is still a draft: it is filed under the schedules heading all the same, and it can
     * still be edited and deleted, which is exactly what the mild freeze buys.
     */
    frozenSchedules: Set<Long>,
    onRunProgram: (WorkoutProgram) -> Unit,
    onEditProgram: (WorkoutProgram?) -> Unit,
    onDeleteProgram: (Long) -> Unit,
    /** Hides a program from the list below, or brings a hidden one back into it. */
    onToggleHiddenProgram: (WorkoutProgram) -> Unit,
    onImportPrograms: (List<WorkoutProgram>) -> Unit,
    onSettings: (TimerSettings) -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    // owns the pickers and the dialogs of exporting and importing; see ProgramTransfer.kt
    val transfer = rememberProgramTransfer(onImported = onImportPrograms)
    /*
     * Hidden programs are dropped HERE and only here, the same boundary
     * ExercisePicker.kt's PickExisting draws for a hidden exercise: this is the list read
     * while looking for something to run or file, and a program stopped being reached for is
     * clutter in it and nowhere else. The row itself keeps running exactly as before — see
     * ProgramEntity.hidden — and stays reachable in the tray at the bottom of this screen.
     */
    val visiblePrograms = remember(programs) { programs.filter { !it.hidden } }
    val hiddenPrograms = remember(programs) { programs.filter { it.hidden } }
    val sections = remember(visiblePrograms, scheduleOwners) {
        programSections(visiblePrograms, scheduleOwners.keys)
    }
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var hiddenTrayOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        // 16 is the screen's own side margin on the scale, and it is what the redraw's
        // arithmetic assumes when it says a card on a 360 dp phone is 328 wide
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.Block),
        // the same 8/24 the other tabs use. Without it this list began flush against the
        // top of the window, which is why the app-name bar clipped the first card HERE
        // first, and ended flush against the navigation bar at the bottom
        contentPadding = PaddingValues(top = Spacing.Line, bottom = Spacing.Cards),
        verticalArrangement = Arrangement.spacedBy(Spacing.Line),
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
            Column(
                Modifier.padding(top = Spacing.Line),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Text("Your programs", style = MaterialTheme.typography.titleMedium)
                // says what the tab is FOR, because the answer is not "start training here".
                // One sentence: three lines of small print over every opening of the tab is
                // the thing SYSTEM.md rule 5 is about.
                Text(
                    "Kept and filed here. A session starts from the exercise, in the workout " +
                        "you are doing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }

        if (visiblePrograms.isEmpty() && hiddenPrograms.isEmpty()) {
            item {
                /*
                 * A heading with two buttons under it and nothing in between reads as a
                 * screen that failed to load, not as a screen with nothing on it. It is a
                 * reachable state — the starter programs are deletable, and now that the app
                 * no longer fills itself in on first launch it is a state a new phone can be
                 * in — so it says what a program is and what the buttons below will do.
                 */
                EmptyState(
                    title = "The library is empty",
                    hint = "A program is a sequence of timed efforts and rests - hangboard " +
                        "repeaters, a Tabata, a circuit. This is where they are kept, so " +
                        "there is nothing to reach for yet: build one with \"New program\", " +
                        "or read one in from a file someone sent you.",
                )
            }
        }

        /*
         * Filed under the headings the user wrote (domain/Program.kt). A list with nothing
         * categorised comes back as one section with no title and draws exactly as it did
         * before, so a phone with three programs is not made to look like a filing cabinet.
         *
         * Sections collapse, and which ones are collapsed is remembered across a rotation
         * but not across a launch: a heading closed last week should not hide the program
         * being looked for today.
         */
        sections.forEach { section ->
            if (section.title.isNotEmpty()) {
                item(key = "section-${section.title}") {
                    SectionHeader(
                        title = section.title,
                        count = section.programs.size,
                        collapsed = section.title in collapsed,
                        onToggle = {
                            collapsed = if (section.title in collapsed) {
                                collapsed - section.title
                            } else {
                                collapsed + section.title
                            }
                        },
                    )
                }
            }
            if (section.title !in collapsed) {
                /*
                 * The schedules say once, under their heading, what the whole section is —
                 * repeating "the app made this, and it no longer changes" on every card would
                 * be four lines of the same sentence on a phone that owns four holds.
                 */
                if (section.schedules) {
                    item(key = "schedules-note") {
                        Text(
                            "Made for one exercise. Editable until the first set is " +
                                "recorded against it; after that only the name can be " +
                                "changed, and it cannot be deleted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted,
                        )
                    }
                }
                items(section.programs, key = { it.id }) { program ->
                    ProgramCard(
                        program = program,
                        enabled = state.enabled,
                        exerciseName = exerciseNames[program.exerciseId],
                        scheduleFor = scheduleOwners[program.id].orEmpty(),
                        frozen = program.id in frozenSchedules,
                        onRun = { onRunProgram(program) },
                        onEdit = { onEditProgram(program) },
                        onExport = { transfer.export(listOf(program)) },
                        onDelete = { onDeleteProgram(program.id) },
                        onToggleHidden = { onToggleHiddenProgram(program) },
                    )
                }
            }
        }

        /*
         * Hidden programs are still here, collapsed by default — the same "$count hidden -
         * show" idea [SectionHeader] already draws for a closed category, reused so a program
         * put away is never a program nobody can find again. Unlike an exercise (brought back
         * only from FormDetailScreen, reached from its own history) a program has no other
         * screen it lives on, so the tray has to be it.
         */
        if (hiddenPrograms.isNotEmpty()) {
            item(key = "hidden-programs-header") {
                SectionHeader(
                    title = "Hidden",
                    count = hiddenPrograms.size,
                    collapsed = !hiddenTrayOpen,
                    onToggle = { hiddenTrayOpen = !hiddenTrayOpen },
                )
            }
            if (hiddenTrayOpen) {
                items(hiddenPrograms, key = { "hidden-${it.id}" }) { program ->
                    ProgramCard(
                        program = program,
                        enabled = state.enabled,
                        exerciseName = exerciseNames[program.exerciseId],
                        // a schedule put away still says whose it is: the tray is one flat
                        // list, so the heading that would have said it is not there
                        scheduleFor = scheduleOwners[program.id].orEmpty(),
                        frozen = program.id in frozenSchedules,
                        onRun = { onRunProgram(program) },
                        onEdit = { onEditProgram(program) },
                        onExport = { transfer.export(listOf(program)) },
                        onDelete = { onDeleteProgram(program.id) },
                        onToggleHidden = { onToggleHiddenProgram(program) },
                    )
                }
            }
        }

        item {
            // the frequent one of the three, so it is the one that looks like a button.
            // All three were identical OutlinedButtons: "Export all" is pressed once a year.
            Button(
                onClick = { onEditProgram(null) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("New program") }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
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

/**
 * The live run, in the size you can read from the floor.
 *
 * Shared with [ConductorScreen] rather than private, because a protocol-led set started from
 * an exercise card and one started from this tab are the same run being read in the same
 * position — arms out, a metre or two from the phone. Two copies of this panel would be two
 * places for the step name and the time left to start disagreeing.
 */
@Composable
internal fun RunPanel(state: TimerUiState, actions: TimerActions) {
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
                    fontSize = TextSize.Display,
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

/**
 * A collapsible heading. The count is on it so a closed section still says how big it is.
 *
 * The count is a COUNT in both states. It used to read "5 hidden - show" when closed, and that
 * collided with the tray of hidden programs further down this same screen — one word for two
 * different things — while the chevron already says which state the section is in (SYSTEM.md,
 * owner's comment 4: one state, one wording).
 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalGachiColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(top = Spacing.Line),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (collapsed) "Show $title" else "Hide $title",
                tint = colors.inkMuted,
                modifier = Modifier.size(16.dp),
            )
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Text(
            "$count",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
    }
    HorizontalDivider(color = colors.grid)
}

/**
 * One row of the library: a name, a line of meta, ONE button, and a menu behind three dots.
 *
 * ── Why the five actions are not five buttons ───────────────────────────────────
 * They were, in one `Row`, with "Run" on a `weight(1f)`. Edit, Export, Hide and Delete want
 * something like 245-270 dp of the 312 a 360 dp phone leaves inside the card, so "Run" — the
 * only thing on the card anybody comes here to press — was handed 26 to 51 dp, below a Material
 * button's own 58 dp minimum, with its label clipped. It is now the full width of the card, and
 * the other four are in the menu, where they have room for the words that say what they do
 * ("Export to a file" rather than "Export"). SYSTEM.md rules 1 and 8.
 *
 * ── The schedules (decisions §18.15) ────────────────────────────────────────────
 * [scheduleFor] non-empty means this program is some exercise's schedule: the row says WHOSE it
 * is, and its menu carries no deletion at all. The name belongs on the row and not only in the
 * editor, because the freeze was otherwise discoverable only by opening the program and finding
 * its fields turned into text — by which point the owner had already gone looking for a program
 * he never wrote. The missing delete is the working agreement's own rule: a forbidden action
 * loses the control that STARTS it, not the one that finishes it.
 *
 * That the times are fixed is said ONCE, in the note under the section heading, rather than on
 * every row and again at the foot of every card (rule 5: an explanation is not a paragraph).
 */
@Composable
private fun ProgramCard(
    program: WorkoutProgram,
    enabled: Boolean,
    exerciseName: String?,
    scheduleFor: List<String> = emptyList(),
    frozen: Boolean = false,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    val colors = LocalGachiColors.current
    /*
     * Everything that is not "run this" lives in the menu, and the menu is the one every other
     * card in this app already uses (ui/components/ItemActions.kt): the same long press, the
     * same three dots in front of it, the same divider and critical colour under a destructive
     * entry. "Open" rather than "Edit" because a frozen schedule is opened and read, not edited,
     * and one word that is true of both beats two of which one lies.
     */
    val menu = buildList {
        add(ItemAction("Open", onClick = onEdit))
        add(ItemAction("Export to a file", onClick = onExport))
        add(
            ItemAction(
                if (program.hidden) "Show in this list" else "Hide from this list",
                onClick = onToggleHidden,
            )
        )
        /*
         * No delete on a FROZEN schedule, rather than a refusal after it is pressed: the
         * exercise is keyed to this program's uid and nothing cascades, so deleting it would
         * leave that exercise pointing at a row that is gone while its sets still name the
         * times it used to hold. The repository refuses too (ProgramRepository.delete) — this
         * is the door, that is the lock, and both ask the same question now (§18.19).
         *
         * [scheduleFor] is deliberately NOT the test any more. Being somebody's schedule is
         * what files this card under its heading; being trained on is what shuts it. A
         * schedule with no sets against it yet is a draft the owner is still assembling, and
         * refusing to delete it was the punishment §18.19 removed.
         */
        if (!frozen) {
            add(ItemAction("Delete the program", destructive = true, onClick = onDelete))
        }
    }

    ItemActions(
        title = program.name,
        actions = menu,
        onTap = onEdit,
        modifier = Modifier.fillMaxWidth(),
    ) { press, openMenu ->
        Card(Modifier.fillMaxWidth().then(press)) {
            Column(
                Modifier.padding(Spacing.Inset),
                verticalArrangement = Arrangement.spacedBy(Spacing.Line),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    ) {
                        Text(program.name, style = MaterialTheme.typography.titleMedium)
                        if (scheduleFor.isNotEmpty()) {
                            // WHOSE it is: the one thing about a schedule that the note under
                            // the section heading cannot say, because it differs per row
                            Text(
                                "Schedule for ${scheduleFor.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                            )
                        }
                        Text(
                            buildString {
                                append("${program.workStepCount()} efforts")
                                append(" $META_SEPARATOR ${formatClock(program.totalSec())}")
                                // stated on the card, because it is what decides whether
                                // finishing this program offers to write the sets down - but
                                // not when the line above has just named the same exercise,
                                // which is the usual case for a schedule
                                exerciseName?.takeIf { it !in scheduleFor }?.let {
                                    append(" $META_SEPARATOR logs as $it")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted,
                        )
                    }
                    IconButton(onClick = openMenu, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Actions for ${program.name}",
                            tint = colors.inkMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                /*
                 * THE ONE ACTION OF THIS CARD, across the whole of it.
                 *
                 * It used to share a row with four text buttons and a `weight(1f)`, which meant
                 * it was handed whatever they left over: Edit, Export, Hide and Delete want some
                 * 245-270 dp of the 312 a 360 dp screen has, so "Run" got between 26 and 51 —
                 * less than a Material button's own 58 dp minimum, with its label clipped. The
                 * main action of a card cannot be the narrowest thing on it (SYSTEM.md rule 8,
                 * and rule 1: one action, one button).
                 *
                 * Disabled, it says WHY on itself. The "The timer is off" card is at the top of
                 * the list and is long out of sight by the time a dead button is pressed.
                 */
                Button(
                    onClick = onRun,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (enabled) "Run" else "Run - the timer is off")
                }
            }
        }
    }
}

/** One separator for a meta line, so "a · b · c" never becomes "a   b   c" on the next card. */
private const val META_SEPARATOR = "·"

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
            subtitle = "Recording a set starts that exercise's rest. Several run at once, " +
                "one per exercise, so a superset counts them all.",
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
