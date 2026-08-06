package xyz.oleolegka.gachimuchi.ui

import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.Bodyweight
import xyz.oleolegka.gachimuchi.domain.Cardio
import xyz.oleolegka.gachimuchi.domain.Duration
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.Tick
import xyz.oleolegka.gachimuchi.domain.ValueFormat
import xyz.oleolegka.gachimuchi.domain.activityName
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
private val shortDayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val shortMonthFormat = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
private val weekdayDayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** Weekday headers of the calendar grid, Monday first (the grid starts on Monday). */
val weekdayShort: List<String> =
    DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

fun fmtDay(d: LocalDate): String = d.format(dayFormat)

fun fmtMonth(d: LocalDate): String = d.format(monthFormat)

/** Activity name taken from the form (body weight has no name — its role is used instead). */
fun ActivityForm.displayName(): String = activityName()

/**
 * A series value in its own units, for tile headlines and record lines.
 *
 * The domain says what KIND a number is ([ValueFormat]); printing it is a UI concern, so
 * the two never have to agree on wording — only on the kind.
 */
fun fmtValue(value: Double, format: ValueFormat): String = when (format) {
    ValueFormat.KILOGRAMS -> fmtKg(value)
    ValueFormat.SECONDS -> fmtDuration(value.roundToInt())
    ValueFormat.PACE -> fmtPace(value)
    ValueFormat.DISTANCE -> fmtDistance(value)
    ValueFormat.COUNT -> fmtCount(value)
}

/**
 * A value split into the number and its unit, for the tiles that typeset the two at
 * different sizes ("108" large, "kg" small beside it). The unit is null when the number
 * carries its own (a pace reads "5:00 /km", a count reads as itself).
 */
fun fmtValueParts(value: Double, format: ValueFormat): Pair<String, String?> = when (format) {
    ValueFormat.KILOGRAMS -> fmtCount((value * 10).roundToInt() / 10.0) to "kg"
    ValueFormat.SECONDS -> when {
        value >= 3600 -> fmtCount((value / 360).roundToInt() / 10.0) to "h"
        value >= 60 -> fmtCount((value / 60).roundToInt().toDouble()) to "min"
        else -> fmtCount(value) to "s"
    }
    ValueFormat.PACE -> fmtAxis(value, format) to "/km"
    ValueFormat.DISTANCE -> if (value >= 1000) {
        fmtCount((value / 100).roundToInt() / 10.0) to "km"
    } else {
        fmtCount(value.roundToInt().toDouble()) to "m"
    }
    ValueFormat.COUNT -> fmtCount(value) to null
}

/**
 * A signed change for a delta caption: "+6 kg", "-1.7 kg", "12 s faster".
 *
 * Plain ASCII signs rather than the mock-up's triangles: the project bans emoji outright,
 * and a glyph that some fonts render in colour is not worth the argument. The word beside
 * it, not the sign, is what says whether the change is good.
 */
fun fmtDelta(change: Double, format: ValueFormat): String {
    val (number, unit) = fmtValueParts(kotlin.math.abs(change), format)
    val sign = if (change >= 0) "+" else "-"
    return if (unit == null) "$sign$number" else "$sign$number $unit"
}

/** A count: whole numbers have no decimal tail, so "12" rather than "12.0". */
fun fmtCount(value: Double): String {
    val r = (value * 10).roundToInt() / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

/**
 * A value for an AXIS TICK — the same units, but as short as the axis can get away with.
 *
 * Axis labels compete for a few dozen pixels: "1 h 12 min" next to "58 min" makes the
 * gridlines unreadable, so time on an axis is one unit throughout and weights lose their
 * unit suffix (which the axis title carries instead).
 */
fun fmtAxis(value: Double, format: ValueFormat): String = when (format) {
    ValueFormat.KILOGRAMS -> fmtCount(value)
    ValueFormat.SECONDS -> if (value >= 3600) "${fmtCount(value / 3600)}h"
        else if (value >= 120) "${(value / 60).roundToInt()}m"
        else "${value.roundToInt()}s"
    ValueFormat.PACE -> {
        val total = value.roundToInt()
        "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
    ValueFormat.DISTANCE -> if (value >= 1000) fmtCount((value / 100).roundToInt() / 10.0) else fmtCount(value)
    ValueFormat.COUNT -> fmtCount(value)
}

/** The unit an axis title should carry, or "" when the numbers speak for themselves. */
fun axisUnit(format: ValueFormat, maxValue: Double): String = when (format) {
    ValueFormat.KILOGRAMS -> "kg"
    ValueFormat.SECONDS -> if (maxValue >= 3600) "h" else if (maxValue >= 120) "min" else "s"
    ValueFormat.PACE -> "/km"
    ValueFormat.DISTANCE -> if (maxValue >= 1000) "km" else "m"
    ValueFormat.COUNT -> ""
}

/**
 * A day with its weekday: "Thu 6 Aug". The calendar and the slot editor both need it —
 * a plan is read as "Thursday" far more often than as "the sixth".
 */
fun fmtWeekdayDay(d: LocalDate): String = d.format(weekdayDayFormat)

/** A short day for a chart axis or a badge: "6 Aug". */
fun fmtShortDay(d: LocalDate): String = d.format(shortDayFormat)

/**
 * How long ago a day was, in words: the caption under a tile title.
 *
 * Anything beyond a week gets an absolute date instead of "23 days ago" — past about a
 * week nobody counts days, and the date is the more useful of the two.
 */
fun fmtRelativeDay(d: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(d, today)
    return when {
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days in 2..6 -> "$days days ago"
        days < 0 -> fmtShortDay(d)
        else -> fmtShortDay(d)
    }
}

/** A record's date for a badge: "Record - 5 Aug" never appears without the day (§12-C). */
fun fmtRecordDate(d: LocalDate, today: LocalDate): String =
    if (ChronoUnit.DAYS.between(d, today) <= 1) fmtRelativeDay(d, today) else fmtShortDay(d)

/** A month for the heatmap ribbon: "Aug". */
fun fmtShortMonth(d: LocalDate): String = d.format(shortMonthFormat)

/**
 * The label on the app's primary button — the one that opens the logging screen.
 *
 * It says LOG, and it says it first. The button used to read "Start workout" / "Continue
 * workout", which describes the wrong half of the app: it is pressed AFTER a set has been
 * done, to write it down, and "start" invites the reading "start the countdown" — which is
 * a real feature here, sitting on a tab of its own two icons away. Naming the button after
 * the thing it records rather than after a session it does not actually start also matches
 * the data model, where a session has no beginning and no end (see domain/Session.kt).
 *
 * ONE wording, always. It briefly had two — "Log a set" before the day's first entry and
 * "Log another set" after — which told the user something they could already see (the
 * entries are on the screen underneath) while implying a difference that does not exist:
 * both open the same screen in the same state. A control that rewords itself for no change
 * in behaviour makes the reader stop and look for the change.
 */
const val LOG_BUTTON_LABEL = "Log a set"

/** A rest between sets, in the compact "2:30" shape the session feed uses. */
fun fmtRest(sec: Double): String {
    val total = sec.roundToInt()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
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
