package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The arithmetic of the celebration: when to show something, and which picture.
 *
 * The randomness is pinned with a seeded [Random], so "picks one of the pool" is a real
 * assertion here rather than a coin flip that fails once a month on CI.
 */
class CelebrationTest {

    private fun picture(id: String, forRecords: Boolean = false) =
        CelebrationPicture(id = id, forRecords = forRecords, addedAt = "2026-08-06")

    @Test
    fun `off shows nothing, records-only shows records, every-set shows everything`() {
        assertFalse(shouldCelebrate(CelebrationMode.OFF, isRecord = false))
        assertFalse(shouldCelebrate(CelebrationMode.OFF, isRecord = true))

        assertFalse(shouldCelebrate(CelebrationMode.RECORDS_ONLY, isRecord = false))
        assertTrue(shouldCelebrate(CelebrationMode.RECORDS_ONLY, isRecord = true))

        assertTrue(shouldCelebrate(CelebrationMode.EVERY_SET, isRecord = false))
        assertTrue(shouldCelebrate(CelebrationMode.EVERY_SET, isRecord = true))
    }

    @Test
    fun `an unknown stored mode falls back instead of throwing`() {
        assertEquals(CelebrationMode.EVERY_SET, CelebrationMode.fromCode(2))
        assertEquals(CelebrationMode.RECORDS_ONLY, CelebrationMode.fromCode(42))
        assertEquals(CelebrationMode.RECORDS_ONLY, CelebrationMode.fromCode(-1))
    }

    @Test
    fun `an empty gallery picks nothing`() {
        assertNull(pickPicture(emptyList(), isRecord = false))
        assertNull(pickPicture(emptyList(), isRecord = true))
    }

    @Test
    fun `a record draws from the starred ones and an ordinary set from the rest`() {
        val pictures = listOf(
            picture("plain-a"),
            picture("starred-a", forRecords = true),
            picture("plain-b"),
            picture("starred-b", forRecords = true),
        )
        repeat(50) { seed ->
            val record = pickPicture(pictures, isRecord = true, random = Random(seed))
            assertTrue("a record must come from the starred pool", record!!.forRecords)
            val ordinary = pickPicture(pictures, isRecord = false, random = Random(seed))
            assertFalse("an ordinary set must not eat the starred pool", ordinary!!.forRecords)
        }
    }

    @Test
    fun `with nothing starred both pools are the whole gallery`() {
        val pictures = listOf(picture("a"), picture("b"), picture("c"))
        val seen = mutableSetOf<String>()
        repeat(50) { seed ->
            seen += pickPicture(pictures, isRecord = true, random = Random(seed))!!.id
            seen += pickPicture(pictures, isRecord = false, random = Random(seed))!!.id
        }
        assertEquals(setOf("a", "b", "c"), seen)
    }

    @Test
    fun `with everything starred an ordinary set still gets a picture`() {
        val pictures = listOf(picture("a", forRecords = true), picture("b", forRecords = true))
        val ordinary = pickPicture(pictures, isRecord = false, random = Random(7))
        assertTrue(ordinary!!.id in setOf("a", "b"))
    }

    @Test
    fun `a single starred picture is what records get and what sets fall back to`() {
        val pictures = listOf(picture("only", forRecords = true))
        assertEquals("only", pickPicture(pictures, isRecord = true, random = Random(1))!!.id)
        assertEquals("only", pickPicture(pictures, isRecord = false, random = Random(1))!!.id)
    }

    /**
     * Every form the user DOES is celebrated, and the weigh-in is the only one that is not.
     *
     * Duration and cardio were outside this until 2026-08-14, on the argument that a stretch is
     * filed as a total rather than a set — a distinction the model makes and the person training
     * does not. It came back from a phone as a bug report, so both are named here explicitly
     * rather than left to the shape of the check.
     */
    @Test
    fun `everything done is celebrated, a weigh-in is not`() {
        val strength = StrengthSet(exercise = "Squat", reps = 5, weightKg = 100.0, opDate = "2026-08-06")
        val hold = HoldSet(activity = "Hang", holdSec = 10.0, opDate = "2026-08-06")
        val weighIn = Bodyweight(weightKg = 80.0, opDate = "2026-08-06")
        val tick = Tick(activity = "Stretching", opDate = "2026-08-06")
        val stretch = Duration(activity = "Stretching", durationSec = 300, opDate = "2026-08-06")
        val run = Cardio(activity = "Run", distanceM = 5000.0, opDate = "2026-08-06")

        assertTrue(celebratedByPicture(strength))
        assertTrue(celebratedByPicture(hold))
        assertTrue("a check-in is a thing done, same as a set", celebratedByPicture(tick))
        assertTrue("reported from the phone, 2026-08-14", celebratedByPicture(stretch))
        assertTrue("cardio had no ground to stand on that duration did not", celebratedByPicture(run))
        assertFalse("stepping on the scales is not a thing done", celebratedByPicture(weighIn))
    }
}
