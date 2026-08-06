package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a finished — or abandoned — run into the sets it is worth writing down.
 *
 * The interesting cases are all the ones where the run did NOT go to plan, because a
 * hangboard session that ends exactly as written is the rare one. Getting those wrong is
 * expensive in a way an empty screen is not: the journal is the only record of what was
 * trained, personal records are computed from it, and a set that never happened cannot be
 * told apart from one that did a month later.
 */
class RunLogTest {

    private val hangs = ExerciseRef(
        id = 42, name = "Hangs 20 mm", form = ExerciseForm.HOLD,
        edgeMm = 20.0, workSec = 7.0, restSec = 3.0,
    )

    /** 15 s lead-in, then four sets of six 7:3 hangs with three minutes between them. */
    private val program = programFromExercise(
        exercise = hangs, reps = 6, sets = 4, restBetweenSetsSec = 180, prepareSec = 15,
    )!!

    private val steps = program.flatten()

    private val day = "2026-08-06"

    /*
     * The step list this whole file reasons about, once:
     *   0            the lead-in
     *   1,3,..,11    set 1, six hangs with 3 s between; 12 is its 183 s pause (3 + 180 merged)
     *   13..23       set 2, 24 its pause
     *   25..35       set 3, 36 its pause
     *   37..47       set 4, no pause after the last effort
     */
    @Test
    fun `the fixture is the program this file assumes it is`() {
        assertEquals(48, steps.size)
        assertEquals(StepKind.PREPARE, steps[0].kind)
        assertEquals(24, steps.count { it.kind == StepKind.WORK })
        assertEquals(183, steps[12].durationSec)
        assertEquals(StepKind.WORK, steps[37].kind)
    }

    // --- a run that went to plan ---------------------------------------------------------

    @Test
    fun `a finished run is four sets of six, with the pause between them but not after`() {
        val sets = completedSets(steps, endedAtIndex = steps.lastIndex, finished = true)

        assertEquals(4, sets.size)
        assertEquals(listOf(1, 2, 3, 4), sets.map { it.setNumber })
        assertTrue(sets.all { it.reps == 6 && it.plannedReps == 6 })
        assertTrue(sets.all { it.workSec == 7 })
        // the block's own 3 s and the 180 s between sets are one real pause of 183
        assertEquals(listOf(183, 183, 183), sets.dropLast(1).map { it.restAfterSec })
        assertNull("nothing follows the last set", sets.last().restAfterSec)
    }

    // --- runs that were stopped ----------------------------------------------------------

    @Test
    fun `stopping inside the third set offers two full sets and the part that was done`() {
        // standing on step 29: the third hang of set 3, two of them behind it
        val sets = completedSets(steps, endedAtIndex = 29, finished = false)

        assertEquals(3, sets.size)
        assertEquals(listOf(6, 6, 2), sets.map { it.reps })
        // the short set still says what it was meant to be, so the offer can show "2 of 6"
        assertEquals(6, sets.last().plannedReps)
        // and the pause after it never happened, so it is not invented
        assertNull(sets.last().restAfterSec)
        assertEquals(listOf(183, 183), sets.dropLast(1).map { it.restAfterSec })
    }

    @Test
    fun `stopping during the pause leaves the pause unknown rather than guessed`() {
        // index 24 is the 183 s pause after set 2, still running
        val midPause = completedSets(steps, endedAtIndex = 24, finished = false)
        assertEquals(listOf(6, 6), midPause.map { it.reps })
        assertNull("the pause was cut short, so its length is not a fact", midPause.last().restAfterSec)

        // one step further and the next set has started, so the pause did happen in full
        val afterPause = completedSets(steps, endedAtIndex = 25, finished = false)
        assertEquals(183, afterPause[1].restAfterSec)
    }

    @Test
    fun `stopping during the lead-in offers nothing at all`() {
        assertTrue(completedSets(steps, endedAtIndex = 0, finished = false).isEmpty())
    }

    @Test
    fun `stopping on the very first hang offers nothing, because it is not over yet`() {
        assertTrue(completedSets(steps, endedAtIndex = 1, finished = false).isEmpty())

        // one step on, that hang is behind us and counts
        val one = completedSets(steps, endedAtIndex = 2, finished = false)
        assertEquals(listOf(1), one.map { it.reps })
    }

    @Test
    fun `an empty program produces no sets rather than an empty offer`() {
        assertTrue(completedSets(emptyList(), endedAtIndex = 0, finished = true).isEmpty())
    }

