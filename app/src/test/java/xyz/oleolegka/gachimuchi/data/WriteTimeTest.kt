package xyz.oleolegka.gachimuchi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * The whole point of the time columns, in one sentence: A MOSCOW RECORD AND A BANGKOK RECORD
 * MUST NOT LOOK THE SAME.
 *
 * These are plain JVM tests with the zone passed in explicitly, so they assert the behaviour
 * rather than whatever zone the machine running them happens to be in.
 */
class WriteTimeTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val bangkok = ZoneId.of("Asia/Bangkok")

    @Test
    fun `one wall clock in two zones is two moments, and the row now says which`() {
        val ts = "2026-08-06T10:00:00"
        val here = WriteTime.ofLocal(ts, moscow)!!
        val there = WriteTime.ofLocal(ts, bangkok)!!

        // the reading the user had in front of them is the same, and stays the same
        assertEquals(ts, here.local)
        assertEquals(ts, there.local)
        // and everything that has to be ordered or subtracted now sees two different moments
        assertNotEquals(here.utc, there.utc)
        assertEquals("2026-08-06T07:00:00Z", here.utc)
        assertEquals("2026-08-06T03:00:00Z", there.utc)
        assertEquals(180, here.offsetMin)
        assertEquals(420, there.offsetMin)
    }

    @Test
    fun `a session either side of a flight sorts by when it happened, not by the clock`() {
        // half past eight in Bangkok, then half past six the same evening back home: the local
        // clocks say the second one came first, and the instants say otherwise
        val abroad = WriteTime.ofLocal("2026-08-06T20:30:00", bangkok)!!
        val home = WriteTime.ofLocal("2026-08-06T18:30:00", moscow)!!

        assertEquals(true, abroad.local > home.local)
        assertEquals("the instant is the one that is right", true, abroad.utc < home.utc)
    }

    /**
     * The instant is fixed width. `Instant.toString()` is not — it drops the seconds when they
     * are zero — and a column compared as TEXT cannot afford that.
     */
    @Test
    fun `the instant keeps its seconds even when they are zero`() {
        val onTheMinute = WriteTime.ofLocal("2026-08-06T10:00:00", moscow)!!
        assertEquals("2026-08-06T07:00:00Z", onTheMinute.utc)
        assertEquals(20, onTheMinute.utc.length)
        // and text ordering agrees with time ordering across the boundary it would break on
        val aSecondEarlier = WriteTime.ofLocal("2026-08-06T09:59:59", moscow)!!
        assertEquals(true, aSecondEarlier.utc < onTheMinute.utc)
    }

    @Test
    fun `the offset is the one that was in force, not the zone's answer for today`() {
        // Berlin is +2 in the summer and +1 in the winter; a row must carry the one it was
        // written at, or applying it to the instant gives back the wrong wall clock
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(120, WriteTime.ofLocal("2026-07-01T12:00:00", berlin)!!.offsetMin)
        assertEquals(60, WriteTime.ofLocal("2026-01-01T12:00:00", berlin)!!.offsetMin)
    }

    @Test
    fun `a timestamp that will not parse is null rather than a guess`() {
        assertNull(WriteTime.ofLocal("", moscow))
        assertNull(WriteTime.ofLocal("yesterday evening", moscow))
        assertNull(WriteTime.ofLocal("2026-08-06", moscow))
    }

    // --- the day out of the payload ----------------------------------------------------

    @Test
    fun `the day is read off the payload, and only when it is a day`() {
        val set = """{"exercise":"Bench press","reps":5,"op_date":"2026-08-01"}"""
        assertEquals("2026-08-01", opDateOfPayload(set))

        // a reversal is about an event and not about a training day
        assertNull(opDateOfPayload("""{"cancels":17}"""))
        // an amendment names no day of its own: the day it writes is inside its patch
        assertNull(opDateOfPayload("""{"target_uid":"x","fields":{"op_date":"2026-08-01"}}"""))
        // and nothing that is not an ISO day reaches the column
        assertNull(opDateOfPayload("""{"op_date":"1 August"}"""))
        assertNull(opDateOfPayload("""{"op_date":null}"""))
        assertNull(opDateOfPayload("{not json"))
        assertNull(opDateOfPayload("[]"))
    }
}
