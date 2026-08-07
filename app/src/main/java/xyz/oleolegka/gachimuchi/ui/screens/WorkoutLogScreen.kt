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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.MAX_STEP_SEC
import xyz.oleolegka.gachimuchi.domain.MIN_STEP_SEC
import xyz.oleolegka.gachimuchi.domain.RestFloor
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.Workout
import xyz.oleolegka.gachimuchi.domain.WorkoutExercise
import xyz.oleolegka.gachimuchi.domain.activityName
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.ceilSeconds
import xyz.oleolegka.gachimuchi.domain.formatClock
import xyz.oleolegka.gachimuchi.domain.lastTimeOf
import xyz.oleolegka.gachimuchi.domain.parseCount
import xyz.oleolegka.gachimuchi.domain.progressAt
import xyz.oleolegka.gachimuchi.domain.restHintSec
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.DashedNote
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.StepperField
import xyz.oleolegka.gachimuchi.ui.components.rememberTickingNow
import xyz.oleolegka.gachimuchi.ui.fmtShortDay
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * Inside a workout: one card per exercise, each with its own rest running underneath it.
 *
 * ── What this replaces, and why it is a different screen ────────────────────────
 * [LogScreen] shows the whole DAY as a tape and points a single entry card at a single
 * "active exercise". Both halves of that fail the case the app exists for (§13.1): a superset
 * is several exercises alive at once, and choosing one of them used to be the act that
 * abandoned the others. There is no active exercise here. Every exercise of the workout has a
 * card, the card is the thing that is tapped, and the rests run side by side because they are
 * floors and floors are parallel by construction (domain/Floors.kt).
 *
 * [LogScreen] is not deleted: a SINGLE ENTRY outside any workout — the stretching in front of
 * the television — is still logged through it, and there is no workout there to draw cards of.
 *
 * ── An exercise with no sets is the point, not an edge case ─────────────────────
 * Exercises are added BEFORE the first set of them, on the way in: the plan for the next hour
 * is sketched standing in the doorway. So an empty card is the normal early state of this
 * screen and says so, rather than being hidden until it has something to show.
 *
 * ── Everything tapped between sets is at the bottom ─────────────────────────────
 * The original ergonomic requirement, carried over from [LogScreen] whole: between sets the
 * phone is in one hand and every control has to be inside the arc of a thumb. "Add exercise"
 * is the bottom bar; the quick entry form is a bottom SHEET, which puts its fields and its big
 * primary button in the bottom third of the screen whatever the card list is doing above it.
 * The cards themselves scroll, but a card is a large target and tapping one is not the move
 * being repeated twenty times an hour — pressing "Repeat set" is.
 *
 * ── The day comes from the workout and from nowhere else ────────────────────────
 * Every form built here is stamped with [Workout.opDate], never with today. `buildWorkout`
 * files a set under the workout's date whatever the set's own payload says, so a screen that
 * used today would produce rows this screen and the calendar disagree about — and the journal
 * is append-only, so the disagreement could never be corrected. That is the whole of
 * `loggingDay`, applied at the one place that builds forms rather than passed in as a
 * parameter that could be handed the wrong value.
 *
 * ── Room left, deliberately empty ───────────────────────────────────────────────
 * Four things belong on this screen and are not on it: starting a protocol-led set from a
 * card and letting the conductor take the screen (§13.3), the weight question on the way into
 * such a set (§13.5), the readiness summary a finished set produces (§13.4), and a way to
 * declare the workout over (§13.8, which has not decided what closes one). Each is its own
 * change with its own verification. None of them is stubbed: a card carries no button that
 * does nothing, because a control that does nothing is worse than an absent one — it is
 * pressed in the gym, and nothing happens, and the app is the thing that broke.
 */
