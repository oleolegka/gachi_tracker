package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.Bodyweight
import xyz.oleolegka.gachimuchi.domain.Cardio
import xyz.oleolegka.gachimuchi.domain.Duration
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.Tick
import xyz.oleolegka.gachimuchi.domain.formatClock
import xyz.oleolegka.gachimuchi.ui.fmtAddedKg
import xyz.oleolegka.gachimuchi.ui.fmtDistance
import xyz.oleolegka.gachimuchi.ui.fmtDuration
import xyz.oleolegka.gachimuchi.ui.fmtKg
import xyz.oleolegka.gachimuchi.ui.fmtPace
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize
import kotlin.math.max

/**
 * What was done of one exercise, as a TABLE rather than as one comma-joined sentence.
 *
 * ── What was wrong with the sentence ────────────────────────────────────────────
 * `sets.joinToString(", ")` produced, for three hangs of a fingerboard:
 *
 *     +7.5 kg, 5 reps × 7 s, 7:3 protocol, +7.5 kg, 5 reps × 7 s, 7:3 protocol, +5 kg, …
 *
 * The owner's word for it was "chaos". Three separate faults are in there: the protocol is a
 * property of the EXERCISE and was printed once per set; two identical sets were spelled out
 * twice; and no number stood under any other number, so the one question the block exists to
 * answer — did the weight go up or down — could not be answered by looking.
 *
 * ── What replaces it ────────────────────────────────────────────────────────────
 * Three columns and a badge. How many times, what was on the bar (right-aligned, so the
 * kilograms line up), and what was done with it. The protocol moves to the card's meta line
 * when every set shares one, and the rest is not printed at all unless THIS set rested for
 * something other than what the card is set to — one value, one place.
 *
 * ── Why only NEIGHBOURING sets collapse ─────────────────────────────────────────
 * The order of the sets is a fact. "60 · 62.5 · 60" is a session that backed off and
 * "2× 60 · 62.5" is one that went up, and grouping by weight across the whole card would print
 * the second when the first happened. Runs of equal neighbours carry no such information, so
 * they collapse; one set gets no "1×", which would be the same needless repetition in a
 * smaller font.
 */

/** One recorded set, taken apart far enough for a table to line its columns up. */
data class SetParts(
    /** What was on it: "+7.5 kg", "60 kg", "78.4 kg", or "" when nothing was loaded. */
    val load: String,
    /** What was done with it: "8 reps", "5 reps × 7 s", "5.2 km · 28 min". */
    val volume: String,
    /** "7:3 protocol", or null for a set that runs to no protocol. */
    val protocol: String? = null,
    /** The rest recorded on THIS set, in seconds, or null when none was written down. */
    val restAfterSec: Int? = null,
    val warmup: Boolean = false,
    val incomplete: Boolean = false,
)

/** One LINE of the table: a run of identical neighbouring sets, and what to say about it. */
data class SetRow(
    /** How many identical sets in a row this line stands for. Never below one. */
    val count: Int,
    val load: String,
    val volume: String,
    /**
     * What is added to [volume] in a quieter voice — the protocol when it is not the card's, and
     * a rest that differs from the one the card is set to. Empty when there is nothing to add.
     */
    val note: String,
    val warmup: Boolean,
    val incomplete: Boolean,
)

/** The sets of one card, ready to draw: the lines, and what the card says once above them. */
data class SetTableModel(
    val rows: List<SetRow>,
    /**
     * The protocol every set of this card shares, said ONCE in the card's meta line, or null
     * when the sets disagree (then each row carries its own) or there is no protocol at all.
     */
    val commonProtocol: String?,
)

/**
 * Takes the sets of one card apart, lifts what they all agree on, and collapses the runs.
 *
 * [restSec] is the rest the CARD is set to — the value printed on its own button. A set that
 * rested for exactly that says nothing about it, which is the whole of "one value, one place";
 * a set that rested for something else says so, because then the number is news.
 */
