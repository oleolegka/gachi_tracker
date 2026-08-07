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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import xyz.oleolegka.gachimuchi.domain.matchesExerciseQuery
import xyz.oleolegka.gachimuchi.domain.offersWholeCatalog
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
 * matches ALIASES as well as names (§11), which is what makes "bench" find "Bench press".
 *
 * There is DELIBERATELY no fuzzy matching or "did you mean": §11 settled that an unknown
 * word must never be guessed at. Either you pick from the list or you create a new
 * exercise on purpose.
 *
 * Which is why a word that matches NOTHING falls back to the whole catalog rather than to
 * an empty list (see [xyz.oleolegka.gachimuchi.domain.offersWholeCatalog]). That is the
 * opposite of guessing: nothing is preselected, and it is the only moment at which the
 * alias mechanism can be used at all, because a word is learned by being in the search box
 * when an exercise is tapped. A list narrowed to nothing removes every exercise the word
 * could be attached to and leaves "create a new one" as the only exit — which is how a
 * second "Bench press" gets into a catalog that already has one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    state: UiState,
    today: LocalDate,
    onPick: (Long, String?) -> Unit,
    /**
     * Creating a new catalog exercise, or NULL for a caller that only picks from what is
     * already there — the slot editor, which plans sessions out of exercises the user
     * already trains and has no business asking §12-A identity questions (form, edge,
     * protocol) days before the first set.
     *
     * Nullable rather than defaulted, so that every caller has to say which it is: a create
     * button wired to nothing is exactly the failure this is meant to make impossible.
     */
    onCreate: ((String, ExerciseForm, Double?, Double?, Double?) -> Unit)?,
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
                    onCreate = { name, form, edge, work, rest ->
                        onCreate(name, form, edge, work, rest)
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
                        onPick(id, query.takeIf { it.isNotBlank() })
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
     * A word that matches nothing falls back to the whole catalog rather than to an empty
     * list — that is where a synonym is taught (see [offersWholeCatalog]). The flag is kept
     * so the text below can say why the list did not narrow.
     */
    val teaching = remember(state.exercises, state.aliases, query) {
        val matches = state.exercises.count { matchesExerciseQuery(it.name, state.aliasesOf(it.id), query) }
        offersWholeCatalog(query, matches, state.exercises.size)
    }
    val items = remember(state.exercises, state.aliases, query, order, teaching) {
        state.exercises
            .filter { teaching || matchesExerciseQuery(it.name, state.aliasesOf(it.id), query) }
            .sortedWith { a, b -> order.compare(a.id, b.id) }
    }

    val catalogEmpty = state.exercises.isEmpty()

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
            placeholder = { Text("Search by name or alias") },
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
     * The list did not narrow, and the reason has to be said: an unexplained full list
     * after typing reads as a broken search. It is also the moment the two ways forward
     * have to be spelled out, because they are not interchangeable — tapping teaches the
     * word to something that already exists, creating starts a separate history.
     */
    if (teaching || items.isEmpty()) {
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

                teaching && onNew == null ->
                    "Nothing is called \"$query\" yet. Tap the exercise you mean and the word " +
                        "becomes one of its names, so it finds it next time."

                teaching ->
                    "Nothing is called \"$query\" yet. Tap the exercise you mean and the word " +
                        "becomes one of its names, so it finds it next time - or create it as a " +
                        "new exercise, with a history of its own."

                onNew == null -> "Nothing matches."

                else ->
                    "Nothing matches. Create it as a new exercise, and the typed word becomes " +
                        "its alias."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(items, key = { it.id }) { exercise ->
            val form = runCatching { ExerciseForm.fromCode(exercise.form) }.getOrNull()
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
 * of the exercise, not of a set. For holds the edge and the work:rest protocol are asked
 * in the same breath, because §12-A makes them part of the identity — the same hangs on a
 * 15 mm edge are a DIFFERENT exercise with its own history and its own record.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateExerciseForm(
    initialName: String,
    onCancel: () -> Unit,
    onCreate: (String, ExerciseForm, Double?, Double?, Double?) -> Unit,
) {
    val colors = LocalGachiColors.current
    var name by rememberSaveable { mutableStateOf(initialName) }
    var form by rememberSaveable { mutableStateOf(ExerciseForm.STRENGTH) }
    var edge by rememberSaveable { mutableStateOf("") }
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
            "Edge and protocol are part of the identity: hangs on another edge or another " +
                "work:rest are a separate exercise with a separate record.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = edge, onValueChange = { edge = it }, modifier = Modifier.weight(1f),
                singleLine = true, label = { Text("Edge, mm") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
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
                 * minus, which some keyboards offer on the decimal layout) is not a 0 mm
                 * edge or a 0-second hang, it is an empty field with a character in it —
                 * and the HoldSet validator rejects a non-positive edge or protocol by
                 * throwing, which on the logging screen would surface as a crash on the Add
                 * button rather than as a message. Stored as null, the field simply stays
                 * unset, which is a state the whole app already handles.
                 *
                 * The protocol is a pair or nothing at all: half of it would be rejected by
                 * the same validator on the very first set.
                 */
                val w = if (hold) parseNumber(work)?.takeIf { it > 0 } else null
                val r = if (hold) parseNumber(rest)?.takeIf { it > 0 } else null
                val pair = if (w != null && r != null) w to r else null
                onCreate(
                    name.trim(), form,
                    if (hold) parseNumber(edge)?.takeIf { it > 0 } else null,
                    pair?.first, pair?.second,
                )
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) { Text("Create and use") }
    }
}
