package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The countdown, with the clock supplied by the test rather than by the device.
 *
 * Every case here is one that only shows up on a real phone in a pocket: the process
 * frozen across several step boundaries, the app killed and rebuilt from disk, the phone
 * rebooted while a rest was running. On a device each of them takes a workout to
 * reproduce and leaves no trace when it goes wrong.
 */
class RunnerTest {

    /** Tabata-shaped: 10 s prepare, then 20 s work / 10 s rest three times. */
    private val steps: List<WorkoutStep> = WorkoutProgram(
        name = "test",
        prepareSec = 10,
        groups = listOf(
            ProgramGroup(
                name = "g",
                blocks = listOf(ProgramBlock("Work", workSec = 20, restSec = 10, repeats = 3)),
            )
        ),
    ).flatten()

    /** 10 + 20 + 10 + 20 + 10 + 20, with the trailing rest dropped. */
    private val totalMs = 90_000L

    private val t0 = 1_000_000L

    @Test
    fun `starting puts the first step's end on the clock, not a countdown in a field`() {
        val state = startRun(steps, t0)
        assertTrue(state.running)
        assertEquals(0, state.stepIndex)
        assertEquals(t0 + 10_000, state.stepEndAtMs)
        assertEquals(10_000, stepRemainingMs(steps, state, t0))
        assertEquals(totalMs, totalRemainingMs(steps, state, t0))
    }

    @Test
    fun `remaining time falls with the clock while nothing runs at all`() {
        val state = startRun(steps, t0)
        assertEquals(7_000, stepRemainingMs(steps, state, t0 + 3_000))
        assertEquals(totalMs - 3_000, totalRemainingMs(steps, state, t0 + 3_000))
    }

    @Test
    fun `an empty program is finished rather than a run with no steps`() {
        val state = startRun(emptyList(), t0)
        assertTrue(state.finished)
        assertEquals(0, stepRemainingMs(emptyList(), state, t0))
    }

    // --- catching up after the process was frozen or killed ---------------------------

    @Test
    fun `settling walks forward over every boundary that passed while nothing was running`() {
        val state = startRun(steps, t0)
        // 45 s later: prepare (10) and the first work (20) and the first rest (10) are done,
        // and we are 5 s into the second work step
        val settled = settleRun(steps, state, t0 + 45_000)

        assertEquals(3, settled.stepIndex)
        assertEquals(StepKind.WORK, steps[settled.stepIndex].kind)
        assertTrue(settled.running)
        assertEquals(15_000, stepRemainingMs(steps, settled, t0 + 45_000))
    }

    @Test
    fun `catching up lands exactly where an uninterrupted run would have, with no drift`() {
        var stepwise = startRun(steps, t0)
        // advance one second at a time, the way a live run does
        for (second in 1..45) stepwise = settleRun(steps, stepwise, t0 + second * 1000L)
        val atOnce = settleRun(steps, startRun(steps, t0), t0 + 45_000)

        assertEquals(atOnce.stepIndex, stepwise.stepIndex)
        assertEquals(atOnce.stepEndAtMs, stepwise.stepEndAtMs)
    }

    @Test
    fun `coming back after the whole program elapsed reports it finished, not step one`() {
        val settled = settleRun(steps, startRun(steps, t0), t0 + totalMs + 60_000)
        assertTrue(settled.finished)
        assertFalse(settled.running)
        assertEquals(0, stepRemainingMs(steps, settled, t0 + totalMs + 60_000))
        assertNull(currentStep(steps, settled, t0 + totalMs + 60_000))
    }

    @Test
    fun `a boundary is reached at its exact moment, not one millisecond later`() {
        val state = startRun(steps, t0)
        assertEquals(0, settleRun(steps, state, t0 + 9_999).stepIndex)
        assertEquals(1, settleRun(steps, state, t0 + 10_000).stepIndex)
    }

    // --- the controls -----------------------------------------------------------------

