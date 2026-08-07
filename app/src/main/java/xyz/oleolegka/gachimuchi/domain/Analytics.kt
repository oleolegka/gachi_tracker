package xyz.oleolegka.gachimuchi.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Dashboard aggregations: the daily series the charts are drawn from, the activity
 * heatmap, the hero counters and the door tiles of the overview.
 *
 * Like the rest of `domain/`, everything here is a PURE REDUCER over the journal, so the
 * numbers the screens show are testable on the JVM without Compose and without a device.
 * The screens are forbidden to compute anything themselves — a chart that invents its own
 * aggregation is a chart that disagrees with the record block next to it.
 *
 * ── WHY EVERY FORM DOES NOT GET BOTH CHARTS ─────────────────────────────────────
 * The mock-up shows two charts on the detail screen: a TREND line and a VOLUME bar chart.
 * For three of the six forms one of the two does not honestly exist:
 *
 * - a [ExerciseForm.DURATION] entry IS its own volume ("Emil hangs, 9 minutes"), so a
 *   separate volume chart would be the same numbers drawn twice;
 * - a [ExerciseForm.TICK] carries no metric at all — only frequency (decisions.md §3), so
 *   it has a volume (check-ins per day) and no trend whatsoever;
 * - a [ExerciseForm.BODYWEIGHT] weigh-in has no volume — you cannot do "more" of it.
 *
 * In those cases the corresponding series is NULL and the screen shows an empty state
 * that says why, instead of a made-up metric. That is the whole reason [FormSeries] is
 * nullable rather than "empty list".
 */

/** One day of a daily series: the ISO day and the aggregated value. */
data class DayPoint(val opDate: String, val value: Double)

/** How a value should be rendered. The domain names the kind; `ui/Format.kt` prints it. */
enum class ValueFormat { KILOGRAMS, SECONDS, PACE, DISTANCE, COUNT }

/**
 * How several days collapse into one bar or point when the chart is drawn at a coarser
 * granularity.
 *
 * A volume SUMS (two workouts in a week lifted the total of both); a progress metric
 * takes the BEST of the period (your 1RM for a week is your best set of that week, not
 * the sum of your sets, which would be a meaningless number that grows with attendance).
 */
enum class Aggregation { SUM, BEST }

/**
 * What a series means. [lowerIsBetter] exists solely for pace: for every other metric a
 * rising line is progress, and a chart that does not say so about pace reads backwards.
 */
data class SeriesSpec(
    val label: String,
    val format: ValueFormat,
    val aggregation: Aggregation,
    val lowerIsBetter: Boolean = false,
)

/** The move from the previous point to the last one, and whether that counts as progress. */
data class SeriesDelta(val change: Double, val improved: Boolean)

/** A labelled series. Points are ascending by day and carry no gaps for empty periods. */
data class FormSeries(val spec: SeriesSpec, val points: List<DayPoint>) {
    val isEmpty: Boolean get() = points.isEmpty()
    val last: DayPoint? get() = points.lastOrNull()
    val best: DayPoint? get() =
        if (spec.lowerIsBetter) points.minByOrNull { it.value } else points.maxByOrNull { it.value }

    /**
     * The change between the last two points, or null when there is only one.
     *
     * "Improved" is direction-aware: for pace a FALL is progress, for everything else a
     * rise is. A single point has no delta and gets null rather than a zero — "no change"
     * and "nothing to compare with" are different statements.
     */
    fun delta(): SeriesDelta? {
        if (points.size < 2) return null
        val change = points.last().value - points[points.size - 2].value
        return SeriesDelta(change, improved = if (spec.lowerIsBetter) change < 0 else change > 0)
    }

    fun inPeriod(period: Period, today: LocalDate): FormSeries =
        copy(points = points.inPeriod(period, today))

    /** The same series re-bucketed; [Granularity.DAY] returns it unchanged. */
    fun bucketed(granularity: Granularity): FormSeries =
        copy(points = bucketPoints(points, granularity, spec))
}

// --- chart granularity -------------------------------------------------------------------

/** Width of one bar or one point on the detail screen. */
enum class Granularity { DAY, WEEK, MONTH }

