package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState

/**
 * The chip row of an entry form, ON A 360 dp PHONE, which is the only width at which the defect
 * this class exists for is visible.
 *
 * "Own body weight" + "Warm-up" + "Not completed" in a row that does not wrap want some 344-367
 * dp of the 336 a 360 dp screen leaves inside the card. What runs off the edge is the LAST chip,
 * "Not completed" — the one that decides whether the set counts towards a record. At 411 dp all
 * three fit, which is why the whole suite, running at 411, never saw it (SYSTEM.md rule 8: a
 * screen is checked at 360, and what does not fit is a defect rather than a detail).
 *
 * This is one of the few things a Robolectric screen test CAN measure honestly: the layout is
 * real, so the bounds are real. What it still cannot do is judge the typeface — the numbers
 * above were estimated from an average glyph width, and a device with a larger system font makes
 * the overflow worse, never better. So the assertion is not "the row is 232 dp wide" but "no chip
 * ends past the edge of the screen", which stays true whatever the font does.
 */
@Config(sdk = [34], qualifiers = "w360dp-h891dp-xhdpi")
class EntryChipsWrapTest : ScreenTest() {

    private val pullUp = ExerciseRef(id = 1, name = "Pull-up", form = ExerciseForm.STRENGTH)

    private val oneArmHang = ExerciseRef(
        id = 2,
        name = "One-arm hang",
        form = ExerciseForm.HOLD,
        oneSided = true,
    )

    /** The card's own inset, so the form is measured inside the width it really gets. */
    private fun form(content: @androidx.compose.runtime.Composable () -> Unit) {
        screen {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { content() }
        }
    }

    /**
     * Both halves matter. `assertIsDisplayed` is what actually catches the live defect — a chip
     * pushed off the right-hand edge is clipped and stops being displayed — and the bounds check
     * is what would catch the near miss, a chip that ends exactly ON the edge and is technically
     * still drawn. Checked against a broken build, in that order: with the row not wrapping, the
     * strength form fails on the first of the two.
     */
    private fun assertInsideTheScreen(text: String) {
        compose.onNodeWithText(text).assertIsDisplayed()
        val screenWidth = compose.onRoot().getUnclippedBoundsInRoot().right
        val right = compose.onNodeWithText(text).getUnclippedBoundsInRoot().right
        assertTrue(
            "\"$text\" ends at $right, past the $screenWidth edge of a 360 dp screen",
            right <= screenWidth,
        )
    }

    @Test
    fun `all three chips of a strength set are on the screen at 360 dp`() {
        form {
            StrengthEntry(
                state = UiState(),
                exercise = pullUp,
                opDate = "2026-08-11",
                onAddSet = {},
            )
        }

        assertInsideTheScreen("Own body weight")
        assertInsideTheScreen("Warm-up")
        assertInsideTheScreen("Not completed")
    }

    /**
     * The longest row this app has: a one-sided hold asks for the side in the same row as the
     * two flags, so there are four chips rather than three.
     *
     * Worth saying that this one does NOT currently fail without the wrap: with the row forced
     * onto one line the four short labels still land inside 360 dp in Robolectric's font. So it
     * is a guard against the next chip added here rather than evidence of a defect fixed — the
     * defect proved by a broken build is the strength form's, above.
     */
    @Test
    fun `the four chips of a one-sided hold are on the screen at 360 dp`() {
        form {
            HoldEntry(
                state = UiState(),
                exercise = oneArmHang,
                opDate = "2026-08-11",
                onAddSet = {},
            )
        }

        assertInsideTheScreen("Left")
        assertInsideTheScreen("Right")
        assertInsideTheScreen("Warm-up")
        assertInsideTheScreen("Not completed")
    }
}
