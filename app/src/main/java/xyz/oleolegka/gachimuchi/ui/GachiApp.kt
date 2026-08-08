package xyz.oleolegka.gachimuchi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.exerciseToLogNext
import xyz.oleolegka.gachimuchi.domain.knownCategories
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.loggingDay
import xyz.oleolegka.gachimuchi.domain.openWorkoutRow
import xyz.oleolegka.gachimuchi.timer.SpeechStatus
import xyz.oleolegka.gachimuchi.ui.components.DayActions
import xyz.oleolegka.gachimuchi.ui.components.LogReceiptDialog
import xyz.oleolegka.gachimuchi.ui.components.RunLogDialog
import xyz.oleolegka.gachimuchi.ui.components.TimerActions
import xyz.oleolegka.gachimuchi.ui.components.TimerUiState
import xyz.oleolegka.gachimuchi.ui.components.rememberTimerEnabler
import xyz.oleolegka.gachimuchi.ui.screens.CalendarScreen
import xyz.oleolegka.gachimuchi.ui.screens.ConductorScreen
import xyz.oleolegka.gachimuchi.ui.screens.DayEntriesScreen
import xyz.oleolegka.gachimuchi.ui.screens.FormDetailScreen
import xyz.oleolegka.gachimuchi.ui.screens.LogScreen
import xyz.oleolegka.gachimuchi.ui.screens.OverviewScreen
import xyz.oleolegka.gachimuchi.ui.screens.ProgramEditorScreen
import xyz.oleolegka.gachimuchi.ui.screens.SettingsScreen
import xyz.oleolegka.gachimuchi.ui.screens.TimerScreen
import xyz.oleolegka.gachimuchi.ui.screens.TodayScreen
import xyz.oleolegka.gachimuchi.ui.screens.WorkoutLogActions
import xyz.oleolegka.gachimuchi.ui.screens.WorkoutLogScreen
import xyz.oleolegka.gachimuchi.ui.screens.WorkoutScreen
import java.time.LocalDate

/** The bottom-bar icon of each tab. The tabs themselves are plain data — see Navigation.kt. */
private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.TODAY -> Icons.Filled.Star
        Tab.OVERVIEW -> Icons.AutoMirrored.Filled.List
        Tab.CALENDAR -> Icons.Filled.DateRange
        Tab.TIMER -> Icons.Filled.PlayArrow
        Tab.SETTINGS -> Icons.Filled.Settings
    }

/**
 * Five tabs in the bottom bar (§12-C: Today is a tab of its own), plus the logging screen,
 * the workout screen, the form detail screen and the program editor on top of them.
 *
 * Navigation is plain state, without navigation-compose. The four screens above the tabs
 * are not routes but MODES: each takes over the whole window, has nothing to navigate to,
 * and leaving it is a single action. Which one is in front is decided by the `when` below,
 * and where BACK leads is decided by [backStep] over the same flags, in the same order —
 * see ui/Navigation.kt for why that is a function rather than a stack.
 *
 * ── Where logging is entered from ───────────────────────────────────────────────────
 * From a CARD ON A DAY, and from nowhere else. There used to be a floating "Log a set"
 * button on Today, which asked the user to press it and then work out which exercise it had
 * decided the set was about. Now the thing tapped says what it is: a planned session starts
 * a workout, a running workout continues, "Add" offers a workout or a single entry. The
 * lambdas behind those taps are [DayActions], built here and handed to the two screens that
 * draw day cards (Today and the calendar) — both are direct children of this composable, so
 * they are ordinary parameters rather than a composition local.
 *
 * The consequence worth naming: the logging screen is now always entered FOR A DAY, and
 * usually for a workout, so it is told which day it is writing under instead of assuming
 * today. That is what makes typing up last Tuesday possible at all (§13.6), and it is what
 * stopped a set logged into a backdated workout being filed under today by the calendar
 * while the workout showed it — see [loggingDay].
 *
 * The timer does NOT come through here. A finished run never opens the logging screen: it
 * raises [RunLogDialog] with the sets, the exercise and the day already worked out, and
 * confirming writes them straight through the repository. Routing it through the entry card
 * would mean typing in four sets the app has just counted.
 */
