package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import xyz.oleolegka.gachimuchi.domain.DraftSummary
import xyz.oleolegka.gachimuchi.domain.dayCards
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.components.DayActions
import xyz.oleolegka.gachimuchi.ui.components.DayCardList
import xyz.oleolegka.gachimuchi.ui.components.SectionHeader
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today: the way into a workout, and nothing else.
 *
 * ── What this screen stopped being ──────────────────────────────────────────────
 * It used to be a report: every exercise of the day as its own card, with a "Records today"
 * block above them. That answered "what did I do", which is a question the Overview tab and
 * the calendar already answer better, and it answered it on the screen the app opens on —
 * so the screen you land on holding a phone in a gym was a summary of the past with a
 * general-purpose "Log a set" button floating over it.
 *
 * It is now a SHORT LIST OF THINGS TO TAP. A planned session offers to start; a workout in
 * progress offers to continue; anything already done is one card you can open. Two or three
 * cards, one screenful, and the choice is made by tapping the workout rather than by
 * pressing a button and then working out which exercise it meant.
 *
 * The records did not vanish with the block that showed them: a card says whether records
 * were set on it, the workout behind the card spells them out, and the Overview tab has the
 * standing ones. What went away is a heading that was only ever populated on a good day.
 *
 * The list itself is `DayCardList` and is shared with the calendar — see
 * ui/components/DayCardList.kt, and domain/DayCards.kt for what a card is.
 */
@Composable
fun TodayScreen(
    state: UiState,
    today: LocalDate,
    actions: DayActions,
    modifier: Modifier = Modifier,
    /** The workout being composed, when there is one — see [DraftSummary]. */
    draft: DraftSummary? = null,
) {
    val colors = LocalGachiColors.current

    /*
     * The plan is judged against the clock, not only the date (a session at 20:00 is still
     * outstanding at noon), so "now" is read again whenever the data changes. A screen left
     * open and untouched keeps the reading it was composed with — the same trade the
     * calendar makes, and one recomposition away from correct.
     */
    val now: LocalDateTime = remember(state.events, state.slots, today) { LocalDateTime.now() }
    val day = remember(state.events, state.slots, today, now, draft) {
        dayCards(state.events, state.slots, today, today, now, draft)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.Block, end = Spacing.Block,
            top = Spacing.Line, bottom = Spacing.Cards,
        ),
        /*
         * Cards, not Block — and this is the one gap on the screen that looked CORRECT while
         * being wrong. Everything else here was a raw dp; this already named a constant from
         * the scale, just the constant for "between blocks inside a card" rather than the one
         * for "between cards". SYSTEM.md's clarification of 2026-08-11 settled the feeds of
         * Overview, Today, Calendar and FormDetail together at 24, so all four look alike.
         */
        verticalArrangement = Arrangement.spacedBy(Spacing.Cards),
    ) {
        item {
            /*
             * The date, and nothing beside it.
             *
             * There used to be a "Demo data" button here, one tap from the primary screen,
             * with no confirmation, which poured synthetic sets straight into the journal.
             * The one screen that answers "what am I doing today" was the one screen that
             * could make that answer untrue by accident. The demo is gone entirely now, and
             * an empty day says what to do rather than being padded out with invented sets.
             */
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
            ) {
                Text(
                    today.format(weekdayDateFormat),
                    fontSize = TextSize.Title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // the year is the least of the three facts here and is set as such, rather than
                // joined to the date with a dash and given the same weight as the weekday
                Text("${today.year}", fontSize = TextSize.Meta, color = colors.inkMuted)
            }
        }

        item {
            Column(Modifier.fillMaxWidth()) {
                SectionHeader("Today", cardNote(day.cards.size))
                DayCardList(day = day, date = today, actions = actions, pastWorkoutNames = state.pastWorkoutNames)
            }
        }
    }
}

private fun cardNote(cards: Int): String = when (cards) {
    0 -> "nothing yet"
    1 -> "1 card"
    else -> "$cards cards"
}

private val weekdayDateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
