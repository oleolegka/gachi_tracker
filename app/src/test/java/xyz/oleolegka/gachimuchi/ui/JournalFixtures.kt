package xyz.oleolegka.gachimuchi.ui

import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.REPEAT_NONE
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_EXERCISE_ADDED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_STARTED
import xyz.oleolegka.gachimuchi.domain.WorkoutExerciseAdded
import xyz.oleolegka.gachimuchi.domain.WorkoutStarted
import xyz.oleolegka.gachimuchi.domain.bodyweightOf
import xyz.oleolegka.gachimuchi.domain.payloadJson
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.toPayload

/**
 * Journals for the screen tests, written the way the app writes them.
 *
 * The screens are fed through the real reducers (`dayCards`, `buildWorkout`) rather than
 * handed a list of already-built cards. Handing them cards would test the drawing against
 * a fixture invented for the test; going through the reducer tests the drawing against the
 * only input it ever gets in production, and catches the thing a unit test of either half
 * cannot see — a screen that draws the title and quietly forgets the subtitle.
 */
class Journal {

    private var nextId = 1L
    private val rows = ArrayList<JournalEvent>()

    val events: List<JournalEvent> get() = rows.toList()

    private fun add(type: String, payload: String, ts: String, workoutId: Long? = null): Long {
        val id = nextId++
        rows += JournalEvent(id, ts, 1, 1, type, payload, workoutId)
        return id
    }

    /** Opens a workout on [day] and returns its id, which is the id of the start event. */
    fun startWorkout(day: String, at: String = "09:00", slotId: Long? = null): Long = add(
        TYPE_WORKOUT_STARTED,
        payloadJson.encodeToString(WorkoutStarted(day, slotId)),
        "${day}T$at:00",
    )

    /** Puts an exercise into a workout before any set of it exists. */
    fun addExercise(workoutId: Long, day: String, exercise: ExerciseRef, restSec: Int, at: String = "09:01") =
        add(
            TYPE_WORKOUT_EXERCISE_ADDED,
            payloadJson.encodeToString(WorkoutExerciseAdded(workoutId, exercise.id, restSec)),
            "${day}T$at:00",
            workoutId,
        )

    fun strengthSet(
        exercise: ExerciseRef,
        day: String,
        reps: Int = 5,
        weightKg: Double = 60.0,
        at: String = "09:10",
        workoutId: Long? = null,
        /**
         * The day the row was WRITTEN on, which is the same as [day] unless the training was
         * typed up afterwards. The screens print a clock time only when the two agree, so
         * this is what a test of that rule has to be able to vary.
         */
        writtenOn: String = day,
    ): Long {
        val form = strengthSetOf(exercise, day, reps = reps, weightKg = weightKg)
        return add(form.type, form.toPayload(), "${writtenOn}T$at:00", workoutId)
    }

    /** A weigh-in: the one form that carries no exercise, in or out of a workout. */
    fun weighIn(day: String, kg: Double = 74.2, at: String = "07:30", workoutId: Long? = null): Long {
        val form = bodyweightOf(day, kg)
        return add(form.type, form.toPayload(), "${day}T$at:00", workoutId)
    }
}

// --- the catalog ------------------------------------------------------------------------

fun exerciseRef(id: Long, name: String, form: ExerciseForm = ExerciseForm.STRENGTH) =
    ExerciseRef(id, name, form)

fun exerciseEntity(id: Long, name: String, form: ExerciseForm = ExerciseForm.STRENGTH) =
    ExerciseEntity(id = id, name = name, form = form.code, createdAt = "2026-01-01T00:00:00")

fun slot(id: Long, name: String, atTime: String?, day: String) =
    Slot(id = id, name = name, atTime = atTime, repeatRule = REPEAT_NONE, anchorDate = day)