/**
 * How wide a bucket should be for a window.
 *
 * Thirty days of daily bars still fit a phone and keep the detail; a year of them is a
 * picket fence 365 bars wide, so anything longer is bucketed by week, and a multi-year
 * history by month. The mock-up's volume chart is drawn by weeks for exactly this
 * reason (research_visual.md §4.2 says "bar of volume by weeks").
 */
fun Period.granularity(spanDays: Int): Granularity = when (this) {
    Period.MONTH -> Granularity.DAY
    Period.QUARTER, Period.YEAR -> Granularity.WEEK
    Period.ALL -> if (spanDays > 400) Granularity.MONTH else Granularity.WEEK
}

/** The day a bucket is labelled by: itself, its Monday, or the first of its month. */
internal fun bucketStart(opDate: String, granularity: Granularity): String = when (granularity) {
    Granularity.DAY -> opDate
    Granularity.WEEK -> LocalDate.parse(opDate).let { it.minusDays(((it.dayOfWeek.value + 6) % 7).toLong()) }.toString()
    Granularity.MONTH -> LocalDate.parse(opDate).withDayOfMonth(1).toString()
}

/** Collapses points into buckets, combining each bucket by the spec's [Aggregation]. */
internal fun bucketPoints(
    points: List<DayPoint>,
    granularity: Granularity,
    spec: SeriesSpec,
): List<DayPoint> {
    if (granularity == Granularity.DAY || points.isEmpty()) return points
    return points.groupBy { bucketStart(it.opDate, granularity) }
        .toSortedMap()
        .map { (start, ofBucket) ->
            val values = ofBucket.map { it.value }
            val value = when (spec.aggregation) {
                Aggregation.SUM -> values.sum()
                Aggregation.BEST -> if (spec.lowerIsBetter) values.min() else values.max()
            }
            DayPoint(start, value)
        }
}

// --- period filter of the detail screen ------------------------------------------------

/**
 * The windows of the segment control. [days] = null means "the whole journal"; the
 * window is inclusive of today, so 30 days is today plus the 29 before it.
 */
enum class Period(val label: String, val days: Int?) {
    MONTH("30 days", 30),
    QUARTER("3 months", 90),
    YEAR("Year", 365),
    ALL("All", null),
}

/** First day of the window, or null for [Period.ALL]. */
fun Period.startDate(today: LocalDate): LocalDate? = days?.let { today.minusDays((it - 1).toLong()) }

/** Keeps the points that fall inside the window (an all-time window keeps everything). */
fun List<DayPoint>.inPeriod(period: Period, today: LocalDate): List<DayPoint> {
    val from = period.startDate(today)?.toString() ?: return this
    val to = today.toString()
    return filter { it.opDate in from..to }
}

// --- daily series per form -------------------------------------------------------------

/**
 * Entries of one exercise, by day. Body weight is the exception: it carries no
 * exercise_id (it never had one — see [Bodyweight]), so its "exercise" is the form itself
 * and every weigh-in in the journal belongs to it.
 */
private fun formsOf(
    activities: List<ActivityEvent>,
    exercise: ExerciseLink,
    form: ExerciseForm,
): List<ActivityEvent> = if (form == ExerciseForm.BODYWEIGHT) {
    activities.filter { it.form is Bodyweight }
} else {
    activities.filter {
        it.type == form.eventType && it.form.exerciseLink()?.matches(exercise) == true
    }
}

/**
 * The same entries with the WARM-UPS taken out — what volume and the progress axes are
 * computed over.
 *
 * Only the two forms that can carry the flag are affected; everything else comes through
 * whole. A day made of nothing but warm-ups therefore produces NO point rather than a zero,
 * which is the honest shape: there was no working volume that day, and a zero bar would
 * claim there was a session that achieved nothing.
 *
 * The day still counts as active and still appears in the feed — that separation is the
 * whole point of the flag, and it is stated on [StrengthSet.warmup].
 */
private fun working(events: List<ActivityEvent>): List<ActivityEvent> = events.filter { ev ->
    when (val form = ev.form) {
        is StrengthSet -> !form.warmup
        is HoldSet -> !form.warmup
        else -> true
    }
}

/** Groups by day, ascending, applying [reduce] to each day's entries; empty days are dropped. */
private fun byDay(
    events: List<ActivityEvent>,
    reduce: (List<ActivityEvent>) -> Double?,
): List<DayPoint> = events.groupBy { it.opDate }
    .toSortedMap()
    .mapNotNull { (day, ofDay) -> reduce(ofDay)?.let { DayPoint(day, it) } }

