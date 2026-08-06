package xyz.oleolegka.gachimuchi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import xyz.oleolegka.gachimuchi.domain.CelebrationCue
import xyz.oleolegka.gachimuchi.domain.CompletedSet
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.RecordHit
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.RunOutcome
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.celebratedByPicture
import xyz.oleolegka.gachimuchi.domain.evaluateHoldRecord
import xyz.oleolegka.gachimuchi.domain.evaluateStrengthRecord
import xyz.oleolegka.gachimuchi.domain.holdSetsByExerciseId
import xyz.oleolegka.gachimuchi.domain.holdSetsFromRun
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.programFromExercise
import xyz.oleolegka.gachimuchi.domain.resolveRestSec
import xyz.oleolegka.gachimuchi.domain.restProgram
import xyz.oleolegka.gachimuchi.domain.restSourceLabel
import xyz.oleolegka.gachimuchi.domain.startsRest
import xyz.oleolegka.gachimuchi.domain.strengthSetsByExerciseId
import xyz.oleolegka.gachimuchi.domain.withUniqueNames
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

/**
 * Proof that a run went into the journal: what was written, under which day, and the event
 * ids it became so the write can be taken back without going looking for it.
 */
data class LogReceipt(
    val exerciseName: String,
    val setCount: Int,
    val opDate: String,
    val eventIds: List<Long>,
)

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
            val worthAPicture = celebratedByPicture(form)
            // BEFORE the write, or the set would be compared against itself and no set
            // would ever be a record
            val record = if (worthAPicture) recordBrokenBy(form) else null
            repo.record(form)
            if (worthAPicture) {
                _celebrations.tryEmit(
                    CelebrationCue(serial = ++celebrationSerial, isRecord = record != null, text = record?.text)
                )
            }
            val settings = timer.settings.value
            if (timer.enabled.value && settings.autoStartRest && startsRest(form)) {
                startRest(form.exerciseId)
            }
        }
    }

    // --- celebration -------------------------------------------------------------------
    //
    // The ViewModel only says WHAT happened; whether anything is shown, and which picture,
    // is decided where the gallery lives (data/GalleryStore.kt, ui/celebrate/). A cue is
    // fire-and-forget: nothing buffers it for a screen that is not there, because a
    // celebration for a set logged two minutes ago is not a celebration.

    private var celebrationSerial = 0L

    private val _celebrations = MutableSharedFlow<CelebrationCue>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val celebrations: SharedFlow<CelebrationCue> = _celebrations.asSharedFlow()

    /**
     * The record this set breaks, judged against the journal AS IT IS NOW — the same
     * comparison the session feed makes for a set already written (domain/Session.kt), so
     * the overlay and the feed cannot disagree about what was a record.
     */
    private suspend fun recordBrokenBy(form: ActivityForm): RecordHit? {
        val exerciseId = form.exerciseId ?: return null
        val events = repo.allEvents()
        return when (form) {
            is StrengthSet ->
                evaluateStrengthRecord(strengthSetsByExerciseId(events, exerciseId), form.weightKg, form.reps)

            is HoldSet -> evaluateHoldRecord(holdSetsByExerciseId(events, exerciseId), form)
            else -> null
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

    // --- the plan (§12-B) ---------------------------------------------------------------
    //
    // The plan is the one thing in the app that is edited rather than appended to, and the
    // ViewModel adds nothing to it: the calendar screen builds a draft, the domain says
    // whether it is storable, the repository writes it. There is no "current slot" state
    // here, because the editor is a dialog that lives and dies inside the screen.

    /** Creates a slot (id null) or rewrites an existing one. An unusable draft is ignored. */
    fun saveSlot(draft: SlotDraft, id: Long? = null) {
        viewModelScope.launch { repo.saveSlot(draft, id) }
    }

    /** Deletes a slot and, with it, every occurrence of it — past days included. */
    fun deleteSlot(id: Long) {
        viewModelScope.launch { repo.deleteSlot(id) }
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
            timer.start(restProgram(seconds), exerciseId, RunOrigin.REST)
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
            timer.start(program, exercise.id, RunOrigin.EXERCISE)
        }
    }

    /**
     * Runs a saved program.
     *
     * The origin follows the program's own link to a catalog exercise, so that a protocol
     * typed into the editor and a protocol generated from the exercise behave the same way
     * when they finish. They used not to: this passed the defaults, which made every saved
     * program a run that belonged to nothing and therefore offered nothing, however many
     * sets it had just counted.
     */
    fun runProgram(program: WorkoutProgram) = timer.start(
        program = program,
        exerciseId = program.exerciseId,
        origin = if (program.exerciseId != null) RunOrigin.EXERCISE else RunOrigin.PROGRAM,
    )

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

    /**
     * Stores programs read out of a file. Nothing is replaced: a name that is already taken
     * gets a mark rather than overwriting the program that has it (see [withUniqueNames]).
     */
    fun importPrograms(programs: List<WorkoutProgram>) {
        viewModelScope.launch {
            val existing = programRepo.allPrograms().map { it.name }
            withUniqueNames(programs, existing).forEach { programRepo.save(it) }
        }
    }

    // --- writing a finished run into the journal ---------------------------------------

    /** The run waiting to be offered as sets, or null. Nothing is written until confirmed. */
    val runOutcome: StateFlow<RunOutcome?> = timer.outcome

    /** "Not this time": the offer goes away and the journal is untouched. */
    fun dismissRunOutcome() = timer.clearOutcome()

    /**
     * Writes the confirmed sets, one journal event per set, through the same repository and
     * the same domain builders the entry card uses.
     *
     * Deliberately NOT through [addSet]: that starts a rest timer, which is right after a
     * set done by hand and wrong here — the run has just ended and there is nothing left to
     * rest between.
     *
     * The day comes from the OUTCOME, not from today. An offer now survives the process
     * (timer/TimerController.kt), so it can be answered the morning after the session it
     * describes, and filing an evening workout under the next day would quietly corrupt the
     * one record the app exists to keep.
     *
     * Answering also teaches the program which exercise it trains, so a protocol run from
     * the timer tab has to be told once and never again.
     */
    fun logRunSets(exercise: ExerciseRef, sets: List<CompletedSet>, addedKg: Double? = null) {
        val outcome = timer.outcome.value
        viewModelScope.launch {
            val day = outcome?.opDate?.takeIf { it.isNotBlank() } ?: today.toString()
            val written = ArrayList<Long>()
            /*
             * Wrapped, and the ids collected as they go, because the failure mode being
             * avoided is silence. A form the validator rejects would otherwise throw inside
             * this coroutine, kill it, and leave the offer up with no explanation and no
             * write — which reads exactly like the bug this whole change exists to fix. If
             * it throws, whatever did land is still undoable and the offer stays up to be
             * tried again.
             */
            val ok = runCatching {
                holdSetsFromRun(exercise, day, sets, addedKg).forEach { written += repo.record(it) }
            }.isSuccess

            if (ok) {
                outcome?.programId?.takeIf { it != 0L && outcome.exerciseId != exercise.id }
                    ?.let { runCatching { programRepo.linkExercise(it, exercise.id) } }
                timer.clearOutcome()
            }
            _logReceipt.value = LogReceipt(
                exerciseName = exercise.name,
                setCount = written.size,
                opDate = day,
                eventIds = written,
            )
        }
    }

    // --- saying what was written -------------------------------------------------------
    //
    // A write the user cannot see is a write the user does not believe in, and disbelief is
    // expensive here: a session logged by the timer and not noticed gets typed in a second
    // time, which puts twice the training in the journal that produced it. So the write
    // reports itself, and the report carries the ids it wrote so that "no, not that" is one
    // tap rather than a hunt through the feed.

    private val _logReceipt = MutableStateFlow<LogReceipt?>(null)

    /** What the last run-log write put in the journal, until it is acknowledged. */
    val logReceipt: StateFlow<LogReceipt?> = _logReceipt.asStateFlow()

    fun dismissReceipt() {
        _logReceipt.value = null
    }

    /**
     * Takes back a run that was just written. The journal is append-only, so this appends
     * reversing events exactly as the feed's own undo does — the sets stay in the history
     * and stop counting, and nothing about this path is special-cased.
     */
    fun undoRunSets() {
        val receipt = _logReceipt.value ?: return
        _logReceipt.value = null
        viewModelScope.launch { receipt.eventIds.forEach { repo.cancelSet(it) } }
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
