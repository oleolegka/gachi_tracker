package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
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

    /**
     * `rememberExerciseEditor()` reaches for the PROCESS-WIDE database directly (see its own
     * KDoc: a documented deviation from the callback architecture the rest of the app uses), so
     * a screen test that wants the correction dialog to open on real values has to put them in
     * THAT database, not only in the [UiState] this screen is handed. Written through the real
     * repositories rather than by hand, so the row is exactly what a live app would have built —
     * `ensureExercise` is what folds "Hangs"'s protocol into a library program in the first
     * place, the same as it would on a phone.
     */
    private val realDb by lazy { AppDatabase.get(ApplicationProvider.getApplicationContext()) }

    private val hangs: ExerciseEntity by lazy {
        runBlocking {
            val repo = ActivityRepository(realDb)
            val id = repo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
            repo.exercise(id)!!
        }
    }

    private val hangsProgram: WorkoutProgram by lazy {
        runBlocking { ProgramRepository(realDb).programById(hangs.protocolProgramId!!)!! }
    }

    private fun detail(exercise: ExerciseEntity = hangs) {
        screen {
            FormDetailScreen(
                state = UiState(
                    exercises = listOf(exercise),
                    programsById = mapOf(hangs.protocolProgramId!! to hangsProgram),
                    loading = false,
                ),
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
     * The dialog opens on what the exercise IS: the name in an editable field, the protocol
     * as READ TEXT — it is a fact about the exercise now, not a field, because the protocol
     * cannot be changed here any more (see `ui/components/ExerciseEditor.kt`'s
     * `EditExerciseDialog`).
     *
     * The protocol is asserted as "7 : 3" — work and rest together, as the fixed fact is
     * shown, not as two separate typeable numbers the way it used to be.
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
        compose.onNodeWithText("7 : 3").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    /**
     * The caveat is on the screen, not only in a KDoc: the protocol is fixed, and an exercise
     * genuinely moved to another protocol is a different exercise, created as one.
     */
    @Test
    fun `the edit dialog says the protocol is fixed and why`() {
        detail()
        openMenu()

        compose.onNodeWithText("Edit exercise").performClick()
        settle()

        compose.onNodeWithText("Fixed.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("create it as a new", substring = true).assertIsDisplayed()
        // and the form is stated as the thing that cannot move either
        compose.onNodeWithText("The form stays holds", substring = true).assertIsDisplayed()
    }
}