/**
 * The PROGRESS series of an exercise, or null when the form has no progress metric.
 *
 * - strength: the best estimated 1RM of the day (Epley, the same formula the records use,
 *   so the line and the record badge can never disagree). Days made only of body-weight
 *   sets produce NO point — there is no weight to compute a 1RM from;
 * - holds: the maximum ADDED WEIGHT of the day (§12-A). If the whole history carries no
 *   added weight (a plank), it falls back to the longest hold in seconds, exactly as
 *   [holdRecord] does;
 * - cardio: the best (lowest) PACE of the day, and null when the history has no pace at
 *   all — distance is volume, not progress, and pretending otherwise would draw a chart
 *   that rewards running slower for longer;
 * - duration: the total time of the day (which is also the only thing there is to plot);
 * - body weight: the last weigh-in of the day.
 *
 * WARM-UPS ARE LEFT OUT, exactly as they are from the records. This is a progress axis and
 * it is computed with the record's own formula precisely so that the line and the record
 * badge beside it can never disagree (that promise is the reason Epley appears here at all);
 * a warm-up that counted towards one and not the other would break it.
 */
fun trendSeries(
    activities: List<ActivityEvent>,
    exercise: ExerciseLink,
    form: ExerciseForm,
): FormSeries? {
    val mine = working(formsOf(activities, exercise, form))
    return when (form) {
        ExerciseForm.STRENGTH -> FormSeries(
            SeriesSpec("Estimated 1RM", ValueFormat.KILOGRAMS, Aggregation.BEST),
            byDay(mine) { ofDay ->
                ofDay.mapNotNull { ev ->
                    (ev.form as? StrengthSet)?.let { s -> s.weightKg?.let { est1rm(it, s.reps) } }
                }.maxOrNull()
            },
        )

        ExerciseForm.HOLD -> {
            val holds = mine.mapNotNull { it.form as? HoldSet }
            if (holds.any { it.addedKg != null }) {
                FormSeries(
                    SeriesSpec("Added weight", ValueFormat.KILOGRAMS, Aggregation.BEST),
                    // a clean hold sits on this axis at zero rather than falling off it, for the
                    // reason [evaluateHoldRecord] spells out: with assistance in the history the
                    // day the band came off is the best day, and it must not be a gap in the line
                    byDay(mine) { ofDay ->
                        ofDay.mapNotNull { it.form as? HoldSet }.map { it.addedKg ?: 0.0 }.maxOrNull()
                    },
                )
            } else {
                FormSeries(
                    SeriesSpec("Longest hold", ValueFormat.SECONDS, Aggregation.BEST),
                    byDay(mine) { ofDay -> ofDay.mapNotNull { (it.form as? HoldSet)?.holdSec }.maxOrNull() },
                )
            }
        }

        ExerciseForm.CARDIO -> {
            val paces = mine.mapNotNull { (it.form as? Cardio)?.paceSecPerKm }
            if (paces.isEmpty()) null else FormSeries(
                SeriesSpec("Best pace", ValueFormat.PACE, Aggregation.BEST, lowerIsBetter = true),
                byDay(mine) { ofDay -> ofDay.mapNotNull { (it.form as? Cardio)?.paceSecPerKm }.minOrNull() },
            )
        }

        ExerciseForm.DURATION -> FormSeries(
            SeriesSpec("Time", ValueFormat.SECONDS, Aggregation.SUM),
            byDay(mine) { ofDay ->
                ofDay.mapNotNull { (it.form as? Duration)?.durationSec?.toDouble() }
                    .takeIf { it.isNotEmpty() }?.sum()
            },
        )

        ExerciseForm.BODYWEIGHT -> FormSeries(
            SeriesSpec("Body weight", ValueFormat.KILOGRAMS, Aggregation.BEST),
            byDay(mine) { ofDay -> (ofDay.last().form as? Bodyweight)?.weightKg },
        )

        // a check-in has no metric by definition (§3): frequency is its volume, not a trend
        ExerciseForm.TICK -> null
    }?.takeIf { it.points.isNotEmpty() }
}

