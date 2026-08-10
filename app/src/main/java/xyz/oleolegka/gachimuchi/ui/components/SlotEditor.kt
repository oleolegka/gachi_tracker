package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.REPEAT_DAILY
import xyz.oleolegka.gachimuchi.domain.REPEAT_NONE
import xyz.oleolegka.gachimuchi.domain.REPEAT_RULES
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.SlotProblem
import xyz.oleolegka.gachimuchi.domain.deletionWarning
import xyz.oleolegka.gachimuchi.domain.formatTime
import xyz.oleolegka.gachimuchi.domain.formatTimeDigits
import xyz.oleolegka.gachimuchi.domain.isBackdated
import xyz.oleolegka.gachimuchi.domain.newSlotDraft
import xyz.oleolegka.gachimuchi.domain.nextOccurrence
import xyz.oleolegka.gachimuchi.domain.parseMinuteOfDay
import xyz.oleolegka.gachimuchi.domain.parseSlotTime
import xyz.oleolegka.gachimuchi.domain.problem
import xyz.oleolegka.gachimuchi.domain.problemText
import xyz.oleolegka.gachimuchi.domain.repeatLabel
import xyz.oleolegka.gachimuchi.domain.toDraft
import xyz.oleolegka.gachimuchi.domain.toSlot
import xyz.oleolegka.gachimuchi.domain.withExerciseAdded
import xyz.oleolegka.gachimuchi.domain.withExerciseMoved
import xyz.oleolegka.gachimuchi.domain.withExerciseRemoved
import xyz.oleolegka.gachimuchi.domain.withExerciseRest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.fmtRest
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.screens.ExercisePickerSheet
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * Planning a session: the editor behind the calendar's "Plan a session" button and behind
 * the pencil on a planned row.
 *
 * ── A dialog, not a screen ──────────────────────────────────────────────────────
 * There are four fields and the calendar behind them is the context ("this day"), which a
 * full screen would cover up. The program editor is a screen because a program is a tree;
 * a slot is a line.
 *
 * ── Defaults, so the common case is two taps ────────────────────────────────────
 * It opens as a one-off on the day that was selected, with no time — the cheapest thing a
 * plan can be. A name is the only thing that must be typed, and the names already in the
 * plan are offered as chips, so the second "Gym" is a tap.
 *
 * ── The date field is where a weekly plan gets its weekday ──────────────────────
 * The model has no "repeat on Tue and Thu": the anchor date carries the weekday and the
 * rule only says how often (domain/Schedule.kt). That is the one non-obvious thing here,
 * so the dialog spells the consequence out in words under the field ("Repeats every
 * Thursday") instead of leaving it to be discovered in the grid afterwards.
 *
 * ── The time is typed without a colon ───────────────────────────────────────────
 * A phone's number keypad has no colon key, which makes a plain "HH:MM" field impossible
 * to fill in. So there are three ways in and none of them needs one: the quick chips (how
 * a round hour is really chosen), the Material clock dialog, and the field itself, which
 * takes digits only and puts the colon in as they arrive — "1700" becomes "17:00" while
 * being typed (`formatTimeDigits`).
 *
 * A field that rewrites itself has to carry its own CARET, which is why the time is the one
 * field here held as a `TextFieldValue`. The colon is inserted in front of the caret, and a
 * caret left at the offset the keyboard reported would then sit before the digit it was
 * typed after: "1", "7", "0" reads "17:0" with the caret at offset 3, and the next digit
 * lands before the zero — 17:50 for someone who typed 17:05. So the caret is recomputed
 * from the number of DIGITS in front of it, which the colon does not change
 * (`caretAfterDigits`). Digit by digit is how a phone is really typed on.
 *
 * Half a time is not a time: "17:0" on screen means the last digit is still coming, so
 * `parseSlotTime` refuses it, the field turns red and Save stays disabled. The alternative
 * — reading it as 17:00 — would store a time the user did not type whenever they meant
 * 17:05, and would do it silently.
 *
 * ── The exercises are an extra, and the layout has to say so ────────────────────
 * A slot with nothing under it is a complete plan (domain/Schedule.kt), and by far the most
 * common one — "gym on Thursday" is the whole thought most of the time. So the composition
 * lives behind a COLLAPSED LINE that opens on a tap, and the line reads "none planned"
 * rather than sitting there as an empty field. An empty required-looking control is a
 * standing reproach: it makes the cheapest useful plan feel half-finished and invites the
 * user to fill something in before they know what they will do.
 *
 * It opens by itself when a slot already has exercises, because then there is something to
 * see and hiding it would mean a tap to find out whether anything is in there at all.
 *
 * ── Plans start from today, never before ────────────────────────────────────────
 * The date field can be stepped to any day, but Save shuts the moment it lands before
 * [today] — the same gate the time field already uses for a half-typed value, now guarding
 * against two reported bugs at once: a plan added straight onto a day already gone (which
 * can overwrite a MISSED verdict `planVsFact` had already settled on it) and a repeating
 * slot anchored in the past making every past occurrence of it read as planned (§12-B's
 * `occursOn` has always started from the anchor — it was the anchor that had no floor). See
 * [xyz.oleolegka.gachimuchi.domain.isBackdated] for why the check lives here and not in
 * [xyz.oleolegka.gachimuchi.domain.toSlot] itself.
 */
@Composable
fun SlotEditorDialog(
    initial: Slot?,
    day: LocalDate,
    suggestions: List<String>,
    today: LocalDate,
    /** The catalog and the journal behind it: what the exercise picker searches through. */
    state: UiState,
    /**
     * Writes a new catalog row and hands back its id. Null hides the picker's create button,
     * which is only right for a caller that genuinely cannot write — the calendar can.
     */
    onCreateExercise: ((
        name: String,
        form: ExerciseForm,
        workSec: Double?,
        restSec: Double?,
        then: (Long) -> Unit,
    ) -> Unit)? = null,
    onSave: (SlotDraft) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current
    var draft by remember(initial, day) {
        mutableStateOf(initial?.toDraft() ?: newSlotDraft(day))
    }
    // transient like the dialog itself: a clock left open across a rotation is a surprise
    var clockOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // open on what is already there, shut on what is not — see the header
    var exercisesOpen by remember(initial) { mutableStateOf(initial?.exercises?.isNotEmpty() == true) }
    // the field checks first (name, time, rule, a readable date), and only once those are
    // clean does "is this day already gone" get asked — see isBackdated's own KDoc for why
    // that one is kept apart rather than folded into problem() itself
    val problem = draft.problem() ?: if (draft.isBackdated(today)) SlotProblem.DATE_IN_PAST else null
    val anchor = remember(draft.anchorDate) {
        runCatching { LocalDate.parse(draft.anchorDate) }.getOrDefault(day)
    }
    val parsedTime = parseSlotTime(draft.timeText)
    // digits that are not a time yet: saving would drop or invent the minutes
    val timeBroken = draft.timeText.isNotBlank() && parsedTime == null
    // the time field keeps its own caret (see the header); everything that writes the time
    // from outside the field — the chips, the clock, Clear — goes through `setTime`
    var timeField by remember(initial, day) {
        mutableStateOf(TextFieldValue(draft.timeText, TextRange(draft.timeText.length)))
    }
    fun setTime(text: String) {
        draft = draft.copy(timeText = text)
        timeField = TextFieldValue(text, TextRange(text.length))
    }

    if (clockOpen) {
        TimePickerSheet(
            initial = parsedTime,
            onPick = {
                setTime(it)
                clockOpen = false
            },
            onDismiss = { clockOpen = false },
        )
    }

    if (picking) {
        ExercisePickerSheet(
            state = state,
            today = today,
            onPick = { id -> draft = draft.withExerciseAdded(id) },
            /*
             * Planning can create, and once could not. The argument for refusing was that the
             * identity questions (form, protocol) belong to the moment of the first set — which
             * sounds right and is wrong in the hand: planning Tuesday's hangs on a protocol you
             * have never tried is exactly what a plan is for, and the picker offered no way out
             * of the dead end. Reported from the phone, 2026-08-08.
             */
            onCreate = onCreateExercise?.let { create ->
                { name, form, work, rest ->
                    create(name, form, work, rest) { id ->
                        draft = draft.withExerciseAdded(id)
                    }
                }
            },
            onDismiss = { picking = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Plan a session" else "Edit this session") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("Session name") },
                    placeholder = { Text("Gym") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                // the names already in the plan: a second "Gym" should not be typed again
                if (suggestions.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { name ->
                            SiblingChip(
                                text = name,
                                selected = draft.name.trim().equals(name, ignoreCase = true),
                                accent = colors.accent,
                                onClick = { draft = draft.copy(name = name) },
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = timeField,
                        // the colon is inserted for the user: it is not on the number keypad,
                        // and the caret is moved along with it
                        onValueChange = { typed ->
                            val formatted = formatTimeDigits(typed.text)
                            val digitsTyped = typed.text
                                .take(typed.selection.end)
                                .count { it.isDigit() }
                            timeField = TextFieldValue(
                                text = formatted,
                                selection = TextRange(caretAfterDigits(formatted, digitsTyped)),
                            )
                            draft = draft.copy(timeText = formatted)
                        },
                        label = { Text("Time (optional)") },
                        placeholder = { Text("18:00") },
                        singleLine = true,
                        isError = timeBroken,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    )
                    if (draft.timeText.isNotBlank()) {
                        TextButton(onClick = { setTime("") }) { Text("Clear") }
                    }
                }

                // a numeric keyboard has no colon, so "1830" has to be as good as "18:30";
                // the chips cover the hours a session actually starts at, and the clock is
                // the way in for everything they do not cover
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QUICK_TIMES.forEach { time ->
                        SiblingChip(
                            text = time,
                            selected = parsedTime == time,
                            accent = colors.accent,
                            onClick = { setTime(time) },
                        )
                    }
                    SiblingChip(
                        text = "Clock",
                        selected = false,
                        accent = colors.accent,
                        onClick = { clockOpen = true },
                    )
                }

                SegmentControl(
                    options = REPEAT_RULES,
                    selected = draft.repeatRule.takeIf { it in REPEAT_RULES } ?: REPEAT_NONE,
                    label = { ruleLabel(it) },
                    onSelect = { draft = draft.copy(repeatRule = it) },
                    modifier = Modifier.padding(top = 2.dp),
                )

                DayField(
                    label = if (draft.repeatRule == REPEAT_NONE) "On" else "Starts on",
                    day = anchor,
                    onChange = { draft = draft.copy(anchorDate = it.toString()) },
                )

                Text(
                    repeatLabel(draft.repeatRule, draft.anchorDate),
                    fontSize = 12.sp,
                    color = colors.inkSecondary,
                )

                // what the plan will actually say once this is saved, in one line
                draft.toSlot(initial?.id ?: 0L)?.let { candidate ->
                    val next = nextOccurrence(candidate, today)
                    Text(
                        if (next == null) {
                            "This day is in the past, so nothing is coming up for it."
                        } else {
                            "Next: ${fmtWeekdayDay(next)}" + candidate.atTime?.let { " at $it" }.orEmpty()
                        },
                        fontSize = 12.sp,
                        color = colors.inkMuted,
                    )
                }

                PlannedExercisesSection(
                    exercises = draft.exercises,
                    open = exercisesOpen,
                    onToggle = { exercisesOpen = !exercisesOpen },
                    nameOf = { state.exerciseById(it)?.name },
                    onAdd = { picking = true },
                    onRemove = { draft = draft.withExerciseRemoved(it) },
                    onMove = { index, delta -> draft = draft.withExerciseMoved(index, delta) },
                    onRest = { index, sec -> draft = draft.withExerciseRest(index, sec) },
                )

                if (problem != null) {
                    Text(problemText(problem), fontSize = 12.sp, color = colors.inkMuted)
                }

                if (initial != null) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text("Delete this session", color = colors.critical)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onSave(draft) }) {
                Text(if (initial == null) "Add to the plan" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "Yes, delete it" — with the consequence written out, not summarised.
 *
 * The wording comes from the domain (`deletionWarning`), because what deletion does is a
 * property of the MODEL, not of this dialog: occurrences are computed from the row, so a
 * series cannot be half-deleted and a past occurrence cannot be kept.
 */
@Composable
fun DeleteSlotDialog(slot: Slot, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalGachiColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${slot.name}\"?") },
        text = {
            Text(
                deletionWarning(slot),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = colors.critical) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}

/**
 * What the session is meant to consist of — a plan for the workout, not a record of one.
 *
 * ── Collapsed by default, and worded as an option ───────────────────────────────
 * The header is the whole control when nothing is planned: one line saying so, which opens
 * on a tap. It deliberately does NOT look like an empty field, because it is not one — the
 * slot saves perfectly well without it, and the user said as much ("exercises are needed,
 * but not required; sometimes I will not be bothered"). A control that looks unfilled reads
 * as an instruction, and the instruction here would be wrong.
 *
 * ── Order is the plan, so order is editable ─────────────────────────────────────
 * Up and down rather than drag-and-drop: the list is three or four rows inside a scrolling
 * dialog, and a long-press-drag inside a scroll container inside a dialog fights the two
 * gestures either side of it. The arrows are unambiguous and stay hittable one-handed.
 *
 * ── The rest is per exercise and optional ───────────────────────────────────────
 * "Usual" is the default and it is NOT the absence of an answer to a required question: it
 * means "whatever this exercise normally gets", which is a live value the catalog keeps and
 * this plan should not freeze a copy of. A number is only stored when the user says this
 * session is different — the heavy day that wants three minutes where ninety seconds is
 * normal.
 */
@Composable
private fun PlannedExercisesSection(
    exercises: List<PlannedExercise>,
    open: Boolean,
    onToggle: () -> Unit,
    nameOf: (Long) -> String?,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRest: (Int, Int?) -> Unit,
) {
    val colors = LocalGachiColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (exercises.isEmpty()) "Exercises - none planned" else "Exercises (${exercises.size})",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkSecondary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Hide the exercises" else "Show the exercises",
                tint = colors.inkMuted,
            )
        }

        if (open) {
            Text(
                "Optional. A session with nothing listed is a plan just the same - this is " +
                    "only here for when you already know what you are going to do.",
                fontSize = 12.sp,
                color = colors.inkMuted,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )

            exercises.forEachIndexed { index, planned ->
                PlannedExerciseRow(
                    position = index,
                    last = index == exercises.lastIndex,
                    // a name the catalog no longer has: the line is KEPT and labelled rather
                    // than dropped, because a plan quietly losing a line is worse than one
                    // showing a line it cannot name (data/db/Entities.kt, SlotExerciseEntity)
                    name = nameOf(planned.exerciseId) ?: "Removed exercise",
                    restSec = planned.restSec,
                    onUp = { onMove(index, -1) },
                    onDown = { onMove(index, 1) },
                    onRemove = { onRemove(index) },
                    onRest = { onRest(index, it) },
                )
            }

            TextButton(onClick = onAdd, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent)
                Text("  Add an exercise", color = colors.accent)
            }
        }
    }
}

/** One planned exercise: where it sits, what it is, and how long the pauses in it are. */
@Composable
private fun PlannedExerciseRow(
    position: Int,
    last: Boolean,
    name: String,
    restSec: Int?,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
    onRest: (Int?) -> Unit,
) {
    val colors = LocalGachiColors.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${position + 1}.",
                fontSize = 13.sp,
                color = colors.inkMuted,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                name,
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onUp, enabled = position > 0) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Move \"$name\" earlier",
                    tint = colors.inkSecondary,
                )
            }
            IconButton(onClick = onDown, enabled = !last) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Move \"$name\" later",
                    tint = colors.inkSecondary,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Take \"$name\" out of the plan",
                    tint = colors.critical,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Rest", fontSize = 12.sp, color = colors.inkMuted)
            SiblingChip(
                text = "Usual",
                selected = restSec == null,
                accent = colors.accent,
                onClick = { onRest(null) },
            )
            REST_CHOICES.forEach { seconds ->
                SiblingChip(
                    text = fmtRest(seconds.toDouble()),
                    selected = restSec == seconds,
                    accent = colors.accent,
                    onClick = { onRest(seconds) },
                )
            }
        }
    }
}

