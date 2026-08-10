package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.Bodyweight
import xyz.oleolegka.gachimuchi.domain.Cardio
import xyz.oleolegka.gachimuchi.domain.Duration
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.LoadedSet
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.Tick
import xyz.oleolegka.gachimuchi.domain.formatNumber
import xyz.oleolegka.gachimuchi.domain.formatPace
import xyz.oleolegka.gachimuchi.domain.parseCount
import xyz.oleolegka.gachimuchi.domain.parseNumber
import xyz.oleolegka.gachimuchi.domain.parsePace
import xyz.oleolegka.gachimuchi.ui.label
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * Correcting or removing ONE entry that is already in the journal.
 *
 * ── Why an entry can be corrected at all, in an append-only journal ─────────────
 * It is not edited in place. Confirming here writes a whole NEW row and marks this one
 * superseded — see `domain/Amendments.kt` for the model — and deleting appends a deletion. The
 * original row stays exactly as it was written, which is the whole point of the journal — this
 * dialog changes what the journal SAYS is current, never what it recorded.
 *
 * ── What is deliberately not on this dialog: the exercise ──────────────────────
 * There is no way to move this entry to another exercise, and that is a UI rule rather than a
 * refusal enforced underneath it: an exercise's history is the set of entries that always were
 * its own, and a set that can walk from one exercise to another turns every record and every
 * chart into a statement about wherever the sets happen to be pointing today. The candidate
 * this dialog builds always keeps [entry]'s own exercise (see `amended` below), so the honest
 * thing is to never offer a control for it — moving a set is a deletion and a new entry, and
 * the line at the bottom says so.
 *
 * ── The button is enabled only when what it would write is legal ───────────────
 * The candidate form is BUILT on every keystroke, inside `runCatching`, and the confirm button
 * follows whether it built. The form validators are the same ones the journal is written
 * through — reps above zero, a real date, no zero weights — so a value they would reject
 * cannot be confirmed here instead of throwing out of a click handler. That matters more than
 * usual on this screen: the throw would land on a correction, and a crash while fixing a typo
 * is how somebody stops trusting the app with their history.
 *
 * ── Removing an entry is NOT here (§14.1) ──────────────────────────────────────
 * It used to be: a "Remove" button beside "Save", with its own confirmation behind it. That
 * made two ways to reach one act — the row's long press offers the removal directly — and two
 * ways is how the two drift apart. This dialog now does exactly one thing, which is what its
 * title says. See [ItemActions] for the gesture and [ConfirmRemoveDialog] for the question it
 * asks first.
 */
