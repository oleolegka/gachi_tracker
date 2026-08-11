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
 */
@Composable
fun StepperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    steps: List<Double>,
    modifier: Modifier = Modifier,
    decimal: Boolean = true,
    placeholder: String? = null,
) {
    val colors = LocalGachiColors.current
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.sortedDescending().forEach { step ->
                StepButton("-${formatNumber(step)}", Modifier.width(STEP_BUTTON_WIDTH)) {
                    onValueChange(applyStep(value, -step))
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = placeholder?.let { { Text(it, textAlign = TextAlign.Center) } },
                textStyle = TextStyle(fontSize = 18.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )
            steps.sorted().forEach { step ->
                StepButton("+${formatNumber(step)}", Modifier.width(STEP_BUTTON_WIDTH)) {
                    onValueChange(applyStep(value, step))
                }
            }
        }
    }
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
        Text(label, fontSize = 13.sp, maxLines = 1)
    }
}

/** How wide a step button is when it sits BESIDE the field, as it does on this one. */
private val STEP_BUTTON_WIDTH = 50.dp