/**
 * Rests offered as one tap. Chips rather than a number field for the reason the quick times
 * above are chips: the phone's numeric keyboard over a dialog is three interactions to say
 * something that has five plausible answers. A rest outside this set is a thing to set on
 * the day, on the timer, where it is actually being counted.
 */
private val REST_CHOICES = listOf(60, 90, 120, 150, 180, 240)

/**
 * A day picked by stepping, not by a calendar popup: the calendar is already on screen
 * behind the dialog, and the day it was opened on is nearly always the right one. One or
 * two days either way is the whole use for this control; anything further is a tap on the
 * grid before the dialog is opened.
 */
@Composable
private fun DayField(label: String, day: LocalDate, onChange: (LocalDate) -> Unit) {
    val colors = LocalGachiColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = colors.inkMuted, modifier = Modifier.padding(end = 4.dp))
        IconButton(onClick = { onChange(day.minusDays(1)) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "A day earlier",
                tint = colors.inkSecondary,
            )
        }
        Text(
            fmtWeekdayDay(day),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChange(day.plusDays(1)) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "A day later",
                tint = colors.inkSecondary,
            )
        }
    }
}

/** The rule as a segment label; the domain codes are storage, not English. */
private fun ruleLabel(rule: String): String = when (rule) {
    REPEAT_DAILY -> "Every day"
    REPEAT_WEEKLY -> "Every week"
    else -> "Once"
}

