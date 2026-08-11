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
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.exerciseRef
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
    fun `the screen offers correcting the exercise, hiding it, and deleting it`() {
        detail()
        openMenu()

        compose.onNodeWithText("Edit exercise").assertIsDisplayed()
        compose.onNodeWithText("Hide from the picker").assertIsDisplayed()
        compose.onNodeWithText("Delete exercise").assertIsDisplayed()
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
        // the screen opens the sentence, so the C is capital - and this matcher is case
        // sensitive, which is what made the lower-cased version of this line fail
        compose.onNodeWithText("Create it as a new", substring = true).assertIsDisplayed()
        // and the form is stated as the thing that cannot move either
        compose.onNodeWithText("The form stays holds", substring = true).assertIsDisplayed()
    }

    // --- the records block, merged across a one-sided exercise's two hands -----------------

    /**
     * The gap this closes: `holdRecord` (domain/Records.kt) rightly keeps the left hand's
     * best apart from the right hand's — the two are years apart in strength and comparing
     * them would be dishonest — but the screen used to draw each as its own full-width card,
     * both captioned "Most weight hung". That read as two records for two different
     * exercises rather than one exercise reported per hand.
     *
     * Since the redraw (2026-08-11) the two hands are two COLUMNS of that one card rather
     * than one run-on line: "Left 10 / Right 8" set as a figure was a sentence, not a pair
     * of numbers to compare. What is asserted is unchanged in substance — one caption, both
     * values, both dates, each side named.
     */
    @Test
    fun `the two hands of a one-sided exercise share one record card, not two`() {
        val oneArm = exerciseEntity(30, "One-arm hangs", ExerciseForm.HOLD).copy(oneSided = true)
        val ref = exerciseRef(30, "One-arm hangs", ExerciseForm.HOLD)
        val journal = Journal()
        journal.holdSet(ref, "2026-08-01", addedKg = 10.0, side = HoldSide.LEFT)
        journal.holdSet(ref, "2026-07-20", addedKg = 8.0, side = HoldSide.RIGHT)

        screen {
            FormDetailScreen(
                state = UiState(events = journal.events, exercises = listOf(oneArm), loading = false),
                exerciseId = oneArm.id,
                today = today,
                onClose = {},
            )
        }

        // one row for the axis, not two - "Most weight hung" drawn once
        compose.onAllNodesWithText("Most weight hung").assertCountEquals(1)
        // one column per hand: the side, the figure and that side's own date (§12-C)
        compose.onNodeWithText("LEFT").assertIsDisplayed()
        compose.onNodeWithText("RIGHT").assertIsDisplayed()
        compose.onNodeWithText("10").assertIsDisplayed()
        compose.onNodeWithText("8").assertIsDisplayed()
        compose.onNodeWithText("1 Aug").assertIsDisplayed()
        compose.onNodeWithText("20 Jul").assertIsDisplayed()
    }

    /** The ordinary two-handed exercise is untouched: one record, one column, as ever. */
    @Test
    fun `a two-handed exercise still gets a plain single record card`() {
        val ref = exerciseRef(1, "Hangs", ExerciseForm.HOLD)
        val entity = exerciseEntity(1, "Hangs", ExerciseForm.HOLD)
        val journal = Journal()
        journal.holdSet(ref, "2026-08-01", addedKg = 12.0)

        screen {
            FormDetailScreen(
                state = UiState(events = journal.events, exercises = listOf(entity), loading = false),
                exerciseId = entity.id,
                today = today,
                onClose = {},
            )
        }

        compose.onAllNodesWithText("Most weight hung").assertCountEquals(1)
        compose.onNodeWithText("LEFT", substring = true).assertDoesNotExist()
        compose.onNodeWithText("RIGHT", substring = true).assertDoesNotExist()
    }

    // --- deleting the exercise ---------------------------------------------------------------

    /**
     * The warning names a NUMBER, not just "are you sure" — the owner's own requirement: a
     * confirmation that does not say how many entries go with it is not informative.
     */
    @Test
    fun `the delete confirmation says how many entries will disappear`() {
        val ref = exerciseRef(1, "Hangs", ExerciseForm.HOLD)
        val entity = exerciseEntity(1, "Hangs", ExerciseForm.HOLD)
        val journal = Journal()
        journal.holdSet(ref, "2026-08-01")
        journal.holdSet(ref, "2026-07-20")

        screen {
            FormDetailScreen(
                state = UiState(events = journal.events, exercises = listOf(entity), loading = false),
                exerciseId = entity.id,
                today = today,
                onClose = {},
            )
        }
        openMenu()
        compose.onNodeWithText("Delete exercise").performClick()
        settle()

        compose.onNodeWithText("Delete this exercise?").assertIsDisplayed()
        compose.onNodeWithText("2 entries go", substring = true).assertIsDisplayed()
    }

    /** Nothing recorded yet gets a different sentence, not "0 entries". */
    @Test
    fun `an exercise with nothing recorded gets a plain warning instead of a count`() {
        detail()
        openMenu()
        compose.onNodeWithText("Delete exercise").performClick()
        settle()

        compose.onNodeWithText("Nothing has been recorded under it yet", substring = true)
            .assertIsDisplayed()
    }

    /** Confirming closes the screen — there is nothing left here to look at. */
    @Test
    fun `confirming the delete closes the screen`() {
        var closed = false
        screen {
            FormDetailScreen(
                state = UiState(
                    exercises = listOf(hangs),
                    programsById = mapOf(hangs.protocolProgramId!! to hangsProgram),
                    loading = false,
                ),
                exerciseId = hangs.id,
                today = today,
                onClose = { closed = true },
            )
        }
        openMenu()
        compose.onNodeWithText("Delete exercise").performClick()
        settle()
        compose.onNodeWithText("Delete").performClick()
        settle()

        assert(closed) { "onClose was not called after confirming the delete" }
    }

    /** Dismissing the warning leaves the exercise exactly as it was, screen still open. */
    @Test
    fun `dismissing the delete confirmation keeps the exercise`() {
        detail()
        openMenu()
        compose.onNodeWithText("Delete exercise").performClick()
        settle()
        compose.onNodeWithText("Keep it").performClick()
        settle()

        compose.onNodeWithText("Delete this exercise?").assertDoesNotExist()
        // the screen is still the one for this exercise, not closed
        compose.onAllNodesWithText("Hangs").assertCountEquals(1)
    }
}
