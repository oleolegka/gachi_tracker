package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.REPEAT_DAILY
import xyz.oleolegka.gachimuchi.domain.REPEAT_NONE
import xyz.oleolegka.gachimuchi.domain.REPEAT_RULES
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.deletionWarning
import xyz.oleolegka.gachimuchi.domain.newSlotDraft
import xyz.oleolegka.gachimuchi.domain.nextOccurrence
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
    val problem = draft.problem()
    val anchor = remember(draft.anchorDate) {
        runCatching { LocalDate.parse(draft.anchorDate) }.getOrDefault(day)
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
                        onValueChange = { draft = draft.copy(timeText = it) },
                        label = { Text("Time (optional)") },
                        placeholder = { Text("18:00") },
                        singleLine = true,
                        isError = draft.timeText.isNotBlank() && parseSlotTime(draft.timeText) == null,
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
                // the chips cover the hours a session actually starts at
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QUICK_TIMES.forEach { time ->
                        SiblingChip(
                            text = time,
                            selected = parseSlotTime(draft.timeText) == time,
                            accent = colors.accent,
                            onClick = { draft = draft.copy(timeText = time) },
                        )
                    }
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
