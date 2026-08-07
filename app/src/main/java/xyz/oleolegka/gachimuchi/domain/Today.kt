package xyz.oleolegka.gachimuchi.domain

import java.time.LocalDateTime

/**
 * When "today" stops being today.
 *
 * ── Why this is a function and not `LocalDate.now()` at start-up ────────────────
 * It used to be exactly that: one reading, taken when the ViewModel was built, handed to
 * every screen. A phone that sits in a pocket overnight does not rebuild the ViewModel, so
 * the app woke up on Tuesday still believing it was Monday — and since the day the screens
 * showed was also the day sets were filed under, the first set of the morning was recorded
 * against yesterday. Silently, in the one record the app exists to keep honest.
 *
 * The fix is a watcher that re-reads the clock, and the only part of it worth testing is
 * the arithmetic: how long to wait before looking again.
 */

/**
 * How long the day watcher sleeps between readings.
 *
 * Deliberately CAPPED rather than "sleep until midnight and wake up exactly then". A
 * coroutine `delay` on Android is not a promise about wall-clock time — the device dozes,
 * the process is frozen, and a sleep timed to end at 00:00 can come back minutes or hours
 * late. Polling every minute costs nothing (a date comparison) and is correct however the
 * long sleep behaves, so the cap is the belt and the countdown is the braces: near midnight
 * the wait shortens to exactly the time left, so the switch is prompt when the phone is awake.
 */
const val DAY_WATCH_MAX_MS: Long = 60_000L

/**
 * Milliseconds from [now] until the next midnight.
 *
 * Never zero: at exactly 00:00:00.000 the answer is a whole day, not "no time at all". A
 * zero would turn the watcher into a spin loop, which is the one failure mode worse than
 * the bug it exists to fix.
 */
fun millisUntilNextDay(now: LocalDateTime): Long {
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
    return java.time.Duration.between(now, nextMidnight).toMillis()
}

/** How long to wait before checking the date again — see [DAY_WATCH_MAX_MS]. */
fun dayWatchDelayMs(now: LocalDateTime, maxMs: Long = DAY_WATCH_MAX_MS): Long =
    minOf(maxMs, millisUntilNextDay(now)).coerceAtLeast(1L)
