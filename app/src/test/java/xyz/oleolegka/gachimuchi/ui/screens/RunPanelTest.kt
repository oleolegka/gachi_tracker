package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.RunState
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutStep
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.components.TimerActions
import xyz.oleolegka.gachimuchi.ui.components.TimerUiState

/**
 * The panel of a run in progress — the whole of the conductor screen and the top of the
 * programs tab.
 *
 * ── Why every case here is PAUSED ───────────────────────────────────────────────
 * A running step is measured against `SystemClock.elapsedRealtime()`, which this harness
 * does not own: the number on screen would depend on how long the composition took. A
 * paused run carries what is left of its step in its own state, so everything below is a
 * fact about the panel rather than about the machine it ran on. The countdown itself is
 * tested where it lives (`timer/TimerControllerTest.kt`).
 *
 * What this cannot say is anything about SIZE or POSITION: that "Stop" no longer sits 4 dp
 * from "Pause" is a layout fact, and nothing here rasterises a pixel. What it does pin is
 * the wording and the wiring, which is where the two of them were confused in the first
 * place — one row, two buttons, one label apiece.
 */
class RunPanelTest : ScreenTest() {

    private var paused = 0
    private var resumed = 0
    private var stopped = 0
    private var skipped = 0
    private var went = 0
    private var nudged = 0

    private val actions = TimerActions(
        enable = {},
        pause = { paused++ },
        resume = { resumed++ },
        skip = { skipped++ },
        previous = { went++ },
        nudge = { nudged++ },
        stop = { stopped++ },
    )

    /** Two steps of a set of hangs: the third hang of six, in the second set of four. */
    private val hang = WorkoutStep(
        kind = StepKind.WORK,
        name = "Hang",
        durationSec = 7,
        groupRepeat = 2,
        groupRepeats = 4,
        blockRepeat = 3,
        blockRepeats = 6,
    )
    private val rest = hang.copy(kind = StepKind.REST, name = "Rest", durationSec = 3)

    private fun panel(
        steps: List<WorkoutStep> = listOf(hang, rest),
        index: Int = 0,
        running: Boolean = false,
    ) {
        val state = TimerUiState(
            enabled = true,
            run = RunSnapshot(
                programId = 1,
                programName = "Hangs 20 mm protocol",
                steps = steps,
                state = RunState(
                    stepIndex = index,
                    running = running,
                    // far enough ahead that no plausible composition time runs it out; the
                    // number it produces is never asserted on, only the buttons around it
                    stepEndAtMs = if (running) Long.MAX_VALUE / 4 else 0,
                    pausedLeftMs = if (running) 0 else 5_000,
                ),
                bootRef = 0,
            ),
            settings = TimerSettings(),
            speechAvailable = false,
            restSec = 120,
            restSource = "the default",
        )
        screen { RunPanel(state = state, actions = actions) }
    }

    /**
     * A pause is a state of the SCREEN, and it used to be written onto the exercise: the step
     * name was printed as "Hang - paused", so the thing being trained changed its name every
     * time the run was interrupted.
     */
    @Test
    fun `a pause is a label of its own and leaves the step's name alone`() {
        panel()

        compose.onNodeWithText("PAUSED").assertExists()
        compose.onNodeWithText("Hang").assertExists()
        compose.onNodeWithText("Hang - paused").assertDoesNotExist()
    }

    /** "3 of 6" and "set 2 of 4" used to be joined by two literal spaces. */
    @Test
    fun `the position inside the block and the set is one line, on the app's separator`() {
        panel()

        compose.onNodeWithText("3 of 6 · set 2 of 4").assertExists()
    }

    /**
     * "What is next" and "how long is left" were one string joined by five spaces — a layout
     * typed into a sentence, and two facts presented as one.
     */
    @Test
    fun `what is next and what is left are two labelled facts`() {
        panel()

        compose.onNodeWithText("NEXT").assertExists()
        compose.onNodeWithText("Rest 0:03").assertExists()
        compose.onNodeWithText("LEFT").assertExists()
        compose.onNodeWithText("in the program", substring = true).assertExists()
    }

    /**
     * The last step used to leave the line empty, which says nothing about WHY it is empty
     * (SYSTEM.md, rule 6). The cost is a line of text at the moment somebody is hanging off a
     * fingerboard, and it is the deliberate trade.
     */
    @Test
    fun `the last step of a run says that nothing follows it`() {
        panel(index = 1)

        compose.onNodeWithText("nothing - this is the last step").assertExists()
    }

    /**
     * Pausing and stopping are one press apart in frequency and a whole set apart in
     * consequence; they used to be two halves of one row, 4 dp between them.
     */
    @Test
    fun `pausing and stopping are two separate buttons, each wired to its own action`() {
        panel()

        compose.onNodeWithText("Resume").performClick()
        settle()
        compose.onNodeWithText("Stop the set").performClick()
        settle()

        assertEquals(1, resumed)
        assertEquals(1, stopped)
        assertEquals("pausing a paused run is not on offer", 0, paused)
    }

    @Test
    fun `a running step offers the pause, not the resume`() {
        panel(running = true)

        compose.onNodeWithText("Pause").assertExists()
        compose.onNodeWithText("Resume").assertDoesNotExist()
        compose.onNodeWithText("PAUSED").assertDoesNotExist()
    }

    /** A run of one step has nowhere to go back to and nothing to skip to. */
    @Test
    fun `a run of a single step keeps only the two nudges`() {
        panel(steps = listOf(hang))

        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithText("-30").assertExists()
        compose.onNodeWithText("+30").assertExists()
        // and no "left in the program" either: the step IS the program
        compose.onNodeWithText("LEFT").assertDoesNotExist()
    }
}
