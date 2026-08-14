package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.formatDurationDigits
import xyz.oleolegka.gachimuchi.domain.formatDurationSec
import xyz.oleolegka.gachimuchi.domain.parseDurationText
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

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
 * ── Down as well as up ───────────────────────────────────────────────────────
 * Each bump gets a minus to match. It used to offer additions only, on the argument that
 * backspacing a digit is the way down — which is true at a desk and false in a hand: from
 * the phone, 2026-08-11, "there are only the +10 and +30 buttons, no minus", on a screen
 * reached mid-workout. Overshooting by one tap is the ordinary way this field is used, and
 * the way back has to cost the same one tap.
 *
 * A minus never goes below [minSec] (zero unless the caller says otherwise): a negative
 * length of time is not a value any caller can store, and clamping is what the buttons
 * do — the FIELD still accepts anything typed, and the caller still decides through
 * [isError] what it thinks of it.
 *
 * ── The buttons sit UNDER the field, not beside it ───────────────────────────
 * Four of them beside a field leaves the field itself a sliver on a phone. Under it, the
 * value gets the full width and each button gets a quarter of it, which is well past the
 * 48dp a thumb needs.
 *
 * ── The caret is pinned to the END, and that is the whole point ──────────────
 * Reported from a phone, 2026-08-14: "когда сама пишу время, вводит символы в рандомное, а не
 * желаемое, место", with a screenshot of a field reading "000:50".
 *
 * The field held a plain `String` and rewrote it on every keystroke, which leaves the caret
 * wherever the platform last put it — a tap into the middle of "0:50", most often. The next
 * digit then goes in at the tap rather than at the end, so the register fills from the WRONG
 * side and zeros pile up in front. Two of those is exactly the "000:50" in the screenshot.
 *
 * So the value is held as a `TextFieldValue` and the caret goes to the end after every change.
 * There is nothing arbitrary about that position: a register filled from the right has exactly
 * one place where a keystroke means anything, and the end is it. Typing anywhere adds the ones
 * of seconds and shifts everything left; backspace takes the last digit and shifts everything
 * right. A caret parked mid-text can only make one of those two lie.
 *
 * This is NOT what the time-of-day field next door does ([SlotEditorDialog] keeps its caret
 * where the digits are, via `caretAfterDigits`), and the difference is real rather than an
 * oversight: that field types LEFT to right into a fixed HH:MM shape and never invents a digit,
 * so "how many digits are in front of the caret" survives its rewrite. This one pads the
 * seconds and can add a minute, so the same count means something different before and after —
 * a caret rebuilt from it walks backwards ("1", "3", "0" lands on 3:01, not 1:30).
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
    /** Seconds each button adds and takes away — e.g. `listOf(10, 30)` for a rest. */
    bumpsSec: List<Int>,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    /** The floor the minus buttons stop at. Zero unless the caller has a real minimum. */
    minSec: Int = 0,
) {
    val colors = LocalGachiColors.current
    val keyboard = LocalSoftwareKeyboardController.current

    // the caret is this field's own business — see the header for why an offset cannot
    // survive the rewrite this field does on every keystroke
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // a bump, or any other caller writing from outside, has the last word and lands at the end
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))

    fun bump(deltaSec: Int) {
        val now = parseDurationText(value) ?: 0
        onValueChange(formatDurationSec((now + deltaSec).coerceAtLeast(minSec)))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
        OutlinedTextField(
            value = field,
            // reformatted on every keystroke and the caret put back at the end: see the class
            // KDoc for why the end is the only place it can be
            onValueChange = { typed ->
                val formatted = formatDurationDigits(typed.text)
                field = TextFieldValue(formatted, TextRange(formatted.length))
                onValueChange(formatted)
            },
            modifier = Modifier
                .fillMaxWidth()
                /*
                 * The label is drawn as a Text ABOVE this field rather than passed to it,
                 * so without this the field has no accessible name at all: a screen reader
                 * announces an unnamed edit box, and nothing can address it by what it is.
                 * Naming it here fixes both.
                 */
                .then(
                    if (label != null) {
                        Modifier.semantics { contentDescription = label }
                    } else {
                        Modifier
                    }
                ),
            singleLine = true,
            isError = isError,
            textStyle = TextStyle(fontSize = TextSize.Figure, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.Tight),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // labelled in plain seconds, not mm:ss — a bump is always well under a minute
            // in practice, and "+30s" reads at a glance where "+0:30" does not. Biggest step
            // outermost, so the pair either side of the middle is the fine adjustment.
            bumpsSec.sortedDescending().forEach { step ->
                StepButton("-${step}s", Modifier.weight(1f)) { bump(-step) }
            }
            bumpsSec.sorted().forEach { step ->
                StepButton("+${step}s", Modifier.weight(1f)) { bump(step) }
            }
        }
    }
}
