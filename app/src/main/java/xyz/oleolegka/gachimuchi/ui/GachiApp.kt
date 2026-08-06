package xyz.oleolegka.gachimuchi.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.timer.SpeechStatus
import xyz.oleolegka.gachimuchi.ui.components.RunLogDialog
import xyz.oleolegka.gachimuchi.ui.components.TimerActions
import xyz.oleolegka.gachimuchi.ui.components.TimerUiState
import xyz.oleolegka.gachimuchi.ui.components.rememberTimerEnabler
import xyz.oleolegka.gachimuchi.ui.screens.CalendarScreen
import xyz.oleolegka.gachimuchi.ui.screens.LogScreen
import xyz.oleolegka.gachimuchi.ui.screens.OverviewScreen
import xyz.oleolegka.gachimuchi.ui.screens.ProgramEditorScreen
import xyz.oleolegka.gachimuchi.ui.screens.TimerScreen
import xyz.oleolegka.gachimuchi.ui.screens.TodayScreen

/**
 * Four tabs in the bottom bar (§12-C: Today is a tab of its own), plus the logging screen
 * and the program editor on top of them.
 *
 * Navigation is still plain state, without navigation-compose. The logging screen and the
 * editor are not routes but MODES: each takes over the whole window, has nothing to
 * navigate to, and leaving it is a single action. A back stack library would buy nothing
 * here and would cost a dependency plus saved-state plumbing.
 */
private enum class Tab(val title: String, val icon: ImageVector) {
    TODAY("Today", Icons.Filled.Star),
    OVERVIEW("Overview", Icons.AutoMirrored.Filled.List),
    CALENDAR("Calendar", Icons.Filled.DateRange),
    TIMER("Timer", Icons.Filled.PlayArrow),
}

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

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var logging by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    val today = remember { viewModel.today }
    val iso = today.toString()

    // "start" or "continue" is decided by the journal, not by a flag: a session is simply
    // everything recorded today, so a crash or a closed app never loses one
    val session = remember(state.events, iso) { buildSession(state.events, iso) }

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

    editing?.let { target ->
        ProgramEditorScreen(
            initial = target.program,
            onSave = {
                viewModel.saveProgram(it)
                editing = null
            },
            onClose = { editing = null },
        )
        return
    }

    if (logging) {
        LogScreen(
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
        return
    }

    Scaffold(
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
            if (tab == Tab.TODAY) {
                ExtendedFloatingActionButton(
                    onClick = {
                        // point the entry card at the exercise the workout left off on
                        if (activeExerciseId == null) {
                            viewModel.selectExercise(session.groups.lastOrNull()?.exerciseId)
                        }
                        logging = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(if (session.isEmpty) "Start workout" else "Continue workout") },
                )
            }
        },
    ) { padding ->
        val inner = Modifier.padding(padding)
        when (tab) {
            Tab.TODAY -> TodayScreen(state, today, inner, onReseed = viewModel::reseed)
            Tab.OVERVIEW -> OverviewScreen(state, today, inner)
            Tab.CALENDAR -> CalendarScreen(state, today, inner)
            Tab.TIMER -> {
                // the settings row about spoken steps has to know the answer before it is
                // touched, so the engine is looked for when the screen appears
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
        }
    }
}

/**
 * Wrapper so that "edit nothing yet" (a new program) is distinguishable from "not editing"
 * — both would otherwise be null, and the editor would never open for a new program.
 */
private data class EditorTarget(val program: WorkoutProgram?)
