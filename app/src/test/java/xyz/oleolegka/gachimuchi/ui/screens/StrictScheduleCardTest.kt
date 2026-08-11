package xyz.oleolegka.gachimuchi.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.ui.Journal
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.exerciseEntity
import xyz.oleolegka.gachimuchi.ui.protocolProgramIdFor

/**
 * The workout card of a hold with a STRICT schedule (§18.15), on the screen where it is tapped.
 *
 * The card decides between two things — hand the screen to the conductor, or raise the manual
 * entry form — and it used to decide on "does the exercise have a work:rest PAIR". A strict
 * schedule need not have one: this fixture's opens with a ten-second hang and no rest of its
 * own, because the pause in it comes after the second edge. The pair read off that first block
 * is `10 : 0`, which is no pair at all, so the richest schedule in the catalog was the one
 * falling through to a form asking for numbers the user has not produced yet.
 *
 * Written on the SCREEN and not on the gate, per the working agreement: the tap is the thing
 * the owner does, and four tests on a condition once failed to notice a button that should not
 * have been drawn at all.
 */
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xhdpi")
class StrictScheduleCardTest : ScreenTest() {

    private val iso = "2026-08-11"
    private val exerciseId = 3L

    /** Two edges in a fixed order, twice over — and no rest on the first block. */
    private val schedule = WorkoutProgram(
        id = protocolProgramIdFor(exerciseId),
        name = "Two edges",
        groups = listOf(
            ProgramGroup(
                name = "Edges",
                blocks = listOf(
                    ProgramBlock("Hang 20 mm", workSec = 10, restSec = 0),
                    ProgramBlock("Hang 15 mm", workSec = 7, restSec = 120),
                ),
                repeats = 2,
            ),
        ),
    )

    private val catalog = listOf(
        exerciseEntity(exerciseId, "Edges", ExerciseForm.HOLD)
            .copy(protocolProgramId = schedule.id, defaultRestSec = 240),
    )

    private val started = mutableListOf<Triple<String, Double?, HoldSide?>>()

    private fun show() {
        val state = UiState(
            events = journal.events,
            exercises = catalog,
            programsById = mapOf(schedule.id to schedule),
            loading = false,
        )
        screen {
            WorkoutLogScreen(
                state = state,
                workoutId = workoutId,
                settings = TimerSettings(),
                floors = emptyList(),
                actions = WorkoutLogActions(
                    addExercise = { _, _, _, _ -> },
                    createExercise = { _, _ -> },
                    addSet = {},
                    undoSet = {},
                    removeExercise = { _, _, _ -> },
                    reorderExercises = {},
                    finish = {},
                    finishExercise = { _, _ -> },
                    unfinishExercise = {},
                    unfinishWorkout = {},
                    startProtocolSet = { start -> started += Triple(start.exercise.name, start.addedKg, start.side) },
                    openConductor = {},
                    close = {},
                ),
                nowMs = 1_000_000L,
            )
        }
    }

    private val journal = Journal()
    private val workoutId = journal.startWorkout(iso, at = "18:00").also {
        journal.addExercise(it, iso, ref(), restSec = 240, at = "18:01")
    }

    private fun ref() = UiState(exercises = catalog, programsById = mapOf(schedule.id to schedule))
        .refById(exerciseId)!!

    @Test
    fun `tapping a strictly scheduled card hands the screen to the conductor`() {
        show()
        compose.onNodeWithText("Edges").performClick()
        settle()

        assertEquals(listOf(Triple("Edges", null, null)), started)
        // and it is a run, not a report: no form and no question on the way in
        compose.onAllNodesWithText("Repeat set").assertCountEquals(0)
    }
}
