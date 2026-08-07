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
import xyz.oleolegka.gachimuchi.data.toRef
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
import xyz.oleolegka.gachimuchi.ui.screens.FormDetailScreen
import xyz.oleolegka.gachimuchi.ui.screens.LogScreen
import xyz.oleolegka.gachimuchi.ui.screens.OverviewScreen
import xyz.oleolegka.gachimuchi.ui.screens.ProgramEditorScreen
import xyz.oleolegka.gachimuchi.ui.screens.SettingsScreen
import xyz.oleolegka.gachimuchi.ui.screens.TimerScreen
import xyz.oleolegka.gachimuchi.ui.screens.TodayScreen
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
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    // the form detail screen is a MODE over the overview, like the logging screen: it has
    // exactly one way out and nothing to navigate to from inside it
    var detailExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
    val today by viewModel.today.collectAsStateWithLifecycle()
    val iso = today.toString()

    /*
     * The workout in progress, folded out of the journal rather than remembered as state —
     * the same rule the rest of the model follows, so it cannot drift away from the events
     * it is derived from. It decides which card says "Continue" and whether the workout
     * screen offers a way back into the entry card.
     */
    val runningWorkoutId = remember(state.events, iso) { openWorkoutRow(state.events, iso)?.id }

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
    // change of the catalog rather than on every recomposition of a running countdown
    val holdExercises = remember(state.exercises) {
        state.exercises.map { it.toRef() }.filter { it.form == ExerciseForm.HOLD }
    }

    // the headings programs are already filed under, offered by the editor so the same one
    // is not spelled two ways; hoisted next to the catalog above for the same reason
    val programCategories = remember(programs) { knownCategories(programs) }

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
    val startWorkoutNow by rememberUpdatedState<(LocalDate, Long?) -> Unit> { day, slotId ->
        /*
         * TODO(§13.7): copy the slot's exercises into the workout. A workout started from a
         * plan currently arrives EMPTY and the user adds exercises as they go, exactly as an
         * off-plan one does.
         *
         * Everything needed is now in place — `plannedExercises(state.slots, slotId)` reads
         * the list and `ActivityRepository.addExerciseToWorkout` writes each one in — so this
         * is a deliberate omission rather than a missing dependency: it is one
         * `addExerciseToWorkout` per planned exercise, run between the start event and the
         * navigation below, and it needs its own verification (which rest wins when the slot
         * names one and the catalog remembers another is still open, §13.8).
         *
         * It is a COPY rather than a reference whenever it lands: the plan is editable and
         * the facts are not, so rewriting the slot next month must not rewrite the
         * composition of a workout already done.
         */
        viewModel.startWorkout(day, slotId) { id ->
            openLoggingNow(day.toString(), id)
        }
    }

    // the day comes from the WORKOUT, never from any screen's idea of today (see [loggingDay])
    val continueWorkoutNow by rememberUpdatedState<(Long) -> Unit> { id ->
        openLoggingNow(loggingDay(buildWorkout(state.events, id), iso), id)
    }

    val dayActions = remember {
        DayActions(
            startFromPlan = { slotId, day -> startWorkoutNow(day, slotId) },
            startWorkout = { day -> startWorkoutNow(day, null) },
            logSingleEntry = { day -> openLoggingNow(day.toString(), null) },
            continueWorkout = { id -> continueWorkoutNow(id) },
            openWorkout = { id -> viewingWorkoutId = id },
            openExercise = { id -> detailExerciseId = id },
        )
    }

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
        logging = loggingDate != null,
        showingWorkout = viewingWorkoutId != null,
        tab = tab,
    )
    BackHandler(enabled = step != BackStep.LeaveApp) {
        when (step) {
            BackStep.CloseEditor -> editing = null
            BackStep.CloseFormDetail -> detailExerciseId = null
            BackStep.CloseLogging -> {
                loggingDate = null
                loggingWorkoutId = null
            }

            BackStep.CloseWorkout -> viewingWorkoutId = null
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
            lastAddedKg = { id -> lastHoldSet(state.events, state.linkOf(id))?.addedKg },
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
    val viewingWorkout = viewingWorkoutId

    when {
        editorTarget != null -> ProgramEditorScreen(
            initial = editorTarget.program,
            candidates = holdExercises,
            categories = programCategories,
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

        loggingOn != null -> {
            val day = remember(loggingOn) {
                runCatching { LocalDate.parse(loggingOn) }.getOrDefault(today)
            }
            /*
             * INTERMEDIATE STATE, said out loud. This screen is the old one, wired to the
             * new frame: it is told which day it writes under (which is what fixes the
             * backdating bug) but it still shows the whole DAY's tape rather than the
             * workout's, so on a day with two workouts it shows both. Rebuilding it around
             * per-exercise cards with parallel rest bars is the next step (§13.2) and is not
             * something to half-do here.
             */
            LogScreen(
                state = state,
                day = day,
                activeExerciseId = activeExerciseId,
                timer = timerState,
                timerActions = timerActions,
                onEnableTimer = enableTimer,
                onStartExerciseProgram = { viewModel.startProgramForExercise(it) },
                onSelectExercise = viewModel::selectExercise,
                onCreateExercise = viewModel::createExercise,
                // an entry logged with no workout behind it must not be swallowed by the
                // workout that happens to be open — see ActivityRepository.record
                onAddSet = { form -> viewModel.addSet(form, attachToWorkout = loggingWorkoutId != null) },
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
