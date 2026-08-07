package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Test
import xyz.oleolegka.gachimuchi.ui.ScreenTest

/**
 * The settings tab, which is the celebration pictures and nothing else yet.
 *
 * Two tests, because there is not much here and what there is has no arguable behaviour: it
 * reads a store and draws three radio rows. What is worth pinning is that the screen comes
 * up at all — it reaches for a process-wide gallery and for the system picture picker, and
 * either of those failing would take the tab down on a device without ever failing to
 * compile.
 *
 * ── A gap, stated rather than papered over ──────────────────────────────────────
 * WHICH mode is selected cannot be asserted. The rows draw a `RadioButton(onClick = null)`
 * inside a `Modifier.clickable` Row, so the selected state is painted but never published
 * as semantics — a screen reader cannot tell which one is chosen either, and nor can a
 * test. Fixing that means moving the row to `Modifier.selectable`, which is a change to a
 * screen rather than to a test and was left out of this piece of work deliberately.
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
}
