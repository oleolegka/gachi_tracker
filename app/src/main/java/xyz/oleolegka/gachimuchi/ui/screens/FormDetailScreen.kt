package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRecord
import xyz.oleolegka.gachimuchi.domain.exerciseLink
import xyz.oleolegka.gachimuchi.domain.FormSeries
import xyz.oleolegka.gachimuchi.domain.Granularity
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.Period
import xyz.oleolegka.gachimuchi.domain.RecordHit
import xyz.oleolegka.gachimuchi.domain.SeriesOnAxis
import xyz.oleolegka.gachimuchi.domain.ValueFormat
import xyz.oleolegka.gachimuchi.domain.scheduleCaption
import xyz.oleolegka.gachimuchi.domain.granularity
import xyz.oleolegka.gachimuchi.domain.onAxis
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.recordsOf
import xyz.oleolegka.gachimuchi.domain.timeAxis
import xyz.oleolegka.gachimuchi.domain.trendSeries
import xyz.oleolegka.gachimuchi.domain.volumeSeries
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.BarChart
import xyz.oleolegka.gachimuchi.ui.components.ConfirmRemoveDialog
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.ui.components.rememberExerciseEditor
import xyz.oleolegka.gachimuchi.ui.components.GachiCard
import xyz.oleolegka.gachimuchi.ui.components.IdentityChip
import xyz.oleolegka.gachimuchi.ui.components.LineChart
import xyz.oleolegka.gachimuchi.ui.components.NoteText
import xyz.oleolegka.gachimuchi.ui.components.REMOVAL_IS_REVERSIBLE
import xyz.oleolegka.gachimuchi.ui.components.SectionHeader
import xyz.oleolegka.gachimuchi.ui.components.SegmentControl
import xyz.oleolegka.gachimuchi.ui.components.RecordBadge
import xyz.oleolegka.gachimuchi.ui.axisUnit
import xyz.oleolegka.gachimuchi.ui.fmtShortDay
import xyz.oleolegka.gachimuchi.ui.fmtValueParts
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize
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
 * ── §12-A: the protocol is part of a hold exercise's identity ───────────────────
 * "Hangs" at 7:3 and "Hangs" at 10:5 are separate catalog rows with separate histories, so
 * the identity chip under the title states the protocol — it comes from the catalog COLUMNS,
 * not from parsing the name — which is never ambiguous about which exercise the chart below
 * belongs to.
 *
 * A §12-A sibling switcher used to sit here too, letting the screen hop between hangboard
 * exercises that differed only by EDGE (the hangboard lip width, in mm). The edge attribute
 * has been removed from the app entirely (see `MIGRATION_17_18` in `data/db/AppDatabase.kt`)
 * and the switcher left with it: there is nothing left for it to compare, and "Hangs 20mm" is
 * simply a different exercise from "Hangs" now, the same way any two differently-named
 * exercises are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDetailScreen(
    state: UiState,
    exerciseId: Long,
    today: LocalDate,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens the correction screen on this exercise. A callback and not a dialog raised in
     * here: correcting an exercise is a MODE of its own now (`ui/screens/EditExerciseScreen.kt`),
     * drawn over this screen by [xyz.oleolegka.gachimuchi.ui.GachiApp] so that back closes it
     * and lands here rather than leaving the app.
     */
    onEditExercise: (ExerciseEntity) -> Unit = {},
) {
    var period by remember(exerciseId) { mutableStateOf(Period.MONTH) }
    var menuOpen by remember(exerciseId) { mutableStateOf(false) }
    var confirmDelete by remember(exerciseId) { mutableStateOf(false) }

    val entity = state.exerciseById(exerciseId)
    val form = state.formOf(exerciseId)
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
    val link = remember(state.exercises, exerciseId) { state.linkOf(exerciseId) }
    // what the delete confirmation warns about: not "are you sure" on its own, but how many
    // entries are about to stop being shown anywhere
    val entryCount = remember(activities, link) {
        activities.count { it.form.exerciseLink()?.matches(link) == true }
    }
    val trendAll = remember(activities, link, form) { trendSeries(activities, link, form) }
    /*
     * The two catalog columns are passed rather than left to their defaults, and the defaults
     * are the whole hazard: both are the pre-column answer, so omitting them compiles and
     * quietly draws the old chart. Without the share a pull-up is worth no tonnage at all and
     * a week of them looks like a week off; without the flag the records block shows one best
     * for both hands, which is the better hand wearing the exercise's name.
     */
    val volumeAll = remember(activities, link, form, entity.bodyweightShare) {
        volumeSeries(activities, link, form, entity.bodyweightShare)
    }
    val records = remember(activities, link, form, entity.oneSided) {
        recordsOf(activities, link, form, entity.oneSided)
    }

    // the bucket width follows the window, so a year is weeks rather than 365 bars
    val spanDays = remember(trendAll, volumeAll) { historySpanDays(trendAll, volumeAll, today) }
    val granularity = period.granularity(spanDays)
    /*
     * ONE TIME AXIS FOR THE SCREEN, built from the window and handed to both charts.
     *
     * Each chart used to take its own points and stretch them across its own card, so the
     * trend ran to the day of the last 1RM and the volume beside it ran to the day of the
     * last session — two pictures of different stretches of time, drawn the same width, one
     * above the other. Comparing them by eye is the entire reason they are stacked.
     *
     * The series are narrowed to the window first and PLACED on the axis after, so a metric
     * that has nothing in this window comes out empty rather than compressing the axis onto
     * whatever it does have. Why the axis is built from the period and not from the data is
     * on `TimeAxis` in domain/Analytics.kt.
     */
    val trendWindow = remember(trendAll, period, today) { trendAll?.inPeriod(period, today) }
    val volumeWindow = remember(volumeAll, period, today) { volumeAll?.inPeriod(period, today) }
    val axis = remember(trendWindow, volumeWindow, period, granularity, today) {
        timeAxis(listOfNotNull(trendWindow, volumeWindow), period, granularity, today)
    }
    val trend = remember(trendWindow, axis) { trendWindow?.onAxis(axis) }
    val volume = remember(volumeWindow, axis) { volumeWindow?.onAxis(axis) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                        Text(
                            entity.name,
                            fontSize = TextSize.Title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
                        ) {
                            /*
                             * The form's own colour, the one every chart on this screen is
                             * drawn in. The word beside it was the only statement of what
                             * kind of exercise this is, in the quietest grey on the screen;
                             * the dot ties the word to the line underneath it. Colour is
                             * never the only channel here — the word is still there.
                             */
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(colors.forForm(form))
                            )
                            Text(
                                form.title.lowercase(),
                                fontSize = TextSize.Meta,
                                color = colors.inkSecondary,
                            )
                        }
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
                     * that shows an exercise as a thing in its own right — its name, its
                     * protocol, its history — and therefore the screen somebody is looking at
                     * when they notice one of those is wrong. The picker is for choosing, and
                     * a menu of corrections in a list you are trying to get out of quickly is
                     * a menu in the way.
                     */
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit exercise") },
                            onClick = {
                                menuOpen = false
                                onEditExercise(entity)
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
                        // set off from the two harmless entries above it, and in the
                        // critical colour: it used to be a third line of exactly the same
                        // weight as "Edit exercise" (rule 3)
                        HorizontalDivider(
                            color = colors.grid,
                            modifier = Modifier.padding(vertical = Spacing.Tight),
                        )
                        DropdownMenuItem(
                            text = { Text("Delete exercise", color = colors.critical) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
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
        /*
         * Whether the window is empty is a fact about the WINDOW, so it is said once, under
         * the switch that sets it — not twice more inside two chart cards. A metric that does
         * not exist at all for this form (a check-in has no trend) is a different statement
         * and stays on its own card.
         */
        val windowEmpty = listOfNotNull(trend, volume).let { series ->
            series.isNotEmpty() && series.all { it.isEmpty }
        }

        LazyColumn(
            Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = Spacing.Block, end = Spacing.Block,
                top = Spacing.Line, bottom = Spacing.Section,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Cards),
        ) {
            if (form == ExerciseForm.HOLD) {
                /*
                 * The two facts, and not the paragraph they used to be wrapped in.
                 *
                 * The first thing this screen showed was an explanation of the DATA MODEL —
                 * that an exercise is name plus protocol, that "Hangs" at 7:3 and at 10:5 are
                 * different rows, that what counts as a record is the weight. Both facts are
                 * right here as chips; the argument for why the model is like that lives on
                 * the exercise editor, the screen where it can be acted on (rule 5: an
                 * explanation is not a paragraph).
                 */
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Line)) {
                        // the same words the picker and the overview use, from the one
                        // function that answers "what is this exercise's schedule" — see
                        // [scheduleCaption] for what the three of them used to say instead
                        val scheduleLine =
                            scheduleCaption(entity.protocolProgramId?.let { state.programsById[it] })
                        if (scheduleLine != null) {
                            IdentityChip("schedule", scheduleLine)
                        }
                        IdentityChip("tracked", "weight")
                    }
                }
            }

            /*
             * THE RECORD IS THE FIRST THING ON THE SCREEN.
             *
             * It used to be the last, below two charts — the best news this screen has, and
             * the only part of it that had to be scrolled to (rule 7). A form with no record
             * MODEL is the one exception: there the block is a sentence explaining why there
             * is nothing, and a screen that opens with a paragraph is exactly what the chips
             * above have just stopped doing. Those stay at the bottom.
             */
            if (form.hasRecords) {
                item { RecordsBlock(form, records, today) }
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
                        periodNote(period, granularity, windowEmpty),
                        fontSize = TextSize.Meta,
                        color = colors.inkSecondary,
                        lineHeight = TextSize.Meta * 1.4f,
                        modifier = Modifier.padding(top = Spacing.Line, start = Spacing.Tight),
                    )
                }
            }

            item {
                val label = trendAll?.spec?.label ?: "Trend"
                ChartCard(
                    title = label,
                    subtitle = chartUnit(trend),
                    series = trend,
                    // "log a couple of sessions and the line appears" is a lie to somebody
                    // who has logged plenty and is looking at the wrong month
                    emptyTitle = if (trendAll == null) trendEmptyTitle(form) else nothingIn(label),
                    // what to do about an empty WINDOW is said once, under the switch
                    emptyHint = if (trendAll == null) trendEmptyHint(form) else null,
                ) { series ->
                    LineChart(
                        slots = series.slots,
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
                        subtitle = chartUnit(volume),
                        series = volume,
                        emptyTitle = nothingIn(volumeAll.spec.label),
                        emptyHint = null,
                    ) { series ->
                        BarChart(slots = series.slots, format = series.spec.format)
                    }
                }
            }

            if (!form.hasRecords) {
                item { RecordsBlock(form, records, today) }
            }
        }
    }

    if (confirmDelete) {
        ConfirmRemoveDialog(
            title = "Delete this exercise?",
            subject = entity.name,
            explanation = (
                if (entryCount == 0) {
                    "Nothing has been recorded under it yet, so nothing else disappears with it. "
                } else {
                    "Its $entryCount ${if (entryCount == 1) "entry" else "entries"} go with it " +
                        "and stop showing anywhere - the history, the calendar, its own trend " +
                        "and records, the streak. "
                }
                ) + REMOVAL_IS_REVERSIBLE,
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                editor.delete(entity)
                onClose()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * "No impulse in this window" — the sentence a chart card puts under its own title when the
 * metric exists and the window is empty.
 *
 * Built from the metric's own label because there are a dozen of them (`SeriesSpec` in
 * domain/Analytics.kt) and a screen that named only the two a hangboard produces would go
 * quietly generic on the rest — which is the fault being fixed here, not a new one to
 * introduce.
 */
private fun nothingIn(label: String): String = "No ${label.lowercase()} in this window"

/**
 * A chart in its card — INCLUDING when there is no chart.
 *
 * ── The empty state stays inside the card, under its title ──────────────────────
 * This used to return before [GachiCard] was reached, so an empty window drew three
 * centred plaques in a row, two of them word for word identical, and not one of them said
 * WHICH chart was empty (rule 6). The title is the answer, and the title is drawn by this
 * function — so the early return had to move inside the card rather than the sentences
 * having to repeat what the heading already knew.
 *
 * [emptyHint] is usually null now: what to do about an empty window is advice about the
 * WINDOW, and it is given once, under the switch that sets it. A hint here means something
 * else — that this metric does not exist for this form at all, which no change of period
 * will fix.
 */
@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    series: SeriesOnAxis?,
    emptyTitle: String,
    emptyHint: String?,
    chart: @Composable (SeriesOnAxis) -> Unit,
) {
    val colors = LocalGachiColors.current
    GachiCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.Inset)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = Spacing.Line),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    title,
                    fontSize = TextSize.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (subtitle.isNotBlank()) {
                    Text(subtitle, fontSize = TextSize.Meta, color = colors.inkMuted)
                }
            }
            if (series == null || series.isEmpty) {
                HorizontalDivider(color = colors.grid)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .padding(top = Spacing.Inset),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.CenterVertically),
                ) {
                    Text(
                        emptyTitle,
                        fontSize = TextSize.Body,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (emptyHint != null) {
                        Text(
                            emptyHint,
                            fontSize = TextSize.Meta,
                            lineHeight = TextSize.Meta * 1.4f,
                            color = colors.inkSecondary,
                        )
                    }
                }
                return@Column
            }
            // one point is a dot, not a trend: say so rather than drawing a chart of it
            if (series.filled == 1) {
                Text(
                    "Only one entry in this window - not a trend yet.",
                    fontSize = TextSize.Meta,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(bottom = Spacing.Line),
                )
            }
            chart(series)
        }
    }
}

