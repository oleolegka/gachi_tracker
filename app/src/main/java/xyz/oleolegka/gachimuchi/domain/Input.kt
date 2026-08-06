package xyz.oleolegka.gachimuchi.domain

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Parsing and formatting of the numbers typed on the logging screen.
 *
 * This lives in `domain` rather than in `ui` for one reason: it is the layer where a
 * mistyped string turns into a number that is about to be written into the journal, and
 * that deserves tests. The functions are pure Kotlin — no Android, no Compose.
 *
 * The guiding rule is BE LENIENT WHEN READING: a phone keyboard may produce a comma as
 * the decimal separator, a field may be left empty, and the value may carry spaces. None
 * of that should crash the screen — an unreadable value is simply `null`, and the caller
 * decides what to do (usually: keep the Add button disabled).
 */

/** A number out of a text field: comma or dot, spaces trimmed. Junk and NaN give null. */
fun parseNumber(text: String): Double? {
    val cleaned = text.trim().replace(',', '.')
    if (cleaned.isEmpty()) return null
    val value = cleaned.toDoubleOrNull() ?: return null
    return if (value.isFinite()) value else null
}

/** A whole count (reps, minutes) out of a text field. Negative values give null. */
fun parseCount(text: String): Int? = parseNumber(text)?.let { if (it < 0) null else it.roundToInt() }

/**
 * A number for a text field: rounded to two decimals with the trailing zeros trimmed, so
 * that a `+2.5` step does not turn "60" into "62.50000000000001".
 */
fun formatNumber(x: Double): String {
    val rounded = (x * 100).roundToLong() / 100.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

/**
 * Applies a +/- step button to the current field contents. An empty (or unreadable)
 * field counts as zero, and the result is clamped at [min] — the buttons must never
 * produce a negative weight, which the form validators would reject anyway.
 */
fun applyStep(text: String, delta: Double, min: Double = 0.0): String =
    formatNumber(max(min, (parseNumber(text) ?: 0.0) + delta))

/**
 * A running pace: "4:30" (minutes:seconds per km) or plain seconds. Returns seconds per
 * km. Seconds outside 0..59 are a typo rather than a pace, so they give null.
 */
fun parsePace(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if (!t.contains(':')) return parseNumber(t)?.takeIf { it > 0 }
    val parts = t.split(':', limit = 2)
    val minutes = parts[0].trim().toIntOrNull() ?: return null
    val seconds = parts[1].trim().toIntOrNull() ?: return null
    if (minutes < 0 || seconds !in 0..59) return null
    val total = minutes * 60 + seconds
    return if (total > 0) total.toDouble() else null
}

/** Seconds per km back into "4:30" (for prefilling the field from the previous entry). */
fun formatPace(secPerKm: Double): String {
    val total = secPerKm.roundToInt()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
