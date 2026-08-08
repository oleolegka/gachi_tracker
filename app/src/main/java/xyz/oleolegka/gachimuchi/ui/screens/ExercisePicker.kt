package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.exerciseUsage
import xyz.oleolegka.gachimuchi.domain.firstBlock
import xyz.oleolegka.gachimuchi.domain.matchesExerciseQuery
import xyz.oleolegka.gachimuchi.domain.parseNumber
import xyz.oleolegka.gachimuchi.domain.pickerOrder
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.fmtDay
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * Picking the exercise to log — a bottom sheet over the session, never a separate screen.
 *
 * A sheet rather than a route: the session feed stays visible behind it, the gesture to
 * dismiss is the system one, and switching exercises costs two taps (open, pick) instead
 * of a navigation there and back.
 *
 * The order is recency-first (see [pickerOrder]), so mid-workout the exercise you want is
 * usually in the first few rows and no typing happens at all. Search covers the rest and
 * matches the NAME, as a substring, so "bench" finds "Bench press".
 *
 * There is DELIBERATELY no fuzzy matching and no "did you mean": §11 settled that an unknown
 * word must never be guessed at. Either you pick from the list or you create a new exercise
 * on purpose.
 *
 * ── A search that finds nothing shows nothing ───────────────────────────────────
 * It used to show the WHOLE catalog instead, and the reason was not about searching at all:
 * the app learned synonyms by watching which row was tapped while a word was in this box, so
 * a list narrowed to nothing was the one state in which the word could never be taught. The
 * synonyms are gone (domain/Session.kt), and with them that reason.
 *
 * So the list now says what it means. The two exits it used to hide are named out loud
 * instead — clear the search, or create the exercise — because they are not the same move
 * and the difference matters: one finds an exercise that already has a history, the other
 * starts a second one beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    state: UiState,
    today: LocalDate,
    onPick: (Long) -> Unit,
    /**
     * Creating a new catalog exercise, or NULL for a caller that only picks from what is
     * already there — the slot editor, which plans sessions out of exercises the user
     * already trains and has no business asking §12-A identity questions (form, protocol)
     * days before the first set.
     *
     * Nullable rather than defaulted, so that every caller has to say which it is: a create
     * button wired to nothing is exactly the failure this is meant to make impossible.
     */
    onCreate: ((String, ExerciseForm, Double?, Double?) -> Unit)?,
    onDismiss: () -> Unit,
    /**
     * Skip the list and open on the create form. Set when the catalog is empty: a search
     * box above an empty list is a dead end dressed up as a choice, and the only useful
     * action on it would be the button underneath.
     */
    startInCreate: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    // a caller that cannot create can never open on the create form, whatever it asked for
    var creating by rememberSaveable { mutableStateOf(startInCreate && onCreate != null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (creating && onCreate != null) {
                CreateExerciseForm(
                    initialName = query,
                    onCancel = { creating = false },
                    onCreate = { name, form, work, rest ->
                        onCreate(name, form, work, rest)
                        creating = false
                        onDismiss()
                    },
                )
            } else {
                PickExisting(
                    state = state,
                    today = today,
                    query = query,
                    onQuery = { query = it },
                    onPick = { id ->
                        onPick(id)
                        onDismiss()
                    },
                    onNew = onCreate?.let { { creating = true } },
                )
            }
        }
    }
}

