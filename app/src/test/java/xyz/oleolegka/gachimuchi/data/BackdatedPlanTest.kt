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
import xyz.oleolegka.gachimuchi.domain.REPEAT_NONE
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.toDraft
import java.time.LocalDate

/**
 * The floor under a plan's anchor date, at the boundary the database is behind.
 *
 * It used to live in the slot editor and nowhere else — one disabled button — so the rule
 * held only for as long as every writer went through that dialog and was composed against a
 * "today" that was still true. `saveSlot` now asks the same question again, which is what
 * every other refusal in it already did.
 *
 * The rule is about the ACT, not the value: putting a plan on a day gone is refused; an
 * existing plan whose anchor is already back there may still be saved, because its anchor is
 * not being moved anywhere and refusing removes none of its past occurrences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackdatedPlanTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    private val today = LocalDate.of(2026, 8, 11)

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
        rule: String = REPEAT_NONE,
        day: LocalDate = today,
    ) = SlotDraft(name = name, timeText = "", repeatRule = rule, anchorDate = day.toString())

    @Test
    fun `a new plan on a day already gone is refused by the repository too`() = runTest {
        assertNull(repo.saveSlot(draft(day = today.minusDays(1)), today = today))
        assertEquals(0, repo.allSlots().size)
    }

    @Test
    fun `today and tomorrow are both fine`() = runTest {
        assertNotNull(repo.saveSlot(draft(day = today), today = today))
        assertNotNull(repo.saveSlot(draft(day = today.plusDays(1)), today = today))
        assertEquals(2, repo.allSlots().size)
    }

    /**
     * The regression the first cut of this rule caused: a weekly session set up three weeks
     * ago IS anchored three weeks ago, and comparing that anchor with today made every edit
     * of it — a rename, a time, an exercise — impossible.
     */
    @Test
    fun `an old repeating plan can still be renamed without moving its anchor`() = runTest {
        val anchor = today.minusDays(21)
        // set up as history: written with no floor, the way a restore or an older build would
        val id = repo.saveSlot(draft(rule = REPEAT_WEEKLY, day = anchor))!!

        val again = repo.saveSlot(
            repo.slot(id)!!.toDraft().copy(name = "Powerlifting"), id = id, today = today,
        )

        assertEquals(id, again)
        assertEquals("Powerlifting", repo.slot(id)!!.name)
        assertEquals(anchor.toString(), repo.slot(id)!!.anchorDate)
    }

    @Test
    fun `an old plan may be moved forward but not further back`() = runTest {
        val anchor = today.minusDays(21)
        val id = repo.saveSlot(draft(rule = REPEAT_WEEKLY, day = anchor))!!

        val backwards = repo.saveSlot(
            repo.slot(id)!!.toDraft().copy(anchorDate = anchor.minusDays(7).toString()),
            id = id,
            today = today,
        )
        assertNull(backwards)
        assertEquals(anchor.toString(), repo.slot(id)!!.anchorDate)

        val forwards = repo.saveSlot(
            repo.slot(id)!!.toDraft().copy(anchorDate = today.toString()), id = id, today = today,
        )
        assertEquals(id, forwards)
        assertEquals(today.toString(), repo.slot(id)!!.anchorDate)
    }

    /** A plan whose row has gone in the meantime writes nothing, floor or no floor. */
    @Test
    fun `an edit of a deleted plan still writes nothing`() = runTest {
        val id = repo.saveSlot(draft(), today = today)!!
        repo.deleteSlot(id)

        assertNull(repo.saveSlot(draft(name = "Back again"), id = id, today = today))
    }
}