@Immutable
data class WorkoutLogActions(
    /**
     * Put an exercise in the workout at this rest, or — called again for one already in it —
     * change the rest.
     *
     * ONE CALLBACK FOR BOTH, because in an append-only journal they are one event: adding an
     * exercise that is already there does not reorder it and the last rest wins (see
     * `buildWorkout`). Two callbacks would be two names for the same write, and the screen
     * would have to decide which of them a given tap was — a decision it has no reason to make.
     */
    val addExercise: (exerciseId: Long, restSec: Int) -> Unit,

    /**
     * Create a catalog exercise, and hand its id back.
     *
     * The continuation is what makes creating an exercise mid-workout work at all: the rest is
     * asked for straight afterwards and the question needs the id, which only exists once the
     * row has been written.
     */
    val createExercise: (
        name: String,
        form: ExerciseForm,
        edgeMm: Double?,
        workSec: Double?,
        restSec: Double?,
        then: (Long) -> Unit,
    ) -> Unit,

    /** Append a set. The form is already stamped with the workout's day. */
    val addSet: (ActivityForm) -> Unit,

    /** Take back a set: an append-only reversal, not a delete. */
    val undoSet: (eventId: Long) -> Unit,

    /** Leave the workout. It is not finished by leaving — nothing here ends one. */
    val close: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutLogScreen(
    state: UiState,
    workoutId: Long,
    settings: TimerSettings,
    /** Every rest the app is counting, of which at most one belongs to each card. */
    floors: List<RestFloor>,
    actions: WorkoutLogActions,
    modifier: Modifier = Modifier,
    /**
     * The monotonic reading the bars are drawn against.
     *
     * Defaulted to the ticking clock so the caller does not have to recompose four times a
     * second on the screen's behalf, and a parameter at all so a test can state an instant
     * instead of racing one.
     */
    nowMs: Long = rememberTickingNow(active = floors.isNotEmpty()),
) {
    val colors = LocalGachiColors.current
    val workout = remember(state.events, workoutId) { buildWorkout(state.events, workoutId) }

    if (workout == null) {
        // a wipe, a reseed, a workout that was never in this journal: say so rather than
        // draw an empty screen that looks like a workout with nothing in it
        Column(modifier.padding(24.dp)) {
            Text("This workout is no longer in the journal.", color = colors.inkSecondary)
        }
        return
    }

    val opDate = workout.opDate
    val date = remember(opDate) { runCatching { LocalDate.parse(opDate) }.getOrNull() }

    var picking by rememberSaveable { mutableStateOf(false) }
    /** The exercise whose rest is being asked about, or null for no question on screen. */
    var askingRestFor by rememberSaveable { mutableStateOf<Long?>(null) }
    /** The exercise whose quick entry form is raised, or null. */
    var entryFor by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // the name snapshot taken when "start" was pressed, never the plan's
                        // name as it reads today — a plan is editable and a fact is not
                        Text(
                            workout.name ?: "Workout",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            listOfNotNull(date?.let { fmtWeekdayDay(it) }, summaryOf(workout))
                                .joinToString(" - "),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.close) {
                        Icon(Icons.Filled.Close, contentDescription = "Leave the workout")
                    }
                },
                actions = {
                    /*
                     * Small, plain, and as far from the bottom of the screen as it can be put.
                     * A mis-tap here cancels a set that was actually done, so it is deliberately
                     * nowhere near the thumb the rest of this screen is laid out for.
                     */
                    val last = lastSetOf(workout)
                    TextButton(onClick = { last?.let(actions.undoSet) }, enabled = last != null) {
                        Text("Undo last")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                Button(
                    onClick = { picking = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 15.dp, vertical = 10.dp)
                        .heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(
                        "Add exercise",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (workout.exercises.isEmpty()) {
                item {
                    DashedNote(
                        "Nothing in this workout yet. Add the exercises you are about to do - " +
                            "a card with no sets on it is fine, it is the plan for the next hour."
                    )
                }
            }

            items(workout.exercises.size, key = { workout.exercises[it].exercise.key }) { index ->
                val exercise = workout.exercises[index]
                val id = exercise.exerciseId
                ExerciseCard(
                    name = exerciseName(state, exercise),
                    restSec = exercise.restSec,
                    sets = exercise.sets.map { it.form.summaryLine() },
                    floor = id?.let { e -> floors.firstOrNull { it.exerciseId == e } },
                    nowMs = nowMs,
                    // an exercise this phone has no catalog row for cannot have a form built
                    // for it, so it is shown and not offered — see WorkoutExercise.exerciseId
                    onTap = id?.takeIf { state.refById(it) != null }?.let { e -> { entryFor = e } },
                    onRest = id?.let { e -> { askingRestFor = e } },
                )
            }

            if (workout.entriesWithoutExercise.isNotEmpty()) {
                item {
                    /*
                     * A weigh-in carries no exercise_id by design, so it belongs to the workout
                     * without belonging to any of its exercises. Shown rather than dropped: an
                     * entry that is in the journal and on no screen is how a record silently
                     * stops existing.
                     */
                    ExerciseCard(
                        name = "Other entries",
                        restSec = null,
                        sets = workout.entriesWithoutExercise.map { it.form.summaryLine() },
                        floor = null,
                        nowMs = nowMs,
                        onTap = null,
                        onRest = null,
                    )
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
        }
    }

    if (picking) {
        ExercisePickerSheet(
            state = state,
            today = date ?: LocalDate.now(),
            startInCreate = state.exercises.isEmpty(),
            // picked or created, the next question is the same one, so both land on it
            onPick = { id -> askingRestFor = id },
            onCreate = { name, form, edge, work, rest ->
                actions.createExercise(name, form, edge, work, rest) { id -> askingRestFor = id }
            },
            onDismiss = { picking = false },
        )
    }

    /*
     * Both of the raised things below resolve their exercise through the catalog and simply
     * do not appear when it is not there — rather than clearing the id, which would be a
     * write to state from inside composition and a recomposition loop waiting to happen. An
     * exercise deleted while the sheet was open leaves a dead id behind; the next tap
     * replaces it, and nothing is drawn about it meanwhile.
     */
    askingRestFor?.let { id -> state.refById(id)?.let { ref ->
        val already = workout.exercises.firstOrNull { it.exerciseId == id }
        RestDialog(
            exerciseName = ref.name,
            // what was chosen for THIS workout wins; failing that, restHintSec knows the
            // order of the rest (the catalog column, then what was actually rested)
            initialSec = already?.restSec?.takeIf { it >= MIN_STEP_SEC }
                ?: restHintSec(settings, state.events, ref),
            confirmLabel = if (already == null) "Add to workout" else "Save",
            onConfirm = { sec ->
                actions.addExercise(id, sec)
                askingRestFor = null
            },
            onDismiss = { askingRestFor = null },
        )
    } }

    entryFor?.let { id -> state.refById(id)?.let { ref ->
        QuickEntrySheet(
            state = state,
            exercise = ref,
            opDate = opDate,
            workoutId = workoutId,
            onAddSet = { form ->
                actions.addSet(form)
                entryFor = null
            },
            onDismiss = { entryFor = null },
        )
    } }
}

/*
 * ── Two small private helpers that WorkoutScreen.kt also has ────────────────────
 * [summaryOf] and [exerciseName] are file-private copies of the ones on the read-only
 * workout screen, deliberately rather than by oversight. They are the same sentences today
 * because the two screens describe the same thing to the same reader, and sharing them would
 * mean widening a symbol on a file this change has no other reason to touch. If they are ever
 * asked to differ, they can — which is the honest state: neither screen depends on the other
 * saying it the same way.
 */

/** "3 exercises, 11 sets", or what a workout with nothing in it has instead. */
private fun summaryOf(workout: Workout): String {
    if (workout.isEmpty) return "nothing recorded yet"
    val exercises = workout.exercises.size
    val sets = workout.setCount
    return "$exercises ${if (exercises == 1) "exercise" else "exercises"}, " +
        "$sets ${if (sets == 1) "set" else "sets"}"
}

/**
 * The last set recorded in this workout, whatever exercise it belongs to, or null.
 *
 * Journal ids increase with writing order, so the largest is the newest — the same fact
 * `Session.lastEventId` leans on. Scoped to the WORKOUT rather than to the day, which is
 * strictly better than what [LogScreen] can offer: on a day with two workouts, undo here
 * cannot reach into the other one.
 */
private fun lastSetOf(workout: Workout): Long? =
    (workout.exercises.flatMap { it.sets } + workout.entriesWithoutExercise)
        .maxOfOrNull { it.id }

/**
 * The exercise's name.
 *
 * The catalog first, because that is the name the user maintains. The set's own payload is
 * the fallback and it matters: the journal outlives the catalog, and a card headed
 * "Exercise 14" is one you cannot use.
 */
private fun exerciseName(state: UiState, exercise: WorkoutExercise): String =
    state.exerciseById(exercise.exerciseId)?.name
        ?: exercise.sets.firstOrNull()?.form?.activityName()
        ?: "Exercise ${exercise.exerciseId}"

/**
 * One exercise of the workout: what it is, what has been done of it, and where its rest is.
 *
 * The whole card is the tap target for logging, because that is the move being repeated all
 * session and it should not require aiming. The rest is the one other control, and it is a
 * text button showing the current value — the label IS the control, so no icon has to be
 * decoded and no second row of chrome appears on a card that is mostly numbers.
 */
@Composable
private fun ExerciseCard(
    name: String,
    restSec: Int?,
    sets: List<String>,
    floor: RestFloor?,
    nowMs: Long,
    onTap: (() -> Unit)?,
    onRest: (() -> Unit)?,
) {
    val colors = LocalGachiColors.current
    GachiCard(
        Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 13.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onRest != null) {
                TextButton(onClick = onRest, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(
                        restSec?.takeIf { it >= MIN_STEP_SEC }
                            ?.let { "rest ${formatClock(it)}" }
                            ?: "set a rest",
                        fontSize = 12.sp,
                    )
                }
            }
        }
        HorizontalDivider(color = colors.grid)

        Text(
            // one line per card rather than one row per set: this is read at a glance
            // between sets, and the question it answers is "where am I up to"
            if (sets.isEmpty()) "no sets yet" else sets.joinToString(", "),
            fontSize = 12.sp,
            color = if (sets.isEmpty()) colors.inkMuted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
        )

        floor?.let { RestBar(it, nowMs) }
    }
}

/**
 * The rest under one card: a bar that fills, and a line that says what it means.
 *
 * ── "Ready" alone is the least useful thing a rest timer can say ────────────────
 * People miss the moment constantly, and the difference between "ready" and "ready, and you
 * have been standing here for two and a half minutes" is the difference between a timer that
 * reports and one worth reading. The overrun is the number [FloorProgress.overdueMs] exists
 * for and this is what draws it.
 *
 * The bar STOPS at the end rather than growing past its track — an overrun is a duration, not
 * a fraction above one — so once ready the words carry the news and the bar only says "done".
 *
 * Remaining time rounds UP and time already spent rounds DOWN, which is the rule everywhere
 * in this app: a countdown must not show 0:00 while there is still a second to wait, and a
 * rest ready for 1:20.9 has not been ready for 1:21.
 */
@Composable
private fun RestBar(floor: RestFloor, nowMs: Long) {
    val colors = LocalGachiColors.current
    val progress = floor.progressAt(nowMs)
    val overdueSec = (progress.overdueMs / 1000).toInt()

    Column(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 9.dp)) {
        Text(
            when {
                !progress.ready -> "rest ${formatClock(ceilSeconds(progress.remainingMs))} left"
                overdueSec <= 0 -> "ready"
                else -> "ready, +${formatClock(overdueSec)}"
            },
            fontSize = 11.sp,
            // stated in words as well as colour, like every other verdict in this app
            color = if (progress.ready) colors.goodText else colors.inkSecondary,
            fontWeight = if (progress.ready) FontWeight.Medium else FontWeight.Normal,
        )
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            color = if (progress.ready) colors.good else colors.accent,
        )
    }
}

