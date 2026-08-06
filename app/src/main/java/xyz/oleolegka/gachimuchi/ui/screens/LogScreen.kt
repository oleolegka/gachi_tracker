package xyz.oleolegka.gachimuchi.ui.screens

import androidx.activity.compose.BackHandler
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
 * ── What a session is ───────────────────────────────────────────────────────────
 * Everything logged today (see domain/Session.kt). There is no "start" or "finish"
 * event, so "continue today's workout" is not a feature but the default, and closing the
 * screen mid-workout costs nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    state: UiState,
    today: LocalDate,
    activeExerciseId: Long?,
    onSelectExercise: (Long?, String?) -> Unit,
    onCreateExercise: (String, ExerciseForm, Double?, Double?, Double?) -> Unit,
    onAddSet: (ActivityForm) -> Unit,
    onUndoSet: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalGachiColors.current
    val iso = today.toString()
    val session = remember(state.events, iso) { buildSession(state.events, iso) }
    val active = state.refById(activeExerciseId)
    var picking by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Workout", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${fmtDay(today)} - ${session.setCount} entries, " +
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
            EntryPanel(
                state = state,
                exercise = active,
                opDate = iso,
                onPick = { picking = true },
                onAddSet = onAddSet,
            )
        },
    ) { padding ->
        SessionFeed(
            session = session,
            activeExerciseId = activeExerciseId,
            onSelectExercise = { onSelectExercise(it, null) },
            onPick = { picking = true },
            modifier = Modifier.padding(padding),
        )
    }

    if (picking) {
        ExercisePickerSheet(
            state = state,
            today = today,
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
                    Text("Nothing logged today yet.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pick an exercise below and record the first set. Everything you add " +
                            "shows up here, newest at the bottom.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = onPick, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Choose an exercise")
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
                        exercise?.name ?: "No exercise chosen",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        exercise?.let { contextLine(it) } ?: "tap to choose",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }
                TextButton(onClick = onPick) { Text(if (exercise == null) "Choose" else "Change") }
            }

            if (exercise == null) {
                Button(
                    onClick = onPick,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text("Choose an exercise") }
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
        exercise.edgeMm?.let { append(" - ${formatNumber(it)} mm edge") }
        exercise.protocol?.let { append(" - ${formatNumber(it.first)}:${formatNumber(it.second)} protocol") }
    }
}

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

@Composable
private fun StrengthEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events, exercise.id) { lastStrengthSet(state.events, exercise.id) }
    val prefillWeight = if (last?.ownWeight == true) last.addedKg else last?.weightKg

    var weight by remember(exercise.id, last) { mutableStateOf(prefillWeight?.let(::formatNumber) ?: "") }
    var reps by remember(exercise.id, last) { mutableStateOf(last?.reps?.toString() ?: "") }
    var ownWeight by remember(exercise.id, last) { mutableStateOf(last?.ownWeight ?: false) }

    val repsValue = parseCount(reps)
    val weightValue = parseNumber(weight)
    val untouched = last != null && weightValue == prefillWeight &&
        repsValue == last.reps && ownWeight == last.ownWeight

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
    FilterChip(
        selected = ownWeight,
        onClick = { ownWeight = !ownWeight },
        label = { Text("Own body weight") },
        modifier = Modifier.heightIn(min = 40.dp),
    )
    SubmitButton(repeat = untouched, enabled = repsValue != null && repsValue > 0) {
        onAddSet(
            strengthSetOf(
                exercise = exercise, opDate = opDate, reps = repsValue!!,
                weightKg = weightValue, ownWeight = ownWeight, addedKg = weightValue,
            )
        )
    }
}

/**
 * Holds. Edge and protocol are NOT asked for: §12-A puts them on the exercise, so the
 * only variables of a set are the added weight and the number of reps.
 */
@Composable
private fun HoldEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events, exercise.id) { lastHoldSet(state.events, exercise.id) }
    var weight by remember(exercise.id, last) { mutableStateOf(last?.addedKg?.let(::formatNumber) ?: "") }
    var reps by remember(exercise.id, last) { mutableStateOf(last?.reps?.toString() ?: "") }

    val repsValue = parseCount(reps)
    val weightValue = parseNumber(weight)
    val untouched = last != null && weightValue == last.addedKg && repsValue == last.reps

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
    SubmitButton(
        repeat = untouched,
        enabled = (weightValue != null && weightValue > 0) || (repsValue != null && repsValue > 0),
    ) {
        onAddSet(holdSetOf(exercise = exercise, opDate = opDate, addedKg = weightValue, reps = repsValue))
    }
}

@Composable
private fun CardioEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events, exercise.id) { lastCardio(state.events, exercise.id) }
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
private fun DurationEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events, exercise.id) { lastDuration(state.events, exercise.id) }
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
private fun TickEntry(exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
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
private fun BodyweightEntry(state: UiState, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events) { lastBodyweight(state.events) }
    var kg by remember(last) { mutableStateOf(last?.weightKg?.let(::formatNumber) ?: "") }
    val value = parseNumber(kg)?.takeIf { it > 0 }

    StepperField(label = "Body weight, kg", value = kg, onValueChange = { kg = it }, steps = listOf(0.1, 0.5))
    SubmitButton(repeat = false, enabled = value != null, label = "Record weight") {
        onAddSet(bodyweightOf(opDate = opDate, weightKg = value!!))
    }
}
