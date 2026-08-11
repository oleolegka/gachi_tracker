package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three branches of a hold, told apart by the shape of the schedule alone (§18.15).
 *
 * The point of these tests is that no column was added and no migration ran: if the rule ever
 * drifts, an exercise silently changes branch — and with it what it asks before a run — with
 * nothing written down anywhere to say so.
 */
class HoldScheduleTest {

    private fun pair(work: Int = 7, rest: Int = 3) = WorkoutProgram(
        name = "Pair",
        groups = listOf(
            ProgramGroup(name = "Set", blocks = listOf(ProgramBlock("Hang", work, rest)))
        ),
    )

    @Test
    fun `no schedule at all is the free branch`() {
        assertEquals(ScheduleKind.FREE, scheduleKindOf(null))
    }

    @Test
    fun `one group one block no repeats is the simple pair`() {
        assertEquals(ScheduleKind.SIMPLE_PAIR, scheduleKindOf(pair()))
        assertTrue(pair().isSimplePair())
    }

    @Test
    fun `repeats on the block make it strict`() {
        val program = pair().let {
            it.copy(groups = listOf(it.groups[0].copy(blocks = listOf(ProgramBlock("Hang", 7, 3, repeats = 6)))))
        }
        assertEquals(ScheduleKind.STRICT, scheduleKindOf(program))
        assertFalse(program.isSimplePair())
    }

    @Test
    fun `repeats on the group make it strict`() {
        val program = pair().let { it.copy(groups = listOf(it.groups[0].copy(repeats = 4))) }
        assertEquals(ScheduleKind.STRICT, scheduleKindOf(program))
    }

    @Test
    fun `a second block makes it strict`() {
        val program = pair().let {
            it.copy(
                groups = listOf(
                    it.groups[0].copy(
                        blocks = listOf(ProgramBlock("Hang", 7, 3), ProgramBlock("Pull", 5, 5))
                    )
                )
            )
        }
        assertEquals(ScheduleKind.STRICT, scheduleKindOf(program))
    }

    @Test
    fun `a second group makes it strict`() {
        val program = pair().let { it.copy(groups = it.groups + it.groups) }
        assertEquals(ScheduleKind.STRICT, scheduleKindOf(program))
    }

    /** The shipped repeaters protocol is the reference example of the strict branch. */
    @Test
    fun `hangboard repeaters are strict`() {
        assertEquals(ScheduleKind.STRICT, scheduleKindOf(starterPrograms().first()))
    }

    /**
     * The ambiguity §18.15 names out loud, pinned so nobody later "fixes" it by accident: a
     * schedule hand-built as one group of one block reads as a simple pair, because it carries
     * no more information than the two numbers do.
     */
    @Test
    fun `a hand built one block schedule is indistinguishable from a typed pair`() {
        val handBuilt = WorkoutProgram(
            name = "Built by hand",
            prepareSec = 42,
            category = "Hangboard",
            groups = listOf(
                ProgramGroup(name = "Whatever", blocks = listOf(ProgramBlock("Hang", 7, 3)))
            ),
        )
        assertEquals(ScheduleKind.SIMPLE_PAIR, scheduleKindOf(handBuilt))
    }

    /*
     * ── The three degenerate schedules, and why they are FREE ──────────────────────
     * The one place two independently written classifiers disagreed before they were merged
     * (2026-08-11). A shape test alone calls all three STRICT — a program with no single group
     * is "not a simple pair", so it falls to the last branch — and STRICT is what makes
     * `ExerciseRef.canBeConducted` true, which draws the button that hands the screen to the
     * conductor. `TimerController.start` then drops a run with no steps, so the tap does
     * nothing at all and says nothing about it.
     */

    @Test
    fun `a schedule with no groups counts nothing and is free`() {
        assertEquals(ScheduleKind.FREE, scheduleKindOf(WorkoutProgram(name = "Empty", groups = emptyList())))
    }

    @Test
    fun `a group with no blocks counts nothing and is free`() {
        val program = pair().let { it.copy(groups = listOf(it.groups[0].copy(blocks = emptyList()))) }
        assertEquals(ScheduleKind.FREE, scheduleKindOf(program))
    }

    @Test
    fun `a block with no work time counts nothing and is free`() {
        assertEquals(ScheduleKind.FREE, scheduleKindOf(pair(work = 0, rest = 3)))
        // and the shape test on its own would have called it a pair, which is the divergence
        assertTrue(pair(work = 0, rest = 3).isSimplePair())
    }

    /** A block with no REST of its own is a different matter: there is work in it, so it counts. */
    @Test
    fun `a block with no rest is classified on its shape like any other`() {
        assertEquals(ScheduleKind.SIMPLE_PAIR, scheduleKindOf(pair(work = 7, rest = 0)))
    }

    @Test
    fun `the summary names the pair, the effort count and the length`() {
        val program = starterPrograms().first() // 7:3 x 6, four sets, 180 s between
        assertEquals("7:3 - 24 efforts, ${formatClock(program.totalSec())}", program.scheduleSummary())
    }

    // --- the caption the three list rows share -------------------------------------------

    /**
     * The one that used to be wrong in three places at once: a strict schedule captioned with
     * its first block's pair, which for "10 s then 7 s, six of each, four times" printed
     * "10:3" and said nothing about the other forty-seven efforts.
     */
    @Test
    fun `a strict schedule is captioned by what it holds, not by its first block`() {
        val program = starterPrograms().first() // 7:3 x 6, four sets
        assertEquals("strict - 24 efforts", scheduleCaption(program))
    }

    @Test
    fun `a simple pair is still captioned as the pair it is`() {
        assertEquals("7:3", scheduleCaption(pair()))
    }

    @Test
    fun `a free hold has no caption at all, and neither has one that counts nothing`() {
        assertNull(scheduleCaption(null))
        assertNull(scheduleCaption(pair(work = 0, rest = 3)))
    }

    /** One effort is one effort, not "1 efforts" — the row is read by a person. */
    @Test
    fun `a strict schedule of a single effort says effort in the singular`() {
        val single = WorkoutProgram(
            name = "Max hang",
            groups = listOf(
                ProgramGroup(
                    name = "Hang",
                    blocks = listOf(ProgramBlock("Hang", 30, 0)),
                    repeats = 1,
                ),
                ProgramGroup(name = "Empty", blocks = emptyList()),
            ),
        )
        assertEquals(ScheduleKind.STRICT, scheduleKindOf(single))
        assertEquals("strict - 1 effort", scheduleCaption(single))
    }
}
