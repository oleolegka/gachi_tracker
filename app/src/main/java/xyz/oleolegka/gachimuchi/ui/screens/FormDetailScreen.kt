package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRecord
import xyz.oleolegka.gachimuchi.domain.FormSeries
import xyz.oleolegka.gachimuchi.domain.Granularity
import xyz.oleolegka.gachimuchi.domain.HoldSibling
import xyz.oleolegka.gachimuchi.domain.Period
import xyz.oleolegka.gachimuchi.domain.RecordHit
import xyz.oleolegka.gachimuchi.domain.ValueFormat
import xyz.oleolegka.gachimuchi.domain.granularity
import xyz.oleolegka.gachimuchi.domain.holdSiblings
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.recordsOf
import xyz.oleolegka.gachimuchi.domain.trendSeries
import xyz.oleolegka.gachimuchi.domain.volumeSeries
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.BarChart
import xyz.oleolegka.gachimuchi.ui.components.EmptyState
import xyz.oleolegka.gachimuchi.ui.components.rememberExerciseEditor
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.IdentityChip
import xyz.oleolegka.gachimuchi.ui.components.LineChart
import xyz.oleolegka.gachimuchi.ui.components.NoteText
import xyz.oleolegka.gachimuchi.ui.components.SectionHeader
import xyz.oleolegka.gachimuchi.ui.components.SegmentControl
import xyz.oleolegka.gachimuchi.ui.components.SiblingChip
import xyz.oleolegka.gachimuchi.ui.components.StatCard
import xyz.oleolegka.gachimuchi.ui.axisUnit
import xyz.oleolegka.gachimuchi.ui.fmtShortDay
import xyz.oleolegka.gachimuchi.ui.fmtValueParts
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The detail screen of one exercise: period switch, trend, volume, records.
 *
 * ── What is NOT here, and why ───────────────────────────────────────────────────
 * Not every form gets two charts. A duration entry is its own volume, a check-in has no
 * metric at all, and a weigh-in has no volume — in those cases `domain/Analytics.kt`
 * returns null for the missing series and this screen prints a sentence saying so. The
 * alternative would be a made-up second metric, which is exactly what decisions.md §3
 * forbids for check-ins.
 *
 * Cardio has no records block either: §8.3c has never defined cardio records, so the
 * screen says that out loud instead of showing an empty "Records" heading.
 *
 * ── §12-A: the hangboard is several exercises ───────────────────────────────────
 * Edge and protocol are part of a hold exercise's IDENTITY, so "Hangs 20 mm 7:3" and
 * "Hangs 15 mm 7:3" are separate catalog rows with separate histories. That makes the
 * detail screen unusable without a way to hop between them, hence the sibling chips; the
 * identity chips under them state the edge and the protocol so it is never ambiguous which
 * of the siblings the chart belongs to. Those two values come from the catalog COLUMNS,
 * not from parsing the name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDetailScreen(
    state: UiState,
    exerciseId: Long,
    today: LocalDate,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentId by remember(exerciseId) { mutableStateOf(exerciseId) }
    var period by remember(exerciseId) { mutableStateOf(Period.MONTH) }
    var menuOpen by remember(exerciseId) { mutableStateOf(false) }

    val entity = state.exerciseById(currentId)
    val form = state.formOf(currentId)
    val colors = LocalGachiColors.current
    val editor = rememberExerciseEditor()

    if (entity == null || form == null) {
        // the exercise vanished from under the screen (a wipe, a reseed): say so and go back
        Column(modifier.padding(24.dp)) {
            Text("This exercise is no longer in the catalog.", color = colors.inkSecondary)
        }
        return
    }

    val activities = remember(state.events) { readActivities(state.events) }
    // the screen navigates by row number; the journal is keyed by identity (see ExerciseLink)
    val link = remember(state.exercises, currentId) { state.linkOf(currentId) }
    val trendAll = remember(activities, link, form) { trendSeries(activities, link, form) }
    val volumeAll = remember(activities, link, form) { volumeSeries(activities, link, form) }
    val records = remember(activities, link, form) { recordsOf(activities, link, form) }

    val siblings = remember(state.exercises, currentId, form) {
        if (form != ExerciseForm.HOLD) emptyList()
        else holdSiblings(state.exercises.map { it.toSibling() }, currentId)
    }

    // the bucket width follows the window, so a year is weeks rather than 365 bars
    val spanDays = remember(trendAll, volumeAll) { historySpanDays(trendAll, volumeAll, today) }
    val granularity = period.granularity(spanDays)
    val trend = trendAll?.inPeriod(period, today)?.bucketed(granularity)
    val volume = volumeAll?.inPeriod(period, today)?.bucketed(granularity)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(entity.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(form.title.lowercase(), fontSize = 11.sp, color = colors.inkMuted)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    /*
                     * The catalog is editable HERE and nowhere else, because this is the screen
                     * that shows an exercise as a thing in its own right — its name, its edge,
                     * its protocol, its history — and therefore the screen somebody is looking
                     * at when they notice one of those is wrong. The picker is for choosing,
                     * and a menu of corrections in a list you are trying to get out of quickly
                     * is a menu in the way.
                     */
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit exercise") },
                            onClick = {
                                menuOpen = false
                                editor.edit(entity)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (entity.hidden) "Show in the picker" else "Hide from the picker")
                            },
                            onClick = {
                                menuOpen = false
                                editor.toggleHidden(entity)
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (form == ExerciseForm.HOLD) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NoteText(
                            "An exercise is name + edge + protocol. \"Hangs 20 mm 7:3\" and " +
                                "\"15 mm 7:3\" are different exercises. What is tracked and what " +
                                "counts as a record is the WEIGHT."
                        )
                        if (siblings.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                siblings.forEach { sibling ->
                                    SiblingChip(
                                        text = sibling.name,
                                        selected = sibling.exerciseId == currentId,
                                        accent = colors.forForm(ExerciseForm.HOLD),
                                        onClick = { currentId = sibling.exerciseId },
                                    )
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            entity.edgeMm?.let { IdentityChip("edge", "${it.toInt()} mm") }
                            if (entity.protocolWorkSec != null && entity.protocolRestSec != null) {
                                IdentityChip(
                                    "protocol",
                                    "${entity.protocolWorkSec.toInt()}:${entity.protocolRestSec.toInt()}",
                                )
                            }
                            IdentityChip("metric", "weight")
                        }
                    }
                }
            }

            item {
                Column {
                    SegmentControl(
                        options = Period.entries,
                        selected = period,
                        label = { it.label },
                        onSelect = { period = it },
                    )
                    Text(
                        periodNote(period, granularity),
                        fontSize = 12.sp,
                        color = colors.inkMuted,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 9.dp, start = 2.dp),
                    )
                }
            }

            item {
                ChartCard(
                    title = trendAll?.spec?.label ?: "Trend",
                    subtitle = chartSubtitle(trend, granularity),
                    series = trend,
                    emptyTitle = trendEmptyTitle(form),
                    emptyHint = trendEmptyHint(form),
                ) { series ->
                    LineChart(
                        points = series.points,
                        format = series.spec.format,
                        lowerIsBetter = series.spec.lowerIsBetter,
                        lineColor = colors.forForm(form),
                    )
                }
            }

            if (volumeAll != null) {
                item {
                    ChartCard(
                        title = volumeAll.spec.label,
                        subtitle = chartSubtitle(volume, granularity),
                        series = volume,
                        emptyTitle = "Nothing in this window",
                        emptyHint = "Pick a longer period, or log a session and it lands here.",
                    ) { series ->
                        BarChart(points = series.points, format = series.spec.format)
                    }
                }
            }

            item { RecordsBlock(form, records, today) }
        }
    }
}

