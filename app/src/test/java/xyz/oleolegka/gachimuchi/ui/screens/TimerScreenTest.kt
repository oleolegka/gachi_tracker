package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.formatClock
import xyz.oleolegka.gachimuchi.domain.totalSec
import xyz.oleolegka.gachimuchi.domain.workStepCount
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.components.TimerActions
import xyz.oleolegka.gachimuchi.ui.components.TimerUiState

/**
 * The programs tab, at rest: what it says when the timer is off, when the library is empty,
 * and that the buttons are wired to the callbacks they claim.
 *
 * A live run is NOT exercised here. The countdown is driven by the process-wide controller
 * and by real time, and it is already tested where it lives (`timer/TimerControllerTest.kt`,
 * `timer/FloorControllerTest.kt`); a Compose test that advanced a fake clock through it
 * would be re-testing the conductor through a peephole.
 */
class TimerScreenTest : ScreenTest() {

    private var enabled = 0
    private var disabled = 0
    private var ran: WorkoutProgram? = null
    private var edited: WorkoutProgram? = null
    private var editRequested = 0
    private var deleted: Long? = null
    private var hiddenToggled: WorkoutProgram? = null

    private val actions = TimerActions(
        enable = { enabled++ },
        pause = {},
        resume = {},
        skip = {},
        previous = {},
        nudge = {},
        stop = {},
    )

