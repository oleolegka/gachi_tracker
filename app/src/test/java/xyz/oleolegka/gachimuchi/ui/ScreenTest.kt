package xyz.oleolegka.gachimuchi.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.ui.theme.GachimuchiTheme

/**
 * The base every screen test sits on: a Compose tree, raised in the app's own theme, on the
 * JVM.
 *
 * ── Why this runs under Robolectric and not on a device ─────────────────────────
 * There is no device and there is not going to be one. `createComposeRule` normally means
 * an emulator and a second Gradle command; under [RobolectricTestRunner] it composes into
 * an Android runtime that is a jar, so screen tests are part of `./gradlew test` alongside
 * the reducer tests and cost the same nothing to run. What that buys and — more to the
 * point — what it does NOT is written out in the class comment of [DayCardListTest].
 *
 * ── The theme is not decoration here ────────────────────────────────────────────
 * [screen] wraps the content in [GachimuchiTheme] because the app's colour roles are
 * provided through it (`LocalGachiColors`), and every component in this app reads them. A
 * bare `setContent` would compose against Material's defaults, which is a different screen
 * from the one that ships — and the one defect this test suite is meant to keep out (a
 * control filled from an unset colour role) is invisible in exactly that setup.
 *
 * ── The animation clock is held still, and that is a limitation ────────────────
 * With the clock advancing by itself, a Material text field never lets the composition
 * settle: `setContent` spins until the idling strategy gives up after a minute, and it does
 * so for a bare `OutlinedTextField` on an otherwise empty screen, in or out of a dialog.
 * Freezing the clock ([settle] winds it on where a test needs an animation to finish) makes
 * every screen in this app testable. The price is that animated behaviour is not exercised
 * at all — see the "what this does not catch" note in [DayCardListTest], of which this is
 * now one more item.
 *
 * The SDK is pinned to 34 for the reason every other Robolectric test here pins it: the
 * android-all jar for 34 is the one on the machine. The window is pinned to the size of an
 * ordinary phone rather than left at Robolectric's default (a 320x470 dp handset from
 * 2010), because on that default half the content of a real screen is off the bottom and
 * the assertions would start describing a device nobody has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
abstract class ScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Raises [content] as the whole screen, in the app theme, with the clock held still. */
    protected fun screen(dark: Boolean = false, content: @Composable () -> Unit) {
        /*
         * DO NOT TURN THIS BACK ON without reading the header. It looks like a line that
         * makes tests less realistic for no reason, and the effect of removing it is not
         * "some animations go untested" — it is that every screen carrying a text field
         * stops working at all: the composition never settles, `setContent` below spins for
         * a minute, and the failure arrives as a timeout in a test that looks unrelated.
         */
        compose.mainClock.autoAdvance = false
        compose.setContent { GachimuchiTheme(darkTheme = dark) { content() } }
    }

    /**
     * Winds the frozen clock on, for the one thing that needs it: a surface that ANIMATES
     * itself into place (a bottom sheet slides up from off screen) has no bounds worth
     * asserting on until it has arrived.
     */
    protected fun settle(millis: Long = 1_000) {
        compose.mainClock.advanceTimeBy(millis)
    }
}
