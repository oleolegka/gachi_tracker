package xyz.oleolegka.gachimuchi.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.StrengthSet

/**
 * The three decisions taken before a set block is drawn, which is where they belong: a table
 * cannot make them, and the screen that used to make none of them printed the protocol once per
 * set and the same set twice.
 */
class SetTableTest {

    private val iso = "2026-08-07"

    private fun bench(kg: Double, reps: Int = 5, restAfter: Double? = null, warmup: Boolean = false) =
        StrengthSet(
            exercise = "Bench press", reps = reps, weightKg = kg, opDate = iso,
            restAfterSec = restAfter, warmup = warmup,
        )

    private fun hang(kg: Double, protocol: Pair<Double, Double>? = null) = HoldSet(
        activity = "Hangs", reps = 5, holdSec = 7.0, addedKg = kg, opDate = iso,
        workSec = protocol?.first, restSec = protocol?.second,
    )

    @Test
    fun `identical neighbours collapse and the single one gets no count`() {
        val table = setTable(listOf(bench(60.0), bench(60.0), bench(62.5)), restSec = null)

        assertEquals(2, table.rows.size)
        assertEquals(2, table.rows[0].count)
        assertEquals("60 kg", table.rows[0].load)
        assertEquals("5 reps", table.rows[0].volume)
        // one set on a line is one line, and "1×" on it would be the repetition we are leaving
        assertEquals(1, table.rows[1].count)
        assertEquals("62.5 kg", table.rows[1].load)
    }

    /**
     * The reason collapsing stops at the neighbour. "60, 62.5, 60" is a session that backed off
     * and "2× 60, 62.5" is one that went up; grouping across the card would print the second
     * where the first happened, which is a different training day.
     */
    @Test
    fun `sets that are equal but not adjacent stay apart`() {
        val table = setTable(listOf(bench(60.0), bench(62.5), bench(60.0)), restSec = null)

        assertEquals(listOf("60 kg", "62.5 kg", "60 kg"), table.rows.map { it.load })
        assertEquals(listOf(1, 1, 1), table.rows.map { it.count })
    }

    @Test
    fun `a protocol every set shares is said once, above the rows`() {
        val table = setTable(listOf(hang(7.5, 7.0 to 3.0), hang(5.0, 7.0 to 3.0)), restSec = null)

        assertEquals("7:3 protocol", table.commonProtocol)
        // and nowhere else: it was printed as many times as there were sets
        assertEquals(listOf("", ""), table.rows.map { it.note })
    }

    @Test
    fun `sets that disagree about the protocol each carry their own`() {
        val table = setTable(listOf(hang(7.5, 7.0 to 3.0), hang(7.5, 10.0 to 5.0)), restSec = null)

        assertNull(table.commonProtocol)
        assertEquals(listOf("7:3 protocol", "10:5 protocol"), table.rows.map { it.note })
    }

    /**
     * One value, one place (rule 4). The rest chosen for the exercise is printed on its button;
     * a row repeating it was the same number twice on one card, in two formats.
     */
    @Test
    fun `a rest equal to the card's own is not printed on the row, and a different one is`() {
        val table = setTable(
            listOf(bench(60.0, restAfter = 120.0), bench(62.5, restAfter = 180.0)),
            restSec = 120,
        )

        assertEquals("", table.rows[0].note)
        assertEquals("rest 3:00", table.rows[1].note)
    }

    /** Two sets that differ only in a flag are two lines: one of them is not a working set. */
    @Test
    fun `a warm-up does not collapse into the working set beside it`() {
        val table = setTable(listOf(bench(60.0, warmup = true), bench(60.0)), restSec = null)

        assertEquals(2, table.rows.size)
        assertEquals(true, table.rows[0].warmup)
        assertEquals(false, table.rows[1].warmup)
    }
}