    // --- the outcome the controller hands to the screen ------------------------------------

    private fun snapshot(state: RunState, origin: RunOrigin, exerciseId: Long? = hangs.id) = RunSnapshot(
        programId = 0, programName = program.name, steps = steps, state = state,
        bootRef = 0, exerciseId = exerciseId, origin = origin,
    )

    @Test
    fun `a finished run from an exercise is offered, and knows it was not interrupted`() {
        val state = RunState(stepIndex = steps.lastIndex, running = false, finished = true)

        val outcome = runOutcome(snapshot(state, RunOrigin.EXERCISE), now = 0)

        assertTrue(outcome.offersLogging)
        assertFalse(outcome.interrupted)
        assertEquals(4, outcome.sets.size)
        assertEquals(hangs.id, outcome.exerciseId)
    }

    @Test
    fun `a run stopped by hand is offered too, marked as interrupted`() {
        val state = RunState(stepIndex = 29, running = true, stepEndAtMs = 5_000)

        val outcome = runOutcome(snapshot(state, RunOrigin.EXERCISE), now = 1_000)

        assertTrue(outcome.offersLogging)
        assertTrue(outcome.interrupted)
        assertEquals(listOf(6, 6, 2), outcome.sets.map { it.reps })
    }

    @Test
    fun `a rest between sets is never offered, even though it carries an exercise`() {
        val rest = restProgram(150)
        val restSteps = rest.flatten()
        val snapshot = RunSnapshot(
            programId = 0, programName = rest.name, steps = restSteps,
            state = RunState(stepIndex = 0, running = false, finished = true),
            bootRef = 0, exerciseId = hangs.id, origin = RunOrigin.REST,
        )

        val outcome = runOutcome(snapshot, now = 0)

        // it does expand into one work step, which is exactly why the origin has to be stored
        assertEquals(1, outcome.sets.size)
        assertFalse(outcome.offersLogging)
    }

    @Test
    fun `a plain program is offered too, without knowing which exercise it was`() {
        val state = RunState(stepIndex = steps.lastIndex, running = false, finished = true)

        val outcome = runOutcome(snapshot(state, RunOrigin.PROGRAM, exerciseId = null), now = 0)

        /*
         * The reversal that fixes the lost session: a protocol saved in the editor used to
         * count its sets and offer nothing, purely because it had no exercise attached. The
         * sets are what matter; which exercise they were is a question the offer can ask.
         */
        assertTrue(outcome.offersLogging)
        assertNull(outcome.exerciseId)
        assertEquals(listOf(6, 6, 6, 6), outcome.sets.map { it.reps })
    }

    @Test
    fun `a run that completed nothing is not worth interrupting anyone about`() {
        val state = RunState(stepIndex = 0, running = true, stepEndAtMs = 9_000)

        val outcome = runOutcome(snapshot(state, RunOrigin.EXERCISE), now = 1_000)

        assertTrue(outcome.sets.isEmpty())
        assertFalse(outcome.offersLogging)
    }

    @Test
    fun `an outcome settles a stale state instead of reporting where the run was left`() {
        // the state says "step 1, ending 10 s ago"; by now the program has run itself out
        val state = RunState(stepIndex = 0, running = true, stepEndAtMs = 1_000)

        val outcome = runOutcome(snapshot(state, RunOrigin.EXERCISE), now = 10_000_000)

        assertFalse(outcome.interrupted)
        assertEquals(4, outcome.sets.size)
    }

    // --- WHEN the run ended, as opposed to when anyone found out ---------------------------
    //
    // The offer outlives the process, which means the code that turns a run into an outcome
    // very often runs long after the run itself. Everything below is about that gap. Reading
    // the wall clock at materialisation time was wrong in three visible ways at once: an
    // evening session was filed under the next morning, the offer claimed the run had just
    // ended, and the twenty-four hour cut off measured from the wrong end.

    private val utc: java.time.ZoneId = java.time.ZoneId.of("UTC")

    /** 2026-08-05, 21:40 UTC — an evening hangboard session. */
    private val eveningWallMs = java.time.LocalDateTime.of(2026, 8, 5, 21, 40)
        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `a finished run ended when its last step ended, not when it was noticed`() {
        val endedAt = 900_000L
        val state = RunState(stepIndex = steps.lastIndex, running = true, stepEndAtMs = endedAt)

        // discovered twelve hours later, on the next launch
        assertEquals(endedAt, runEndedAtMs(steps, state, now = endedAt + 12 * 3600_000L))
    }

