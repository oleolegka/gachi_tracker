package xyz.oleolegka.gachimuchi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.toRef
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.CelebrationCue
import xyz.oleolegka.gachimuchi.domain.CompletedSet
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.RecordHit
import xyz.oleolegka.gachimuchi.domain.RestFloor
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.RunOutcome
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.celebratedByPicture
import xyz.oleolegka.gachimuchi.domain.dayWatchDelayMs
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
import xyz.oleolegka.gachimuchi.domain.evaluateHoldRecord
import xyz.oleolegka.gachimuchi.domain.evaluateStrengthRecord
import xyz.oleolegka.gachimuchi.domain.exerciseLink
import xyz.oleolegka.gachimuchi.domain.holdSetsOfExercise
import xyz.oleolegka.gachimuchi.domain.holdSetsFromRun
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.programFromExercise
import xyz.oleolegka.gachimuchi.domain.resolveRestSec
import xyz.oleolegka.gachimuchi.domain.restSourceLabel
import xyz.oleolegka.gachimuchi.domain.startsRest
import xyz.oleolegka.gachimuchi.domain.strengthSetsOfExercise
import xyz.oleolegka.gachimuchi.domain.withUniqueNames
import xyz.oleolegka.gachimuchi.timer.SpeechStatus
import xyz.oleolegka.gachimuchi.timer.TimerController
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Screen state: the whole journal, catalog and slot list, with the reducers applied on the
 * spot by the domain functions. Per-screen slicing lives in the screens themselves — there
 * is very little data (a personal diary), and an extra "view state per screen" layer would
 * only get in the way.
 *
 * [loading] means "the database has not answered yet", and nothing else. It is what keeps a
 * dialog raised on the first frame from describing an empty catalog as the whole truth.
 */
data class UiState(
    val events: List<JournalEvent> = emptyList(),
    val exercises: List<ExerciseEntity> = emptyList(),
    val slots: List<Slot> = emptyList(),
    val loading: Boolean = true,
) {
    fun exerciseById(id: Long?): ExerciseEntity? = id?.let { e -> exercises.firstOrNull { it.id == e } }

    fun formOf(id: Long?): ExerciseForm? =
        exerciseById(id)?.let { runCatching { ExerciseForm.fromCode(it.form) }.getOrNull() }

    /** The catalog row as the domain sees it — what the entry card builds its forms from. */
    fun refById(id: Long?): ExerciseRef? = exerciseById(id)?.toRef()

    /**
     * How the journal names an exercise the screen is holding a number for.
     *
     * The screens navigate by local row number, and the journal is keyed by identity — see
     * [ExerciseLink]. This is the one place that bridges the two, so no screen invents its own
     * bridge. An exercise no longer in the catalog falls back to the number, which still finds
     * every entry written before it was deleted.
     */
    fun linkOf(id: Long): ExerciseLink = refById(id)?.link ?: ExerciseLink.ofId(id)
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
    /**
     * The write did not complete: something threw part way through.
     *
     * Kept apart from `setCount == 0` because the two need opposite sentences and used to
     * get the same one. An offer edited down to nothing is the user's own decision and
     * "nothing was written" is the whole answer; a write that failed is the app's fault, may
     * have landed some of the sets ([eventIds] says which), and telling that user their sets
     * were empty is a lie that sends them looking in the wrong place.
     */
    val failed: Boolean = false,
)

