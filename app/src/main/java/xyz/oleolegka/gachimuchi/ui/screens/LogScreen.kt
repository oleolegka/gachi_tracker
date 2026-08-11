package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.bodyweightOf
import xyz.oleolegka.gachimuchi.domain.cardioOf
import xyz.oleolegka.gachimuchi.domain.durationOf
import xyz.oleolegka.gachimuchi.domain.formatDurationSec
import xyz.oleolegka.gachimuchi.domain.formatNumber
import xyz.oleolegka.gachimuchi.domain.formatPace
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.lastBodyweight
import xyz.oleolegka.gachimuchi.domain.lastCardio
import xyz.oleolegka.gachimuchi.domain.lastDuration
import xyz.oleolegka.gachimuchi.domain.lastHoldSet
import xyz.oleolegka.gachimuchi.domain.lastStrengthSet
import xyz.oleolegka.gachimuchi.domain.parseCount
import xyz.oleolegka.gachimuchi.domain.parseDurationText
import xyz.oleolegka.gachimuchi.domain.parseNumber
import xyz.oleolegka.gachimuchi.domain.parsePace
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.tickOf
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.StepperField
import xyz.oleolegka.gachimuchi.ui.components.TimeField
import xyz.oleolegka.gachimuchi.ui.label
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/*
 * ── The six entry forms: one form per activity shape (§3) ───────────────────────
 * Each is a set of fields plus the primary button, prefilled from the journal, and they are
 * `internal` because [WorkoutLogScreen]'s quick entry sheet is what raises them.
 *
 * ── There used to be a screen in this file, and its absence is the point ─────────
 * `LogScreen` drew a whole day as a tape with ONE pinned entry card pointed at an "active
 * exercise", and it was the way in for a single entry — an exercise recorded with no workout
 * around it. Two ways to record meant two orders of questions, and they drifted exactly as far
 * apart as nothing stopped them: the workout asked for the rest between sets and the single
 * entry never did; the workout raised a card you tapped when you had actually done something
 * and the single entry pushed a prefilled "Repeat set" at you the moment an exercise was
 * chosen; a protocol-led exercise started from a card in one and from a button on a timer bar
 * in the other, with the side asked in a dialog because there was no card to have answered it.
 *
 * The fix was not to teach this screen the other one's questions — that is the arrangement
 * that had already failed twice. A single entry IS an exercise of a workout with the workout
 * taken away, so there is now one screen ([WorkoutLogScreen]) over two containers, the second
 * of which is built by `looseWorkout` in domain/Workout.kt. What is left here is the part that
 * was always shared and never diverged: the fields themselves.
 */

/**
 * The primary button. It is the biggest target on the screen and says what will happen:
 * "Repeat set" while the card still holds the previous values, "Add set" once something
 * was changed.
 */
