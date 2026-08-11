package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.toCatalog
import xyz.oleolegka.gachimuchi.domain.DoorTile
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.activityHeatmap
import xyz.oleolegka.gachimuchi.domain.doorTiles
import xyz.oleolegka.gachimuchi.domain.scheduleCaption
import xyz.oleolegka.gachimuchi.domain.heroStats
import xyz.oleolegka.gachimuchi.domain.presenceWindow
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.ActivityHeatmapCard
import xyz.oleolegka.gachimuchi.ui.components.DoorTile
import xyz.oleolegka.gachimuchi.ui.components.EmptyState
import xyz.oleolegka.gachimuchi.ui.components.HeroCard
import xyz.oleolegka.gachimuchi.ui.components.MiniBars
import xyz.oleolegka.gachimuchi.ui.components.MiniDots
import xyz.oleolegka.gachimuchi.ui.components.SectionHeader
import xyz.oleolegka.gachimuchi.ui.components.Sparkline
import xyz.oleolegka.gachimuchi.ui.components.rememberExerciseEditor
import xyz.oleolegka.gachimuchi.ui.fmtDelta
import xyz.oleolegka.gachimuchi.ui.fmtRecordDate
import xyz.oleolegka.gachimuchi.ui.fmtRelativeDay
import xyz.oleolegka.gachimuchi.ui.fmtValueParts
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * Overview: the score first, then the year, then the doors.
 *
 * The layout is `design-system/app-next/overview.html`: three blocks, 24 dp apart, 16 dp side
 * margins — the numbers of the scale rather than the 18 and 15 the prototype was drawn with.
 * Nothing on this screen is computed here — the hero counters, the
 * heatmap buckets and every tile come out of `domain/Analytics.kt`, which is what lets the
 * numbers be unit-tested and stops the screen from disagreeing with the detail screen it
 * leads to.
 *
 * The streak ring of the design-system mock-up is deliberately absent: decisions.md §12-C
 * took the streak off this screen and kept the heatmap.
 */
@Composable
fun OverviewScreen(
    state: UiState,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onOpenForm: (Long) -> Unit = {},
) {
    val catalog = remember(state.exercises) { state.exercises.mapNotNull { it.toCatalog() } }
    val hero = remember(state.events, today) { heroStats(state.events, today, windowDays = 7) }
    val heatmap = remember(state.events, today) {
        activityHeatmap(state.events, today.minusDays(363), today)
    }
    val tiles = remember(state.events, catalog) { doorTiles(state.events, catalog) }
    val byId = remember(state.exercises) { state.exercises.associateBy { it.id } }
    /*
     * Working on the catalog as its own thing — adding a row, or reaching one to rename or
     * delete it — is not the same errand as looking at a door tile above, but it is the same
     * exercises, so it lives on this tab rather than a screen built to hold one button. See
     * [rememberExerciseEditor] for why creating this way never touches the entry card.
     */
    val editor = rememberExerciseEditor()
    var browsingCatalog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.Block, end = Spacing.Block,
            top = Spacing.Line, bottom = Spacing.Cards,
        ),
        // Cards and not Block. SYSTEM.md's translation table says "18 -> 16" and its table of
        // MEANINGS says "between cards, 24"; the clarification of 2026-08-11 settled it for
        // all four feeds at once in favour of the meaning, because 24 is what stands in the
        // redraws the owner accepted.
        verticalArrangement = Arrangement.spacedBy(Spacing.Cards),
    ) {
        item {
            HeroCard(
                eyebrow = "Over the last 7 days",
                value = hero.workouts.toString(),
                unit = if (hero.workouts == 1) "workout" else "workouts",
                subtitle = if (hero.workouts == 0) "nothing logged this week yet" else "days with training",
                meta = heroMeta(hero.entries, hero.previousWorkouts, hero.workouts),
                highlight = if (hero.currentStreak >= 2) "${hero.currentStreak} days in a row" else null,
            )
        }

        item { ActivityHeatmapCard(heatmap, today) }

        item {
            Column(Modifier.fillMaxWidth()) {
                /*
                 * The legend says what the two marks on a tile MEAN, rather than naming the
                 * component that draws one: "sparkline - record" answered a question nobody
                 * asked, and half of it was the name of a widget.
                 *
                 * The catalog button is the header's action and no longer a full-width button
                 * under it — see SectionHeader. It is here whether or not a tile follows,
                 * because the catalog is worked on independently of whether anything has been
                 * logged against it yet; it is not the empty state's way out.
                 */
                SectionHeader("Forms - tap for details") {
                    OutlinedButton(
                        onClick = { browsingCatalog = true },
                        // 48 and not the mock-up's 40: a page in a browser is measured with
                        // a mouse, and this is the same floor every chip in the app keeps.
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = Spacing.Inset),
                    ) {
                        Text("Catalog")
                    }
                }
                // a line of its own, under the header, because the header row now carries a
                // control: a legend squeezed beside a button is a legend nobody reads
                Text(
                    "figure: last entries · pill: a record",
                    fontSize = TextSize.Caption,
                    color = LocalGachiColors.current.inkMuted,
                    modifier = Modifier.padding(bottom = Spacing.Line),
                )
                if (tiles.isEmpty()) {
                    EmptyState(
                        title = "No workouts of any kind yet",
                        // one sentence (SYSTEM.md rule 5): what to do and where it turns up
                        hint = "Start on Today - the first one shows up here as a tile.",
                    )
                }
            }
        }

        items(tiles, key = { it.exerciseId }) { tile ->
            val entity = byId[tile.exerciseId]
            val program = entity?.protocolProgramId?.let { state.programsById[it] }
            FormDoorTile(tile, entity, program, today, onOpenForm)
        }
    }

    if (browsingCatalog) {
        /*
         * The same sheet logging and planning use to pick an exercise, reused rather than
         * built again — see its own KDoc for why a caller says what happens after a pick. Here
         * that is "open its page" (rename, hide, delete all live on [FormDetailScreen], which
         * is otherwise reached only from a tile above, so an exercise with no history yet —
         * including one just created — would be unreachable without this), and creating adds a
         * row and stops, through [editor] rather than the workout-shaped path the other callers
         * use.
         */
        ExercisePickerSheet(
            state = state,
            today = today,
            heading = "Exercise catalog",
            createLabel = "Add to the catalog",
            onPick = {
                browsingCatalog = false
                onOpenForm(it)
            },
            onCreate = editor.create,
            onDismiss = { browsingCatalog = false },
        )
    }
}

