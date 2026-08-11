package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.oleolegka.gachimuchi.data.ExercisePictureStore
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.PREPARE_DEFAULT_SEC
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.ScheduleKind
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.exerciseUsage
import xyz.oleolegka.gachimuchi.domain.knownCategories
import xyz.oleolegka.gachimuchi.domain.matchesExerciseQuery
import xyz.oleolegka.gachimuchi.domain.parseProtocolSeconds
import xyz.oleolegka.gachimuchi.domain.pickerOrder
import xyz.oleolegka.gachimuchi.domain.scheduleCaption
import xyz.oleolegka.gachimuchi.domain.scheduleKindOf
import xyz.oleolegka.gachimuchi.domain.scheduleSummary
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.celebrate.rememberPicture
import xyz.oleolegka.gachimuchi.ui.fmtDay
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * Everything the create form collected, in one value.
 *
 * ── Why a value and not five more parameters ───────────────────────────────────
 * The chain from the form to `ActivityRepository.ensureExercise` is four callbacks long
 * (sheet, screen, `GachiApp`, ViewModel) and every one of them used to spell out
 * `(String, ExerciseForm, Double?, Double?)`. Two of those are the same TYPE, so a sixth
 * question — is it one side at a time — could be added at the form and dropped anywhere
 * along the way without a single compile error. That is exactly how the switch came to
 * exist in the edit dialog and nowhere else (backlog §23.4). One value travelling the
 * whole way makes a dropped answer a missing field rather than a silent default.
 */
