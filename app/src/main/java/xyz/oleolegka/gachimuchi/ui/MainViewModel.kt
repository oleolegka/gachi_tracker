package xyz.oleolegka.gachimuchi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.AliasEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.seed.DemoSeed
import xyz.oleolegka.gachimuchi.data.toRef
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.programFromExercise
import xyz.oleolegka.gachimuchi.domain.resolveRestSec
import xyz.oleolegka.gachimuchi.domain.restProgram
import xyz.oleolegka.gachimuchi.domain.restSourceLabel
import xyz.oleolegka.gachimuchi.domain.startsRest
import xyz.oleolegka.gachimuchi.timer.SpeechStatus
import xyz.oleolegka.gachimuchi.timer.TimerController
import java.time.LocalDate

/**
 * Screen state: the whole journal, catalog, aliases and slot list, with the reducers
 * applied on the spot by the domain functions. Per-screen slicing lives in the screens
 * themselves — there is very little data (a personal diary), and an extra "view state per
 * screen" layer would only get in the way.
 */
data class UiState(
    val events: List<JournalEvent> = emptyList(),
    val exercises: List<ExerciseEntity> = emptyList(),
    val aliases: List<AliasEntity> = emptyList(),
    val slots: List<Slot> = emptyList(),
    val loading: Boolean = true,
) {
    fun exerciseById(id: Long?): ExerciseEntity? = id?.let { e -> exercises.firstOrNull { it.id == e } }

    fun formOf(id: Long?): ExerciseForm? =
        exerciseById(id)?.let { runCatching { ExerciseForm.fromCode(it.form) }.getOrNull() }

    /** The catalog row as the domain sees it — what the entry card builds its forms from. */
    fun refById(id: Long?): ExerciseRef? = exerciseById(id)?.toRef()

    /** Words that lead to an exercise: the picker searches by them as well as by name. */
    fun aliasesOf(exerciseId: Long): List<String> =
        aliases.filter { it.value == exerciseId && !it.blocked }.map { it.key }
}

