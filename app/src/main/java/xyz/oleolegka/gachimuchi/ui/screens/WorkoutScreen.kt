package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.Workout
import xyz.oleolegka.gachimuchi.domain.WorkoutExercise
import xyz.oleolegka.gachimuchi.domain.activityName
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.cardKey
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.label
import xyz.oleolegka.gachimuchi.ui.components.DashedNote
import xyz.oleolegka.gachimuchi.ui.components.EntryBlock
import xyz.oleolegka.gachimuchi.ui.components.EntryEditorDialog
import xyz.oleolegka.gachimuchi.ui.components.NameDialog
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * One workout, opened to be looked at: what was in it, in which order, with which sets and
 * which records.
 *
 * ── Where an entry is corrected and removed (§13.6, §14.1) ──────────────────────
 * This is that screen. Every set is its own row, and A LONG PRESS ON THE ROW raises what can
 * be done with it: correcting it (which opens [EntryEditorDialog] on its values and its day)
 * and removing it, behind one more question.
 *
 * It used to be an "Edit" button on every row with the removal hidden inside the dialog behind
 * it. That was two ways to reach one act and a button on a row that is read far more often than
 * it is corrected; the gesture replaces both — see [ItemActions] for why a long press and not a
 * bin. The dialog no longer removes anything, so there is exactly one path to a deletion.
 *
 * ANY entry, not only the newest. "Undo last" on the logging screen could only ever reach the
 * top of the pile, which is no use at all for the set from Tuesday that was typed as 60 when
 * it was 65. Nothing is rewritten to make it work: a correction and a removal are both new
 * events naming the old one, and `domain/Amendments.kt` folds them for every reader at once —
 * so the records, the charts, the calendar and the heatmap agree by construction rather than
 * by four screens remembering to ask.
 *
 * The exercise an entry belongs to is NOT editable here; see the dialog for why that is a rule
 * and not a gap.
 *
 * ── The workout in progress is reached from here too ────────────────────────────
 * When this is the open workout, the screen carries "Continue", which leads to the entry
 * card. That is the same action the day card offers; having it in both places means opening
 * a workout to check what is in it is never a dead end you have to back out of.
 *
 * ── Naming and reopening a workout, from the workout itself ─────────────────────
 * The top bar also carries "Rename" (the same dialog the day card's long press already opened,
 * now reachable without leaving this screen) and, once the workout is finished, "Reopen" — the
 * whole-workout twin of a card's own "Back to active". Neither is a lock in reverse: reopening
 * undoes only the mark, not anything recorded while it stood.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    state: UiState,
    workoutId: Long,
    onContinue: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Correct an entry: the whole form as it should now read. Appends, never rewrites. */
    onAmendEntry: (eventId: Long, updated: ActivityForm) -> Unit = { _, _ -> },
    /** Remove an entry — any of them, not only the newest. */
    onDeleteEntry: (eventId: Long) -> Unit = {},
    /** Name this workout, or clear its name with null — the same write the day card offers. */
    onRenameWorkout: (workoutId: Long, name: String?) -> Unit = { _, _ -> },
    /**
     * Undo the workout's own "finished" mark, putting it back in progress. Null hides the
     * control entirely rather than disabling it: a caller with nothing to wire it to (a
     * read-only export view, say) should not offer a button that can never do anything.
     */
    onUnfinishWorkout: ((eventId: Long) -> Unit)? = null,
) {
    val colors = LocalGachiColors.current
    val workout = remember(state.events, workoutId) { buildWorkout(state.events, workoutId) }

    /**
     * The entry whose editor is open, held by EVENT ID rather than by the entry itself: the
     * journal is re-folded after every write, so a held copy would be the pre-correction one.
     */
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }

    /** Whether the rename dialog is on screen — see [NameDialog] below. */
    var renaming by rememberSaveable { mutableStateOf(false) }

    if (workout == null) {
        // the journal no longer has it (a wipe, a reseed): say so rather than draw nothing
        Column(modifier.padding(24.dp)) {
            Text("This workout is no longer in the journal.", color = colors.inkSecondary)
        }
        return
    }

    // the same record verdicts the day cards and the logging feed use, looked up by event id
    val recordOf = remember(state.events, workout.opDate) {
        buildSession(state.events, workout.opDate).groups
            .flatMap { it.sets }
            .associate { it.eventId to it.record }
    }
    val date = remember(workout.opDate) { runCatching { LocalDate.parse(workout.opDate) }.getOrNull() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // the snapshot taken when the workout was started, never the plan's
                        // name as it reads today -- see WorkoutStarted
                        Text(
                            workout.name ?: "Workout",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            listOfNotNull(date?.let { fmtWeekdayDay(it) }, summaryOf(workout))
                                .joinToString(" - "),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the day")
                    }
                },
                actions = {
                    // reachable from the workout's OWN screen now, not only from a long press
                    // on its card on Today — see the class KDoc
                    TextButton(onClick = { renaming = true }) {
                        Text(if (workout.name == null) "Name it" else "Rename")
                    }
                    // a status, not a lock (§13): the button undoes the mark, not anything it
                    // recorded, and only appears once there is a mark to undo
                    if (workout.finished) {
                        TextButton(
                            onClick = { workout.finishedEventId?.let { onUnfinishWorkout?.invoke(it) } },
                            enabled = onUnfinishWorkout != null,
                        ) { Text("Reopen") }
                    }
                },
            )
        },
        bottomBar = {
            /*
             * Scaffold gives the bottom bar slot no window insets of its own: it only turns
             * whatever this composes to into the content's bottom padding (see Material3's
             * Scaffold source — `bottom = bottomBarHeight ?: insets`, and that fallback to
             * insets only fires when NOTHING is composed here at all). So the navigation bar is
             * read here, once, whichever branch runs — including the one below with no button,
             * where skipping it would leave the list's own fixed bottom padding as the only
             * thing between the last row and the system bar on three-button navigation.
             */
            if (onContinue != null) {
                Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 15.dp, vertical = 10.dp)
                            .heightIn(min = 52.dp),
                    ) { Text("Continue this workout", style = MaterialTheme.typography.titleMedium) }
                }
            } else {
                Spacer(Modifier.navigationBarsPadding())
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (workout.isEmpty) {
                // the sentence follows the button at the bottom, and stops where the button
                // stops: a finished workout with nothing in it has no "carry on" to offer
                item {
                    DashedNote(
                        if (onContinue != null) {
                            "Nothing recorded yet. Continue the workout and the first set lands here."
                        } else {
                            "Nothing was recorded in this workout."
                        }
                    )
                }
            }

            items(workout.exercises.size, key = { workout.exercises[it].cardKey }) { index ->
                val exercise = workout.exercises[index]
                EntryBlock(
                    name = exerciseName(state, exercise),
                    restSec = exercise.restSec,
                    entries = exercise.sets,
                    recordOf = recordOf,
                    onCorrect = { editing = it },
                    onRemove = onDeleteEntry,
                )
            }

            if (workout.entriesWithoutExercise.isNotEmpty()) {
                item {
                    /*
                     * A weigh-in carries no exercise_id by design, so it belongs to the
                     * workout without belonging to any of its exercises. It is shown rather
                     * than dropped: an entry that exists in the journal and appears on no
                     * screen is how a record silently stops existing.
                     */
                    EntryBlock(
                        name = "Other entries",
                        restSec = null,
                        entries = workout.entriesWithoutExercise,
                        recordOf = recordOf,
                        onCorrect = { editing = it },
                        onRemove = onDeleteEntry,
                    )
                }
            }
        }
    }

    /*
     * Resolved out of the workout every recomposition rather than captured when the row was
     * tapped, so the dialog shows the entry as the journal currently reads it. An entry that
     * disappeared from under the dialog (deleted, or the workout re-folded without it) simply
     * closes it — the `let` falls through — instead of editing something that is no longer there.
     */
    editing?.let { eventId ->
        val entry = (workout.exercises.flatMap { it.sets } + workout.entriesWithoutExercise)
            .firstOrNull { it.id == eventId }
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

    if (renaming) {
        NameDialog(
            title = if (workout.name == null) "Name this workout" else "Rename this workout",
            label = "Name (optional)",
            initial = workout.name.orEmpty(),
            confirmLabel = "Save",
            note = "Leave it empty and the card goes back to showing the time of day.",
            onConfirm = { name ->
                renaming = false
                onRenameWorkout(workoutId, name)
            },
            onDismiss = { renaming = false },
        )
    }
}

/** "3 exercises, 11 sets", or what an empty workout has instead. */
private fun summaryOf(workout: Workout): String {
    if (workout.isEmpty) return "nothing recorded yet"
    val exercises = workout.exercises.size
    val sets = workout.setCount
    return "$exercises ${if (exercises == 1) "exercise" else "exercises"}, " +
        "$sets ${if (sets == 1) "set" else "sets"}"
}

/**
 * The exercise's name — with the hand appended, for the exercise trained one limb at a time
 * that this block is one half of; see [xyz.oleolegka.gachimuchi.ui.screens.WorkoutLogScreen]'s
 * copy of this same helper for why two blocks of one exercise need telling apart at all.
 *
 * The catalog first, because that is the name the user maintains. The set's own payload is
 * the fallback and it matters: the journal outlives the catalog (an exercise can be deleted
 * while its history stays), and a block headed "exercise 14" is a workout you cannot read.
 */
private fun exerciseName(state: UiState, exercise: WorkoutExercise): String {
    val name = state.exerciseById(exercise.exerciseId)?.name
        ?: exercise.sets.firstOrNull()?.form?.activityName()
        ?: "Exercise ${exercise.exerciseId}"
    return exercise.side?.let { "$name - ${it.label()}" } ?: name
}
