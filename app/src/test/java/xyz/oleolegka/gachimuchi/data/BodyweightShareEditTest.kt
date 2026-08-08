package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.bodyweightOf
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.volumeSeries
import xyz.oleolegka.gachimuchi.ui.components.asPercentField
import xyz.oleolegka.gachimuchi.ui.components.percentAsShare

/**
 * The whole road a body-weight share travels: a number typed into the edit dialog, through
 * the repository, into the column, and out the other end as tonnage on a chart.
 *
 * ── Why it is worth a test of its own ───────────────────────────────────────────
 * Every station on this road already had tests — the arithmetic in `strengthLoadKg`, the
 * column in the migration, the snapshot on the set — and the road still did not work, because
 * one station was missing: nothing in the app wrote the column. So this test deliberately
 * starts at the TEXT the dialog collects and ends at the chart, and asserts the before as well
 * as the after. The "before" is the assertion that would have caught the original hole.
 *
 * The dialog itself is not driven here (it is a Compose dialog and this is a Room test); what
 * is used is its conversion pair, which is the part that could silently divide by a hundred.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BodyweightShareEditTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    private val day = "2026-08-08"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ActivityRepository(db)
    }

    @After
    fun tearDown() = db.close()

    /** The volume series of one exercise, read the way `FormDetailScreen` reads it. */
    private suspend fun volumeOf(exerciseId: Long) = repo.exercise(exerciseId)!!.let { row ->
        volumeSeries(
            readActivities(repo.allEvents()),
            ExerciseLink(row.uid, row.id),
            ExerciseForm.fromCode(row.form),
            row.bodyweightShare,
        )
    }

    @Test
    fun `pull-ups are worth no kilograms until the share is set, and eight reps of them after`() =
        runTest {
            val id = repo.ensureExercise("Pull-ups", ExerciseForm.STRENGTH)
            val pullUps = repo.exercise(id)!!.toRef()
            repo.record(bodyweightOf(day, weightKg = 70.0))
            repo.record(strengthSetOf(pullUps, day, reps = 8, ownWeight = true))

            // the set knows what it weighed - that half of the machinery was already working
            val logged = readActivities(repo.allEvents())
                .mapNotNull { it.form as? StrengthSet }.single()
            assertEquals(70.0, logged.bodyweightKg!!, 1e-9)

            // BEFORE: the catalog says nothing, so there is no tonnage to draw and the chart
            // falls back to counting reps. This is what the owner saw on the phone.
            val before = volumeOf(id)!!
            assertEquals("Reps", before.spec.label)
            assertEquals(8.0, before.points.single().value, 1e-9)
            assertNull(repo.exercise(id)!!.bodyweightShare)

            // what the dialog does when "100" is typed into "How much of you it lifts, %"
            repo.setBodyweightShare(id, percentAsShare("100"))
            assertEquals(1.0, repo.exercise(id)!!.bodyweightShare!!, 1e-9)

            // AFTER: eight reps of a whole 70 kg person
            val after = volumeOf(id)!!
            assertEquals("Volume, reps x weight", after.spec.label)
            assertEquals(560.0, after.points.single().value, 1e-9)
        }

    @Test
    fun `a push-up at two thirds is worth two thirds, and the field reopens showing 65`() =
        runTest {
            val id = repo.ensureExercise("Push-ups", ExerciseForm.STRENGTH)
            val pushUps = repo.exercise(id)!!.toRef()
            repo.record(bodyweightOf(day, weightKg = 70.0))
            repo.record(strengthSetOf(pushUps, day, reps = 10, ownWeight = true))

            repo.setBodyweightShare(id, percentAsShare("65"))
            assertEquals(0.65, repo.exercise(id)!!.bodyweightShare!!, 1e-9)
            assertEquals(455.0, volumeOf(id)!!.points.single().value, 1e-9)

            // reopening the dialog shows what was typed, not 0.65 and not 65.00000000000001
            assertEquals("65", repo.exercise(id)!!.bodyweightShare.asPercentField())
        }

    @Test
    fun `emptying the field puts the exercise back to saying nothing`() = runTest {
        val id = repo.ensureExercise("Dips", ExerciseForm.STRENGTH)
        val dips = repo.exercise(id)!!.toRef()
        repo.record(bodyweightOf(day, weightKg = 70.0))
        repo.record(strengthSetOf(dips, day, reps = 6, ownWeight = true))

        repo.setBodyweightShare(id, percentAsShare("100"))
        assertNotNull(repo.exercise(id)!!.bodyweightShare)

        repo.setBodyweightShare(id, percentAsShare(""))
        assertNull(repo.exercise(id)!!.bodyweightShare)
        // and the chart is back to exactly what it drew before anybody said anything
        assertEquals("Reps", volumeOf(id)!!.spec.label)
    }

    @Test
    fun `assistance is subtracted from the share, and never below zero`() = runTest {
        val id = repo.ensureExercise("Assisted pull-ups", ExerciseForm.STRENGTH)
        val ref = repo.exercise(id)!!.toRef()
        repo.record(bodyweightOf(day, weightKg = 70.0))
        repo.record(strengthSetOf(ref, day, reps = 5, ownWeight = true, addedKg = -20.0))
        repo.setBodyweightShare(id, percentAsShare("100"))

        // five reps of (70 - 20)
        assertEquals(250.0, volumeOf(id)!!.points.single().value, 1e-9)
    }

    /**
     * The conversion on its own, including the two ways it could quietly ruin the column: a
     * number bigger than one body, and a share typed where a percent was asked for.
     */
    @Test
    fun `the percent field refuses what cannot be a share of one body`() {
        assertEquals(1.0, percentAsShare("100")!!, 1e-9)
        assertEquals(0.65, percentAsShare("65")!!, 1e-9)
        assertEquals(0.655, percentAsShare("65,5")!!, 1e-9)
        assertNull("empty is 'not said'", percentAsShare(""))
        assertNull("more than the whole body", percentAsShare("150"))
        assertNull("zero is not an answer", percentAsShare("0"))
        assertNull("assistance is on the set, not here", percentAsShare("-10"))
        assertNull("not a number", percentAsShare("two thirds"))

        // the round trip the dialog performs every time it opens and saves
        assertEquals("100", 1.0.asPercentField())
        assertEquals("65", 0.65.asPercentField())
        assertEquals("", (null as Double?).asPercentField())
        assertEquals(0.65, percentAsShare(0.65.asPercentField())!!, 1e-9)
    }
}
