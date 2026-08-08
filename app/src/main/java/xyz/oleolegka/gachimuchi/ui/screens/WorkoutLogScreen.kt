package xyz.oleolegka.gachimuchi.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
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
import xyz.oleolegka.gachimuchi.domain.formatNumber
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.lastTimeOf
import xyz.oleolegka.gachimuchi.domain.ledByProtocol
import xyz.oleolegka.gachimuchi.domain.parseCount
import xyz.oleolegka.gachimuchi.domain.parseNumber
import xyz.oleolegka.gachimuchi.domain.progressAt
import xyz.oleolegka.gachimuchi.domain.restHintSec
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.CardRadius
import xyz.oleolegka.gachimuchi.ui.components.DashedNote
import xyz.oleolegka.gachimuchi.ui.components.ConfirmRemoveDialog
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.ItemAction
import xyz.oleolegka.gachimuchi.ui.components.ItemActions
import xyz.oleolegka.gachimuchi.ui.components.ItemDrag
import xyz.oleolegka.gachimuchi.ui.components.moved
import xyz.oleolegka.gachimuchi.ui.components.rememberReorderState
import xyz.oleolegka.gachimuchi.ui.components.REMOVAL_IS_REVERSIBLE
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
 * ── What a tap on a card does depends on the exercise ───────────────────────────
 * For most exercises it raises the quick entry form, which is what a card has always done.
 * For an exercise RUN BY ITS PROTOCOL (`ledByProtocol`, §13.2) it starts the set instead and
 * hands the screen to the conductor — because for those the app is not recording what
 * happened, it is calling out what to do next, and a form asking for numbers that do not
 * exist yet is in the way. A card whose set is already running leads back to it.
 *
 * ── The cards can be rearranged, because the gym decides the order ──────────────
 * The list used to be the order the exercises were ADDED in, which is the order they were
 * thought of standing in the doorway. A machine being occupied is enough to make that wrong for
 * the rest of the hour, so a card can be picked up on a long press and carried to where it now
 * belongs. What that costs elsewhere: the order is a fact of its own now and lives in the
 * journal — see `TYPE_WORKOUT_ORDER_SET`.
 *
 * The long press was already spoken for: it raises the menu that removes an exercise. It still
 * does, and which of the two a press meant is decided by whether the finger moved — the reasoning
 * and the threshold are on [ItemDrag]. The menu additionally carries "Move up" and "Move down",
 * which is the same move for a screen reader, where a drag reports nothing.
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
        workSec: Double?,
        restSec: Double?,
        then: (Long) -> Unit,
    ) -> Unit,

    /** Append a set. The form is already stamped with the workout's day. */
    val addSet: (ActivityForm) -> Unit,

    /** Take back a set: an append-only reversal, not a delete. */
    val undoSet: (eventId: Long) -> Unit,

    /**
     * Take an exercise out of this workout, ROWS AND ALL: the "added" events that put it here
     * and every set recorded under it, named by the screen because it has already folded them.
     *
     * The sets go with it, and they have to. Removing only the "added" row would leave the
     * card exactly where it was — `buildWorkout` puts an exercise in a workout on the first
     * set of it as readily as on an explicit add, because a set logged for an exercise nobody
     * added is still training that happened. So "remove this exercise" is a removal of the
     * whole block or it is nothing, and the confirmation says which sets are going.
     */
    val removeExercise: (eventIds: List<Long>) -> Unit,

    /**
     * State the order the exercises of this workout are to be done in, WHOLE.
     *
     * The whole list on every change rather than "this one moved there", for the reasons on
     * `TYPE_WORKOUT_ORDER_SET`. What that buys the screen is that it never has to describe a
     * move: it hands over the arrangement it is currently showing, and the journal's answer
     * either matches it or replaces it.
     */
    val reorderExercises: (order: List<ExerciseLink>) -> Unit,

    /**
     * Declare the workout over.
     *
     * Not a lock and not a way out: the screen stays where it is, everything on it can still
     * be tapped, and a set added afterwards goes into this workout and moves its end time.
     * What it changes is that sets logged from anywhere ELSE stop landing here.
     */
    val finish: () -> Unit,

    /**
     * Begin a protocol-led set and give the screen to the conductor.
     *
     * [addedKg] is what was hung off the belt, answered BEFORE the set rather than after it
     * (§13.5): the plate goes on before you get under the cable, so that is when the app can
     * ask without being in the way. Null means nothing was asked — see [WorkoutLogScreen] for
     * the rule that decides.
     */
    val startProtocolSet: (exercise: ExerciseRef, addedKg: Double?) -> Unit,

    /** Go back to the set already running. It has never stopped; this only shows it again. */
    val openConductor: () -> Unit,

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
     * The exercise whose protocol-led set is running right now, or null for none.
     *
     * Read off the run itself (`RunSnapshot.exerciseId`) rather than remembered here, so a
     * set that ends while this screen is not looking cannot leave a card claiming to be
     * running. The card says so in words and leads back to the conductor instead of raising
     * the entry form — logging the set is what the conductor offers when it finishes.
     */
    liveExerciseId: Long? = null,
    /**
     * What matured while a protocol-led set had the rests muted, as one sentence, or null.
     *
     * Computed and spoken already (domain/Floors.kt, `floorSummaryText`); this is the only
     * place it is WRITTEN DOWN. It matters because a running conductor silences every floor
     * (a beep in the middle of a seven-second hang is exactly what must not happen), so a set
     * that took two minutes can end with three rests having come due unannounced. One line
     * answers "what is ready now" at a glance, which a queue of missed beeps never could.
     */
    readySummary: String? = null,
    /** Acknowledge the summary. It says nothing that is not already visible on the cards. */
    onDismissSummary: () -> Unit = {},
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
    /** The exercise whose protocol-led set is waiting on the weight question, or null. */
    var weighingFor by rememberSaveable { mutableStateOf<Long?>(null) }
    /**
     * The exercise whose removal is being confirmed, by [ExerciseLink.key] rather than by
     * catalog id: a block can be there for an exercise this phone has no catalog row for, and
     * that block is exactly the one somebody would want out of the workout.
     */
    var removingKey by rememberSaveable { mutableStateOf<String?>(null) }

    /*
     * ── The order under the finger, which the journal has not been told about yet ───
     * A drag rearranges the list twenty times before it is dropped, and writing a row for each
     * would be twenty facts to record one decision. So the arrangement being SHOWN is held here
     * while the card is in the hand, and one event is written when it lands.
     *
     * Null is the ordinary state and means "whatever the journal folds to". It goes back to null
     * once the journal agrees with what is on screen, which is what makes the write authoritative
     * rather than this variable: a reorder that failed to reach the database would otherwise be
     * visible here forever, and instead the next fold puts the list back and the user can see
     * that nothing happened.
     *
     * NOT rememberSaveable: it is the state of a finger that is on the screen right now. Nothing
     * is lost by forgetting it across a rotation, and remembering it would restore a preview of
     * an arrangement nobody is holding.
     */
    var preview by remember { mutableStateOf<List<String>?>(null) }
    val live = workout.exercises
    val shown = remember(live, preview) {
        preview?.let { keys -> keys.mapNotNull { key -> live.firstOrNull { it.exercise.key == key } } }
            ?: live
    }
    LaunchedEffect(live, preview) {
        val liveKeys = live.map { it.exercise.key }
        val held = preview ?: return@LaunchedEffect
        // the journal has caught up, or the workout has changed under the preview (an exercise
        // added or removed) and a stale arrangement would hide it
        if (held == liveKeys || held.toSet() != liveKeys.toSet()) preview = null
    }

    val listState = rememberLazyListState()
    /*
     * Every one of these reads `preview` at the moment it is CALLED rather than closing over the
     * list that was on screen when this composition ran. A drag delivers many pointer events
     * between two frames, and each one has to see what the one before it did to the order —
     * otherwise the second swap of a drag is computed against the arrangement from before the
     * first, and the card lands somewhere nobody dragged it.
     */
    val liveKeys = { live.map { it.exercise.key } }
    val shownKeys = { preview ?: liveKeys() }
    val reorder = rememberReorderState(
        listState = listState,
        keys = shownKeys,
        onOrder = { order -> preview = order },
    )

    /**
     * Moves one card and states the result straight away — the menu's version of a drag.
     *
     * No preview: there is no finger to keep up with, so the journal is the only thing that has
     * to be told and the fold that comes back is what redraws the list.
     */
    fun moveExercise(from: Int, to: Int) {
        stateOrder(shownKeys().moved(from, to), live, actions.reorderExercises)
    }

    /** Hands the arrangement now on screen to the journal, or gives up on it if it is the same. */
    fun commitOrder() {
        // the keys as they are RIGHT NOW, not as the last composition drew them: the drop lands
        // in the same run of pointer events as the swaps did
        if (!stateOrder(shownKeys(), live, actions.reorderExercises)) preview = null
    }

    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            Column {
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
                            listOfNotNull(
                                date?.let { fmtWeekdayDay(it) },
                                summaryOf(workout),
                                // stated only once it means something: an unfinished workout
                                // has an end time too, and it is simply "so far"
                                "finished ${clockOf(workout.endTs)}".takeIf { workout.finished },
                            ).joinToString(" - "),
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
                    /*
                     * Up here for the same reason as "Undo last", and not because it is
                     * dangerous — it is not, nothing is lost and the screen carries on
                     * working. It is simply pressed once at the end of a session, and every
                     * control the thumb can reach without aiming is reserved for the moves
                     * made twenty times an hour.
                     */
                    TextButton(onClick = actions.finish, enabled = !workout.finished) {
                        Text(if (workout.finished) "Finished" else "Finish")
                    }
                },
            )
            /*
             * PINNED under the title bar rather than put in the scrolling list. It appears at
             * the moment a set ends and the phone is being picked up again, and a line that
             * the same thumb can scroll away before reading it is a line that gets missed.
             */
            readySummary?.let { line -> ReadyBanner(line, onDismissSummary) }
            }
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
            state = listState,
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

            items(shown.size, key = { shown[it].exercise.key }) { index ->
                val exercise = shown[index]
                val key = exercise.exercise.key
                val id = exercise.exerciseId
                // an exercise this phone has no catalog row for cannot have a form built
                // for it, so it is shown and not offered — see WorkoutExercise.exerciseId
                val ref = state.refById(id)
                val running = id != null && id == liveExerciseId
                ExerciseCard(
                    name = exerciseName(state, exercise),
                    restSec = exercise.restSec,
                    sets = exercise.sets.map { it.form.summaryLine() },
                    running = running,
                    floor = id?.let { e -> floors.firstOrNull { it.exerciseId == e } },
                    nowMs = nowMs,
                    onTap = when {
                        running -> actions.openConductor
                        ref == null -> null
                        /*
                         * The protocol comes FIRST, before the form: for these the app is
                         * conducting a set rather than taking a report of one, and there are
                         * no numbers to prefill a form with until the set has been done.
                         * `ledByProtocol` is the exercise's own answer (a maximum-weight hang
                         * carries a protocol and is still led by weight — §13.2), and the
                         * protocol has to actually be there for a run to be built out of it.
                         */
                        ledByProtocol(ref) && ref.protocol != null -> {
                            {
                                /*
                                 * ASK ONLY IF THERE WAS A PLATE LAST TIME (§13.5). Asking
                                 * unconditionally would put a question in front of every
                                 * bodyweight protocol — a set that used to start with one tap
                                 * and no screens at all.
                                 */
                                if (lastAddedKg(state, ref) == null) {
                                    actions.startProtocolSet(ref, null)
                                } else {
                                    weighingFor = ref.id
                                }
                            }
                        }

                        else -> {
                            { entryFor = ref.id }
                        }
                    },
                    onRest = id?.let { e -> { askingRestFor = e } },
                    onRemove = { removingKey = key },
                    /*
                     * Nothing to drag when there is one card, and a lift with nowhere to go
                     * would be a gesture that appears to work and cannot. The menu keeps
                     * working either way — see [ItemDrag].
                     */
                    drag = if (shown.size < 2) {
                        null
                    } else {
                        ItemDrag(
                            onStart = { reorder.start(key) },
                            onDrag = { dy -> reorder.drag(dy) },
                            onDrop = { reorder.stop(); commitOrder() },
                            onCancel = { reorder.stop(); preview = null },
                        )
                    },
                    lifted = reorder.draggedKey == key,
                    liftOffset = { reorder.offsetOf(key) },
                    /*
                     * The same move, written down. A drag reports nothing to a screen reader and
                     * cannot be performed without seeing where the card has got to, so the menu
                     * carries the version that works without either — and it is also the version
                     * somebody uses when one card has to go up one place and aiming is a bother.
                     */
                    onMoveUp = if (index == 0) null else ({ moveExercise(index, index - 1) }),
                    onMoveDown = if (index == shown.lastIndex) null else ({ moveExercise(index, index + 1) }),
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
                        running = false,
                        floor = null,
                        nowMs = nowMs,
                        onTap = null,
                        onRest = null,
                        // this card is not an exercise of the workout but a bag of entries
                        // that name none, so there is no block to take out of it. Removing
                        // one of them is done from the workout review screen, per entry.
                        onRemove = null,
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
            onCreate = { name, form, work, rest ->
                actions.createExercise(name, form, work, rest) { id -> askingRestFor = id }
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

    weighingFor?.let { id -> state.refById(id)?.let { ref ->
        WeightDialog(
            exerciseName = ref.name,
            initialKg = lastAddedKg(state, ref),
            onConfirm = { kg ->
                actions.startProtocolSet(ref, kg)
                weighingFor = null
            },
            onDismiss = { weighingFor = null },
        )
    } }

    /*
     * Resolved out of the workout on every recomposition rather than captured when the menu
     * was raised, same rule as the entry editor next door: the journal is re-folded after
     * every write, so a held copy would be the pre-removal one. A block that disappeared from
     * under the question simply draws nothing.
     */
    removingKey?.let { key ->
        workout.exercises.firstOrNull { it.exercise.key == key }?.let { exercise ->
            val setCount = exercise.sets.size
            ConfirmRemoveDialog(
                title = "Remove this exercise from the workout?",
                subject = exerciseName(state, exercise),
                explanation = (
                    if (setCount == 0) {
                        "Nothing has been recorded under it yet, so nothing stops counting. "
                    } else {
                        "Its $setCount ${if (setCount == 1) "set goes" else "sets go"} with it " +
                            "and stop counting towards volume, records and the streak. "
                    }
                    ) + REMOVAL_IS_REVERSIBLE,
                onConfirm = {
                    removingKey = null
                    actions.removeExercise(
                        exercise.addedEventIds + exercise.sets.map { it.id }
                    )
                },
                onDismiss = { removingKey = null },
            )
        }
    }

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

/**
 * Tells the journal that [keys] is the order of [live], unless there is nothing to tell it.
 *
 * Returns whether anything was written, which is what lets the caller decide what to do with the
 * arrangement it is holding: nothing was written means nothing is coming back, so the preview has
 * to be dropped or the screen would show an order the journal does not have.
 *
 * Two ways there is nothing to say. The order is ALREADY that — a card dragged and put back, and
 * a row stating what is already true is a row every future reader has to fold for no answer. Or
 * [keys] names something [live] no longer holds, which is a preview that outlived the workout it
 * described (an exercise removed on another screen mid-drag); stating a partial order would drop
 * whatever the preview does not name to the bottom of the list, silently.
 */
private fun stateOrder(
    keys: List<String>,
    live: List<WorkoutExercise>,
    reorder: (List<ExerciseLink>) -> Unit,
): Boolean {
    if (keys == live.map { it.exercise.key }) return false
    val wanted = keys.mapNotNull { key -> live.firstOrNull { it.exercise.key == key } }
    if (wanted.size != live.size) return false
    reorder(wanted.map { it.exercise })
    return true
}

/**
 * The wall clock out of a journal timestamp: "2026-08-07T18:14:00" -> "18:14".
 *
 * By position rather than by parsing, because every timestamp this app writes has that exact
 * shape and a row that does not is a row nothing else could read either. A string that is
 * too short comes back whole, which is a visibly odd heading rather than a crash on the
 * screen somebody is standing in a gym with.
 */
private fun clockOf(ts: String): String =
    ts.substringAfter('T', "").takeIf { it.length >= 5 }?.take(5) ?: ts

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
 * The plate hung on the last set of this exercise, or null when there was none.
 *
 * A ZERO IS A NULL here, and that is the whole of the rule §13.5 asks for: the weight
 * question exists for the belt-and-plate case, and a protocol done at body weight has never
 * had a question in front of it. "Nothing was hung last time" and "nothing has ever been
 * logged" both mean there is nothing to prefill and nothing worth asking about, so they give
 * the same answer rather than two branches that read differently and behave the same.
 *
 * Only hold sets carry an added weight, which is also the only form that carries a protocol —
 * so this is the whole of the question for every exercise that can reach it.
 */
private fun lastAddedKg(state: UiState, exercise: ExerciseRef): Double? =
    lastHoldSet(state.events, exercise.link)?.addedKg?.takeIf { it != 0.0 }

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
 * What came due while a protocol-led set had the rests silenced.
 *
 * ── One line, and no new noise ──────────────────────────────────────────────────
 * A running conductor mutes every floor, because a beep in the middle of a seven-second hang
 * is precisely the thing that ruins the set it was meant to time (domain/Floors.kt). What
 * that leaves behind is a set of rests that matured unannounced, and the wrong way to settle
 * up is a burst of the beeps that were withheld: they arrive after the fact, out of order,
 * and say nothing about WHEN each one came due. So the debt is paid in words instead —
 * already spoken by the time this appears, and now also written down, which is the half that
 * was missing (§13.4).
 *
 * It is a statement and not a warning, so it carries no icon and no alarm colour: everything
 * in it is also visible on the cards below, in the bars that have been counting all along.
 * What it adds is that they can be read at a glance, at the one moment they are all relevant
 * at once.
 */
@Composable
private fun ReadyBanner(line: String, onDismiss: () -> Unit) {
    val colors = LocalGachiColors.current
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 15.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                line,
                fontSize = 12.sp,
                color = colors.goodText,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Got it", fontSize = 12.sp)
            }
        }
    }
}

