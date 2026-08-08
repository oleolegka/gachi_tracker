package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.ui.screens.wholeSecondsOrNull

/**
 * The whole road a Work/Rest field travels when a new hold exercise is created: text typed
 * into `CreateExerciseForm`, through [wholeSecondsOrNull], into
 * [ActivityRepository.ensureExercise], into the library program a protocol IS.
 *
 * ── Why it is worth a test of its own ───────────────────────────────────────────
 * A fractional value used to reach the database TRUNCATED rather than refused: the field
 * parsed with `parseNumber` and stored whatever came out, and the only place that ever turned
 * "7.6" into a whole number was `ProgramRepository`'s `resolveOrCreateProtocolProgram`, silently,
 * with `.toInt()` at save time — the decimal was simply gone, and never at the field where it
 * could be seen. Whole seconds is the rule now, and the fix is that the field itself rounds, so
 * the truncation downstream never has anything left to cut. This test starts at the TEXT and
 * ends at the stored block, on the same footing [BodyweightShareEditTest] does for the
 * body-weight share's own road.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtocolInputRoundingTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ActivityRepository(db)
    }

    @After
    fun tearDown() = db.close()

    // --- the field's own conversion: rounds, does not truncate ---------------------------

    @Test
    fun `a fractional field rounds to the nearest whole second rather than being cut off`() {
        // 7.6 rounds UP to 8 - a floor()/toInt() truncation would have given 7, which is
        // exactly the defect this pins
        assertEquals(8.0, wholeSecondsOrNull("7.6"))
        // 3.4 rounds DOWN to 3 - proving this is rounding and not always-up
        assertEquals(3.0, wholeSecondsOrNull("3.4"))
        assertEquals(8.0, wholeSecondsOrNull("7,6")) // the comma keyboards offer
    }

    @Test
    fun `zero, blank and negative are refused rather than stored as a protocol`() {
        assertNull(wholeSecondsOrNull(""))
        assertNull(wholeSecondsOrNull("0"))
        assertNull(wholeSecondsOrNull("-2"))
    }

    // --- end to end: the value that actually reaches the stored program ------------------

    @Test
    fun `a fractional protocol typed while creating an exercise lands in the library rounded, not truncated`() = runTest {
        val work = wholeSecondsOrNull("7.6")
        val rest = wholeSecondsOrNull("3.4")

        val id = repo.ensureExercise("Hangs", ExerciseForm.HOLD, workSec = work, restSec = rest)

        val ref = repo.toRef(repo.exercise(id)!!)
        assertEquals("the field rounded 7.6 up, not down", 8.0, ref.workSec!!, 1e-9)
        assertEquals("the field rounded 3.4 down", 3.0, ref.restSec!!, 1e-9)
    }
}
