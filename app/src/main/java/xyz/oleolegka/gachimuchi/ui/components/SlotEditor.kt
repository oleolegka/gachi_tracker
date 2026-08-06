package xyz.oleolegka.gachimuchi.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import xyz.oleolegka.gachimuchi.domain.REPEAT_DAILY
import xyz.oleolegka.gachimuchi.domain.REPEAT_NONE
import xyz.oleolegka.gachimuchi.domain.REPEAT_RULES
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.deletionWarning
import xyz.oleolegka.gachimuchi.domain.formatTime
import xyz.oleolegka.gachimuchi.domain.formatTimeDigits
import xyz.oleolegka.gachimuchi.domain.newSlotDraft
import xyz.oleolegka.gachimuchi.domain.nextOccurrence
import xyz.oleolegka.gachimuchi.domain.parseMinuteOfDay
import xyz.oleolegka.gachimuchi.domain.parseSlotTime
import xyz.oleolegka.gachimuchi.domain.problem
import xyz.oleolegka.gachimuchi.domain.problemText
import xyz.oleolegka.gachimuchi.domain.repeatLabel
import xyz.oleolegka.gachimuchi.domain.toDraft
import xyz.oleolegka.gachimuchi.domain.toSlot
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
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
 * Half a time is not a time: "17:0" on screen means the last digit is still coming, so
 * `parseSlotTime` refuses it, the field turns red and Save stays disabled. The alternative
 * — reading it as 17:00 — would store a time the user did not type whenever they meant
 * 17:05, and would do it silently.
 */
@Composable
fun SlotEditorDialog(
    initial: Slot?,
    day: LocalDate,
    suggestions: List<String>,
    today: LocalDate,
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
    val problem = draft.problem()
    val anchor = remember(draft.anchorDate) {
        runCatching { LocalDate.parse(draft.anchorDate) }.getOrDefault(day)
    }
    val parsedTime = parseSlotTime(draft.timeText)
    // digits that are not a time yet: saving would drop or invent the minutes
    val timeBroken = draft.timeText.isNotBlank() && parsedTime == null

    if (clockOpen) {
        TimePickerSheet(
            initial = parsedTime,
            onPick = {
                draft = draft.copy(timeText = it)
                clockOpen = false
            },
            onDismiss = { clockOpen = false },
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
                        value = draft.timeText,
                        // the colon is inserted for the user: it is not on the number keypad
                        onValueChange = { draft = draft.copy(timeText = formatTimeDigits(it)) },
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
                        TextButton(onClick = { draft = draft.copy(timeText = "") }) { Text("Clear") }
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
                            onClick = { draft = draft.copy(timeText = time) },
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
