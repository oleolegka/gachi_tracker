package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.ActivityEvent
import xyz.oleolegka.gachimuchi.domain.LoadedSet
import xyz.oleolegka.gachimuchi.domain.RecordHit
import xyz.oleolegka.gachimuchi.ui.fmtDuration
import xyz.oleolegka.gachimuchi.ui.summaryLine
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import java.time.Duration
import java.time.LocalDateTime
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * The width of the set-number gutter, and therefore the indent of every line that hangs
 * under a set rather than beside it. One value because the two have to agree: they are the
 * same column, and the second line looks broken the moment it stops lining up with the
 * first. Not on the spacing scale for the same reason — it is a column width, not a gap.
 */
private val NumberColumn = 20.dp

/**
 * The breakdown of what was done: a heading, and one row per entry with its numbers, its
 * clock time and the record it broke.
 *
 * ── Why this is a component and not a private function on one screen ────────────
 * It started as one, on the workout review screen, and then the day breakdown needed exactly
 * the same rows for entries recorded OUTSIDE a workout (§14.2). Writing them again would have
 * been two answers to "what does a set look like when you read it back" — and the second one
 * would have been the one to quietly lose the record line, because nothing on that screen
 * would have said it was missing. So the rows moved here whole, and both screens draw the same
 * ones.
 *
 * ── The gesture, and the two things behind it ───────────────────────────────────
 * A long press on a row raises what can be done with it: correcting it and removing it. See
 * [ItemActions] for why a press rather than a control per row, and why the removal asks once
 * more before it writes. A caller that passes neither gets read-only rows, which is what a
 * screen with nothing to write with should show.
 */
@Composable
fun EntryBlock(
    /** The heading: an exercise's name, or what the entries have in common. */
    name: String,
    /**
     * The rest CHOSEN for this exercise in this workout, in seconds, or null when nobody chose
     * one. A different fact from the pauses [showGaps] measures — see `WorkoutExerciseAdded`.
     */
    restSec: Int?,
    entries: List<ActivityEvent>,
    /** The record verdicts of the day, by event id — folded once by the caller. */
    recordOf: Map<Long, RecordHit?>,
    modifier: Modifier = Modifier,
    /** What a row says when there are none. */
    emptyNote: String = "no sets yet",
    /** Correct one entry. Null leaves the rows read-only. */
    onCorrect: ((eventId: Long) -> Unit)? = null,
    /** Remove one entry. Already confirmed by the time it is called. */
    onRemove: ((eventId: Long) -> Unit)? = null,
    /**
     * Whether each row states how long after the previous one it was recorded.
     *
     * Off inside a workout, where the heading already carries the rest that was CHOSEN and two
     * numbers about rest on one card would have to be told apart every time they disagreed.
     * On where there is no chosen rest to show — entries logged on their own — because there
     * the gap between one set and the next is the only thing that answers "how long did I
     * actually wait", which is the question the day breakdown exists for.
     */
    showGaps: Boolean = false,
) {
    val colors = LocalGachiColors.current
    GachiCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = Spacing.Inset, vertical = Spacing.Line,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                fontSize = TextSize.Title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // the rest CHOSEN for this exercise in this workout, which is a different fact
            // from the pause the timestamps happen to show (see WorkoutExerciseAdded)
            restSec?.takeIf { it > 0 }?.let {
                Text("rest ${fmtDuration(it)}", fontSize = TextSize.Caption, color = colors.inkMuted)
            }
        }
        HorizontalDivider(color = colors.grid)

        if (entries.isEmpty()) {
            Text(
                emptyNote,
                fontSize = TextSize.Meta,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = Spacing.Inset, vertical = Spacing.Line),
            )
            return@GachiCard
        }

        entries.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = colors.grid)
            EntryLine(
                number = index + 1,
                entry = entry,
                record = recordOf[entry.id],
                gapSec = if (showGaps && index > 0) {
                    secondsBetween(entries[index - 1].ts, entry.ts)
                } else {
                    null
                },
                onCorrect = onCorrect,
                onRemove = onRemove,
            )
        }
    }
}

/**
 * One recorded entry, and the long press that acts on it.
 *
 * The numbers are on the left and the clock time on the right. Nothing else is drawn: the row
 * is read far more often than it is corrected, and a control on it would be paying for the
 * rare case out of the common one's legibility. There is no delete affordance anywhere near
 * a thumb, deliberately — removing training that actually happened is two deliberate acts.
 */