    @Test
    fun `a run still going ends now, and not at a moment in the future`() {
        val state = RunState(stepIndex = 29, running = true, stepEndAtMs = 5_000)

        // stopped by hand inside a step: its end moment has not arrived and must not be used
        assertEquals(1_000L, runEndedAtMs(steps, state, now = 1_000))
    }

    @Test
    fun `an evening session answered the next morning is filed under the evening`() {
        val endedAt = 900_000L
        val bootRef = eveningWallMs - endedAt
        val snapshot = RunSnapshot(
            programId = 0, programName = program.name, steps = steps,
            state = RunState(stepIndex = steps.lastIndex, running = true, stepEndAtMs = endedAt),
            bootRef = bootRef, exerciseId = hangs.id, origin = RunOrigin.EXERCISE,
        )

        // the process was killed; the outcome is materialised when the phone is unlocked at
        // 09:00 the following day
        val outcome = runOutcome(snapshot, now = endedAt + 11 * 3600_000L + 20 * 60_000L, zone = utc)

        assertEquals("2026-08-05", outcome.opDate)
        assertEquals(eveningWallMs, outcome.endedAtWallMs)
        assertEquals(4, outcome.sets.size)
        // and it knows it is not fresh, which is what puts "this run ended at 21:40" on the
        // offer instead of letting it pretend the session just finished
        assertFalse(outcome.isFresh(eveningWallMs + 11 * 3600_000L))
        assertFalse(outcome.isExpired(eveningWallMs + 11 * 3600_000L))
    }

    @Test
    fun `a snapshot with no boot reference says it does not know when it ended`() {
        val state = RunState(stepIndex = steps.lastIndex, running = false, finished = true)

        val outcome = runOutcome(snapshot(state, RunOrigin.EXERCISE), now = 0, zone = utc)

        // bootRef 0 is "unknown", and an unknown moment is reported as unknown rather than
        // as 1970 or as today
        assertEquals(0L, outcome.endedAtWallMs)
        assertEquals("", outcome.opDate)
        assertTrue(outcome.isFresh(System.currentTimeMillis()))
    }

    // --- a run the device restarted out from under ------------------------------------------

    @Test
    fun `a reboot keeps the sets that were already done and dates them honestly`() {
        /*
         * Set 3, second hang (step 27), saved at the moment that step began. The device then
         * went down; the monotonic clock it was counting against no longer exists, so the run
         * cannot be resumed — but two complete sets happened and they are not in doubt.
         */
        val stepStart = 900_000L
        val state = RunState(
            stepIndex = 27, running = true, stepEndAtMs = stepStart + steps[27].durationMs,
        )
        val snapshot = RunSnapshot(
            programId = 0, programName = program.name, steps = steps, state = state,
            bootRef = eveningWallMs - stepStart, exerciseId = hangs.id, origin = RunOrigin.EXERCISE,
        )

        val outcome = salvagedOutcome(snapshot, zone = utc)

        assertTrue(outcome.offersLogging)
        assertTrue(outcome.interrupted)
        // the two finished sets, and the one hang of set 3 that had already been completed;
        // the hang the run was STANDING ON is not counted - the device could have gone down
        // at any point inside it
        assertEquals(listOf(6, 6, 1), outcome.sets.map { it.reps })
        // dated from the last moment the run is known to have been alive
        assertEquals(eveningWallMs, outcome.endedAtWallMs)
        assertEquals("2026-08-05", outcome.opDate)
    }

    @Test
    fun `a rest between sets is not salvaged across a reboot either`() {
        val rest = restProgram(150)
        val restSteps = rest.flatten()
        val snapshot = RunSnapshot(
            programId = 0, programName = rest.name, steps = restSteps,
            state = RunState(stepIndex = 0, running = true, stepEndAtMs = 150_000),
            bootRef = eveningWallMs, exerciseId = hangs.id, origin = RunOrigin.REST,
        )

        assertFalse(salvagedOutcome(snapshot, zone = utc).offersLogging)
    }

