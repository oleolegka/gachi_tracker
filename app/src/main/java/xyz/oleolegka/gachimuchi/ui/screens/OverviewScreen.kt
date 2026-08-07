package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.CatalogExercise
import xyz.oleolegka.gachimuchi.domain.DoorTile
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.activityHeatmap
import xyz.oleolegka.gachimuchi.domain.doorTiles
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
import xyz.oleolegka.gachimuchi.ui.fmtDelta
import xyz.oleolegka.gachimuchi.ui.fmtRecordDate
import xyz.oleolegka.gachimuchi.ui.fmtRelativeDay
import xyz.oleolegka.gachimuchi.ui.fmtValueParts
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate

/**
 * Overview: the score first, then the year, then the doors.
 *
 * The layout is the one from `design/prototype` §Overview: three blocks, 18 dp apart,
 * 15 dp side margins. Nothing on this screen is computed here — the hero counters, the
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

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
                SectionHeader("Forms - tap for details", "sparkline - record")
                if (tiles.isEmpty()) {
                    EmptyState(
                        title = "No workouts of any kind yet",
                        hint = "Start on Today: a workout, or a single entry. The first one " +
                            "shows up here as a tile and becomes the baseline every record " +
                            "is measured against.",
                    )
                }
            }
        }

        items(tiles, key = { it.exerciseId }) { tile ->
            FormDoorTile(tile, byId[tile.exerciseId], today, onOpenForm)
        }
    }
}

/** Meta line of the hero: what the number is made of and how it compares with last week. */
private fun heroMeta(entries: Int, previous: Int, current: Int): String {
    val parts = mutableListOf("$entries ${if (entries == 1) "entry" else "entries"}")
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
        caption = tileCaption(tile, entity, today),
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
 * For a hangboard the edge and protocol go in too — §12-A makes them part of the
 * exercise's identity, so "Hangs" alone does not say which exercise this tile is about.
 */
private fun tileCaption(tile: DoorTile, entity: ExerciseEntity?, today: LocalDate): String {
    val parts = mutableListOf<String>()
    if (tile.form == ExerciseForm.HOLD && entity != null) {
        entity.edgeMm?.let { parts += "${it.toInt()} mm edge" }
        if (entity.protocolWorkSec != null && entity.protocolRestSec != null) {
            parts += "${entity.protocolWorkSec.toInt()}:${entity.protocolRestSec.toInt()}"
        }
    }
    if (parts.isEmpty()) parts += tile.form.title.lowercase()
    parts += fmtRelativeDay(LocalDate.parse(tile.lastDate), today)
    return parts.joinToString(" - ")
}

/**
 * Catalog row -> what the dashboard needs; an unreadable form code drops out of the feed.
 *
 * ── Every column the analytics can use, or they silently do nothing ─────────────
 * This mapping used to carry the id, the name and the form, and drop the rest. The three it
 * dropped are not decoration:
 *
 *  - [ExerciseEntity.uid] is how an entry names its exercise off this phone. Without it the
 *    link fell back to the local row number for everything, which is the fallback meant for
 *    entries too old to carry an identity — see [ExerciseLink.matches].
 *  - [ExerciseEntity.oneSided] is what splits a record per hand. Dropped, `recordsOf` took its
 *    default and reported one record for both hands: the better one, hiding the gap the
 *    training exists to close.
 *  - [ExerciseEntity.bodyweightShare] is what gives a pull-up any tonnage at all. Dropped,
 *    `volumeSeries` took its default and a week of pull-ups drew as a week of doing nothing.
 *
 * All three arrived with columns of their own and defaults that preserve the old behaviour,
 * which is exactly why leaving them out compiled, ran, and quietly answered the old question.
 */
fun ExerciseEntity.toCatalog(): CatalogExercise? =
    runCatching { ExerciseForm.fromCode(form) }.getOrNull()
        ?.let {
            CatalogExercise(
                id = id,
                name = name,
                form = it,
                uid = uid,
                oneSided = oneSided,
                bodyweightShare = bodyweightShare,
            )
        }
