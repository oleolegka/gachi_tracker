package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.ui.ScreenTest

/**
 * The time field on its own, away from any screen that wires it.
 *
 * It exists because a bump button that did nothing could have been broken on either side of
 * that wire, and a test through a whole screen cannot say which.
 */
@Config(sdk = [34], qualifiers = "w600dp-h1600dp-xhdpi")
class TimeFieldTest : ScreenTest() {

    private fun field() =
        compose.onNode(hasSetTextAction() and hasContentDescription("Rest, mm:ss"))

    @Test
    fun `a bump adds its seconds to what the field already holds`() {
        screen {
            var value by remember { mutableStateOf("0:45") }
            TimeField(
                label = "Rest, mm:ss",
                value = value,
                onValueChange = { value = it },
                bumpsSec = listOf(10),
            )
        }

        field().assertTextContains("0:45")
        compose.onNode(hasClickAction() and hasText("+10s")).performClick()
        settle()

        field().assertTextContains("0:55")
    }
}