/**
 * The VOLUME series of an exercise — how MUCH was done on a day — or null when the form
 * has no volume of its own.
 *
 * - strength: TONNAGE, sets x reps x weight (research_visual.md §4.2). An exercise whose
 *   history carries no weight at all (pull-ups, dips) would make that a flat zero, so
 *   those fall back to total REPS — the same shape of fallback the hold trend uses, and
 *   for the same reason: a chart of zeroes is worse than a chart of a different metric
 *   that is honestly labelled. A MIXED history (some weighted sets, some not) does use
 *   tonnage, and the body-weight sets then contribute nothing to the bar — an
 *   understatement this code does not currently correct;
 * - holds: number of SETS of the day (hangs are counted, not weighed — the weight is the
 *   trend axis);
 * - cardio: total distance of the day, falling back to total time when nothing was
 *   measured in metres;
 * - check-ins: how many were made that day — this is the frequency, and it is the only
 *   statistic a tick has;
 * - duration and body weight: null (see the file header).
 *
 * WARM-UPS DO NOT COUNT. Tonnage is what the working sets moved; ramping up to them is not
 * a smaller version of the same achievement, and letting the empty bar into the bar chart
 * would make a cautious session look like a bigger one.
 */
fun volumeSeries(
    activities: List<ActivityEvent>,
    exercise: ExerciseLink,
    form: ExerciseForm,
): FormSeries? {
    val mine = working(formsOf(activities, exercise, form))
    return when (form) {
        ExerciseForm.STRENGTH -> {
            val sets = mine.mapNotNull { it.form as? StrengthSet }
            if (sets.any { it.weightKg != null }) FormSeries(
                SeriesSpec("Volume, reps x weight", ValueFormat.KILOGRAMS, Aggregation.SUM),
                byDay(mine) { ofDay ->
                    ofDay.sumOf { ev ->
                        (ev.form as? StrengthSet)?.let { s -> (s.weightKg ?: 0.0) * s.reps } ?: 0.0
                    }
                },
            ) else FormSeries(
                SeriesSpec("Reps", ValueFormat.COUNT, Aggregation.SUM),
                byDay(mine) { ofDay -> ofDay.sumOf { (it.form as? StrengthSet)?.reps ?: 0 }.toDouble() },
            )
        }

        ExerciseForm.HOLD -> FormSeries(
            SeriesSpec("Sets", ValueFormat.COUNT, Aggregation.SUM),
            byDay(mine) { ofDay -> ofDay.size.toDouble() },
        )

        ExerciseForm.CARDIO -> {
            val hasDistance = mine.any { (it.form as? Cardio)?.distanceM != null }
            if (hasDistance) FormSeries(
                SeriesSpec("Distance", ValueFormat.DISTANCE, Aggregation.SUM),
                byDay(mine) { ofDay -> ofDay.sumOf { (it.form as? Cardio)?.distanceM ?: 0.0 } },
            ) else FormSeries(
                SeriesSpec("Time", ValueFormat.SECONDS, Aggregation.SUM),
                byDay(mine) { ofDay ->
                    ofDay.sumOf { ((it.form as? Cardio)?.durationSec ?: 0).toDouble() }.takeIf { it > 0 }
                },
            )
        }

        ExerciseForm.TICK -> FormSeries(
            SeriesSpec("Check-ins", ValueFormat.COUNT, Aggregation.SUM),
            byDay(mine) { ofDay -> ofDay.size.toDouble() },
        )

        ExerciseForm.DURATION, ExerciseForm.BODYWEIGHT -> null
    }?.takeIf { it.points.isNotEmpty() }
}

/**
 * The records of an exercise, with their dates (§12-C: a record is never shown bare).
 *
 * Only strength and holds have a record model. Cardio, duration, check-ins and body
 * weight return an EMPTY list, and the screen says so out loud — "no records for this
 * form yet" is a true statement, "no records" would not be.
 */
fun recordsOf(
    activities: List<ActivityEvent>,
    exercise: ExerciseLink,
    form: ExerciseForm,
): List<ExerciseRecord> = when (form) {
    ExerciseForm.STRENGTH -> listOfNotNull(
        strengthRecord(activities, exercise),
        heaviestSet(activities, exercise),
    )

    ExerciseForm.HOLD -> listOfNotNull(holdRecord(activities, exercise))

    else -> emptyList()
}

/**
 * The heaviest single set of a strength exercise, with its date. A second axis next to
 * the estimated 1RM: the 1RM record can be taken by a light-and-many set, and "the most I
 * have ever picked up" is a different question that lifters actually ask.
 */