/**
 * The rest between sets, asked once when the exercise joins the workout and changed from the
 * card afterwards.
 *
 * ── Prefilled so that agreeing is one tap ───────────────────────────────────────
 * The offer is what was chosen last time (§13.2), and the point of asking at all is that it
 * can be overruled — not that it has to be answered. So the confirm button is the primary
 * one, it is enabled on arrival, and the value under it is already right for the ordinary case.
 *
 * Seconds rather than minutes and seconds because a second field is a second thing to get
 * wrong for a number that is almost always a round count; the "3:00" underneath is what makes
 * the raw number readable.
 */
@Composable
private fun RestDialog(
    exerciseName: String,
    initialSec: Int,
    confirmLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current
    var draft by remember(exerciseName, initialSec) { mutableStateOf(initialSec.toString()) }
    val seconds = parseCount(draft)?.takeIf { it in MIN_STEP_SEC..MAX_STEP_SEC }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rest between sets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    exerciseName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StepperField(
                    label = "Rest, seconds",
                    value = draft,
                    onValueChange = { draft = it },
                    steps = listOf(15.0, 30.0),
                    decimal = false,
                )
                Text(
                    seconds?.let { "That is ${formatClock(it)}." }
                        ?: "A rest is between 1 second and an hour.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { seconds?.let(onConfirm) }, enabled = seconds != null) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Recording a set: the fields that suit the exercise, what it was last time, and the big
 * button, all in the bottom third of the screen.
 *
 * A SHEET rather than a panel pinned under the card list, because the fields belong to the
 * exercise that was tapped and a panel would have to say which one it currently means — which
 * is "the active exercise" wearing a different hat, and the active exercise is the thing this
 * screen exists to abolish.
 *
 * The six forms come from [LogScreen] unchanged. They prefill themselves from the last set of
 * this exercise ANYWHERE, this workout's own included, which is what makes another set of the
 * same one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickEntrySheet(
    state: UiState,
    exercise: ExerciseRef,
    opDate: String,
    workoutId: Long,
    onAddSet: (ActivityForm) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /*
     * What the PREVIOUS session did, which is a different question from what the prefill
     * answers — see domain/LastTime.kt. This is the line the choice at the bar is made on, so
     * it carries the whole day's sets and not just its best one: "60 kg x 9, 60 kg x 8,
     * 60 kg x 6" says something about what to try next that "60 kg x 9" does not.
     */
    val lastTime = remember(state.events, exercise.id, workoutId, opDate) {
        lastTimeOf(state.events, exercise.link, onOrBefore = opDate, excludingWorkoutId = workoutId)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(exercise.name, style = MaterialTheme.typography.titleMedium)
            Text(
                lastTime?.let { last ->
                    val day = runCatching { LocalDate.parse(last.opDate) }.getOrNull()
                    val dayText = day?.let { fmtShortDay(it) } ?: last.opDate
                    "Last time ($dayText): " + last.sets.joinToString(", ") { it.form.summaryLine() }
                } ?: "No earlier set of this one.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkSecondary,
            )

            when (exercise.form) {
                ExerciseForm.STRENGTH -> StrengthEntry(state, exercise, opDate, onAddSet)
                ExerciseForm.HOLD -> HoldEntry(state, exercise, opDate, onAddSet)
                ExerciseForm.CARDIO -> CardioEntry(state, exercise, opDate, onAddSet)
                ExerciseForm.DURATION -> DurationEntry(state, exercise, opDate, onAddSet)
                ExerciseForm.TICK -> TickEntry(exercise, opDate, onAddSet)
                ExerciseForm.BODYWEIGHT -> BodyweightEntry(state, opDate, onAddSet)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
