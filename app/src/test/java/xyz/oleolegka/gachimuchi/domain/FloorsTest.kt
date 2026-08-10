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
    //
    // A floor is one sentence — not before moment T — and it is written down on both clocks,
    // so a restart destroys one copy of it and not the other. These carry it across on the
    // surviving copy. What they must NOT do is what an earlier version of this file did:
    // declare the floor ready because its countdown was unreadable. A reboot can be quicker
    // than the rest it interrupted, and "you may go" said early is the only harm a floor can
    // do.
    //
    // The rest is two minutes and is started 500 s into the boot that later dies.

    private val saved = startFloor(1, "Bench", orderedMs = 120_000, nowElapsed = 500_000, nowWall = WALL)

    /** A floor taken up after a restart, given a monotonic clock [upFor] ms old. */
    private fun carried(wallNow: Long, upFor: Long = 30_000): RestFloor {
        // the monotonic clock restarts with the device, so wall-minus-monotonic jumps
        val bootRef = bootReference(wallNow, upFor)
        assertTrue("this is only a reboot if the boot reference moved", saved.isFromPreviousBoot(bootRef))
        return settleFloors(
            listOf(saved),
            nowElapsed = upFor,
            nowWall = wallNow,
            currentBootRef = bootRef,
        ).single()
    }

    @Test
    fun `a rest that outlived the reboot keeps counting down what is left of it`() {
        // down and back up in forty seconds, so eighty of the two minutes remain
        val settled = carried(wallNow = WALL + 40_000)
        val at = settled.progressAt(30_000)

        assertFalse("declaring this ready would be forty seconds of rest the user never had", at.ready)
        assertEquals(80_000L, at.remainingMs)
        assertEquals(1f / 3f, at.fraction, 0.0001f)
        assertFalse(settled.signalled)
        assertEquals(110_000L, nextFloorSignalMs(listOf(settled), conductorRunning = false))
        assertNull(floorCue(listOf(settled), conductorRunning = false, now = 30_000).signal)
    }

    @Test
    fun `a rest that ran out while the device was off is settled in silence`() {
        // five minutes down against a two minute rest
        val settled = carried(wallNow = WALL + 300_000)
        val at = settled.progressAt(30_000)

        assertTrue(at.ready)
        assertEquals("overdue is measured, not invented", 180_000L, at.overdueMs)

        val cue = floorCue(listOf(settled), conductorRunning = false, now = 30_000)
        assertNull("nobody was there to hear it", cue.signal)
        assertTrue(cue.floors.single().signalled)
        assertNull(cue.wakeAtMs)
    }

    /** The one restart that still deserves a noise: it finished within seconds of the rest. */
    @Test
    fun `a rest that ran out during the last seconds of the reboot still sounds`() {
        val settled = carried(wallNow = WALL + 123_000)

        assertEquals(27_000L, settled.readyAtMs)
        assertEquals(1L, floorCue(listOf(settled), conductorRunning = false, now = 30_000).signal?.exerciseId)
    }

    /**
     * The wall clock is the one clock that can be moved. Set back ten minutes, it would say
     * twelve minutes are left of a two minute rest; the remainder is capped at the length that
     * was ordered instead, because a countdown growing past its own length is unreadable and
     * resting a few seconds short is the cheaper error.
     */
    @Test
    fun `a wall clock moved backwards cannot stretch a rest past its ordered length`() {
        val settled = carried(wallNow = WALL - 600_000)
        val at = settled.progressAt(30_000)

        assertEquals(120_000L, at.remainingMs)
        assertEquals(0f, at.fraction, 0.0001f)
    }

    @Test
    fun `a floor already sounded before the reboot stays sounded, with a truthful overrun`() {
        val settled = saved.copy(signalled = true)
            .carriedAcrossReboot(
                nowElapsed = 30_000,
                nowWall = WALL + 300_000,
                currentBootRef = bootReference(WALL + 300_000, 30_000),
            )

        assertTrue(settled.signalled)
        assertEquals(180_000L, settled.progressAt(30_000).overdueMs)
    }

    /**
     * Carried floors land where they actually matured rather than at the instant of the
     * restart, so a spent one does not push a live one out of its slot.
     */
    @Test
    fun `a floor carried across a restart does not delay a live one`() {
        val spent = carried(wallNow = WALL + 300_000)
        val live = floor(2, "Abs", readyAt = 30_500)

        val cue = floorCue(listOf(spent, live), conductorRunning = false, now = 30_000)

        assertNull(cue.signal)
        assertEquals("not pushed to 32 500 by a floor that matured before the boot", 30_500L, cue.wakeAtMs)
    }

    @Test
    fun `a floor from this boot is taken up untouched`() {
        val bench = startFloor(1, "Bench", orderedMs = 120_000, nowElapsed = 10_000, nowWall = WALL)
        // the same boot, a minute later, with a second of clock drift
        val bootRef = bootReference(WALL + 61_000, 70_000)

        assertFalse(bench.isFromPreviousBoot(bootRef))
        assertEquals(
            "within a boot the monotonic clock is authoritative and the wall clock is ignored",
            listOf(bench),
            settleFloors(
                listOf(bench),
                nowElapsed = 70_000,
                nowWall = WALL + 61_000,
                currentBootRef = bootRef,
            ),
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

    /**
     * The whole reason [RestFloor.side] exists: a one-sided exercise's left and right card each
     * get their own countdown, so marking the right hand's set must not stop the left hand's
     * still-running rest — which is exactly what happened before a floor was keyed by
     * (exerciseId, side) rather than by exerciseId alone.
     */
    @Test
    fun `two cards of one exercise keep two independent floors`() {
        val left = startFloor(1, "Hangs - Left", orderedMs = 120_000, nowElapsed = 0, nowWall = WALL, side = "left")
        val right = startFloor(1, "Hangs - Right", orderedMs = 60_000, nowElapsed = 0, nowWall = WALL, side = "right")

        val floors = listOf(left).withFloor(right)

        assertEquals("marking the right hand did not touch the left hand's rest", 2, floors.size)
        assertEquals(120_000L, floors.first { it.side == "left" }.readyAtMs)
        assertEquals(60_000L, floors.first { it.side == "right" }.readyAtMs)

        // a second right-hand set still replaces only the right hand's floor
        val rightAgain =
            startFloor(1, "Hangs - Right", orderedMs = 90_000, nowElapsed = 30_000, nowWall = WALL, side = "right")
        val settled = floors.withFloor(rightAgain)

        assertEquals(2, settled.size)
        assertEquals("untouched by the right hand's new set", 120_000L, settled.first { it.side == "left" }.readyAtMs)
        assertEquals(120_000L, settled.first { it.side == "right" }.readyAtMs)
    }

    @Test
    fun `no floors at all asks for no alarm`() {
        assertNull(nextFloorSignalMs(emptyList(), conductorRunning = false))
        val cue = floorCue(emptyList(), conductorRunning = false, now = 10_000)
        assertNull(cue.signal)
        assertNull(cue.wakeAtMs)
    }

    // --- the rest a floor actually measured ---------------------------------------------

    @Test
    fun `the actual rest is the wall-clock gap since the floor started`() {
        val rest = floor(1, readyAt = 120_000).actualRestSec(nowWallMs = WALL + 90_000)
        assertEquals(90.0, rest!!, 1e-9)
    }

    /** Standing at the doorway of the rest for two and a half minutes is not a rest of zero. */
    @Test
    fun `a set recorded the instant the floor started reads as zero rest, not no rest`() {
        val rest = floor(1, readyAt = 120_000).actualRestSec(nowWallMs = WALL)
        assertEquals(0.0, rest!!, 1e-9)
    }

    /**
     * The same line [MAX_REST_SEC] draws for the DERIVED gap ([secondsBetween]): past twenty
     * minutes this is a break in the workout, not a rest between sets of one exercise, however
     * exactly the wall clock can measure it.
     */
    @Test
    fun `right at twenty minutes it is still a rest, one second later it is a break`() {
        val atTheLine = floor(1, readyAt = 120_000).actualRestSec(nowWallMs = WALL + (MAX_REST_SEC * 1000).toLong())
        assertEquals(MAX_REST_SEC, atTheLine!!, 1e-9)

        val overTheLine = floor(1, readyAt = 120_000)
            .actualRestSec(nowWallMs = WALL + (MAX_REST_SEC * 1000).toLong() + 1_000)
        assertNull("an hour standing around is a break, not a rest, however exactly it is measured", overTheLine)
    }

    /**
     * The wall clock is the one clock a user or an NTP sync can move (see the note at the top
     * of this file). A jump backwards between the floor starting and the next set landing must
     * not be reported as a negative rest — there is nothing this reading can honestly say.
     */
    @Test
    fun `a wall clock that jumped backwards reads as no measurement, not a negative rest`() {
        val rest = floor(1, readyAt = 120_000).actualRestSec(nowWallMs = WALL - 5_000)
        assertNull(rest)
    }
}
