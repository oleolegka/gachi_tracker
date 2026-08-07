package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The arithmetic behind "today stopped being today".
 *
 * The bug this exists for: "today" was read once, when the ViewModel was built, and a phone
 * left in a pocket overnight woke up still believing it was yesterday — so the first set of
 * the morning went into the journal under the wrong date. Only the waiting is testable; the
 * watcher itself is three lines of coroutine around these two functions.
 */
class TodayTest {

    private fun at(text: String) = LocalDateTime.parse(text)

    @Test
    fun `midnight is a whole day away from midnight, never no time at all`() {
        // the spin loop this rules out: a zero wait would make the watcher busy-poll the
        // clock forever, which is the one failure worse than the bug it fixes
        assertEquals(24 * 60 * 60 * 1000L, millisUntilNextDay(at("2026-08-07T00:00:00")))
    }

    @Test
    fun `the wait shrinks as midnight approaches`() {
        assertEquals(60_000L, millisUntilNextDay(at("2026-08-07T23:59:00")))
        assertEquals(1_000L, millisUntilNextDay(at("2026-08-07T23:59:59")))
        assertEquals(2 * 60 * 60 * 1000L, millisUntilNextDay(at("2026-08-07T22:00:00")))
    }

    @Test
    fun `the watcher never sleeps longer than the cap, however far off midnight is`() {
        // a coroutine delay is not a promise about wall-clock time — the device dozes and
        // the process is frozen — so the long sleep is only ever an optimisation
        assertEquals(DAY_WATCH_MAX_MS, dayWatchDelayMs(at("2026-08-07T09:00:00")))
        assertEquals(DAY_WATCH_MAX_MS, dayWatchDelayMs(at("2026-08-07T00:00:00")))
    }

    @Test
    fun `close to midnight the wait is exactly the time left, so the switch is prompt`() {
        assertEquals(1_500L, dayWatchDelayMs(at("2026-08-07T23:59:58.500")))
        assertEquals(30_000L, dayWatchDelayMs(at("2026-08-07T23:59:30")))
    }

    @Test
    fun `the wait is always positive from every second of the day`() {
        var moment = at("2026-08-07T00:00:00")
        val end = at("2026-08-08T00:00:00")
        while (moment.isBefore(end)) {
            assertTrue("a zero wait at $moment would spin", dayWatchDelayMs(moment) > 0)
            moment = moment.plusMinutes(7)
        }
    }
}