fun setTable(forms: List<ActivityForm>, restSec: Int?): SetTableModel {
    val parts = forms.map { it.setParts() }
    val protocols = parts.map { it.protocol }.distinct()
    val common = protocols.singleOrNull()

    val rows = ArrayList<SetRow>()
    for (part in parts) {
        val note = listOfNotNull(
            part.protocol.takeIf { common == null },
            part.restAfterSec
                ?.takeIf { it != restSec }
                ?.let { "rest ${formatClock(it)}" },
        ).joinToString(" · ")
        val row = SetRow(
            count = 1,
            load = part.load,
            volume = part.volume,
            note = note,
            warmup = part.warmup,
            incomplete = part.incomplete,
        )
        val last = rows.lastOrNull()
        if (last != null && last.copy(count = 1) == row) {
            rows[rows.lastIndex] = last.copy(count = last.count + 1)
        } else {
            rows += row
        }
    }
    return SetTableModel(rows, common)
}

/**
 * One set, split into the load and what was done with it.
 *
 * The split is by MEANING and not by string surgery: [xyz.oleolegka.gachimuchi.ui.summaryLine]
 * joins the same pieces for the places that want one line, and neither is derived from the
 * other — a column cannot be recovered from a sentence once it has been written.
 */
fun ActivityForm.setParts(): SetParts = when (this) {
    is StrengthSet -> SetParts(
        load = when {
            weightKg != null -> fmtKg(weightKg)
            // signed by fmtAddedKg and never by a "+" written here: assistance is a negative
            // added weight, and a hard-coded plus printed it as "+-20 kg"
            ownWeight -> listOfNotNull("body weight", addedKg?.let { fmtAddedKg(it) }).joinToString(" ")
            else -> ""
        },
        volume = "$reps reps",
        restAfterSec = restAfterSec?.toInt(),
        warmup = warmup,
        incomplete = incomplete,
    )

    is HoldSet -> SetParts(
        load = when {
            addedKg != null -> fmtAddedKg(addedKg)
            ownWeight -> "body weight"
            else -> ""
        },
        volume = buildString {
            reps?.let { append("$it reps") }
            holdSec?.let {
                if (isNotEmpty()) append(" × ")
                append(fmtDuration(it.toInt()))
            }
        },
        protocol = if (workSec != null && restSec != null) {
            "${workSec.toInt()}:${restSec.toInt()} protocol"
        } else {
            null
        },
        restAfterSec = restAfterSec?.toInt(),
        warmup = warmup,
        incomplete = incomplete,
    )

    is Duration -> SetParts(load = "", volume = fmtDuration(durationSec))

    is Tick -> SetParts(load = "", volume = "check-in")

    is Cardio -> SetParts(
        load = "",
        volume = listOfNotNull(
            distanceM?.let { fmtDistance(it) },
            durationSec?.let { fmtDuration(it) },
            paceSecPerKm?.let { fmtPace(it) },
        ).joinToString(" · "),
    )

    // the weigh-in has no "what was done with it": the number IS the entry
    is Bodyweight -> SetParts(load = fmtKg(weightKg), volume = "")
}

/** The badge a row wears, or none. A set can be both a ramp-up and a set that fell short. */
private fun SetRow.badges(): List<Pair<String, Boolean>> = buildList {
    if (warmup) add("Warm-up" to false)
    if (incomplete) add("Not completed" to true)
}

/**
 * Draws [rows] with the loads under each other.
 *
 * ── Why this is a Layout of its own ─────────────────────────────────────────────
 * The columns of one card have to agree on their widths, and nothing in the standard library
 * does that: a `Row` per line divides the space of that line alone, so "+7.5 kg" and "+15 kg"
 * each get their own idea of where the column starts. Two passes fix it — measure the three
 * columns whose width comes from their content, then give what is left to the one that wraps —
 * and two passes is exactly what a custom [Layout] is for.
 *
 * A fixed dp width was the cheap alternative and it is wrong for a reason that shows up on a
 * phone rather than in a test: the system font scale can be at 200 %, and the column measured
 * for "60 kg" at 100 % clips it.
 */