class MainViewModel(
    private val repo: ActivityRepository,
    private val programRepo: ProgramRepository,
    private val timer: TimerController,
) : ViewModel() {

    val state: StateFlow<UiState> =
        combine(repo.events, repo.exercises, repo.slots) { events, exercises, slots ->
            UiState(events, exercises, slots, loading = false)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /**
     * One "today" for every screen: the calendar and the Today tab must not drift apart.
     *
     * A FLOW, not a value read at start-up. It used to be `LocalDate.now()` evaluated once
     * when this class was built, which is correct until the app spends a night in a pocket:
     * the ViewModel survives, the date does not move, and the next morning's first set is
     * written under yesterday. The watcher re-reads the clock (domain/Today.kt says how
     * often) and only the change is published, so nothing recomposes for a reading that
     * said the same thing.
     */
    val today: StateFlow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(dayWatchDelayMs(LocalDateTime.now()))
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDate.now())

    // --- logging a workout ------------------------------------------------------------
    //
    // A session is not a stored entity (see domain/Session.kt): the only state the app has
    // to remember is WHICH EXERCISE the entry card is currently pointed at. Everything
    // else is derived from the journal, which is why closing the screen mid-workout, or
    // the app being killed, loses nothing.

    private val _activeExerciseId = MutableStateFlow<Long?>(null)
    val activeExerciseId: StateFlow<Long?> = _activeExerciseId.asStateFlow()

    /**
     * Points the entry card at an exercise, and that is the whole of it.
     *
     * It used to take a second argument as well — the text left in the search box when the
     * row was tapped — and learn it as a synonym for the exercise. Nothing is learned now
     * (see domain/Session.kt on why the synonyms went), so choosing an exercise is a
     * selection and not also a silent write.
     */
    fun selectExercise(id: Long?) {
        _activeExerciseId.value = id
    }

    /**
     * Appends a set to the journal. One event = one set; nothing is ever updated.
     *
     * This is also where the rest starts, because recording a set is the moment the rest
     * begins — asking the user to then press a second button would mean the countdown always
     * starts a few seconds late, and those seconds are the whole point of measuring it. Only
     * set-based forms trigger it (see [startsRest]); a weigh-in does not.
     *
     * ── It starts a FLOOR, not a run ────────────────────────────────────────────
     * It used to start a one-step program on the conductor, which meant recording a set of
     * abs cancelled the rest that was counting on the bench — one countdown existed and the
     * newest owner took it. A superset is the ordinary case for this app and that behaviour
     * made it unusable. Now each exercise gets its own floor (domain/Floors.kt), they run
     * side by side, and starting one for an exercise that already has one replaces only that
     * one.
     *
     * The duration is resolved AFTER the write, so the gap that has just been measured (the
     * pause before this very set) is part of what the offer is based on.
     */
    fun addSet(form: ActivityForm, attachToWorkout: Boolean = true) {
        viewModelScope.launch {
            val worthAPicture = celebratedByPicture(form)
            // BEFORE the write, or the set would be compared against itself and no set
            // would ever be a record
            val record = if (worthAPicture) recordBrokenBy(form) else null
            repo.record(form, attachToWorkout = attachToWorkout)
            if (worthAPicture) {
                _celebrations.tryEmit(
                    CelebrationCue(serial = ++celebrationSerial, isRecord = record != null, text = record?.text)
                )
            }
            val settings = timer.settings.value
            /*
             * TRAINING TYPED UP AFTER THE FACT IS SILENT (§13.6). A rest that ended a
             * fortnight ago is not something to wait out, and a countdown starting while
             * somebody enters old notes on the sofa is pure noise. The date is the set's
             * own, not this class's idea of today, so the rule holds however stale that is.
             */
            val live = form.opDate == LocalDate.now().toString()
            /*
             * A floor belongs to an exercise: it is drawn under that exercise's card and it
             * says that exercise's name out loud. A set recorded against nothing has nowhere
             * to put one, so it gets none rather than a nameless bar. In practice the two
             * forms that reach here always carry an exercise; the branch is here so that a
             * form which one day does not cannot produce a floor called "null".
             */
            val exerciseId = form.exerciseId
            if (live && timer.enabled.value && settings.autoStartRest && startsRest(form) && exerciseId != null) {
                timer.floors.start(
                    exerciseId = exerciseId,
                    exerciseName = repo.exercise(exerciseId)?.name ?: "Rest",
                    orderedMs = resolveRestSec(settings, repo.allEvents(), exerciseId) * 1000L,
                )
            }
        }
    }

    // --- workouts (§13) -------------------------------------------------------------------

    /**
     * Opens a workout and hands its id back, because the caller's next move is to go inside
     * the thing it just created and it has no other way to name it — the id IS the id of the
     * event the repository just wrote.
     *
     * [slotId] is the plan it was started from, when it was started from one. The exercises
     * ON that slot are NOT copied in yet; see the note at the call site in ui/GachiApp.kt.
     */
    fun startWorkout(day: LocalDate, slotId: Long? = null, then: (Long) -> Unit) {
        viewModelScope.launch { then(repo.startWorkout(day.toString(), slotId)) }
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
        val exercise = form.exerciseLink() ?: return null
        val events = repo.allEvents()
        return when (form) {
            is StrengthSet ->
                evaluateStrengthRecord(strengthSetsOfExercise(events, exercise), form.weightKg, form.reps)

            is HoldSet -> evaluateHoldRecord(holdSetsOfExercise(events, exercise), form)
            else -> null
        }
    }

    /** Cancels a set by appending a reversing event — the set itself stays in the history. */
    fun undoSet(eventId: Long) {
        viewModelScope.launch { repo.cancelSet(eventId) }
    }

    /**
     * Creates a catalog exercise and immediately points the entry card at it. For holds,
     * edge and protocol are part of the identity (§12-A) and are stored on the exercise
     * rather than asked for on every set.
     */
    fun createExercise(
        name: String,
        form: ExerciseForm,
        edgeMm: Double? = null,
        workSec: Double? = null,
        restSec: Double? = null,
    ) {
        viewModelScope.launch {
            _activeExerciseId.value = repo.ensureExercise(name.trim(), form, edgeMm, workSec, restSec)
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

    // --- the rests between sets, which run in parallel ---------------------------------
    //
    // Forwarded, not owned, for the same reason the run is: a rest has to keep counting when
    // no ViewModel exists, and it survives the process being killed. domain/Floors.kt has the
    // rules; timer/FloorController.kt runs them.

    /** Every rest the app is keeping, for the bars under the exercise cards. */
    val restFloors: StateFlow<List<RestFloor>> = timer.floors.floors

    /** The line about rests that matured while a protocol had them muted, or null. */
    val floorSummary: StateFlow<String?> = timer.floors.summary

    fun dismissFloorSummary() = timer.floors.clearSummary()

    /** Takes one rest bar off, by hand. */
    fun dismissFloor(exerciseId: Long) = timer.floors.dismiss(exerciseId)

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
            val reps = lastHoldSet(events, exercise.link)?.reps ?: DEFAULT_HOLD_REPS
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
            val day = outcome?.opDate?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString()
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
            /*
             * The failure is REPORTED AS A FAILURE. It used to be flattened into
             * `setCount = 0`, and the receipt then explained that every set in the offer had
             * been empty — about sets that were not empty, after a write that broke for some
             * other reason entirely. A confirmation that invents a cause is worse than one
             * that says nothing: it sends the user away satisfied that they know what
             * happened, and the offer they would otherwise have retried is the last copy of
             * the session.
             */
            _logReceipt.value = LogReceipt(
                exerciseName = exercise.name,
                setCount = written.size,
                opDate = day,
                eventIds = written,
                failed = !ok,
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

    /** Writes the starter programs on first launch. They are the only thing written unasked. */
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
