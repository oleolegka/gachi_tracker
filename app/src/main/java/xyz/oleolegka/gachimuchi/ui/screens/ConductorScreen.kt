package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.oleolegka.gachimuchi.ui.components.TimerActions
import xyz.oleolegka.gachimuchi.ui.components.TimerUiState
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing

/**
 * The conductor: a protocol-led set, running, with the screen to itself.
 *
 * ── Why a set gets a whole screen when a rest gets a bar ─────────────────────────
 * The two kinds of countdown differ in who is in charge (domain/Floors.kt). A rest is a
 * floor — it says "not before", it may be read late, and several of them share the card list
 * quite happily. A protocol INSIDE a set says "now": seven seconds on, three off, and an
 * instruction that arrives after the moment it describes has ruined the set it was timing.
 * There is at most one of those at a time, and it owns the screen and the speaker for as long
 * as it runs (§13.2).
 *
 * ── Leaving is not stopping, and that is the whole point of the arrow ────────────
 * The back gesture and the arrow both close this screen and do NOTHING to the run: it keeps
 * counting, keeps speaking, and the card of the exercise it belongs to says so and leads back
 * here. That is what makes a superset work — the bench is logged from the card list while the
 * hang is still being called out — and it is also the honest reading of a back gesture, which
 * has never meant "abandon what you were doing" anywhere else in this app.
 *
 * Stopping is the "Stop" button inside the panel, and it is deliberately the only thing that
 * ends a set early: an interrupted run still offers what it managed (§13.3, step 13), so the
 * button is not a discard and does not need a confirmation in front of it.
 *
 * ── Nothing here owns any state ──────────────────────────────────────────────────
 * The run lives in the process-wide TimerController, because it has to survive this
 * composition, this Activity and — via the alarm — this process. This screen is a view of
 * [TimerUiState] and a handful of callbacks, so being killed and rebuilt mid-set costs
 * nothing but a redraw.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConductorScreen(
    /** The exercise the set belongs to, or null when the run names none. */
    exerciseName: String?,
    state: TimerUiState,
    actions: TimerActions,
    /** Close the screen. The run is untouched — see the note above. */
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            exerciseName ?: state.run?.programName ?: "Set",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            // said in words, once, where the gesture is: a person who
                            // assumes back means "cancel" will not try it to find out
                            "Back leaves this screen - the set keeps running",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onLeave) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Leave this screen, the set keeps running",
                        )
                    }
                },
            )
        },
    ) { padding ->
        // 15 is not on the scale and never was; the screens either side of this one sit on 16
        Column(Modifier.padding(padding).fillMaxWidth().padding(horizontal = Spacing.Block)) {
            /*
             * The same panel the Programs tab draws, and not a second one built to look like
             * it. A protocol read from three metres away while hanging off a fingerboard is
             * the case both screens are answering, so a copy here would be one more place for
             * the numbers to start disagreeing about what step is running.
             */
            RunPanel(state = state, actions = actions)
        }
    }
}
