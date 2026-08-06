package xyz.oleolegka.gachimuchi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.exerciseToLogNext
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.timer.SpeechStatus
import xyz.oleolegka.gachimuchi.ui.components.RunLogDialog
import xyz.oleolegka.gachimuchi.ui.components.TimerActions
import xyz.oleolegka.gachimuchi.ui.components.TimerUiState
import xyz.oleolegka.gachimuchi.ui.components.rememberTimerEnabler
import xyz.oleolegka.gachimuchi.ui.screens.CalendarScreen
import xyz.oleolegka.gachimuchi.ui.screens.FormDetailScreen
import xyz.oleolegka.gachimuchi.ui.screens.LogScreen
import xyz.oleolegka.gachimuchi.ui.screens.OverviewScreen
import xyz.oleolegka.gachimuchi.ui.screens.ProgramEditorScreen
import xyz.oleolegka.gachimuchi.ui.screens.SettingsScreen
import xyz.oleolegka.gachimuchi.ui.screens.TimerScreen
import xyz.oleolegka.gachimuchi.ui.screens.TodayScreen

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
 * the form detail screen and the program editor on top of them.
 *
 * Navigation is plain state, without navigation-compose. The three screens above the tabs
 * are not routes but MODES: each takes over the whole window, has nothing to navigate to,
 * and leaving it is a single action. Which one is in front is decided by the `when` below,
 * and where BACK leads is decided by [backStep] over the same flags, in the same order —
 * see ui/Navigation.kt for why that is a function rather than a stack.
 *
 * ── Where logging is entered from ───────────────────────────────────────────────────
 * The button lives on Today and only on Today. It was briefly on all five tabs, on the
 * argument that recording a set is what the app is for; that turned the app's primary
 * action into furniture that followed the user around, present on Settings and on the
 * yearly heatmap where there is nothing to record. Today is the screen about the workout
 * happening now, which is the screen the button is an answer to.
 *
 * The other ways in are CONTEXTUAL, offered by screens that know what would be logged: a
 * planned session tapped on the calendar, a finished run on the timer. They all go through
 * [LocalOpenLogging], published here, so a screen needs no new parameter to offer one.
 *
 * It is a floating button rather than a sixth destination in the bottom bar because the
 * bar already carries five, which is the ceiling Material sets before labels start being
 * clipped. And the logging screen cannot become a tab without losing the one thing it is
 * built around: its entry card is pinned to the bottom of the window so the buttons tapped
 * between sets stay inside the arc of a thumb, and a navigation bar underneath it would
 * push that card up and out of reach.
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

    var tab by rememberSaveable { mutableStateOf(HomeTab) }
    var logging by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    // the form detail screen is a MODE over the overview, like the logging screen: it has
    // exactly one way out and nothing to navigate to from inside it
    var detailExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
    val today = remember { viewModel.today }
    val iso = today.toString()

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
        startRest = { viewModel.startRest(activeExerciseId) },
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

    /*
     * The one way into the logging screen, for the button below and for any screen that
     * offers it in context (see [LocalOpenLogging]).
     *
     * The published object is remembered ONCE and forwards to a lambda kept current by
     * rememberUpdatedState. Handing out a fresh instance per recomposition would push a
     * new value through the composition local several times a second while the timer runs,
     * and every screen reading it would be recomposed for a callback that never changed.
     */
    val openLoggingNow by rememberUpdatedState<(Long?) -> Unit> { exerciseId ->
        when {
            // the tap said which exercise it was about — a planned session, a finished run
            exerciseId != null -> viewModel.selectExercise(exerciseId)
            // open the entry card on something usable rather than on "no exercise chosen";
            // the picker is still one tap away on the card itself
            activeExerciseId == null -> viewModel.selectExercise(
                exerciseToLogNext(state.events, iso, state.exercises.map { it.id })
            )
        }
        logging = true
    }
    val openLogging = remember { OpenLogging { openLoggingNow(it) } }

    /*
     * Back, for the whole app, decided in one place.
     *
     * Disabled — not absent — when there is nothing to go back to: a disabled handler lets
     * the gesture fall through to the system, which backgrounds the app. Handling it and
     * doing nothing would trap the user inside.
     */
    val step = backStep(
        editingProgram = editing != null,
        showingFormDetail = detailExerciseId != null,
        logging = logging,
        tab = tab,
    )
    BackHandler(enabled = step != BackStep.LeaveApp) {
        when (step) {
            BackStep.CloseEditor -> editing = null
            BackStep.CloseFormDetail -> detailExerciseId = null
            BackStep.CloseLogging -> logging = false
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
     * It waits for the catalog to arrive first: on the frame after the Activity is rebuilt
     * the exercise list is momentarily empty, and a dialog that cannot find its exercise
     * closes itself — which would throw the offer away before it was ever seen.
     */
    runOutcome?.takeIf { state.exercises.isNotEmpty() }?.let { outcome ->
        val exercise = state.refById(outcome.exerciseId)
        RunLogDialog(
            outcome = outcome,
            exercise = exercise,
            suggestedAddedKg = remember(outcome, state.events) {
                outcome.exerciseId?.let { lastHoldSet(state.events, it)?.addedKg }
            },
            onLog = { sets, addedKg ->
                if (exercise == null) viewModel.dismissRunOutcome()
                else viewModel.logRunSets(exercise, sets, addedKg)
            },
            onDismiss = viewModel::dismissRunOutcome,
        )
    }

    // held in locals so the branches below smart-cast, and so the order of the branches is
    // literally the order of [backStep]
    val editorTarget = editing
    val detailId = detailExerciseId

    CompositionLocalProvider(LocalOpenLogging provides openLogging) {
        when {
            editorTarget != null -> ProgramEditorScreen(
                initial = editorTarget.program,
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

            logging -> LogScreen(
                state = state,
                today = today,
                activeExerciseId = activeExerciseId,
                timer = timerState,
                timerActions = timerActions,
                onEnableTimer = enableTimer,
                onStartExerciseProgram = { viewModel.startProgramForExercise(it) },
                onSelectExercise = viewModel::selectExercise,
                onCreateExercise = viewModel::createExercise,
                onAddSet = viewModel::addSet,
                onUndoSet = viewModel::undoSet,
                onClose = { logging = false },
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
                floatingActionButton = {
                    if (tab == HomeTab) {
                        ExtendedFloatingActionButton(
                            onClick = { openLogging() },
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            text = { Text(LOG_BUTTON_LABEL) },
                            /*
                             * The colours are stated rather than defaulted. A floating
                             * button takes its fill from `primaryContainer`, which the
                             * theme now sets to the palest step of the blue ramp (see
                             * ui/theme/Theme.kt) — right for a selected chip, too quiet
                             * for the one button this app is built around. The accent is
                             * the colour the rest of the app already uses to mean "this is
                             * the thing to press".
                             */
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
            ) { padding ->
                /*
                 * Room for the log button to float over — on the tab that HAS one, and
                 * nowhere else. Reserving it on every tab left a blank strip under the last
                 * row of four screens with nothing floating above them.
                 */
                val inner = Modifier
                    .padding(padding)
                    .then(
                        if (tab == HomeTab) Modifier.padding(bottom = LogButtonClearance)
                        else Modifier
                    )
                when (tab) {
                    Tab.TODAY -> TodayScreen(state, today, inner, onReseed = viewModel::reseed)
                    Tab.OVERVIEW ->
                        OverviewScreen(state, today, inner, onOpenForm = { detailExerciseId = it })

                    Tab.CALENDAR -> CalendarScreen(
                        state = state,
                        today = today,
                        modifier = inner,
                        onSaveSlot = viewModel::saveSlot,
                        onDeleteSlot = viewModel::deleteSlot,
                    )

                    Tab.TIMER -> {
                        // the settings row about spoken steps has to know the answer before
                        // it is touched, so the engine is looked for when the screen appears
                        LaunchedEffect(Unit) { viewModel.prepareSpeech() }
                        TimerScreen(
                            state = timerState,
                            actions = timerActions,
                            programs = programs,
                            onRunProgram = viewModel::runProgram,
                            onEditProgram = { editing = EditorTarget(it) },
                            onDeleteProgram = viewModel::deleteProgram,
                            onImportPrograms = viewModel::importPrograms,
                            onSettings = viewModel::updateTimerSettings,
                            onEnable = enableTimer,
                            onDisable = viewModel::disableTimer,
                            modifier = inner,
                        )
                    }

                    Tab.SETTINGS -> SettingsScreen(inner)
                }
            }
        }
    }
}

/** Height an extended floating button occupies, plus its margin — see [GachiApp]. */
private val LogButtonClearance = 72.dp

/**
 * Wrapper so that "edit nothing yet" (a new program) is distinguishable from "not editing"
 * — both would otherwise be null, and the editor would never open for a new program.
 */
private data class EditorTarget(val program: WorkoutProgram?)
