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
 *
 * ── KILOGRAMS AND KILOGRAM-SECONDS NEVER SHARE A COLUMN ─────────────────────────
 * A strength volume is kilograms lifted; a hold volume is [holdImpulseKgSec], kilogram-SECONDS.
 * They are different quantities on different scales and there is no exchange rate between them.
 * Nothing here adds one to the other, and no screen may either: a bar labelled "volume" holding
 * both would be a number with no unit that grows fastest when the training changes shape. Every
 * product surveyed either shows them apart or shows only one of them; none converts honestly.
 *
 * What compares a week of barbell work with a week of hangs is [workingSetTally] — the count of
 * working sets, which is dimensionless and therefore the only total the two weeks can share.
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

// --- the time axis a screen shares --------------------------------------------------------

/**
 * The time axis of a whole screen: one slot per bucket, ascending, covering the ENTIRE
 * window whether or not anything was logged into it.
 *
 * ── Why the window may not be left to each series ───────────────────────────────
 * Two charts of one exercise are stacked in order to be READ AGAINST EACH OTHER — the trend
 * above, the volume below, the same days under both. Drawn from its own points alone, a chart
 * has an axis that begins at its own first entry and ends at its own last one, so a trend
 * whose last 1RM was set on the third ended on the third while the volume beside it ended on
 * the eighth. Nothing said so; the two pictures simply described different fortnights at the
 * same width, which is exactly the comparison the layout invites a reader to make.
 *
 * The second half of it is worse and was invisible: points were laid out by INDEX, so a month
 * between two entries occupied the same width as two consecutive days. Neither chart was a
 * picture of time at all — it was a picture of the order things happened in.
 *
 * The slots are therefore built from the PERIOD and shared, and each series is placed onto
 * them ([onAxis]). A bucket nobody trained in gets a null, which the charts leave blank —
 * NOT a zero, which would claim a session that achieved nothing (the distinction [byDay]
 * already makes when it drops a day of pure warm-ups rather than plotting it at zero).
 */
data class TimeAxis(val granularity: Granularity, val slots: List<String>) {
    val size: Int get() = slots.size
    val isEmpty: Boolean get() = slots.isEmpty()
}

/** One slot of a [TimeAxis] for one series: the bucket's day, and the value there or null. */
data class AxisSlot(val opDate: String, val value: Double?)

/**
 * A series placed on the screen's shared [TimeAxis]: exactly as long as the axis, holes and
 * all, so two of them can be drawn one above the other and compared column by column.
 */
data class SeriesOnAxis(val spec: SeriesSpec, val slots: List<AxisSlot>) {
    /** The values that exist, in order. The empty slots are not zeroes and are not in here. */
    val values: List<Double> get() = slots.mapNotNull { it.value }

    /** How many buckets of the window actually carry something. */
    val filled: Int get() = slots.count { it.value != null }

    /** Nothing was logged in this window at all — the screen says so instead of drawing. */
    val isEmpty: Boolean get() = filled == 0
}

/**
 * A backstop, not a policy: one entry mis-dated to 1970 would otherwise ask for tens of
 * thousands of buckets. Past 400 days the granularity is already months ([granularity]), so
 * this bound is eighty years of them and no real journal can reach it.
 */
private const val MAX_AXIS_SLOTS = 1000

/** Every bucket start between [from] and [to] inclusive, ascending. */
internal fun bucketSlots(from: LocalDate, to: LocalDate, granularity: Granularity): List<String> {
    if (to.isBefore(from)) return emptyList()
    val end = LocalDate.parse(bucketStart(to.toString(), granularity))
    var cursor = LocalDate.parse(bucketStart(from.toString(), granularity))
    val out = ArrayList<String>()
    while (!cursor.isAfter(end) && out.size < MAX_AXIS_SLOTS) {
        out.add(cursor.toString())
        cursor = when (granularity) {
            Granularity.DAY -> cursor.plusDays(1)
            Granularity.WEEK -> cursor.plusWeeks(1)
            Granularity.MONTH -> cursor.plusMonths(1)
        }
    }
    return out
}