/**
 * Meta line of the hero: what the number is made of and how it compares with last week.
 *
 * ── "0 entries" is not printed, the phrase is dropped ───────────────────────────
 * An ENTRY is a set somebody wrote down. A workout started and left empty — the owner logs a
 * session of climbing on rock that way — is a day of training with no entries in it, and since
 * the hero's own number counts DAYS (domain/Analytics.kt's `trainingDays`) a week holding only
 * such a session read "1 workout ... 0 entries".
 *
 * The fix is not to let an empty session count as an entry. Owner's ruling, 2026-08-11:
 * stretching the word to flatter a counter would be lying in the definition of the metric. The
 * count is honestly zero; a phrase with nothing to say is simply not shown, separator and all.
 */
private fun heroMeta(entries: Int, previous: Int, current: Int): String {
    val parts = mutableListOf<String>()
    if (entries > 0) parts += "$entries ${if (entries == 1) "entry" else "entries"}"
    parts += when {
        current > previous -> "${current - previous} more than the week before"
        current < previous -> "${previous - current} fewer than the week before"
        else -> "same as the week before"
    }
    return parts.joinToString(" - ")
}

/**
 * One row of the feed. The figure changes with the form: a line where there is a metric to
 * plot, bars for a duration total, dots for a check-in — a line through ones and zeroes
 * would imply a magnitude a check-in does not have.
 */
@Composable
private fun FormDoorTile(
    tile: DoorTile,
    entity: ExerciseEntity?,
    program: WorkoutProgram?,
    today: LocalDate,
    onOpenForm: (Long) -> Unit,
) {
    val colors = LocalGachiColors.current
    val spine = colors.forForm(tile.form)
    val last = tile.series.last
    val (number, unit) = last?.let { fmtValueParts(it.value, tile.series.spec.format) } ?: ("-" to null)
    val delta = tile.series.delta()

    DoorTile(
        name = tile.name,
        caption = tileCaption(tile, entity, program, today),
        value = number,
        unit = unit,
        delta = delta?.takeIf { it.improved }?.let { fmtDelta(it.change, tile.series.spec.format) },
        spineColor = spine,
        recordDate = tile.record?.let { fmtRecordDate(LocalDate.parse(it.opDate), today) },
        onClick = { onOpenForm(tile.exerciseId) },
    ) {
        val values = tile.series.points.map { it.value }
        when (tile.form) {
            ExerciseForm.TICK -> MiniDots(
                presenceWindow(tile.series.points, today, days = 10),
                colors.forForm(tile.form),
            )

            ExerciseForm.DURATION -> MiniBars(values.takeLast(6), colors.forForm(tile.form))

            else -> Sparkline(
                values.takeLast(12),
                color = colors.accent,
                height = 22.dp,
                lowerIsBetter = tile.series.spec.lowerIsBetter,
            )
        }
    }
}

/**
 * The caption under a tile name: what form it is and how long ago it happened.
 *
 * For a hangboard the protocol goes in too — §12-A makes it part of the exercise's
 * identity, so "Hangs" alone does not say which exercise this tile is about.
 */
private fun tileCaption(tile: DoorTile, entity: ExerciseEntity?, program: WorkoutProgram?, today: LocalDate): String {
    val parts = mutableListOf<String>()
    if (tile.form == ExerciseForm.HOLD && entity != null) {
        // one function for all three captions — see [scheduleCaption]
        scheduleCaption(program)?.let { parts += it }
    }
    if (parts.isEmpty()) parts += tile.form.title.lowercase()
    parts += fmtRelativeDay(LocalDate.parse(tile.lastDate), today)
    return parts.joinToString(" - ")
}

// ExerciseEntity.toCatalog() has moved to data/CatalogMapping.kt: a screen is not the place
// to describe how the table is read (see the KDoc there, and domain/Catalog.kt's CatalogRow,
// for the bug two independent mappers like this one used to cause).
