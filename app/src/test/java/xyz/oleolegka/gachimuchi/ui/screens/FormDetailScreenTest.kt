package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import java.time.LocalDate

/**
 * The detail screen's one action: correcting the exercise it is about, and taking it out of
 * the pickers.
 *
 * ── Why the screen needed a test at the moment it got a menu ────────────────────
 * Everything else here is charts, and the charts are pinned by `domain/AnalyticsTest.kt`
 * where there is arithmetic to check. The menu is different: it is the only way in the whole
 * app to correct a catalog row, it reaches for the process-wide database while the screen
 * composes, and either of those failing would take the screen down on a device without ever
 * failing to compile.
 *
 * What this does NOT check is the write itself — that is `data/ExerciseCatalogTest.kt`, where
 * there is a database to check it against. Here the question is only whether the way to it
 * exists, opens, and says what it is going to do.
 *
 * Measured on a wide window for the reason [ScreenTest] gives: a Material text field inside a
 * dialog never settles at phone width under Robolectric. The dialog's fit on a real phone is
 * therefore not proven here either.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class FormDetailScreenTest : ScreenTest() {

    private val today = LocalDate.parse("2026-08-07")

    private val hangs = ExerciseEntity(
        id = 1,
        name = "Hangs",
        form = ExerciseForm.HOLD.code,
        createdAt = "2026-08-01T10:00:00",
        edgeMm = 20.0,
        protocolWorkSec = 7.0,
        protocolRestSec = 3.0,
    )

    private fun detail(exercise: ExerciseEntity = hangs) {
        screen {
            FormDetailScreen(
                state = UiState(exercises = listOf(exercise), loading = false),
                exerciseId = exercise.id,
                today = today,
                onClose = {},
            )
        }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription("More").performClick()
        settle()
    }

    @Test
    fun `the screen offers correcting the exercise and hiding it`() {
        detail()
        openMenu()

        compose.onNodeWithText("Edit exercise").assertIsDisplayed()
        compose.onNodeWithText("Hide from the picker").assertIsDisplayed()
    }

    /** A hidden exercise is reached from the overview, and this is where it is brought back. */
    @Test
    fun `an exercise that is already hidden is offered the way back instead`() {
        detail(hangs.copy(hidden = true))
        openMenu()

        compose.onNodeWithText("Show in the picker").assertIsDisplayed()
        compose.onNodeWithText("Hide from the picker").assertDoesNotExist()
    }

    /**
     * The dialog opens on what the exercise IS, not on empty fields: an edit that starts blank
     * is an edit that quietly clears the edge of anybody who only meant to fix a typo.
     *
     * The numbers are asserted as "20" rather than "20.0" on purpose — that is how they were
     * typed, and a field that re-renders them with a decimal point reads as the app having
     * changed something.
     */
    @Test
    fun `the edit dialog opens on the values the exercise already has`() {
        detail()
        openMenu()

        compose.onNodeWithText("Edit exercise").performClick()
        settle()

        // twice: the heading the screen already had, and the field the dialog opened with
        compose.onAllNodesWithText("Hangs").assertCountEquals(2)
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithText("20").assertIsDisplayed()
        compose.onNodeWithText("7").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    /**
     * The caveat is on the screen, not only in a KDoc: correcting the catalog does not
     * rewrite what the sets say they were performed at, and an exercise genuinely moved to
     * another edge is a different exercise.
     */
    @Test
    fun `the edit dialog says what the correction does to the history`() {
        detail()
        openMenu()

        compose.onNodeWithText("Edit exercise").performClick()
        settle()

        compose.onNodeWithText("still carry the edge", substring = true).assertIsDisplayed()
        compose.onNodeWithText("is a different exercise", substring = true).assertIsDisplayed()
        // and the form is stated as the thing that cannot move
        compose.onNodeWithText("The form stays holds", substring = true).assertIsDisplayed()
    }
}
