package xyz.oleolegka.gachimuchi.ui

import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.Bodyweight
import xyz.oleolegka.gachimuchi.domain.Cardio
import xyz.oleolegka.gachimuchi.domain.Duration
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.HoldSide
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

/**
 * Weight added to your own body weight, WITH ITS SIGN: "+20 kg", "-20 kg".
 *
 * ── Why this is not a "+" in front of [fmtKg] ───────────────────────────────────
 * Added weight is a SIGNED axis (see [xyz.oleolegka.gachimuchi.domain.StrengthSet.addedKg]):
 * a minus means the load was reduced by a band or a counterweight, which is an ordinary way
 * to train a pull-up nobody can do yet. Callers used to write `" +${fmtKg(it)}"`, so a hang
 * off a band that took twenty kilograms printed as "+-20 kg" — two signs in a row, and a
 * reader left to work out which of them was meant.
 *
 * The sign is decided by what [fmtKg] ACTUALLY PRINTED rather than by the input, so exactly
 * one sign comes out in every case: a value that rounds away to zero prints "+0 kg" instead
 * of a "-0 kg" that claims an assistance nobody had.
 */
fun fmtAddedKg(x: Double): String {
    val text = fmtKg(x)
    return if (text.startsWith("-")) text else "+$text"
}

/**
 * AN IMPULSE, in kilogram-seconds: "2940 kg·s".
 *
 * ── Why this is not printed as anything shorter ─────────────────────────────────
 * A session's impulse runs into five figures and a month's into six, so the temptation is to
 * compact it to "17k" the way [fmtDistance] turns metres into kilometres. That is exactly
 * where a chart starts lying: the axis title is chosen from the LARGEST value of a series
 * while each tick is formatted on its own, so a compacting threshold makes the small ticks
 * print in one unit under a title naming another. Kilogram-seconds are already an invented
 * quantity ([xyz.oleolegka.gachimuchi.domain.holdImpulseKgSec] says so at length); they do
 * not also need a magnitude a reader has to reconstruct.
 *
 * The fraction is dropped instead. A tenth of a kilogram-second is a hundredth of a second of
 * hanging, which is below anything a hangboard set is recorded to.
 */
fun fmtKgSec(x: Double): String = "${fmtWholeKgSec(x)} kg·s"

/** The bare number of [fmtKgSec], with no unit: for the callers that typeset the unit apart. */
private fun fmtWholeKgSec(x: Double): String =
    if (x.isFinite()) kotlin.math.round(x).toLong().toString() else "0"

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
 * How a side is named on screen. The enum's [HoldSide.code] is the STORED value and is not a
 * label: it is the payload's own spelling and must stay readable by anything that does not
 * know this app, so the wording lives here where every other piece of wording does.
 */
fun HoldSide.label(): String = when (this) {
    HoldSide.LEFT -> "Left"
    HoldSide.RIGHT -> "Right"
}

/**
 * A series value in its own units, for tile headlines and record lines.
 *
 * The domain says what KIND a number is ([ValueFormat]); printing it is a UI concern, so
 * the two never have to agree on wording — only on the kind.
 */
fun fmtValue(value: Double, format: ValueFormat): String = when (format) {
    ValueFormat.KILOGRAMS -> fmtKg(value)
    ValueFormat.KILOGRAM_SECONDS -> fmtKgSec(value)
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
    ValueFormat.KILOGRAM_SECONDS -> fmtWholeKgSec(value) to "kg·s"
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
    // deliberately not compacted, so a tick can never read in a different unit from the
    // title above it -- see [fmtKgSec]
    ValueFormat.KILOGRAM_SECONDS -> fmtWholeKgSec(value)
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

/**
 * A value drawn INSIDE the plot — the number over a bar, the callout on the last point of a
 * line — WITH ITS UNIT: "2940 kg·s", "108 kg", "5:00 /km".
 *
 * ── Why this exists, and where the line between it and [fmtAxis] runs ───────────
 * A tick on the Y axis is the SCALE: it repeats four or five times up the side of the card,
 * and "kg" beside every one of them is noise, so the unit is stated once in the caption beside
 * the chart's title ([axisUnit]). A number printed on the data itself is not the scale — it is
 * the figure the reader takes away and quotes, and it was going out bare. An impulse is where
 * that bites hardest: kilogram-seconds are this app's own construction
 * ([xyz.oleolegka.gachimuchi.domain.holdImpulseKgSec] says so at length), so "2940" over a bar
 * means nothing at all to anybody who has not just read the caption (backlog.md §14.2).
 *
 * Built on [fmtAxis] rather than on [fmtValueParts] so that a label and the ticks under it are
 * spelled the same way. [ValueFormat.SECONDS] is left exactly as [fmtAxis] spells it — "45s",
 * "42m", "1.2h" already carry their unit — and a [ValueFormat.COUNT] has none to carry.
 */
fun fmtOnChart(value: Double, format: ValueFormat): String = when (format) {
    ValueFormat.SECONDS, ValueFormat.COUNT -> fmtAxis(value, format)
    else -> "${fmtAxis(value, format)} ${axisUnit(format, value)}"
}

/** The unit an axis title should carry, or "" when the numbers speak for themselves. */
fun axisUnit(format: ValueFormat, maxValue: Double): String = when (format) {
    ValueFormat.KILOGRAMS -> "kg"
    // the one unit here that does not follow the magnitude, on purpose ([fmtKgSec])
    ValueFormat.KILOGRAM_SECONDS -> "kg·s"
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

/*
 * There used to be a `LOG_BUTTON_LABEL` here, for the floating button on Today, and a long
 * note arguing about its wording — "Log a set" against "Start workout" — on the grounds that
 * a session had no beginning and no end to start.
 *
 * A session has one now (domain/Workout.kt), and the button is gone with the screen that
 * carried it: the primary action is on the CARD of the thing being done, so it never has to
 * be named generically. What replaced the argument is that each card names its own action —
 * "Start" on a plan, "Continue" on the workout in progress — and each is unambiguous because
 * the thing it acts on is right beside it.
 */

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
            // signed by [fmtAddedKg] and never by a "+" written here: assistance is a
            // negative added weight, and a hard-coded plus printed it as "+-20 kg"
            addedKg?.let { append(" ${fmtAddedKg(it)}") }
            append(" × ")
        }
        append("$reps reps")
        restAfterSec?.let { append(", rest ${fmtDuration(it.toInt())}") }
    }

    is HoldSet -> buildString {
        // §12-A: the protocol is a property of the exercise, so the added weight is what
        // matters in a set line; the protocol is shown only as context
        // signed too, and for a hangboard the sign is the more important half: most of the
        // work happens on the negative side of this axis, and a bare "15 kg" said neither
        // which direction it went nor that it was ADDED weight rather than an absolute one
        addedKg?.let { append(fmtAddedKg(it)); append(", ") }
        reps?.let { append("$it reps") }
        holdSec?.let { if (reps != null) append(" × "); append(fmtDuration(it.toInt())) }
        if (workSec != null && restSec != null) {
            append(", ${workSec.toInt()}:${restSec.toInt()} protocol")
        }
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
