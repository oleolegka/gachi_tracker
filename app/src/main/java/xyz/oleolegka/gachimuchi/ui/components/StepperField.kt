package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.applyStep
import xyz.oleolegka.gachimuchi.domain.formatNumber
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * A number field with +/- buttons on both sides — the workhorse of the logging screen.
 *
 * Two ways in, on purpose. The buttons cover the common move (bump the weight by one
 * plate, add a rep) without ever opening the keyboard, which matters when the phone is
 * balanced on a bench between sets. The field itself takes the SYSTEM numeric keyboard
 * for everything else — a hand-rolled numpad would be one more thing to learn and would
 * lose the platform's editing behaviour for nothing.
 *
 * Tap targets are 48dp, the minimum that is reliably hittable with a thumb, and the row
 * is laid out negatives-field-positives so that the two most used steps sit closest to
 * the value they change.
 *
 * ── [stacked]: the same field with its buttons UNDERNEATH ────────────────────
 * Beside the field is right where the field has a screen's width to sit in. Inside a
 * DIALOG it is not: four buttons of 50dp each, plus the gaps, leave the value itself
 * about 48dp — narrower than any one of the buttons changing it, which is what the run
 * offer looked like. [stacked] is the layout [TimeField] already uses for exactly this
 * reason: the field takes the full width and the four buttons share it out below, a
 * quarter each, which on the narrowest phone this app targets is still 57dp.
 */
@Composable
fun StepperField(
    /** Drawn above the field. Null when the caller heads the block some other way. */
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    steps: List<Double>,
    modifier: Modifier = Modifier,
    decimal: Boolean = true,
    placeholder: String? = null,
    /** Buttons under the field, sharing its width, rather than beside it. See the KDoc. */
    stacked: Boolean = false,
    /** What a screen reader calls the field, for callers that pass no [label]. */
    fieldDescription: String? = null,
    /**
     * Whether the value this field holds has a NEGATIVE HALF, and the minus buttons may
     * therefore walk into it.
     *
     * Off for everything a body can only have a positive amount of — reps, seconds, body
     * weight, the load on a bar. On for added weight, which is signed
     * ([xyz.oleolegka.gachimuchi.domain.StrengthSet.addedKg]): below zero is a band taking
     * load OFF the hang, which on a fingerboard is the half of the axis most of the training
     * actually happens on.
     *
     * ── Why the buttons and not just the keyboard ───────────────────────────────
     * The field takes [KeyboardType.Decimal], which asks Android for a numeric pad and does
     * NOT ask for a signed one; whether a minus key is on it is up to whichever keyboard is
     * installed. So on the offer after a run — where the four step buttons ARE the control,
     * sitting under the field a quarter of the width each — "minus fifteen" could be
     * unreachable altogether, and pressing minus from zero looked like the app refusing the
     * press for no stated reason. With this the buttons reach it in three taps.
     */
    signed: Boolean = false,
) {
    val colors = LocalGachiColors.current
    // no floor at all when the axis is signed; [applyStep] clamps at this
    val floor = if (signed) Double.NEGATIVE_INFINITY else 0.0

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Line),
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
        if (stacked) {
            NumberField(
                value = value,
                onValueChange = onValueChange,
                decimal = decimal,
                placeholder = placeholder,
                description = fieldDescription,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
            ) {
                steps.sortedDescending().forEach { step ->
                    StepButton("-${formatNumber(step)}", Modifier.weight(1f)) {
                        onValueChange(applyStep(value, -step, floor))
                    }
                }
                steps.sorted().forEach { step ->
                    StepButton("+${formatNumber(step)}", Modifier.weight(1f)) {
                        onValueChange(applyStep(value, step, floor))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                steps.sortedDescending().forEach { step ->
                    StepButton("-${formatNumber(step)}", Modifier.width(STEP_BUTTON_WIDTH)) {
                        onValueChange(applyStep(value, -step, floor))
                    }
                }
                NumberField(
                    value = value,
                    onValueChange = onValueChange,
                    decimal = decimal,
                    placeholder = placeholder,
                    description = fieldDescription,
                    modifier = Modifier.weight(1f),
                )
                steps.sorted().forEach { step ->
                    StepButton("+${formatNumber(step)}", Modifier.width(STEP_BUTTON_WIDTH)) {
                        onValueChange(applyStep(value, step, floor))
                    }
                }
            }
        }
    }
}

/** The field itself, so that both layouts above spell it exactly once. */
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    decimal: Boolean,
    placeholder: String?,
    description: String?,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(
            if (description != null) {
                Modifier.semantics { contentDescription = description }
            } else {
                Modifier
            }
        ),
        singleLine = true,
        placeholder = placeholder?.let { { Text(it, textAlign = TextAlign.Center) } },
        textStyle = TextStyle(fontSize = TextSize.Title, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
    )
}

/**
 * Shared with [TimeField].
 *
 * [modifier] exists for that other caller: this one packs its buttons beside the field and
 * wants them a fixed 50dp wide, while [TimeField] puts a row of four UNDER its field and
 * shares the width out between them. The default keeps this file's own layout unchanged.
 */
@Composable
internal fun StepButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.then(Modifier.height(48.dp)),
        contentPadding = PaddingValues(0.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(label, fontSize = TextSize.Meta, maxLines = 1)
    }
}

/** How wide a step button is when it sits BESIDE the field, as it does on this one. */
private val STEP_BUTTON_WIDTH = 50.dp