/**
 * The axis of a screen showing [series] over [period], at [granularity].
 *
 * The span comes from the WINDOW, not from the data: 30 days is thirty slots even if two of
 * them were trained, which is what makes "showing: last 30 days" a true statement and what
 * stops a sparse series from stretching itself across the card.
 *
 * [Period.ALL] has no start of its own, so it takes the first day anything was logged on.
 * Pass the series already narrowed with [inPeriod] — for every window but "all" that filter
 * has already dropped what falls outside, and this is only the frame around what is left.
 */
fun timeAxis(
    series: List<FormSeries>,
    period: Period,
    granularity: Granularity,
    today: LocalDate,
): TimeAxis {
    val days = series.flatMap { it.points }.map { it.opDate }
    val from = period.startDate(today)
        ?: days.minOrNull()?.let(LocalDate::parse)
        ?: today
    // an entry dated ahead of today is not dropped off the end of an all-time axis: the
    // fixed windows have already excluded it, and here it is the last thing there is
    val latest = days.maxOrNull()?.let(LocalDate::parse)
    val to = if (period.days == null && latest != null && latest.isAfter(today)) latest else today
    return TimeAxis(granularity, bucketSlots(minOf(from, to), to, granularity))
}

/**
 * This series laid onto [axis]: bucketed by the axis's own granularity, then placed slot by
 * slot. Buckets with nothing in them come out null rather than absent, which is what keeps
 * every series on the screen the same length and the same shape.
 */
