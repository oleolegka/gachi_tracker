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
import xyz.oleolegka.gachimuchi.domain.DraftCard
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.LoadedSet
import xyz.oleolegka.gachimuchi.domain.OrderedCard
import xyz.oleolegka.gachimuchi.domain.ProgramStart
import xyz.oleolegka.gachimuchi.domain.asPlanned
import xyz.oleolegka.gachimuchi.domain.lastWorkoutNamed
import xyz.oleolegka.gachimuchi.domain.pastWorkoutNames
import xyz.oleolegka.gachimuchi.domain.resolvedCards
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
import xyz.oleolegka.gachimuchi.domain.deletedExerciseLinks
import xyz.oleolegka.gachimuchi.domain.actualRestSec
import xyz.oleolegka.gachimuchi.domain.evaluateHoldRecord
import xyz.oleolegka.gachimuchi.domain.evaluateStrengthRecord
import xyz.oleolegka.gachimuchi.domain.exerciseLink
import xyz.oleolegka.gachimuchi.domain.holdSetsOfExercise
import xyz.oleolegka.gachimuchi.domain.holdSetsFromRun
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.scheduledRun
import xyz.oleolegka.gachimuchi.domain.programFromExercise
import xyz.oleolegka.gachimuchi.domain.resolveRestSec
import xyz.oleolegka.gachimuchi.domain.restHintSec
import xyz.oleolegka.gachimuchi.domain.restSourceLabel
import xyz.oleolegka.gachimuchi.domain.startsRest
import xyz.oleolegka.gachimuchi.domain.strengthSetsOfExercise
import xyz.oleolegka.gachimuchi.domain.withUniqueNames
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.workoutEventIds
import xyz.oleolegka.gachimuchi.timer.SpeechStatus
import xyz.oleolegka.gachimuchi.ui.screens.NewExercise
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
    /**
     * The program library, by local row id — folded in here so that [refById] and every screen
     * that needs an exercise's resolved protocol (the identity chip, the picker's protocol
     * caption, the hold-exercise list `GachiApp.kt` builds for the timer tab) can do it without
     * a second, separate `combine` of their own. This is NOT the only place the library is
     * exposed: [xyz.oleolegka.gachimuchi.ui.MainViewModel.programs] stays a StateFlow of its
     * own too, because the program editor screen reads that directly and has no reason to carry
     * the rest of [UiState] along with it.
     */
    val programsById: Map<Long, WorkoutProgram> = emptyMap(),
    val loading: Boolean = true,
) {
    fun exerciseById(id: Long?): ExerciseEntity? = id?.let { e -> exercises.firstOrNull { it.id == e } }

    fun formOf(id: Long?): ExerciseForm? =
        exerciseById(id)?.let { runCatching { ExerciseForm.fromCode(it.form) }.getOrNull() }

    /**
     * The domain's view of a catalog row, protocol resolved — what the entry card builds its
     * forms from, and what every screen wanting `ExerciseRef.workSec`/`restSec` reads.
     */
    fun refOf(exercise: ExerciseEntity): ExerciseRef =
        exercise.toRef(exercise.protocolProgramId?.let { programsById[it] })

    /** The catalog row as the domain sees it — what the entry card builds its forms from. */
    fun refById(id: Long?): ExerciseRef? = exerciseById(id)?.let(::refOf)

    /**
     * Names offered by a "start like last time" dropdown (§13.9) — see
     * [xyz.oleolegka.gachimuchi.domain.pastWorkoutNames] for what decides the list and its order.
     */
    val pastWorkoutNames: List<String> get() = pastWorkoutNames(events)

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
        combine(repo.events, repo.exercises, repo.slots, programRepo.programs) { events, exercises, slots, programs ->
            /*
             * The ONE place a deleted exercise's own catalog row is taken out of what the app
             * shows — every screen reads the exercise list off this [UiState], never off
             * `repo.exercises` directly (see ui/screens/ExercisePicker.kt, OverviewScreen.kt,
             * GachiApp.kt's hold-exercise list). Its history is a separate concern, handled by
             * the SAME fold one level down: readActivities/buildWorkout already drop a deleted
             * exercise's own entries because they go through domain/Amendments.kt, which this
             * merely mirrors for the row itself — see [deletedExerciseLinks]'s own KDoc for why
             * the two are two folds of one journal rather than one shared answer.
             */
            val gone = deletedExerciseLinks(events)
            val visibleExercises = if (gone.isEmpty()) {
                exercises
            } else {
                exercises.filterNot { ex -> gone.any { it.matches(ExerciseLink(ex.uid, ex.id)) } }
            }
            UiState(events, visibleExercises, slots, programs.associateBy { it.id }, loading = false)
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
     * The new floor's duration is resolved AFTER the write, so the gap that has just been
     * measured (the pause before this very set) is part of what the offer is based on.
     *
     * ── The OLD floor closes the loop on the set BEFORE this one ────────────────
     * If an exercise already had a floor running, that floor's [xyz.oleolegka.gachimuchi.domain.RestFloor.startedAtWallMs]
     * is the wall-clock moment its own previous set was recorded — the one honest reading of
     * how long the rest between them actually was, rather than [xyz.oleolegka.gachimuchi.domain.secondsBetween]'s
     * guess from two write times. It is read and turned into an amendment BEFORE the write
     * below, because after it the new set would itself become "the previous set" and
     * [ActivityRepository.recordActualRest] would target the wrong row.
     *
     * This deliberately does NOT require [TimerSettings.autoStartRest] to still be on: that
     * setting only decides whether a NEW floor starts, further down. A floor already running
     * is a fact about a rest that genuinely happened, and turning autoStartRest off between two
     * sets is not a reason to stop believing the clock that already ran.
     */
    fun addSet(form: ActivityForm, attachToWorkout: Boolean = true, intoWorkoutId: Long? = null) {
        viewModelScope.launch {
            val worthAPicture = celebratedByPicture(form)
            // BEFORE the write, or the set would be compared against itself and no set
            // would ever be a record
            val record = if (worthAPicture) recordBrokenBy(form) else null

            /*
             * TRAINING TYPED UP AFTER THE FACT IS SILENT (§13.6). A rest that ended a
             * fortnight ago is not something to wait out, and a countdown starting while
             * somebody enters old notes on the sofa is pure noise. The date is the set's
             * own, not this class's idea of today, so the rule holds however stale that is.
             */
            val live = form.opDate == LocalDate.now().toString()
            val settings = timer.settings.value
            /*
             * A floor belongs to an exercise: it is drawn under that exercise's card and it
             * says that exercise's name out loud. A set recorded against nothing has nowhere
             * to put one, so it gets none rather than a nameless bar. In practice the two
             * forms that reach here always carry an exercise; the branch is here so that a
             * form which one day does not cannot produce a floor called "null".
             */
            val exerciseId = form.exerciseId
            /*
             * Which CARD this set belongs to, for an exercise trained one limb at a time — the
             * left hand's rest and the right hand's are two floors, not one, so the exercise id
             * alone no longer names the countdown a set closes out or the one it starts. Only a
             * [LoadedSet] ever carries a side; every other form floors by exercise id alone,
             * side always null, exactly as before this existed.
             */
            val side = (form as? LoadedSet)?.sideOf
            if (live && timer.enabled.value && startsRest(form) && exerciseId != null) {
                timer.floors.floors.value.firstOrNull { it.exerciseId == exerciseId && it.side == side?.code }
                    ?.actualRestSec(System.currentTimeMillis())
                    ?.let { repo.recordActualRest(form.exerciseLink()!!, it, side) }
            }

            repo.record(form, attachToWorkout = attachToWorkout, intoWorkoutId = intoWorkoutId)
            if (worthAPicture) {
                _celebrations.tryEmit(
                    CelebrationCue(serial = ++celebrationSerial, isRecord = record != null, text = record?.text)
                )
            }
            if (live && timer.enabled.value && settings.autoStartRest && startsRest(form) && exerciseId != null) {
                val exercise = repo.exercise(exerciseId)
                val label = exercise?.name ?: "Rest"
                /*
                 * THE REST THAT WAS CHOSEN, and only failing that the one that was measured —
                 * which is what [restHintSec] resolves and what [resolveRestSec] on its own
                 * does not. The difference became visible the moment a workout could be asked
                 * which rest an exercise gets: the card would say "rest 3:00" because that is
                 * what was agreed to, and the bar underneath would count the median gap
                 * between the last few sets instead. A timer disagreeing with the number
                 * printed above it is worse than no timer, because it is believed.
                 */
                timer.floors.start(
                    exerciseId = exerciseId,
                    // said by hand as well as by card, since the summary line and the shade
                    // notification only ever have the name to tell the two floors apart by
                    exerciseName = if (side != null) "$label - ${side.label()}" else label,
                    orderedMs = restHintSec(settings, repo.allEvents(), exercise?.let { repo.toRef(it) }) * 1000L,
                    side = side?.code,
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
     * [slotId] is the plan it was started from, when it was started from one — and when it is
     * one, that plan's exercises are COPIED into the workout before the caller is told about
     * it, so the screen it opens is already the list of what the session is meant to be
     * (§13.7). Copied and not referenced: the plan is editable and the facts are not.
     *
     * The copy happens between the start and [then] rather than after it, so the screen never
     * draws a workout that is about to gain three cards on the next frame.
     */
    fun startWorkout(
        day: LocalDate,
        slotId: Long? = null,
        /**
         * What to call it, or null for a workout nobody named — which is the ordinary case and
         * not a defect. A workout started FROM A PLAN and given no name of its own takes the
         * plan's, copied once as a snapshot; see [ActivityRepository.startWorkout].
         */
        name: String? = null,
        then: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val id = repo.startWorkout(day.toString(), slotId, name)
            if (slotId != null) repo.copyPlannedExercises(id, slotId, timer.settings.value)
            then(id)
        }
    }

    // --- the workout that has not started yet (§13.1) ---------------------------------------
    //
    // Sketching a session ahead of time used to mean pressing "start", which wrote a real
    // `workout_started` row an hour before the person meant to train and took the plan card
    // off Today under it. "I did not want to start a workout, why did the app decide that I
    // did." So the start event is now created LAZILY: adding exercises to a draft touches only
    // this state, and nothing is written to the journal until an explicit "start workout" or
    // the first set — see [promoteDraft].

    /**
     * A workout being sketched before it has actually begun. [cards] are staged locally and
     * become real `workout_exercise_added` rows only when [promoteDraft] fires; nothing here
     * is in the journal, so the plan card it came from (if any) stays exactly what it was.
     */
    data class WorkoutDraft(
        val day: LocalDate,
        val slotId: Long? = null,
        val name: String? = null,
        val cards: List<DraftCard> = emptyList(),
    )

    private val _draft = MutableStateFlow<WorkoutDraft?>(null)
    val draft: StateFlow<WorkoutDraft?> = _draft.asStateFlow()

    /**
     * Opens a draft for [day] — pre-filled from [slotId]'s plan, or, when [slotId] is null and
     * [name] is exactly the name of a past workout, from the LATEST workout that carried it
     * (§13.9, "start like last time": three workouts can share one name, and this is the one
     * place that decides which — see [lastWorkoutNamed]). Neither source applying leaves the
     * draft empty, which is the ordinary state for a workout started off-plan under a name
     * nothing has used before.
     *
     * Both sources are resolved through [resolvedCards], the one funnel
     * [ActivityRepository.copyPlannedExercises] also goes through for a real workout — so a
     * plan and a past workout are turned into cards the same way regardless of which wrote this
     * one.
     *
     * Replaces whatever draft was open, on the same "starting one closes the last" grounds
     * [ActivityRepository.startWorkout] already applies to real workouts. NOTHING IS WRITTEN TO
     * THE JOURNAL by this either way — the workout itself is only opened once [promoteDraft]
     * fires.
     */
    fun beginDraft(day: LocalDate, slotId: Long? = null, name: String? = null) {
        viewModelScope.launch {
            val events = repo.allEvents()
            val planned = when {
                slotId != null -> repo.slotExercises(slotId)
                name != null -> lastWorkoutNamed(events, name)?.let(::asPlanned).orEmpty()
                else -> emptyList()
            }
            val settings = timer.settings.value
            val cards = resolvedCards(
                planned,
                refOf = { id -> state.value.refById(id) },
                restFallback = { ref -> restHintSec(settings, events, ref) },
            )
            _draft.value = WorkoutDraft(day, slotId, name, cards)
        }
    }

    /** Stages an exercise into the draft, or — called again for one already there — changes its rest. */
    fun updateDraftCard(exerciseId: Long, restSec: Int, side: HoldSide? = null) {
        val current = _draft.value ?: return
        val without = current.cards.filterNot { it.exerciseId == exerciseId && it.side == side }
        _draft.value = current.copy(cards = without + DraftCard(exerciseId, restSec, side))
    }

    /** Takes a card off the draft. There is nothing to undo in the journal — it was never written. */
    fun removeDraftCard(exerciseId: Long, side: HoldSide? = null) {
        val current = _draft.value ?: return
        _draft.value = current.copy(cards = current.cards.filterNot { it.exerciseId == exerciseId && it.side == side })
    }

    /** States the draft's order — the same shape a real workout's [setWorkoutExerciseOrder] does. */
    fun reorderDraftCards(order: List<OrderedCard>) {
        val current = _draft.value ?: return
        val reordered = order.mapNotNull { entry ->
            current.cards.firstOrNull { it.exerciseId == entry.exercise.id && it.side == entry.side }
        }
        _draft.value = current.copy(cards = reordered)
    }

    /** Leaves the draft behind without starting anything — closing the screen before either did. */
    fun discardDraft() {
        _draft.value = null
    }

    /**
     * Turns the draft into a real workout: the start event, then every staged card's own
     * "added" row — the two writes [ActivityRepository.startWorkout] and
     * [ActivityRepository.addExerciseToWorkout] always were, just no longer made in advance of
     * there being anything to write them for. [then] receives the new workout's id, the same
     * way [startWorkout] hands one back, because the caller's next move needs it — to file the
     * set that triggered this, or simply to start showing the real workout instead of the draft.
     *
     * [ActivityRepository.startWorkout] itself is deliberately NOT what this calls: it also
     * copies a plan's composition, which here would duplicate the very cards [beginDraft]
     * already staged from that same plan.
     *
     * A no-op when there is no draft open — the caller raced an empty state, or promoted twice
     * for the two things that can trigger it (the button and the first set) landing together.
     */
    fun promoteDraft(then: (Long) -> Unit) {
        val current = _draft.value ?: return
        _draft.value = null
        viewModelScope.launch {
            val id = repo.startWorkout(current.day.toString(), current.slotId, current.name)
            current.cards.forEach { card -> repo.addExerciseToWorkout(id, card.exerciseId, card.restSec, card.side) }
            then(id)
        }
    }

    /**
     * Puts an exercise into a workout at a chosen rest — and, called again for one already
     * there, changes that rest.
     *
     * ONE METHOD FOR BOTH, because the journal has one event for both: adding an exercise
     * twice does not duplicate it, the last rest wins, and the order it was added in is kept
     * (see `buildWorkout`). The repository also writes the rest onto the catalog row, so the
     * choice made here is what the NEXT workout will be offered — the two writes are two
     * different facts and neither can be derived from the other; see
     * [ActivityRepository.addExerciseToWorkout].
     *
     * [side] names one CARD of a one-sided exercise. Adding both is two calls — see
     * [xyz.oleolegka.gachimuchi.ui.screens.WorkoutLogScreen] for where they are made.
     */
    fun addExerciseToWorkout(workoutId: Long, exerciseId: Long, restSec: Int, side: HoldSide? = null) {
        viewModelScope.launch { repo.addExerciseToWorkout(workoutId, exerciseId, restSec, side) }
    }

    /**
     * States the order the exercises of a workout are to be done in — see
     * [ActivityRepository.setWorkoutExerciseOrder].
     *
     * The WHOLE order, every time, because that is what the event carries: the screen hands over
     * the arrangement it is showing and does not have to describe a move.
     */
    fun setWorkoutExerciseOrder(workoutId: Long, order: List<OrderedCard>) {
        viewModelScope.launch { repo.setWorkoutExerciseOrder(workoutId, order) }
    }

    /**
     * Says a workout is over.
     *
     * It is not an undo and not a lock: the workout keeps everything it has, can be opened
     * again, and a set added afterwards goes into it and moves its end time. What it changes
     * is that this workout stops being the one sets land in by default.
     */
    fun finishWorkout(workoutId: Long) {
        viewModelScope.launch { repo.finishWorkout(workoutId) }
    }

    /**
     * Puts a workout that was marked done back in progress, by deleting the event that said
     * it was finished — see [ActivityRepository.unfinishWorkout]. The whole-workout twin of
     * [unfinishWorkoutExercise]: nothing already recorded is touched, and nothing is restarted.
     */
    fun unfinishWorkout(eventId: Long) {
        viewModelScope.launch { repo.unfinishWorkout(eventId) }
    }

    /**
     * Marks one CARD done — see [ActivityRepository.finishWorkoutExercise] — and stops timing
     * its rest.
     *
     * The two are one action from here, and that is a decision worth stating rather than
     * leaving implicit: a card that no longer offers a "log a set" button has nothing left
     * for a countdown to be FOR, and a beep arriving under a card the user has just declared
     * done reads as the app disagreeing with them. Nothing is lost by dismissing rather than
     * pausing it — a floor is only ever "not before" a NEXT set, and the next set on this card
     * starts a fresh one the same way it always has, finished or not.
     */
    fun finishWorkoutExercise(workoutId: Long, exercise: ExerciseLink, side: HoldSide? = null) {
        exercise.id?.let { timer.floors.dismiss(it, side?.code) }
        viewModelScope.launch { repo.finishWorkoutExercise(workoutId, exercise, side) }
    }

    /**
     * Puts a card that was marked done back among the active ones, by deleting the event that
     * said it was finished — see [ActivityRepository.unfinishWorkoutExercise].
     *
     * NOTHING is restarted here, and that is deliberate: the rest countdown dismissed on the
     * way in measured a pause that is now minutes in the past, and starting it again would put
     * a number on the screen that describes nothing. The next set on this card starts a fresh
     * one, exactly as it does for a card that was never finished at all.
     */
    fun unfinishWorkoutExercise(eventId: Long) {
        viewModelScope.launch { repo.unfinishWorkoutExercise(eventId) }
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
     *
     * [StrengthSet.warmup] and [StrengthSet.incomplete] are both passed through — a card that
     * writes either must not pop a "new record" cue for a set domain/Records.kt already refuses
     * to count. [HoldSet] needs nothing of the sort passed separately: [evaluateHoldRecord] reads
     * both flags straight off the form it is handed.
     */
    private suspend fun recordBrokenBy(form: ActivityForm): RecordHit? {
        val exercise = form.exerciseLink() ?: return null
        val events = repo.allEvents()
        return when (form) {
            // outward one branch — LoadedSet is the only pair with a record model; which record
            // function applies still depends on the concrete form, so that stays nested
            is LoadedSet -> when (form) {
                is StrengthSet ->
                    evaluateStrengthRecord(
                        strengthSetsOfExercise(events, exercise), form.weightKg, form.reps,
                        warmup = form.warmup, side = form.sideOf, incomplete = form.incomplete,
                    )

                is HoldSet -> evaluateHoldRecord(holdSetsOfExercise(events, exercise), form)
            }

            else -> null
        }
    }

    /** Cancels a set by appending a reversing event — the set itself stays in the history. */
    fun undoSet(eventId: Long) {
        viewModelScope.launch { repo.cancelSet(eventId) }
    }

    /**
     * Corrects an entry already in the journal, from the whole form the editor is holding.
     *
     * ANY entry, not only the last one — which is the difference from [undoSet], and the reason
     * this exists. Nothing is rewritten: the repository appends an amendment naming the target
     * and `domain/Amendments.kt` folds the two into what the readers see.
     *
     * The exercise carried by [updated] is IGNORED by the repository rather than applied (see
     * `amendEntry`), so a correction can never move a set to another exercise. The editor does
     * not offer it either — this is the belt to that dialog's braces, not the only guard.
     */
    fun amendEntry(eventId: Long, updated: ActivityForm) {
        viewModelScope.launch { repo.amendEntry(eventId, updated) }
    }

    /**
     * Removes ANY entry from every reading of the journal — not only the newest, which is all
     * [undoSet] could ever reach.
     *
     * The row stays in the table; this appends a deletion naming it. So it is reversible in
     * principle (deleting the deletion brings it back), and nothing about the append-only
     * guarantee is given up to get a delete button.
     */
    fun deleteEntry(eventId: Long) {
        viewModelScope.launch { repo.deleteEntry(eventId) }
    }

    /**
     * Removes several events as ONE act — an exercise taken out of a workout, which is its
     * "added" rows and every set of it.
     *
     * Still one deletion event per row, because that is the only thing the journal has to say
     * with: there is no "delete these" event and inventing one would mean a second shape for
     * every reader in domain/Amendments.kt to understand. What ties the rows together is
     * [ActivityRepository.deleteEntries]'s own transaction, not a coroutine on its own — one
     * coroutine keeps the journal from being READ back half-removed by the flow the screen is
     * collecting, but only a database transaction keeps it from being WRITTEN back half-removed
     * when the process itself does not survive the loop.
     */
    fun deleteEntries(eventIds: List<Long>) {
        if (eventIds.isEmpty()) return
        viewModelScope.launch { repo.deleteEntries(eventIds) }
    }

    /**
     * Takes one exercise CARD out of a workout: its rows, and the rest counting under it.
     *
     * ── Why the rest is dismissed twice, by two different rules ─────────────────
     * First by CARD — (exerciseId, side) — which is right and is what the left hand's card
     * being removed must not do to the right hand's countdown.
     *
     * Then, once the rows are gone, by EXERCISE, but only if the workout no longer holds any
     * card of it. That second pass is the belt to the first one's braces, and it exists because
     * the two keys are written by different code paths: a floor is keyed by the side of the SET
     * that started it, a card by the side of the row that added it, and any journal where those
     * two disagree (a set recorded with no side on a one-sided exercise — see WorkoutExercise's
     * own KDoc for how that produces a third, sideless block) leaves a countdown alive with
     * nothing left on screen to stop it. That is §23.A2 as reported from the phone: the exercise
     * is gone and the rest goes on counting, and speaking, in the background.
     */
    fun removeWorkoutExercise(
        workoutId: Long,
        eventIds: List<Long>,
        exerciseId: Long?,
        side: HoldSide? = null,
    ) {
        exerciseId?.let { timer.floors.dismiss(it, side?.code) }
        viewModelScope.launch {
            if (eventIds.isNotEmpty()) repo.deleteEntries(eventIds)
            if (exerciseId != null) {
                val stillThere = buildWorkout(repo.allEvents(), workoutId)
                    ?.exercises.orEmpty()
                    .any { it.exerciseId == exerciseId }
                if (!stillThere) timer.floors.dismissAllOf(exerciseId)
            }
        }
    }

    /**
     * Removes a workout and everything recorded into it.
     *
     * ── Why the whole thing and not just the start event ────────────────────────
     * Deleting the start alone takes the workout off every screen and leaves its sets counting:
     * a row pointing at a workout the journal no longer holds is treated as recorded OUTSIDE
     * any workout (see `setsOutsideWorkouts`), so the sets would come back as loose entries on
     * the same day and keep their place in the volume, the records and the streak. That is the
     * opposite of what the confirmation on the card promised, which is why the rows are named
     * together — see [workoutEventIds].
     *
     * The journal is read here rather than in the screen because a card knows only an id; the
     * rows a workout is made of are a question about the journal, and this is the layer that
     * has one.
     *
     * The write itself goes through [ActivityRepository.deleteEntries], as one transaction —
     * see its own KDoc for why a loop of [ActivityRepository.deleteEntry] calls used to be able
     * to leave a workout half gone.
     */
    fun deleteWorkout(workoutId: Long) {
        viewModelScope.launch {
            val events = repo.allEvents()
            /*
             * The countdowns first, and from the workout as it still reads — after the rows are
             * gone there is nothing left to ask which exercises it held. A rest outlives the
             * screen it was started from (it is a foreground service, a notification and an
             * alarm), so deleting the workout without this leaves the phone counting, and
             * eventually speaking, for a session that is no longer in the log (§23.A2).
             */
            buildWorkout(events, workoutId)?.exercises?.forEach { exercise ->
                exercise.exerciseId?.let { timer.floors.dismiss(it, exercise.side?.code) }
            }
            repo.deleteEntries(workoutEventIds(events, workoutId))
        }
    }

    /**
     * Names a workout already started, or — with null — takes its name away again.
     *
     * An amendment of the start event, folded like every other correction, so the card, the
     * workout screen and the logging screen agree without any of them being told. See
     * [ActivityRepository.renameWorkout].
     */
    fun renameWorkout(workoutId: Long, name: String?) {
        viewModelScope.launch { repo.renameWorkout(workoutId, name) }
    }

    /**
     * Creates a catalog exercise and immediately points the entry card at it. For holds, the
     * protocol is part of the identity (§12-A) and is stored on the exercise rather than asked
     * for on every set.
     *
     * [then] receives the new row's id. It exists because creating an exercise mid-workout is
     * never the last step — the workout then asks what rest it should get, and that question
     * cannot be put until the row it is about exists. A caller that only needs the exercise to
     * become the active one leaves it out and reads [activeExerciseId] as before.
     */
    fun createExercise(new: NewExercise, then: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val id = repo.ensureExercise(
                name = new.name.trim(),
                form = new.form,
                workSec = new.workSec,
                restSec = new.restSec,
                oneSided = new.oneSided,
                protocolProgramId = new.protocolProgramId,
            )
            _activeExerciseId.value = id
            then?.invoke(id)
        }
    }

    // --- the plan (§12-B) ---------------------------------------------------------------
    //
    // The plan is the one thing in the app that is edited rather than appended to, and the
    // ViewModel adds nothing to it: the calendar screen builds a draft, the domain says
    // whether it is storable, the repository writes it. There is no "current slot" state
    // here, because the editor is a dialog that lives and dies inside the screen.

    /**
     * Creates a slot (id null) or rewrites an existing one. An unusable draft is ignored.
     *
     * The day is handed down so the repository can refuse a plan put on a day already gone
     * without trusting a screen to have refused first — see [ActivityRepository.saveSlot].
     * It comes off the same [today] every screen reads, so nothing can disagree about which
     * day it is.
     */
    fun saveSlot(draft: SlotDraft, id: Long? = null) {
        viewModelScope.launch { repo.saveSlot(draft, id, today = today.value) }
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

    /**
     * Takes one rest bar off, by hand — [side] names the CARD, the same as everywhere else a
     * one-sided exercise's two cards are told apart, so taking one hand's card out of a
     * workout does not leave the other hand's rest untouched by mistake, and does not touch
     * the OTHER hand's own countdown either.
     */
    fun dismissFloor(exerciseId: Long, side: HoldSide? = null) = timer.floors.dismiss(exerciseId, side?.code)

    /** Both cards of it, when the exercise itself is what is going — see [FloorController.dismissAllOf]. */
    fun dismissFloorsOf(exerciseId: Long) = timer.floors.dismissAllOf(exerciseId)

    /**
     * Removes a whole SINGLE-entry card of a day: its rows, and the rest its exercise may still
     * be counting.
     *
     * The two halves are one act because they were one object on the screen. Deleting the rows
     * and leaving the countdown was the shape of §23.A2 — a bar, a notification and eventually a
     * beep for something the log no longer contains.
     */
    fun deleteSingleEntries(eventIds: List<Long>, exerciseId: Long?) {
        exerciseId?.let { dismissFloorsOf(it) }
        deleteEntries(eventIds)
    }

    /**
     * The plate answered on the way INTO the set that is running, or null when none was.
     *
     * ── Why it is held here and not in the run ──────────────────────────────────
     * It is the answer to a question the USER was asked, and its only job is to be the number
     * the offer at the end arrives prefilled with. Putting it in [RunSnapshot] would mean a
     * schema change to the stored run for a value nothing about the running of the set uses.
     *
     * The cost, stated: this does NOT survive the process. A set conducted while the app is
     * killed and rebuilt from the alarm comes back with the offer prefilled from the last
     * logged set instead of from the answer given at the start — the behaviour before the
     * question existed, so nothing is worse than it was, but the answer is quietly lost.
     */
    private val _entryAddedKg = MutableStateFlow<Double?>(null)
    val entryAddedKg: StateFlow<Double?> = _entryAddedKg.asStateFlow()

    /**
     * The one-tap program for a hangboard exercise: its work:rest protocol is already on
     * the catalog row, the rep count comes from the last set of it that was logged, the set
     * count from the settings, and the pause between sets from what was actually rested.
     *
     * Takes a single [ProgramStart] rather than the exercise, the plate and the side as three
     * loose parameters — see that type's own KDoc for why. In short: with three independent
     * parameters, two of them defaulted to null, a caller could supply the exercise and
     * quietly skip the other two, which is exactly what let a standalone one-sided run start
     * with no side and vanish from both hands' records. A [ProgramStart] cannot be built
     * without an answer for [ProgramStart.side], even when that answer is "there is no side
     * to answer for".
     *
     * [ProgramStart.addedKg] is the one thing that can be asked first, and only when there is
     * a reason to (§13.5) — the caller decides that, because the caller is the one that would
     * be putting the extra screen in front of the user.
     *
     * [ProgramStart.side] names the CARD this run was started from, for an exercise trained
     * one limb at a time — the same answer the manual entry form is handed as `fixedSide`. It
     * travels with the run (`RunSnapshot.side`) and comes back out on every set the run's
     * offer writes ([logRunSets]), which is what makes the two cards of a protocol-led
     * one-sided exercise lead to two distinguishable runs instead of one that forgets which
     * hand it was.
     */
    fun startProgramForExercise(start: ProgramStart) {
        _entryAddedKg.value = start.addedKg

        /*
         * A STRICT SCHEDULE PLAYS ITSELF (§18.15), and it does so before anything below is
         * read. The schedule fixes every temporal thing about the run — which efforts, how
         * long, in what order, with what pauses, how many repeats, how many sets — so the rep
         * count off the last logged set, the set count off the settings and the pause off the
         * journal have no say in it. They are not merely overridden here, they are never
         * fetched: the one variable left before a strict run is the plate, and the caller has
         * already answered that.
         */
        scheduledRun(start.exercise)?.let { schedule ->
            timer.start(schedule, start.exercise.id, RunOrigin.EXERCISE, start.side)
            return
        }

        viewModelScope.launch {
            val events = repo.allEvents()
            val settings = timerSettings.value
            val reps = lastHoldSet(events, start.exercise.link)?.reps ?: DEFAULT_HOLD_REPS
            val program = programFromExercise(
                exercise = start.exercise,
                reps = reps,
                sets = settings.defaultSets,
                restBetweenSetsSec = resolveRestSec(settings, events, start.exercise.id),
                prepareSec = settings.prepareSec,
            ) ?: return@launch
            timer.start(program, start.exercise.id, RunOrigin.EXERCISE, start.side)
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
    fun runProgram(program: WorkoutProgram): Unit {
        // nobody was asked about a plate for this one, and the previous answer belongs to a
        // run that is now over
        _entryAddedKg.value = null
        timer.start(
            program = program,
            exerciseId = program.exerciseId,
            origin = if (program.exerciseId != null) RunOrigin.EXERCISE else RunOrigin.PROGRAM,
        )
    }

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

    /** Keeps a program out of the library list, or brings it back — see `ProgramEntity.hidden`. */
    fun setProgramHidden(id: Long, hidden: Boolean) {
        viewModelScope.launch { programRepo.setHidden(id, hidden) }
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
    fun dismissRunOutcome() {
        _entryAddedKg.value = null
        timer.clearOutcome()
    }

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
        // the question has been answered for good: whatever the user typed into the offer is
        // the weight now, and the entry answer must not prefill the NEXT run
        _entryAddedKg.value = null
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
                holdSetsFromRun(exercise, day, sets, addedKg, outcome?.sideOf).forEach {
                    written += repo.record(it)
                }
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