@Composable
private fun PickExisting(
    state: UiState,
    today: LocalDate,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (Long) -> Unit,
    /** Null when this caller does not create exercises — see [ExercisePickerSheet]. */
    onNew: (() -> Unit)?,
) {
    val colors = LocalGachiColors.current
    val order = remember(state.events) { pickerOrder(exerciseUsage(state.events)) }
    val usage = remember(state.events) { exerciseUsage(state.events) }
    /*
     * Hidden exercises are dropped HERE and only here, which is the whole of what hiding does:
     * this is the list you pick from, and an exercise you stopped training is clutter in it and
     * nowhere else. The row itself is untouched — its sets, records, charts and totals go on
     * exactly as before, and the overview still lists it, which is where it is brought back
     * from (see ActivityRepository.setHidden).
     */
    val visible = remember(state.exercises) { state.exercises.filter { !it.hidden } }
    val items = remember(visible, query, order) {
        visible
            .filter { matchesExerciseQuery(it.name, query) }
            .sortedWith { a, b -> order.compare(a.id, b.id) }
    }

    val catalogEmpty = visible.isEmpty()
    val searching = query.isNotBlank()

    Text("Exercise", style = MaterialTheme.typography.titleMedium)
    // nothing to search through on a first run, and a search box would only invite typing
    // that can never match
    if (!catalogEmpty) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            // the way back to the whole catalog, which used to happen by itself when a
            // search matched nothing; now that the list stays narrowed it has to be reachable
            // without deleting the word character by character
            trailingIcon = {
                if (searching) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear the search")
                    }
                }
            },
            placeholder = { Text("Search by name") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
    }

    if (onNew != null) {
        Button(
            onClick = onNew,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(if (catalogEmpty) "  Create your first exercise" else "  New exercise")
        }
    }

    HorizontalDivider()

    /*
     * An empty list has to say WHY it is empty, and the two reasons want opposite sentences:
     * a catalog with nothing in it is a first run, while a search that found nothing is a
     * list that has been narrowed and can be un-narrowed. Saying "nothing here" to both
     * would leave the second one looking like a dead end when the way out is one tap away.
     */
    if (items.isEmpty()) {
        Text(
            when {
                /*
                 * Without a way to create, every one of these has to stop offering one. The
                 * sentences are otherwise identical, because the situation is: the difference
                 * is only in which exits exist from it, and naming an exit that is not on the
                 * screen is how a dead end gets dressed up as a choice.
                 */
                catalogEmpty && onNew == null ->
                    "Nothing in the catalog yet. Exercises are created while logging a workout; " +
                        "once one exists it can be planned here."

                catalogEmpty ->
                    "Nothing in the catalog yet. An exercise is created once and then reused " +
                        "for every set of it."

                onNew == null ->
                    "No exercise is called \"$query\". Clear the search to see the whole catalog."

                else ->
                    "No exercise is called \"$query\". Clear the search to see the whole " +
                        "catalog, or create it as a new exercise with a history of its own."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(items, key = { it.id }) { exercise ->
            val form = runCatching { ExerciseForm.fromCode(exercise.form) }.getOrNull()
            val protocolBlock = exercise.protocolProgramId?.let { state.programsById[it] }?.firstBlock()
            val used = usage[exercise.id]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(exercise.id) }
                    .heightIn(min = 56.dp)
                    .padding(vertical = 8.dp),
            ) {
                Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(form?.title ?: "unknown form")
                        /*
                         * The protocol is on the row because it is what makes two rows of the
                         * same NAME two exercises (§12-A). It used to be unnecessary here for a
                         * bad reason: creating an exercise deduplicated by name, so a second
                         * "Hangs" on another protocol could not exist — it was silently handed
                         * the first one's history. Now that it can exist, a list showing two
                         * identical lines would put the same failure back one step later, with
                         * the user picking whichever of the two came first.
                         */
                        if (protocolBlock != null) {
                            append(" - ${protocolBlock.workSec}:${protocolBlock.restSec}")
                        }
                        if (used == null) {
                            append(" - not logged yet")
                        } else {
                            append(" - ${used.count} entries, last on ")
                            append(fmtDay(runCatching { LocalDate.parse(used.lastDate) }.getOrDefault(today)))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}

/**
 * Creating an exercise. The form is asked ONCE, here, and never again (§11): it is part
 * of the exercise, not of a set. For holds the work:rest protocol is asked in the same
 * breath, because §12-A makes it part of the identity — the same hangs on a different
 * protocol are a DIFFERENT exercise with its own history and its own record.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateExerciseForm(
    initialName: String,
    onCancel: () -> Unit,
    onCreate: (String, ExerciseForm, Double?, Double?) -> Unit,
) {
    val colors = LocalGachiColors.current
    var name by rememberSaveable { mutableStateOf(initialName) }
    var form by rememberSaveable { mutableStateOf(ExerciseForm.STRENGTH) }
    var work by rememberSaveable { mutableStateOf("") }
    var rest by rememberSaveable { mutableStateOf("") }

    Text("New exercise", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Name") },
    )

    Text("Form", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExerciseForm.entries.forEach { candidate ->
            FilterChip(
                selected = form == candidate,
                onClick = { form = candidate },
                label = { Text(candidate.title) },
            )
        }
    }

    if (form == ExerciseForm.HOLD) {
        Text(
            "The work:rest protocol is part of the identity: hangs on another protocol are " +
                "a separate exercise with a separate record.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = work, onValueChange = { work = it }, modifier = Modifier.weight(1f),
                singleLine = true, label = { Text("Work, s") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = rest, onValueChange = { rest = it }, modifier = Modifier.weight(1f),
                singleLine = true, label = { Text("Rest, s") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        Button(
            onClick = {
                val hold = form == ExerciseForm.HOLD
                /*
                 * Every number here is "positive, or it was never filled in". A zero (or a
                 * minus, which some keyboards offer on the decimal layout) is not a 0-second
                 * hang, it is an empty field with a character in it — and the HoldSet
                 * validator rejects a non-positive protocol by throwing, which on the
                 * logging screen would surface as a crash on the Add button rather than as
                 * a message. Stored as null, the field simply stays unset, which is a state
                 * the whole app already handles.
                 *
                 * The protocol is a pair or nothing at all: half of it would be rejected by
                 * the same validator on the very first set.
                 */
                val w = if (hold) parseNumber(work)?.takeIf { it > 0 } else null
                val r = if (hold) parseNumber(rest)?.takeIf { it > 0 } else null
                val pair = if (w != null && r != null) w to r else null
                onCreate(name.trim(), form, pair?.first, pair?.second)
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) { Text("Create and use") }
    }
}
