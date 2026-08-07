package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Test
import xyz.oleolegka.gachimuchi.BuildConfig
import xyz.oleolegka.gachimuchi.ui.ScreenTest

/**
 * The settings tab, which is the celebration pictures and nothing else yet.
 *
 * Not much here, and what there is has no arguable behaviour: it reads a store and draws
 * three radio rows. What is worth pinning is that the screen comes up at all — it reaches
 * for a process-wide gallery and for the system picture picker, and either of those failing
 * would take the tab down on a device without ever failing to compile.
 *
 * ── Which mode is on is now a fact the tree carries ─────────────────────────────
 * The rows used to be a `RadioButton(onClick = null)` inside a `Modifier.clickable` Row,
 * which paints the choice and publishes nothing: no test and no screen reader could say
 * which mode was picked. They are `Modifier.selectable` with a radio role now, so the state
 * is in semantics and [modeIsSelected] can read it.
 *
 * ── Why the test chooses a mode instead of trusting the default ─────────────────
 * The gallery is a process-wide singleton and the mode it holds is written through to
 * SharedPreferences, so a mode chosen by one test method is still chosen when the next one
 * composes the screen. Asserting on the store's default would therefore pass or fail on the
 * order the methods happen to run in. Each assertion below follows a click of its own.
 */
class SettingsScreenTest : ScreenTest() {

    @Test
    fun `the tab comes up with the three celebration modes and what each one means`() {
        screen { SettingsScreen() }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Celebration").assertIsDisplayed()

        compose.onNodeWithText("On every set").assertIsDisplayed()
        compose.onNodeWithText("A picture each time a set is written down.").assertIsDisplayed()
        compose.onNodeWithText("On records only").assertIsDisplayed()
        compose.onNodeWithText("Only when the set beat everything before it.").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("The pictures stay, nothing is shown.").assertIsDisplayed()
    }

    @Test
    fun `an empty gallery says so, and the way to fill it is a button that is wired up`() {
        screen { SettingsScreen() }

        // the tab is a lazy list, so the lower rows have to be scrolled to before they exist
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add pictures"))
        compose.onNodeWithText("Add pictures").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("none yet").assertIsDisplayed()
        compose.onNodeWithText(
            "No pictures yet. Pictures are chosen with the system picker, and the app " +
                "keeps its own copy of each one — moving or deleting the original later " +
                "changes nothing here. Until there is at least one, nothing is ever shown."
        ).assertExists()

        // choosing a mode must not throw: the row writes through to the process-wide store,
        // which is a real SharedPreferences write even under Robolectric
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("On every set"))
        compose.onNodeWithText("On every set").assertHasClickAction().performClick()
    }

    /**
     * Choosing a mode marks that row as chosen and unmarks the others — which is the whole of
     * what a radio group has to say about itself, and what a screen reader reads out.
     *
     * The row is asserted rather than the `RadioButton` inside it, because the button is
     * `onClick = null` on purpose: the row is the target, so the row is what carries the
     * state. The chips of Compose semantics involved (a merged node, a role, a selected flag)
     * are exactly the ones a talkback user hears.
     */
    @Test
    fun `the chosen mode is published as chosen, and the other two as not`() {
        screen { SettingsScreen() }

        // the click writes to a flow the screen collects, and with the frame clock held still
        // the recomposition it causes has not been drawn when the next line reads the tree
        modeRow("Off").performClick()
        settle()

        modeIsSelected("Off").assertIsSelected()
        modeIsSelected("On every set").assertIsNotSelected()
        modeIsSelected("On records only").assertIsNotSelected()

        // and the mark moves rather than accumulating
        modeRow("On records only").performClick()
        settle()

        modeIsSelected("On records only").assertIsSelected()
        modeIsSelected("Off").assertIsNotSelected()
    }

    /**
     * The installed version is on the screen, because there is nowhere else to read it: the
     * app is not on a store and Obtainium does not say what it put there.
     *
     * Asserted against [BuildConfig] rather than against "0.4.1 (5)" — a test that has to be
     * edited on every release is a test that will be edited without being read, and the
     * version bump itself is not what is being checked here. What IS checked is that the two
     * numbers reach the screen at all, which stops working the moment somebody drops
     * `buildConfig = true` from the module.
     */
    @Test
    fun `the version installed is shown, name and code`() {
        screen { SettingsScreen() }

        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(version))
        compose.onNodeWithText(version).assertIsDisplayed()
    }

    /** The row carrying [title], brought into the lazy list's window first. */
    private fun modeRow(title: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        return compose.onNodeWithText(title)
    }

    /**
     * The row as the tree publishes it: a node with a radio ROLE, which is the row rather
     * than the text inside it. Matching on the role is what makes this a check of the
     * semantics and not of the wording.
     */
    private fun modeIsSelected(title: String) =
        compose.onNode(hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
}