/** Hours a session tends to start at — one tap instead of four digits. */
private val QUICK_TIMES = listOf("07:00", "09:00", "12:00", "18:00", "20:00")

/**
 * Where the caret goes in [text] once [digits] digits are behind it.
 *
 * The field's contents are rewritten under the caret on every keystroke, so an offset means
 * nothing across the rewrite — a colon appearing in front of the caret moves everything after
 * it along by one. What DOES survive is how many digits the user has typed past, because
 * inserting a separator never changes that count. So the offset is thrown away and rebuilt:
 * count digits in the new text, stop after the [digits]-th, and sit just behind it.
 *
 * Landing just behind the last digit rather than after the separator that may follow it is
 * what makes deleting work: backspace on "17:0" takes the zero, not the colon.
 */
private fun caretAfterDigits(text: String, digits: Int): Int {
    if (digits <= 0) return 0
    var seen = 0
    text.forEachIndexed { index, char ->
        if (char.isDigit()) {
            seen++
            if (seen == digits) return index + 1
        }
    }
    return text.length
}

/**
 * The Material 3 clock: the third way to a time, for the ones the chips do not cover.
 *
 * Both of its faces are offered — the dial and the platform's own two-field keyboard entry
 * — because the dial is quicker for a round hour and hopeless for 21:35. It writes the
 * canonical "HH:MM" straight into the field, which is a value the parser always accepts, so
 * the clock is also the way out of a half-typed time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(initial: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val minutes = parseMinuteOfDay(initial)
    val state = rememberTimePickerState(
        initialHour = (minutes ?: DEFAULT_PICKER_MINUTE) / 60,
        initialMinute = (minutes ?: DEFAULT_PICKER_MINUTE) % 60,
        is24Hour = true,
    )
    var keyboard by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Time of the session", style = MaterialTheme.typography.labelLarge)
                if (keyboard) TimeInput(state = state) else TimePicker(state = state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { keyboard = !keyboard }) {
                        Text(if (keyboard) "Clock" else "Keyboard")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(formatTime(state.hour, state.minute)) }) {
                        Text("Set")
                    }
                }
            }
        }
    }
}

/** Where the clock opens when the slot has no time yet: the usual evening session. */
private const val DEFAULT_PICKER_MINUTE = 18 * 60
