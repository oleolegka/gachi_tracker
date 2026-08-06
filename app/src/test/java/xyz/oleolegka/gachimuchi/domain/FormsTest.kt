package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Form tests: validation (the same rules as in `bot/domain.py`) and payload
 * compatibility with the server JSON — sync will one day ride on that payload, so the
 * key names are asserted explicitly rather than left to "however it serializes".
 */
class FormsTest {

    @Test
    fun `normalization folds case and punctuation`() {
        assertEquals("bench press", normPhrase("Bench Press"))
        assertEquals("hangs 20 mm 7 3", normPhrase("Hangs 20 mm · 7:3"))
        assertNull(normPhrase("   "))
        assertNull(normPhrase(null))
        // Cyrillic YO folds to YE, kept for parity with the bot; escapes keep this file ASCII
        assertEquals("\u0435", normPhrase("\u0401"))
    }

    @Test
    fun `strength set validates reps and weight compatibility`() {
        assertThrows(IllegalArgumentException::class.java) {
            StrengthSet(exercise = "bench", reps = 0, weightKg = 60.0, opDate = "2026-08-06")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrengthSet(exercise = "bench", reps = 5, weightKg = 60.0, ownWeight = true, opDate = "2026-08-06")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrengthSet(exercise = "pull-ups", reps = 5, addedKg = 10.0, opDate = "2026-08-06")
        }
        // added weight on top of body weight is legal
        StrengthSet(exercise = "pull-ups", reps = 5, addedKg = 10.0, ownWeight = true, opDate = "2026-08-06")
    }

    @Test
    fun `work rest protocol is only accepted as a pair`() {
        assertThrows(IllegalArgumentException::class.java) {
            HoldSet(activity = "hangs", workSec = 7.0, opDate = "2026-08-06")
        }
        HoldSet(activity = "hangs", workSec = 7.0, restSec = 3.0, opDate = "2026-08-06")
    }

    @Test
    fun `cardio requires at least one metric`() {
        assertThrows(IllegalArgumentException::class.java) {
            Cardio(activity = "running", opDate = "2026-08-06")
        }
        Cardio(activity = "running", distanceM = 5000.0, opDate = "2026-08-06")
    }

    @Test
    fun `op_date format is validated`() {
        assertThrows(IllegalArgumentException::class.java) {
            Tick(activity = "stretching", opDate = "06.08.2026")
        }
    }

    @Test
    fun `payload keys match the server snake_case`() {
        val set = StrengthSet(
            exercise = "Bench press", reps = 5, weightKg = 62.5, exerciseId = 3,
            restAfterSec = 150.0, opDate = "2026-08-06",
        )
        val json = set.toJsonObject()
        assertTrue(json.keys.containsAll(
            listOf("exercise", "reps", "weight_kg", "added_kg", "own_weight",
                "exercise_id", "rest_after_sec", "op_date", "exercise_key")
        ))
        assertEquals("bench press", set.exerciseKey)
    }

    @Test
    fun `a payload written by the Python bot is read without loss`() {
        // the string exactly as bot-domain writes it (asdict of the form)
        val payload = """
            {"exercise":"Bench press","reps":5,"weight_kg":62.5,"added_kg":null,
             "own_weight":false,"exercise_id":3,"rest_after_sec":150.0,
             "op_date":"2026-08-06","exercise_key":"bench press"}
        """.trimIndent()
        val form = formFromEvent(TYPE_STRENGTH_SET, payload) as StrengthSet
        assertEquals(62.5, form.weightKg!!, 1e-9)
        assertEquals(5, form.reps)
        assertEquals(3L, form.exerciseId)
        assertEquals("2026-08-06", form.opDate)
    }

    @Test
    fun `unknown payload keys do not break reading`() {
        val payload = """{"activity":"stretching","op_date":"2026-08-06","activity_key":"stretching","rpe":7}"""
        val form = formFromEvent(TYPE_TICK, payload) as Tick
        assertEquals("stretching", form.activity)
    }

    @Test
    fun `a form round-trips through the journal without losing fields`() {
        val hold = HoldSet(
            activity = "Hangs 20 mm · 7:3", reps = 5, workSec = 7.0, restSec = 3.0,
            edgeMm = 20.0, addedKg = 12.0, ownWeight = true, exerciseId = 7,
            restAfterSec = 180.0, opDate = "2026-08-06",
        )
        val back = formFromEvent(TYPE_HOLD_SET, hold.toPayload())
        assertEquals(hold, back)
    }

    @Test
    fun `form codes match the Python flow FORM_ constants`() {
        assertEquals(1, ExerciseForm.STRENGTH.code)
        assertEquals(2, ExerciseForm.HOLD.code)
        assertEquals(3, ExerciseForm.CARDIO.code)
        assertEquals(4, ExerciseForm.DURATION.code)
        assertEquals(5, ExerciseForm.TICK.code)
        assertEquals(6, ExerciseForm.BODYWEIGHT.code)
        assertEquals(ExerciseForm.HOLD, ExerciseForm.fromCode(2))
    }
}