fun heaviestSet(activities: List<ActivityEvent>, exercise: ExerciseLink): ExerciseRecord? {
    val weighted = activities.mapNotNull { ev ->
        (ev.form as? StrengthSet)
            ?.takeIf { it.exerciseLink()?.matches(exercise) == true && it.weightKg != null && !it.warmup }
            ?.let { it to ev.opDate }
    }
    if (weighted.isEmpty()) return null
    val (best, day) = weighted.maxBy { it.first.weightKg!! }
    return ExerciseRecord(
        exercise, RecordHit.Axis.WEIGHT_AT_REPS, best.weightKg!!, day,
        "heaviest set ${fmtNum(best.weightKg)} kg x ${best.reps}",
    )
}

// --- hangboard siblings (§12-A) --------------------------------------------------------

/** Tokens that carry a hangboard's measurements rather than its name. */
private val MEASUREMENT_WORDS = setOf("mm", "cm", "s", "sec", "kg", "min")

/**
 * The base name shared by the §12-A siblings of a hangboard exercise.
 *
 * "Hangs 20 mm - 7:3" and "Hangs 15 mm - 7:3" are two different catalog rows on purpose
 * (edge and protocol are part of the identity), but on the detail screen they have to be
 * reachable from one another, otherwise comparing this week's 20 mm against last week's
 * 15 mm means walking back to the overview.
 *
 * The grouping is derived from the NAME, with the numbers and the unit words dropped, and
 * that is a heuristic: an exercise named without its edge in the title lands in its own
 * group of one, and the switcher simply does not appear. The edge and protocol shown in
 * the header do NOT come from this parsing — they come from the structured columns on the
 * catalog row, so a wrong guess here can never misreport a measurement.
 */
fun holdBaseKey(name: String): String {
    val norm = normPhrase(name) ?: return ""
    val kept = norm.split(' ').filter { token ->
        token.isNotBlank() &&
            token.none { it.isDigit() } &&
            token !in MEASUREMENT_WORDS
    }
    return kept.joinToString(" ").ifBlank { norm }
}

/** A hangboard exercise as the sibling switcher needs it: identity plus its measurements. */
data class HoldSibling(
    val exerciseId: Long,
    val name: String,
    val edgeMm: Double?,
    val workSec: Double?,
    val restSec: Double?,
)

/**
 * The §12-A siblings of a hold exercise, INCLUDING the one asked about, ordered by edge
 * (thinnest last — a thinner edge is the harder exercise) and then by name.
 *
 * Returns a single-element list when the exercise has no siblings; the screen then hides
 * the switcher rather than showing a chip row with one chip in it.
 */
fun holdSiblings(catalog: List<HoldSibling>, exerciseId: Long): List<HoldSibling> {
    val self = catalog.firstOrNull { it.exerciseId == exerciseId } ?: return emptyList()
    val base = holdBaseKey(self.name)
    return catalog.filter { holdBaseKey(it.name) == base }
        .sortedWith(compareByDescending<HoldSibling> { it.edgeMm ?: Double.MAX_VALUE }.thenBy { it.name })
}

/**
 * Whether each of the last [days] days (oldest first, ending on [today]) carries a point.
 *
 * This is what the check-in tile draws instead of a line: a frequency form has no value to
 * plot, so the figure shows the pattern of days it happened on. Filled and hollow dots,
 * not a line through ones and zeroes, which would imply a magnitude that does not exist.
 */
fun presenceWindow(points: List<DayPoint>, today: LocalDate, days: Int = 10): List<Boolean> {
    require(days > 0) { "days must be positive" }
    val present = points.mapTo(HashSet()) { it.opDate }
    return (days - 1 downTo 0).map { back -> today.minusDays(back.toLong()).toString() in present }
}

// --- the activity heatmap --------------------------------------------------------------

/** A distinct activity of a day: what the calendar draws a dot for and the heatmap counts. */
data class ActivityRef(val key: String, val exerciseId: Long?, val name: String)

