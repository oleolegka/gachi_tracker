package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.screens.FormDetailScreen
import java.time.LocalDate

/**
 * The edit dialog's own bug: the two flags ("one side at a time", bodyweight share) were
 * written ONLY on a successful name save (`EditExerciseDialog`'s own `onSave` in
 * `rememberExerciseEditor`), while the dialog itself closed the instant Save was tapped,
 * whatever `repo.editExercise` was about to say. A name refused as taken therefore looked
 * identical, on screen, to one that went through - the dialog was already gone, the flags
 * had silently not been written, and the "Not saved" alert that followed talked about the
 * name alone. Owner, 2026-08-10: "ну конечно же нужно показывать то, что есть" - the fix is
 * that the dialog now only closes once the write it is reporting on actually happened.
 *
 * ── Why this goes through [FormDetailScreen] rather than `rememberExerciseEditor` alone ────
 * `rememberExerciseEditor()` reaches for the process-wide database directly - a documented
 * deviation in its own KDoc - so there is no callback to hand this a fake repository through.
 * `FormDetailScreenTest` already established the pattern of driving it through a real screen
 * and a real (Robolectric) database; this file follows the same one rather than inventing a
 * second way to reach the same dialog.
 *
 * ── Why Save is followed by [waitFor], not a single [settle] ────────────────────────────
 * Save runs a real suspend `ActivityRepository.editExercise` against Room, which resumes on
 * a genuine background thread - not on the frozen compose frame clock [settle] winds, and a
 * single fixed [settle] is a race against however long that thread takes. [waitFor] keeps
 * winding the clock forward in small steps - each one gives the coroutine a chance to post
 * its resumption onto the (paused-by-default, Robolectric) main looper and be picked up by
 * the next recomposition - until the text it is waiting for actually appears, or a generous
 * real-time budget runs out and it fails loudly instead of hanging forever.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class ExerciseEditorTest : ScreenTest() {

    private val today = LocalDate.parse("2026-08-10")
    private val realDb by lazy { AppDatabase.get(ApplicationProvider.getApplicationContext()) }
    private val repo by lazy { ActivityRepository(realDb) }

    private fun exercise(name: String): ExerciseEntity = runBlocking {
        val id = repo.ensureExercise(name, ExerciseForm.STRENGTH)
        repo.exercise(id)!!
    }

    private fun detail(target: ExerciseEntity) {
        screen {
            FormDetailScreen(
                state = UiState(exercises = listOf(target), loading = false),
                exerciseId = target.id,
                today = today,
                onClose = {},
            )
        }
    }

    private fun openEditor() {
        compose.onNodeWithContentDescription("More").performClick()
        settle()
        compose.onNodeWithText("Edit exercise").performClick()
        settle()
    }

    private fun exists(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!exists(text)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for \"$text\" to appear" }
            settle(100)
        }
    }

    private fun waitGone(text: String, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (exists(text)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for \"$text\" to disappear" }
            settle(100)
        }
    }

    @Test
    fun `a name refused as taken leaves the dialog open with the toggle exactly as set`() {
        exercise("Squat") // the name the rename below is going to collide with
        val benchPress = exercise("Bench press")
        detail(benchPress)
        openEditor()

        compose.onNodeWithText("One side at a time").performClick()
        settle()
        compose.onNodeWithText("Name").performTextReplacement("Squat")
        settle()
        compose.onNodeWithText("Save").performClick()
        waitFor("Not saved")

        compose.onNodeWithText("Not saved").assertIsDisplayed()
        compose.onNodeWithText("already has that name", substring = true).assertIsDisplayed()
        // the dialog under it is still open, not silently closed - the fix under test
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("One side at a time").assertIsSelected()
    }

    @Test
    fun `dismissing the not-saved alert still shows the toggle on, ready to retry`() {
        exercise("Squat")
        val benchPress = exercise("Bench press")
        detail(benchPress)
        openEditor()

        compose.onNodeWithText("One side at a time").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Squat")
        settle()
        compose.onNodeWithText("Save").performClick()
        waitFor("Not saved")
        compose.onNodeWithText("OK").performClick()
        settle()

        compose.onNodeWithText("One side at a time").assertIsSelected()
        compose.onNodeWithText("Name").assertIsDisplayed()
    }

    /** Nothing about a name collision escapes to the database - the flag was never written. */
    @Test
    fun `a refused save does not write the flag to the database either`() {
        exercise("Squat")
        val benchPress = exercise("Bench press")
        detail(benchPress)
        openEditor()

        compose.onNodeWithText("One side at a time").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Squat")
        settle()
        compose.onNodeWithText("Save").performClick()
        waitFor("Not saved")

        val stored = runBlocking { repo.exercise(benchPress.id)!! }
        assert(!stored.oneSided) { "a refused save must not have reached the database" }
    }

    @Test
    fun `a successful save closes the dialog and writes the toggle`() {
        val benchPress = exercise("Bench press")
        detail(benchPress)
        openEditor()

        compose.onNodeWithText("One side at a time").performClick()
        settle()
        compose.onNodeWithText("Save").performClick()
        waitGone("Save")

        compose.onNodeWithText("Not saved").assertDoesNotExist()

        val stored = runBlocking { repo.exercise(benchPress.id)!! }
        assert(stored.oneSided) { "the flag toggled before Save should have been written" }
    }
}
