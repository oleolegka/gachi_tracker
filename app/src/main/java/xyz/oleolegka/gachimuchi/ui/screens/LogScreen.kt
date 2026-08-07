package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.Session
import xyz.oleolegka.gachimuchi.domain.SessionGroup
import xyz.oleolegka.gachimuchi.domain.SessionSet
import xyz.oleolegka.gachimuchi.domain.bodyweightOf
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.cardioOf
import xyz.oleolegka.gachimuchi.domain.durationOf
import xyz.oleolegka.gachimuchi.domain.formatNumber
import xyz.oleolegka.gachimuchi.domain.formatPace
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.lastBodyweight
import xyz.oleolegka.gachimuchi.domain.lastCardio
import xyz.oleolegka.gachimuchi.domain.lastDuration
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.lastStrengthSet
import xyz.oleolegka.gachimuchi.domain.parseCount
import xyz.oleolegka.gachimuchi.domain.parseNumber
import xyz.oleolegka.gachimuchi.domain.parsePace
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.tickOf
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.StepperField
import xyz.oleolegka.gachimuchi.ui.components.TimerActions
import xyz.oleolegka.gachimuchi.ui.components.TimerBar
import xyz.oleolegka.gachimuchi.ui.components.TimerUiState
import xyz.oleolegka.gachimuchi.ui.fmtDay
import xyz.oleolegka.gachimuchi.ui.fmtRest
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * Logging a workout — the screen the app exists for.
 *
 * ── The layout, and why ─────────────────────────────────────────────────────────
 * The session tape scrolls, and the ENTRY CARD IS PINNED TO THE BOTTOM. That is the
 * whole point: between sets the phone is held in one hand and everything that gets
 * tapped — the step buttons and the big Add button — has to sit inside the arc of a
 * thumb. A form at the top of a scrolling list fails that within two exercises.
 *
 * ── One tap for another set of the same ─────────────────────────────────────────
 * The card is PREFILLED from the last set of that exercise, taken from the journal (not
 * from screen state, so it survives closing the app). The common case — same weight,
 * same reps, one more time — is therefore a single tap on the primary button, which
 * renames itself to "Repeat set" when the values are untouched.
 *
 * ── One screen, no navigation ───────────────────────────────────────────────────
 * Choosing an exercise is a bottom sheet, not a route; the session stays visible behind
 * it. Nothing here pushes a back stack, so the back gesture means exactly one thing:
 * leave the workout.
 *
 * ── The day is given, not assumed ───────────────────────────────────────────────
 * [day] is the day being written under, and it is NOT necessarily today. It used to be:
 * the screen took "today" and stamped it onto every form it built. That broke the moment a
 * workout could be dated to a day already gone — the workout would show the set (a workout
 * claims its rows by id) while the calendar filed it under today (the calendar reads the
 * payload), and an append-only journal offers no way to correct it afterwards. The caller
 * resolves the day through `loggingDay` and hands it here; see ui/GachiApp.kt.
 *
 * ── What this screen is FOR now: an entry on its own ────────────────────────────
 * Recording INSIDE a workout is [WorkoutLogScreen] — a card per exercise, each with its own
 * rest counting under it, which is what §13.2 asked for and what the single "active exercise"
 * below could never do. This screen keeps the other case, the one that has no workout at all:
 * the stretching in front of the television, reached by "Add - single entry" on a day.
 *
 * The tape below is everything logged on [day] (domain/Session.kt), which for a single entry
 * is the right scope — there is no workout to narrow it to. Sets written from here are
 * DELIBERATELY not attached to whatever workout happens to be open; see the call site in
 * ui/GachiApp.kt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    state: UiState,
    day: LocalDate,
    activeExerciseId: Long?,
    timer: TimerUiState,
    timerActions: TimerActions,
    onEnableTimer: () -> Unit,
    onStartExerciseProgram: (ExerciseRef) -> Unit,
    onSelectExercise: (Long?) -> Unit,
    onCreateExercise: (String, ExerciseForm, Double?, Double?, Double?) -> Unit,
    onAddSet: (ActivityForm) -> Unit,
    onUndoSet: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalGachiColors.current
    val iso = day.toString()
    val session = remember(state.events, iso) { buildSession(state.events, iso) }
    val active = state.refById(activeExerciseId)
    var picking by remember { mutableStateOf(false) }

    /*
     * A first run has nothing to log against, and every prompt on this screen would
     * otherwise say "choose" — an instruction with nothing to choose from. When the catalog
     * is empty the screen asks for an exercise to be CREATED instead, and the picker opens
     * straight on its create form rather than on a search box over an empty list.
     */
    val catalogEmpty = state.exercises.isEmpty()

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Workout", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${fmtDay(day)} - ${session.setCount} entries, " +
                                "${session.groups.size} exercises",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close the workout")
                    }
                },
                actions = {
                    // undo is a small, deliberately unglamorous target far from the big
                    // Add button: a mis-tap here would cancel a set that was actually done
                    TextButton(
                        onClick = { session.lastEventId?.let(onUndoSet) },
                        enabled = session.lastEventId != null,
                    ) { Text("Undo last") }
                },
            )
        },
        bottomBar = {
            /*
             * The timer sits ABOVE the entry card, not over it. It is a glance-at thing
             * and the card is the thing being used, so the timer gets one line and the
             * card keeps every pixel it had — the whole reason the card is pinned down
             * here is that its buttons must stay inside the arc of a thumb.
             */
            Column {
                TimerBar(
                    state = timer,
                    actions = timerActions,
                    exercise = active,
                    onStartExerciseProgram = { active?.let(onStartExerciseProgram) },
                    onEnable = onEnableTimer,
                )
                EntryPanel(
                    state = state,
                    exercise = active,
                    opDate = iso,
                    catalogEmpty = catalogEmpty,
                    onPick = { picking = true },
                    onAddSet = onAddSet,
                )
            }
        },
    ) { padding ->
        SessionFeed(
            session = session,
            activeExerciseId = activeExerciseId,
            catalogEmpty = catalogEmpty,
            onSelectExercise = onSelectExercise,
            onPick = { picking = true },
            modifier = Modifier.padding(padding),
        )
    }

    if (picking) {
        ExercisePickerSheet(
            state = state,
            today = day,
            startInCreate = catalogEmpty,
            onPick = onSelectExercise,
            onCreate = onCreateExercise,
            onDismiss = { picking = false },
        )
    }
}