@Composable
fun GachiApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeExerciseId by viewModel.activeExerciseId.collectAsStateWithLifecycle()
    val timerRun by viewModel.timerRun.collectAsStateWithLifecycle()
    val timerSettings by viewModel.timerSettings.collectAsStateWithLifecycle()
    val timerEnabled by viewModel.timerEnabled.collectAsStateWithLifecycle()
    val speech by viewModel.speechStatus.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val runOutcome by viewModel.runOutcome.collectAsStateWithLifecycle()
    val logReceipt by viewModel.logReceipt.collectAsStateWithLifecycle()
    // the parallel rests, for the bars under the cards inside a workout. The list changes
    // when a rest starts or is cleared, not on every tick — the countdown is drawn from the
    // clock, so nothing here recomposes four times a second (see rememberTickingNow)
    val restFloors by viewModel.restFloors.collectAsStateWithLifecycle()
    // the plate answered on the way into the running set, for the offer at the end of it
    val entryAddedKg by viewModel.entryAddedKg.collectAsStateWithLifecycle()
    // what matured while a protocol-led set had the rests muted. Already spoken by the time
    // it gets here; the screen is where it gets written down
    val floorSummary by viewModel.floorSummary.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(HomeTab) }
    /*
     * WHICH DAY is being logged, or null for "not logging". A boolean used to be enough,
     * because the entry card always wrote today; it is not enough now that a workout can be
     * dated to a day already gone, and the day it writes under has to travel with it.
     */
    var loggingDate by rememberSaveable { mutableStateOf<String?>(null) }
    /** The workout the entry card is writing into, or null for an entry on its own. */
    var loggingWorkoutId by rememberSaveable { mutableStateOf<Long?>(null) }
    var viewingWorkoutId by rememberSaveable { mutableStateOf<Long?>(null) }
    /*
     * Whether the conductor has the screen — a BOOLEAN and not "which exercise", because the
     * run itself already knows which exercise it belongs to (`RunSnapshot.exerciseId`) and two
     * answers to that would be one too many. This says only whether the user is looking at it,
     * which is a question about the screen and nothing else: leaving turns it off and the set
     * carries on regardless (§13.3, step 10).
     */
    var conductorOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    // the form detail screen is a MODE over the overview, like the logging screen: it has
    // exactly one way out and nothing to navigate to from inside it
    var detailExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
    /*
     * WHICH exercise and WHICH day the breakdown is showing. Two states rather than one pair,
     * because rememberSaveable stores what the Bundle can carry and a pair of two nullables
     * would need a Saver written for it — for a value whose halves are always set and cleared
     * together anyway.
     */
    var entriesExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var entriesDate by rememberSaveable { mutableStateOf<String?>(null) }
    val today by viewModel.today.collectAsStateWithLifecycle()
    val iso = today.toString()

    /*
     * The workout in progress, folded out of the journal rather than remembered as state —
     * the same rule the rest of the model follows, so it cannot drift away from the events
     * it is derived from. It decides which card says "Continue" and whether the workout
     * screen offers a way back into the entry card.
     */
    val runningWorkoutId = remember(state.events) { openWorkoutRow(state.events)?.id }

    /*
     * The offered rest length is derived from the whole journal, so it is computed once
     * per change of the things it depends on rather than on every recomposition — the
     * countdown recomposes several times a second while running.
     */
    val restSec = remember(state.events, activeExerciseId, timerSettings) {
        viewModel.restSecFor(activeExerciseId)
    }
    val restSource = remember(state.events, activeExerciseId, timerSettings) {
        viewModel.restSourceFor(activeExerciseId)
    }

    val timerState = TimerUiState(
        enabled = timerEnabled,
        run = timerRun,
        settings = timerSettings,
        speechAvailable = speech == SpeechStatus.READY,
        restSec = restSec,
        restSource = restSource,
    )
    val timerActions = TimerActions(
        enable = viewModel::enableTimer,
        pause = viewModel::pauseTimer,
        resume = viewModel::resumeTimer,
        skip = viewModel::skipStep,
        previous = viewModel::previousStep,
        nudge = viewModel::nudgeTimer,
        stop = viewModel::stopTimer,
    )
    // the permission conversation, hoisted once so both the tab and the logging screen use
    // the same one and the dialog cannot appear twice
    val enableTimer = rememberTimerEnabler(onEnabled = viewModel::enableTimer)

    // the hold exercises, which are the ones a program can be logged as; computed once per
    // change of the catalog (or the library, since a resolved ExerciseRef.protocol now comes
    // from it) rather than on every recomposition of a running countdown
    val holdExercises = remember(state.exercises, state.programsById) {
        state.exercises.map(state::refOf).filter { it.form == ExerciseForm.HOLD }
    }

    // the headings programs are already filed under, offered by the editor so the same one
    // is not spelled two ways; hoisted next to the catalog above for the same reason
    val programCategories = remember(programs) { knownCategories(programs) }

    /*
     * Every program id some exercise's protocol currently IS — the UI-side mirror of
     * ProgramRepository.isReferenced, computed here from state already loaded rather than by
     * a second query, because both this screen's lock and TimerScreen's freeze badge need the
     * same answer on every recomposition of a live catalog. The enforcement itself lives in
     * the repository (see save's own KDoc); this is only what decides which controls to show.
     */
    val referencedProgramIds = remember(state.exercises) {
        state.exercises.mapNotNull { it.protocolProgramId }.toSet()
    }

    /*
     * Opening the entry card. Kept current by rememberUpdatedState so the lambdas handed to
     * the day screens never have to be rebuilt: handing out fresh ones per recomposition
     * would recompose both screens several times a second while a countdown runs.
     */
    val openLoggingNow by rememberUpdatedState<(String, Long?) -> Unit> { day, workoutId ->
        // open on something usable rather than on "no exercise chosen"; the picker is still
        // one tap away on the card itself
        if (activeExerciseId == null) {
            viewModel.selectExercise(
                exerciseToLogNext(state.events, day, state.exercises.map { it.id })
            )
        }
        loggingDate = day
        loggingWorkoutId = workoutId
    }
    /*
     * Starting a workout, from a plan or off-plan. When it comes from a plan its exercises
     * are already in it by the time the screen opens — the copy happens inside
     * [MainViewModel.startWorkout], between the start event and this navigation, so there is
     * no frame on which the workout looks empty.
     */
    val startWorkoutNow by rememberUpdatedState<(LocalDate, Long?, String?) -> Unit> {
        day, slotId, name ->
        viewModel.startWorkout(day, slotId, name) { id ->
            openLoggingNow(day.toString(), id)
        }
    }

    // the day comes from the WORKOUT, never from any screen's idea of today (see [loggingDay])
    val continueWorkoutNow by rememberUpdatedState<(Long) -> Unit> { id ->
        openLoggingNow(loggingDay(buildWorkout(state.events, id), iso), id)
    }

    val dayActions = remember {
        DayActions(
            // a workout started from a plan takes the plan's name as its snapshot, so it is
            // not asked for one — see ActivityRepository.startWorkout
            startFromPlan = { slotId, day -> startWorkoutNow(day, slotId, null) },
            startWorkout = { day, name -> startWorkoutNow(day, null, name) },
            logSingleEntry = { day -> openLoggingNow(day.toString(), null) },
            continueWorkout = { id -> continueWorkoutNow(id) },
            openWorkout = { id -> viewingWorkoutId = id },
            openExercise = { id, day ->
                entriesExerciseId = id
                entriesDate = day.toString()
            },
            deleteWorkout = viewModel::deleteWorkout,
            renameWorkout = viewModel::renameWorkout,
        )
    }

    /*
     * Back, for the whole app, decided in one place.
     *
     * Disabled — not absent — when there is nothing to go back to: a disabled handler lets
     * the gesture fall through to the system, which backgrounds the app. Handling it and
     * doing nothing would trap the user inside.
     */
    /*
     * A set that ends while nobody is looking takes its screen down with it. Without this the
     * flag would survive the run and the next start would find the conductor already "open",
     * which is a screen showing a run that is not there.
     */
    val runEnded = timerRun == null
    LaunchedEffect(runEnded) { if (runEnded) conductorOpen = false }

    val step = backStep(
        editingProgram = editing != null,
        showingFormDetail = detailExerciseId != null,
        logging = loggingDate != null,
        showingWorkout = viewingWorkoutId != null,
        tab = tab,
        conducting = conductorOpen && timerRun != null,
        showingDayEntries = entriesExerciseId != null && entriesDate != null,
    )
    BackHandler(enabled = step != BackStep.LeaveApp) {
        when (step) {
            BackStep.CloseEditor -> editing = null
            BackStep.CloseFormDetail -> detailExerciseId = null
            // the set keeps running, keeps speaking, and the card leads back to it
            BackStep.CloseConductor -> conductorOpen = false
            BackStep.CloseLogging -> {
                loggingDate = null
                loggingWorkoutId = null
            }

            BackStep.CloseWorkout -> viewingWorkoutId = null
            BackStep.CloseDayEntries -> {
                entriesExerciseId = null
                entriesDate = null
            }

            is BackStep.SwitchTab -> tab = step.tab
            BackStep.LeaveApp -> Unit // unreachable: the handler is disabled in that state
        }
    }

    /*
     * The offer to write a finished run into the journal is raised HERE, above the tabs and
     * above the logging screen, rather than on the timer tab: a run ends while the phone is
     * in a pocket and the screen it comes back to is whichever one was open. It is a dialog,
     * so it draws over whatever that turns out to be.
     *
     * It waits for the journal to have loaded — before the first emission [UiState.loading]
     * is true — because on that frame the catalog is empty, and an offer raised against an
     * empty catalog would say "there is nothing to file this under" about a phone that has
     * plenty. It does NOT wait for the catalog to be non-empty: a phone with no hold
     * exercise on it is a real state, and the offer says so rather than never appearing.
     */
    runOutcome?.takeIf { !state.loading }?.let { outcome ->
        RunLogDialog(
            outcome = outcome,
            exercise = state.refById(outcome.exerciseId),
            candidates = holdExercises,
            /*
             * THE ANSWER GIVEN ON THE WAY IN WINS, for the exercise it was given about. That
             * is the whole payoff of asking before the set (§13.5): the plate that was
             * actually hung is what the offer arrives filled in with, rather than the one
             * hung the previous time. For anything else — a different exercise picked in the
             * offer, or a run nobody was asked about — the last logged set is still the best
             * guess available.
             */
            lastAddedKg = { id ->
                entryAddedKg?.takeIf { id == outcome.exerciseId }
                    ?: lastHoldSet(state.events, state.linkOf(id))?.addedKg
            },
            nowWallMs = System.currentTimeMillis(),
            onLog = viewModel::logRunSets,
            onDismiss = viewModel::dismissRunOutcome,
        )
    }

    /*
     * And the answer to "did that actually go in?".
     *
     * Writing used to be silent: the dialog closed and the user was left to go and look. Two
     * sessions in a row were run in the belief that nothing had been recorded, which is the
     * expensive kind of doubt — it makes the feature worse than useless, because the sets
     * get typed in again. So a write says what it wrote, and offers to take it back while
     * the memory of pressing the button is still fresh.
     *
     * Like the offer above it, it is raised here rather than inside any of the branches
     * below: it is a dialog in its own window, so it draws over whichever screen is in
     * front, and it takes the back gesture itself before [backStep] is ever consulted —
     * which is why neither dialog appears in that function (see ui/Navigation.kt).
     */
    logReceipt?.let { receipt ->
        LogReceiptDialog(
            receipt = receipt,
            onUndo = viewModel::undoRunSets,
            onDismiss = viewModel::dismissReceipt,
        )
    }

    // held in locals so the branches below smart-cast, and so the order of the branches is
    // literally the order of [backStep]
    val editorTarget = editing
    val detailId = detailExerciseId
    val loggingOn = loggingDate
    val loggingWorkout = loggingWorkoutId
    val viewingWorkout = viewingWorkoutId
    val entriesOf = entriesExerciseId
    val entriesOn = entriesDate

    when {
        editorTarget != null -> ProgramEditorScreen(
            initial = editorTarget.program,
            candidates = holdExercises,
            categories = programCategories,
            locked = editorTarget.program?.id?.let { it != 0L && it in referencedProgramIds } == true,
            onSave = {
                viewModel.saveProgram(it)
                editing = null
            },
            onClose = { editing = null },
        )

        detailId != null -> FormDetailScreen(
            state = state,
            exerciseId = detailId,
            today = today,
            onClose = { detailExerciseId = null },
        )

        /*
         * A protocol-led set, with the screen and the speaker (§13.2). Drawn OVER the logging
         * screen rather than replacing it: leaving is one gesture and lands straight back on
         * the card list, because during a superset that trip is made repeatedly and in a
         * hurry. The condition carries `timerRun != null` as well as the flag so that a run
         * ending anywhere — its own end, the Stop button, a process rebuild — puts the card
         * list back without anything having to notice and clear a flag first.
         */
        conductorOpen && timerRun != null -> ConductorScreen(
            exerciseName = state.exerciseById(timerRun?.exerciseId)?.name,
            state = timerState,
            actions = timerActions,
            onLeave = { conductorOpen = false },
        )

        /*
         * TWO WAYS TO RECORD, and which one appears is decided by whether there is a workout
         * behind it.
         *
         * Inside a WORKOUT it is [WorkoutLogScreen]: a card per exercise, each with its own
         * rest counting under it, which is the shape §13.2 settled on and the reason the whole
         * model was rebuilt. On its OWN — the stretching in front of the television — it is
         * still [LogScreen], because there is no workout there to draw the cards of and the
         * old screen answers that case exactly.
         */
        loggingOn != null && loggingWorkout != null -> {
            val workoutBeingLogged = loggingWorkout
            /*
             * Built once per workout rather than per recomposition: the screen holds a rest
             * bar that redraws four times a second, and fresh lambdas on every frame would
             * make every card in it recompose along with the bar.
             */
            val workoutActions = remember(workoutBeingLogged) {
                WorkoutLogActions(
                    addExercise = { exerciseId, restSec, side ->
                        viewModel.addExerciseToWorkout(workoutBeingLogged, exerciseId, restSec, side)
                    },
                    createExercise = { name, form, work, rest, then ->
                        viewModel.createExercise(name, form, work, rest, then)
                    },
                    /*
                     * INTO THIS WORKOUT, named rather than looked up. The screen is drawing a
                     * particular workout and that is the one a set typed on it belongs to —
                     * which is not the same as "the open one" the moment a FINISHED workout is
                     * opened to add the set forgotten in the changing room (§13).
                     */
                    addSet = { form ->
                        viewModel.addSet(form, intoWorkoutId = workoutBeingLogged)
                    },
                    undoSet = viewModel::undoSet,
                    // rows and all: the "added" events and every set of the exercise, so a
                    // block taken out of the workout does not come straight back on its sets
                    removeExercise = viewModel::deleteEntries,
                    // the whole order, one row per drop -- see TYPE_WORKOUT_ORDER_SET
                    reorderExercises = { order ->
                        viewModel.setWorkoutExerciseOrder(workoutBeingLogged, order)
                    },
                    finish = { viewModel.finishWorkout(workoutBeingLogged) },
                    startProtocolSet = { exercise, addedKg ->
                        viewModel.startProgramForExercise(exercise, addedKg)
                        conductorOpen = true
                    },
                    openConductor = { conductorOpen = true },
                    close = {
                        loggingDate = null
                        loggingWorkoutId = null
                    },
                )
            }
            WorkoutLogScreen(
                state = state,
                workoutId = workoutBeingLogged,
                settings = timerSettings,
                floors = restFloors,
                actions = workoutActions,
                liveExerciseId = timerRun?.exerciseId,
                readySummary = floorSummary,
                onDismissSummary = viewModel::dismissFloorSummary,
            )
        }

        loggingOn != null -> {
            val day = remember(loggingOn) {
                runCatching { LocalDate.parse(loggingOn) }.getOrDefault(today)
            }
            LogScreen(
                state = state,
                day = day,
                activeExerciseId = activeExerciseId,
                timer = timerState,
                timerActions = timerActions,
                onEnableTimer = enableTimer,
                onStartExerciseProgram = { viewModel.startProgramForExercise(it) },
                onSelectExercise = viewModel::selectExercise,
                onCreateExercise = { name, form, work, rest ->
                    viewModel.createExercise(name, form, work, rest)
                },
                // an entry logged with no workout behind it must not be swallowed by the
                // workout that happens to be open — see ActivityRepository.record
                onAddSet = { form -> viewModel.addSet(form, attachToWorkout = false) },
                onUndoSet = viewModel::undoSet,
                onClose = {
                    loggingDate = null
                    loggingWorkoutId = null
                },
            )
        }

        viewingWorkout != null -> WorkoutScreen(
            state = state,
            workoutId = viewingWorkout,
            // "continue" only where continuing means something: the workout in progress
            onContinue = if (viewingWorkout == runningWorkoutId) {
                { continueWorkoutNow(viewingWorkout) }
            } else {
                null
            },
            onClose = { viewingWorkoutId = null },
            // both append rather than rewrite; domain/Amendments.kt folds them for every reader
            onAmendEntry = viewModel::amendEntry,
            onDeleteEntry = viewModel::deleteEntry,
        )

        /*
         * The breakdown of one exercise on one day, opened from a single-entry card. It sits
         * below the workout screen for no reason other than that both are opened from a day
         * card and neither leads to the other; what matters is that it is BELOW the form
         * detail screen, which is now reached from inside it.
         */
        entriesOf != null && entriesOn != null -> DayEntriesScreen(
            state = state,
            exerciseId = entriesOf,
            date = entriesOn,
            onOpenHistory = { detailExerciseId = it },
            onClose = {
                entriesExerciseId = null
                entriesDate = null
            },
            // both append rather than rewrite; domain/Amendments.kt folds them for every reader
            onAmendEntry = viewModel::amendEntry,
            onDeleteEntry = viewModel::deleteEntry,
        )

        else -> Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.title) },
                            label = { Text(t.title) },
                        )
                    }
                }
            },
        ) { padding ->
            // no floating button on any tab any more: the primary action is on the cards
            val inner = Modifier.padding(padding)
            when (tab) {
                Tab.TODAY -> TodayScreen(state, today, dayActions, inner)
                Tab.OVERVIEW ->
                    OverviewScreen(state, today, inner, onOpenForm = { detailExerciseId = it })

                Tab.CALENDAR -> CalendarScreen(
                    state = state,
                    today = today,
                    dayActions = dayActions,
                    modifier = inner,
                    onSaveSlot = viewModel::saveSlot,
                    onDeleteSlot = viewModel::deleteSlot,
                    onCreateExercise = { name, form, work, rest, then ->
                        viewModel.createExercise(name, form, work, rest, then)
                    },
                )

                Tab.TIMER -> {
                    // the settings row about spoken steps has to know the answer before
                    // it is touched, so the engine is looked for when the screen appears
                    LaunchedEffect(Unit) { viewModel.prepareSpeech() }
                    TimerScreen(
                        state = timerState,
                        actions = timerActions,
                        programs = programs,
                        exerciseNames = remember(state.exercises) {
                            state.exercises.associate { it.id to it.name }
                        },
                        onRunProgram = viewModel::runProgram,
                        onEditProgram = { editing = EditorTarget(it) },
                        onDeleteProgram = viewModel::deleteProgram,
                        onToggleHiddenProgram = { program ->
                            viewModel.setProgramHidden(program.id, !program.hidden)
                        },
                        onImportPrograms = viewModel::importPrograms,
                        onSettings = viewModel::updateTimerSettings,
                        onEnable = enableTimer,
                        onDisable = viewModel::disableTimer,
                        modifier = inner,
                    )
                }

                Tab.SETTINGS -> SettingsScreen(modifier = inner)
            }
        }
    }
}

/**
 * Wrapper so that "edit nothing yet" (a new program) is distinguishable from "not editing"
 * — both would otherwise be null, and the editor would never open for a new program.
 */
private data class EditorTarget(val program: WorkoutProgram?)
