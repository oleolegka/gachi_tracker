package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.ActivityEvent
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.RecordHit
import xyz.oleolegka.gachimuchi.domain.Workout
import xyz.oleolegka.gachimuchi.domain.WorkoutExercise
import xyz.oleolegka.gachimuchi.domain.activityName
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.DashedNote
import xyz.oleolegka.gachimuchi.ui.components.EntryEditorDialog
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.fmtDuration
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * One workout, opened to be looked at: what was in it, in which order, with which sets and
 * which records.
 *
 * ── Where an entry is corrected and removed (§13.6) ─────────────────────────────
 * This is that screen. Every set is its own row, and the gap the layout was holding open on
 * the right now carries "Edit", which raises [EntryEditorDialog] — values, the day, and a
 * removal behind one more question.
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
) {
    val colors = LocalGachiColors.current
    val workout = remember(state.events, workoutId) { buildWorkout(state.events, workoutId) }

    /**
     * The entry whose editor is open, held by EVENT ID rather than by the entry itself: the
     * journal is re-folded after every write, so a held copy would be the pre-correction one.
     */
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }

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
            )
        },
        bottomBar = {
            if (onContinue != null) {
                Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 15.dp, vertical = 10.dp)
                            .heightIn(min = 52.dp),
                    ) { Text("Continue this workout", style = MaterialTheme.typography.titleMedium) }
                }
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

            items(workout.exercises.size, key = { workout.exercises[it].exercise.key }) { index ->
                val exercise = workout.exercises[index]
                ExerciseBlock(
                    name = exerciseName(state, exercise),
                    restSec = exercise.restSec,
                    sets = exercise.sets,
                    recordOf = recordOf,
                    onEdit = { editing = it },
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
                    ExerciseBlock(
                        name = "Other entries",
                        restSec = null,
                        sets = workout.entriesWithoutExercise,
                        recordOf = recordOf,
                        onEdit = { editing = it },
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
            onDelete = {
                onDeleteEntry(eventId)
                editing = null
            },
            onDismiss = { editing = null },
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
 * The exercise's name.
 *
 * The catalog first, because that is the name the user maintains. The set's own payload is
 * the fallback and it matters: the journal outlives the catalog (an exercise can be deleted
 * while its history stays), and a block headed "exercise 14" is a workout you cannot read.
 */
private fun exerciseName(state: UiState, exercise: WorkoutExercise): String =
    state.exerciseById(exercise.exerciseId)?.name
        ?: exercise.sets.firstOrNull()?.form?.activityName()
        ?: "Exercise ${exercise.exerciseId}"

@Composable
private fun ExerciseBlock(
    name: String,
    restSec: Int?,
    sets: List<ActivityEvent>,
    recordOf: Map<Long, RecordHit?>,
    onEdit: (eventId: Long) -> Unit,
) {
    val colors = LocalGachiColors.current
    GachiCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // the rest CHOSEN for this exercise in this workout, which is a different fact
            // from the pause the timestamps happen to show (see WorkoutExerciseAdded)
            restSec?.takeIf { it > 0 }?.let {
                Text("rest ${fmtDuration(it)}", fontSize = 11.sp, color = colors.inkMuted)
            }
        }
        HorizontalDivider(color = colors.grid)

        if (sets.isEmpty()) {
            Text(
                "no sets yet",
                fontSize = 12.sp,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            )
            return@GachiCard
        }

        sets.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = colors.grid)
            SetLine(
                number = index + 1,
                entry = entry,
                record = recordOf[entry.id],
                onEdit = { onEdit(entry.id) },
            )
        }
    }
}

/**
 * One recorded set, with the way to correct it.
 *
 * The numbers are on the left and the clock time on the right; "Edit" sits between them, in
 * the gap this row was laid out loose to hold. It is a plain text button and not an icon
 * because it is pressed rarely and has to be unambiguous when it is — and there is no delete
 * beside it, deliberately: removing training is one level in, behind the editor, so that a
 * mis-tap on a list of finished sets cannot do it.
 */
@Composable
private fun SetLine(
    number: Int,
    entry: ActivityEvent,
    record: RecordHit?,
    onEdit: () -> Unit,
) {
    val colors = LocalGachiColors.current
    Column(Modifier.fillMaxWidth().padding(start = 13.dp, end = 4.dp, top = 9.dp, bottom = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$number",
                fontSize = 10.sp,
                color = colors.inkMuted,
                modifier = Modifier.width(20.dp),
            )
            Text(
                entry.form.summaryLine(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            clockOf(entry)?.let { Text(it, fontSize = 10.sp, color = colors.inkMuted) }
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Edit", fontSize = 11.sp)
            }
        }
        record?.let {
            Text(
                "Record: ${it.text}",
                fontSize = 11.sp,
                color = colors.good,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
    }
}

/**
 * "HH:mm" of the entry, but only when it was written on the day it is filed under.
 *
 * Old training typed up on the sofa carries the time it was TYPED, and printing that beside
 * a set done last Tuesday morning would be a plausible-looking lie. Same rule as the day
 * cards and the calendar's own stamps.
 */
private fun clockOf(entry: ActivityEvent): String? =
    if (entry.ts.length >= 16 && entry.ts.startsWith("${entry.opDate}T")) {
        entry.ts.substring(11, 16)
    } else {
        null
    }
