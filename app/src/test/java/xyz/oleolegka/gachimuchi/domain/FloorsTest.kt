package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parallel rests: what several countdowns running at once are allowed to say, and — the
 * half that is easy to get wrong — when they must keep quiet.
 *
 * The rules under test are not conveniences. The tone generator plays one tone at a time,
 * so two floors coming due together do not produce two signals, they produce one signal
 * with a bite taken out of it; that is the same mechanism that used to silence the step
 * boundary on 7:3 repeaters (see SignalTimingTest). With one timer it was one collision to
 * fix. With a superset's worth of rests running side by side it is a class of collision,
 * which is why the spacing is arithmetic here rather than a delay somewhere in a service.
 *
 * All of it is pure arithmetic over an injected monotonic clock, so all of it runs on the
 * JVM. What that cannot show is stated plainly: whether the phone's audio path adds latency
 * of its own, whether two tones two seconds apart are actually distinguishable through a
 * pocket, and whether the alarm the service sets from [nextFloorSignalMs] survives Doze.
 * Those need a phone.
 */
class FloorsTest {

    private companion object {
        /** Wall clock at an arbitrary moment; only the differences from it matter. */
        const val WALL = 1_700_000_000_000L
    }

    private fun floor(
        id: Long,
        name: String = "Bench",
        readyAt: Long,
        orderedMs: Long = 120_000,
        bootRef: Long = 0,
    ) = RestFloor(
        exerciseId = id,
        exerciseName = name,
        readyAtMs = readyAt,
        bootRef = bootRef,
        orderedMs = orderedMs,
        startedAtWallMs = WALL,
    )

    // --- where a floor stands ----------------------------------------------------------

    @Test
    fun `a floor just started is empty and counting`() {
        val bench = startFloor(1, "Bench", orderedMs = 120_000, nowElapsed = 10_000, nowWall = WALL)
        val at = bench.progressAt(10_000)

        assertFalse(at.ready)
        assertEquals(120_000L, at.remainingMs)
        assertEquals(0L, at.overdueMs)
        assertEquals(0f, at.fraction, 0.0001f)
    }

    @Test
    fun `halfway through, the bar is half full`() {
        val bench = floor(1, readyAt = 130_000)
        val at = bench.progressAt(70_000)

        assertFalse(at.ready)
        assertEquals(60_000L, at.remainingMs)
        assertEquals(0.5f, at.fraction, 0.0001f)
    }

    @Test
    fun `at exactly nothing left the floor is ready, full and not yet overdue`() {
        val at = floor(1, readyAt = 130_000).progressAt(130_000)

        assertTrue(at.ready)
        assertEquals(0L, at.remainingMs)
        assertEquals(0L, at.overdueMs)
        assertEquals(1f, at.fraction, 0.0001f)
    }

    /** The reason [FloorProgress.overdueMs] exists: "ready" alone is the least useful answer. */
    @Test
    fun `an overrun is reported as time spent ready, and the bar does not grow past full`() {
        val at = floor(1, readyAt = 130_000).progressAt(280_000)

        assertTrue(at.ready)
        assertEquals(0L, at.remainingMs)
        assertEquals(150_000L, at.overdueMs)
        assertEquals("a fraction above one would be a bar leaving its track", 1f, at.fraction, 0.0001f)
    }

    @Test
    fun `a floor with no length at all reads as complete rather than as an empty bar`() {
        val at = floor(1, readyAt = 10_000, orderedMs = 0).progressAt(10_000)

        assertTrue(at.ready)
        assertEquals(1f, at.fraction, 0.0001f)
    }

    // --- the collision rule ------------------------------------------------------------

    @Test
    fun `two floors ready in the same instant do not sound in the same instant`() {
        val floors = listOf(floor(1, "Bench", readyAt = 10_000), floor(2, "Abs", readyAt = 10_000))

        val first = floorCue(floors, conductorRunning = false, now = 10_000)
        assertEquals(1L, first.signal?.exerciseId)
        assertEquals("the second floor waits out the first one's tone", 12_000L, first.wakeAtMs)

        val second = floorCue(first.floors, conductorRunning = false, now = 12_000)
        assertEquals(2L, second.signal?.exerciseId)
        assertNull("nothing left to say", second.wakeAtMs)
    }

    /**
     * Three at once, listed in an order that is not the order they must sound in — the tie
     * is broken by exercise id, so the sequence is the same whatever order the list was
     * built in.
     */
    @Test
    fun `three floors ready together are spaced pairwise and in a fixed order`() {
        val floors = listOf(
            floor(3, "Rows", readyAt = 10_000),
            floor(1, "Bench", readyAt = 10_000),
            floor(2, "Abs", readyAt = 10_000),
        )

        val first = floorCue(floors, conductorRunning = false, now = 10_000)
        assertEquals(1L, first.signal?.exerciseId)
        assertEquals(12_000L, first.wakeAtMs)

        val second = floorCue(first.floors, conductorRunning = false, now = 12_000)
        assertEquals(2L, second.signal?.exerciseId)
        assertEquals(14_000L, second.wakeAtMs)

        val third = floorCue(second.floors, conductorRunning = false, now = 14_000)
        assertEquals(3L, third.signal?.exerciseId)
        assertNull(third.wakeAtMs)
    }