fun FormSeries.onAxis(axis: TimeAxis): SeriesOnAxis {
    val byBucket = bucketPoints(points, axis.granularity, spec).associate { it.opDate to it.value }
    return SeriesOnAxis(spec, axis.slots.map { AxisSlot(it, byBucket[it]) })
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

/**
 * The share of body weight an exercise loads, as a number the arithmetic can trust, or null
 * for "nothing was ever set".
 *
 * A stored value outside (0, 1] is treated as absent rather than used, on exactly the grounds
 * [ExerciseRef.edge] gives for a zero edge: a catalog row can carry rubbish (a row that
 * arrives from another journal, a value typed before the field was validated), and a chart
 * quietly drawn from a share of 4.0 is worse than a chart that says nothing.
 */
internal fun usableShare(share: Double?): Double? = share?.takeIf { it > 0.0 && it <= 1.0 }

/**
 * THE SECONDS ONE HOLD SET SPENT UNDER LOAD, or null when the set says nothing this app can
 * put a number on.
 *
 * `number of hangs x the length of one hang`. THE PAUSES INSIDE THE SET DO NOT COUNT: a
 * repeater protocol of 7:3 spends three of every ten seconds hanging off nothing, and counting
 * them would make a set of long pauses look like work.
 *
 * Where the length of one hang comes from, in order:
 * - [HoldSet.holdSec], the length this set was actually recorded at. It is the set's own
 *   statement and it wins over anything the exercise says about itself;
 * - [HoldSet.workSec], the work half of the protocol snapshot ([HoldSet] explains why the
 *   snapshot is on the set). A set logged from a finished interval run carries no `hold_sec`
 *   — the run states the protocol instead — and it is still a set that was hung.
 *
 * A set that names no count of hangs is read as ONE hang rather than as no set at all. That is
 * the shape of a maximum-added-weight hang: one effort, and nothing to count.
 */
internal fun holdSecondsUnderTension(set: HoldSet): Double? {
    val oneHang = set.holdSec ?: set.workSec ?: return null
    return oneHang * (set.reps ?: 1)
}

/**
 * THE IMPULSE OF ONE HOLD SET, in kilogram-seconds, or null when the set carries no body
 * weight to load or no time to load it for.
 *
 * `(body weight + added weight) x time under tension`. This is what tonnage is for a lift:
 * force times the amount of it. A hang has no reps to multiply by and no bar to weigh, and
 * counting sets — what this app did before — says a five-second hang and a fifty-second hang
 * are the same training.
 *
 * ── This is our own construction, and not an industry standard ──────────────────
 * NOTHING VALIDATES IMPULSE AS A TRAINING METRIC. It is not a thing other trackers compute:
 * a survey of what else exists found no settled volume metric for isometric work anywhere,
 * and the closest prior art is `Load*Sec` in one product (PitchSix) and impulse used as a
 * TEST parameter in the research literature — as a way of describing a protocol, never as a
 * dose that has been shown to predict adaptation. What it has going for it is that it is the
 * physically correct analogue of tonnage and that every input is already recorded. What it
 * does not have is evidence that a bigger number is better training. Read it as "how much
 * work was done", never as "how good the week was".
 *
 * ── The load is the whole body, and where that overstates ───────────────────────
 * A hang loads all of you, so [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.bodyweightShare]
 * is deliberately NOT consulted here — a one-arm hang still has the whole person on the end of
 * it. The overstatement is at the other end of the form: a plank is also a [ExerciseForm.HOLD]
 * and holds nothing like its whole body weight up, so its impulse reads high. The number is
 * comparable with ITSELF over time, which is what a chart of one exercise is for, and it is
 * not comparable between two different holds.
 *
 * Assistance is subtracted, since [HoldSet.addedKg] is signed, and the result is floored at
 * zero for the reason [strengthLoadKg] gives: a band taking more off you than you weigh is a
 * hang that loaded nothing, not one that loaded a negative amount which could then subtract
 * from a week's total.
 *
 * Null when nobody had stepped on the scales by then — [StrengthSet.bodyweightKg] explains
 * why that is unknown rather than zero, and such a set contributes nothing rather than
 * dragging the day down to nothing.
 */
internal fun holdImpulseKgSec(set: HoldSet): Double? {
    val seconds = holdSecondsUnderTension(set) ?: return null
    val body = set.bodyweightKg ?: return null
    return (body + (set.addedKg ?: 0.0)).coerceAtLeast(0.0) * seconds
}

/**
 * The kilograms one strength set actually moved, or null when the set states no weight this
 * app can put a number on.
 *
 * Three cases, and the third is the new one:
 * - an absolute weight is the weight, and nothing else is consulted;
 * - a body-weight set with no share stated, or none recorded at the time, has NO number. Not
 *   zero — unknown. That is what it was before this existed and it stays that way, so a
 *   catalog nobody has filled in draws exactly the charts it drew yesterday;
 * - a body-weight set with both is worth `share x body weight + added weight`, which is what
 *   makes a week of pull-ups stop reading as a week of doing nothing.
 *
 * The floor at zero is for assistance ([StrengthSet.addedKg] can be negative): a band taking
 * more off you than the movement puts on is a set that lifted nothing, not one that lifted a
 * negative amount and can be used to subtract from the week's tonnage.
 */
internal fun strengthLoadKg(set: StrengthSet, bodyweightShare: Double?): Double? {
    set.weightKg?.let { return it }
    if (!set.ownWeight) return null
    val share = usableShare(bodyweightShare) ?: return null
    val body = set.bodyweightKg ?: return null
    return (share * body + (set.addedKg ?: 0.0)).coerceAtLeast(0.0)
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
 * - strength: TONNAGE, sets x reps x weight (research_visual.md §4.2). A body-weight set
 *   counts here as soon as the exercise says what share of you it lifts and the set knows
 *   what you weighed (see [strengthLoadKg]) — which is the whole reason both of those exist,
 *   since a week of pull-ups otherwise reads as a week of doing nothing. An exercise whose
 *   history yields no weight at all still falls back to total REPS — the same shape of
 *   fallback the hold trend uses, and for the same reason: a chart of zeroes is worse than a
 *   chart of a different metric that is honestly labelled. A MIXED history does use tonnage,
 *   and the sets with no computable load contribute nothing to the bar — an understatement
 *   this code does not correct, now narrowed to sets logged before anybody weighed themselves;
 * - holds: the IMPULSE of the day in kilogram-seconds, `(body weight + added) x time under
 *   tension` summed over the sets ([holdImpulseKgSec], which also states plainly that this is
 *   our own construction rather than a metric anybody has validated). Counting sets — which is
 *   what this was — made a five-second hang and a fifty-second hang the same training, and
 *   time under load is the entire content of an isometric set. An exercise whose history
 *   yields no impulse at all still falls back to the SET COUNT, the same shape of fallback the
 *   strength branch uses for reps and for the same reason;
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
    /**
     * What share of body weight this exercise loads, off the catalog row — see
     * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.bodyweightShare]. Null (the default)
     * keeps the behaviour every caller had before the column existed: body-weight sets carry
     * no tonnage and an all-body-weight history falls back to counting reps.
     */
    bodyweightShare: Double? = null,
): FormSeries? {
    val mine = working(formsOf(activities, exercise, form))
    return when (form) {
        ExerciseForm.STRENGTH -> {
            val sets = mine.mapNotNull { it.form as? StrengthSet }
            if (sets.any { strengthLoadKg(it, bodyweightShare) != null }) FormSeries(
                SeriesSpec("Volume, reps x weight", ValueFormat.KILOGRAMS, Aggregation.SUM),
                byDay(mine) { ofDay ->
                    ofDay.sumOf { ev ->
                        (ev.form as? StrengthSet)
                            ?.let { s -> (strengthLoadKg(s, bodyweightShare) ?: 0.0) * s.reps }
                            ?: 0.0
                    }
                },
            ) else FormSeries(
                SeriesSpec("Reps", ValueFormat.COUNT, Aggregation.SUM),
                byDay(mine) { ofDay -> ofDay.sumOf { (it.form as? StrengthSet)?.reps ?: 0 }.toDouble() },
            )
        }

        ExerciseForm.HOLD -> {
            val holds = mine.mapNotNull { it.form as? HoldSet }
            if (holds.any { holdImpulseKgSec(it) != null }) FormSeries(
                // the unit is in the LABEL because [ValueFormat] has no kilogram-second member:
                // adding one means adding a branch to four exhaustive `when`s in ui/Format.kt,
                // which is the screen layer this change is deliberately not touching. A count
                // prints the bare number, so nothing renders it as kilograms in the meantime —
                // and that is the one rendering that must never happen (see below)
                SeriesSpec("Impulse, kg·s", ValueFormat.COUNT, Aggregation.SUM),
                byDay(mine) { ofDay ->
                    ofDay.sumOf { ev -> (ev.form as? HoldSet)?.let { holdImpulseKgSec(it) } ?: 0.0 }
                },
            ) else FormSeries(
                SeriesSpec("Sets", ValueFormat.COUNT, Aggregation.SUM),
                byDay(mine) { ofDay -> ofDay.size.toDouble() },
            )
        }

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
    /**
     * What the CATALOG says about the exercise being trained one limb at a time — see
     * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.oneSided]. Defaulted to false so that
     * a caller holding no catalog row still gets the two-handed answer, which is what every
     * caller got before the flag existed.
     */
    oneSided: Boolean = false,
): List<ExerciseRecord> = when (form) {
    ExerciseForm.STRENGTH -> listOfNotNull(
        strengthRecord(activities, exercise),
        heaviestSet(activities, exercise),
    )

    ExerciseForm.HOLD -> holdRecord(activities, exercise, oneSided)

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

// --- working sets ------------------------------------------------------------------------

/**
 * The two forms that are made of SETS. A run, a stretch and a weigh-in are training and are
 * not sets, so they have nothing to contribute to a count of sets.
 *
 * The same pair [startsRest] names, and for the same underlying reason — a set is the unit
 * that is performed, rested after, and counted.
 */
private val SET_TYPES: List<String> = listOf(TYPE_STRENGTH_SET, TYPE_HOLD_SET)

/**
 * Whether an entry is a WORKING set: a set-based form that is not a warm-up.
 *
 * Warm-ups are left out on exactly the grounds [working] gives for volume and the records:
 * ramping up to the working weight is not a smaller version of the achievement, and counting
 * it would let a cautious session outscore a hard one.
 */
fun isWorkingSet(form: ActivityForm): Boolean = when (form) {
    is StrengthSet -> !form.warmup
    is HoldSet -> !form.warmup
    else -> false
}

/** One exercise's share of a [WorkingSetTally]. */
data class ExerciseSetCount(val exercise: ActivityRef, val sets: Int)

/**
 * How many working sets a window holds, in total and per exercise.
 *
 * ── The only total two different kinds of training can share ────────────────────
 * A week of barbell work has a tonnage in kilograms; a week of hangs has an impulse in
 * kilogram-seconds ([holdImpulseKgSec]). Those two numbers cannot be added, cannot be
 * compared, and cannot be drawn on one axis without inventing an exchange rate that does not
 * exist. A SET IS DIMENSIONLESS, so this is the one honest answer to "did I do more this week
 * than last" across a change of training.
 *
 * It is also the cheapest thing in this file: no body weight, no catalog, no share, no
 * protocol. Every set ever logged can be counted, including the ones that carry no numbers
 * anybody has filled in.
 *
 * What it deliberately does NOT say is how hard those sets were — three heavy singles and
 * three sets of twenty are the same three here. That is the price of being comparable at all,
 * and it is why this stands beside the volume charts rather than replacing them.
 *
 * [byExercise] is ordered by count, most first, and by name within a tie, so a screen can take
 * the head of the list. The name is the one the MOST RECENT entry used: entries carry the name
 * written at the time, and the grouping is by identity ([ExerciseLink.key]) rather than by that
 * name, so a renamed exercise stays one line and is called what it is called now.
 */
data class WorkingSetTally(val total: Int, val byExercise: List<ExerciseSetCount>)

/**
 * The working sets of the inclusive [dateFrom]..[dateTo] window over op_date; both default to
 * "no bound", which reads the whole journal.
 *
 * Deleted entries are out and amended ones carry their corrections, because this reads through
 * [readActivities] like everything else — a set that was taken back must not go on being
 * counted.
 */
fun workingSetTally(
    events: List<JournalEvent>,
    dateFrom: String? = null,
    dateTo: String? = null,
): WorkingSetTally {
    val counts = LinkedHashMap<String, ExerciseSetCount>()
    var total = 0
    for (ev in readActivities(events, SET_TYPES, dateFrom, dateTo)) {
        if (!isWorkingSet(ev.form)) continue
        total++
        val exercise = ev.form.exerciseLink()
        // an entry written before the catalog existed names no exercise; it is still a set that
        // was performed, and it is filed under the words it was written with rather than dropped
        val key = exercise?.key ?: "name:${ev.key ?: ev.type}"
        val before = counts[key]?.sets ?: 0
        counts[key] = ExerciseSetCount(
            ActivityRef(key, exercise?.id, ev.form.activityName()),
            before + 1,
        )
    }
    return WorkingSetTally(
        total = total,
        byExercise = counts.values.sortedWith(
            compareByDescending<ExerciseSetCount> { it.sets }.thenBy { it.exercise.name }
        ),
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
    /** Trained one limb at a time — see [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.oneSided]. */
    val oneSided: Boolean = false,
    /**
     * What share of body weight this exercise loads — see
     * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.bodyweightShare].
     */
    val bodyweightShare: Double? = null,
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
            ?: volumeSeries(activities, link, form, row.bodyweightShare)
            ?: return@mapNotNull null
        val last = series.last ?: return@mapNotNull null
        DoorTile(
            exerciseId = id,
            name = name,
            form = form,
            series = series,
            // the first of them, which for one-sided work is the LEFT hand's rather than
            // "the exercise's": a tile has room for one badge and the detail screen is where
            // both hands are shown side by side
            record = recordsOf(activities, link, form, row.oneSided).firstOrNull(),
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
