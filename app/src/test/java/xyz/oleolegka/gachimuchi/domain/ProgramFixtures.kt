package xyz.oleolegka.gachimuchi.domain

/**
 * The MULTI-SET shape, written down by hand because nothing in the app builds one from an
 * exercise any more.
 *
 * `programFromExercise` used to take a set count and a pause between sets and multiply them
 * into the program; §18.17 cut a conducted run down to ONE set, and the pause after it became a
 * floor. That is a change to what the app STARTS, not to what the runner can be handed: a strict
 * schedule (§18.15) is played exactly as written and may well say "four sets, three minutes
 * between", and the salvage, bucketing and offer paths all behave differently across a group
 * boundary. So the tests that cover those keep a way to write the shape down, and it lives here
 * rather than in a production function nobody calls.
 *
 * Deliberately identical to the pre-§18.17 builder, so a test that used to read
 * `programFromExercise(exercise, reps, sets, restBetweenSetsSec, prepareSec)` still means the
 * same thing.
 */
fun multiSetProgram(
    exercise: ExerciseRef,
    reps: Int,
    sets: Int,
    restBetweenSetsSec: Int,
    prepareSec: Int = PREPARE_DEFAULT_SEC,
): WorkoutProgram? {
    val protocol = exercise.protocol ?: return null
    val workSec = protocol.first.toInt()
    val restSec = protocol.second.toInt()
    if (workSec <= 0) return null
    return WorkoutProgram(
        name = exercise.name,
        prepareSec = prepareSec,
        exerciseId = exercise.id,
        groups = listOf(
            ProgramGroup(
                name = exercise.name,
                blocks = listOf(
                    ProgramBlock(
                        name = exercise.name,
                        workSec = workSec,
                        restSec = restSec.coerceAtLeast(0),
                        repeats = reps.coerceAtLeast(1),
                    )
                ),
                repeats = sets.coerceAtLeast(1),
                restBetweenRepeatsSec = restBetweenSetsSec.coerceAtLeast(0),
            )
        ),
    )
}
