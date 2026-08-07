package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.DayState
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.REPEAT_DAILY
import xyz.oleolegka.gachimuchi.domain.REPEAT_NONE
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.activityStamps
import xyz.oleolegka.gachimuchi.domain.planVsFact
import xyz.oleolegka.gachimuchi.domain.slotsForRange
import xyz.oleolegka.gachimuchi.domain.toDraft
import xyz.oleolegka.gachimuchi.domain.withExerciseMoved
import xyz.oleolegka.gachimuchi.domain.withExerciseRemoved
import xyz.oleolegka.gachimuchi.domain.withExerciseRest
import java.time.LocalDate

/**
 * Creating, editing and deleting a plan slot end to end: the draft the editor builds goes
 * into the real Room schema and comes back out as occurrences on the calendar.
 *
 * The point of running this against the database rather than against the domain alone is
 * the EDIT: a slot is one master row, so "move the gym to Tuesday" has to be an update of
 * that row which the expansion then follows. A test that only called the domain would pass
 * even if the update wrote a second row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlotEditingTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    private val monday = LocalDate.of(2026, 8, 3)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ActivityRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun draft(
        name: String = "Gym",
        time: String = "",
        rule: String = REPEAT_NONE,
        day: LocalDate = monday,
    ) = SlotDraft(name = name, timeText = time, repeatRule = rule, anchorDate = day.toString())

    @Test
    fun `a saved draft becomes a slot with a normalized name and time`() = runTest {
        val id = repo.saveSlot(draft(name = "  Gym  ", time = "1800"))!!
        val slot = repo.slot(id)!!
        assertEquals("Gym", slot.name)
        assertEquals("18:00", slot.atTime)
        assertEquals(REPEAT_NONE, slot.repeatRule)
        assertEquals(monday.toString(), slot.anchorDate)
        assertEquals(1, repo.allSlots().size)
    }

    @Test
    fun `a draft that is not storable is refused at the repository, not just on screen`() = runTest {
        assertNull(repo.saveSlot(draft(name = "   ")))
        assertNull(repo.saveSlot(draft(time = "half six")))
        assertNull(repo.saveSlot(draft(rule = "monthly")))
        assertEquals(0, repo.allSlots().size)
    }

    @Test
    fun `editing rewrites the same row, and the occurrences follow`() = runTest {
        val id = repo.saveSlot(draft(name = "Gym", time = "18:00", rule = REPEAT_WEEKLY))!!
        assertEquals(
            listOf("2026-08-03", "2026-08-10", "2026-08-17"),
            slotsForRange(repo.allSlots(), monday, monday.plusDays(20)).map { it.day },
        )

        // same slot, moved to Tuesday and renamed
        val again = repo.saveSlot(
            draft(name = "Gym and sauna", time = "19:30", rule = REPEAT_WEEKLY, day = monday.plusDays(1)),
            id = id,
        )
        assertEquals("the edit must not create a second slot", id, again)
        assertEquals(1, repo.allSlots().size)

        val occurrences = slotsForRange(repo.allSlots(), monday, monday.plusDays(20))
        assertEquals(listOf("2026-08-04", "2026-08-11", "2026-08-18"), occurrences.map { it.day })
        assertEquals("Gym and sauna", occurrences.first().name)
        assertEquals("19:30", occurrences.first().atTime)
    }

    @Test
    fun `changing the rule changes how many days the slot lands on`() = runTest {
        val id = repo.saveSlot(draft(name = "Stretching", rule = REPEAT_NONE))!!
        assertEquals(1, slotsForRange(repo.allSlots(), monday, monday.plusDays(6)).size)

        repo.saveSlot(draft(name = "Stretching", rule = REPEAT_WEEKLY), id = id)
        assertEquals(1, slotsForRange(repo.allSlots(), monday, monday.plusDays(6)).size)
        assertEquals(2, slotsForRange(repo.allSlots(), monday, monday.plusDays(7)).size)

        repo.saveSlot(draft(name = "Stretching", rule = REPEAT_DAILY), id = id)
        assertEquals(7, slotsForRange(repo.allSlots(), monday, monday.plusDays(6)).size)

        // and back to a one-off: nothing is left behind from the wider rules
        repo.saveSlot(draft(name = "Stretching", rule = REPEAT_NONE), id = id)
        assertEquals(1, slotsForRange(repo.allSlots(), monday, monday.plusDays(30)).size)
    }

    @Test
    fun `editing a slot that is gone writes nothing`() = runTest {
        val id = repo.saveSlot(draft())!!
        repo.deleteSlot(id)
        assertNull(repo.saveSlot(draft(name = "Back again"), id = id))
        assertEquals(0, repo.allSlots().size)
    }

    @Test
    fun `deleting a repeating slot removes every occurrence, the past ones included`() = runTest {
        val id = repo.saveSlot(draft(name = "Gym", time = "18:00", rule = REPEAT_WEEKLY))!!
        val today = monday.plusDays(14)
        assertEquals(3, slotsForRange(repo.allSlots(), monday, today).size)

        repo.deleteSlot(id)
        assertEquals(0, repo.allSlots().size)
        assertTrue(slotsForRange(repo.allSlots(), monday, today.plusDays(60)).isEmpty())
    }

    @Test
    fun `plan and fact are recomputed after every edit of the plan`() = runTest {
        val today = monday.plusDays(14)
        // the clock the verdicts are read against: the small hours of the last day, so
        // every earlier slot's window has closed and the last day's 18:00 one has not opened
        val now = today.atStartOfDay()
        val exerciseId = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        // one workout, on the Monday. The entry is written NOW and dated back to the Monday,
        // so its clock time says nothing about when it was trained (activityStamps drops it)
        // and it can only close a slot by the same-day fallback.
        repo.record(
            StrengthSet(
                exercise = "Bench press", reps = 5, weightKg = 60.0,
                exerciseId = exerciseId, opDate = monday.toString(),
            )
        )

        suspend fun states(): Map<String, DayState> {
            val events = repo.allEvents()
            val stamps = activityStamps(events, monday.toString(), today.toString())
            return planVsFact(repo.allSlots(), stamps, monday, today, now)
                .associate { it.day to it.state }
        }

        // no plan yet: a workout on an unplanned day is "extra"
        assertEquals(DayState.EXTRA, states().getValue(monday.toString()))
        assertEquals(DayState.EMPTY, states().getValue(monday.plusDays(7).toString()))

        // plan it weekly: the day with the workout turns done, the next one turns missed
        val id = repo.saveSlot(draft(name = "Gym", time = "18:00", rule = REPEAT_WEEKLY))!!
        assertEquals(DayState.DONE, states().getValue(monday.toString()))
        assertEquals(DayState.MISS, states().getValue(monday.plusDays(7).toString()))
        assertEquals(
            "a session whose window has not opened is not missed yet",
            DayState.PLAN, states().getValue(today.toString()),
        )

        // move it a day later: the workout stops counting for it, and the day it left is bare
        repo.saveSlot(
            draft(name = "Gym", time = "18:00", rule = REPEAT_WEEKLY, day = monday.plusDays(1)),
            id = id,
        )
        assertEquals(DayState.EXTRA, states().getValue(monday.toString()))
        assertEquals(DayState.MISS, states().getValue(monday.plusDays(1).toString()))

        // delete it: the missed days stop being missed, because there is no plan any more
        repo.deleteSlot(id)
        val after = states()
        assertEquals(DayState.EXTRA, after.getValue(monday.toString()))
        assertTrue(
            "nothing is missed once the slot is gone",
            after.values.none { it == DayState.MISS || it == DayState.PLAN },
        )
        // the workout itself is untouched by any of this
        assertEquals(1, repo.eventCount())
    }

    // --- what the session is made of -------------------------------------------------------

    /**
     * The composition end to end: three exercises into a slot, back out in order, reordered,
     * and gone when the slot goes.
     *
     * Against the real schema rather than the domain alone, because everything that can go
     * wrong here is below the domain: the order lives in a `position` column that is written
     * from the list index, the optional rest is a nullable column that a wrong declaration
     * would flatten to zero, and the rows disappear by a foreign key rather than by any code
     * this test can see.
     */
    @Test
    fun `a slot keeps the exercises planned into it, in order, with their rests`() = runTest {
        val bench = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val squat = repo.ensureExercise("Squat", ExerciseForm.STRENGTH)
        val row = repo.ensureExercise("Barbell row", ExerciseForm.STRENGTH)

        val id = repo.saveSlot(
            draft(name = "Gym", time = "18:00", rule = REPEAT_WEEKLY).copy(
                exercises = listOf(
                    PlannedExercise(bench, restSec = 180),
                    PlannedExercise(squat, restSec = 240),
                    // no rest of its own: "whatever this exercise usually gets"
                    PlannedExercise(row),
                )
            )
        )!!

        val stored = repo.slot(id)!!
        assertEquals(listOf(bench, squat, row), stored.exercises.map { it.exerciseId })
        assertEquals(listOf(180, 240, null), stored.exercises.map { it.restSec })
        // and the same list arrives through the whole-plan read the calendar uses
        assertEquals(stored.exercises, repo.allSlots().single().exercises)
        assertEquals(stored.exercises, repo.slotExercises(id))
    }

    @Test
    fun `reordering the plan rewrites the same slot rather than adding to it`() = runTest {
        val bench = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val squat = repo.ensureExercise("Squat", ExerciseForm.STRENGTH)
        val row = repo.ensureExercise("Barbell row", ExerciseForm.STRENGTH)

        val id = repo.saveSlot(
            draft(name = "Gym", time = "18:00").copy(
                exercises = listOf(PlannedExercise(bench), PlannedExercise(squat), PlannedExercise(row))
            )
        )!!

        // squat first now, and the bench loses the rest it had
        repo.saveSlot(
            repo.slot(id)!!.toDraft().withExerciseMoved(1, -1),
            id = id,
        )

        assertEquals(listOf(squat, bench, row), repo.slot(id)!!.exercises.map { it.exerciseId })
        /*
         * The replace-rather-than-diff property, and the reason it is asserted on the ROW
         * COUNT: a save that inserted without clearing would read back correctly through the
         * ordered query and still be leaving a duplicate of the whole plan behind on every
         * edit.
         */
        assertEquals(3, db.slots().allExercises().size)
        assertEquals(1, repo.allSlots().size)

        // dropping one and adding a rest to another, through the same path the editor uses
        repo.saveSlot(
            repo.slot(id)!!.toDraft().withExerciseRemoved(2).withExerciseRest(0, 240),
            id = id,
        )
        assertEquals(listOf(squat, bench), repo.slot(id)!!.exercises.map { it.exerciseId })
        assertEquals(listOf(240, null), repo.slot(id)!!.exercises.map { it.restSec })
        assertEquals(2, db.slots().allExercises().size)
    }

    @Test
    fun `deleting a slot takes its composition with it and leaves the catalog alone`() = runTest {
        val bench = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val gym = repo.saveSlot(
            draft(name = "Gym", time = "18:00").copy(exercises = listOf(PlannedExercise(bench, 180)))
        )!!
        val other = repo.saveSlot(
            draft(name = "Fingerboard", day = monday.plusDays(2))
                .copy(exercises = listOf(PlannedExercise(bench)))
        )!!
        assertEquals(2, db.slots().allExercises().size)

        repo.deleteSlot(gym)

        // the deleted plan's rows are gone, the surviving plan's are not
        assertEquals(listOf(other), db.slots().allExercises().map { it.slotId })
        assertEquals(listOf(bench), repo.slot(other)!!.exercises.map { it.exerciseId })
        // and the exercise itself is untouched: a plan referring to it is not a claim on it
        assertEquals(1, repo.allExercises().size)
    }

    @Test
    fun `a plan that is not storable writes no exercises either`() = runTest {
        val bench = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)

        // refused for a blank name, so nothing about it reaches the database
        assertNull(
            repo.saveSlot(draft(name = "  ").copy(exercises = listOf(PlannedExercise(bench))))
        )
        assertEquals(0, db.slots().allExercises().size)

        // and an edit of a slot that has been deleted in the meantime writes nothing either
        val id = repo.saveSlot(draft(name = "Gym"))!!
        repo.deleteSlot(id)
        assertNull(
            repo.saveSlot(
                draft(name = "Gym").copy(exercises = listOf(PlannedExercise(bench))),
                id = id,
            )
        )
        assertEquals(0, db.slots().allExercises().size)
    }

    @Test
    fun `deleting one slot leaves the others alone`() = runTest {
        val gym = repo.saveSlot(draft(name = "Gym", time = "18:00", rule = REPEAT_WEEKLY))!!
        repo.saveSlot(draft(name = "Fingerboard", time = "20:00", rule = REPEAT_WEEKLY, day = monday.plusDays(2)))

        repo.deleteSlot(gym)
        val left = repo.allSlots()
        assertEquals(1, left.size)
        assertEquals("Fingerboard", left.single().name)
        assertEquals(
            listOf("2026-08-05", "2026-08-12"),
            slotsForRange(left, monday, monday.plusDays(13)).map { it.day },
        )
    }
}
