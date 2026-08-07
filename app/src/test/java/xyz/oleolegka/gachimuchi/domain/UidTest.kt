package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class UidTest {

    @Test
    fun `a fresh uid is shaped like a version 7 uuid`() {
        assertTrue(isUid(newUid()))
    }

    @Test
    fun `two uids minted in the same millisecond are still different`() {
        val at = 1_800_000_000_000L
        val many = (1..1000).map { newUid(atMillis = at) }
        assertEquals("uids collided within one millisecond", many.size, many.toSet().size)
    }

    /**
     * THE PROPERTY THE WHOLE CHOICE OF v7 RESTS ON. If plain string order stopped being time
     * order, a merged journal would need a separate sort key and an index over these would
     * scatter its writes; both are the kind of thing that is discovered much later.
     */
    @Test
    fun `later uids sort after earlier ones as plain strings`() {
        val start = 1_700_000_000_000L
        val ordered = (0 until 200).map { newUid(atMillis = start + it * 37L) }
        assertEquals(ordered, ordered.sorted())
    }

    @Test
    fun `the timestamp survives at millisecond resolution`() {
        // the same millisecond twice, with different randomness, has to share its leading bits
        val at = 1_755_123_456_789L
        val a = newUid(atMillis = at, random = Random(1))
        val b = newUid(atMillis = at, random = Random(2))
        // "xxxxxxxx-xxxx" is the 48-bit timestamp, hyphen included
        assertEquals(a.substring(0, 13), b.substring(0, 13))
        // and one millisecond later is a different prefix
        assertTrue(newUid(atMillis = at + 1, random = Random(1)).substring(0, 13) > a.substring(0, 13))
    }

    @Test
    fun `the version and variant nibbles are the ones RFC 9562 asks for`() {
        for (seed in 0 until 50) {
            val uid = newUid(atMillis = 1_700_000_000_000L, random = Random(seed))
            assertEquals("version nibble of $uid", '7', uid[14])
            assertTrue("variant nibble of $uid", uid[19] in "89ab")
        }
    }

    @Test
    fun `things that are not uids are not mistaken for them`() {
        assertFalse(isUid(""))
        assertFalse(isUid("42"))
        // a v4 uuid: right shape, wrong version, and it would not sort by time
        assertFalse(isUid("0f8fad5b-d9cb-469f-a165-70867728950e"))
        // upper case is not what this app writes, and accepting it would let two spellings of
        // one id past the unique index
        assertFalse(isUid(newUid().uppercase()))
    }
}