    @Test
    fun `pausing freezes what was left and the clock moving on changes nothing`() {
        val paused = pauseRun(steps, startRun(steps, t0), t0 + 4_000)
        assertFalse(paused.running)
        assertEquals(6_000, paused.pausedLeftMs)
        assertEquals(6_000, stepRemainingMs(steps, paused, t0 + 4_000))
        // an hour later it is still six seconds
        assertEquals(6_000, stepRemainingMs(steps, paused, t0 + 3_600_000))
    }

    @Test
    fun `resuming pushes the end out by exactly what was left, wherever the clock now is`() {
        val paused = pauseRun(steps, startRun(steps, t0), t0 + 4_000)
        val resumed = resumeRun(steps, paused, t0 + 3_600_000)

        assertTrue(resumed.running)
        assertEquals(6_000, stepRemainingMs(steps, resumed, t0 + 3_600_000))
        assertEquals(0, resumed.stepIndex)
    }

    @Test
    fun `pause and resume do not shorten the program`() {
        val started = startRun(steps, t0)
        val paused = pauseRun(steps, started, t0 + 4_000)
        val resumed = resumeRun(steps, paused, t0 + 100_000)
        assertEquals(totalMs - 4_000, totalRemainingMs(steps, resumed, t0 + 100_000))
    }

    @Test
    fun `skipping starts the next step from the top`() {
        val skipped = skipStep(steps, startRun(steps, t0), t0 + 2_000)
        assertEquals(1, skipped.stepIndex)
        assertEquals(20_000, stepRemainingMs(steps, skipped, t0 + 2_000))
    }

    @Test
    fun `skipping while paused stays paused at the top of the next step`() {
        val paused = pauseRun(steps, startRun(steps, t0), t0 + 2_000)
        val skipped = skipStep(steps, paused, t0 + 2_000)

        assertFalse(skipped.running)
        assertEquals(1, skipped.stepIndex)
        assertEquals(20_000, skipped.pausedLeftMs)
    }

    @Test
    fun `skipping the last step ends the program`() {
        val onLast = settleRun(steps, startRun(steps, t0), t0 + 71_000)
        assertEquals(steps.lastIndex, onLast.stepIndex)
        assertTrue(skipStep(steps, onLast, t0 + 71_000).finished)
    }

    // --- what was held, as opposed to what was passed (§18.20) ------------------------

    @Test
    fun `a skipped step is marked, so being past it is not the same as having held it`() {
        val skipped = skipStep(steps, startRun(steps, t0), t0 + 2_000)
        assertEquals(setOf(0), skipped.skipped)
    }

    @Test
    fun `a step the clock carries the run through leaves no mark`() {
        val settled = settleRun(steps, startRun(steps, t0), t0 + 45_000)
        assertTrue(settled.skipped.isEmpty())
    }

    @Test
    fun `skipping the last step marks it before the run reports itself finished`() {
        val onLast = settleRun(steps, startRun(steps, t0), t0 + 71_000)
        val ended = skipStep(steps, onLast, t0 + 71_000)

        assertTrue(ended.finished)
        // `finished` is what makes every step count; without the mark the last hang of the
        // session would be written by the act of skipping it
        assertEquals(setOf(steps.lastIndex), ended.skipped)
    }

    @Test
    fun `a skipped step held to its end the second time counts again`() {
        val skipped = skipStep(steps, startRun(steps, t0), t0 + 2_000)
        val back = previousStep(steps, skipped, t0 + 3_000)
        assertEquals(0, back.stepIndex)
        assertEquals(setOf(0), back.skipped)

        val heldThrough = settleRun(steps, back, t0 + 3_000 + 10_000)
        assertEquals(1, heldThrough.stepIndex)
        assertTrue(heldThrough.skipped.isEmpty())
    }

    @Test
    fun `two skips in one run are both remembered`() {
        val first = skipStep(steps, startRun(steps, t0), t0 + 2_000)
        val onSecond = settleRun(steps, first, t0 + 2_000 + 20_000)
        assertEquals(2, onSecond.stepIndex)

        val second = skipStep(steps, onSecond, t0 + 22_000)
        assertEquals(setOf(0, 2), second.skipped)
    }