@Composable
private fun EntryLine(
    number: Int,
    entry: ActivityEvent,
    record: RecordHit?,
    gapSec: Int?,
    onCorrect: ((eventId: Long) -> Unit)?,
    onRemove: ((eventId: Long) -> Unit)?,
) {
    val colors = LocalGachiColors.current
    val summary = entry.form.summaryLine()
    // the only thing on the row that can say a set does not count towards a record - the
    // reps and the weight next to it look exactly like a working set's (Records.kt)
    val warmup = (entry.form as? LoadedSet)?.warmup == true
    // the only thing on the row that can say a set did not go the distance - see
    // [StrengthSet.incomplete]; the weight and reps still read like a set that landed clean
    val incomplete = (entry.form as? LoadedSet)?.incomplete == true
    var confirming by remember(entry.id) { mutableStateOf(false) }

    val menu = buildList {
        onCorrect?.let { correct -> add(ItemAction("Correct") { correct(entry.id) }) }
        onRemove?.let { add(ItemAction("Remove entry", destructive = true) { confirming = true }) }
    }

    ItemActions(title = summary, actions = menu, modifier = Modifier.fillMaxWidth()) { press, _ ->
        Column(
            Modifier
                .fillMaxWidth()
                .then(press)
                .padding(horizontal = Spacing.Inset, vertical = Spacing.Line)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$number",
                    fontSize = TextSize.Caption,
                    color = colors.inkMuted,
                    modifier = Modifier.width(NumberColumn),
                )
                Text(
                    summary,
                    fontSize = TextSize.Meta,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (warmup) WarmupBadge(Modifier.padding(end = Spacing.Tight))
                if (incomplete) IncompleteBadge(Modifier.padding(end = Spacing.Tight))
                clockOf(entry)?.let { Text(it, fontSize = TextSize.Caption, color = colors.inkMuted) }
            }
            /*
             * The pause that was actually taken, which is a MEASUREMENT and says so: it counts
             * from the moment the previous set was written down, which is a few seconds after
             * it ended and rather longer when the phone stayed in a pocket. Stated as "after"
             * rather than as "rest" so it is never read as the rest that was chosen.
             */
            gapSec?.let {
                Text(
                    "after ${fmtDuration(it)}",
                    fontSize = TextSize.Caption,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(start = NumberColumn, top = Spacing.Tight),
                )
            }
            /*
             * `goodText` and not `good`: the fill colour of a badge is not a colour to set type
             * in. On the light surface `good` is 3.27:1 - PALER than the muted captions above it
             * (3.50:1), which made the one line the journal is opened for the faintest thing on
             * the card. SYSTEM.md rule 7: good news is never fainter than ordinary news.
             * `goodText` is 7.35:1 here and identical to `good` in the dark theme, where the
             * problem never existed. Same role DayCardList already uses for the same line.
             */
            record?.let {
                Text(
                    "Record: ${it.text}",
                    fontSize = TextSize.Caption,
                    color = colors.goodText,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = NumberColumn, top = Spacing.Tight),
                )
            }
        }
    }

    if (confirming) {
        ConfirmRemoveDialog(
            title = "Remove this entry?",
            subject = summary,
            explanation = "It stops counting towards volume, records and the streak. " +
                REMOVAL_IS_REVERSIBLE,
            onConfirm = {
                confirming = false
                onRemove?.invoke(entry.id)
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * "Warm-up" - the one thing on a row that tells a ramp-up set apart from a working one.
 *
 * Nothing else does: the reps and the weight of a warm-up set look exactly like a working
 * set's, and domain/Records.kt silently leaves it out of every record it judges. Without
 * this a user reading "60 x 5, 60 x 8, 65 x 5" back has no way to tell why the first one
 * did not win anything - the badge is that answer, kept small and neutral (the muted role,
 * not [GachiColors.warning] - a warm-up is not a problem, just a set that does not count).
 */
@Composable
private fun WarmupBadge(modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Text(
        "Warm-up",
        modifier = modifier
            .clip(RoundedCornerShape(Radius.Small))
            .background(colors.recessed)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.Small))
            .padding(horizontal = Spacing.Line, vertical = Spacing.Tight),
        fontSize = TextSize.Caption,
        fontWeight = FontWeight.Medium,
        color = colors.inkMuted,
    )
}

/**
 * "Not completed" - the one thing on a row that says the lifter did not carry the set through,
 * at a weight and rep count that otherwise read exactly like a set that landed clean.
 *
 * Modelled on [WarmupBadge], with one deliberate difference: this one carries
 * [GachiColors.warning] rather than the muted role. A warm-up not counting is the expected
 * shape of a warm-up; a set that fell short of what it was attempted at is closer to worth a
 * second look, which is what the milder-than-critical warning tone is for. Set by hand on the
 * entry card and on the correction dialog - see ui/screens/LogScreen.kt's IncompleteChip and
 * ui/components/EntryEditor.kt's IncompleteToggle - never inferred, because the app has no way
 * to know whether a hold actually went the distance.
 *
 * ── The colour is the FILL, not the type ────────────────────────────────────────
 * It used to be `warning` type on the recessed surface, which is **1.60:1** - three times under
 * the floor, on the one word of the row that exists to be noticed. Filled and set in black it
 * is **10.7:1**, and in BOTH themes, because `warning` is the one status colour the palette does
 * not redefine per theme. This badge is a second, older copy of the one SetTable draws (that one
 * was fixed first); the two now look the same because they say the same thing, and this copy is
 * the one that reaches the workout screen and the day breakdown.
 */
@Composable
private fun IncompleteBadge(modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Text(
        "Not completed",
        modifier = modifier
            .clip(RoundedCornerShape(Radius.Small))
            .background(colors.warning)
            .padding(horizontal = Spacing.Line, vertical = Spacing.Tight),
        fontSize = TextSize.Caption,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF0B0B0B),
    )
}

/**
 * "HH:mm" of the entry, but only when it was written on the day it is filed under.
 *
 * Old training typed up on the sofa carries the time it was TYPED, and printing that beside
 * a set done last Tuesday morning would be a plausible-looking lie. Same rule as the day
 * cards and the calendar's own stamps.
 */
private fun clockOf(entry: ActivityEvent): String? =
    if (entry.ts.length >= 16 && entry.ts.startsWith("${entry.opDate}T")) {
        entry.ts.substring(11, 16)
    } else {
        null
    }

/**
 * Whole seconds between two journal timestamps, or null when either will not parse or the
 * second is not after the first.
 *
 * Null rather than a negative number or a zero: rows are ordered by when they were WRITTEN,
 * and a clock that moved backwards between two of them (a timezone change, a manual correction
 * on the phone) makes the difference meaningless rather than small. Saying nothing is the
 * honest answer; printing "after 0 s" would invent a fact.
 */
private fun secondsBetween(earlier: String, later: String): Int? = runCatching {
    Duration.between(LocalDateTime.parse(earlier), LocalDateTime.parse(later)).seconds
}.getOrNull()?.takeIf { it > 0 }?.toInt()
