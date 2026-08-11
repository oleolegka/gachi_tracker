package xyz.oleolegka.gachimuchi.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.data.toCatalog
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.doorTiles
import xyz.oleolegka.gachimuchi.domain.toPayload
import xyz.oleolegka.gachimuchi.ui.exerciseEntity

/**
 * What the catalog row hands the analytics, and what happens to the charts when it holds
 * something back.
 *
 * ── Why this is a test and not a two-line mapping nobody checks ─────────────────
 * `toCatalog` used to carry the id, the name and the form and drop the uid, the one-sided
 * flag and the body-weight share. Every one of the three has a default that reproduces the
 * behaviour from before its column existed, which is exactly why dropping them COMPILED, RAN,
 * AND LOOKED FINE — the screens went on answering the old question with the old numbers, and
 * two features shipped into the database were invisible on every chart.
 *
 * So the assertions below are deliberately not "the field was copied". They go through
 * `doorTiles` and pin the number and the wording a person ends up reading, each against the
 * counterfactual of the column being absent. A mapping test that only compared fields would
 * pass just as happily if `doorTiles` stopped consulting them tomorrow.
 */
class CatalogMappingTest {

    private fun journal(vararg forms: ActivityForm): List<JournalEvent> =
        forms.mapIndexed { index, form ->
            JournalEvent(index + 1L, "2026-08-0${index + 1}T10:00:00", 1, 1, form.type, form.toPayload())
        }

    @Test
    fun `the mapping carries every column the analytics reads, identity included`() {
        val row = exerciseEntity(1, "Pull-ups").copy(oneSided = true, bodyweightShare = 0.65)
        val mapped = row.toCatalog()!!

        assertEquals(1L, mapped.id)
        assertEquals("Pull-ups", mapped.name)
        assertEquals(ExerciseForm.STRENGTH, mapped.form)
        assertTrue(mapped.oneSided)
        assertEquals(0.65, mapped.bodyweightShare!!, 1e-9)
        // the link the journal is searched by has to be the identity, not the bare row number:
        // a number means nothing off the phone that handed it out
        assertEquals(row.uid, mapped.link.uid)
    }

    /** An unreadable form code still drops the row rather than crashing the feed. */
    @Test
    fun `a row whose form code means nothing stays out of the feed`() {
        assertNull(exerciseEntity(1, "Nonsense").copy(form = 99).toCatalog())
    }

    /**
     * The feature in one sentence: a week of pull-ups used to draw as a week of doing nothing,
     * because a body-weight set had no weight to multiply the reps by.
     */
    @Test
    fun `a pull-up is worth its share of you once the catalog says what that share is`() {
        val entity = exerciseEntity(1, "Pull-ups").copy(bodyweightShare = 1.0)
        val events = journal(
            StrengthSet(
                exercise = "Pull-ups", reps = 8, ownWeight = true, bodyweightKg = 70.0,
                exerciseId = 1, opDate = "2026-08-01",
            ),
        )

        val tile = doorTiles(events, listOf(entity.toCatalog()!!)).single()
        assertEquals("Volume, reps x weight", tile.series.spec.label)
        assertEquals(560.0, tile.series.last!!.value, 1e-9)

        /*
         * The counterfactual, and the reason the bug was invisible: with the share held back —
         * which is what the mapping did — the same journal falls back to counting reps. A
         * chart, a number, a plausible tile, and the wrong question answered.
         */
        val held = doorTiles(events, listOf(entity.copy(bodyweightShare = null).toCatalog()!!)).single()
        assertEquals("Reps", held.series.spec.label)
        assertEquals(8.0, held.series.last!!.value, 1e-9)
    }

    /**
     * The flag's own job, isolated from the sides on the sets: it decides whether a history that
     * named no hand is read as ONE exercise's record or as one per hand. Without the flag
     * reaching `recordsOf`, the tile would carry the two-handed answer for an exercise the
     * catalog says is trained one hand at a time.
     *
     * The sideless set itself is credited to both hands (the owner's symmetric reading), so what
     * the flag changes here is the label, not the number.
     */
    @Test
    fun `the one-sided flag reaches the records, so a sideless history is read per hand`() {
        val entity = exerciseEntity(2, "One-arm hang", ExerciseForm.HOLD).copy(oneSided = true)
        val events = journal(
            HoldSet(
                activity = "One-arm hang", reps = 3, addedKg = 5.0,
                exerciseId = 2, opDate = "2026-08-01",
            ),
        )

        val flagged = doorTiles(events, listOf(entity.toCatalog()!!)).single()
        assertEquals("added weight 5 kg (left)", flagged.record!!.text)

        val held = doorTiles(events, listOf(entity.copy(oneSided = false).toCatalog()!!)).single()
        assertEquals("added weight 5 kg", held.record!!.text)
    }

    /** And when the sets DO name a hand, the tile's one badge is a hand's record, not the pair's. */
    @Test
    fun `a hang logged per hand keeps the hands apart`() {
        val entity = exerciseEntity(2, "One-arm hang", ExerciseForm.HOLD).copy(oneSided = true)
        val events = journal(
            HoldSet(
                activity = "One-arm hang", reps = 3, addedKg = 5.0, side = "left",
                exerciseId = 2, opDate = "2026-08-01",
            ),
            HoldSet(
                activity = "One-arm hang", reps = 3, addedKg = 12.0, side = "right",
                exerciseId = 2, opDate = "2026-08-02",
            ),
        )

        val tile = doorTiles(events, listOf(entity.toCatalog()!!)).single()
        // a tile has room for one badge and it is the left hand's, not "the exercise's" — the
        // weaker hand is what the training is about, and the better one must not speak for both
        assertEquals("added weight 5 kg (left)", tile.record!!.text)
    }
}