@Composable
fun SetTable(rows: List<SetRow>, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    val quiet = SpanStyle(color = colors.inkMuted)

    Layout(
        modifier = modifier,
        content = {
            rows.forEach { row ->
                // 1 — how many times
                Text(
                    if (row.count > 1) "${row.count}×" else "",
                    fontSize = TextSize.Meta,
                    color = colors.inkMuted,
                    style = TabularFigures,
                )
                // 2 — what was on it
                Text(
                    row.load,
                    fontSize = TextSize.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = TabularFigures,
                )
                // 3 — what was done with it, and the quiet note after it
                Text(
                    buildAnnotatedString {
                        append(row.volume)
                        if (row.note.isNotEmpty()) {
                            if (row.volume.isNotEmpty()) append(" ")
                            withStyle(quiet) { append("· ${row.note}") }
                        }
                    },
                    fontSize = TextSize.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = TabularFigures,
                )
                // 4 — what is unusual about it
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                    row.badges().forEach { (text, alarming) -> SetBadge(text, alarming) }
                }
            }
        },
    ) { measurables, constraints ->
        val gap = Spacing.Line.roundToPx()
        val lineGap = Spacing.Tight.roundToPx()
        val free = Constraints()

        // the three columns whose width is their content's, measured before anything is placed
        val counts = ArrayList<Placeable>(rows.size)
        val loads = ArrayList<Placeable>(rows.size)
        val badges = ArrayList<Placeable>(rows.size)
        for (i in rows.indices) {
            counts += measurables[i * 4].measure(free)
            loads += measurables[i * 4 + 1].measure(free)
            badges += measurables[i * 4 + 3].measure(free)
        }
        val countW = counts.maxOfOrNull { it.width } ?: 0
        val loadW = loads.maxOfOrNull { it.width } ?: 0
        val badgeW = badges.maxOfOrNull { it.width } ?: 0

        val total = constraints.maxWidth
        // what is left over goes to the column that can wrap. Never below zero: a badge and a
        // load can between them eat a 360 dp screen, and a negative constraint is a crash
        val volumeW = (total - countW - loadW - badgeW - gap * 3).coerceAtLeast(0)
        val volumes = rows.indices.map { i ->
            measurables[i * 4 + 2].measure(Constraints(maxWidth = volumeW))
        }

        /*
         * BASELINES, not tops. The three sizes on a line (13, 15, 11) sit on one line of type,
         * which is what makes a table read as a table; aligning their boxes instead would leave
         * the count floating above the weight beside it.
         */
        val cells = rows.indices.map { i -> listOf(counts[i], loads[i], volumes[i], badges[i]) }
        val baselines = cells.map { row ->
            row.mapNotNull { it[FirstBaseline].takeIf { line -> line != Int.MIN_VALUE } }
                .maxOrNull() ?: 0
        }
        val offsets = cells.mapIndexed { i, row ->
            row.map { cell ->
                val own = cell[FirstBaseline]
                if (own != Int.MIN_VALUE) baselines[i] - own else 0
            }
        }
        val heights = cells.mapIndexed { i, row ->
            row.mapIndexed { j, cell -> offsets[i][j] + cell.height }.maxOrNull() ?: 0
        }

        val height = heights.sum() + lineGap * max(0, rows.size - 1)
        layout(total, height) {
            var y = 0
            cells.forEachIndexed { i, row ->
                val (count, load, volume, badge) = row
                // right-aligned inside its own column, so the digits stack
                count.place(countW - count.width, y + offsets[i][0])
                load.place(countW + gap + (loadW - load.width), y + offsets[i][1])
                volume.place(countW + gap + loadW + gap, y + offsets[i][2])
                badge.place(total - badge.width, y + offsets[i][3])
                y += heights[i] + lineGap
            }
        }
    }
}

/**
 * The mark on a set that is not an ordinary working one.
 *
 * "Not completed" is a FILL and not coloured text. In the app it was `--warning` type on the
 * recessed surface, which measures 1.6:1 — below anything readable, on the one line of the card
 * that exists to be noticed. The warning colour is not redefined in the dark theme, so black on
 * it works out at about 11:1 in both. "Warm-up" stays a neutral outline: a ramp-up set is not a
 * problem and must not be dressed as one.
 */
@Composable
private fun SetBadge(text: String, alarming: Boolean) {
    val colors = LocalGachiColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.Small))
            .then(
                if (alarming) {
                    Modifier.background(colors.warning)
                } else {
                    Modifier.border(1.dp, colors.border, RoundedCornerShape(Radius.Small))
                }
            )
            .padding(horizontal = Spacing.Tight + 2.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            fontSize = TextSize.Caption,
            fontWeight = FontWeight.SemiBold,
            color = if (alarming) Color(0xFF0B0B0B) else colors.inkMuted,
        )
    }
}

/**
 * Figures of a fixed width, asked for explicitly.
 *
 * The system typeface serves proportional digits by default: "1" is narrower than "8", so a
 * column of right-aligned weights still wobbles. Nothing in Compose turns this on for you.
 */
val TabularFigures: TextStyle = TextStyle(fontFeatureSettings = "tnum")