class MainViewModel(
    private val repo: ActivityRepository,
    private val programRepo: ProgramRepository,
    private val timer: TimerController,
) : ViewModel() {

    private val seeding = MutableStateFlow(false)

    val state: StateFlow<UiState> =
        combine(repo.events, repo.exercises, repo.aliases, repo.slots, seeding) { events, exercises, aliases, slots, busy ->
            UiState(events, exercises, aliases, slots, loading = busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** One "today" for every screen: the calendar and the Today tab must not drift apart. */
    val today: LocalDate = LocalDate.now()

    // --- logging a workout ------------------------------------------------------------
    //
    // A session is not a stored entity (see domain/Session.kt): the only state the app has
    // to remember is WHICH EXERCISE the entry card is currently pointed at. Everything
    // else is derived from the journal, which is why closing the screen mid-workout, or
    // the app being killed, loses nothing.

    private val _activeExerciseId = MutableStateFlow<Long?>(null)
    val activeExerciseId: StateFlow<Long?> = _activeExerciseId.asStateFlow()

    /**
     * Points the entry card at an exercise. [learnedWord], when given, is the text that
     * was typed into the search field before the exercise was tapped: §11 turns it into
     * an alias, so the same word finds the same exercise next time without a search.
     */
    fun selectExercise(id: Long?, learnedWord: String? = null) {
        _activeExerciseId.value = id
        val word = learnedWord?.trim()
        if (id != null && !word.isNullOrEmpty()) {
            viewModelScope.launch { repo.learnAlias(word, id) }
        }
    }

    /**
     * Appends a set to the journal. One event = one set; nothing is ever updated.
     *
     * This is also where the rest timer starts, because recording a set is the moment the
     * rest begins — asking the user to then press a second button would mean the countdown
     * always starts a few seconds late, and those seconds are the whole point of measuring
     * it. Only set-based forms trigger it (see [startsRest]); a weigh-in does not.
     *
     * The duration is resolved AFTER the write, so the gap that has just been measured
     * (the pause before this very set) is part of what the offer is based on.
     */
    fun addSet(form: ActivityForm) {
        viewModelScope.launch {
            repo.record(form)
            val settings = timer.settings.value
            if (timer.enabled.value && settings.autoStartRest && startsRest(form)) {
                startRest(form.exerciseId)
            }
        }
    }

    /** Cancels a set by appending a reversing event — the set itself stays in the history. */
    fun undoSet(eventId: Long) {
        viewModelScope.launch { repo.cancelSet(eventId) }
    }

    /**
     * Creates a catalog exercise and immediately points the entry card at it. The name is
     * learned as an alias straight away, so typing it later finds this exercise.
     * For holds, edge and protocol are part of the identity (§12-A) and are stored on the
     * exercise rather than asked for on every set.
     */
    fun createExercise(
        name: String,
        form: ExerciseForm,
        edgeMm: Double? = null,
        workSec: Double? = null,
        restSec: Double? = null,
    ) {
        viewModelScope.launch {
            val id = repo.ensureExercise(name.trim(), form, edgeMm, workSec, restSec)
            repo.learnAlias(name, id)
            _activeExerciseId.value = id
        }
    }

    /**
     * Demo history on first launch: there is nothing to verify on empty screens.
     * Idempotent — a repeated call first wipes the previous seed's events (by author).
     */
    fun seedIfEmpty() {
        viewModelScope.launch {
            if (repo.eventCount() > 0) return@launch
            seeding.value = true
            runCatching { DemoSeed.seed(repo, today) }
            seeding.value = false
        }
    }

    /** Debug button: rewrite the demo history in place (the seed events are replaced). */
    fun reseed() {
        viewModelScope.launch {
            seeding.value = true
            runCatching { DemoSeed.seed(repo, today) }
            seeding.value = false
        }
    }

    // --- the workout timer -------------------------------------------------------------
    //
    // The ViewModel owns none of the timer's state: a run has to keep going when no
    // ViewModel exists (the app closed, the process killed and rebuilt by the alarm), so
    // it lives in the process-wide TimerController and this class only forwards.

    val timerRun: StateFlow<RunSnapshot?> = timer.run
    val timerSettings: StateFlow<TimerSettings> = timer.settings
    val timerEnabled: StateFlow<Boolean> = timer.enabled
    val speechStatus: StateFlow<SpeechStatus> = timer.speaker.status

    val programs: StateFlow<List<WorkoutProgram>> = programRepo.programs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun enableTimer() = timer.setEnabled(true)

    /**
     * Probes for a speech engine. Called when the timer screen appears rather than when
     * the announcement switch is touched: the switch has to be able to say "no engine on
     * this device" BEFORE it is touched, which on a phone without Google services is the
     * usual answer.
     */
    fun prepareSpeech() = timer.prepareSpeech()

    fun disableTimer() = timer.setEnabled(false)

    fun updateTimerSettings(settings: TimerSettings) = timer.updateSettings(settings)

    /** How long a rest for this exercise would be, and whether that came from history. */
    fun restSecFor(exerciseId: Long?): Int =
        resolveRestSec(timerSettings.value, state.value.events, exerciseId)

    fun restSourceFor(exerciseId: Long?): String =
        restSourceLabel(timerSettings.value, state.value.events, exerciseId)

    /** Starts a single pause. Its length comes from the settings and from the journal. */
    fun startRest(exerciseId: Long?) {
        viewModelScope.launch {
            val events = repo.allEvents()
            val seconds = resolveRestSec(timerSettings.value, events, exerciseId)
            timer.start(restProgram(seconds), exerciseId)
        }
    }

    /**
     * The one-tap program for a hangboard exercise: its work:rest protocol is already on
     * the catalog row, the rep count comes from the last set of it that was logged, the set
     * count from the settings, and the pause between sets from what was actually rested.
     * Nothing is asked, because nothing is unknown.
     */
    fun startProgramForExercise(exercise: ExerciseRef) {
        viewModelScope.launch {
            val events = repo.allEvents()
            val settings = timerSettings.value
            val reps = lastHoldSet(events, exercise.id)?.reps ?: DEFAULT_HOLD_REPS
            val program = programFromExercise(
                exercise = exercise,
                reps = reps,
                sets = settings.defaultSets,
                restBetweenSetsSec = resolveRestSec(settings, events, exercise.id),
                prepareSec = settings.prepareSec,
            ) ?: return@launch
            timer.start(program, exercise.id)
        }
    }

    fun runProgram(program: WorkoutProgram) = timer.start(program)

    fun pauseTimer() = timer.pause()
    fun resumeTimer() = timer.resume()
    fun skipStep() = timer.skip()
    fun previousStep() = timer.previous()
    fun nudgeTimer(deltaSec: Int) = timer.nudge(deltaSec)
    fun stopTimer() = timer.stop()

    fun saveProgram(program: WorkoutProgram) {
        viewModelScope.launch { programRepo.save(program) }
    }

    fun deleteProgram(id: Long) {
        viewModelScope.launch { programRepo.delete(id) }
    }

    /** Writes the starter programs on first launch, alongside the demo history. */
    fun seedProgramsIfEmpty() {
        viewModelScope.launch { runCatching { programRepo.seedStartersIfEmpty() } }
    }

    class Factory(
        private val repo: ActivityRepository,
        private val programRepo: ProgramRepository,
        private val timer: TimerController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repo, programRepo, timer) as T
    }

    private companion object {
        /** Reps in a hangboard set when the journal has no previous one to copy. */
        const val DEFAULT_HOLD_REPS = 6
    }
}