/**
 * The tape of what has been done today: exercises in the order they first appeared, sets
 * inside them in the order they were recorded. Tapping a block points the entry card back
 * at that exercise — coming back to something already started costs ONE tap, without the
 * picker.
 */
@Composable
private fun SessionFeed(
    session: Session,
    activeExerciseId: Long?,
    catalogEmpty: Boolean,
    onSelectExercise: (Long) -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (session.isEmpty) {
            item {
                Column(Modifier.padding(top = 24.dp)) {
                    Text(
                        if (catalogEmpty) "Nothing to log against yet." else "Nothing logged today yet.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (catalogEmpty) {
                            // said in full, because the word does a lot of work here and the
                            // screen is being read by someone who has never seen it before
                            "An exercise is the thing sets are recorded against - \"Bench " +
                                "press\", \"Boulder gym\", \"Hangs 20 mm\". Create one and the " +
                                "card below turns into the fields that suit it: weight and " +
                                "reps, a distance, or a single check-in."
                        } else {
                            "Pick an exercise below and record the first set. Everything you " +
                                "add shows up here, newest at the bottom."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = onPick, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (catalogEmpty) "Create your first exercise" else "Choose an exercise")
                    }
                }
            }
        }

        items(session.groups, key = { it.groupKey }) { group ->
            SessionGroupCard(
                group = group,
                active = group.exerciseId != null && group.exerciseId == activeExerciseId,
                onClick = { group.exerciseId?.let(onSelectExercise) },
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SessionGroupCard(group: SessionGroup, active: Boolean, onClick: () -> Unit) {
    val colors = LocalGachiColors.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = group.exerciseId != null, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${group.sets.size} sets",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
            }
            group.sets.forEachIndexed { index, set -> SetRow(index + 1, set) }
        }
    }
}

@Composable
private fun SetRow(number: Int, set: SessionSet) {
    val colors = LocalGachiColors.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
                modifier = Modifier.width(20.dp),
            )
            Text(set.form.summaryLine(), style = MaterialTheme.typography.bodyMedium)
            set.restBeforeSec?.let {
                Text(
                    "   rest ${fmtRest(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
            }
        }
        // a record is stated in words, never by colour alone
        set.record?.let {
            Text(
                "Record: ${it.text}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.good,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
    }
}