data class NewExercise(
    val name: String,
    val form: ExerciseForm,
    /** The protocol as two typed numbers, when a new one is being described. */
    val workSec: Double? = null,
    val restSec: Double? = null,
    /** §18.2: each side keeps its own record, and every set is asked which side it was. */
    val oneSided: Boolean = false,
    /**
     * An EXISTING library program to be led by, instead of [workSec]/[restSec] describing a
     * new one. Set only at creation — §18.9 freezes the protocol from then on — and the two
     * are exclusive: a picked program is the protocol, and nothing is invented beside it.
     */
    val protocolProgramId: Long? = null,
    /**
     * A schedule BUILT ON THE SPOT in the program editor, to be written to the library and
     * then pointed at — the strict branch of §18.15.
     *
     * A third alternative to the two above, and exclusive with both: an id names a schedule
     * that already exists, the two numbers describe the minimal one, and this one is a whole
     * [WorkoutProgram] that does not exist yet. It travels as a value rather than being saved
     * by the form, because the form must not leave a program behind in the library when the
     * user backs out of creating the exercise (see
     * [xyz.oleolegka.gachimuchi.ui.MainViewModel.createExercise], which is where it is stored,
     * one step before the exercise that references it).
     */
    val newProgram: WorkoutProgram? = null,
)

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
    onCreate: ((NewExercise) -> Unit)?,
    onDismiss: () -> Unit,
    /**
     * Skip the list and open on the create form. Set when the catalog is empty: a search
     * box above an empty list is a dead end dressed up as a choice, and the only useful
     * action on it would be the button underneath.
     */
    startInCreate: Boolean = false,
    /**
     * What the sheet is doing here, which is not the same question in every caller: logging
     * and planning both PICK something for a purpose, so "Exercise" and "Create and use" say
     * what happens next. A caller with no next step — browsing or growing the catalog on its
     * own — passes words that say only that: see [xyz.oleolegka.gachimuchi.ui.screens.OverviewScreen].
     */
    heading: String = "Exercise",
    createLabel: String = "Create and use",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    // a caller that cannot create can never open on the create form, whatever it asked for
    var creating by rememberSaveable { mutableStateOf(startInCreate && onCreate != null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val frame = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .imePadding()

        if (creating && onCreate != null) {
            /*
             * The create form SCROLLS and the pick list does not, which is why the two live in
             * separate containers rather than sharing one.
             *
             * The form grew past a phone screen the moment holds started choosing between three
             * branches (§18.15): name, form chips, the sides question, three explained cards and
             * then a schedule to build or pick. Without a scroll the confirm button is simply
             * below the glass, which is the "the feature was there and unreachable" failure this
             * project keeps hitting. The pick list cannot share this container because it is a
             * LazyColumn, and a lazily scrolling list nested inside a scrolling column is
             * measured with an unbounded height and crashes.
             */
            Column(
                modifier = frame.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CreateExerciseForm(
                    initialName = query,
                    // the library, as schedules a hold can be pointed at — see the form
                    programs = remember(state.programsById) {
                        state.programsById.values.sortedBy { it.name.lowercase() }
                    },
                    categories = remember(state.programsById) {
                        knownCategories(state.programsById.values.toList())
                    },
                    confirmLabel = createLabel,
                    onCancel = { creating = false },
                    onCreate = { new ->
                        onCreate(new)
                        creating = false
                        onDismiss()
                    },
                )
            }
        } else {
            Column(
                modifier = frame,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickExisting(
                    state = state,
                    today = today,
                    heading = heading,
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
    /** See [ExercisePickerSheet.heading]. */
    heading: String,
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

    Text(heading, style = MaterialTheme.typography.titleMedium)
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

    val context = LocalContext.current
    val pictureStore = remember(context) { ExercisePictureStore.get(context) }

    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(items, key = { it.id }) { exercise ->
            val form = runCatching { ExerciseForm.fromCode(exercise.form) }.getOrNull()
            val scheduleLine =
                scheduleCaption(exercise.protocolProgramId?.let { state.programsById[it] })
            val used = usage[exercise.id]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(exercise.id) }
                    .heightIn(min = 56.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                /*
                 * The whole point of a picture here (owner's own words): "on different machines
                 * the same weight feels very different" — recognising the specific rack or
                 * pulldown from last time. Absent for the common case (no picture), which is
                 * why the row is a Row only when there is one to show and a Column otherwise
                 * looks exactly as it always has, including its own click target and padding.
                 */
                exercise.pictureId?.let { pictureId ->
                    val bitmap = rememberPicture(pictureStore.fileOf(pictureId), PICKER_THUMB_MAX_PX)
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null, // decoration: the name beside it already says which exercise
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                Column {
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
                            if (scheduleLine != null) append(" - $scheduleLine")
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
}

/**
 * How large a decode the picker's row thumbnail asks for. Small on purpose — this is a list,
 * not the celebration overlay — and downsampled at DECODE time by [decodeScaled], so a multi
 * megabyte phone photo never exists as a full-size bitmap just to be shown at 44dp.
 */
private const val PICKER_THUMB_MAX_PX = 96

/**
 * Creating an exercise. The form is asked ONCE, here, and never again (§11): it is part
 * of the exercise, not of a set. For holds the work:rest protocol is asked in the same
 * breath, because §12-A makes it part of the identity — the same hangs on a different
 * protocol are a DIFFERENT exercise with its own history and its own record.
 *
 * ── "One side at a time" is asked HERE, not only in the edit dialog ────────────
 * It used to live only in the correction dialog of an EXISTING exercise, so one-sidedness
 * was unreachable at the one moment an exercise is described — and everything downstream
 * of it (two cards in a workout, a rest floor per side, a record per side) was dead for
 * every exercise as it was created. Reported from the phone, 2026-08-11, as the critical
 * one of four. Gated on the same forms the edit dialog gates it on: a hang and a pistol
 * squat are the same asymmetry, a run and a weigh-in have no sides.
 *
 * ── A hold says WHICH OF THREE it is, out loud (§18.15) ────────────────────────
 * This form used to ask a hold for two numbers, or let it point at a library program through
 * a row of chips, and never said what either meant. The owner, from the phone: "right now it
 * is not clear at all what I am choosing". The three things a hold can be were always there
 * in the data and were never named:
 *
 *   FREE          no schedule at all — nothing counts time, the duration is typed by hand;
 *   SIMPLE PAIR   one work and one rest, with the hold count and the set count asked before
 *                 every run, because the schedule does not carry them;
 *   STRICT        the whole timing fixed in advance — efforts, order, gaps, repeats, sets —
 *                 with nothing left to ask before a run but the weight.
 *
 * So the choice is now a card each, with one sentence saying what it is and when to take it,
 * and NOTHING is preselected: this is the one moment it can be answered (§18.9 freezes an
 * exercise's schedule from then on), so it is worth a deliberate tap rather than a default
 * nobody read.
 *
 * The strict branch reuses the library editor rather than growing a second one — see
 * [ScheduleBranch]'s note on the dialog, and [xyz.oleolegka.gachimuchi.ui.screens.
 * ProgramEditorScreen]'s `asSchedule`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateExerciseForm(
    initialName: String,
    /** The library, as schedules a hold can be pointed at. Programs with no usable first
     *  block are filtered out here — see [protocolCandidates]. */
    programs: List<WorkoutProgram>,
    /** Headings already in use, handed to the schedule editor so the same one is not
     *  spelled two ways. */
    categories: List<String>,
    /** See [ExercisePickerSheet.createLabel]. */
    confirmLabel: String,
    onCancel: () -> Unit,
    onCreate: (NewExercise) -> Unit,
) {
    val colors = LocalGachiColors.current
    var name by rememberSaveable { mutableStateOf(initialName) }
    var form by rememberSaveable { mutableStateOf(ExerciseForm.STRENGTH) }
    var work by rememberSaveable { mutableStateOf("") }
    var rest by rememberSaveable { mutableStateOf("") }
    var oneSided by rememberSaveable { mutableStateOf(false) }
    /** Which of the three §18.15 branches, or null while the question is still unanswered. */
    var kind by rememberSaveable { mutableStateOf<ScheduleKind?>(null) }
    /** A library schedule this hold is to be pointed at, in the strict branch. */
    var programId by rememberSaveable { mutableStateOf<Long?>(null) }
    /*
     * A schedule built in the editor and not yet stored anywhere. Plain `remember`, not
     * `rememberSaveable`: a WorkoutProgram is neither Parcelable nor java-Serializable, and
     * handing one to the saveable machinery throws rather than degrading. The cost is real and
     * worth naming — rotate the phone mid-build and the draft is gone — but it is a nested
     * value with a list of lists in it, and a Parcelable-shaped copy of the domain model is a
     * larger thing than this form should be growing.
     */
    var draft by remember { mutableStateOf<WorkoutProgram?>(null) }

    val hold = form == ExerciseForm.HOLD
    // the same gate the edit dialog applies: what is lifted has sides, what is timed or
    // ticked off does not
    val lifted = hold || form == ExerciseForm.STRENGTH
    val candidates = remember(programs) { protocolCandidates(programs) }
    val picked = candidates.firstOrNull { it.id == programId }
    /** The strict branch's answer, whichever way it was given: built, or taken off the shelf. */
    val strictSchedule = draft ?: picked

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

    if (lifted) {
        FilterChip(
            selected = oneSided,
            onClick = { oneSided = !oneSided },
            label = { Text("One side at a time") },
        )
        Text(
            "Each side keeps its own record, and every set of this exercise is asked which " +
                "side it was. It joins a workout as two cards, one per side.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
        )
    }

    if (hold) {
        Text("Schedule", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Text(
            "How this exercise is timed is part of what it IS: the same holds on another " +
                "schedule are a separate exercise with a separate record, and this is the " +
                "only moment it can be chosen.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
        )
        ScheduleKind.entries.forEach { candidate ->
            ScheduleCard(
                kind = candidate,
                selected = kind == candidate,
                onSelect = { kind = candidate },
            )
        }

        when (kind) {
            ScheduleKind.FREE -> Unit

            ScheduleKind.SIMPLE_PAIR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            ScheduleKind.STRICT -> ScheduleBranch(
                name = name,
                draft = draft,
                picked = picked,
                candidates = candidates,
                categories = categories,
                onDraft = {
                    draft = it
                    // the two answers are exclusive: a schedule built by hand replaces one
                    // taken off the shelf rather than travelling beside it
                    programId = null
                },
                onPick = {
                    programId = it
                    draft = null
                },
            )

            null -> Unit
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
                // whole seconds only, and rounded rather than truncated: see
                // [parseProtocolSeconds] for why the rule lives in the domain. Read at all
                // only in the branch that asks for them: numbers left behind in the fields
                // by a user who then chose another branch must not travel with it.
                val typing = hold && kind == ScheduleKind.SIMPLE_PAIR
                val w = if (typing) parseProtocolSeconds(work) else null
                val r = if (typing) parseProtocolSeconds(rest) else null
                val pair = if (w != null && r != null) w to r else null
                val strict = hold && kind == ScheduleKind.STRICT
                onCreate(
                    NewExercise(
                        name = name.trim(),
                        form = form,
                        workSec = pair?.first,
                        restSec = pair?.second,
                        // a form with no sides never reports one, whatever the chip was
                        // left at before the form was switched
                        oneSided = lifted && oneSided,
                        protocolProgramId = if (strict) picked?.id else null,
                        newProgram = if (strict) draft else null,
                    )
                )
            },
            /*
             * A hold cannot be created until it has said which of the three it is, and the
             * branch it named has to actually carry an answer.
             *
             * The gate is ON THE BUTTON THAT STARTS THE THING rather than in a message
             * afterwards, for the same reason the past-day plan rule ended up there: a refusal
             * at the end of a form is a form that let you fill it in wrong. And the reason the
             * gate exists at all is that the wrong answers here are not correctable — §18.9
             * freezes an exercise's schedule at creation — so "Simple pair with both fields
             * empty" would silently produce a FREE exercise, permanently, from a card the user
             * had explicitly tapped to say otherwise.
             */
            enabled = name.isNotBlank() && (
                !hold || when (kind) {
                    ScheduleKind.FREE -> true
                    ScheduleKind.SIMPLE_PAIR ->
                        parseProtocolSeconds(work) != null && parseProtocolSeconds(rest) != null
                    ScheduleKind.STRICT -> strictSchedule != null
                    null -> false
                }
                ),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) { Text(confirmLabel) }
    }
}

/**
 * One of the three branches, as a card that says what it is and when to take it.
 *
 * A card rather than a chip, and a sentence rather than a label, because the complaint that
 * produced this was not "the control is wrong", it was "I cannot tell what I am choosing"
 * (owner, 2026-08-11). A row of chips reading Free / Pair / Strict would have been the same
 * screen with shorter words on it.
 *
 * The radio button is not decoration either: it makes the group read as "one of these" to
 * anyone arriving with a screen reader, and it is what `assertIsSelected` in the tests is
 * asserting on.
 */
@Composable
private fun ScheduleCard(kind: ScheduleKind, selected: Boolean, onSelect: () -> Unit) {
    val colors = LocalGachiColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // null onClick: the whole card is the target, and a second one inside it
                // would give the same choice two hit areas with different sizes
                RadioButton(selected = selected, onClick = null)
                Text(kind.title, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                when (kind) {
                    ScheduleKind.FREE ->
                        "No schedule at all. Nothing counts time for you: how long each hold " +
                            "lasted is typed in by hand. Take it when the holds are improvised."

                    ScheduleKind.SIMPLE_PAIR ->
                        "One work time and one rest time, and nothing else. How many holds " +
                            "and how many sets is asked before every run. Take it when the " +
                            "pair is the whole of it."

                    ScheduleKind.STRICT ->
                        "Every timing fixed in advance: which efforts, how long, in what " +
                            "order, the gaps between them, the repeats and the sets. Before a " +
                            "run nothing is asked but the weight. Take it when the session is " +
                            "a protocol you follow."
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkSecondary,
            )
        }
    }
}

/**
 * The strict branch: build a schedule, or take one that is already in the library.
 *
 * ── Why the editor arrives in a DIALOG and not as a route ───────────────────────
 * The schedule the owner wants to describe here is the full [WorkoutProgram] — groups,
 * blocks, repeats, gaps, a lead-in — and an editor for exactly that already exists as a
 * screen ([ProgramEditorScreen]). Writing a second, smaller one inside this sheet is how two
 * editors of the same thing start disagreeing about it.
 *
 * The alternative to a dialog was navigating to that screen and coming back with a result.
 * It was rejected on where this form LIVES: the create form is inside a modal bottom sheet
 * with four different hosts (the entry card, the workout screen, the slot editor, the
 * overview), and every one of them holds its own "is the sheet open" flag locally. Routing
 * to a top-level screen and back would mean lifting a half-filled create form — name, form,
 * sides, branch — out of the sheet and into `GachiApp` state that survives the sheet closing,
 * in four places, so that the trip could return to it. A dialog keeps the whole exchange
 * inside the composable that owns the draft: the sheet stays exactly where it was, and the
 * editor's Save hands a value back to a local variable.
 *
 * ── What is bad about it, said out loud ─────────────────────────────────────────
 * It stacks a full-window dialog on top of a modal sheet — three windows deep counting the
 * activity — and Compose's own dismissal handling is what has to keep the order straight;
 * the app's single [xyz.oleolegka.gachimuchi.ui.backStep] never sees any of it. And the
 * editor's own top bar is written for the library ("New program", Save), which is a second
 * vocabulary arriving in the middle of a form that says "schedule"; `asSchedule` renames the
 * two words that would otherwise contradict it, but the editor is still visibly a screen
 * borrowed from elsewhere.
 *
 * ── Nothing is written until the exercise is ────────────────────────────────────
 * The built schedule goes home as a value on [NewExercise] and is stored one step before the
 * exercise that references it. Backing out of creation therefore leaves the library exactly
 * as it was, however many times the editor was opened.
 */
@Composable
private fun ScheduleBranch(
    /** The exercise's name so far, used to name the schedule it is being built for. */
    name: String,
    draft: WorkoutProgram?,
    picked: WorkoutProgram?,
    candidates: List<WorkoutProgram>,
    categories: List<String>,
    onDraft: (WorkoutProgram) -> Unit,
    onPick: (Long) -> Unit,
) {
    val colors = LocalGachiColors.current
    var editing by remember { mutableStateOf(false) }
    val chosen = draft ?: picked

    if (chosen != null) {
        Text(
            "Schedule: ${chosen.name}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            chosen.scheduleSummary(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
        )
        Text(
            "Fixed from the moment this exercise exists: a schedule an exercise is led by " +
                "is not edited afterwards, it is what the exercise IS.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (draft != null) "Edit the schedule" else "Build a schedule")
        }
    }

    if (candidates.isNotEmpty()) {
        Text(
            "Or use one already in the library. Two exercises sharing a schedule is normal " +
                "and deliberate: 20 mm and 15 mm hangs are the same protocol on a different edge.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
        )
        candidates.forEach { program ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = picked?.id == program.id,
                        role = Role.RadioButton,
                        onClick = { onPick(program.id) },
                    )
                    .heightIn(min = 48.dp)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = picked?.id == program.id, onClick = null)
                Column {
                    Text(program.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        program.scheduleSummary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }
            }
        }
    }

    if (editing) {
        Dialog(
            onDismissRequest = { editing = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            /*
             * Filled and opaque on purpose. `usePlatformDefaultWidth = false` widens the dialog
             * window but leaves its height wrapping the content, so without this the editor
             * would float as a card over the sheet — and its own scrolling list would be
             * measured against whatever height that card happened to want. Full window, own
             * surface: it reads as the screen it is borrowed from.
             */
            Surface(modifier = Modifier.fillMaxSize()) {
                ProgramEditorScreen(
                    initial = draft ?: blankSchedule(name),
                    // the exercise does not exist yet, so there is nothing to link to and the
                    // link is implied anyway: this schedule is being built FOR it
                    candidates = emptyList(),
                    categories = categories,
                    asSchedule = true,
                    onSave = {
                        onDraft(it)
                        editing = false
                    },
                    onClose = { editing = false },
                )
            }
        }
    }
}

/**
 * The schedule an editor opened from the create form starts from.
 *
 * Named after the exercise rather than "New program", because this one is going straight into
 * the library beside the others and "New program" is what five of them were called the last
 * time a migration named things. One group of one hold is the smallest thing the editor will
 * save (it refuses a program of zero length), and it is the shape the user then adds repeats
 * and sets to.
 */
private fun blankSchedule(exerciseName: String): WorkoutProgram {
    val label = exerciseName.trim().ifEmpty { "Hold" }
    return WorkoutProgram(
        name = "$label schedule",
        prepareSec = PREPARE_DEFAULT_SEC,
        groups = listOf(
            ProgramGroup(
                name = "Set",
                blocks = listOf(ProgramBlock(name = label, workSec = 7, restSec = 3)),
            )
        ),
    )
}

/**
 * The library programs the STRICT branch can be led by: the ones that ARE a strict schedule.
 *
 * ── The test is the classifier, and that is the whole point ─────────────────────
 * This list sits under a card the user has just tapped that promises "every timing fixed in
 * advance, nothing asked before a run but the weight". Whether that promise is kept is
 * decided later by [scheduleKindOf] reading the shape of the program, so the only programs
 * that may appear here are the ones that classifier calls [ScheduleKind.STRICT]. Anything
 * else is an exercise created as one branch and conducted as another — which
 * `domain/HoldSchedule.kt` names as the worst failure it can have.
 *
 * Two kinds of row leave the list because of it, and both were offers of something the app
 * would not then do:
 *
 * - a program with NOTHING TO COUNT (no groups, a group with no blocks, blocks of zero work).
 *   The library editor stores such a program, [flatten] turns it into an empty list of steps,
 *   and an exercise pointed at one classifies as [ScheduleKind.FREE] — so the strict card
 *   would have produced a free hold, permanently (§18.9), from a row whose own caption said
 *   "empty - 0 efforts";
 * - a program shaped like a plain PAIR — one group, one block, no repeats. It classifies as
 *   [ScheduleKind.SIMPLE_PAIR], which is the branch that asks how many holds and how many sets
 *   before every run. That is the pair branch's own job, reached by typing the two numbers,
 *   and offering it here made the card above the list a lie.
 *
 * Richness is still fair game, INCLUDING several groups and a warm-up: that is the whole point
 * of picking one instead of typing two numbers.
 *
 * ── What is no longer required, and why it never should have been ───────────────
 * The old test was "the first block is a real work:rest pair", rest included. A strict
 * schedule whose first effort is followed by no pause at all — a maximum hang with the rest
 * carried on the group — is a perfectly ordinary hangboard protocol, and it was silently
 * absent from this list. The reason given was that `HoldSet`'s validator throws on a
 * non-positive protocol, but it never sees one: [xyz.oleolegka.gachimuchi.domain.ExerciseRef.
 * protocol] answers null unless BOTH numbers are positive, and a null protocol writes both
 * fields as null, which is the pair the validator asks for.
 */
internal fun protocolCandidates(programs: List<WorkoutProgram>): List<WorkoutProgram> =
    programs.filter { scheduleKindOf(it) == ScheduleKind.STRICT }