    /**
     * The one case where two floors really are due at the same call: the process overslept
     * by more than the stagger but less than the lateness window. One sounds; the other is
     * re-spaced rather than played on top of it.
     */
    @Test
    fun `an overslept backlog still sounds one floor at a time`() {
        val floors = listOf(floor(1, "Bench", readyAt = 10_000), floor(2, "Abs", readyAt = 10_000))

        val cue = floorCue(floors, conductorRunning = false, now = 12_500)
        assertEquals(1L, cue.signal?.exerciseId)
        assertEquals("re-spaced from now, not from the moment it was owed", 14_500L, cue.wakeAtMs)

        val next = floorCue(cue.floors, conductorRunning = false, now = 14_500)
        assertEquals(2L, next.signal?.exerciseId)
    }

    @Test
    fun `a floor ready well after another is not delayed by it`() {
        val floors = listOf(floor(1, "Bench", readyAt = 10_000), floor(2, "Abs", readyAt = 60_000))

        assertEquals(10_000L, nextFloorSignalMs(floors, conductorRunning = false))
        val first = floorCue(floors, conductorRunning = false, now = 10_000)
        assertEquals("no reason to push a floor half a minute away", 60_000L, first.wakeAtMs)
    }

    // --- the conductor mutes, it does not pause ----------------------------------------

    @Test
    fun `no floor sounds while a conductor is running`() {
        val floors = listOf(floor(1, "Bench", readyAt = 10_000))

        val muted = floorCue(floors, conductorRunning = true, now = 90_000)

        assertNull(muted.signal)
        assertNull("the conductor owns the wake-up while it runs", muted.wakeAtMs)
        assertNull(nextFloorSignalMs(floors, conductorRunning = true))
        assertFalse("muted, so nothing is used up", muted.floors.single().signalled)
    }

    @Test
    fun `a muted floor keeps counting`() {
        val floors = listOf(floor(1, "Bench", readyAt = 130_000))

        val muted = floorCue(floors, conductorRunning = true, now = 70_000)

        assertEquals(60_000L, muted.floors.single().progressAt(70_000).remainingMs)
        assertEquals(0.5f, muted.floors.single().progressAt(70_000).fraction, 0.0001f)
    }

    /**
     * The whole point of the mute: what was held back comes out as one line of text, and
     * NOT as the beeps that were suppressed. A burst of tones after a set is over cannot be
     * told apart from the tone that ended the set.
     */
    @Test
    fun `a floor that matured under a conductor is summarised afterwards and never sounded`() {
        val floors = listOf(floor(1, "Bench", readyAt = 10_000))

        val muted = floorCue(floors, conductorRunning = true, now = 90_000)
        val release = releaseFloors(muted.floors, now = 90_000)

        assertEquals("Bench has been ready for 1:20", floorSummaryText(release.ready))

        val after = floorCue(release.floors, conductorRunning = false, now = 90_000)
        assertNull("summarised is dealt with: no beep after the fact", after.signal)
        assertNull(after.wakeAtMs)
    }

    @Test
    fun `a floor still counting is left alone by the release`() {
        val floors = listOf(floor(1, "Bench", readyAt = 10_000), floor(2, "Abs", readyAt = 200_000))

        val release = releaseFloors(floors, now = 90_000)

        assertEquals(listOf(1L), release.ready.map { it.floor.exerciseId })
        assertFalse(release.floors.first { it.exerciseId == 2L }.signalled)
        assertEquals(200_000L, nextFloorSignalMs(release.floors, conductorRunning = false))
    }

    // --- a floor from a previous boot --------------------------------------------------

    @Test
    fun `a floor started before a reboot counts as ready and is never sounded`() {
        val saved = startFloor(1, "Bench", orderedMs = 120_000, nowElapsed = 500_000, nowWall = WALL)
        // the monotonic clock restarts at the reboot, so wall-minus-monotonic jumps
        val bootRef = bootReference(WALL + 300_000, 30_000)
        assertTrue(saved.isFromPreviousBoot(bootRef))

        val settled = settleFloors(listOf(saved), nowElapsed = 30_000, currentBootRef = bootRef).single()

        assertTrue("its countdown is a reading of a clock that no longer exists", settled.signalled)
        assertTrue(settled.progressAt(30_000).ready)
        assertEquals(0L, settled.progressAt(30_000).overdueMs)
        assertNull(floorCue(listOf(settled), conductorRunning = false, now = 30_000).signal)
        assertNull(nextFloorSignalMs(listOf(settled), conductorRunning = false))
    }

