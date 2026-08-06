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
import xyz.oleolegka.gachimuchi.data.db.AliasEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.seed.DemoSeed
import xyz.oleolegka.gachimuchi.data.toRef
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.Slot
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

class MainViewModel(private val repo: ActivityRepository) : ViewModel() {

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

    /** Appends a set to the journal. One event = one set; nothing is ever updated. */
    fun addSet(form: ActivityForm) {
        viewModelScope.launch { repo.record(form) }
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

    class Factory(private val repo: ActivityRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
    }
}
