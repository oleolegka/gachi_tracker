package xyz.oleolegka.gachimuchi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the back gesture leads, from every state the app can be in.
 *
 * This is the whole reason [backStep] exists as a function instead of a chain of `if`s
 * inside the composable: back used to do nothing anywhere, the app simply went to the
 * background from the middle of a screen, and the only way to find that out was to build
 * an APK and swipe on a phone.
 *
 * The two properties worth guarding are at the bottom: back always makes progress (it
 * never answers "stay where you are"), and it always terminates (following it from any
 * state reaches the exit in a bounded number of presses). A rule that fails either one
 * traps the user, and neither is obvious from reading the `when`.
 */
class NavigationTest {

    @Test
    fun `back closes the program editor first, whatever is underneath it`() {
        // the editor is opened from the timer tab and drawn over everything, so it must
        // win over the tab AND over anything that was open before it
        assertEquals(
            BackStep.CloseEditor,
            backStep(editingProgram = true, showingFormDetail = false, logging = false, tab = Tab.TIMER),
        )
        assertEquals(
            BackStep.CloseEditor,
            backStep(editingProgram = true, showingFormDetail = true, logging = true, tab = Tab.OVERVIEW),
        )
    }

    @Test
    fun `back leaves the form detail screen for the tab it was opened from`() {
        assertEquals(
            BackStep.CloseFormDetail,
            backStep(editingProgram = false, showingFormDetail = true, logging = false, tab = Tab.OVERVIEW),
        )
        // closing it only clears the mode; the tab underneath was never changed, so
        // Overview is what comes back — the same thing the arrow in its title bar does
        assertEquals(
            BackStep.CloseFormDetail,
            backStep(editingProgram = false, showingFormDetail = true, logging = true, tab = Tab.OVERVIEW),
        )
    }

    @Test
    fun `back leaves the logging screen for whichever tab opened it`() {
        // logging is reachable from Today's button and, in context, from the calendar and
        // the timer. Each of them leaves its own tab selected underneath.
        for (tab in Tab.entries) {
            assertEquals(
                "logging opened from $tab",
                BackStep.CloseLogging,
                backStep(editingProgram = false, showingFormDetail = false, logging = true, tab = tab),
            )
        }
    }

    @Test
    fun `back from any other tab goes home, and only home ends the app`() {
        for (tab in Tab.entries - HomeTab) {
            assertEquals(
                "from $tab",
                BackStep.SwitchTab(HomeTab),
                backStep(editingProgram = false, showingFormDetail = false, logging = false, tab = tab),
            )
        }
        assertEquals(
            BackStep.LeaveApp,
            backStep(editingProgram = false, showingFormDetail = false, logging = false, tab = HomeTab),
        )
    }

    @Test
    fun `home is the tab the app opens on`() {
        // if these ever drift apart, back would send the user to a tab they never started
        // from and the first press of the day would move the app instead of leaving it
        assertEquals(Tab.TODAY, HomeTab)
        assertEquals(Tab.TODAY, Tab.entries.first())
    }

    @Test
    fun `every state either goes somewhere or hands the gesture to the system`() {
        // the trap this rules out: a handler that is enabled, consumes the swipe and then
        // has nothing to do, leaving the user unable to get out of the app at all
        forEveryState { state ->
            val step = state.back()
            if (step == BackStep.LeaveApp) {
                assertEquals("a state that exits must be the bare home tab", home(), state)
            } else {
                assertNotEquals("back must change something in $state", state, state.after(step))
            }
        }
    }

    @Test
    fun `back always reaches the exit, in a bounded number of presses`() {
        /*
         * Four is the ceiling over ALL flag combinations: three modes closed one at a time,
         * then one hop to the home tab. In the app itself only one mode can be open at once
         * — the editor is reached from the timer tab, the detail screen from the overview,
         * and each covers the whole window — so the real worst case is two presses. The walk
         * is over every combination anyway, reachable or not, because a rule that loops on a
         * state nobody expected to reach is exactly the kind that ships.
         */
        forEveryState { state ->
            var current = state
            var presses = 0
            while (current.back() != BackStep.LeaveApp) {
                current = current.after(current.back())!!
                presses++
                assertTrue("back loops from $state", presses <= 4)
            }
            assertEquals("back must end at the bare home tab, from $state", home(), current)
            // one mode at a time is what the app can actually produce, and from there the
            // exit is never more than a mode and a tab away
            val modes = listOf(state.editingProgram, state.showingFormDetail, state.logging)
            if (modes.count { it } <= 1) {
                assertTrue("$state should exit within two presses, took $presses", presses <= 2)
            }
        }
    }

    @Test
    fun `the logging entry point defaults to letting the app choose the exercise`() {
        // the signature the calendar and the timer call: openLogging() with no argument
        // means "whatever makes sense", openLogging(id) means "this one"
        var opened: Long? = -1
        val entry = OpenLogging { opened = it }

        entry()
        assertNull("no argument must arrive as null, not as a made-up id", opened)

        entry(42L)
        assertEquals(42L, opened)
    }

    @Test
    fun `a screen drawn outside the app frame can still ask for logging without crashing`() {
        // the shape of the composition local's default: a preview, or the picture
        // onboarding, has no logging screen to open, and a no-op beats an exception
        val nowhere = OpenLogging {}
        nowhere()
        nowhere(7L)
    }

    @Test
    fun `every tab has a label to put under its icon`() {
        assertEquals(5, Tab.entries.size)
        assertEquals(Tab.entries.size, Tab.entries.map { it.title }.toSet().size)
        Tab.entries.forEach { assertTrue(it.title.isNotBlank()) }
    }

    // ── the model the two property tests walk over ──────────────────────────────────

    /** One reachable state of the navigation: the flags [backStep] is given. */
    private data class NavState(
        val editingProgram: Boolean,
        val showingFormDetail: Boolean,
        val logging: Boolean,
        val tab: Tab,
    )

    private fun home() = NavState(false, false, false, HomeTab)

    private fun NavState.back() = backStep(editingProgram, showingFormDetail, logging, tab)

    /** The state the app is left in after [step] is carried out — mirrors the handler. */
    private fun NavState.after(step: BackStep): NavState? = when (step) {
        BackStep.CloseEditor -> copy(editingProgram = false)
        BackStep.CloseFormDetail -> copy(showingFormDetail = false)
        BackStep.CloseLogging -> copy(logging = false)
        is BackStep.SwitchTab -> copy(tab = step.tab)
        BackStep.LeaveApp -> null
    }

    private fun forEveryState(check: (NavState) -> Unit) {
        var seen = 0
        for (editor in listOf(false, true)) {
            for (detail in listOf(false, true)) {
                for (log in listOf(false, true)) {
                    for (tab in Tab.entries) {
                        check(NavState(editor, detail, log, tab))
                        seen++
                    }
                }
            }
        }
        assertEquals(2 * 2 * 2 * Tab.entries.size, seen)
    }

    @Test
    fun `the steps are distinguishable from one another`() {
        // they are compared by value in the handler; a data object that lost its identity
        // would make two branches of the when fire on the same press
        val all = listOf(
            BackStep.CloseEditor,
            BackStep.CloseFormDetail,
            BackStep.CloseLogging,
            BackStep.SwitchTab(HomeTab),
            BackStep.LeaveApp,
        )
        assertEquals(all.size, all.toSet().size)
        assertSame(BackStep.LeaveApp, BackStep.LeaveApp)
        assertEquals(BackStep.SwitchTab(HomeTab), BackStep.SwitchTab(Tab.TODAY))
    }
}