/**
 * One exercise of the workout: what it is, what has been done of it, and where its rest is.
 *
 * The whole card is the tap target for logging, because that is the move being repeated all
 * session and it should not require aiming. The rest is the one other control, and it is a
 * text button showing the current value — the label IS the control, so no icon has to be
 * decoded and no second row of chrome appears on a card that is mostly numbers.
 *
 * ── A card in the hand is drawn as one ──────────────────────────────────────────
 * [lifted] raises it off the page: a shadow under it and a different fill behind it, because a
 * shadow alone is a few pixels of grey that is easy to miss under a thumb and this app states
 * every verdict in more than one channel. The gap it leaves behind opens because the LIST moves
 * the other cards, not because anything is animated here.
 */
@Composable
private fun ExerciseCard(
    name: String,
    restSec: Int?,
    sets: List<String>,
    /** A protocol-led set of this exercise is being conducted right now. */
    running: Boolean,
    floor: RestFloor?,
    nowMs: Long,
    onTap: (() -> Unit)?,
    onRest: (() -> Unit)?,
    /** Long press, released without moving: take this exercise out of the workout. */
    onRemove: (() -> Unit)?,
    /** Long press, moved: carry the card. Null when there is nowhere to carry it to. */
    drag: ItemDrag? = null,
    lifted: Boolean = false,
    /**
     * Pixels to draw the card away from where the list put it.
     *
     * A LAMBDA and not a value, so the reading happens while the frame is being drawn rather
     * than in composition: a card being dragged moves on every pointer event, and recomposing
     * a card carrying a progress bar sixty times a second to move it is the cost this avoids.
     */
    liftOffset: () -> Float = { 0f },
    /** Move this card one place, from the menu. Null at the end it is already at. */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val colors = LocalGachiColors.current
    val menu = buildList {
        onMoveUp?.let { add(ItemAction("Move up") { it() }) }
        onMoveDown?.let { add(ItemAction("Move down") { it() }) }
        // destructive last, and away from the top of the menu where the finger already is
        onRemove?.let { add(ItemAction("Remove from this workout", destructive = true) { it() }) }
    }
    ItemActions(
        title = name,
        actions = menu,
        onTap = onTap,
        drag = drag,
        modifier = Modifier
            .fillMaxWidth()
            // above its neighbours, or the card it is being dragged over draws on top of it
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                translationY = liftOffset()
                shadowElevation = if (lifted) 8.dp.toPx() else 0f
                shape = RoundedCornerShape(CardRadius)
                clip = false
            },
    ) { press ->
    GachiCard(
        Modifier.fillMaxWidth().then(press),
        background = if (lifted) MaterialTheme.colorScheme.surfaceVariant else null,
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

        if (running) {
            /*
             * The card of a set being conducted somewhere else. The words matter more than
             * usual here: the phone has been put down or the screen has been left, and this
             * line is the only thing saying that the protocol did not stop when the screen
             * did — and that this card, not the Programs tab, is the way back to it.
             */
            Text(
                "Set running - tap to go back to it",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
                modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 9.dp),
            )
        }

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
                /*
                 * The chosen rest, said the way a person reads a rest, and said LOUDLY.
                 *
                 * The field above holds bare seconds because that is what the steppers add
                 * to, and "90" is not a length of time anyone recognises at a glance. This
                 * line used to be the small grey afterthought under it, which put the only
                 * legible form of the answer in the smallest type on the screen — reported
                 * from the phone as "hard to tell what is even selected" (2026-08-08).
                 */
                Text(
                    seconds?.let(::formatClock) ?: "--:--",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (seconds != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        colors.inkMuted
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                if (seconds == null) {
                    Text(
                        "A rest is between 1 second and an hour.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
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
 * The plate, asked on the way INTO a protocol-led set.
 *
 * ── Why before and not after ────────────────────────────────────────────────────
 * The weight is hung on the belt before you get under the cable, so before the set is the
 * moment the answer is actually known and the moment the phone is still in a hand. Asking
 * afterwards was the state this replaces, and it produced a specific failure (§13.5): the run
 * offered one weight for the whole thing, so a set where the last few reps were done lighter
 * had nowhere to say so.
 *
 * ── An empty box is a legitimate answer ─────────────────────────────────────────
 * Blank means "no plate today", which is why the confirm button is enabled on arrival and
 * with the field cleared. This dialog is only ever raised when the last set DID carry one
 * (see [lastAddedKg]), so the ordinary path is one tap on a number that is already right, and
 * the only thing that ever needs typing is the day the plate comes off or changes.
 */
@Composable
private fun WeightDialog(
    exerciseName: String,
    initialKg: Double?,
    onConfirm: (Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current
    var draft by remember(exerciseName, initialKg) {
        mutableStateOf(initialKg?.let { formatNumber(it) }.orEmpty())
    }
    val kg = parseNumber(draft)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Added weight") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    exerciseName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StepperField(
                    label = "Added weight, kg",
                    value = draft,
                    onValueChange = { draft = it },
                    steps = listOf(2.5, 5.0),
                )
                Text(
                    "Hang it before you start. Leave it empty for none.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(kg) }) { Text("Start the set") }
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
