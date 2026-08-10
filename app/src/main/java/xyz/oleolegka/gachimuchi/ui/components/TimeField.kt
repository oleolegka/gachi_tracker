package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.formatDurationDigits
import xyz.oleolegka.gachimuchi.domain.formatDurationSec
import xyz.oleolegka.gachimuchi.domain.parseDurationText
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors

/**
 * A length of time, typed as mm:ss with free entry — the one way this app asks "how long"
 * now, used for the rest chosen in the slot editor, the rest asked mid-workout, and a
 * duration entry (§13.9).
 *
 * ── What this replaces ────────────────────────────────────────────────────────
 * Three screens, three answers: a ladder of preset chips with no way past its own ceiling
 * (SlotEditor's rest, capped at 4:00 because that was the last chip); free entry, but in bare
 * SECONDS with no way to type "five minutes" as anything but 300 (the live workout's rest
 * dialog); and a MINUTES field that reached a whole number of seconds only through a decimal
 * point, "0.5" for thirty seconds (LogScreen's duration entry — the owner's own word for it
 * was "шиза"). One field, mm:ss, free entry, with the bumps each caller actually wants.
 *
 * ── Only "+", never "-" ──────────────────────────────────────────────────────
 * Unlike [StepperField] this offers no way down: backspacing a digit already does that, and
 * every caller of this field asked for additions only (+30/+10 for a rest, +10 for a
 * duration) — see this component's own callers for the exact numbers.
 *
 * ── The ceiling is the caller's, not this field's ────────────────────────────
 * [parseDurationText] refuses nothing but a broken value (seconds past 59); how HIGH a typed
 * value is allowed to go is a question with a different answer for a rest (a day,
 * [xyz.oleolegka.gachimuchi.domain.MAX_REST_INPUT_SEC]) and for a protocol step (an hour,
 * [xyz.oleolegka.gachimuchi.domain.MAX_STEP_SEC]) — see that constant's own KDoc for why they
 * are not the same number. So the caller decides what counts as valid and passes [isError]
 * accordingly; this field only ever refuses to show something that is not a time at all.
 */
@Composable
fun TimeField(
    /** Drawn above the field, or omitted when a caller already labels it some other way. */
    label: String?,
    /** The field's own text, exactly as typed — hold this in [formatDurationDigits]'s shape. */
    value: String,
    onValueChange: (String) -> Unit,
    /** Seconds each button adds, smallest first — e.g. `listOf(10, 30)` for a rest. */
    bumpsSec: List<Int>,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val colors = LocalGachiColors.current
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                // reformatted on every keystroke, not read back from the field's own cursor:
                // see the class KDoc for why a new digit is always the ones of seconds
                onValueChange = { onValueChange(formatDurationDigits(it)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = isError,
                textStyle = TextStyle(fontSize = 18.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )
            bumpsSec.sorted().forEach { bump ->
                // labelled in plain seconds, not mm:ss — a bump is always well under a minute
                // in practice, and "+30s" reads at a glance where "+0:30" does not
                StepButton("+${bump}s") {
                    onValueChange(formatDurationSec((parseDurationText(value) ?: 0) + bump))
                }
            }
        }
    }
}
