package xyz.oleolegka.gachimuchi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            backStep(true, showingFormDetail = false, logging = false, showingWorkout = false, tab = Tab.TIMER),
        )
        assertEquals(
            BackStep.CloseEditor,
            backStep(true, showingFormDetail = true, logging = true, showingWorkout = true, tab = Tab.OVERVIEW),
        )
    }

    @Test
    fun `back leaves the form detail screen for the tab it was opened from`() {
        assertEquals(
            BackStep.CloseFormDetail,
            backStep(false, showingFormDetail = true, logging = false, showingWorkout = false, tab = Tab.OVERVIEW),
        )
        // closing it only clears the mode; the tab underneath was never changed, so
        // Overview is what comes back — the same thing the arrow in its title bar does
        assertEquals(
            BackStep.CloseFormDetail,
            backStep(false, showingFormDetail = true, logging = true, showingWorkout = false, tab = Tab.OVERVIEW),
        )
    }

    /**
     * The gesture that must NOT stop a set. Backing out of the conductor lands on the card
     * list of the workout it was started from, which is what makes a superset work: the bench
     * is logged while the hang is still being called out (§13.3, step 10).
     */
    @Test
    fun `back leaves the conductor and lands on the workout it was started from`() {
        assertEquals(
            BackStep.CloseConductor,
            backStep(false, showingFormDetail = false, logging = true, showingWorkout = false,
                tab = HomeTab, conducting = true),
        )
        // and with the conductor gone, the next press closes the logging screen under it
        assertEquals(
            BackStep.CloseLogging,
            backStep(false, showingFormDetail = false, logging = true, showingWorkout = false,
                tab = HomeTab, conducting = false),
        )
    }

    @Test
    fun `back leaves the logging screen for whichever tab opened it`() {
        // logging is reached from a card on a day, which lives on Today and on the calendar
        // — but the mode is drawn over whatever tab was selected, so each leaves its own.
        for (tab in Tab.entries) {
            assertEquals(
                "logging opened from $tab",
                BackStep.CloseLogging,
                backStep(false, showingFormDetail = false, logging = true, showingWorkout = false, tab = tab),
            )
        }
    }

    @Test
    fun `back out of logging lands on the workout it was logging into`() {
        // the pair that makes the order of these two a decision rather than an accident:
        // "Continue" leads from the workout screen into the entry card, and backing out of
        // the entry card has to return to the workout rather than skip past it
        assertEquals(
            BackStep.CloseLogging,
            backStep(false, showingFormDetail = false, logging = true, showingWorkout = true, tab = HomeTab),
        )
        assertEquals(
            BackStep.CloseWorkout,
            backStep(false, showingFormDetail = false, logging = false, showingWorkout = true, tab = HomeTab),
        )
    }

    @Test
    fun `back leaves the workout screen for whichever tab opened it`() {
        // a workout is opened from a card, and cards live on two different tabs
        for (tab in Tab.entries) {
            assertEquals(
                "workout opened from $tab",
                BackStep.CloseWorkout,
                backStep(false, showingFormDetail = false, logging = false, showingWorkout = true, tab = tab),
            )
        }
    }

    @Test
    fun `back from any other tab goes home, and only home ends the app`() {
        for (tab in Tab.entries - HomeTab) {
            assertEquals(
                "from $tab",
                BackStep.SwitchTab(HomeTab),
                backStep(false, showingFormDetail = false, logging = false, showingWorkout = false, tab = tab),
            )
        }
        assertEquals(
            BackStep.LeaveApp,
            backStep(false, showingFormDetail = false, logging = false, showingWorkout = false, tab = HomeTab),
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
         * Six is the ceiling over ALL flag combinations: five modes closed one at a time,
         * then one hop to the home tab. In the app itself at most THREE can be open at once —
         * the conductor over logging over a workout, which is a hang started from a card
         * inside a workout that was opened to look at — so the real worst case is four
         * presses. The walk is over every combination anyway, reachable or not, because a
         * rule that loops on a state nobody expected to reach is exactly the kind that ships.
         */
        forEveryState { state ->
            var current = state
            var presses = 0
            while (current.back() != BackStep.LeaveApp) {
                current = current.after(current.back())!!
                presses++
                assertTrue("back loops from $state", presses <= 6)
            }
            assertEquals("back must end at the bare home tab, from $state", home(), current)
            // the combinations the app can actually produce, and from each of them the exit
            // is never more than the open modes plus a tab away
            val modes = listOf(
                state.editingProgram, state.showingFormDetail, state.conducting,
                state.logging, state.showingWorkout,
            )
            if (modes.count { it } <= 1) {
                assertTrue("$state should exit within two presses, took $presses", presses <= 2)
            }
        }
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
        val showingWorkout: Boolean,
        val tab: Tab,
        val conducting: Boolean = false,
    )

    private fun home() = NavState(false, false, false, false, HomeTab)

    private fun NavState.back() =
        backStep(editingProgram, showingFormDetail, logging, showingWorkout, tab, conducting)

    /**
     * The state the app is left in after [step] is carried out — mirrors the handler.
     *
     * Closing the conductor clears only the flag that says it is on screen. THE RUN IS NOT
     * TOUCHED, here or in the app: this is the model of navigation, and a protocol-led set is
     * not part of navigation — it keeps counting whichever screen is in front of it.
     */
    private fun NavState.after(step: BackStep): NavState? = when (step) {
        BackStep.CloseEditor -> copy(editingProgram = false)
        BackStep.CloseFormDetail -> copy(showingFormDetail = false)
        BackStep.CloseConductor -> copy(conducting = false)
        BackStep.CloseLogging -> copy(logging = false)
        BackStep.CloseWorkout -> copy(showingWorkout = false)
        is BackStep.SwitchTab -> copy(tab = step.tab)
        BackStep.LeaveApp -> null
    }

    private fun forEveryState(check: (NavState) -> Unit) {
        var seen = 0
        for (editor in listOf(false, true)) {
            for (detail in listOf(false, true)) {
                for (log in listOf(false, true)) {
                    for (workout in listOf(false, true)) {
                        for (conducting in listOf(false, true)) {
                            for (tab in Tab.entries) {
                                check(NavState(editor, detail, log, workout, tab, conducting))
                                seen++
                            }
                        }
                    }
                }
            }
        }
        assertEquals(2 * 2 * 2 * 2 * 2 * Tab.entries.size, seen)
    }

    @Test
    fun `the steps are distinguishable from one another`() {
        // they are compared by value in the handler; a data object that lost its identity
        // would make two branches of the when fire on the same press
        val all = listOf(
            BackStep.CloseEditor,
            BackStep.CloseFormDetail,
            BackStep.CloseConductor,
            BackStep.CloseLogging,
            BackStep.CloseWorkout,
            BackStep.SwitchTab(HomeTab),
            BackStep.LeaveApp,
        )
        assertEquals(all.size, all.toSet().size)
        assertSame(BackStep.LeaveApp, BackStep.LeaveApp)
        assertEquals(BackStep.SwitchTab(HomeTab), BackStep.SwitchTab(Tab.TODAY))
    }
}
