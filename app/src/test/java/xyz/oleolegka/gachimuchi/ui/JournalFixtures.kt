package xyz.oleolegka.gachimuchi.ui

import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.EntryDeleted
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.OrderedExercise
import xyz.oleolegka.gachimuchi.domain.REPEAT_NONE
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.TYPE_ENTRY_DELETED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_EXERCISE_ADDED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_FINISHED
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_ORDER_SET
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_STARTED
import xyz.oleolegka.gachimuchi.domain.WorkoutExerciseAdded
import xyz.oleolegka.gachimuchi.domain.WorkoutFinished
import xyz.oleolegka.gachimuchi.domain.WorkoutOrder
import xyz.oleolegka.gachimuchi.domain.WorkoutStarted
import xyz.oleolegka.gachimuchi.domain.bodyweightOf
import xyz.oleolegka.gachimuchi.domain.holdSetOf
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

    /**
     * Opens a workout on [day] and returns its id, which is the id of the start event.
     *
     * [name] is the snapshot the app takes when "start" is pressed — copied off the plan when
     * the workout is started from one. The screens read it and never ask the plan, so a
     * fixture that leaves it out is a workout nobody named, whatever [slotId] says.
     */
    fun startWorkout(
        day: String,
        at: String = "09:00",
        slotId: Long? = null,
        name: String? = null,
    ): Long = add(
        TYPE_WORKOUT_STARTED,
        payloadJson.encodeToString(WorkoutStarted(day, slotId, name = name)),
        "${day}T$at:00",
    )

    /**
     * Closes a workout. It carries NO time of its own: when the training ended is folded out
     * of the last set recorded, so this row states only which workout is over.
     */
    fun finishWorkout(workoutId: Long, day: String, at: String = "20:00") = add(
        TYPE_WORKOUT_FINISHED,
        payloadJson.encodeToString(WorkoutFinished(workoutId)),
        "${day}T$at:00",
        workoutId,
    )

    /** Puts an exercise into a workout before any set of it exists. */
    fun addExercise(workoutId: Long, day: String, exercise: ExerciseRef, restSec: Int, at: String = "09:01") =
        add(
            TYPE_WORKOUT_EXERCISE_ADDED,
            payloadJson.encodeToString(WorkoutExerciseAdded(workoutId, exercise.id, restSec)),
            "${day}T$at:00",
            workoutId,
        )

    /**
     * States the order the exercises of a workout are to be done in, WHOLE — the row a drag
     * writes. By exercise number, which is what a journal this app wrote on one phone carries.
     */
    fun setExerciseOrder(
        workoutId: Long,
        day: String,
        vararg exercises: ExerciseRef,
        at: String = "09:20",
    ) = add(
        TYPE_WORKOUT_ORDER_SET,
        payloadJson.encodeToString(
            WorkoutOrder(workoutId, exercises.map { OrderedExercise(exerciseId = it.id) })
        ),
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

    /**
     * A hang. [addedKg] null is the plate-free case, which is the one the weight question on
     * the way into a protocol-led set is required NOT to appear for (§13.5).
     *
     * [side] is what a one-sided exercise records instead of nothing, and [warmup] keeps a
     * ramp-up out of the volume and the records. Both go through `holdSetOf` rather than into
     * a hand-made payload, so a fixture cannot say something the app itself could not.
     */
    fun holdSet(
        exercise: ExerciseRef,
        day: String,
        reps: Int = 6,
        addedKg: Double? = null,
        at: String = "09:10",
        workoutId: Long? = null,
        side: HoldSide? = null,
        warmup: Boolean = false,
    ): Long {
        val form = holdSetOf(
            exercise = exercise, opDate = day, addedKg = addedKg, reps = reps,
            warmup = warmup, side = side,
        )
        return add(form.type, form.toPayload(), "${day}T$at:00", workoutId)
    }

    /**
     * Removes an entry the way the app does: a new event naming the target's IDENTITY, never a
     * row taken out of the list. Written into a fixture so a screen test can assert that the
     * screen reads the journal through the amendment funnel rather than raw.
     */
    fun deleteEntry(eventId: Long): Long {
        val target = rows.first { it.id == eventId }
        return add(
            TYPE_ENTRY_DELETED,
            payloadJson.encodeToString(EntryDeleted(targetUid = target.uid)),
            target.ts,
        )
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

fun exerciseEntity(
    id: Long,
    name: String,
    form: ExerciseForm = ExerciseForm.STRENGTH,
    /** Kept out of the pickers — see [ExerciseEntity.hidden]. */
    hidden: Boolean = false,
    /** Part of the identity of a hold, and what tells two rows of one name apart (§12-A). */
    edgeMm: Double? = null,
    workSec: Double? = null,
    restSec: Double? = null,
) = ExerciseEntity(
    id = id, name = name, form = form.code, createdAt = "2026-01-01T00:00:00", hidden = hidden,
    edgeMm = edgeMm, protocolWorkSec = workSec, protocolRestSec = restSec,
)

fun slot(id: Long, name: String, atTime: String?, day: String) =
    Slot(id = id, name = name, atTime = atTime, repeatRule = REPEAT_NONE, anchorDate = day)