/**
 * The pinned entry card. Which fields it shows is decided by the FORM OF THE EXERCISE
 * (§3), and an exercise has exactly one form, so nothing here is ever asked twice.
 */
@Composable
private fun EntryPanel(
    state: UiState,
    exercise: ExerciseRef?,
    opDate: String,
    catalogEmpty: Boolean,
    onPick: () -> Unit,
    onAddSet: (ActivityForm) -> Unit,
) {
    val colors = LocalGachiColors.current
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HorizontalDivider(color = colors.grid)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onPick),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        exercise?.name
                            ?: if (catalogEmpty) "No exercises yet" else "No exercise chosen",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        exercise?.let { contextLine(it) }
                            ?: if (catalogEmpty) "add the first one to start logging" else "tap to choose",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }
                if (exercise != null) {
                    TextButton(onClick = onPick) { Text("Change") }
                }
            }

            if (exercise == null) {
                Button(
                    onClick = onPick,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(if (catalogEmpty) "Create your first exercise" else "Choose an exercise") }
            } else {
                when (exercise.form) {
                    ExerciseForm.STRENGTH -> StrengthEntry(state, exercise, opDate, onAddSet)
                    ExerciseForm.HOLD -> HoldEntry(state, exercise, opDate, onAddSet)
                    ExerciseForm.CARDIO -> CardioEntry(state, exercise, opDate, onAddSet)
                    ExerciseForm.DURATION -> DurationEntry(state, exercise, opDate, onAddSet)
                    ExerciseForm.TICK -> TickEntry(exercise, opDate, onAddSet)
                    ExerciseForm.BODYWEIGHT -> BodyweightEntry(state, opDate, onAddSet)
                }
            }
        }
    }
}

/** The read-only context of an exercise: for holds, the §12-A identity spelled out. */
private fun contextLine(exercise: ExerciseRef): String = buildString {
    append(exercise.form.title.lowercase())
    if (exercise.form == ExerciseForm.HOLD) {
        exercise.edge?.let { append(" - ${formatNumber(it)} mm edge") }
        exercise.protocol?.let { append(" - ${formatNumber(it.first)}:${formatNumber(it.second)} protocol") }
    }
}

/*
 * ── The six entry forms below are shared, and that is why they are `internal` ────
 * One form per activity shape (§3), each one a set of fields plus the primary button, each
 * prefilled from the journal. WorkoutLogScreen raises the same six inside its quick-entry
 * sheet, and it has to be the SAME six: which fields an exercise asks for, what counts as a
 * repeat, and which values a set is built with are decisions that must not be able to differ
 * between two screens both called "record a set". A second copy would drift on the first day
 * one of them gained a field.
 *
 * They stay in this file rather than moving to a component of their own because this is
 * where they are read in context, and moving them is a diff that touches every one of them
 * while proving nothing.
 */

/**
 * The primary button. It is the biggest target on the screen and says what will happen:
 * "Repeat set" while the card still holds the previous values, "Add set" once something
 * was changed.
 */