@Composable
private fun SubmitButton(repeat: Boolean, enabled: Boolean, label: String? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(
            label ?: if (repeat) "Repeat set" else "Add set",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * The warm-up toggle, shared by the two forms that can carry one.
 *
 * ── Off on arrival, always, and never prefilled ─────────────────────────────────
 * A warm-up is a decision made about ONE set, not a property of the exercise, so the card
 * opens on "working set" however the previous set was marked. That is also what keeps the
 * ordinary move at two taps — raise the form, press the button — because the control that
 * matters most here is the one nobody has to touch.
 *
 * Getting this backwards is the expensive direction: a card that arrived pre-ticked from a
 * ramp-up would quietly file the working set that follows as a warm-up, and a warm-up counts
 * towards neither volume nor records. The set would be in the journal, on the day's feed, and
 * missing from every number the training is judged by.
 */
@Composable
private fun WarmupChip(selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text("Warm-up") },
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

/**
 * The "not completed" toggle, shared by the two forms that can carry the flag — modelled on
 * [WarmupChip] one line up, with the axis it defaults to turned around: OFF on arrival, always,
 * because whether the LAST set was carried through says nothing about whether THIS one will be
 * (owner: "если провисел весь цикл — хорошо, в следующий раз больше поставлю; а если не смог
 * доделать, то больше брать и не надо"). The app cannot tell this on its own — the timer counts
 * its seconds whether or not the lifter actually held on for all of them — so it is a mark set
 * by hand, never inferred. See [StrengthSet.incomplete] for what ticking it changes: the set
 * stays in the tonnage (the effort was real) and drops out of the records (the number was not
 * actually held).
 */
@Composable
private fun IncompleteChip(selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text("Not completed") },
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

/**
 * "Last time this was not completed" — the one thing this card says about a PAST set rather
 * than the one being built, and the reason [LoadedSet.incomplete] exists at all: the owner's
 * whole ask was a note that feeds the NEXT decision about weight, not a badge for its own sake.
 * Placed right under the weight and reps fields (or the hold time, on [HoldEntry]) — the numbers
 * this is a comment on — rather than folded into [contextLine], which is read once when the
 * card is chosen and not while the fields are being looked at.
 *
 * Silent when the answer is no (most of the time) or when there is no last set at all, on the
 * same grounds every quiet default in this file follows: a card that speaks up about ordinary
 * training is a card nobody reads carefully any more.
 */
@Composable
private fun LastTimeIncompleteNote(show: Boolean) {
    if (!show) return
    val colors = LocalGachiColors.current
    Text(
        "Last time this was not completed - consider the same weight again, or less.",
        style = MaterialTheme.typography.labelSmall,
        color = colors.warning,
    )
}

/**
 * Whether an entry card owes an answer for which side a set was — shared by every form that
 * carries [LoadedSet.side], so [StrengthEntry] and [HoldEntry] settle the question the same
 * way. Only a one-sided exercise owes an answer, and only when the card itself did not already
 * say which one — on any other exercise, or with a fixed side, there is nothing left to ask.
 */
private fun sideMissingOf(oneSided: Boolean, fixedSide: HoldSide?, side: HoldSide?): Boolean =
    oneSided && fixedSide == null && side == null

/**
 * The side chip row and the line explaining a disabled button — shared by every entry form
 * that can carry a [LoadedSet.side]. See [HoldEntry]'s own KDoc for [fixedSide]: non-null, the
 * card that raised this form already answered the question and this draws nothing at all.
 */
@Composable
private fun SideChooser(
    oneSided: Boolean,
    fixedSide: HoldSide?,
    side: HoldSide?,
    onSideChange: (HoldSide?) -> Unit,
    sideMissing: Boolean,
) {
    if (!oneSided || fixedSide != null) return
    val colors = LocalGachiColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HoldSide.entries.forEach { option ->
            FilterChip(
                selected = side == option,
                // tapping the chosen one again clears it rather than doing nothing, so a
                // mis-tap is undone the same way it was made
                onClick = { onSideChange(if (side == option) null else option) },
                label = { Text(option.label()) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
    if (sideMissing) {
        Text(
            "Say which side. This one is trained a limb at a time, and each side keeps " +
                "its own record - a set that names neither belongs to neither.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
        )
    }
}

/**
 * Strength sets: weight x reps and — on an exercise trained one limb at a time — which side.
 *
 * The side question is asked the same way [HoldEntry] asks it ([SideChooser], [sideMissingOf],
 * [fixedSide]): the mechanism (two workout cards, per-side rest, per-side records) never
 * depended on the exercise's form, only the field carrying the answer did — see
 * [xyz.oleolegka.gachimuchi.domain.LoadedSet.side].
 */
@Composable
internal fun StrengthEntry(
    state: UiState,
    exercise: ExerciseRef,
    opDate: String,
    onAddSet: (ActivityForm) -> Unit,
    fixedSide: HoldSide? = null,
) {
    val last = remember(state.events, exercise.id) { lastStrengthSet(state.events, exercise.link) }
    val prefillWeight = if (last?.ownWeight == true) last.addedKg else last?.weightKg

    var weight by remember(exercise.id, last) { mutableStateOf(prefillWeight?.let(::formatNumber) ?: "") }
    var reps by remember(exercise.id, last) { mutableStateOf(last?.reps?.toString() ?: "") }
    var ownWeight by remember(exercise.id, last) { mutableStateOf(last?.ownWeight ?: false) }
    var warmup by remember(exercise.id, last) { mutableStateOf(false) }
    // OFF ON ARRIVAL, on the same grounds [WarmupChip] gives for its own flag: whether the
    // last set was carried through is not a property of the exercise, so this card opens on
    // "carried through" however the previous set actually went. See [IncompleteChip].
    var incomplete by remember(exercise.id, last) { mutableStateOf(false) }
    // NOT prefilled from the last set, on the same grounds [HoldEntry] leaves its own side
    // blank: one-sided work alternates, so last time's side is the wrong answer about as
    // often as it is right, and the failure would be silent.
    var side by remember(exercise.id, last, fixedSide) { mutableStateOf(fixedSide) }

    val repsValue = parseCount(reps)
    val weightValue = parseNumber(weight)
    /*
     * The warm-up flag is part of what makes a set "the same again": ramping up and then
     * repeating the ramp-up is a repeat, and a working set after one is not. Comparing it
     * against the previous set rather than against false is what keeps the button honest in
     * both directions — the card starts unticked, so a working set after a working set still
     * reads "Repeat set" and still costs one tap. [incomplete] joins it for the same reason:
     * a card that reads "Repeat set" while quietly UN-marking a set the lifter said fell short
     * would silently turn the correction back into a repeat.
     */
    val untouched = last != null && weightValue == prefillWeight &&
        repsValue == last.reps && ownWeight == last.ownWeight && warmup == last.warmup &&
        incomplete == last.incomplete && side == last.sideOf
    val sideMissing = sideMissingOf(exercise.oneSided, fixedSide, side)

    StepperField(
        label = if (ownWeight) "Added weight, kg (empty means body weight only)" else "Weight, kg",
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
    // "In the past, this weight was not carried through" — the input the owner asked this
    // whole feature to feed into: whether to push the weight up next time. See [IncompleteChip].
    LastTimeIncompleteNote(last?.incomplete == true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = ownWeight,
            onClick = { ownWeight = !ownWeight },
            label = { Text("Own body weight") },
            modifier = Modifier.heightIn(min = 40.dp),
        )
        WarmupChip(warmup) { warmup = !warmup }
        IncompleteChip(incomplete) { incomplete = !incomplete }
    }
    SideChooser(exercise.oneSided, fixedSide, side, onSideChange = { side = it }, sideMissing)
    SubmitButton(
        repeat = untouched,
        enabled = !sideMissing && repsValue != null && repsValue > 0,
    ) {
        onAddSet(
            strengthSetOf(
                exercise = exercise, opDate = opDate, reps = repsValue!!,
                weightKg = weightValue, ownWeight = ownWeight, addedKg = weightValue,
                warmup = warmup, incomplete = incomplete, side = side,
            )
        )
    }
}

/**
 * Holds. The protocol is NOT asked for: §12-A puts it on the exercise, so the variables of
 * a set are the added weight, the number of reps and — on an exercise trained one limb at
 * a time — which hand it was.
 *
 * ── The side is asked for, and it cannot be skipped ─────────────────────────────
 * A record on a one-sided exercise is per (exercise, side): the weaker hand has its own
 * history and the gap between the two is what the training exists to close. A set that names
 * no side on such an exercise is therefore NOT "both hands" — it is a set that failed to say,
 * and the readers report it as a defect rather than guessing
 * ([xyz.oleolegka.gachimuchi.domain.holdRecord] files it under "side not recorded"). So the
 * primary button stays disabled until one is chosen, with a line underneath saying why: a
 * dead button that explains nothing is the worst thing on a screen used mid-set.
 *
 * NOT PREFILLED FROM THE LAST SET, unlike every other field on this card. One-sided work
 * alternates, so last time's answer is the wrong one about as often as it is right — and the
 * failure is silent: two lefts in the journal, a right hand's history missing a set, and a
 * record on the wrong hand. The weight and the reps prefill because being wrong about them is
 * visible in the field before the button is pressed; the hand is not.
 *
 * ── [fixedSide] — asked already, by the card ─────────────────────────────────────
 * Null everywhere this used to be the whole of it: a bare entry not raised from a workout, and
 * the standalone [LogScreen] itself, where there is no card to have already said which hand.
 * Inside a workout a one-sided exercise gets two CARDS, one per side (see domain/Workout.kt),
 * and the card that was tapped is itself the answer — asking again with a chip row here would
 * be the same question twice on the one screen used mid-set. So a non-null [fixedSide] hides the
 * chips and writes that side, unconditionally, with nothing left for [sideMissing] to catch.
 *
 * [StrengthEntry] asks the same question the same way, for the same reason — this is the
 * older of the two only because a hangboard is where the app's one-sided training started.
 */
@Composable
internal fun HoldEntry(
    state: UiState,
    exercise: ExerciseRef,
    opDate: String,
    onAddSet: (ActivityForm) -> Unit,
    fixedSide: HoldSide? = null,
) {
    val last = remember(state.events, exercise.id) { lastHoldSet(state.events, exercise.link) }
    var weight by remember(exercise.id, last) { mutableStateOf(last?.addedKg?.let(::formatNumber) ?: "") }
    var reps by remember(exercise.id, last) { mutableStateOf(last?.reps?.toString() ?: "") }
    var holdSeconds by remember(exercise.id, last) { mutableStateOf(last?.holdSec?.let(::formatNumber) ?: "") }
    var warmup by remember(exercise.id, last) { mutableStateOf(false) }
    // OFF ON ARRIVAL — see [StrengthEntry]'s own note on why this is never prefilled.
    var incomplete by remember(exercise.id, last) { mutableStateOf(false) }
    var side by remember(exercise.id, last, fixedSide) { mutableStateOf(fixedSide) }

    val repsValue = parseCount(reps)
    val weightValue = parseNumber(weight)
    val holdSecValue = parseNumber(holdSeconds)
    val untouched = last != null && weightValue == last.addedKg && repsValue == last.reps &&
        holdSecValue == last.holdSec && warmup == last.warmup &&
        incomplete == last.incomplete && side == last.sideOf
    val sideMissing = sideMissingOf(exercise.oneSided, fixedSide, side)

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
    /*
     * THE LENGTH OF ONE HOLD, in seconds — see [HoldSet.holdSec]. Nothing else here can supply
     * it: the catalog's work:rest protocol is a plan, not a record of what this set actually
     * did, and a hold with no protocol at all (a plank) has no other source for it whatsoever.
     * Left blank the set is stored exactly as it always was — nothing invented for a length
     * nobody stated.
     */
    StepperField(
        label = "Hold time, s",
        value = holdSeconds,
        onValueChange = { holdSeconds = it },
        steps = listOf(1.0, 5.0),
    )
    // "Last time this was not held for the full protocol" — the owner's own example ("провисел
    // не 7 секунд, а 5") is exactly what this line is for. See [IncompleteChip].
    LastTimeIncompleteNote(last?.incomplete == true)
    SideChooser(exercise.oneSided, fixedSide, side, onSideChange = { side = it }, sideMissing)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WarmupChip(warmup) { warmup = !warmup }
        IncompleteChip(incomplete) { incomplete = !incomplete }
    }
    SubmitButton(
        repeat = untouched,
        enabled = !sideMissing &&
            ((weightValue != null && weightValue > 0) || (repsValue != null && repsValue > 0)),
    ) {
        onAddSet(
            holdSetOf(
                exercise = exercise, opDate = opDate, addedKg = weightValue, reps = repsValue,
                holdSec = holdSecValue, warmup = warmup, incomplete = incomplete, side = side,
            )
        )
    }
}

@Composable
internal fun CardioEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events, exercise.id) { lastCardio(state.events, exercise.link) }
    var km by remember(exercise.id, last) {
        mutableStateOf(last?.distanceM?.let { formatNumber(it / 1000) } ?: "")
    }
    var minutes by remember(exercise.id, last) {
        mutableStateOf(last?.durationSec?.let { formatNumber(it / 60.0) } ?: "")
    }
    var pace by remember(exercise.id, last) {
        mutableStateOf(last?.paceSecPerKm?.let(::formatPace) ?: "")
    }

    val distance = parseNumber(km)?.takeIf { it > 0 }?.let { it * 1000 }
    val duration = parseNumber(minutes)?.takeIf { it > 0 }?.let { (it * 60).toInt() }
    val paceValue = parsePace(pace)
    val untouched = last != null && distance == last.distanceM && duration == last.durationSec

    StepperField(label = "Distance, km", value = km, onValueChange = { km = it }, steps = listOf(0.5, 1.0))
    StepperField(label = "Time, min", value = minutes, onValueChange = { minutes = it }, steps = listOf(1.0, 5.0))
    StepperField(
        label = "Pace, min:s per km (optional)",
        value = pace,
        onValueChange = { pace = it },
        steps = emptyList(),
        placeholder = "4:30",
    )
    SubmitButton(
        repeat = untouched,
        enabled = distance != null || duration != null || paceValue != null,
        label = if (untouched) "Repeat entry" else "Add entry",
    ) {
        onAddSet(
            cardioOf(
                exercise = exercise, opDate = opDate, distanceM = distance,
                durationSec = duration, paceSecPerKm = paceValue,
            )
        )
    }
}

@Composable
internal fun DurationEntry(state: UiState, exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events, exercise.id) { lastDuration(state.events, exercise.link) }
    // mm:ss, free entry — it used to be a MINUTES field reaching a whole number of seconds
    // only through a decimal point ("0.5" for thirty seconds), the owner's own word for it
    // was "шиза" (§13.9)
    var duration by remember(exercise.id, last) {
        mutableStateOf(last?.durationSec?.let(::formatDurationSec) ?: "")
    }
    val seconds = parseDurationText(duration)?.takeIf { it > 0 }
    val untouched = last != null && seconds == last.durationSec

    TimeField(label = "Duration, mm:ss", value = duration, onValueChange = { duration = it }, bumpsSec = listOf(10))
    SubmitButton(
        repeat = untouched,
        enabled = seconds != null && seconds > 0,
        label = if (untouched) "Repeat entry" else "Add entry",
    ) {
        onAddSet(durationOf(exercise = exercise, opDate = opDate, durationSec = seconds!!))
    }
}

@Composable
internal fun TickEntry(exercise: ExerciseRef, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val colors = LocalGachiColors.current
    Text(
        "No metrics for this one — the statistic is how often it happens.",
        style = MaterialTheme.typography.labelSmall,
        color = colors.inkSecondary,
    )
    SubmitButton(repeat = false, enabled = true, label = "Check in") {
        onAddSet(tickOf(exercise = exercise, opDate = opDate))
    }
}

/** Body weight is a plain series and carries no exercise_id — the catalog row is only the way in. */
@Composable
internal fun BodyweightEntry(state: UiState, opDate: String, onAddSet: (ActivityForm) -> Unit) {
    val last = remember(state.events) { lastBodyweight(state.events) }
    var kg by remember(last) { mutableStateOf(last?.weightKg?.let(::formatNumber) ?: "") }
    val value = parseNumber(kg)?.takeIf { it > 0 }

    StepperField(label = "Body weight, kg", value = kg, onValueChange = { kg = it }, steps = listOf(0.1, 0.5))
    SubmitButton(repeat = false, enabled = value != null, label = "Record weight") {
        onAddSet(bodyweightOf(opDate = opDate, weightKg = value!!))
    }
}
