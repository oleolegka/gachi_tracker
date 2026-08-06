package xyz.oleolegka.gachimuchi.ui

import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.Bodyweight
import xyz.oleolegka.gachimuchi.domain.Cardio
import xyz.oleolegka.gachimuchi.domain.Duration
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.Tick
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Number and string formatting for the screens. Labels are spelled out rather than
 * cryptic (project rule); units use their standard symbols.
 *
 * Dates come from java.time with the locale pinned to [Locale.ENGLISH] instead of the
 * device locale: the app ships a single language, and month names following the system
 * language would clash with the rest of the interface.
 */

fun fmtKg(x: Double): String {
    val r = (x * 10).roundToInt() / 10.0
    return if (r == r.toLong().toDouble()) "${r.toLong()} kg" else "$r kg"
}

fun fmtDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return when {
        m >= 60 -> "${m / 60} h ${m % 60} min"
        m > 0 && s > 0 -> "$m min $s s"
        m > 0 -> "$m min"
        else -> "$s s"
    }
}

fun fmtPace(secPerKm: Double): String {
    val total = secPerKm.roundToInt()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')} /km"
}

fun fmtDistance(m: Double): String =
    if (m >= 1000) "${(m / 100).roundToInt() / 10.0} km" else "${m.roundToInt()} m"

private val dayFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/** Weekday headers of the calendar grid, Monday first (the grid starts on Monday). */
val weekdayShort: List<String> =
    DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

fun fmtDay(d: LocalDate): String = d.format(dayFormat)

fun fmtMonth(d: LocalDate): String = d.format(monthFormat)

/** Activity name taken from the form (body weight has no name — its role is used instead). */
fun ActivityForm.displayName(): String = when (this) {
    is StrengthSet -> exercise
    is HoldSet -> activity
    is Duration -> activity
    is Tick -> activity
    is Cardio -> activity
    is Bodyweight -> "Body weight"
}

/** One-line description of an entry for lists: only what was actually recorded. */
fun ActivityForm.summaryLine(): String = when (this) {
    is StrengthSet -> buildString {
        weightKg?.let { append(fmtKg(it)); append(" × ") }
        if (ownWeight) {
            append("body weight")
            addedKg?.let { append(" +${fmtKg(it)}") }
            append(" × ")
        }
        append("$reps reps")
        restAfterSec?.let { append(", rest ${fmtDuration(it.toInt())}") }
    }

    is HoldSet -> buildString {
        // §12-A: edge and protocol are properties of the exercise, so the added weight is
        // what matters in a set line; edge and protocol are shown only as context
        addedKg?.let { append(fmtKg(it)); append(", ") }
        reps?.let { append("$it reps") }
        holdSec?.let { if (reps != null) append(" × "); append(fmtDuration(it.toInt())) }
        if (workSec != null && restSec != null) {
            append(", ${workSec.toInt()}:${restSec.toInt()} protocol")
        }
        edgeMm?.let { append(", ${it.toInt()} mm edge") }
    }

    is Duration -> fmtDuration(durationSec)

    is Tick -> "check-in"

    is Cardio -> buildString {
        distanceM?.let { append(fmtDistance(it)) }
        durationSec?.let { if (isNotEmpty()) append(", "); append(fmtDuration(it)) }
        paceSecPerKm?.let { if (isNotEmpty()) append(", "); append(fmtPace(it)) }
    }

    is Bodyweight -> fmtKg(weightKg)
}
