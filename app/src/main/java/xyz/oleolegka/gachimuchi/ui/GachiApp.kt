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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.buildSession
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

/**
 * Four tabs in the bottom bar (§12-C: Today is a tab of its own), plus the logging screen
 * and the program editor on top of them.
 *
 * Navigation is still plain state, without navigation-compose. The logging screen and the
 * editor are not routes but MODES: each takes over the whole window, has nothing to
 * navigate to, and leaving it is a single action. A back stack library would buy nothing
 * here and would cost a dependency plus saved-state plumbing.
 *
 * ── Where logging is entered from, and why it is a button and not a tab ─────────────
 * The log button is shown on EVERY tab, not just Today. Recording what was just done is
 * the thing this app is for, and until now it could only be reached from one of five
 * screens: stand on Overview, Calendar, Timer or Settings and there was no way in at all.
 *
 * It is a floating button rather than a sixth destination in the bottom bar for two
 * reasons. The bar already carries five, which is the ceiling Material sets before labels
 * start being clipped, and "Overview", "Calendar" and "Settings" are exactly the labels
 * that would go first on a narrow phone. And the logging screen cannot become a tab
 * without losing the one thing it is built around: its entry card is pinned to the bottom
 * of the window so the buttons tapped between sets stay inside the arc of a thumb, and a
 * navigation bar underneath it would push that card up and out of reach. Making the bar
 * item open the mode instead of showing a tab would work, but it would be a destination
 * that never appears selected — a lie told by the control that exists to say where you
 * are.
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
            ExtendedFloatingActionButton(
                onClick = {
                    // open the entry card on something usable rather than on "no exercise
                    // chosen"; the picker is still one tap away on the card itself
                    if (activeExerciseId == null) {
                        viewModel.selectExercise(
                            exerciseToLogNext(state.events, iso, state.exercises.map { it.id })
                        )
                    }
                    logging = true
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(logButtonLabel(session.isEmpty)) },
                /*
                 * The colours are stated rather than defaulted. A floating button takes its
                 * fill from `primaryContainer`, a role this theme never defines (see
                 * ui/theme/Theme.kt), so it fell back to the Material baseline — a pale
                 * lavender pill on an off-white plane, off-palette and barely separated
                 * from the background it floats over. The accent is the colour the rest of
                 * the app already uses to mean "this is the thing to press".
                 */
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        /*
         * Room under every tab for the log button to float over. It is reserved here, once,
         * rather than in each screen's own content padding: the button belongs to this
         * scaffold, and a screen that forgot the allowance would hide its own last row
         * behind it.
         */
        val inner = Modifier.padding(padding).padding(bottom = LogButtonClearance)
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

/** Height an extended floating button occupies, plus its margin — see [GachiApp]. */
private val LogButtonClearance = 72.dp

/**
 * Wrapper so that "edit nothing yet" (a new program) is distinguishable from "not editing"
 * — both would otherwise be null, and the editor would never open for a new program.
 */
private data class EditorTarget(val program: WorkoutProgram?)
