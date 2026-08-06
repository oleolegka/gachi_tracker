package xyz.oleolegka.gachimuchi.ui

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
import xyz.oleolegka.gachimuchi.data.toRef
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.knownCategories
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.timer.SpeechStatus
import xyz.oleolegka.gachimuchi.ui.components.LogReceiptDialog
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
    SETTINGS("Settings", Icons.Filled.Settings),
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
    val logReceipt by viewModel.logReceipt.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var logging by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    // the form detail screen is a MODE over the overview, like the logging screen: it has
    // exactly one way out and nothing to navigate to from inside it
    var detailExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
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

    // the hold exercises, which are the ones a program can be logged as; computed once per
    // change of the catalog rather than on every recomposition of a running countdown
    val holdExercises = remember(state.exercises) {
        state.exercises.map { it.toRef() }.filter { it.form == ExerciseForm.HOLD }
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
            lastAddedKg = { id -> lastHoldSet(state.events, id)?.addedKg },
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
     */
    logReceipt?.let { receipt ->
        LogReceiptDialog(
            receipt = receipt,
            onUndo = viewModel::undoRunSets,
            onDismiss = viewModel::dismissReceipt,
        )
    }

    editing?.let { target ->
        ProgramEditorScreen(
            initial = target.program,
            candidates = holdExercises,
            categories = remember(programs) { knownCategories(programs) },
            onSave = {
                viewModel.saveProgram(it)
                editing = null
            },
            onClose = { editing = null },
        )
        return
    }

    detailExerciseId?.let { id ->
        FormDetailScreen(
            state = state,
            exerciseId = id,
            today = today,
            onClose = { detailExerciseId = null },
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
            Tab.OVERVIEW -> OverviewScreen(state, today, inner, onOpenForm = { detailExerciseId = it })
            Tab.CALENDAR -> CalendarScreen(
                state = state,
                today = today,
                modifier = inner,
                onSaveSlot = viewModel::saveSlot,
                onDeleteSlot = viewModel::deleteSlot,
            )
            Tab.TIMER -> {
                // the settings row about spoken steps has to know the answer before it is
                // touched, so the engine is looked for when the screen appears
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

/**
 * Wrapper so that "edit nothing yet" (a new program) is distinguishable from "not editing"
 * — both would otherwise be null, and the editor would never open for a new program.
 */
private data class EditorTarget(val program: WorkoutProgram?)