    private val repeaters = WorkoutProgram(
        id = 1,
        name = "Hangboard repeaters 7:3",
        groups = listOf(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock(name = "Hang", workSec = 7, restSec = 3, repeats = 6)),
                repeats = 4,
                restBetweenRepeatsSec = 180,
            )
        ),
        exerciseId = 1,
    )

    /** Brings a row of the lazy list into existence; below the fold it is not composed. */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun timer(
        on: Boolean,
        programs: List<WorkoutProgram> = emptyList(),
        scheduleOwners: Map<Long, List<String>> = emptyMap(),
    ) {
        val state = TimerUiState(
            enabled = on,
            run = null,
            settings = TimerSettings(),
            speechAvailable = false,
            restSec = 120,
            restSource = "the default",
        )
        screen {
            TimerScreen(
                state = state,
                actions = actions,
                programs = programs,
                exerciseNames = mapOf(1L to "Hangs 20 mm"),
                scheduleOwners = scheduleOwners,
                onRunProgram = { ran = it },
                onEditProgram = {
                    editRequested++
                    edited = it
                },
                onDeleteProgram = { deleted = it },
                onToggleHiddenProgram = { hiddenToggled = it },
                onImportPrograms = {},
                onSettings = {},
                onEnable = { enabled++ },
                onDisable = { disabled++ },
            )
        }
    }

    @Test
    fun `a timer that is off explains itself and offers the one button that matters`() {
        timer(on = false)

        compose.onNodeWithText("The timer is off").assertIsDisplayed()
        compose.onNodeWithText("Turn on the timer").performClick()
        assertEquals(1, enabled)
    }

    @Test
    fun `an empty program list says what a program is instead of looking like a failed load`() {
        timer(on = true)

        compose.onNodeWithText("The library is empty").assertIsDisplayed()
        compose.onNodeWithText("New program").assertIsDisplayed()
        // the list is lazy, so anything below the fold has to be scrolled to before it
        // exists at all
        scrollTo("Export all")
        compose.onNodeWithText("Import from a file").assertIsDisplayed()
        // there is nothing to export, and the button says so rather than doing nothing
        compose.onNodeWithText("Export all").assertIsNotEnabled()
    }

    @Test
    fun `the screen's own three sections are all present`() {
        timer(on = true, programs = listOf(repeaters))

        compose.onNodeWithText("Your programs").assertIsDisplayed()
        compose.onNodeWithText("The timer is off").assertDoesNotExist()
        // the settings are the last thing on the list, deliberately: they are read once and
        // then never again
        scrollTo("Timer settings")
        compose.onNodeWithText("Timer settings").assertIsDisplayed()
    }

    @Test
    fun `a saved program is drawn with what it does and what it will be logged as`() {
        timer(on = true, programs = listOf(repeaters))

        compose.onNodeWithText("Hangboard repeaters 7:3").assertIsDisplayed()
        // the arithmetic belongs to domain/Program.kt and is tested there; what is checked
        // here is that the card prints THOSE numbers, in that order, with the link to a
        // catalog exercise beside them - the link is what decides whether finishing the
        // program offers to write the sets down
        assertEquals(24, repeaters.workStepCount())
        val line = "${repeaters.workStepCount()} efforts   " +
            "${formatClock(repeaters.totalSec())} total   logs as Hangs 20 mm"
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithText("Run").assertIsEnabled()
        scrollTo("Export all")
        compose.onNodeWithText("Export all").assertIsEnabled()
    }

    @Test
    fun `the buttons on a program card each call their own action`() {
        timer(on = true, programs = listOf(repeaters))

        compose.onNodeWithText("Run").performClick()
        assertEquals(repeaters, ran)

        compose.onNodeWithText("Delete").performClick()
        assertEquals(1L, deleted)

        compose.onNodeWithText("Edit").performClick()
        assertEquals(repeaters, edited)
    }

    @Test
    fun `New program asks the editor for a program that does not exist yet`() {
        timer(on = true, programs = listOf(repeaters))

        compose.onNodeWithText("New program").performClick()

        assertEquals(1, editRequested)
        assertNull("null is what the editor is told to open blank", edited)
    }

    /*
     * The schedules (decisions §18.15). What is asserted is what the owner complained about:
     * a program he never wrote sitting in his library with nothing on it to say where it came
     * from or why it could not be edited.
     */

    private val scheduleOfHangs = WorkoutProgram(
        id = 7,
        name = "Hangs 20mm protocol",
        groups = listOf(
            ProgramGroup(
                name = "Set",
                blocks = listOf(ProgramBlock(name = "Hang", workSec = 10, restSec = 60)),
            )
        ),
        exerciseId = 1,
    )

    @Test
    fun `an exercise schedule sits under its own heading, not among the owner's programs`() {
        timer(
            on = true,
            programs = listOf(repeaters, scheduleOfHangs),
            scheduleOwners = mapOf(7L to listOf("Hangs 20 mm")),
        )

        compose.onNodeWithText("Exercise schedules").assertIsDisplayed()
        // the owner's own program keeps its place above and grows no heading of its own
        compose.onNodeWithText("Hangboard repeaters 7:3").assertIsDisplayed()
        compose.onNodeWithText("Hangs 20mm protocol").assertIsDisplayed()
    }

    @Test
    fun `the row says whose schedule it is and that it cannot be retimed`() {
        timer(
            on = true,
            programs = listOf(scheduleOfHangs),
            scheduleOwners = mapOf(7L to listOf("Hangs 20 mm")),
        )

        compose.onNodeWithText("Schedule for Hangs 20 mm - the times are fixed")
            .assertIsDisplayed()
        // and the freeze is readable before anything is opened, which is the whole point
        compose.onNodeWithText(
            "Made for one exercise and fixed once it was used: the times in these cannot be " +
                "changed, only the name. Your own programs are above.",
        ).assertIsDisplayed()
    }

    @Test
    fun `twins sharing one schedule are both named on it`() {
        timer(
            on = true,
            programs = listOf(scheduleOfHangs),
            scheduleOwners = mapOf(7L to listOf("Hangs 20 mm", "Hangs 15 mm")),
        )

        compose.onNodeWithText("Schedule for Hangs 20 mm, Hangs 15 mm - the times are fixed")
            .assertIsDisplayed()
    }

    @Test
    fun `a library with no schedules in it looks exactly as it did`() {
        timer(on = true, programs = listOf(repeaters))

        compose.onNodeWithText("Exercise schedules").assertDoesNotExist()
        compose.onNodeWithText("Hangboard repeaters 7:3").assertIsDisplayed()
    }

    @Test
    fun `a program cannot be run while the timer is switched off`() {
        timer(on = false, programs = listOf(repeaters))

        compose.onNodeWithText("Run").assertIsNotEnabled()
    }
}
