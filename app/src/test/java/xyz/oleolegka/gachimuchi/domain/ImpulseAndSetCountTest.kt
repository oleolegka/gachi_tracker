package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two volumes an isometric exercise can have — IMPULSE where the inputs exist and a set
 * count where they do not — and the working-set tally, which is the only total a week of
 * barbell work and a week of hangs are allowed to share.
 *
 * The arithmetic is asserted against hand-computed numbers rather than against the
 * implementation's own expression, because "time under tension excludes the pauses" is
 * precisely the kind of statement that a test written from the code would agree with while
 * being wrong.
 */
class ImpulseAndSetCountTest {

    private var nextId = 1L

    private fun ev(form: ActivityForm, ts: String = "2026-08-06T10:00:00") =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload())

    /** A repeater set: [reps] hangs of [holdSec] seconds each, on a 7:3 protocol. */
    private fun hang(
        day: String,
        reps: Int? = 6,
        holdSec: Double? = 7.0,
        bodyweightKg: Double? = 70.0,
        addedKg: Double? = null,
        warmup: Boolean = false,
        protocol: Boolean = true,
        id: Long = 2,
    ) = ev(
        HoldSet(
            activity = "Hangs 20 mm", reps = reps, holdSec = holdSec,
            workSec = if (protocol) 7.0 else null, restSec = if (protocol) 3.0 else null,
            addedKg = addedKg, ownWeight = true, bodyweightKg = bodyweightKg, warmup = warmup,
            exerciseId = id, opDate = day,
        )
    )

    private fun acts(events: List<JournalEvent>) = readActivities(events)

    private val hangs = ExerciseLink.ofId(2)

    // --- the impulse of one set --------------------------------------------------------

    @Test
    fun `impulse is load times the seconds actually hung, and the pauses do not count`() {
        // 6 hangs of 7 s = 42 s under tension. The 3 s pauses of the 7:3 protocol are 18 s of
        // the set's wall-clock time and none of its work.
        val set = HoldSet(
            activity = "Hangs 20 mm", reps = 6, holdSec = 7.0, workSec = 7.0, restSec = 3.0,
            addedKg = 8.0, ownWeight = true, bodyweightKg = 70.0, opDate = "2026-08-01",
        )
        assertEquals(42.0, holdSecondsUnderTension(set)!!, 1e-9)
        assertEquals((70.0 + 8.0) * 42.0, holdImpulseKgSec(set)!!, 1e-9)
    }

    @Test
    fun `without a recorded hold length the protocol's work half is used`() {
        val fromRun = HoldSet(
            activity = "Hangs 20 mm", reps = 5, workSec = 10.0, restSec = 5.0,
            ownWeight = true, bodyweightKg = 60.0, opDate = "2026-08-01",
        )
        assertEquals(50.0, holdSecondsUnderTension(fromRun)!!, 1e-9)
        assertEquals(3000.0, holdImpulseKgSec(fromRun)!!, 1e-9)
    }

    @Test
    fun `a set that counts no hangs is one hang, and one with no time at all has no impulse`() {
        val maxHang = HoldSet(
            activity = "One-arm hang", holdSec = 12.0, ownWeight = true, bodyweightKg = 70.0,
            opDate = "2026-08-01",
        )
        assertEquals(12.0, holdSecondsUnderTension(maxHang)!!, 1e-9)
        assertEquals(840.0, holdImpulseKgSec(maxHang)!!, 1e-9)

        val timeless = HoldSet(
            activity = "Hangs", reps = 4, ownWeight = true, bodyweightKg = 70.0,
            opDate = "2026-08-01",
        )
        assertNull(holdSecondsUnderTension(timeless))
        assertNull(holdImpulseKgSec(timeless))
    }

    @Test
    fun `assistance is subtracted, and more help than you weigh is nothing rather than negative`() {
        val assisted = HoldSet(
            activity = "Hangs 20 mm", reps = 2, holdSec = 10.0, addedKg = -15.0,
            ownWeight = true, bodyweightKg = 70.0, opDate = "2026-08-01",
        )
        assertEquals((70.0 - 15.0) * 20.0, holdImpulseKgSec(assisted)!!, 1e-9)

        val overHelped = assisted.copy(addedKg = -80.0)
        assertEquals(0.0, holdImpulseKgSec(overHelped)!!, 1e-9)
    }

    @Test
    fun `a set logged before anybody weighed themselves has no impulse, not a zero one`() {
        val unweighed = HoldSet(
            activity = "Hangs 20 mm", reps = 6, holdSec = 7.0, ownWeight = true,
            opDate = "2026-08-01",
        )
        assertNull(holdImpulseKgSec(unweighed))
    }

    // --- the hold volume series --------------------------------------------------------

    @Test
    fun `the hold volume is the impulse of the day`() {
        val events = listOf(
            hang("2026-08-01", addedKg = 8.0),
            hang("2026-08-01"),
            hang("2026-08-03", addedKg = 10.0),
        )
        val series = volumeSeries(acts(events), hangs, ExerciseForm.HOLD)!!
        assertEquals("Impulse", series.spec.label)
        // the unit is the FORMAT's now, not a suffix on the label
        assertEquals(ValueFormat.KILOGRAM_SECONDS, series.spec.format)
        assertEquals(Aggregation.SUM, series.spec.aggregation)
        assertEquals(listOf("2026-08-01", "2026-08-03"), series.points.map { it.opDate })
        assertEquals(78.0 * 42 + 70.0 * 42, series.points[0].value, 1e-9)
        assertEquals(80.0 * 42, series.points[1].value, 1e-9)
    }

    @Test
    fun `a longer hang is more volume than a shorter one, which counting sets could not say`() {
        val short = volumeSeries(acts(listOf(hang("2026-08-01", holdSec = 5.0))), hangs, ExerciseForm.HOLD)!!
        val long = volumeSeries(acts(listOf(hang("2026-08-01", holdSec = 50.0))), hangs, ExerciseForm.HOLD)!!
        assertTrue(long.points.single().value > short.points.single().value)
    }

    @Test
    fun `a history with no body weight anywhere still counts sets`() {
        val events = listOf(
            hang("2026-08-01", bodyweightKg = null),
            hang("2026-08-01", bodyweightKg = null),
            hang("2026-08-02", bodyweightKg = null),
        )
        val series = volumeSeries(acts(events), hangs, ExerciseForm.HOLD)!!
        assertEquals("Sets", series.spec.label)
        assertEquals(listOf(2.0, 1.0), series.points.map { it.value })
    }

    @Test
    fun `in a mixed history the sets with no snapshot contribute nothing, and that is an understatement`() {
        val events = listOf(
            hang("2026-08-01", bodyweightKg = null),
            hang("2026-08-02"),
        )
        val series = volumeSeries(acts(events), hangs, ExerciseForm.HOLD)!!
        assertEquals("Impulse", series.spec.label)
        // the unit is the FORMAT's now, not a suffix on the label
        assertEquals(ValueFormat.KILOGRAM_SECONDS, series.spec.format)
        // the day of the unweighed hang is a real day with real work on it and draws as zero.
        // Stated rather than corrected: the same trade the strength branch makes for a set
        // whose load cannot be computed
        assertEquals(0.0, series.points[0].value, 1e-9)
        assertEquals(70.0 * 42, series.points[1].value, 1e-9)
    }

    @Test
    fun `warm-up hangs are no part of the impulse`() {
        val events = listOf(
            hang("2026-08-01", warmup = true, addedKg = -20.0),
            hang("2026-08-01", addedKg = 8.0),
        )
        val series = volumeSeries(acts(events), hangs, ExerciseForm.HOLD)!!
        assertEquals(78.0 * 42, series.points.single().value, 1e-9)
    }

    @Test
    fun `kilograms and kilogram-seconds are never the same series`() {
        val lifting = ev(
            StrengthSet(
                exercise = "Bench press", reps = 5, weightKg = 80.0, exerciseId = 1,
                opDate = "2026-08-01",
            )
        )
        val hanging = hang("2026-08-01", addedKg = 8.0)
        val tonnage = volumeSeries(acts(listOf(lifting)), ExerciseLink.ofId(1), ExerciseForm.STRENGTH)!!
        val impulse = volumeSeries(acts(listOf(hanging)), hangs, ExerciseForm.HOLD)!!

        assertEquals(ValueFormat.KILOGRAMS, tonnage.spec.format)
        // a kind of its own, and specifically NOT kilograms: nothing may put these two on one
        // axis or add them into one bar, and the type is what carries that rule
        assertEquals(ValueFormat.KILOGRAM_SECONDS, impulse.spec.format)
        assertNotEquals(tonnage.spec.format, impulse.spec.format)
        assertNotEquals(tonnage.spec.label, impulse.spec.label)
    }

    // --- the working-set tally ---------------------------------------------------------

    @Test
    fun `working sets are counted across forms, per exercise and in total`() {
        val events = listOf(
            hang("2026-08-01"),
            hang("2026-08-01"),
            ev(
                StrengthSet(
                    exercise = "Bench press", reps = 5, weightKg = 80.0, exerciseId = 1,
                    opDate = "2026-08-01",
                )
            ),
        )
        val tally = workingSetTally(events)
        assertEquals(3, tally.total)
        assertEquals(listOf("Hangs 20 mm", "Bench press"), tally.byExercise.map { it.exercise.name })
        assertEquals(listOf(2, 1), tally.byExercise.map { it.sets })
        assertEquals(listOf(2L, 1L), tally.byExercise.map { it.exercise.exerciseId })
    }

    @Test
    fun `warm-ups, weigh-ins and runs are not working sets`() {
        val events = listOf(
            hang("2026-08-01", warmup = true),
            hang("2026-08-01"),
            ev(
                StrengthSet(
                    exercise = "Bench press", reps = 5, weightKg = 40.0, warmup = true,
                    exerciseId = 1, opDate = "2026-08-01",
                )
            ),
            ev(Bodyweight(weightKg = 70.0, opDate = "2026-08-01")),
            ev(
                Cardio(
                    activity = "Running", distanceM = 5000.0, exerciseId = 3,
                    opDate = "2026-08-01",
                )
            ),
            ev(Tick(activity = "Stretching", exerciseId = 4, opDate = "2026-08-01")),
        )
        val tally = workingSetTally(events)
        assertEquals(1, tally.total)
        assertEquals("Hangs 20 mm", tally.byExercise.single().exercise.name)
    }

    @Test
    fun `the window is inclusive of both ends`() {
        val events = listOf(
            hang("2026-07-31"),
            hang("2026-08-01"),
            hang("2026-08-03"),
            hang("2026-08-04"),
        )
        assertEquals(3, workingSetTally(events, "2026-08-01", "2026-08-04").total)
        assertEquals(2, workingSetTally(events, "2026-08-01", "2026-08-03").total)
        assertEquals(4, workingSetTally(events).total)
    }

    @Test
    fun `a set that was taken back stops being counted`() {
        val set = hang("2026-08-01")
        val deletion = JournalEvent(
            id = 900, ts = "2026-08-01T11:00:00", spaceId = 1, authorId = 1,
            type = TYPE_ENTRY_DELETED,
            payload = payloadJson.encodeToString(EntryDeleted(targetUid = set.uid)),
        )
        assertEquals(1, workingSetTally(listOf(set)).total)
        assertEquals(0, workingSetTally(listOf(set, deletion)).total)
    }
}
