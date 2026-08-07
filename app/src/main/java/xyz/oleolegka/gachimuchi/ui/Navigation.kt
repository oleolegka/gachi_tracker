package xyz.oleolegka.gachimuchi.ui

/**
 * Where you can be in this app, and what "back" means from there.
 *
 * There is no navigation-compose here and there is deliberately no back STACK either. The
 * app has five tabs and four full-window modes on top of them, and every one of those
 * modes has exactly one way out. A stack would record a history that nothing ever reads:
 * the only question ever asked of it is "what is on top right now", which the four flags
 * in [GachiApp] already answer.
 *
 * What a stack WOULD have bought is the answer to "where does back go", and that is what
 * [backStep] is: one pure function, in one place, over the same flags the screen is drawn
 * from. It is written in the same order as the `when` that draws the app, so the thing
 * back closes is by construction the thing you are looking at.
 */
enum class Tab(val title: String) {
    TODAY("Today"),
    OVERVIEW("Overview"),
    CALENDAR("Calendar"),
    TIMER("Timer"),
    SETTINGS("Settings"),
}

/**
 * The tab the app opens on, and the one back returns to from any other tab.
 *
 * Back goes HOME rather than to the previously visited tab. A full tab history reads well
 * in a description and badly under a thumb: tabs are switched idly, back and forth, so the
 * history is mostly a record of glances nobody remembers making, and the number of presses
 * needed to leave the app would depend on how much browsing happened. Toggling between two
 * tabs a few times would bury the exit behind half a dozen presses that each undo a move
 * the user did not think of as a move.
 *
 * Home-then-exit is bounded: at most one press to reach Today, one more to leave, from
 * anywhere. It is also what the platform's own bottom-bar convention does, so it is the
 * behaviour a phone user already expects.
 */
val HomeTab = Tab.TODAY

/** What a back gesture does, given what is currently on screen. See [backStep]. */
sealed interface BackStep {
    /** Leave the program editor, discarding whatever was not saved. */
    data object CloseEditor : BackStep

    /** Leave the form detail screen, back to the tab it was opened from (Overview). */
    data object CloseFormDetail : BackStep

    /** Leave the logging screen, back to whatever it was opened over. */
    data object CloseLogging : BackStep

    /** Leave the workout screen, back to the day list that opened it. */
    data object CloseWorkout : BackStep

    /** Switch to [tab] — always [HomeTab], see the note there. */
    data class SwitchTab(val tab: Tab) : BackStep

    /**
     * Nothing left to go back to. The gesture is NOT handled: the system takes it and
     * sends the app to the background, which is what a back press on a home screen has
     * always meant.
     */
    data object LeaveApp : BackStep
}

/**
 * Where back leads from the state described by the arguments.
 *
 * The order is the precedence the app is drawn in — editor over detail over logging over
 * the workout screen over the tabs — because "back" has to close the thing in front, and
 * the thing in front is whichever of these is checked first when drawing. Keeping both
 * orders in step is the whole point of having this as a function: change one and the test
 * that pins them together fails.
 *
 * LOGGING SITS ABOVE THE WORKOUT SCREEN, and that pair is the one place the order carries a
 * decision rather than an accident. A workout is opened to look at, and "Continue" leads
 * from it into the entry card; backing out of the entry card therefore lands on the workout
 * it was logging into, not on the tab two steps below. The reverse order would make the
 * workout unreachable from the screen that belongs to it.
 *
 * Dialogs and bottom sheets are absent on purpose. Each of them (the exercise picker, the
 * slot editor, the offer to log a finished run) is hosted in its own window and takes the
 * gesture before it ever reaches the app, so a rule stated here could only disagree with
 * what actually happens.
 */
fun backStep(
    editingProgram: Boolean,
    showingFormDetail: Boolean,
    logging: Boolean,
    showingWorkout: Boolean,
    tab: Tab,
): BackStep = when {
    editingProgram -> BackStep.CloseEditor
    showingFormDetail -> BackStep.CloseFormDetail
    logging -> BackStep.CloseLogging
    showingWorkout -> BackStep.CloseWorkout
    tab != HomeTab -> BackStep.SwitchTab(HomeTab)
    else -> BackStep.LeaveApp
}

/*
 * There used to be an `OpenLogging` entry point published through a composition local, so
 * that any screen could open the entry card on an exercise of its choosing. It is gone with
 * the button that justified it: logging is no longer something a screen offers out of the
 * blue, it is something a CARD on a day offers, and the card knows the workout it belongs
 * to. What the two screens showing those cards need is in ui/components/DayCardList.kt
 * (`DayActions`), passed as an ordinary parameter — both of them are direct children of
 * [GachiApp], so there was never a depth problem for a composition local to solve.
 */
