package xyz.oleolegka.gachimuchi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.seed.DemoSeed
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.Slot
import java.time.LocalDate

/**
 * Screen state: the whole journal, catalog and slot list, with the reducers applied on
 * the spot by the domain functions. Per-screen slicing lives in the screens themselves —
 * there is very little data (a personal diary), and an extra "view state per screen"
 * layer would only get in the way.
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
}

class MainViewModel(private val repo: ActivityRepository) : ViewModel() {

    private val seeding = MutableStateFlow(false)

    val state: StateFlow<UiState> =
        combine(repo.events, repo.exercises, repo.slots, seeding) { events, exercises, slots, busy ->
            UiState(events, exercises, slots, loading = busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** One "today" for every screen: the calendar and the Today tab must not drift apart. */
    val today: LocalDate = LocalDate.now()

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
