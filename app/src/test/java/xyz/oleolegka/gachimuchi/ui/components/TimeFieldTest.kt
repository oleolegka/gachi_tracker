package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
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

    private fun tap(label: String) {
        compose.onNode(hasClickAction() and hasText(label)).performClick()
        settle()
    }

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
        tap("+10s")

        field().assertTextContains("0:55")
    }

    /** The bug from the phone, 2026-08-11: every bump had a "+" and none of them had a "-". */
    @Test
    fun `every bump has a minus of its own that takes those seconds away`() {
        screen {
            var value by remember { mutableStateOf("2:00") }
            TimeField(
                label = "Rest, mm:ss",
                value = value,
                onValueChange = { value = it },
                bumpsSec = listOf(10, 30),
            )
        }

        tap("-30s")
        field().assertTextContains("1:30")
        tap("-10s")
        field().assertTextContains("1:20")
    }

    @Test
    fun `a minus stops at the floor instead of going negative`() {
        screen {
            var value by remember { mutableStateOf("0:20") }
            TimeField(
                label = "Rest, mm:ss",
                value = value,
                onValueChange = { value = it },
                bumpsSec = listOf(30),
                minSec = 1,
            )
        }

        tap("-30s")
        field().assertTextContains("0:01")
        tap("-30s")
        field().assertTextContains("0:01")
    }

    // --- typing into it, which is where the reported bug was ---------------------------------

    /**
     * THE REPORTED BUG, from a phone on 2026-08-14: "когда сама пишу время, вводит символы в
     * рандомное, а не желаемое, место", with a screenshot of "000:50" on screen.
     *
     * The tap is the whole setup: it leaves the caret in front of the value, and the field used
     * to type from there, so each digit went in at the FRONT of a register that fills from the
     * right. Here the caret is put at the very start explicitly, which is the worst case of what
     * a tap can do, and the field has to come out the same either way.
     */
    @Test
    fun `a digit typed with the caret parked at the front still lands at the end`() {
        screen {
            var value by remember { mutableStateOf("0:50") }
            TimeField(
                label = "Rest, mm:ss",
                value = value,
                onValueChange = { value = it },
                bumpsSec = listOf(10),
            )
        }

        field().performTextInputSelection(TextRange(0))
        field().performTextInput("3")
        settle()

        // 0:50 with a 3 shifted in from the right — NOT "30:50", and never "000:50"
        field().assertTextContains("5:03")
    }

    /**
     * Digit by digit is how a phone is really typed on, and each one has to be the ones of
     * seconds with everything already there shifting left. This is the sequence that broke
     * under a caret rebuilt from the digit count: "1", "3", "0" landed on 3:01.
     */
    @Test
    fun `digits shift in from the right as they are typed`() {
        screen {
            var value by remember { mutableStateOf("") }
            TimeField(
                label = "Rest, mm:ss",
                value = value,
                onValueChange = { value = it },
                bumpsSec = listOf(10),
            )
        }

        field().performTextInput("1")
        settle()
        field().assertTextContains("0:01")
        field().performTextInput("3")
        settle()
        field().assertTextContains("0:13")
        field().performTextInput("0")
        settle()
        field().assertTextContains("1:30")
    }

    /**
     * And back out again. A register that keeps its leading zeros jams one keystroke short of
     * empty — "0:03" backspaces to "00", which formats straight back to "0:00" for ever — so
     * this walks the whole way down to nothing.
     */
    @Test
    fun `backspacing takes the digits away in the order they arrived`() {
        screen {
            var value by remember { mutableStateOf("1:30") }
            TimeField(
                label = "Rest, mm:ss",
                value = value,
                onValueChange = { value = it },
                bumpsSec = listOf(10),
            )
        }

        field().performTextInputSelection(TextRange(4))
        repeat(3) {
            field().performKeyInput { pressKey(Key.Backspace) }
            settle()
        }

        field().assertTextEquals("")
    }

    /** With no floor named, zero is where it stops — never a negative length of time. */
    @Test
    fun `the default floor is zero`() {
        screen {
            var value by remember { mutableStateOf("0:05") }
            TimeField(
                label = "Rest, mm:ss",
                value = value,
                onValueChange = { value = it },
                bumpsSec = listOf(10),
            )
        }

        tap("-10s")
        field().assertTextContains("0:00")
    }
}
