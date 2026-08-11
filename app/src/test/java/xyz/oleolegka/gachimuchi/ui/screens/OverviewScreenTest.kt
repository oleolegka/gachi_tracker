package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.exerciseRef
import java.time.LocalDate

/**
 * The tab's other job: working on the catalog itself, not just looking at the doors it has
 * already earned a tile on.
 *
 * ── Why this needed a test at all ────────────────────────────────────────────────
 * The button opens `ExercisePickerSheet` — already covered as a picker by
 * `ui/components/SlotEditorTest.kt` — but wired here for a purpose neither of that sheet's
 * other two callers has: picking opens the exercise's own page instead of logging or planning
 * it, and creating reaches `rememberExerciseEditor()` (see `FormDetailScreenTest.kt`'s note on
 * why that talks to the real, process-wide database) rather than the ViewModel, specifically so
 * that adding a row never touches an active exercise.
 *
 * ── What this does NOT check ─────────────────────────────────────────────────────
 * The write itself, same scope `FormDetailScreenTest` draws around `rememberExerciseEditor()`'s
 * other actions: `ensureExercise` is `data/ExerciseCatalogTest.kt`'s job, where there is a
 * database to check it against without racing the test dispatcher that a screen test's
 * `runBlocking` read-back does. Here the question is only whether the button reaches the create
 * form and the form closes the sheet without a next step to log or plan.
 *
 * Measured on a wide, tall window: wide for the reason [ScreenTest] gives (a text field inside
 * the sheet's create form never settles at phone width), tall so the button below the hero and
 * the heatmap is on screen without a scroll this harness cannot animate through.
 */
@Config(sdk = [34], qualifiers = "w600dp-h1600dp-xhdpi")
class OverviewScreenTest : ScreenTest() {

    private val today = LocalDate.parse("2026-08-10")

    private fun openButton() {
        compose.onNodeWithText("Manage the exercise catalog").performClick()
        settle()
        settle()
    }

    @Test
    fun `the catalog button opens the picker sheet, browsing what is already there`() {
        screen {
            OverviewScreen(
                state = UiState(exercises = listOf(exerciseEntity(1, "Bench press")), loading = false),
                today = today,
                onOpenForm = {},
            )
        }

        openButton()

        compose.onNodeWithText("Exercise catalog").assertExists()
        compose.onNodeWithText("Bench press").assertExists()
    }

    @Test
    fun `picking an exercise from the browser opens its own page instead of logging it`() {
        var opened: Long? = null
        screen {
            OverviewScreen(
                state = UiState(exercises = listOf(exerciseEntity(1, "Bench press")), loading = false),
                today = today,
                onOpenForm = { opened = it },
            )
        }

        openButton()
        compose.onNodeWithText("Bench press").performClick()
        settle()

        assertEquals(1L, opened)
        // the sheet closed behind the navigation, rather than staying up over the new page
        compose.onNodeWithText("Exercise catalog").assertDoesNotExist()
    }

    /**
     * The whole point of the button: a row can be added without any workout or plan behind it.
     * `ensureExercise` itself is not re-proven here — see the class note — only that the form
     * is reachable, takes a name, and the sheet closes on its own rather than asking what to do
     * with the new row next.
     */
    @Test
    fun `creating an exercise from the browser closes the sheet with nothing left to log or plan`() {
        screen {
            OverviewScreen(
                state = UiState(exercises = emptyList(), loading = false),
                today = today,
                onOpenForm = {},
            )
        }

        openButton()
        // padded with spaces around the icon, same as every other "create" button in the picker
        compose.onNodeWithText("Create your first exercise", substring = true).performClick()
        settle()

        compose.onNodeWithText("Name").performTextInput("Deadlift")
        settle()
        compose.onNodeWithText("Add to the catalog").performClick()
        settle()

        compose.onNodeWithText("Exercise catalog").assertDoesNotExist()
    }

    // --- the hero's meta line ----------------------------------------------------------

    /**
     * A week holding only a session that wrote nothing down — the owner logs climbing on rock
     * that way. The hero counts it (it is a day of training), and the phrase counting ENTRIES
     * has nothing to say about it, so the phrase goes rather than the count being stretched to
     * cover a set nobody wrote.
     */
    @Test
    fun `the entry count disappears from the hero when nothing was written down`() {
        val journal = Journal()
        journal.startWorkout("2026-08-08")

        screen {
            OverviewScreen(
                state = UiState(events = journal.events, loading = false),
                today = today,
                onOpenForm = {},
            )
        }

        // the whole phrase, separator included - not "0 entries" in a quieter colour
        compose.onNodeWithText("entries", substring = true).assertDoesNotExist()
        compose.onNodeWithText("1 more than the week before").assertExists()
        // and the session itself is still counted, which is the reason the line looked odd
        compose.onNodeWithText("days with training").assertExists()
    }

    @Test
    fun `the entry count is untouched when something was written down`() {
        val journal = Journal()
        journal.strengthSet(exerciseRef(2, "Overhead press"), "2026-08-08")

        screen {
            OverviewScreen(
                state = UiState(
                    events = journal.events,
                    exercises = listOf(exerciseEntity(2, "Overhead press")),
                    loading = false,
                ),
                today = today,
                onOpenForm = {},
            )
        }

        compose.onNodeWithText("1 entry - 1 more than the week before").assertExists()
    }

    @Test
    fun `the create button says the row is only added, not logged`() {
        screen {
            OverviewScreen(
                state = UiState(exercises = emptyList(), loading = false),
                today = today,
                onOpenForm = {},
            )
        }

        openButton()
        compose.onNodeWithText("Create your first exercise", substring = true).performClick()
        settle()

        compose.onNodeWithText("Add to the catalog").assertExists()
        compose.onNodeWithText("Create and use").assertDoesNotExist()
    }
}