@Composable
private fun SubmitButton(repeat: Boolean, enabled: Boolean, label: String? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(
            label ?: if (repeat) "Repeat set" else "Add set",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * The warm-up toggle, shared by the two forms that can carry one.
 *
 * ── Off on arrival, always, and never prefilled ─────────────────────────────────
 * A warm-up is a decision made about ONE set, not a property of the exercise, so the card
 * opens on "working set" however the previous set was marked. That is also what keeps the
 * ordinary move at two taps — raise the form, press the button — because the control that
 * matters most here is the one nobody has to touch.
 *
 * Getting this backwards is the expensive direction: a card that arrived pre-ticked from a
 * ramp-up would quietly file the working set that follows as a warm-up, and a warm-up counts
 * towards neither volume nor records. The set would be in the journal, on the day's feed, and
 * missing from every number the training is judged by.
 */
@Composable
private fun WarmupChip(selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text("Warm-up") },
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

@Composable
internal fun StrengthEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events, exercise.id) { lastStrengthSet(state.events, exercise.link) }
    val prefillWeight = if (last?.ownWeight == true) last.addedKg else last?.weightKg

    var weight by remember(exercise.id, last) { mutableStateOf(prefillWeight?.let(::formatNumber) ?: "") }
    var reps by remember(exercise.id, last) { mutableStateOf(last?.reps?.toString() ?: "") }
    var ownWeight by remember(exercise.id, last) { mutableStateOf(last?.ownWeight ?: false) }
    var warmup by remember(exercise.id, last) { mutableStateOf(false) }

    val repsValue = parseCount(reps)
    val weightValue = parseNumber(weight)
    /*
     * The warm-up flag is part of what makes a set "the same again": ramping up and then
     * repeating the ramp-up is a repeat, and a working set after one is not. Comparing it
     * against the previous set rather than against false is what keeps the button honest in
     * both directions — the card starts unticked, so a working set after a working set still
     * reads "Repeat set" and still costs one tap.
     */
    val untouched = last != null && weightValue == prefillWeight &&
        repsValue == last.reps && ownWeight == last.ownWeight && warmup == last.warmup

    StepperField(
        label = if (ownWeight) "Added weight, kg (empty means body weight only)" else "Weight, kg",
        value = weight,
        onValueChange = { weight = it },
        steps = listOf(2.5, 5.0),
    )
    StepperField(
        label = "Reps",
        value = reps,
        onValueChange = { reps = it },
        steps = listOf(1.0),
        decimal = false,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = ownWeight,
            onClick = { ownWeight = !ownWeight },
            label = { Text("Own body weight") },
            modifier = Modifier.heightIn(min = 40.dp),
        )
        WarmupChip(warmup) { warmup = !warmup }
    }
    SubmitButton(repeat = untouched, enabled = repsValue != null && repsValue > 0) {
        onAddSet(
            strengthSetOf(
                exercise = exercise, opDate = opDate, reps = repsValue!!,
                weightKg = weightValue, ownWeight = ownWeight, addedKg = weightValue,
                warmup = warmup,
            )
        )
    }
}

/**
 * Holds. Edge and protocol are NOT asked for: §12-A puts them on the exercise, so the
 * variables of a set are the added weight, the number of reps and — on an exercise trained
 * one limb at a time — which hand it was.
 *
 * ── The side is asked for, and it cannot be skipped ─────────────────────────────
 * A record on a one-sided exercise is per (exercise, side): the weaker hand has its own
 * history and the gap between the two is what the training exists to close. A set that names
 * no side on such an exercise is therefore NOT "both hands" — it is a set that failed to say,
 * and the readers report it as a defect rather than guessing
 * ([xyz.oleolegka.gachimuchi.domain.holdRecord] files it under "side not recorded"). So the
 * primary button stays disabled until one is chosen, with a line underneath saying why: a
 * dead button that explains nothing is the worst thing on a screen used mid-set.
 *
 * NOT PREFILLED FROM THE LAST SET, unlike every other field on this card. One-sided work
 * alternates, so last time's answer is the wrong one about as often as it is right — and the
 * failure is silent: two lefts in the journal, a right hand's history missing a set, and a
 * record on the wrong hand. The weight and the reps prefill because being wrong about them is
 * visible in the field before the button is pressed; the hand is not.
 */