@Composable
fun EntryEditorDialog(
    /** The entry as it currently reads, corrections already applied. */
    entry: ActivityForm,
    /**
     * Whether the exercise this belongs to is trained one limb at a time, from the catalog.
     * The side chooser also appears when the entry already names a side, so an entry recorded
     * before the flag was set can still be corrected.
     */
    oneSided: Boolean = false,
    onAmend: (ActivityForm) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current

    // one draft per field the forms between them can carry; which are shown is decided below
    var day by remember(entry) { mutableStateOf(entry.opDate) }
    var weight by remember(entry) { mutableStateOf(initialWeight(entry)) }
    var reps by remember(entry) { mutableStateOf(initialReps(entry)) }
    var minutes by remember(entry) { mutableStateOf(initialMinutes(entry)) }
    var km by remember(entry) { mutableStateOf(initialKm(entry)) }
    var pace by remember(entry) { mutableStateOf(initialPace(entry)) }
    var warmup by remember(entry) { mutableStateOf(initialWarmup(entry)) }
    var side by remember(entry) { mutableStateOf((entry as? HoldSet)?.sideOf) }
    var holdSeconds by remember(entry) { mutableStateOf(initialHoldSec(entry)) }

    /*
     * The whole validation story in one expression: build what would be written, and let the
     * form's own init block be the judge. Null means "this would not be a legal entry", which
     * is exactly the question the confirm button needs answered.
     */
    val candidate = runCatching {
        amended(entry, day, weight, reps, minutes, km, pace, warmup, side, holdSeconds)
    }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct this entry") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    entry.summaryLine(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                when (entry) {
                    is StrengthSet -> {
                        StepperField(
                            label = if (entry.ownWeight) "Added weight, kg" else "Weight, kg",
                            value = weight,
                            onValueChange = { weight = it },
                            steps = listOf(2.5, 5.0),
                        )
                        StepperField(
                            label = "Reps",
                            value = reps,
                            onValueChange = { reps = it },
                            steps = listOf(1.0),
                            decimal = false,
                        )
                        WarmupToggle(warmup) { warmup = !warmup }
                    }

                    is HoldSet -> {
                        StepperField(
                            label = "Added weight, kg",
                            value = weight,
                            onValueChange = { weight = it },
                            steps = listOf(0.5, 1.0),
                        )
                        StepperField(
                            label = "Reps",
                            value = reps,
                            onValueChange = { reps = it },
                            steps = listOf(1.0),
                            decimal = false,
                        )
                        // the length of one hold — see [HoldSet.holdSec] and the note on the
                        // same field in ui/screens/LogScreen.kt's HoldEntry
                        StepperField(
                            label = "Hold time, s",
                            value = holdSeconds,
                            onValueChange = { holdSeconds = it },
                            steps = listOf(1.0, 5.0),
                        )
                        if (oneSided || entry.sideOf != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HoldSide.entries.forEach { option ->
                                    FilterChip(
                                        selected = side == option,
                                        onClick = { side = if (side == option) null else option },
                                        label = { Text(option.label()) },
                                        modifier = Modifier.heightIn(min = 40.dp),
                                    )
                                }
                            }
                        }
                        WarmupToggle(warmup) { warmup = !warmup }
                    }

                    is Cardio -> {
                        StepperField(
                            label = "Distance, km",
                            value = km,
                            onValueChange = { km = it },
                            steps = listOf(0.5, 1.0),
                        )
                        StepperField(
                            label = "Time, min",
                            value = minutes,
                            onValueChange = { minutes = it },
                            steps = listOf(1.0, 5.0),
                        )
                        StepperField(
                            label = "Pace, min:s per km",
                            value = pace,
                            onValueChange = { pace = it },
                            steps = emptyList(),
                            placeholder = "4:30",
                        )
                    }

                    is Duration -> StepperField(
                        label = "Minutes",
                        value = minutes,
                        onValueChange = { minutes = it },
                        steps = listOf(1.0, 5.0),
                    )

                    is Bodyweight -> StepperField(
                        label = "Body weight, kg",
                        value = weight,
                        onValueChange = { weight = it },
                        steps = listOf(0.1, 0.5),
                    )

                    // a check-in has no metric by definition; its day is the only thing about it
                    is Tick -> Text(
                        "A check-in records no numbers - the day is the whole of it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }

                /*
                 * The date is the correction people actually need — "I logged this on Tuesday
                 * but did it on Monday" — and it is only a value, so an amendment carries it
                 * like any other. It moves the entry on the calendar, the heatmap and the
                 * streak, which is the point.
                 */
                StepperField(
                    label = "Day (YYYY-MM-DD)",
                    value = day,
                    onValueChange = { day = it },
                    steps = emptyList(),
                    placeholder = "2026-08-07",
                )

                Text(
                    if (candidate == null) {
                        "Something here is not a value this entry can take - check the day and " +
                            "the numbers."
                    } else {
                        "Which exercise this belongs to cannot be changed here. Moving a set is " +
                            "removing it and recording it again, so that an exercise's history " +
                            "stays the entries that always were its own."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    // the refusal is stated in words as well as colour, like every verdict here
                    color = if (candidate == null) colors.critical else colors.inkSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { candidate?.let(onAmend) },
                enabled = candidate != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The warm-up toggle of the editor — the same fact the entry card sets when recording. */
@Composable
private fun WarmupToggle(selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text("Warm-up") },
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

// --- the drafts, and the form they add up to ------------------------------------------------
//
// Prefilled from the entry as it currently reads (corrections already folded in), so opening
// the dialog and pressing Save writes an amendment that changes nothing rather than blanking
// the values that were not touched.

private fun initialWeight(entry: ActivityForm): String = when (entry) {
    // outward one branch — only a loaded set has a weight field at all; which field it reads
    // (added weight vs the implement's own) still depends on the concrete form
    is LoadedSet -> when (entry) {
        is StrengthSet -> (if (entry.ownWeight) entry.addedKg else entry.weightKg)?.let(::formatNumber).orEmpty()
        is HoldSet -> entry.addedKg?.let(::formatNumber).orEmpty()
    }

    is Bodyweight -> formatNumber(entry.weightKg)
    else -> ""
}

private fun initialReps(entry: ActivityForm): String = when (entry) {
    // reps is not one of LoadedSet's shared members (StrengthSet's is non-null, HoldSet's is
    // nullable), so the concrete type still decides how to read it
    is LoadedSet -> when (entry) {
        is StrengthSet -> entry.reps.toString()
        is HoldSet -> entry.reps?.toString().orEmpty()
    }

    else -> ""
}

private fun initialMinutes(entry: ActivityForm): String = when (entry) {
    is Duration -> formatNumber(entry.durationSec / 60.0)
    is Cardio -> entry.durationSec?.let { formatNumber(it / 60.0) }.orEmpty()
    else -> ""
}

private fun initialKm(entry: ActivityForm): String =
    (entry as? Cardio)?.distanceM?.let { formatNumber(it / 1000) }.orEmpty()

private fun initialPace(entry: ActivityForm): String =
    (entry as? Cardio)?.paceSecPerKm?.let(::formatPace).orEmpty()

private fun initialWarmup(entry: ActivityForm): Boolean = when (entry) {
    is LoadedSet -> entry.warmup
    else -> false
}

/** The length of one hold, or blank for anything else — [HoldSet] is the only form that has one. */
private fun initialHoldSec(entry: ActivityForm): String =
    (entry as? HoldSet)?.holdSec?.let(::formatNumber).orEmpty()

/**
 * The entry as the drafts would have it.
 *
 * A `copy` of the original rather than a fresh form, so everything nobody edited survives
 * untouched: the exercise link, the protocol snapshot of a hang, the body weight recorded
 * at the time, the rest that was measured after the set. Building a new form from the
 * visible fields would silently drop all of it — and the body-weight snapshot in particular is
 * unrecoverable, since it is what somebody weighed on a day now in the past.
 *
 * THROWS on values the journal would refuse; the caller builds it inside `runCatching` and
 * uses that as the enabled-state of the button.
 */
private fun amended(
    entry: ActivityForm,
    day: String,
    weight: String,
    reps: String,
    minutes: String,
    km: String,
    pace: String,
    warmup: Boolean,
    side: HoldSide?,
    holdSeconds: String = "",
): ActivityForm {
    val weightValue = parseNumber(weight)
    val repsValue = parseCount(reps)
    return when (entry) {
        is StrengthSet -> if (entry.ownWeight) {
            // zero is "nothing was added", which the payload says by leaving the field out; the
            // sign survives, because a negative added weight is assistance and not a typo
            entry.copy(
                addedKg = weightValue?.takeIf { it != 0.0 }, reps = repsValue ?: entry.reps,
                warmup = warmup, opDate = day,
            )
        } else {
            entry.copy(
                weightKg = weightValue?.takeIf { it > 0 }, reps = repsValue ?: entry.reps,
                warmup = warmup, opDate = day,
            )
        }

        is HoldSet -> entry.copy(
            addedKg = weightValue?.takeIf { it != 0.0 },
            reps = repsValue?.takeIf { it > 0 },
            holdSec = parseNumber(holdSeconds)?.takeIf { it > 0 },
            warmup = warmup,
            side = side?.code,
            opDate = day,
        )

        is Cardio -> entry.copy(
            distanceM = parseNumber(km)?.takeIf { it > 0 }?.let { it * 1000 },
            durationSec = parseNumber(minutes)?.takeIf { it > 0 }?.let { (it * 60).toInt() },
            paceSecPerKm = parsePace(pace),
            opDate = day,
        )

        is Duration -> entry.copy(
            durationSec = parseNumber(minutes)?.let { (it * 60).toInt() } ?: entry.durationSec,
            opDate = day,
        )

        is Bodyweight -> entry.copy(weightKg = weightValue ?: entry.weightKg, opDate = day)

        is Tick -> entry.copy(opDate = day)
    }
}