/** A chart in its card, or the reason there is no chart. */
@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    series: FormSeries?,
    emptyTitle: String,
    emptyHint: String,
    chart: @Composable (FormSeries) -> Unit,
) {
    val colors = LocalGachiColors.current
    if (series == null || series.isEmpty) {
        EmptyState(title = emptyTitle, hint = emptyHint)
        return
    }
    GachiCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp, start = 2.dp, end = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(subtitle, fontSize = 11.sp, color = colors.inkMuted)
            }
            // one point is a dot, not a trend: say so rather than drawing a chart of it
            if (series.points.size == 1) {
                Text(
                    "Only one entry in this window - not a trend yet.",
                    fontSize = 12.sp,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                )
            }
            chart(series)
        }
    }
}

/**
 * The records block, or an honest statement that this form has none.
 *
 * §12-C: a record is never shown without its date, which is why every card carries a
 * `when` line even though the badge does not repeat it.
 */
@Composable
private fun RecordsBlock(form: ExerciseForm, records: List<ExerciseRecord>, today: LocalDate) {
    Column(Modifier.fillMaxWidth()) {
        when {
            form == ExerciseForm.CARDIO -> NoteText(
                "Cardio records (best pace / farther / longer) are not computed yet - the tile " +
                    "and this screen show the trend and the latest outings, with no record badge."
            )

            form == ExerciseForm.DURATION -> NoteText(
                "Duration is bare total time, and a record over it is not defined yet (§5), so " +
                    "this form tracks the total rather than a personal best."
            )

            form == ExerciseForm.TICK -> NoteText(
                "A check-in carries no metric by decision (§3): only frequency is tracked. " +
                    "There are deliberately no records and no weight trend here."
            )

            form == ExerciseForm.BODYWEIGHT -> NoteText(
                "Body weight is a plain series, not an achievement: it has no record model, " +
                    "and a \"personal best\" over it would be a value judgement the app does not make."
            )

            records.isEmpty() -> {
                SectionHeader("Records", "with the date")
                EmptyState(
                    title = "No records yet",
                    hint = "The first weighted set is a baseline, not a record. The second one " +
                        "can already beat it.",
                )
            }

            else -> {
                SectionHeader("Records", "with the date")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    records.forEachIndexed { index, record ->
                        val (number, unit) = fmtValueParts(record.value, recordFormat(record))
                        StatCard(
                            label = recordLabel(record),
                            value = number,
                            unit = unit,
                            `when` = fmtShortDay(LocalDate.parse(record.opDate)),
                            // only the leading record gets the badge: two green pills next to
                            // each other stop meaning "this is the notable one"
                            badge = index == 0,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun recordLabel(record: ExerciseRecord): String = when (record.axis) {
    RecordHit.Axis.EST_1RM -> "Best estimated 1RM"
    RecordHit.Axis.WEIGHT_AT_REPS -> "Heaviest single set"
    RecordHit.Axis.HOLD_WEIGHT -> "Most weight hung"
    RecordHit.Axis.HOLD_SECONDS -> "Longest hold"
}

private fun recordFormat(record: ExerciseRecord) = when (record.axis) {
    RecordHit.Axis.HOLD_SECONDS -> ValueFormat.SECONDS
    else -> ValueFormat.KILOGRAMS
}

private fun periodSubtitle(period: Period): String = when (period) {
    Period.MONTH -> "last 30 days"
    Period.QUARTER -> "last 3 months"
    Period.YEAR -> "last 12 months"
    Period.ALL -> "all history"
}

private fun periodNote(period: Period, granularity: Granularity): String =
    "Showing: ${periodSubtitle(period)}, ${granularityWord(granularity)}"

/**
 * The caption beside a chart title: THE UNIT of the Y axis, then the bucket width.
 *
 * The unit lives here rather than on the axis itself because the axis repeats its labels
 * four or five times and "kg" beside every one of them is noise. But it has to be
 * somewhere: bare tick numbers are exactly the "chart without labelled axes" complaint
 * this screen was rewritten to fix.
 */
private fun chartSubtitle(series: FormSeries?, granularity: Granularity): String {
    val unit = series?.let {
        axisUnit(it.spec.format, it.points.maxOfOrNull { p -> p.value } ?: 0.0)
    }.orEmpty()
    val width = granularityWord(granularity)
    return if (unit.isBlank()) width else "$unit - $width"
}

private fun granularityWord(granularity: Granularity): String = when (granularity) {
    Granularity.DAY -> "by day"
    Granularity.WEEK -> "by week"
    Granularity.MONTH -> "by month"
}

private fun trendEmptyTitle(form: ExerciseForm): String = when (form) {
    ExerciseForm.CARDIO -> "No pace recorded"
    ExerciseForm.TICK -> "A check-in has no trend"
    else -> "Nothing to plot yet"
}

private fun trendEmptyHint(form: ExerciseForm): String = when (form) {
    ExerciseForm.CARDIO ->
        "Pace is the only cardio metric that means progress. Distance is volume, and " +
            "charting it as a trend would reward running slower for longer."

    ExerciseForm.TICK ->
        "This form tracks frequency only, by decision (§3). The bar chart below is the " +
            "whole statistic there is."

    else -> "Log a couple of sessions and the line appears here."
}

/** How many days the whole history spans — what decides day / week / month buckets. */
private fun historySpanDays(trend: FormSeries?, volume: FormSeries?, today: LocalDate): Int {
    val first = listOfNotNull(trend?.points?.firstOrNull(), volume?.points?.firstOrNull())
        .minByOrNull { it.opDate } ?: return 0
    return ChronoUnit.DAYS.between(LocalDate.parse(first.opDate), today).toInt().coerceAtLeast(0)
}

/** Catalog row -> the sibling record the §12-A switcher is built from. */
private fun ExerciseEntity.toSibling() = HoldSibling(
    exerciseId = id,
    name = name,
    edgeMm = edgeMm,
    workSec = protocolWorkSec,
    restSec = protocolRestSec,
)