    @Test
    fun `a reboot during the lead-in salvages nothing at all`() {
        val state = RunState(stepIndex = 0, running = true, stepEndAtMs = 15_000)
        val snapshot = RunSnapshot(
            programId = 0, programName = program.name, steps = steps, state = state,
            bootRef = eveningWallMs, exerciseId = hangs.id, origin = RunOrigin.EXERCISE,
        )

        val outcome = salvagedOutcome(snapshot, zone = utc)

        assertTrue(outcome.sets.isEmpty())
        assertFalse("nothing happened, so there is nothing to raise a dialog about", outcome.offersLogging)
    }

    // --- what actually gets written --------------------------------------------------------

    @Test
    fun `the sets become hold sets carrying the exercise identity and the pause`() {
        val sets = completedSets(steps, endedAtIndex = 29, finished = false)

        val written = holdSetsFromRun(hangs, day, sets, addedKg = 8.0)

        assertEquals(3, written.size)
        assertEquals(listOf(6, 6, 2), written.map { it.reps })
        assertTrue(written.all { it.exerciseId == 42L })
        assertTrue(written.all { it.activity == "Hangs 20 mm" })
        // §12-A: edge and protocol come from the exercise and are snapshotted on every set
        assertTrue(written.all { it.edgeMm == 20.0 && it.workSec == 7.0 && it.restSec == 3.0 })
        assertTrue(written.all { it.addedKg == 8.0 && it.ownWeight })
        assertTrue(written.all { it.opDate == day })
        // the pause is written on every set but the last, where there is none
        assertEquals(listOf(183.0, 183.0), written.dropLast(1).map { it.restAfterSec })
        assertNull(written.last().restAfterSec)
    }

    @Test
    fun `a set edited down to zero is not written, and the last live set carries no pause`() {
        val sets = completedSets(steps, endedAtIndex = steps.lastIndex, finished = true)
            .map { if (it.setNumber == 4) it.copy(reps = 0) else it }

        val written = holdSetsFromRun(hangs, day, sets)

        assertEquals(3, written.size)
        assertNull(written.last().restAfterSec)
        // nothing was invented for the weight either
        assertTrue(written.all { it.addedKg == null })
    }

    @Test
    fun `everything edited to zero writes nothing at all`() {
        val sets = completedSets(steps, 29, finished = false).map { it.copy(reps = 0) }
        assertTrue(holdSetsFromRun(hangs, day, sets).isEmpty())
    }

    @Test
    fun `an exercise that is not a hold gets no sets invented for it`() {
        val bench = ExerciseRef(id = 7, name = "Bench press", form = ExerciseForm.STRENGTH)
        val sets = completedSets(steps, endedAtIndex = steps.lastIndex, finished = true)

        assertTrue(holdSetsFromRun(bench, day, sets).isEmpty())
    }

    // --- the snapshot on disk ---------------------------------------------------------------

    @Test
    fun `a run saved by a build that had no origin reads back as a plain program`() {
        // exactly what version 0.3.0 wrote: no "origin" key at all
        val old = """
            {"program_id":0,"program_name":"Rest","steps":[],
             "state":{"step_index":0,"running":true,"step_end_at_ms":1000,
                      "paused_left_ms":0,"finished":false},
             "boot_ref":0,"exercise_id":5}
        """.trimIndent()

        val snapshot = payloadJson.decodeFromString<RunSnapshot>(old)

        // the safe reading: a run of unknown provenance offers nothing
        assertEquals(RunOrigin.PROGRAM, snapshot.origin)
        assertEquals(5L, snapshot.exerciseId)
    }

    @Test
    fun `the origin survives a trip through the stored snapshot`() {
        val original = snapshot(RunState(stepIndex = 3, running = true, stepEndAtMs = 7), RunOrigin.EXERCISE)

        val back = payloadJson.decodeFromString<RunSnapshot>(payloadJson.encodeToString(original))

        assertEquals(RunOrigin.EXERCISE, back.origin)
        assertEquals(original.steps, back.steps)
    }

    // --- the line the offer is read from ---------------------------------------------------

    @Test
    fun `the summary says how many sets and how many efforts in each`() {
        val full = completedSets(steps, steps.lastIndex, finished = true)
        assertEquals("4 sets - 6 + 6 + 6 + 6 efforts of 7 s", runSummaryLine(full))

        val short = completedSets(steps, 29, finished = false)
        assertEquals("3 sets - 6 + 6 + 2 efforts of 7 s", runSummaryLine(short))

        assertEquals("Nothing was completed.", runSummaryLine(emptyList()))
        assertEquals("Nothing was completed.", runSummaryLine(full.map { it.copy(reps = 0) }))
    }
}