    @Test
    fun `a floor from this boot is taken up untouched`() {
        val bench = startFloor(1, "Bench", orderedMs = 120_000, nowElapsed = 10_000, nowWall = WALL)
        // the same boot, a minute later, with a second of clock drift
        val bootRef = bootReference(WALL + 61_000, 70_000)

        assertFalse(bench.isFromPreviousBoot(bootRef))
        assertEquals(
            listOf(bench),
            settleFloors(listOf(bench), nowElapsed = 70_000, currentBootRef = bootRef),
        )
    }

    // --- too late to be worth a noise --------------------------------------------------

    @Test
    fun `a floor reached an hour late is settled in silence`() {
        val floors = listOf(floor(1, "Bench", readyAt = 10_000))

        val cue = floorCue(floors, conductorRunning = false, now = 10_000 + 3_600_000)

        assertNull("an alarm-volume tone for a rest that ended an hour ago", cue.signal)
        assertTrue("but it is dealt with, not left pending forever", cue.floors.single().signalled)
        assertNull(cue.wakeAtMs)
    }

    @Test
    fun `the lateness window is the same five seconds the step boundary uses`() {
        val floors = listOf(floor(1, "Bench", readyAt = 10_000))

        assertEquals(
            1L,
            floorCue(floors, conductorRunning = false, now = 15_000).signal?.exerciseId,
        )
        assertNull(floorCue(floors, conductorRunning = false, now = 15_001).signal)
    }

    /** Lateness is measured against the STAGGERED moment, which is when it would have sounded. */
    @Test
    fun `a staggered floor is judged late from its own turn, not from when it was ready`() {
        val floors = listOf(floor(1, "Bench", readyAt = 10_000), floor(2, "Abs", readyAt = 10_000))

        // 6 s past readiness is past the window for the first floor, but the second one's
        // turn was only 4 s ago
        val cue = floorCue(floors, conductorRunning = false, now = 16_000)

        assertEquals(2L, cue.signal?.exerciseId)
        assertTrue(cue.floors.first { it.exerciseId == 1L }.signalled)
    }

    // --- the summary line --------------------------------------------------------------

    @Test
    fun `nothing to summarise reads as nothing`() {
        assertNull(floorSummaryText(emptyList()))
    }

    @Test
    fun `several floors share one verb, longest wait first`() {
        val floors = listOf(floor(2, "Abs", readyAt = 60_000), floor(1, "Bench", readyAt = 10_000))

        val release = releaseFloors(floors, now = 90_000)

        assertEquals("Bench has been ready for 1:20, Abs for 0:30", floorSummaryText(release.ready))
    }

    @Test
    fun `a floor that matured this very second drops the duration`() {
        val floors = listOf(floor(1, "Bench", readyAt = 90_000), floor(2, "Abs", readyAt = 60_000))

        val release = releaseFloors(floors, now = 90_000)

        assertEquals("Abs has been ready for 0:30, Bench", floorSummaryText(release.ready))
    }

    @Test
    fun `a lone floor that matured this very second says so plainly`() {
        val release = releaseFloors(listOf(floor(1, "Bench", readyAt = 90_000)), now = 90_000)

        assertEquals("Bench is ready", floorSummaryText(release.ready))
    }

    /** Time already spent rounds down: 1:20.9 has not been 1:21. */
    @Test
    fun `a part second of waiting is not rounded up into the summary`() {
        val release = releaseFloors(listOf(floor(1, "Bench", readyAt = 10_000)), now = 90_900)

        assertEquals(80, release.ready.single().readyForSec())
        assertEquals("Bench has been ready for 1:20", floorSummaryText(release.ready))
    }

    // --- keeping the list honest -------------------------------------------------------

    @Test
    fun `starting a second rest for one exercise replaces the first`() {
        val first = startFloor(1, "Bench", orderedMs = 120_000, nowElapsed = 0, nowWall = WALL)
        val abs = startFloor(2, "Abs", orderedMs = 60_000, nowElapsed = 0, nowWall = WALL)
        val again = startFloor(1, "Bench", orderedMs = 180_000, nowElapsed = 30_000, nowWall = WALL)

        val floors = listOf(first, abs).withFloor(again)

        assertEquals(listOf(2L, 1L), floors.map { it.exerciseId })
        assertEquals(210_000L, floors.first { it.exerciseId == 1L }.readyAtMs)
    }

    @Test
    fun `no floors at all asks for no alarm`() {
        assertNull(nextFloorSignalMs(emptyList(), conductorRunning = false))
        val cue = floorCue(emptyList(), conductorRunning = false, now = 10_000)
        assertNull(cue.signal)
        assertNull(cue.wakeAtMs)
    }
}
