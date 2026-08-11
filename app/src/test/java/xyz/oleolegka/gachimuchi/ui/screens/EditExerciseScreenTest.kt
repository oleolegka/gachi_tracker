package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.ui.ScreenTest

/**
 * Correcting an exercise, now that it is a screen.
 *
 * ── The bug this file was written for, and still guards ─────────────────────────────
 * The two flags ("one side at a time", the bodyweight share that has since gone) were
 * written ONLY on a successful name save, while the dialog closed the instant Save was
 * tapped, whatever `repo.editExercise` was about to say. A name refused as taken therefore
 * looked identical, on screen, to one that went through - the form was already gone, the
 * flag had silently not been written, and the alert that followed talked about the name
 * alone. Owner, 2026-08-10: "ну конечно же нужно показывать то, что есть".
 *
 * The first fix was "the dialog only closes once the write it is reporting on happened". On a
 * screen the same rule reads from the other side: [EditExerciseScreen] calls its `onClose`
 * on a successful write and at no other time, so a refusal LEAVES THE SCREEN WHERE IT IS,
 * with everything typed still on it. That is what the first two tests below assert.
 *
 * ── And the second half, which the first one only postponed ────────────────────────
 * A screen that stays open still loses the flip the moment the rename is given up on - and
 * giving up on it is the whole of the reported case: "clicked, was told the NAME is taken,
 * went back, and the switch is where it was". So the switch is written WHATEVER the name did
 * (backlog.md §14.7 point 10): the refusal is about identity, name plus protocol, and which
 * limb an exercise is trained with is not part of that. The assertion that used to say a
 * refused save writes nothing now says the opposite, deliberately.
 *
 * ── Why it drives the real database ────────────────────────────────────────────────
 * [EditExerciseScreen] reaches for the process-wide database directly - a documented
 * deviation in its own KDoc - so there is no callback to hand this a fake repository
 * through. It runs against the real (Robolectric, in-memory) one instead, which is what the
 * dialog's own tests did before it.
 *
 * ── Why Save is followed by [waitFor], not a single [settle] ────────────────────────
 * Save runs a real suspend `ActivityRepository.editExercise` against Room, which resumes on
 * a genuine background thread - not on the frozen compose frame clock [settle] winds, and a
 * single fixed [settle] is a race against however long that thread takes. [waitFor] keeps
 * winding the clock forward in small steps until the text it is waiting for appears, or a
 * generous real-time budget runs out and it fails loudly instead of hanging forever.
 *
 * ── What this does NOT cover ───────────────────────────────────────────────────────
 * The picture. Camera and gallery both go through an `ActivityResultLauncher`, which needs
 * an Activity result this suite has no way to deliver, so the plate, the two buttons and
 * "Remove" are exercised by nothing here - the same gap the dialog left before it.
 */
class EditExerciseScreenTest : ScreenTest() {

    private companion object {
        var counter = 0
    }

    private val realDb by lazy { AppDatabase.get(ApplicationProvider.getApplicationContext()) }
    private val repo by lazy { ActivityRepository(realDb) }

    private var closed = false

    /*
     * A FRESH NAME PER TEST. `AppDatabase.get` is a process-wide singleton and Robolectric
     * hands the whole class one sandbox, so rows written by one test are still there for the
     * next one - and `ensureExercise` is find-or-create, so a second test asking for "Bench
     * press" gets the row the first test already flipped the flag on. Two of these tests
     * assert on the flag's starting state, and they failed exactly that way before this.
     */
    private fun unique(name: String) = "$name ${counter++}"

    private fun exercise(name: String, form: ExerciseForm = ExerciseForm.STRENGTH): ExerciseEntity =
        runBlocking {
            val id = repo.ensureExercise(name, form)
            repo.exercise(id)!!
        }

    private fun hold(name: String): ExerciseEntity = runBlocking {
        val id = repo.ensureExercise(name, ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0)
        repo.exercise(id)!!
    }

    private fun editing(target: ExerciseEntity) {
        closed = false
        val program = target.protocolProgramId?.let {
            runBlocking { ProgramRepository(realDb).programById(it) }
        }
        screen {
            EditExerciseScreen(
                exercise = target,
                program = program,
                onClose = { closed = true },
            )
        }
    }