/**
 * Whether this form HAS a record model at all.
 *
 * Four of the six do not, each for its own reason ([RecordsBlock] states them), and the
 * difference decides where the block goes: a record is the first thing on the screen, an
 * explanation of why there is no record is the last.
 */
private val ExerciseForm.hasRecords: Boolean
    get() = this == ExerciseForm.STRENGTH || this == ExerciseForm.HOLD

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
                SectionHeader("Records")
                RecordCard(
                    label = "No record yet",
                    badge = false,
                    columns = emptyList(),
                    note = "The first weighted set is a baseline; the second one can already " +
                        "beat it.",
                )
            }

            else -> {
                SectionHeader("Records")
                /*
                 * ONE CARD PER AXIS, not one per [ExerciseRecord]. `holdRecord` (domain/Records.kt)
                 * is right to keep the left hand's best and the right hand's best as two separate
                 * comparisons — years of divergence between them makes merging the COMPARISON
                 * dishonest. But a screen that then drew each of those as its own full-width
                 * card, both captioned "Most weight hung", read as two different achievements
                 * for two different exercises rather than one exercise reported per hand.
                 *
                 * Same axis, same card, one COLUMN per hand — which is also what stopped the
                 * numbers being a sentence: "Left 10 / Right 7.5" set at 26 sp, with a second
                 * line repeating the construction for the dates, is prose. Two columns of
                 * label, figure and date can be compared at a glance, and the figure comes
                 * down to the one size the system has for the large number of a screen.
                 *
                 * `groupBy` keeps the order [holdRecord] already produced (left, then right), so
                 * a two-handed exercise or a strength exercise — where every group is a
                 * singleton — draws as a single column. There is no third column for the sets
                 * that named no hand: they belong to both hands now (domain/Records.kt).
                 */
                val grouped = records.groupBy { it.axis }.values.toList()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Inset)) {
                    grouped.forEachIndexed { index, group ->
                        val single = group.size == 1
                        RecordCard(
                            label = recordLabel(group.first()),
                            // only the leading card gets the badge: two green pills next to
                            // each other stop meaning "this is the notable one"
                            badge = index == 0,
                            columns = group.map { record ->
                                val (number, unit) = fmtValueParts(record.value, recordFormat(record))
                                RecordColumn(
                                    side = if (single) null else sideTag(record),
                                    value = number,
                                    unit = unit,
                                    `when` = fmtShortDay(LocalDate.parse(record.opDate)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One side's best on a record card: what it is, what it was, and when. */
private data class RecordColumn(
    /** "Left" / "Right", or null when the axis is not split by side at all. */
    val side: String?,
    val value: String,
    val unit: String?,
    val `when`: String,
)

/**
 * The record, at the top of the screen and in the one large size the type scale has.
 *
 * The figure is [TextSize.Figure] — 22, the size the system reserves for the single big
 * number of a screen. It was 26, which is not on the scale and which turned "Left 10 /
 * Right 7.5" into a line of prose rather than two numbers to compare.
 *
 * With [columns] empty the card is the empty state of the records block: it keeps its own
 * heading (the reason there is nothing) and adds the [note] under it, rather than being a
 * centred plaque that could belong to any block on the screen.
 */
@Composable
private fun RecordCard(
    label: String,
    badge: Boolean,
    columns: List<RecordColumn>,
    note: String? = null,
) {
    val colors = LocalGachiColors.current
    GachiCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.Inset),
            verticalArrangement = Arrangement.spacedBy(Spacing.Inset),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    fontSize = TextSize.Meta,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkSecondary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge) RecordBadge(null, Modifier.padding(start = Spacing.Line))
            }
            if (note != null) {
                Text(
                    note,
                    fontSize = TextSize.Meta,
                    lineHeight = TextSize.Meta * 1.4f,
                    color = colors.inkSecondary,
                )
            }
            if (columns.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Inset),
                ) {
                    columns.forEachIndexed { index, column ->
                        // the hairline between the two hands, so they read as one comparison
                        // split in two rather than as two unrelated numbers
                        if (index > 0) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.grid))
                        }
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        ) {
                            if (column.side != null) {
                                Text(
                                    column.side.uppercase(),
                                    fontSize = TextSize.Caption,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.3.sp,
                                    color = colors.inkMuted,
                                )
                            }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    column.value,
                                    fontSize = TextSize.Figure,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.2).sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (column.unit != null) {
                                    Text(
                                        " ${column.unit}",
                                        fontSize = TextSize.Meta,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.inkMuted,
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                }
                            }
                            Text(
                                column.`when`,
                                fontSize = TextSize.Meta,
                                color = colors.inkMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Left" / "Right" — the same words [FormDetailScreen]'s own identity chip and
 * `WorkoutLogScreen`'s cards already use for a [HoldSide].
 *
 * There used to be a third answer here, "No side", for a record built out of sets that named no
 * hand. It drew as a THIRD COLUMN beside the two hands on any exercise ticked one-sided after
 * its history had already been logged, which is what the owner asked to have taken away
 * (2026-08-11). Such sets now count for both hands instead, so `domain/Records.kt` no longer
 * produces a sideless record inside a split group and this branch is unreachable — it returns
 * no label rather than inventing a word for a case that cannot arrive.
 */
private fun sideTag(record: ExerciseRecord): String? = when (record.side) {
    HoldSide.LEFT -> "Left"
    HoldSide.RIGHT -> "Right"
    null -> null
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
    Period.MONTH -> "Last 30 days"
    Period.QUARTER -> "Last 3 months"
    Period.YEAR -> "Last 12 months"
    Period.ALL -> "All history"
}

/**
 * THE ONE STATEMENT OF THE WINDOW, under the control that sets it.
 *
 * The window used to be named three times in three wordings — "Showing: last 30 days, by
 * day" here, "kg - by day" over one chart and "kg·s - by day" over the other (rule 4). It
 * is one value, so it is said once, and the chart titles keep only their unit.
 *
 * When the window is empty, that is a fact about the window too, and the advice about
 * lengthening it belongs here rather than repeated inside every chart card.
 */
private fun periodNote(period: Period, granularity: Granularity, empty: Boolean): String {
    val window = "${periodSubtitle(period)}, ${granularityWord(granularity)}"
    return if (empty) "$window - nothing in this window. $WINDOW_EMPTY_HINT" else window
}

/** What to do about a window with nothing in it. Said once, by [periodNote]. */
private const val WINDOW_EMPTY_HINT =
    "Pick a longer period, or log a session and it lands here."

/**
 * The caption beside a chart title: THE UNIT of the Y axis, and nothing else.
 *
 * The unit lives here rather than on the axis itself because the axis repeats its labels
 * four or five times and "kg" beside every one of them is noise. But it has to be
 * somewhere: bare tick numbers are exactly the "chart without labelled axes" complaint
 * this screen was rewritten to fix. The bucket width used to be here too and is now said
 * once, by [periodNote].
 */
private fun chartUnit(series: SeriesOnAxis?): String = series?.let {
    axisUnit(it.spec.format, it.values.maxOrNull() ?: 0.0)
}.orEmpty()

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