    @Test
    fun `the mark travels in the snapshot, so a rebuilt process still knows what was skipped`() {
        val skipped = skipStep(steps, startRun(steps, t0), t0 + 2_000)
        val snapshot = RunSnapshot(
            programId = 1,
            programName = "test",
            steps = steps,
            state = skipped,
            bootRef = 0,
        )
        val json = kotlinx.serialization.json.Json.encodeToString(RunSnapshot.serializer(), snapshot)
        val back = kotlinx.serialization.json.Json.decodeFromString(RunSnapshot.serializer(), json)

        assertEquals(setOf(0), back.state.skipped)
    }

    @Test
    fun `a snapshot written before the mark existed still reads, as a run that skipped nothing`() {
        val old = """{"program_id":1,"program_name":"test","steps":[],"state":{"step_index":3,""" +
            """"running":true,"step_end_at_ms":500},"boot_ref":7}"""
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(RunSnapshot.serializer(), old)

        assertEquals(3, parsed.state.stepIndex)
        assertTrue(parsed.state.skipped.isEmpty())
    }

    @Test
    fun `going back restarts the previous step from its beginning`() {
        val onSecond = settleRun(steps, startRun(steps, t0), t0 + 15_000)
        assertEquals(1, onSecond.stepIndex)

        val back = previousStep(steps, onSecond, t0 + 15_000)
        assertEquals(0, back.stepIndex)
        assertEquals(10_000, stepRemainingMs(steps, back, t0 + 15_000))
    }

    @Test
    fun `going back on the first step restarts it instead of falling off the front`() {
        val started = startRun(steps, t0)
        val back = previousStep(steps, started, t0 + 5_000)
        assertEquals(0, back.stepIndex)
        assertEquals(10_000, stepRemainingMs(steps, back, t0 + 5_000))
    }

    @Test
    fun `going back from a finished program reopens the last step`() {
        val done = settleRun(steps, startRun(steps, t0), t0 + totalMs + 1)
        val back = previousStep(steps, done, t0 + totalMs + 1)

        assertFalse(back.finished)
        assertEquals(steps.lastIndex, back.stepIndex)
        assertEquals(20_000, stepRemainingMs(steps, back, t0 + totalMs + 1))
    }

    @Test
    fun `plus thirty seconds lengthens only the current step and nothing after it`() {
        val state = startRun(steps, t0)
        val longer = adjustStep(steps, state, t0 + 2_000, 30_000)

        assertEquals(38_000, stepRemainingMs(steps, longer, t0 + 2_000))
        assertEquals(totalMs - 2_000 + 30_000, totalRemainingMs(steps, longer, t0 + 2_000))
        assertEquals(0, longer.stepIndex)
    }

    @Test
    fun `minus thirty seconds with less than that left ends the step instead of refusing`() {
        val state = startRun(steps, t0)
        val shorter = adjustStep(steps, state, t0 + 2_000, -30_000)

        // the end of the step is now, rather than in the past or refused
        assertEquals(t0 + 2_000, shorter.stepEndAtMs)
        // so the run moves straight on to the next step instead of sitting at zero
        assertEquals(1, settleRun(steps, shorter, t0 + 2_000).stepIndex)
        assertEquals(20_000, stepRemainingMs(steps, shorter, t0 + 2_000))
    }

    @Test
    fun `adjusting while paused changes what is left, not where the clock is`() {
        val paused = pauseRun(steps, startRun(steps, t0), t0 + 4_000)
        val longer = adjustStep(steps, paused, t0 + 4_000, 30_000)

        assertFalse(longer.running)
        assertEquals(36_000, longer.pausedLeftMs)

        val floored = adjustStep(steps, paused, t0 + 4_000, -30_000)
        assertEquals(0, floored.pausedLeftMs)
    }

