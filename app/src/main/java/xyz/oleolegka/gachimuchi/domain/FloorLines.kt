package xyz.oleolegka.gachimuchi.domain

/**
 * What the rest floors say in one line, for a notification shade.
 *
 * Separate from domain/Floors.kt, which is the arithmetic of when a rest may make a noise and
 * is deliberately left alone: this file decides nothing, it only phrases. Both live in the
 * domain and not in timer/TimerNotifications.kt for the same reason the rest of the timer
 * does — a sentence built by hand out of a list is exactly the sort of thing that reads fine
 * in review and comes out as "Bench ready · " on the one day the list has a hole in it, and
 * that is worth a JVM test rather than a phone.
 *
 * The wording is chosen to survive being read at arm's length, upside down, in a shade full
 * of other apps: the exercise name first, because that is what is being looked for, and the
 * state after it.
 */

/**
 * What separates two floors on the line.
 *
 * A middle dot rather than a comma: the names themselves may contain commas ("Bench, close
 * grip" is a perfectly ordinary exercise name), and a separator that can also appear inside
 * an item is not a separator.
 */
const val FLOOR_LINE_SEPARATOR = " · "

/**
 * "Bench ready · Abs 1:20", or null when there are no floors at all.
 *
 * ── Ready first, then the ones still counting ───────────────────────────────────
 * The line is read to answer one question — what may I do now — and the floors that answer
 * it are the ready ones. Within each half the order is the order the floors matured or will
 * mature in, with the exercise id breaking a tie, so the line does not reshuffle itself
 * between two redraws a second apart the way a list in arbitrary order would.
 *
 * ── A ready floor is not given a duration here ─────────────────────────────────
 * The screen says "ready for 1:20" and so does [floorSummaryText]; this does not, because a
 * duration next to a name on this line is ambiguous in the one way that matters — "Abs 1:20"
 * already means one minute twenty still to wait, and the same shape meaning its opposite two
 * items earlier is a line that has to be decoded rather than read.
 *
 * [now] is a monotonic reading, the same clock [RestFloor.readyAtMs] is expressed in.
 */
fun floorNotificationLine(floors: List<RestFloor>, now: Long): String? {
    if (floors.isEmpty()) return null
    val (ready, counting) = floors
        .sortedWith(compareBy({ it.readyAtMs }, { it.exerciseId }))
        .partition { now >= it.readyAtMs }
    val parts = ready.map { "${it.exerciseName} ready" } +
        counting.map { "${it.exerciseName} ${formatClock(ceilSeconds(it.readyAtMs - now))}" }
    return parts.joinToString(FLOOR_LINE_SEPARATOR)
}

/**
 * The floors whose rest is over at [now], in the order they became ready.
 *
 * Named for the rests being over rather than for their being ready, because domain/Floors.kt
 * already has a [ReadyFloor] and it means something narrower: one floor that matured while a
 * conductor had it muted, carrying how long ago that was. A `readyFloors` sitting in the same
 * package as a `ReadyFloor` and returning neither is a name that has to be checked against its
 * signature before it can be read.
 */
fun restsOver(floors: List<RestFloor>, now: Long): List<RestFloor> = floors
    .filter { now >= it.readyAtMs }
    .sortedWith(compareBy({ it.readyAtMs }, { it.exerciseId }))

/**
 * The label of the notification button that clears every rest that is over, or null when
 * none is and the button therefore has nothing to do.
 *
 * One names it, several do not. The button does the same thing either way — clears all of
 * them — so naming the only one is a description and not a promise about which of many would
 * go, which is the mistake the plural form exists to avoid.
 */
fun dismissLabel(over: List<RestFloor>): String? = when (over.size) {
    0 -> null
    1 -> "Dismiss ${over.single().exerciseName}"
    else -> "Dismiss ready"
}
