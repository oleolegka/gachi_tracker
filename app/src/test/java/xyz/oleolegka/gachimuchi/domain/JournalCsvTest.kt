package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * domain/JournalCsv.kt: the CSV is the app's own view of the journal ([readActivities]) laid
 * out as a table, and every test here pins one clause of that.
 *
 * The header, spelled out once, so a test reading a cell by column index says which column it
 * means without repeating the number everywhere.
 */
class JournalCsvTest {

    private var nextId = 1L

    private fun ev(form: ActivityForm, ts: String = "2026-08-06T10:00:00", workoutUid: String? = null) =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload(), workoutUid = workoutUid)

    private fun catalogRow(id: Long, uid: String, name: String, form: ExerciseForm) =
        CatalogRow(id = id, uid = uid, name = name, form = form.code, createdAt = "2026-01-01T00:00:00")

    /** The CSV, split into its header and its data rows (still raw text, not un-escaped). */
    private fun lines(events: List<JournalEvent>, catalog: List<CatalogRow> = emptyList()): List<String> =
        journalCsv(events, catalog).trim('﻿', '\n').split("\n")

    /** A cell by column NAME rather than a bare index that drifts the moment a column moves. */
    private fun col(name: String): Int = CSV_HEADER.indexOf(name).also { assertTrue("no such column: $name", it >= 0) }

    @Test
    fun `the header names every column`() {
        val header = lines(emptyList()).single()
        assertEquals(
            "date,workout,exercise,form,side,weight_kg,added_kg,own_weight,reps,hold_sec," +
                "rest_after_sec,warmup,bodyweight_kg,duration_sec,distance_m,pace_sec_per_km",
            header,
        )
    }

    @Test
    fun `an empty journal is the header and nothing else`() {
        assertEquals(1, lines(emptyList()).size)
    }

    @Test
    fun `a strength set fills the strength columns and leaves the rest blank`() {
        val set = StrengthSet(
            exercise = "Bench press", reps = 5, weightKg = 62.5, exerciseId = 1, opDate = "2026-08-06",
        )
        val cells = lines(listOf(ev(set)))[1].split(",")

        assertEquals("2026-08-06", cells[col("date")])
        assertEquals("", cells[col("workout")])
        assertEquals("Bench press", cells[col("exercise")])
        assertEquals("Strength", cells[col("form")])
        assertEquals("", cells[col("side")])
        assertEquals("62.5", cells[col("weight_kg")])
        assertEquals("", cells[col("added_kg")])
        assertEquals("false", cells[col("own_weight")])
        assertEquals("5", cells[col("reps")])
        assertEquals("", cells[col("hold_sec")])
        assertEquals("false", cells[col("warmup")])
        assertEquals("", cells[col("bodyweight_kg")])
        assertEquals("", cells[col("duration_sec")])
    }

    @Test
    fun `a hold set carries its side, its hold time and its signed added weight`() {
        val hold = HoldSet(
            activity = "One-arm hang", holdSec = 10.0, addedKg = -15.0, ownWeight = true,
            side = HoldSide.LEFT.code, exerciseId = 2, opDate = "2026-08-06",
        )
        val cells = lines(listOf(ev(hold)))[1].split(",")

        assertEquals("Holds", cells[col("form")])
        assertEquals("left", cells[col("side")])
        assertEquals("10.0", cells[col("hold_sec")])
        assertEquals("-15.0", cells[col("added_kg")])
        assertEquals("true", cells[col("own_weight")])
        // a hold set has no absolute weight_kg field at all - not "0", blank
        assertEquals("", cells[col("weight_kg")])
    }

    @Test
    fun `a body weight entry has no exercise, and the number lands in bodyweight_kg`() {
        val weighIn = Bodyweight(weightKg = 74.2, opDate = "2026-08-06")
        val cells = lines(listOf(ev(weighIn)))[1].split(",")

        assertEquals("Body weight", cells[col("form")])
        assertEquals("", cells[col("exercise")])
        assertEquals("74.2", cells[col("bodyweight_kg")])
    }

    @Test
    fun `cardio fills distance, duration and pace, and nothing else`() {
        val run = Cardio(activity = "Run", distanceM = 5000.0, durationSec = 1500, paceSecPerKm = 300.0, opDate = "2026-08-06")
        val cells = lines(listOf(ev(run)))[1].split(",")

        assertEquals("Cardio", cells[col("form")])
        assertEquals("5000.0", cells[col("distance_m")])
        assertEquals("1500", cells[col("duration_sec")])
        assertEquals("300.0", cells[col("pace_sec_per_km")])
        assertEquals("", cells[col("reps")])
    }

    // --- what the app shows, not the raw log ---

    @Test
    fun `a deleted entry does not get a row`() {
        val set = ev(StrengthSet(exercise = "Squats", reps = 5, weightKg = 80.0, opDate = "2026-08-06"))
        val deletion = JournalEvent(
            nextId++, "2026-08-06T11:00:00", 1, 1, TYPE_ENTRY_DELETED,
            payloadJson.encodeToString(EntryDeleted(set.uid)),
        )
        assertEquals(1, lines(listOf(set, deletion)).size) // header only
    }

    @Test
    fun `an amended entry is one row, carrying the correction`() {
        val set = ev(StrengthSet(exercise = "Squats", reps = 5, weightKg = 80.0, opDate = "2026-08-06"))
        val amendment = JournalEvent(
            nextId++, "2026-08-06T11:00:00", 1, 1, TYPE_ENTRY_AMENDED,
            payloadJson.encodeToString(
                EntryAmended(set.uid, kotlinx.serialization.json.JsonObject(mapOf("reps" to JsonPrimitive(8))))
            ),
        )
        val data = lines(listOf(set, amendment)).drop(1)
        assertEquals(1, data.size)
        assertEquals("8", data.single().split(",")[col("reps")])
    }

    @Test
    fun `every entry of a deleted exercise disappears with it`() {
        val set = ev(StrengthSet(exercise = "Deadlift", reps = 5, weightKg = 100.0, exerciseId = 9, opDate = "2026-08-06"))
        val gone = JournalEvent(
            nextId++, "2026-08-06T11:00:00", 1, 1, TYPE_EXERCISE_DELETED,
            payloadJson.encodeToString(ExerciseDeleted(targetId = 9)),
        )
        assertEquals(1, lines(listOf(set, gone)).size)
    }

    // --- exercise names: the catalog's current name wins over the payload's own ---

    @Test
    fun `a renamed exercise is shown under its current catalog name`() {
        val set = ev(
            StrengthSet(exercise = "Squats", reps = 5, weightKg = 80.0, exerciseId = 3, exerciseUid = "ex-3", opDate = "2026-08-06")
        )
        val catalog = listOf(catalogRow(3, "ex-3", "Back squat", ExerciseForm.STRENGTH))
        val cells = lines(listOf(set), catalog)[1].split(",")
        assertEquals("Back squat", cells[col("exercise")])
    }

    @Test
    fun `an unresolvable exercise link falls back to the name the entry itself carries`() {
        val set = ev(StrengthSet(exercise = "Squats", reps = 5, weightKg = 80.0, exerciseId = 3, opDate = "2026-08-06"))
        val cells = lines(listOf(set), emptyList())[1].split(",")
        assertEquals("Squats", cells[col("exercise")])
    }

    // --- workouts ---

    @Test
    fun `an entry outside any workout leaves the workout column blank`() {
        val set = ev(StrengthSet(exercise = "Squats", reps = 5, weightKg = 80.0, opDate = "2026-08-06"))
        assertEquals("", lines(listOf(set))[1].split(",")[col("workout")])
    }

    @Test
    fun `an entry of a named workout carries that name`() {
        val start = JournalEvent(
            nextId++, "2026-08-06T09:00:00", 1, 1, TYPE_WORKOUT_STARTED,
            payloadJson.encodeToString(WorkoutStarted(opDate = "2026-08-06", name = "Evening gym")),
        )
        val set = ev(
            StrengthSet(exercise = "Squats", reps = 5, weightKg = 80.0, opDate = "2026-08-06"),
            ts = "2026-08-06T09:05:00", workoutUid = start.uid,
        )
        assertEquals("Evening gym", lines(listOf(start, set))[1].split(",")[col("workout")])
    }

    @Test
    fun `an entry of an unnamed workout reads as Workout, the app's own fallback`() {
        val start = JournalEvent(
            nextId++, "2026-08-06T09:00:00", 1, 1, TYPE_WORKOUT_STARTED,
            payloadJson.encodeToString(WorkoutStarted(opDate = "2026-08-06")),
        )
        val set = ev(
            StrengthSet(exercise = "Squats", reps = 5, weightKg = 80.0, opDate = "2026-08-06"),
            ts = "2026-08-06T09:05:00", workoutUid = start.uid,
        )
        assertEquals("Workout", lines(listOf(start, set))[1].split(",")[col("workout")])
    }

    // --- ordering and escaping ---

    @Test
    fun `rows are ordered by the day trained, not by the day written`() {
        val backdated = ev(
            StrengthSet(exercise = "A", reps = 1, weightKg = 1.0, opDate = "2026-08-01"),
            ts = "2026-08-06T09:00:00",
        )
        val recent = ev(
            StrengthSet(exercise = "B", reps = 1, weightKg = 1.0, opDate = "2026-08-05"),
            ts = "2026-08-02T09:00:00",
        )
        val data = lines(listOf(backdated, recent)).drop(1)
        assertEquals("A", data[0].split(",")[col("exercise")])
        assertEquals("B", data[1].split(",")[col("exercise")])
    }

    /**
     * THE regression this pins: within one day, rows used to be ordered by row id — a stand-in
     * for "written in this order" that a correction breaks, since it writes a whole new row at
     * the end of the journal (domain/Amendments.kt) whenever the fix happens to be made.
     */
    @Test
    fun `a set corrected long after the fact keeps its place among that day's rows`() {
        val first = ev(
            StrengthSet(exercise = "A", reps = 1, weightKg = 1.0, opDate = "2026-08-06"),
            ts = "2026-08-06T09:00:00",
        )
        val second = ev(
            StrengthSet(exercise = "B", reps = 1, weightKg = 1.0, opDate = "2026-08-06"),
            ts = "2026-08-06T09:05:00",
        )
        // a typo in the FIRST set, fixed a week later
        val fixed = JournalEvent(
            nextId++, "2026-08-13T12:00:00", 1, 1, TYPE_STRENGTH_SET,
            StrengthSet(exercise = "A", reps = 3, weightKg = 1.0, opDate = "2026-08-06").toPayload(),
            occurredTs = first.ts,
        )
        val marker = JournalEvent(
            nextId++, fixed.ts, 1, 1, TYPE_ENTRY_DELETED,
            payloadJson.encodeToString(EntryDeleted(targetUid = first.uid, successorUid = fixed.uid)),
        )

        val data = lines(listOf(first, second, fixed, marker)).drop(1)
        assertEquals("A", data[0].split(",")[col("exercise")])
        assertEquals("B", data[1].split(",")[col("exercise")])
    }

    @Test
    fun `a name with a comma and a quote is quoted and escaped, not corrupted`() {
        val set = ev(StrengthSet(exercise = "Row, \"strict\"", reps = 5, weightKg = 40.0, opDate = "2026-08-06"))
        val row = lines(listOf(set))[1]

        // the embedded comma and quote are inside one quoted field, not split into extra cells -
        // a naive split(",") would see 17 pieces instead of the 16 real columns
        assertEquals(CSV_HEADER.size, rowFieldCount(row))
        assertEquals("Row, \"strict\"", cellsOf(row)[col("exercise")])
    }

    /** Splits a CSV row the way a real reader would: commas inside quotes do not separate cells. */
    private fun cellsOf(row: String): List<String> {
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == '"' && inQuotes && i + 1 < row.length && row[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells.add(current.toString()); current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }

    private fun rowFieldCount(row: String): Int = cellsOf(row).size

    @Test
    fun `numbers use a decimal point regardless of sign or size`() {
        val hold = HoldSet(activity = "Band-assisted", addedKg = -7.5, holdSec = 12.0, opDate = "2026-08-06")
        val cells = lines(listOf(ev(hold)))[1].split(",")
        assertFalse(cells[col("added_kg")].contains(','))
        assertEquals("-7.5", cells[col("added_kg")])
    }
}
