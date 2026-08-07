package xyz.oleolegka.gachimuchi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.oleolegka.gachimuchi.domain.payloadJson
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The two facts every journal row carries about time and day, computed in ONE place so that no
 * writer can produce a row with half of them.
 *
 * See [xyz.oleolegka.gachimuchi.data.db.EventEntity.tsUtc] for why a local time with no zone
 * was not enough, and [xyz.oleolegka.gachimuchi.data.db.EventEntity.opDate] for why the day is
 * a column now.
 */

/** Second precision, no zone — the shape `events.ts` has always been written in. */
private val LOCAL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/**
 * Second precision, in UTC, with the Z spelled out.
 *
 * DELIBERATELY NOT `Instant.toString()`, which is ISO_INSTANT and drops the seconds when they
 * happen to be zero ("...T07:00Z"). The column is compared and sorted as TEXT, so a
 * variable-width format would put one row in fifty in the wrong place, and only ever on the
 * minute — the kind of defect that is found years later.
 */
private val UTC_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("Z"))

/** The shape an op_date is stored in; anything else is not a day and is not indexed as one. */
private val ISO_DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")

/**
 * One moment, said three ways: the local wall clock it was written by, the instant it actually
 * was, and how far apart those two are.
 *
 * [offsetMin] is not redundant with the other two. It is what makes the local reading
 * RECONSTRUCTIBLE from the instant — and it is the answer to "was this logged in Moscow or in
 * Bangkok", which is a fact about a training journal that travels with its owner.
 */
data class WriteTime(val local: String, val utc: String, val offsetMin: Int) {

    companion object {
        fun of(at: ZonedDateTime): WriteTime = WriteTime(
            local = at.toLocalDateTime().format(LOCAL_FORMAT),
            utc = UTC_FORMAT.format(at.toInstant()),
            // the offset AT THAT MOMENT, not the zone's current one: a summer row in a zone
            // that observes daylight saving was written at a different offset from a winter one
            offsetMin = at.offset.totalSeconds / 60,
        )

        fun now(zone: ZoneId = ZoneId.systemDefault()): WriteTime = of(ZonedDateTime.now(zone))

        /**
         * The same three readings for a local time that was recorded WITHOUT a zone, resolved in
         * [zone] — or null when the string will not parse as a local time at all.
         *
         * ── The assumption, stated where it is made ─────────────────────────────
         * There is no second source. A row written before the zone was recorded says
         * "2026-08-06T10:00:00" and nothing else; the only zone anybody can offer is the one the
         * device is in NOW. That is right for the overwhelmingly common case — a journal written
         * at home and read at home — and wrong for exactly the rows this change exists to
         * describe, the ones written abroad. Those get today's offset stamped on a moment that
         * had a different one, and there is no way to tell from here.
         *
         * It is still better than the alternative. Leaving them empty would mean every reader
         * carries a null branch forever for rows that DO have a defensible instant, and the
         * error is bounded by how far the user travels, not unbounded.
         *
         * A gap or an overlap in the zone's own rules (the hour that does not exist when the
         * clocks go forward) is resolved by `atZone`'s ordinary rules rather than refused: a set
         * logged at half past two on that night is a real set, and the hour it is filed under
         * matters less than it existing.
         */
        fun ofLocal(ts: String, zone: ZoneId = ZoneId.systemDefault()): WriteTime? =
            runCatching { of(LocalDateTime.parse(ts).atZone(zone)) }.getOrNull()

        /** The three readings of an instant that is already known, in [zone]. */
        fun ofInstant(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): WriteTime =
            of(instant.atZone(zone))
    }
}

/**
 * The day a stored payload says its entry belongs to, or null when it names none.
 *
 * Every activity form carries `op_date`, and so does `workout_started`; the reversing and
 * correcting events do not, and null is the honest answer for them — a deletion happened on the
 * day it was written and belongs to no training day of its own.
 *
 * A value that is not an ISO day is treated as ABSENT rather than stored. The column is an index
 * and a filter, and a row whose day is rubbish must fall through to the payload readers (which
 * will refuse it) instead of being rejected by a comparison against nonsense.
 */
fun opDateOfPayload(payload: String): String? {
    val json = runCatching { payloadJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject
        ?: return null
    val day = (json["op_date"] as? JsonPrimitive)?.contentOrNull ?: return null
    return day.takeIf { ISO_DAY.matches(it) }
}
