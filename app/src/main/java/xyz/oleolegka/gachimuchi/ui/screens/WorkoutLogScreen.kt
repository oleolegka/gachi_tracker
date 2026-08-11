package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.ActivityEvent
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.LoadedSet
import xyz.oleolegka.gachimuchi.domain.MAX_REST_INPUT_SEC
import xyz.oleolegka.gachimuchi.domain.MIN_STEP_SEC
import xyz.oleolegka.gachimuchi.domain.OrderedCard
import xyz.oleolegka.gachimuchi.domain.RestFloor
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.Workout
import xyz.oleolegka.gachimuchi.domain.WorkoutExercise
import xyz.oleolegka.gachimuchi.domain.activityName
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.cardKey
import xyz.oleolegka.gachimuchi.domain.ceilSeconds
import xyz.oleolegka.gachimuchi.domain.formatClock
import xyz.oleolegka.gachimuchi.domain.formatDurationSec
import xyz.oleolegka.gachimuchi.domain.formatNumber
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.lastTimeOf
import xyz.oleolegka.gachimuchi.domain.ledByProtocol
import xyz.oleolegka.gachimuchi.domain.parseDurationText
import xyz.oleolegka.gachimuchi.domain.parseNumber
import xyz.oleolegka.gachimuchi.domain.progressAt
import xyz.oleolegka.gachimuchi.domain.restHintSec
import xyz.oleolegka.gachimuchi.domain.startsRest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.label
import xyz.oleolegka.gachimuchi.ui.components.ConfirmRemoveDialog
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.ItemAction
import xyz.oleolegka.gachimuchi.ui.components.ItemActions
import xyz.oleolegka.gachimuchi.ui.components.ItemDrag
import xyz.oleolegka.gachimuchi.ui.components.SetTable
import xyz.oleolegka.gachimuchi.ui.components.TabularFigures
import xyz.oleolegka.gachimuchi.ui.components.setTable
import xyz.oleolegka.gachimuchi.ui.components.moved
import xyz.oleolegka.gachimuchi.ui.components.rememberReorderState
import xyz.oleolegka.gachimuchi.ui.components.REMOVAL_IS_REVERSIBLE
import xyz.oleolegka.gachimuchi.ui.components.StepperField
import xyz.oleolegka.gachimuchi.ui.components.TimeField
import xyz.oleolegka.gachimuchi.ui.components.rememberTickingNow
import xyz.oleolegka.gachimuchi.ui.fmtShortDay
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize
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
     *
     * [side] names one CARD. A new exercise trained one limb at a time gets this called TWICE by
     * this screen, once per [HoldSide] — see [RestAsk.New] — and an existing card's own "set a
     * rest" calls it once, with that card's own side (null for an exercise that has only one).
     */
    val addExercise: (exerciseId: Long, restSec: Int, side: HoldSide?) -> Unit,

    /**
     * Create a catalog exercise, and hand its id back.
     *
     * The continuation is what makes creating an exercise mid-workout work at all: the rest is
     * asked for straight afterwards and the question needs the id, which only exists once the
     * row has been written.
     */
    val createExercise: (new: NewExercise, then: (Long) -> Unit) -> Unit,

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
    /**
     * [exerciseId]/[side] name the card even when [eventIds] is empty — a card staged into a
     * draft that has not been written yet has nothing to delete, and this is how a caller
     * knows which staged card to drop, and which rest bar (if any) to take down with it.
     */
    val removeExercise: (eventIds: List<Long>, exerciseId: Long?, side: HoldSide?) -> Unit,

    /**
     * State the order the exercises of this workout are to be done in, WHOLE.
     *
     * The whole list on every change rather than "this one moved there", for the reasons on
     * `TYPE_WORKOUT_ORDER_SET`. What that buys the screen is that it never has to describe a
     * move: it hands over the arrangement it is currently showing, and the journal's answer
     * either matches it or replaces it.
     *
     * By CARD, not by exercise (see [OrderedCard]) — a one-sided exercise's two cards each get
     * their own entry, so dragging the left one past the right one states a real order.
     */
    val reorderExercises: (order: List<OrderedCard>) -> Unit,

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
     *
     * [side] names the CARD that was tapped, the same as [addExercise] does — a one-sided
     * exercise led by its protocol still draws two cards, and this is what stops both of them
     * leading to one run that cannot say which hand it counted.
     */
    val startProtocolSet: (exercise: ExerciseRef, addedKg: Double?, side: HoldSide?) -> Unit,

    /**
     * Mark one CARD done — see `ActivityRepository.finishWorkoutExercise` (§14.2).
     *
     * The card keeps everything already on it, collapses, and joins the finished group above
     * every active card.
     *
     * ── Where this DIFFERS from finishing the whole workout, deliberately ───────────
     * A finished workout is a status and not a lock: its cards stay tappable, and the set
     * remembered on the way to the car goes straight in. A finished CARD is not that. The ask
     * was "so it gets in the way less and cannot be tapped again by accident", and on this
     * screen a tap on the card is the only way to the entry form — so the tap goes, and with
     * it the only route to logging into this card until "Back to active" puts it among the
     * active ones again.
     *
     * The DOMAIN still refuses nothing (see TYPE_WORKOUT_EXERCISE_FINISHED): a set landing on
     * a finished card is recorded and does not un-finish it, which is what keeps a set arriving
     * from anywhere else — an import, a merge, a later screen — from being dropped on the
     * floor. It is this screen, and only this screen, that stops offering.
     */
    val finishExercise: (exercise: ExerciseLink, side: HoldSide?) -> Unit,

    /**
     * Undo [finishExercise] for one card: deletes the "card finished" event named by the
     * block itself — the same reversal every other entry in this app gets, and why this asks
     * for an id rather than an exercise: the card the screen is holding already has the one
     * event that made it finished.
     */
    val unfinishExercise: (eventId: Long) -> Unit,

    /**
     * Undo [finish] for the whole workout — the same reversal [unfinishExercise] is for one
     * card, named by the id of the "workout finished" event itself.
     */
    val unfinishWorkout: (eventId: Long) -> Unit,

    /** Go back to the set already running. It has never stopped; this only shows it again. */
    val openConductor: () -> Unit,

    /** Leave the workout. It is not finished by leaving — nothing here ends one. */
    val close: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutLogScreen(
    state: UiState,
    /**
     * The workout being logged, or null while it is still a draft — see §13.1. A draft has no
     * row in the journal yet, so this screen has nothing to fold [state.events] against; it
     * draws [draftWorkout] instead, and every action closes over the draft rather than an id.
     */
    workoutId: Long?,
    /** The draft this screen shows when [workoutId] is null — see [draftWorkout]. */
    draftWorkout: Workout? = null,
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
    /** No start event written yet — see [workoutId]'s own KDoc. */
    val draftMode = workoutId == null
    val workout = remember(state.events, workoutId, draftWorkout) {
        workoutId?.let { buildWorkout(state.events, it) } ?: draftWorkout
    }

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
    /** The exercise the rest dialog is asking about, or null for no question on screen. */
    var askingRestFor by rememberSaveable { mutableStateOf<Long?>(null) }
    /**
     * Which CARD [askingRestFor] means — [HoldSide.code], or null for either "this exercise has
     * only one card" or "fresh from the picker, both cards at once" ([askingRestIsNew] tells the
     * two apart). Kept as three primitives rather than one small class because
     * [rememberSaveable] needs a type it already knows how to put in a bundle.
     */
    var askingRestSide by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * Whether the rest dialog was raised from the picker — a brand new exercise, where a
     * one-sided one owes BOTH its cards the same rest at once — rather than from a card already
     * in the workout asking to change its own.
     */
    var askingRestIsNew by rememberSaveable { mutableStateOf(false) }
    /** The exercise whose quick entry form is raised, or null. */
    var entryFor by rememberSaveable { mutableStateOf<Long?>(null) }
    /**
     * Which CARD [entryFor] was raised from — see [WorkoutExercise.side] — so the form knows
     * without asking again which hand the card it was tapped from already answered for.
     */
    var entrySide by rememberSaveable { mutableStateOf<String?>(null) }
    /** The exercise whose protocol-led set is waiting on the weight question, or null. */
    var weighingFor by rememberSaveable { mutableStateOf<Long?>(null) }
    /** Which CARD [weighingFor] was raised from — the same idea as [entrySide], for the other tap. */
    var weighingSide by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * The card whose removal is being confirmed, by [WorkoutExercise.cardKey] rather than by
     * catalog id: a block can be there for an exercise this phone has no catalog row for, and
     * that block is exactly the one somebody would want out of the workout — and a one-sided
     * exercise has two blocks that must be told apart.
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
        preview?.let { keys -> keys.mapNotNull { key -> live.firstOrNull { it.cardKey == key } } }
            ?: live
    }
    LaunchedEffect(live, preview) {
        val liveKeys = live.map { it.cardKey }
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
    val liveKeys = { live.map { it.cardKey } }
    val shownKeys = { preview ?: liveKeys() }
    /*
     * THE TWO GROUPS A CARD CAN BE DRAGGED WITHIN, and the reason a card cannot leave its own.
     *
     * Finished cards are drawn above the active ones and ordered by when they were finished
     * (domain/Workout.kt, groupedByCardStatus). A drag that carried a card across the line
     * would write an order this screen would then throw away on the next fold - the gesture
     * would appear to work, land nowhere, and leave the person who made it to guess why. The
     * grouping is what stops the gesture at the boundary instead.
     *
     * Read off the LIVE cards rather than the preview: what group a card belongs to is a fact
     * about the workout, not about a drag in progress, and mid-drag the preview is exactly the
     * thing being second-guessed.
     */
    val groupOfCard = { key: String -> live.firstOrNull { it.cardKey == key }?.finished }
    val reorder = rememberReorderState(
        listState = listState,
        keys = shownKeys,
        onOrder = { order -> preview = order },
        groupOf = groupOfCard,
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
            WorkoutBar(
                // the name snapshot taken when "start" was pressed, never the plan's name as it
                // reads today — a plan is editable and a fact is not
                title = workout.name ?: "Workout",
                meta = listOfNotNull(
                    date?.let { fmtWeekdayDay(it) },
                    summaryOf(workout),
                    // stated only once it means something: an unfinished workout has an end
                    // time too, and it is simply "so far"
                    "finished ${clockOf(workout.endTs)}".takeIf { workout.finished },
                ).joinToString(" · "),
                onClose = actions.close,
                /*
                 * THE SAME SLOT DOES THREE THINGS, one at a time, never more than one on screen
                 * at once: draft, finish, and undo the finish. A draft has nothing to finish yet
                 * — this IS the explicit "start workout" §13.1 asks for — and a finished workout
                 * is a status and not a lock, so the way back is right where the way there was.
                 */
                stateLabel = when {
                    draftMode -> "Start workout"
                    workout.finished -> "Reopen"
                    else -> "Finish"
                },
                onState = when {
                    workout.finished && !draftMode ->
                        ({ workout.finishedEventId?.let(actions.unfinishWorkout); Unit })

                    else -> actions.finish
                },
                /*
                 * "Undo last" has moved into the menu. It is pressed about once a session, and
                 * as a text button it took a third of the width of a 360 dp bar to say a thing
                 * that is usually greyed out. It stays as far from the thumb as it can be put:
                 * a mis-tap here cancels a set that really happened.
                 */
                onUndoLast = lastSetOf(workout)?.let { last -> ({ actions.undoSet(last) }) },
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
            /*
             * Scaffold does NOT give the bottom bar slot any window insets of its own — it only
             * measures whatever this composes and uses that height as the content's bottom
             * padding (see the Material3 Scaffold source: `bottom = bottomBarHeight ?: insets`
             * only falls back to insets when there is no bottom bar at all). So the system
             * navigation bar has to be accounted for HERE, once, or the button sits under it on
             * three-button navigation — [navigationBarsPadding] reads the real inset (small on
             * gesture nav, larger on three-button nav) rather than a guessed constant, which is
             * what keeps gesture nav exactly as it already was.
             */
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    HorizontalDivider(color = colors.border)
                    Button(
                        onClick = { picking = true },
                        shape = RoundedCornerShape(Radius.Small),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = Spacing.Block, vertical = Spacing.Inset)
                            .heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(
                            "Add exercise",
                            fontSize = TextSize.Body,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = Spacing.Line),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = Spacing.Block, end = Spacing.Block,
                top = Spacing.Block, bottom = Spacing.Cards,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Cards),
        ) {
            if (workout.exercises.isEmpty()) {
                item {
                    /*
                     * ONE CALL TO ACTION on an empty draft, not three. There used to be this
                     * note in a dashed box, the filled button under it, and "Start workout" in
                     * the bar — and the third of those does something else entirely. What is
                     * left says what is empty, which is all an empty state owes anybody.
                     */
                    Text(
                        "No exercises in this workout yet.",
                        fontSize = TextSize.Body,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(top = Spacing.Block),
                    )
                }
            }

            items(shown.size, key = { shown[it].cardKey }) { index ->
                val exercise = shown[index]
                val key = exercise.cardKey
                val id = exercise.exerciseId
                // an exercise this phone has no catalog row for cannot have a form built
                // for it, so it is shown and not offered — see WorkoutExercise.exerciseId
                val ref = state.refById(id)
                val running = id != null && id == liveExerciseId
                ExerciseCard(
                    name = exerciseName(state, exercise),
                    restSec = exercise.restSec,
                    sets = exercise.sets.map { it.form },
                    running = running,
                    floor = id?.let { e -> floors.firstOrNull { it.exerciseId == e && it.side == exercise.side?.code } },
                    nowMs = nowMs,
                    onTap = when {
                        running -> actions.openConductor
                        ref == null -> null
                        /*
                         * The protocol comes FIRST, before the form: for these the app is
                         * conducting a set rather than taking a report of one, and there are
                         * no numbers to prefill a form with until the set has been done.
                         * `ledByProtocol` is the exercise's own answer (a maximum-weight hang
                         * carries a protocol and is still led by weight — §13.2), and there has
                         * to be something a run can actually be made of.
                         *
                         * That second test is `canBeConducted` and no longer "the protocol pair
                         * is not null". A strict schedule (§18.15) may open with a block that
                         * has no rest of its own, which reads as no pair at all — so the very
                         * schedules that fix the most were the ones falling through to the
                         * manual entry form.
                         *
                         * NOW side-aware: `exercise.side` is which of the two cards this tap
                         * landed on, and it is passed straight through to the run — the same
                         * value the manual entry form below is handed as `entrySide` — so a
                         * one-sided exercise's two protocol-led cards lead to two runs that
                         * each know which hand they counted, instead of one run that knows
                         * neither.
                         */
                        ledByProtocol(ref) && ref.canBeConducted -> {
                            {
                                /*
                                 * ASK ONLY IF THERE WAS A PLATE LAST TIME (§13.5). Asking
                                 * unconditionally would put a question in front of every
                                 * bodyweight protocol — a set that used to start with one tap
                                 * and no screens at all.
                                 */
                                if (lastAddedKg(state, ref) == null) {
                                    actions.startProtocolSet(ref, null, exercise.side)
                                } else {
                                    weighingFor = ref.id
                                    weighingSide = exercise.side?.code
                                }
                            }
                        }

                        else -> {
                            {
                                entryFor = ref.id
                                entrySide = exercise.side?.code
                            }
                        }
                    },
                    // the same rule the auto-started rest already follows (§13.9) — a weigh-in
                    // is not followed by another set of itself, and the button offering to
                    // time a pause after one was the remaining trace of "brother mistook the
                    // scales for an exercise"
                    onRest = id?.takeIf { ref != null && startsRest(ref.form) }?.let { e ->
                        {
                            askingRestFor = e
                            askingRestSide = exercise.side?.code
                            askingRestIsNew = false
                        }
                    },
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
                    finished = exercise.finished,
                    // a card of a draft cannot be finished — the workout it would belong to
                    // does not exist yet, and offering the button would ask a question the
                    // journal has no row to answer
                    onFinish = if (draftMode || exercise.finished || ref == null) {
                        null
                    } else {
                        { actions.finishExercise(ref.link, exercise.side) }
                    },
                    // the id of the event that said "done" IS the handle for undoing it
                    onUnfinish = exercise.finishedEventId?.let { eventId ->
                        { actions.unfinishExercise(eventId) }
                    },
                )
            }

            if (workout.entriesWithoutExercise.isNotEmpty()) {
                item {
                    /*
                     * A weigh-in carries no exercise_id by design, so it belongs to the workout
                     * without belonging to any of its exercises. Shown rather than dropped: an
                     * entry that is in the journal and on no screen is how a record silently
                     * stops existing.
                     *
                     * IT IS CALLED WHAT IT IS. The heading was "Other entries" on a card that,
                     * nine times in ten, holds one weigh-in — and the app knows the name of the
                     * thing (`activityName`). It generalises only when the card really does hold
                     * more than one kind of entry; "belongs to no exercise" is a fact about it
                     * and has gone to the meta line, where facts about a card live.
                     */
                    val loose = workout.entriesWithoutExercise
                    ExerciseCard(
                        name = loose.map { it.form.activityName() }.distinct().singleOrNull()
                            ?: "Other entries",
                        restSec = null,
                        sets = loose.map { it.form },
                        countNoun = "entry" to "entries",
                        metaNote = "not part of any exercise",
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
            onPick = { id -> askingRestFor = id; askingRestSide = null; askingRestIsNew = true },
            onCreate = { new ->
                actions.createExercise(new) { id ->
                    askingRestFor = id
                    askingRestSide = null
                    askingRestIsNew = true
                }
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
        // fresh from the picker: nothing is "already" there yet, whatever a stale card of the
        // same exercise from an earlier session says — see the write below for what "fresh"
        // then does for a one-sided exercise
        val already = if (askingRestIsNew) null else {
            workout.exercises.firstOrNull { it.exerciseId == id && it.side?.code == askingRestSide }
        }
        RestDialog(
            exerciseName = ref.name,
            // what was chosen for THIS workout wins; failing that, restHintSec knows the
            // order of the rest (the catalog column, then what was actually rested)
            initialSec = already?.restSec?.takeIf { it >= MIN_STEP_SEC }
                ?: restHintSec(settings, state.events, ref),
            confirmLabel = if (already == null) "Add to workout" else "Save",
            onConfirm = { sec ->
                /*
                 * A one-sided exercise picked fresh gets BOTH its cards, at this same rest, in
                 * one answer to one question — the two-card rule holds regardless of which of
                 * the two ways an exercise enters a workout. A card already in the workout
                 * changing its own rest touches only that card: [askingRestSide] names it, and
                 * it is never null for a card that came from a real left/right split.
                 */
                if (askingRestIsNew && ref.oneSided) {
                    actions.addExercise(id, sec, HoldSide.LEFT)
                    actions.addExercise(id, sec, HoldSide.RIGHT)
                } else {
                    actions.addExercise(id, sec, askingRestSide?.let(HoldSide::fromCode))
                }
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
                actions.startProtocolSet(ref, kg, weighingSide?.let(HoldSide::fromCode))
                weighingFor = null
                weighingSide = null
            },
            onDismiss = { weighingFor = null; weighingSide = null },
        )
    } }

    /*
     * Resolved out of the workout on every recomposition rather than captured when the menu
     * was raised, same rule as the entry editor next door: the journal is re-folded after
     * every write, so a held copy would be the pre-removal one. A block that disappeared from
     * under the question simply draws nothing.
     */
    removingKey?.let { key ->
        workout.exercises.firstOrNull { it.cardKey == key }?.let { exercise ->
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
                        // every row the card owns — the "added" rows, the sets, AND the "card
                        // finished" row when there is one, or a removed finished card comes
                        // back as an empty ghost with nothing left to clear it (§14.1)
                        exercise.addedEventIds + exercise.sets.map { it.id } +
                            listOfNotNull(exercise.finishedEventId),
                        exercise.exerciseId,
                        exercise.side,
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
            fixedSide = entrySide?.let(HoldSide::fromCode),
            onAddSet = { form ->
                actions.addSet(form)
                entryFor = null
                entrySide = null
            },
            onDismiss = { entryFor = null; entrySide = null },
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
    reorder: (List<OrderedCard>) -> Unit,
): Boolean {
    if (keys == live.map { it.cardKey }) return false
    val wanted = keys.mapNotNull { key -> live.firstOrNull { it.cardKey == key } }
    if (wanted.size != live.size) return false
    reorder(wanted.map { OrderedCard(it.exercise, it.side) })
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
 *
 * `internal` rather than `private`: [LogScreen] asks the same §13.5 question before its own
 * one-tap program starts, and it has to be the SAME question — a second copy would drift the
 * first time one of the two screens changed what counts as "nothing was hung".
 */
internal fun lastAddedKg(state: UiState, exercise: ExerciseRef): Double? =
    lastHoldSet(state.events, exercise.link)?.addedKg?.takeIf { it != 0.0 }

/**
 * The exercise's name — with the hand appended, for the exercise trained one limb at a time
 * that this card is one half of. Without it the two cards a one-sided exercise gets would read
 * as the same name twice, which is no way to tell which one just got tapped.
 *
 * The catalog first, because that is the name the user maintains. The set's own payload is
 * the fallback and it matters: the journal outlives the catalog, and a card headed
 * "Exercise 14" is one you cannot use.
 */
private fun exerciseName(state: UiState, exercise: WorkoutExercise): String {
    val name = state.exerciseById(exercise.exerciseId)?.name
        ?: exercise.sets.firstOrNull()?.form?.activityName()
        ?: "Exercise ${exercise.exerciseId}"
    return exercise.side?.let { "$name - ${it.label()}" } ?: name
}

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
    /*
     * ON THE RECESSED SURFACE, with a hairline under it. It used to be filled with
     * `surfaceVariant`, which in the light theme is the colour of the plane it sits on — a
     * banner separated from the screen by nothing at all.
     */
    Surface(color = colors.recessed, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.Block, end = Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    line,
                    fontSize = TextSize.Meta,
                    color = colors.goodText,
                    fontWeight = FontWeight.SemiBold,
                    style = TabularFigures,
                    modifier = Modifier.weight(1f).padding(vertical = Spacing.Line),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Got it", fontSize = TextSize.Meta)
                }
            }
            HorizontalDivider(color = colors.border)
        }
    }
}

/**
 * The title bar of a workout — OUR OWN, and not `TopAppBar`.
 *
 * ── Why the Material one had to go ──────────────────────────────────────────────
 * `TopAppBar` is fixed at 64 dp and clips whatever does not fit. This bar carries a name and a
 * line of facts under it, and on a 360 dp screen the second line was cut in half — the exact
 * defect rule 8 names ("what does not fit is a defect, not a detail"). Nothing here has a fixed
 * height: the name is one line with an ellipsis, the facts are a line of their own, and the
 * panel is as tall as the two of them.
 *
 * What that costs, stated plainly: `scrollBehavior` and the component's own window insets are
 * not inherited. The screen never used a scroll behaviour, so nothing is lost there; the insets
 * are applied here by hand, and they have to stay — the status bar is drawn under this app.
 */
@Composable
private fun WorkoutBar(
    title: String,
    meta: String,
    onClose: () -> Unit,
    /** "Finish", "Reopen" or "Start workout" — one slot, one at a time. */
    stateLabel: String,
    onState: () -> Unit,
    /** Take back the last set of this workout. Null when there is none to take back. */
    onUndoLast: (() -> Unit)?,
) {
    val colors = LocalGachiColors.current
    var menu by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        start = Spacing.Tight, end = Spacing.Tight, bottom = Spacing.Inset,
                    )
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Leave the workout",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        title,
                        fontSize = TextSize.Title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onState,
                        shape = RoundedCornerShape(Radius.Small),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stateLabel, fontSize = TextSize.Meta, fontWeight = FontWeight.SemiBold)
                    }
                    if (onUndoLast != null) {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Actions for this workout",
                                    tint = colors.inkSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Undo last set") },
                                    onClick = {
                                        menu = false
                                        onUndoLast()
                                    },
                                )
                            }
                        }
                    }
                }
                /*
                 * Aligned with the CONTENT of the screen below (16), not indented under the
                 * title (48): this is the bar's second line, not a caption of the name.
                 */
                Text(
                    meta,
                    fontSize = TextSize.Meta,
                    color = colors.inkSecondary,
                    style = TabularFigures,
                    modifier = Modifier.padding(start = Spacing.Inset, top = Spacing.Tight),
                )
            }
            HorizontalDivider(color = colors.border)
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
    /**
     * The sets, AS DATA. It used to be `List<String>` — one summary line per set, joined with
     * commas at the bottom of the card — and a column cannot be recovered from a sentence: the
     * load, what was done with it and the protocol have to be separable before anything can be
     * lined up, collapsed or lifted into the meta. See [setTable].
     */
    sets: List<ActivityForm>,
    /** Singular and plural of what this card counts. Sets, unless the card holds something else. */
    countNoun: Pair<String, String> = "set" to "sets",
    /** Anything else the meta line should carry after the count and the protocol. */
    metaNote: String? = null,
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
    /**
     * This card is done for today: drawn thin, ticked, and refusing to log anything.
     *
     * REFUSING is the point rather than a side effect. The ask was "so it gets in the way less
     * and cannot be tapped again by accident", and a card that still takes a tap while looking
     * finished would be the worst of the two states. So [onTap], the rest button and the
     * countdown all go, and what is left is the name, the tick and the way back.
     */
    finished: Boolean = false,
    /** Put a finished card back among the active ones. Null on a card that is not finished. */
    onUnfinish: (() -> Unit)? = null,
    /** Mark this card done. Null on a card that already is. */
    onFinish: (() -> Unit)? = null,
) {
    val colors = LocalGachiColors.current
    val menu = buildList {
        onFinish?.let { add(ItemAction("Mark as done") { it() }) }
        onUnfinish?.let { add(ItemAction("Back to active") { it() }) }
        onMoveUp?.let { add(ItemAction("Move up") { it() }) }
        onMoveDown?.let { add(ItemAction("Move down") { it() }) }
        // destructive last, and away from the top of the menu where the finger already is
        onRemove?.let { add(ItemAction("Remove from this workout", destructive = true) { it() }) }
    }
    ItemActions(
        title = name,
        actions = menu,
        // a finished card takes no taps at all: see [finished]
        onTap = onTap.takeIf { !finished },
        drag = drag,
        modifier = Modifier
            .fillMaxWidth()
            // above its neighbours, or the card it is being dragged over draws on top of it
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                translationY = liftOffset()
                shadowElevation = if (lifted) 8.dp.toPx() else 0f
                shape = RoundedCornerShape(Radius.Card)
                clip = false
            },
    ) { press, openMenu ->
    val table = remember(sets, restSec) { setTable(sets, restSec) }
    GachiCard(
        Modifier.fillMaxWidth().then(press),
        background = if (lifted) MaterialTheme.colorScheme.surfaceVariant else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = Spacing.Inset, end = Spacing.Tight,
                top = Spacing.Tight, bottom = Spacing.Tight,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
        ) {
            if (finished) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Done",
                    tint = colors.good,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                name,
                fontSize = TextSize.Title,
                fontWeight = FontWeight.SemiBold,
                color = if (finished) colors.inkSecondary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onRest != null && !finished) {
                /*
                 * AN OUTLINED BUTTON, not a text one. It was 12 sp of unadorned text and read as
                 * a caption of the card rather than as the control it is — and it is the second
                 * most pressed thing on this screen. The 48 dp is the platform's floor and not
                 * the mock's 40: this is a control aimed at between sets, with one hand.
                 */
                OutlinedButton(
                    onClick = onRest,
                    shape = RoundedCornerShape(Radius.Small),
                    contentPadding = PaddingValues(horizontal = Spacing.Inset),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        restSec?.takeIf { it >= MIN_STEP_SEC }
                            ?.let { "Rest ${formatClock(it)}" }
                            ?: "Set a rest",
                        fontSize = TextSize.Meta,
                        fontWeight = FontWeight.SemiBold,
                        style = TabularFigures,
                    )
                }
            }
            /*
             * The same menu the long press raises, with something on the card that says it is
             * there. "Mark as done", moving the card and removing it were reachable by holding a
             * finger down and by nothing else.
             */
            if (menu.isNotEmpty()) {
                IconButton(onClick = openMenu, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Actions for $name",
                        tint = colors.inkMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        /*
         * EVERYTHING BELOW THE NAME IS WHAT "COLLAPSED" MEANS. A finished card keeps its name,
         * its tick and its menu, and drops the divider, the set list, the running line and the
         * countdown - which is the whole of the height it used to take.
         */
        if (finished) return@GachiCard

        HorizontalDivider(color = colors.grid)

        Column(Modifier.fillMaxWidth().padding(Spacing.Inset)) {
            if (running) {
                /*
                 * The card of a set being conducted somewhere else. The words matter more than
                 * usual here: the phone has been put down or the screen has been left, and this
                 * line is the only thing saying that the protocol did not stop when the screen
                 * did — and that this card, not the Programs tab, is the way back to it.
                 */
                Text(
                    "Set running · tap to go back to it",
                    fontSize = TextSize.Meta,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier.padding(bottom = Spacing.Line),
                )
            }

            /*
             * THE COUNT OF SETS, first and always. The owner asked for it outright — "нет общего
             * какого-то счётчика 'сделано 5 сетов', а мы хотели" — and warm-ups are in it,
             * because they are sets; which of them were ramp-ups is what the badges say. The
             * protocol stands here too when every set shares it, instead of once per set.
             */
            Text(
                listOfNotNull(
                    "${sets.size} ${if (sets.size == 1) countNoun.first else countNoun.second}",
                    table.commonProtocol,
                    metaNote,
                ).joinToString(" · "),
                fontSize = TextSize.Meta,
                color = if (sets.isEmpty()) colors.inkMuted else colors.inkSecondary,
                style = TabularFigures,
                modifier = Modifier.padding(bottom = Spacing.Line),
            )

            if (table.rows.isNotEmpty()) SetTable(table.rows, Modifier.fillMaxWidth())

            floor?.let { RestBar(it, nowMs, Modifier.padding(top = Spacing.Block)) }
        }
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
private fun RestBar(floor: RestFloor, nowMs: Long, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    val progress = floor.progressAt(nowMs)
    val overdueSec = (progress.overdueMs / 1000).toInt()
    val ordered = (floor.orderedMs / 1000).toInt()

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (progress.ready) "Ready" else "Rest left",
                fontSize = TextSize.Meta,
                // stated in words as well as colour, like every other verdict in this app
                color = if (progress.ready) colors.goodText else colors.inkSecondary,
                fontWeight = if (progress.ready) FontWeight.SemiBold else FontWeight.Normal,
            )
            /*
             * THE ONE LARGE NUMBER OF THE CARD, and only while it is counting. A rest still
             * running is read across a room; a rest that has matured is a fact and not a
             * countdown, so the overrun beside "Ready" is set at body size — 22 sp there would
             * shout the least urgent thing on the screen.
             */
            if (!progress.ready) {
                Text(
                    formatClock(ceilSeconds(progress.remainingMs)),
                    fontSize = TextSize.Figure,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = TabularFigures,
                    modifier = Modifier.padding(start = Spacing.Line),
                )
            } else if (overdueSec > 0) {
                Text(
                    "+${formatClock(overdueSec)}",
                    fontSize = TextSize.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.goodText,
                    style = TabularFigures,
                    modifier = Modifier.padding(start = Spacing.Line),
                )
            }
            Spacer(Modifier.weight(1f))
            // one format for one quantity, everywhere: m:ss on the button, m:ss here
            Text(
                if (progress.ready) "rest was ${formatClock(ordered)}" else "of ${formatClock(ordered)}",
                fontSize = TextSize.Meta,
                color = colors.inkMuted,
                style = TabularFigures,
            )
        }
        /*
         * Drawn rather than taken from LinearProgressIndicator, whose track reads
         * `surfaceContainerHighest` and whose stop indicator and gap are Material's own
         * decisions about a component this is not. Four points, our own recess, our own radius.
         */
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Line)
                .height(4.dp)
                .clip(RoundedCornerShape(Radius.Small))
                .background(colors.recessed)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.Small))
                    .background(if (progress.ready) colors.good else colors.accent)
            )
        }
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
    // seeded from a whole number of seconds (the rest this card already has, or the catalog's
    // remembered answer) and typed as mm:ss from there — see ui/components/TimeField.kt (§13.9)
    var draft by remember(exerciseName, initialSec) { mutableStateOf(formatDurationSec(initialSec)) }
    val seconds = parseDurationText(draft)?.takeIf { it in MIN_STEP_SEC..MAX_REST_INPUT_SEC }

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
                /*
                 * The time is on this dialog ONCE, in the field. It used to be twice — a
                 * headline above repeating whatever the field held, in a different size,
                 * added back when the field was a bare count of seconds and unreadable as a
                 * length of time. mm:ss entry made the field itself legible and left the
                 * headline as a duplicate; from the phone, 2026-08-11: "the time is written
                 * twice, in two different fonts". The one kept is the one you can edit.
                 */
                TimeField(
                    label = "Rest, mm:ss",
                    value = draft,
                    onValueChange = { draft = it },
                    bumpsSec = listOf(10, 30),
                    isError = draft.isNotBlank() && seconds == null,
                    // the minus buttons stop at the shortest rest this dialog can store,
                    // rather than walking down into a value it would then refuse
                    minSec = MIN_STEP_SEC,
                )
                if (seconds == null) {
                    Text(
                        "A rest is between 1 second and a day.",
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
 *
 * `internal` rather than `private`: [LogScreen] raises the exact same dialog before its own
 * one-tap program starts, on the same §13.5 rule — see [lastAddedKg]'s own note on why that
 * has to stay one question asked one way rather than two that can drift apart.
 */
@Composable
internal fun WeightDialog(
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
    /** The workout this sheet is raised for, or null while it is still a draft (§13.1). */
    workoutId: Long?,
    /** The card this sheet was raised from already answered which side — see [HoldEntry]. */
    fixedSide: HoldSide? = null,
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
                    "Last time ($dayText): " + last.sets.joinToString(", ") { it.lastTimeSetLine() }
                } ?: "No earlier set of this one.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkSecondary,
            )

            when (exercise.form) {
                ExerciseForm.STRENGTH -> StrengthEntry(state, exercise, opDate, onAddSet, fixedSide)
                ExerciseForm.HOLD -> HoldEntry(state, exercise, opDate, onAddSet, fixedSide)
                ExerciseForm.CARDIO -> CardioEntry(state, exercise, opDate, onAddSet)
                ExerciseForm.DURATION -> DurationEntry(state, exercise, opDate, onAddSet)
                ExerciseForm.TICK -> TickEntry(exercise, opDate, onAddSet)
                ExerciseForm.BODYWEIGHT -> BodyweightEntry(state, opDate, onAddSet)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * One entry of the "Last time" line: the set's own [summaryLine], with "(not completed)" tacked
 * on for the one this was actually asked for — see [StrengthSet.incomplete]. This is the
 * decision the owner asked the whole flag to feed: whether to push the weight up next time, or
 * hold it, or bring it down, and that decision is made right here, against the set that fell
 * short and not just the newest one — see [StrengthEntry]'s own [LastTimeIncompleteNote] for the
 * same fact stated once, for the screen that has no "Last time" line of its own to attach it to.
 */
private fun ActivityEvent.lastTimeSetLine(): String {
    val text = form.summaryLine()
    return if ((form as? LoadedSet)?.incomplete == true) "$text (not completed)" else text
}