/**
 * The DISTINCT ACTIVITIES of every day in the range, in the order they first appeared.
 *
 * "Activity" here means one exercise, not one entry: a gym day logged set by set is
 * twelve events but two or three activities, and it is the latter the calendar dots and
 * the heatmap buckets are scaled for. Counting raw events would send every training day
 * straight into the darkest bucket and turn the year into a solid block.
 *
 * Body weight is excluded along with the rest of the non-[FACT_TYPES]: a weigh-in is not
 * training (see [activeDays]).
 */
fun activitiesByDay(
    events: List<JournalEvent>,
    dateFrom: String,
    dateTo: String,
): Map<String, List<ActivityRef>> {
    val out = LinkedHashMap<String, MutableList<ActivityRef>>()
    val seen = HashSet<Pair<String, String>>()
    for (ev in readActivities(events, FACT_TYPES, dateFrom, dateTo)) {
        val exercise = ev.form.exerciseLink()
        val key = exercise?.key ?: "name:${ev.key ?: ev.type}"
        if (!seen.add(ev.opDate to key)) continue
        out.getOrPut(ev.opDate) { mutableListOf() }
            .add(ActivityRef(key, exercise?.id, ev.form.activityName()))
    }
    return out
}

/** One cell: the day, how many activities it holds and its intensity level (0 = nothing). */
data class HeatmapDay(val opDate: String, val count: Int, val level: Int)

/**
 * A year of activity as full Monday-to-Sunday weeks.
 *
 * [days] is a flat ascending list; [weeks] tells the screen how many columns to draw, and
 * `days[week * 7 + dayOfWeek]` addresses a cell. The grid is padded out to whole weeks at
 * both ends so that the columns line up — the padding days are real days with real counts,
 * they simply fall outside the requested range.
 */
data class Heatmap(
    val days: List<HeatmapDay>,
    val weeks: Int,
    val maxCount: Int,
    val levels: Int,
) {
    val totalActivities: Int get() = days.sumOf { it.count }
    val activeDays: Int get() = days.count { it.count > 0 }
    fun cell(week: Int, dayOfWeek: Int): HeatmapDay? = days.getOrNull(week * 7 + dayOfWeek)

    /** The Monday a column starts on, for the month ribbon above the grid. */
    fun weekStart(week: Int): LocalDate? = cell(week, 0)?.let { LocalDate.parse(it.opDate) }
}

/**
 * Level of a day: 0 for nothing, then one level per activity up to [levels].
 *
 * ABSOLUTE thresholds, not quantiles — the design's legend reads "1 / 2 / 3 / 4+
 * activities", and a legend can only say that if the buckets mean literally that. It
 * works because [activitiesByDay] counts EXERCISES rather than entries, which keeps a
 * realistic day inside 1..4; counting raw sets would need quantiles and would leave the
 * legend unable to name what a shade means.
 */
internal fun heatmapLevel(count: Int, levels: Int): Int = count.coerceIn(0, levels)

/**
 * Builds the heatmap over [dateFrom]..[dateTo], expanded to full Monday-to-Sunday weeks so
 * the columns line up. The padding days are real days with real counts; they simply fall
 * outside the requested range.
 */
fun activityHeatmap(
    events: List<JournalEvent>,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    levels: Int = 4,
): Heatmap {
    require(!dateTo.isBefore(dateFrom)) { "date_to is earlier than date_from" }
    val gridStart = dateFrom.minusDays(((dateFrom.dayOfWeek.value + 6) % 7).toLong())
    val gridEnd = dateTo.plusDays((7 - dateTo.dayOfWeek.value).toLong())

    val perDay = activitiesByDay(events, gridStart.toString(), gridEnd.toString())
    val total = ChronoUnit.DAYS.between(gridStart, gridEnd).toInt() + 1

    val days = (0 until total).map { offset ->
        val iso = gridStart.plusDays(offset.toLong()).toString()
        val c = perDay[iso]?.size ?: 0
        HeatmapDay(iso, c, heatmapLevel(c, levels))
    }
    return Heatmap(
        days = days,
        weeks = total / 7,
        maxCount = days.maxOfOrNull { it.count } ?: 0,
        levels = levels,
    )
}

// --- the hero row ----------------------------------------------------------------------

/**
 * The headline counters of the overview: workouts in the window, how that compares with
 * the window before it, and how many entries went into them.
 *
 * A "workout" is an ACTIVE DAY, not an event: logging fifteen sets on Monday is one
 * workout, and counting events here would make the hero number swing on how finely the
 * day happened to be logged.
 */
