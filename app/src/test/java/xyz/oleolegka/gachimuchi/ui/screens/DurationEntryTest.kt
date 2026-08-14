package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextContains
import org.junit.Ignore
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.durationOf
import xyz.oleolegka.gachimuchi.domain.toPayload
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * The duration entry, typed as mm:ss (§13.9).
 *
 * It used to be a MINUTES field reaching a whole number of seconds only through a decimal
 * point — "0.5" was the only way to record thirty seconds, and there was no way at all to
 * step by less than a minute. This is the regression suite for the field that replaced it.
 */
/*
 * A taller window than the base one: the entry card plus the bump buttons plus the primary
 * button do not fit the default height, and the alternative - scrolling to them - is an
 * animation this suite freezes on purpose, which HANGS rather than fails (see SlotEditorTest).
 */
@Config(sdk = [34], qualifiers = "w600dp-h1600dp-xhdpi")
class DurationEntryTest : ScreenTest() {

    /**
     * The field, addressed by the accessible name TimeField gives it. Its visible label is a
     * Text of its OWN above the box, so matching that label lands on the label - which has no
     * focus to give, and the input fails talking about RequestFocus.
     */
    private fun field() = compose.onNode(hasSetTextAction() and hasContentDescription("Duration, mm:ss"))

    private val plank = exerciseRef(1, "Plank", ExerciseForm.DURATION)

    /** A stretch held one leg at a time — the case the whole side question exists for. */
    private val sidePlank = exerciseRef(2, "Side plank", ExerciseForm.DURATION).copy(oneSided = true)
    private val iso = "2026-08-07"

    private val logged = mutableListOf<ActivityForm>()

    private fun show(
        events: List<JournalEvent> = emptyList(),
        exercise: ExerciseRef = plank,
        fixedSide: HoldSide? = null,
    ) {
        val state = UiState(events = events, loading = false)
        screen {
            /*
             * A COLUMN, and not the bare composable, which is what this file used to raise.
             *
             * Every entry form is written for a ColumnScope - the card that hosts one on the
             * real screen is a Column - and `setContent` is not one: its children all sit at the
             * same origin and OVERLAP. Nothing looked wrong, because the assertions were on text
             * and text still resolves; what broke was every TAP, since a click lands on whatever
             * covers that point last. That is what the ignored bump test below has been
             * describing since 2026-08-11 ("through this screen the click has no effect at all").
             */
            Column {
                DurationEntry(
                    state = state,
                    exercise = exercise,
                    opDate = iso,
                    onAddSet = { logged += it },
                    fixedSide = fixedSide,
                )
            }
        }
    }

    private fun durationEvent(id: Long, durationSec: Int, ts: String = "${iso}T09:00:00"): JournalEvent {
        val form = durationOf(plank, iso, durationSec = durationSec)
        return JournalEvent(id, ts, 1, 1, form.type, form.toPayload())
    }

    @Test
    fun `a fresh exercise offers an empty field, not a fraction of a minute to guess at`() {
        show()

        compose.onNodeWithText("Duration, mm:ss").assertIsDisplayed()
        compose.onNodeWithText("Add entry").assertIsDisplayed()
    }

    /** Thirty seconds is typed as "30", not as the decimal minute this field used to demand. */
    @Test
    fun `typing digits writes mm colon ss, seconds first`() {
        show()

        field().performTextReplacement("30")
        settle()
        field().assertTextContains("0:30")

        compose.onNodeWithText("Add entry").performClick()
        assertEquals(listOf(durationOf(plank, iso, durationSec = 30)), logged)
    }

    @Test
    fun `a minute and a half is typed digit by digit, not as a decimal`() {
        show()

        field().performTextReplacement("130")
        settle()
        field().assertTextContains("1:30")

        compose.onNodeWithText("Add entry").performClick()
        assertEquals(listOf(durationOf(plank, iso, durationSec = 90)), logged)
    }

    @Test
    fun `the last duration prefills the field, formatted, and offers to repeat it`() {
        show(listOf(durationEvent(1L, durationSec = 45)))

        compose.onNodeWithText("0:45").assertIsDisplayed()
        compose.onNodeWithText("Repeat entry").assertIsDisplayed()
    }

    // --- which side, on an exercise held one limb at a time ----------------------------------

    /**
     * THE REPORTED BUG, from a phone on 2026-08-14: "нет возможности сделать разделение нагрузки
     * на право/лево для категории duration, хотя в strength это доступно".
     *
     * The chips are half of it; the other half is that the entry cannot be written WITHOUT an
     * answer, exactly as a hold cannot — a one-sided entry that names no side is the one answer
     * that is certainly wrong, and it is refused at the door rather than reported afterwards.
     */
    @Test
    fun `a one-sided exercise asks which side, and will not record until it is told`() {
        show(exercise = sidePlank)

        field().performTextReplacement("45")
        settle()

        // NOT the sentence the other two forms use ("each side keeps its own record"): a
        // duration has no records in this app, so that one would be a promise nothing keeps
        compose.onNodeWithText("Say which side - each side is counted on its own.").assertIsDisplayed()
        compose.onNodeWithText("Add entry").performClick()
        assertEquals(emptyList<ActivityForm>(), logged)

        compose.onNodeWithText("Left").performClick()
        settle()
        compose.onNodeWithText("Add entry").performClick()

        assertEquals(
            listOf(durationOf(sidePlank, iso, durationSec = 45, side = HoldSide.LEFT)),
            logged,
        )
    }

    /**
     * Two-sided work is not asked at all, and the row where the chips would be does not exist
     * either — this is the one entry form whose chip row holds nothing else, so an always-drawn
     * one would be an empty band of spacing on every ordinary exercise.
     */
    @Test
    fun `an ordinary exercise is not asked which side`() {
        show()

        compose.onNodeWithText("Left").assertDoesNotExist()
        compose.onNodeWithText("Right").assertDoesNotExist()
        compose.onNodeWithText("Say which side - each side is counted on its own.").assertDoesNotExist()
    }

    /**
     * A card raised from the LEFT card of an already-split pair has answered the question
     * already, so the form does not ask it again — and the answer still reaches the entry. The
     * same contract [HoldEntry] has with its own `fixedSide`.
     */
    @Test
    fun `a card that already names its side does not ask again, and still writes it`() {
        show(exercise = sidePlank, fixedSide = HoldSide.RIGHT)

        compose.onNodeWithText("Left").assertDoesNotExist()

        field().performTextReplacement("100")
        settle()
        compose.onNodeWithText("Add entry").performClick()

        assertEquals(
            listOf(durationOf(sidePlank, iso, durationSec = 60, side = HoldSide.RIGHT)),
            logged,
        )
    }

    /**
     * Ignored from 2026-08-11 to 2026-08-14 as "the component is proven and the wiring is not,
     * and which of the two is at fault is unknown". Neither was: the TEST was, and it was the
     * missing Column in [show] — the bump button sat under a sibling that was drawn over it, so
     * the tap never reached it. Nothing in the app was ever broken, which is the useful half of
     * the lesson: an ignored test is a claim about the app, and this one was a claim about the
     * harness for three days.
     */
    @Test
    fun `the bump adds ten seconds to whatever is already there`() {
        show(listOf(durationEvent(1L, durationSec = 45)))

        compose.onNodeWithText("+10s").performClick()
        settle()

        field().assertTextContains("0:55")
        // the consequence, not the glyphs: a bumped value is no longer what was logged last,
        // so the primary button stops offering to repeat and offers to add
        compose.onNodeWithText("Add entry").assertIsDisplayed()
    }
}