    @Test
    fun `an adjustment applies to this run only and not to a later repeat of the block`() {
        // step 1 is the first 20 s work; lengthen it, then run on to step 3, the second one
        val longer = adjustStep(steps, settleRun(steps, startRun(steps, t0), t0 + 10_000), t0 + 10_000, 30_000)
        // the lengthened work step now runs to t0+60s, then the 10 s rest to t0+70s
        val later = settleRun(steps, longer, t0 + 70_000)

        assertEquals(3, later.stepIndex)
        // the second repeat of the same block is its written 20 s, not 50 s
        assertEquals(20_000, stepRemainingMs(steps, later, t0 + 70_000))
    }

    @Test
    fun `the next step is reported so nothing on the run screen is a surprise`() {
        val state = startRun(steps, t0)
        assertEquals(StepKind.PREPARE, currentStep(steps, state, t0)!!.kind)
        assertEquals(StepKind.WORK, nextStep(steps, state, t0)!!.kind)

        val onLast = settleRun(steps, state, t0 + 71_000)
        assertNull(nextStep(steps, onLast, t0 + 71_000))
    }

    @Test
    fun `phase reports what the screen has to branch on`() {
        val running = startRun(steps, t0)
        assertEquals(RunPhase.RUNNING, running.phase())
        assertEquals(RunPhase.PAUSED, pauseRun(steps, running, t0 + 1_000).phase())
        assertEquals(RunPhase.FINISHED, settleRun(steps, running, t0 + totalMs).phase())
    }

    // --- surviving process death and reboot -------------------------------------------

    @Test
    fun `a snapshot round trips through JSON with the end moment intact`() {
        val snapshot = RunSnapshot(
            programId = 3,
            programName = "Tabata",
            steps = steps,
            state = settleRun(steps, startRun(steps, t0), t0 + 45_000),
            bootRef = 12345,
            exerciseId = 7,
        )
        val restored = payloadJson.decodeFromString<RunSnapshot>(payloadJson.encodeToString(snapshot))

        assertEquals(snapshot, restored)
        assertEquals(15_000, stepRemainingMs(restored.steps, restored.state, t0 + 45_000))
    }

    @Test
    fun `a run rebuilt from disk after the process was killed is on the right step`() {
        val saved = RunSnapshot(
            programId = 0, programName = "Tabata", steps = steps,
            state = startRun(steps, t0), bootRef = 12345,
        )
        val json = payloadJson.encodeToString(saved)

        // the process dies here, and is rebuilt 62 s later
        val restored = payloadJson.decodeFromString<RunSnapshot>(json)
        val settled = settleRun(restored.steps, restored.state, t0 + 62_000)

        assertEquals(4, settled.stepIndex)
        assertEquals(StepKind.REST, steps[settled.stepIndex].kind)
        assertEquals(8_000, stepRemainingMs(steps, settled, t0 + 62_000))
    }

    @Test
    fun `the boot reference stays put across a session and moves after a restart`() {
        // same boot: the wall clock and the monotonic clock advance together
        val early = bootReference(wallMs = 1_700_000_000_000, elapsedMs = 1_000_000)
        val later = bootReference(wallMs = 1_700_000_060_000, elapsedMs = 1_060_000)
        assertEquals(early, later)
        assertFalse(isRunStale(early, later))
    }

    @Test
    fun `a run saved before a reboot is discarded rather than resumed at a wrong offset`() {
        val beforeReboot = bootReference(wallMs = 1_700_000_000_000, elapsedMs = 5_000_000)
        // after a restart the monotonic clock is back near zero while the wall clock is not
        val afterReboot = bootReference(wallMs = 1_700_000_120_000, elapsedMs = 30_000)
        assertTrue(isRunStale(beforeReboot, afterReboot))
    }

    @Test
    fun `ordinary clock drift is not mistaken for a reboot`() {
        val before = bootReference(wallMs = 1_700_000_000_000, elapsedMs = 1_000_000)
        // NTP nudges the wall clock by two seconds mid-session
        val after = bootReference(wallMs = 1_700_000_062_000, elapsedMs = 1_060_000)
        assertFalse(isRunStale(before, after))
    }
}
