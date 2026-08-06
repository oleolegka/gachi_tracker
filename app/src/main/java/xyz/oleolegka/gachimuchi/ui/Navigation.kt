package xyz.oleolegka.gachimuchi.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where you can be in this app, and what "back" means from there.
 *
 * There is no navigation-compose here and there is deliberately no back STACK either. The
 * app has five tabs and three full-window modes on top of them, and every one of those
 * modes has exactly one way out. A stack would record a history that nothing ever reads:
 * the only question ever asked of it is "what is on top right now", which the three flags
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

    /** Leave the logging screen, back to the tab it was opened from. */
    data object CloseLogging : BackStep

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
 * the tabs — because "back" has to close the thing in front, and the thing in front is
 * whichever of these is checked first when drawing. Keeping both orders in step is the
 * whole point of having this as a function: change one and the test that pins them
 * together fails.
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
    tab: Tab,
): BackStep = when {
    editingProgram -> BackStep.CloseEditor
    showingFormDetail -> BackStep.CloseFormDetail
    logging -> BackStep.CloseLogging
    tab != HomeTab -> BackStep.SwitchTab(HomeTab)
    else -> BackStep.LeaveApp
}

/**
 * The way into the logging screen, from anywhere.
 *
 * Logging is the thing this app is for, and it is reached from more than one place: the
 * button on Today, a planned session tapped on the calendar, a finished run on the timer.
 * None of those screens should have to be handed a callback through their own signature
 * for it — the entry point belongs to the frame that owns the screen, not to the screens
 * that want in — so it is published through [LocalOpenLogging] and read where it is used.
 *
 * @see LocalOpenLogging for the call site.
 */
@Immutable
class OpenLogging(private val onOpen: (Long?) -> Unit) {
    /**
     * Opens the logging screen.
     *
     * @param exerciseId the exercise to open the entry card on. Null means "decide" — the
     *   exercise already being logged if there is one, otherwise the one
     *   [xyz.oleolegka.gachimuchi.domain.exerciseToLogNext] picks. Pass an id only when
     *   the tap said which exercise it was about, as tapping a planned session does.
     */
    operator fun invoke(exerciseId: Long? = null) = onOpen(exerciseId)
}

/**
 * The logging entry point of the surrounding [GachiApp].
 *
 * ```
 * val openLogging = LocalOpenLogging.current
 * ...
 * Button(onClick = { openLogging(exercise.id) }) { Text("Log this") }
 * ```
 *
 * Static, and the instance behind it is remembered once: it never changes for the life of
 * the app, so reading it costs a reader nothing and no screen is recomposed because of it.
 *
 * The default does nothing. Anything drawn outside [GachiApp] — a preview, the picture
 * onboarding — has no logging screen to open, and a no-op is a better answer there than a
 * crash.
 */
val LocalOpenLogging = staticCompositionLocalOf { OpenLogging {} }