data class HeroStats(
    val windowDays: Int,
    val workouts: Int,
    val previousWorkouts: Int,
    val entries: Int,
    val currentStreak: Int,
) {
    /** Change against the previous window; null when there is no previous window to compare with. */
    val delta: Int get() = workouts - previousWorkouts
}

/**
 * Consecutive active days ending at [today] or at the day before it.
 *
 * Today not being logged YET does not break a streak — at nine in the morning every
 * streak in the app would otherwise read zero. A gap of two days does break it.
 */
fun currentStreak(activeDays: Set<String>, today: LocalDate): Int {
    var day = if (today.toString() in activeDays) today else today.minusDays(1)
    var n = 0
    while (day.toString() in activeDays) {
        n++
        day = day.minusDays(1)
    }
    return n
}

fun heroStats(events: List<JournalEvent>, today: LocalDate, windowDays: Int = 7): HeroStats {
    require(windowDays > 0) { "windowDays must be positive" }
    val from = today.minusDays((windowDays - 1).toLong())
    val prevTo = from.minusDays(1)
    val prevFrom = prevTo.minusDays((windowDays - 1).toLong())

    val current = activeDays(events, from.toString(), today.toString())
    val previous = activeDays(events, prevFrom.toString(), prevTo.toString())
    val entries = readActivities(events, FACT_TYPES, from.toString(), today.toString()).size
    // the streak is read over a wide window so that a long one is not clipped by it
    val streakDays = activeDays(events, today.minusDays(365).toString(), today.toString())

    return HeroStats(
        windowDays = windowDays,
        workouts = current.size,
        previousWorkouts = previous.size,
        entries = entries,
        currentStreak = currentStreak(streakDays, today),
    )
}

// --- door tiles of the overview --------------------------------------------------------

/**
 * A tile of the overview feed: one exercise, its last value, the sparkline behind it and
 * its record. Tapping a tile is what opens the detail screen — hence "door".
 *
 * [series] is the trend where the form has one and the volume where it does not (a
 * check-in tile shows its frequency), so a tile is never blank. [record] is null for the
 * forms that have no record model, and the tile then shows nothing there rather than a
 * placeholder badge.
 */
data class DoorTile(
    val exerciseId: Long,
    val name: String,
    val form: ExerciseForm,
    val series: FormSeries,
    val record: ExerciseRecord?,
    val lastDate: String,
    val entries: Int,
)

/**
 * A catalog row reduced to what the dashboard needs.
 *
 * [uid] rather than the id alone, because the entries this is matched against are keyed by
 * identity now — see [ExerciseLink]. An empty uid means "this caller only has a number", and
 * matching then falls back to the number.
 */
data class CatalogExercise(
    val id: Long,
    val name: String,
    val form: ExerciseForm,
    val uid: String? = null,
) {
    val link: ExerciseLink get() = ExerciseLink(uid, id)
}

/**
 * The overview feed, most recently used first (the same ordering the exercise picker
 * uses — what you trained yesterday is what you want to look at today).
 *
 * Exercises with NO entries at all are left out: an empty tile teaches nothing, and the
 * catalog is allowed to hold exercises that were created and never used. If that empties
 * the feed entirely, the screen shows its empty state.
 */
fun doorTiles(
    events: List<JournalEvent>,
    catalog: List<CatalogExercise>,
): List<DoorTile> {
    val activities = readActivities(events)
    val usage = exerciseUsage(events)
    val order = pickerOrder(usage)

    val tiles = catalog.mapNotNull { row ->
        val (id, name, form) = row
        val link = row.link
        val series = trendSeries(activities, link, form)
            ?: volumeSeries(activities, link, form)
            ?: return@mapNotNull null
        val last = series.last ?: return@mapNotNull null
        DoorTile(
            exerciseId = id,
            name = name,
            form = form,
            series = series,
            record = recordsOf(activities, link, form).firstOrNull(),
            lastDate = last.opDate,
            entries = formsOf(activities, link, form).size,
        )
    }
    // body weight has no exercise_id, so usage never sees it; ordering falls back to its
    // own last entry, which is the honest answer to "how recently was this used"
    return tiles.sortedWith(
        compareByDescending<DoorTile> { it.lastDate }.thenComparator { a, b -> order.compare(a.exerciseId, b.exerciseId) }
    )
}
