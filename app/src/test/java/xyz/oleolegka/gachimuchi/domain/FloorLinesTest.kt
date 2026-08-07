package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sentence the rests say in the notification shade.
 *
 * Worth a test of its own rather than being checked through a Robolectric notification,
 * because everything that can go wrong with it goes wrong at the seams — an empty list, a
 * floor maturing in the same millisecond it is drawn, a countdown rounded the wrong way — and
 * none of those is visible in an assertion about a Notification object.
 */
class FloorLinesTest {

    private fun floor(id: Long, name: String, readyAtMs: Long) = RestFloor(
        exerciseId = id,
        exerciseName = name,
        readyAtMs = readyAtMs,
        bootRef = 0,
        orderedMs = 120_000,
        startedAtWallMs = 0,
    )

    @Test
    fun `no floors is no line at all, rather than an empty one`() {
        assertNull(floorNotificationLine(emptyList(), now = 0))
    }

    @Test
    fun `one still counting reads as a countdown`() {
        val line = floorNotificationLine(listOf(floor(1, "Bench", 80_000)), now = 0)
        assertEquals("Bench 1:20", line)
    }

    /** A countdown rounds UP, the way every countdown in this app displays: 79.4 s is 1:20. */
    @Test
    fun `a part second still to wait is a whole second on the line`() {
        val line = floorNotificationLine(listOf(floor(1, "Bench", 79_400)), now = 0)
        assertEquals("Bench 1:20", line)
    }

    @Test
    fun `one that is over reads as ready, with no duration attached`() {
        val line = floorNotificationLine(listOf(floor(1, "Bench", 10_000)), now = 130_000)
        assertEquals("Bench ready", line)
    }

    /** The boundary belongs to "ready": the floor's own rule is `now >= readyAtMs`. */
    @Test
    fun `the exact moment of readiness is already ready`() {
        assertEquals("Bench ready", floorNotificationLine(listOf(floor(1, "Bench", 5_000)), now = 5_000))
    }

    /**
     * The whole point of the line: what may be done now comes before what is still being
     * waited for, whatever order the list happens to be in.
     */
    @Test
    fun `the ready ones lead, the counting ones follow`() {
        val line = floorNotificationLine(
            listOf(floor(2, "Abs", 90_000), floor(1, "Bench", 5_000)),
            now = 10_000,
        )
        assertEquals("Bench ready · Abs 1:20", line)
    }

    /**
     * Two rests started in the same millisecond must not swap places between two redraws a
     * second apart, so the exercise id breaks the tie the same way the domain's signal order
     * does.
     */
    @Test
    fun `floors ready at the same moment keep a stable order`() {
        val line = floorNotificationLine(
            listOf(floor(9, "Curl", 5_000), floor(2, "Abs", 5_000)),
            now = 10_000,
        )
        assertEquals("Abs ready · Curl ready", line)
    }

    @Test
    fun `several counting are ordered by which comes first`() {
        val line = floorNotificationLine(
            listOf(floor(1, "Bench", 200_000), floor(2, "Abs", 65_000)),
            now = 0,
        )
        assertEquals("Abs 1:05 · Bench 3:20", line)
    }

    // --- the button ---------------------------------------------------------------------

    @Test
    fun `nothing ready means no button`() {
        val floors = listOf(floor(1, "Bench", 90_000))
        assertEquals(emptyList<RestFloor>(), readyFloors(floors, now = 0))
        assertNull(dismissReadyLabel(readyFloors(floors, now = 0)))
    }

    @Test
    fun `one ready rest is named on the button`() {
        val floors = listOf(floor(1, "Bench", 10_000), floor(2, "Abs", 90_000))
        assertEquals("Dismiss Bench", dismissReadyLabel(readyFloors(floors, now = 20_000)))
    }

    /**
     * Several ready rests are NOT named, because the button clears all of them and naming one
     * of several would promise something it does not do.
     */
    @Test
    fun `several ready rests share one unnamed button`() {
        val floors = listOf(floor(1, "Bench", 10_000), floor(2, "Abs", 15_000))
        val ready = readyFloors(floors, now = 20_000)
        assertEquals(listOf(1L, 2L), ready.map { it.exerciseId })
        assertEquals("Dismiss ready", dismissReadyLabel(ready))
    }
}