    private fun exists(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!exists(text)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for \"$text\"" }
            settle(100)
        }
    }

    private fun waitClosed(timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!closed) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the screen to close" }
            settle(100)
        }
    }

    @Test
    fun `a name refused as taken keeps the screen open with the switch exactly as set`() {
        val taken = unique("Squat") // the name the rename below is going to collide with
        exercise(taken)
        val benchPress = exercise(unique("Bench press"))
        editing(benchPress)

        compose.onNode(isToggleable()).performClick()
        settle()
        compose.onNodeWithText("Name").performTextReplacement(taken)
        settle()
        compose.onNodeWithText("Save").performClick()
        waitFor("Name is taken")

        compose.onNodeWithText("Name is taken").assertIsDisplayed()
        // and it says what to do next, which the four-line version of it never did
        compose.onNodeWithText("Pick another name", substring = true).assertIsDisplayed()
        assert(!closed) { "a refused save must not take the screen away" }
        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun `dismissing the refusal leaves everything typed still on the screen`() {
        val taken = unique("Squat")
        exercise(taken)
        val benchPress = exercise(unique("Bench press"))
        editing(benchPress)

        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Name").performTextReplacement(taken)
        settle()
        compose.onNodeWithText("Save").performClick()
        waitFor("Name is taken")
        compose.onNodeWithText("OK").performClick()
        settle()

        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Name").assertIsDisplayed()
    }

    /**
     * THE DEFECT THIS FILE IS NOW ABOUT: a name refused as a duplicate used to take the
     * switch down with it.
     *
     * This assertion is the inverse of the one that stood here before, and the inversion is
     * the fix, not a relaxation. The refusal is about IDENTITY - name plus protocol - and
     * which limb the exercise is trained with is no part of that, so it must not be refused
     * along with it. Keeping the screen open (the tests above) only postpones the loss: the
     * moment the rename is abandoned, which is exactly the reported case, the flip is gone.
     */
    @Test
    fun `a name refused as taken still writes the switch, and says so`() {
        val taken = unique("Squat")
        exercise(taken)
        val benchPress = exercise(unique("Bench press"))
        editing(benchPress)

        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Name").performTextReplacement(taken)
        settle()
        compose.onNodeWithText("Save").performClick()
        waitFor("Name is taken")

        val stored = runBlocking { repo.exercise(benchPress.id)!! }
        assert(stored.oneSided) { "the switch is not part of the name and must survive its refusal" }
        // and the dialog says which half was saved, rather than writing a column in silence
        compose.onNodeWithText("was saved anyway", substring = true).assertIsDisplayed()
    }

    /**
     * The name itself is still refused, and the OLD name is what stayed in the catalog. The
     * test above proves the switch survives; this one proves that did not turn into the
     * rename sneaking through with it.
     */
    @Test
    fun `the refused name itself is not written`() {
        val taken = unique("Squat")
        exercise(taken)
        val benchPress = exercise(unique("Bench press"))
        editing(benchPress)

        compose.onNodeWithText("Name").performTextReplacement(taken)
        settle()
        compose.onNodeWithText("Save").performClick()
        waitFor("Name is taken")

        val stored = runBlocking { repo.exercise(benchPress.id)!! }
        assert(stored.name == benchPress.name) { "the name was refused, so it must not have changed" }
        assert(!closed) { "a refused save must not take the screen away" }
    }

    /** Nothing is written when the switch was never touched: a rename is a rename. */
    @Test
    fun `a refusal with the switch untouched writes nothing and does not mention it`() {
        val taken = unique("Squat")
        exercise(taken)
        val benchPress = exercise(unique("Bench press"))
        editing(benchPress)

        compose.onNodeWithText("Name").performTextReplacement(taken)
        settle()
        compose.onNodeWithText("Save").performClick()
        waitFor("Name is taken")

        val stored = runBlocking { repo.exercise(benchPress.id)!! }
        assert(!stored.oneSided) { "an untouched switch must not be written by a refusal" }
        assert(!exists("was saved anyway", substring = true)) {
            "nothing was saved, so the refusal must not claim anything was"
        }
    }

    @Test
    fun `a successful save leaves the screen and writes the switch`() {
        val benchPress = exercise(unique("Bench press"))
        editing(benchPress)

        compose.onNode(isToggleable()).performClick()
        settle()
        compose.onNodeWithText("Save").performClick()
        waitClosed()

        val stored = runBlocking { repo.exercise(benchPress.id)!! }
        assert(stored.oneSided) { "the switch set before Save should have been written" }
    }

    /**
     * The screen opens on what the exercise IS: the name in a field, the protocol as READ
     * TEXT under a padlock. These assertions used to live in `FormDetailScreenTest`, where
     * the dialog was raised from; they follow the thing they are about.
     */
    @Test
    fun `the protocol and the form are shown as fixed facts, with one sentence saying why`() {
        editing(hold(unique("Hangs")))

        compose.onNodeWithText("FIXED").assertIsDisplayed()
        compose.onNodeWithText("Protocol").assertIsDisplayed()
        compose.onNodeWithText("7 : 3").assertIsDisplayed()
        compose.onNodeWithText("holds").assertIsDisplayed()
        // one sentence for both, where there used to be two paragraphs saying the same thing
        compose.onNodeWithText("A different protocol or form is a different exercise - make a new one.")
            .assertIsDisplayed()
    }

    /** The way out of the screen, which a dialog used to get for free. */
    @Test
    fun `the top bar carries the way out and the save`() {
        editing(exercise(unique("Bench press")))

        compose.onNodeWithText("Edit exercise").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        settle()
        assert(closed) { "the cross in the top bar has to leave the screen" }
    }
}
