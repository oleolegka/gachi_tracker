package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Interval programs: what the workout timer counts down.
 *
 * ── The shape, and why exactly two levels ───────────────────────────────────────
 * A program is a list of GROUPS, a group is a list of BLOCKS. A block is one timed
 * effort with its own rest and its own repeat count ("hang 7 s, rest 3 s, six times");
 * a group repeats the whole run of its blocks ("that, four times, three minutes
 * between"). Two levels is what hangboard repeaters, Tabata, EMOM and circuit training
 * all need, and it is where the editor still fits on a phone screen. Deeper nesting was
 * left out deliberately: it buys rarely-used shapes at the cost of an editor nobody can
 * operate one-handed in a gym.
 *
 * ── The program is data, the run is not ─────────────────────────────────────────
 * Nothing here knows about clocks, services or Android. [flatten] turns a program into a
 * FLAT LIST OF STEPS, and everything the runner (domain/Runner.kt) does is arithmetic
 * over that list plus an injected "now". So the whole countdown — including what happens
 * when the process is killed mid-workout and comes back four minutes later — is testable
 * on the JVM.
 *
 * ── Not the journal ─────────────────────────────────────────────────────────────
 * Programs are reference data, like the exercise catalog and the calendar slots: they
 * are edited and deleted freely. They are NOT events, and running one records nothing.
 * Writing sets from a finished run is a separate feature and is deliberately not here —
 * a timer that silently logs sets you did not actually do would poison the journal.
 */

/** Default countdown before the first work step, in seconds. */
const val PREPARE_DEFAULT_SEC = 10

/**
 * Ceiling on the number of steps a program may expand into. A program is a few numbers
 * multiplied together, so a typo (repeats = 999) turns into a list long enough to hang
 * the editor. Expansion stops at this many steps rather than throwing: a truncated
 * program is visible and fixable, a crash on the program screen is not.
 */
const val MAX_PROGRAM_STEPS = 2000

/** What a step is for. The signal at a boundary and the colour of the row follow from it. */
enum class StepKind {
    /** The lead-in before the first effort: time to reach the bar and get set. */
    PREPARE,

    /** A timed effort. */
    WORK,

    /** A timed pause. */
    REST,
}

/** One timed effort inside a group, with the pause that follows it. */
@Serializable
data class ProgramBlock(
    @SerialName("name") val name: String,
    @SerialName("work_sec") val workSec: Int,
    @SerialName("rest_sec") val restSec: Int = 0,
    /** How many times this work/rest pair runs back to back. */
    @SerialName("repeats") val repeats: Int = 1,
)

/**
 * A run of blocks, repeated as a unit.
 *
 * [restBetweenRepeatsSec] is the pause BETWEEN repeats of this group and never follows
 * the last one; [restAfterSec] is the pause before the NEXT group and is dropped when the
 * group is the last in the program. Both are separate from a block's own rest, and when
 * two of them land next to each other [flatten] adds them up (see there).
 */
@Serializable
data class ProgramGroup(
    @SerialName("name") val name: String,
    @SerialName("blocks") val blocks: List<ProgramBlock>,
    @SerialName("repeats") val repeats: Int = 1,
    @SerialName("rest_between_repeats_sec") val restBetweenRepeatsSec: Int = 0,
    @SerialName("rest_after_sec") val restAfterSec: Int = 0,
)

/**
 * A named program. [id] is the row id when it came from the database and 0 when it was
 * generated on the fly (from an exercise, or for a single rest between sets) and never
 * stored.
 */
@Serializable
data class WorkoutProgram(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String,
    @SerialName("groups") val groups: List<ProgramGroup>,
    @SerialName("prepare_sec") val prepareSec: Int = PREPARE_DEFAULT_SEC,
)

/**
 * One entry of the flattened sequence: a single stretch of time with a name and a kind.
 * The repeat counters travel with the step so the notification and the run screen can say
 * "Hang - 3 of 6, set 2 of 4" without walking back up into the program.
 */
@Serializable
data class WorkoutStep(
    @SerialName("kind") val kind: StepKind,
    @SerialName("name") val name: String,
    @SerialName("duration_sec") val durationSec: Int,
    @SerialName("group_name") val groupName: String = "",
    @SerialName("group_repeat") val groupRepeat: Int = 1,
    @SerialName("group_repeats") val groupRepeats: Int = 1,
    @SerialName("block_repeat") val blockRepeat: Int = 1,
    @SerialName("block_repeats") val blockRepeats: Int = 1,
) {
    val durationMs: Long get() = durationSec * 1000L

    /** "3 of 6" style position inside the block, or null when the block runs once. */
    val blockPosition: String? get() = if (blockRepeats > 1) "$blockRepeat of $blockRepeats" else null

    /** "set 2 of 4", or null when the group runs once. */
    val groupPosition: String? get() = if (groupRepeats > 1) "set $groupRepeat of $groupRepeats" else null
}

/**
 * Expands a program into the sequence the runner counts down.
 *
 * Three rules decide what the list looks like, and all three exist to avoid surprising
 * the person standing under the bar:
 *
 * 1. STEPS OF ZERO LENGTH ARE DROPPED. A block with no rest configured must not produce
 *    a rest step of 0 s that the runner would fly through, announcing a pause that never
 *    happened.
 * 2. ADJACENT RESTS ARE ADDED UP INTO ONE STEP. A block rest of 30 s immediately followed
 *    by 120 s between sets is one pause of 150 s, not two steps in a row both called
 *    "Rest". Summing is the only merge that keeps the total honest; taking the larger of
 *    the two would quietly shorten the workout.
 * 3. A TRAILING REST IS DROPPED. The program ends when the last effort ends; sitting
 *    through a final pause with nothing after it is not a workout, it is a wait.
 *
 * Expansion stops at [MAX_PROGRAM_STEPS]; see the note there for why it truncates rather
 * than throws.
 */
fun WorkoutProgram.flatten(): List<WorkoutStep> {
    val out = ArrayList<WorkoutStep>()

    fun push(step: WorkoutStep) {
        if (step.durationSec <= 0) return
        if (out.size >= MAX_PROGRAM_STEPS) return
        val previous = out.lastOrNull()
        // rule 2: two pauses back to back are one pause
        if (previous != null && previous.kind == StepKind.REST && step.kind == StepKind.REST) {
            out[out.lastIndex] = previous.copy(
                durationSec = previous.durationSec + step.durationSec,
                // named after whichever pause dominates it: three seconds of block rest
                // absorbed into three minutes between sets is a rest between sets
                name = if (step.durationSec > previous.durationSec) step.name else previous.name,
                // the later rest carries the more useful context (where the workout now is)
                groupName = step.groupName,
                groupRepeat = step.groupRepeat,
                groupRepeats = step.groupRepeats,
                blockRepeat = 1,
                blockRepeats = 1,
            )
            return
        }
        out += step
    }

    if (prepareSec > 0) {
        push(
            WorkoutStep(
                kind = StepKind.PREPARE,
                name = "Get ready",
                durationSec = prepareSec,
                groupName = groups.firstOrNull()?.name.orEmpty(),
            )
        )
    }

    for ((groupIndex, group) in groups.withIndex()) {
        val groupRepeats = group.repeats.coerceAtLeast(1)
        for (groupRepeat in 1..groupRepeats) {
            for (block in group.blocks) {
                val blockRepeats = block.repeats.coerceAtLeast(1)
                for (blockRepeat in 1..blockRepeats) {
                    push(
                        WorkoutStep(
                            kind = StepKind.WORK,
                            name = block.name,
                            durationSec = block.workSec,
                            groupName = group.name,
                            groupRepeat = groupRepeat,
                            groupRepeats = groupRepeats,
                            blockRepeat = blockRepeat,
                            blockRepeats = blockRepeats,
                        )
                    )
                    push(
                        WorkoutStep(
                            kind = StepKind.REST,
                            name = "Rest",
                            durationSec = block.restSec,
                            groupName = group.name,
                            groupRepeat = groupRepeat,
                            groupRepeats = groupRepeats,
                            blockRepeat = blockRepeat,
                            blockRepeats = blockRepeats,
                        )
                    )
                }
            }
            if (groupRepeat < groupRepeats) {
                push(
                    WorkoutStep(
                        kind = StepKind.REST,
                        name = "Rest between sets",
                        durationSec = group.restBetweenRepeatsSec,
                        groupName = group.name,
                        groupRepeat = groupRepeat,
                        groupRepeats = groupRepeats,
                    )
                )
            }
        }
        if (groupIndex < groups.lastIndex) {
            push(
                WorkoutStep(
                    kind = StepKind.REST,
                    name = "Rest",
                    durationSec = group.restAfterSec,
                    groupName = group.name,
                    groupRepeat = groupRepeats,
                    groupRepeats = groupRepeats,
                )
            )
        }
    }

    // rule 3: nothing follows the last effort
    while (out.isNotEmpty() && out.last().kind == StepKind.REST) out.removeAt(out.lastIndex)
    return out
}

/** Total length of a program once expanded, in seconds. */
fun WorkoutProgram.totalSec(): Int = flatten().sumOf { it.durationSec }

/** Number of work steps — "how many efforts is this", the number worth showing in a list. */
fun WorkoutProgram.workStepCount(): Int = flatten().count { it.kind == StepKind.WORK }

// --- programs that are generated rather than edited -----------------------------------

/**
 * A single pause, as a one-step program.
 *
 * The rest between sets is not a second mechanism bolted on next to the interval timer:
 * it is a program of one REST step. One runner, one service, one notification, one set of
 * tests. There is no prepare step — the rest starts the moment the set ends.
 */
fun restProgram(restSec: Int, label: String = "Rest"): WorkoutProgram = WorkoutProgram(
    name = label,
    prepareSec = 0,
    groups = listOf(
        ProgramGroup(
            name = label,
            blocks = listOf(ProgramBlock(name = label, workSec = restSec.coerceAtLeast(1))),
        )
    ),
)

/**
 * The integration a standalone timer app cannot have: a program built FROM A CATALOG
 * EXERCISE.
 *
 * A hangboard exercise already carries its work:rest protocol and its edge (§12-A puts
 * them on the exercise, not on the set), so "Hangs 20 mm - 7:3" plus a rep count, a set
 * count and a pause between sets is a complete interval program with nothing left to ask.
 * That is the whole point: the numbers are already in the catalog and in the journal, so
 * starting the right timer costs one tap instead of building a program by hand.
 *
 * Returns null when the exercise has no protocol — there is no work duration to count
 * down, and inventing one would be worse than offering a plain rest timer instead.
 */
fun programFromExercise(
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

// --- the two programs that ship with the app ------------------------------------------

/**
 * Starter programs, written on first launch alongside the demo history.
 *
 * Two, not ten: they exist so the program screen is not an empty list with a plus button,
 * and so the shape of a program is obvious from an example. Both are real protocols
 * rather than filler — 7:3 repeaters is the standard hangboard set, and Tabata is the
 * canonical 20:10.
 */
fun starterPrograms(): List<WorkoutProgram> = listOf(
    WorkoutProgram(
        name = "Hangboard repeaters 7:3",
        prepareSec = 15,
        groups = listOf(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock(name = "Hang", workSec = 7, restSec = 3, repeats = 6)),
                repeats = 4,
                restBetweenRepeatsSec = 180,
            )
        ),
    ),
    WorkoutProgram(
        name = "Tabata 20:10",
        prepareSec = 10,
        groups = listOf(
            ProgramGroup(
                name = "Tabata",
                blocks = listOf(ProgramBlock(name = "Work", workSec = 20, restSec = 10, repeats = 8)),
                repeats = 1,
            )
        ),
    ),
)
