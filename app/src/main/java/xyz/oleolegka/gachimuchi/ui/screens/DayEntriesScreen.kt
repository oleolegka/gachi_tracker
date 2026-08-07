package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.ActivityEvent
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.activityName
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.exerciseLink
import xyz.oleolegka.gachimuchi.domain.setsOutsideWorkouts
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.DashedNote
import xyz.oleolegka.gachimuchi.ui.components.EntryBlock
import xyz.oleolegka.gachimuchi.ui.components.EntryEditorDialog
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * ONE exercise on ONE day, in full: every set, its weight, its reps, the time it was done at,
 * how long after the previous one, and any record it broke.
 *
 * ── The question this screen exists to answer (§14.2) ───────────────────────────
 * A single-entry card used to lead straight to the all-time statistics of its exercise — a
 * period switch, a trend line and a volume chart. That is a real screen and it is the wrong
 * answer to the tap: what somebody who has just tapped "Fingerboard 20 mm, 3 entries" wants is
 * WHAT THEY DID JUST NOW, and the app had nowhere at all to show it. Charts of the last three
 * months answer a different question and answer it after you have stopped caring about this
 * one.
 *
 * The statistics are still one tap away, at the bottom, and that direction is the right way
 * round: the particular first, the summary behind it.
 *
 * ── What is on it, and what deliberately is not ─────────────────────────────────
 * The entries of this exercise recorded OUTSIDE any workout on this day — exactly the group the
 * card is a summary of. Sets of the SAME exercise done inside a workout that day are NOT here,
 * and that is not an oversight: they belong to the workout, they are already shown by its own
 * card, and pulling them in would make this screen disagree with the card that opened it about
 * how many entries there are.
 *
 * The rows are the workout review screen's rows ([EntryBlock]) and not a second set of them, so
 * a set reads the same wherever it is read back — and correcting or removing one works here for
 * the same reason it works there.
 *
 * ── The gaps are a measurement and say so ───────────────────────────────────────
 * There is no rest CHOSEN for an exercise logged on its own — that is a fact of a workout — so
 * the rows carry the pause actually taken between one entry and the next. It is measured from
 * when each was written down, which is a few seconds after the set ended and rather longer when
 * the phone stayed in a pocket; the wording ("after 2 min 5 s") is what keeps it from being
 * read as a rest that was set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayEntriesScreen(
    state: UiState,
    exerciseId: Long,
    /** The day the training belongs to, as the journal writes dates. */
    date: String,
    onOpenHistory: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Correct an entry: the whole form as it should now read. Appends, never rewrites. */
    onAmendEntry: (eventId: Long, updated: ActivityForm) -> Unit = { _, _ -> },
    /** Remove an entry. Already confirmed by the time it is called. */
    onDeleteEntry: (eventId: Long) -> Unit = {},
) {
    val colors = LocalGachiColors.current

    /*
     * Folded out of the journal on every change of it, like every other screen here, so a
     * correction made from this screen is visible on it without anything having to be told.
     */
    val entries = remember(state.events, exerciseId, date) {
        setsOutsideWorkouts(state.events, date)
            .filter { it.form.exerciseLink()?.id == exerciseId }
    }

    // the same record verdicts the day cards and the workout screen use, by event id
    val recordOf = remember(state.events, date) {
        buildSession(state.events, date).groups
            .flatMap { it.sets }
            .associate { it.eventId to it.record }
    }

    val day = remember(date) { runCatching { LocalDate.parse(date) }.getOrNull() }
    val name = exerciseName(state, exerciseId, entries)

    /** The entry whose editor is open, held by EVENT ID — see [WorkoutScreen] for why. */
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            day?.let { fmtWeekdayDay(it) } ?: date,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to the day",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entries.isEmpty()) {
                /*
                 * Reachable: the last entry can be removed from this very screen, and the
                 * journal can also have been wiped underneath it. Saying so beats an empty
                 * screen that looks like a screen that failed to load.
                 */
                item {
                    DashedNote(
                        "Nothing of this one is recorded outside a workout on this day any " +
                            "more. Anything done inside a workout is on the workout's own card."
                    )
                }
            } else {
                item {
                    EntryBlock(
                        // the exercise is named in the bar above, so the card heading carries
                        // what the bar does not: how much of it there is on this day
                        name = summaryOf(entries),
                        // there is no rest chosen for an entry logged on its own; what the
                        // rows carry instead is the pause actually taken
                        restSec = null,
                        entries = entries,
                        recordOf = recordOf,
                        showGaps = true,
                        onCorrect = { editing = it },
                        onRemove = onDeleteEntry,
                    )
                }
            }

            /*
             * The way on to the charts, at the BOTTOM and worded as a different question. It
             * used to be where the tap landed; it is now what the tap leads past, which is the
             * order the two are actually wanted in.
             */
            item {
                TextButton(
                    onClick = { onOpenHistory(exerciseId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("All-time history of $name") }
            }
        }
    }

    /*
     * Resolved out of the entries every recomposition rather than captured when the row was
     * pressed, so the dialog shows the entry as the journal currently reads it. One that
     * disappeared from under it simply closes it.
     */
    editing?.let { eventId ->
        val entry = entries.firstOrNull { it.id == eventId }
        if (entry == null) {
            editing = null
            return@let
        }
        EntryEditorDialog(
            entry = entry.form,
            oneSided = state.exerciseById(entry.form.exerciseId)?.oneSided == true,
            onAmend = { updated ->
                onAmendEntry(eventId, updated)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * The exercise's name.
 *
 * The catalog first, because that is the name the user maintains; the entries' own payload
 * second, because the journal outlives the catalog and a screen headed "Exercise 14" is one
 * you cannot read. The last fallback is reached only when the catalog has lost the row AND
 * every entry of it has just been removed.
 */
private fun exerciseName(state: UiState, exerciseId: Long, entries: List<ActivityEvent>): String =
    state.exerciseById(exerciseId)?.name
        ?: entries.firstOrNull()?.form?.activityName()
        ?: "Exercise $exerciseId"

/** "Outside a workout - 3 entries", in the words the card that opened this screen used. */
private fun summaryOf(entries: List<ActivityEvent>): String {
    val n = entries.size
    return "Outside a workout - $n ${if (n == 1) "entry" else "entries"}"
}