@Composable
internal fun HoldEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val colors = LocalGachiColors.current
    val last = remember(state.events, exercise.id) { lastHoldSet(state.events, exercise.link) }
    var weight by remember(exercise.id, last) { mutableStateOf(last?.addedKg?.let(::formatNumber) ?: "") }
    var reps by remember(exercise.id, last) { mutableStateOf(last?.reps?.toString() ?: "") }
    var warmup by remember(exercise.id, last) { mutableStateOf(false) }
    var side by remember(exercise.id, last) { mutableStateOf<HoldSide?>(null) }

    val repsValue = parseCount(reps)
    val weightValue = parseNumber(weight)
    val untouched = last != null && weightValue == last.addedKg && repsValue == last.reps &&
        warmup == last.warmup && side == last.sideOf
    // only a one-sided exercise owes an answer; on any other one a null side is what "both
    // hands" has always meant and always will
    val sideMissing = exercise.oneSided && side == null

    StepperField(
        label = "Added weight, kg",
        value = weight,
        onValueChange = { weight = it },
        steps = listOf(0.5, 1.0),
    )
    StepperField(
        label = "Reps",
        value = reps,
        onValueChange = { reps = it },
        steps = listOf(1.0),
        decimal = false,
    )
    if (exercise.oneSided) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldSide.entries.forEach { option ->
                FilterChip(
                    selected = side == option,
                    // tapping the chosen one again clears it rather than doing nothing, so a
                    // mis-tap is undone the same way it was made
                    onClick = { side = if (side == option) null else option },
                    label = { Text(option.label) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
        if (sideMissing) {
            Text(
                "Say which side. This one is trained a limb at a time, and each side keeps " +
                    "its own record - a set that names neither belongs to neither.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkSecondary,
            )
        }
    }
    WarmupChip(warmup) { warmup = !warmup }
    SubmitButton(
        repeat = untouched,
        enabled = !sideMissing &&
            ((weightValue != null && weightValue > 0) || (repsValue != null && repsValue > 0)),
    ) {
        onAddSet(
            holdSetOf(
                exercise = exercise, opDate = opDate, addedKg = weightValue, reps = repsValue,
                warmup = warmup, side = side,
            )
        )
    }
}

/** How a side is named on a chip; the enum's own code is the stored value, not a label. */
private val HoldSide.label: String get() = when (this) {
    HoldSide.LEFT -> "Left"
    HoldSide.RIGHT -> "Right"
}

@Composable
internal fun CardioEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events, exercise.id) { lastCardio(state.events, exercise.link) }
    var km by remember(exercise.id, last) {
        mutableStateOf(last?.distanceM?.let { formatNumber(it / 1000) } ?: "")
    }
    var minutes by remember(exercise.id, last) {
        mutableStateOf(last?.durationSec?.let { formatNumber(it / 60.0) } ?: "")
    }
    var pace by remember(exercise.id, last) {
        mutableStateOf(last?.paceSecPerKm?.let(::formatPace) ?: "")
    }

    val distance = parseNumber(km)?.takeIf { it > 0 }?.let { it * 1000 }
    val duration = parseNumber(minutes)?.takeIf { it > 0 }?.let { (it * 60).toInt() }
    val paceValue = parsePace(pace)
    val untouched = last != null && distance == last.distanceM && duration == last.durationSec

    StepperField(label = "Distance, km", value = km, onValueChange = { km = it }, steps = listOf(0.5, 1.0))
    StepperField(label = "Time, min", value = minutes, onValueChange = { minutes = it }, steps = listOf(1.0, 5.0))
    StepperField(
        label = "Pace, min:s per km (optional)",
        value = pace,
        onValueChange = { pace = it },
        steps = emptyList(),
        placeholder = "4:30",
    )
    SubmitButton(
        repeat = untouched,
        enabled = distance != null || duration != null || paceValue != null,
        label = if (untouched) "Repeat entry" else "Add entry",
    ) {
        onAddSet(
            cardioOf(
                exercise = exercise, opDate = opDate, distanceM = distance,
                durationSec = duration, paceSecPerKm = paceValue,
            )
        )
    }
}

@Composable
internal fun DurationEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events, exercise.id) { lastDuration(state.events, exercise.link) }
    var minutes by remember(exercise.id, last) {
        mutableStateOf(last?.durationSec?.let { formatNumber(it / 60.0) } ?: "")
    }
    val seconds = parseNumber(minutes)?.takeIf { it > 0 }?.let { (it * 60).toInt() }
    val untouched = last != null && seconds == last.durationSec

    StepperField(label = "Minutes", value = minutes, onValueChange = { minutes = it }, steps = listOf(1.0, 5.0))
    SubmitButton(
        repeat = untouched,
        enabled = seconds != null && seconds > 0,
        label = if (untouched) "Repeat entry" else "Add entry",
    ) {
        onAddSet(durationOf(exercise = exercise, opDate = opDate, durationSec = seconds!!))
    }
}

@Composable
internal fun TickEntry(exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val colors = LocalGachiColors.current
    Text(
        "No metrics for this one — the statistic is how often it happens.",
        style = MaterialTheme.typography.labelSmall,
        color = colors.inkSecondary,
    )
    SubmitButton(repeat = false, enabled = true, label = "Check in") {
        onAddSet(tickOf(exercise = exercise, opDate = opDate))
    }
}

/** Body weight is a plain series and carries no exercise_id — the catalog row is only the way in. */
@Composable
internal fun BodyweightEntry(state: UiState, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events) { lastBodyweight(state.events) }
    var kg by remember(last) { mutableStateOf(last?.weightKg?.let(::formatNumber) ?: "") }
    val value = parseNumber(kg)?.takeIf { it > 0 }

    StepperField(label = "Body weight, kg", value = kg, onValueChange = { kg = it }, steps = listOf(0.1, 0.5))
    SubmitButton(repeat = false, enabled = value != null, label = "Record weight") {
        onAddSet(bodyweightOf(opDate = opDate, weightKg = value!!))
    }
}
