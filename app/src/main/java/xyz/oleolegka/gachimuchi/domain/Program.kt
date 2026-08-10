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
 * are edited and deleted freely. They are NOT events, and running one still records
 * nothing by itself: a finished run turns into an OFFER to write sets, which the user
 * corrects and confirms (domain/RunLog.kt). A timer that silently logged sets you did not
 * actually do would poison the journal.
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
 *
 * ── The optional link to a catalog exercise ─────────────────────────────────────
 * [exerciseId] is what makes a SAVED program loggable. A program built on the spot from an
 * exercise always knew which exercise it was; a program typed into the editor did not, so
 * running "Hangboard repeaters 7:3" from the timer tab counted twenty-four hangs and then
 * offered nothing, while the identical protocol started from the exercise offered to write
 * them down. That asymmetry was invisible from the outside and is the whole reason a
 * finished session went unlogged.
 *
 * Optional rather than required, because most programs are not one exercise: a circuit is
 * five, and forcing a link on it would mean picking a lie. Null means "ask when it
 * finishes" (ui/components/RunLogDialog.kt), not "never offer".
 *
 * ── [uid] (schema version 19) ─────────────────────────────────────────────────
 * The stable identity of this program across devices and edits — the same role
 * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.uid] plays for a catalog row. It is what an
 * exercise's protocol is now keyed on ([xyz.oleolegka.gachimuchi.domain.ExerciseIdentity]), and
 * a local row id (`Long`) could never fill that role: it counts how many programs THIS phone
 * has written and means nothing on another one, which is exactly what would silently break
 * cross-device identity comparison ([xyz.oleolegka.gachimuchi.domain.PortableExercise.identity],
 * `mergeExercises`) the moment a device compared its own numbering against someone else's.
 */
@Serializable
data class WorkoutProgram(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String,
    @SerialName("groups") val groups: List<ProgramGroup>,
    @SerialName("prepare_sec") val prepareSec: Int = PREPARE_DEFAULT_SEC,
    /** The catalog exercise this program trains, when it is exactly one. */
    @SerialName("exercise_id") val exerciseId: Long? = null,
    /** Free-text heading this program is filed under on the timer tab. Blank means none. */
    @SerialName("category") val category: String = "",
    @SerialName("uid") val uid: String = newUid(),
    /**
     * Kept out of the timer tab's list (schema version 20) — see
     * [xyz.oleolegka.gachimuchi.data.db.ProgramEntity.hidden]. Presentation only: a hidden
     * program still resolves as a protocol, still runs, still shows on the identity chip.
     */
    @SerialName("hidden") val hidden: Boolean = false,
)

/** Programs under one heading, in the order they are stored. */
data class ProgramSection(val title: String, val programs: List<WorkoutProgram>)

/** The heading a program with no category of its own is filed under. */
const val OTHER_PROGRAMS_SECTION = "Other"

/**
 * Files the program list under headings for the timer tab.
 *
 * ── Why a free-text field and not folders, and not the exercise ─────────────────
 * The list grows: a few protocols become dozens, and a flat list of dozens on a phone is a
 * list nobody scrolls to the bottom of. Three ways to cut it were available.
 *
 * FOLDERS AS ROWS would need a table, an editor, a rename, and an answer to "what happens
 * to the programs in a folder you delete" — a lot of machinery for a screen with a handful
 * of items on it.
 *
 * GROUPING BY THE LINKED EXERCISE is free, because the link already exists, but it sorts
 * almost nothing: a program links to an exercise only when it IS one exercise, and a
 * circuit, a Tabata and a warm-up never will be. Most of the list would end up in "other",
 * which is the situation being fixed.
 *
 * A CATEGORY WRITTEN ON THE PROGRAM is one nullable column, is edited in the editor next to
 * everything else about the program, needs no delete story (the last program to leave a
 * category takes the heading with it), and lets the user name the cut — "Hangboard",
 * "Warm-up", "Bouldering" — which is the part no automatic rule can guess.
 *
 * A list where nothing is categorised comes back as ONE section with an empty title, so a
 * phone with three programs does not grow a heading it did not ask for.
 */
fun programSections(programs: List<WorkoutProgram>): List<ProgramSection> {
    if (programs.none { it.category.isNotBlank() }) {
        return if (programs.isEmpty()) emptyList() else listOf(ProgramSection("", programs))
    }
    val byTitle = LinkedHashMap<String, MutableList<WorkoutProgram>>()
    for (program in programs) {
        val title = program.category.trim().ifEmpty { OTHER_PROGRAMS_SECTION }
        byTitle.getOrPut(title) { mutableListOf() } += program
    }
    return byTitle.entries
        .sortedWith(
            // "Other" is last whatever it is called next to; everything else reads A to Z
            compareBy({ it.key == OTHER_PROGRAMS_SECTION }, { it.key.lowercase() })
        )
        .map { ProgramSection(it.key, it.value) }
}

/**
 * The categories already in use, for the editor to offer instead of asking the user to
 * remember how they spelled it last time. Case-insensitively unique, first spelling wins.
 */
fun knownCategories(programs: List<WorkoutProgram>): List<String> {
    val seen = LinkedHashMap<String, String>()
    for (program in programs) {
        val title = program.category.trim()
        if (title.isEmpty()) continue
        seen.putIfAbsent(title.lowercase(), title)
    }
    return seen.values.sortedBy { it.lowercase() }
}

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

/**
 * The first block of a program's first group, or null for an empty program.
 *
 * ── Why this exists ──────────────────────────────────────────────────────────────
 * A protocol built from a plain "work, rest" pair — the shape the exercise create/edit dialogs
 * still speak (see `ActivityRepository`'s find-or-create-protocol-program logic) — is always
 * exactly this: one group, one block, `repeats == 1` on both. This is the read side of that
 * shape, used both to spot a matching program when one already exists in the library and to
 * turn a resolved program back into the two numbers `ExerciseRef.workSec`/`restSec` want (see
 * [xyz.oleolegka.gachimuchi.domain.CatalogRow.toRef]). It says nothing about whether a program
 * genuinely IS that minimal shape — a caller comparing `repeats` and block count does that.
 */
fun WorkoutProgram.firstBlock(): ProgramBlock? = groups.firstOrNull()?.blocks?.firstOrNull()

/** Total length of a program once expanded, in seconds. */
fun WorkoutProgram.totalSec(): Int = flatten().sumOf { it.durationSec }

/** Number of work steps — "how many efforts is this", the number worth showing in a list. */
fun WorkoutProgram.workStepCount(): Int = flatten().count { it.kind == StepKind.WORK }

// --- programs that are generated rather than edited -----------------------------------

/**
 * A single pause, as a one-step program.
 *
 * ── No longer how the rest between sets works, and the correction is worth stating ──
 * This used to be the whole rest feature: a pause was a program of one step, run on the same
 * conductor as everything else, so there was one runner, one service and one set of tests.
 * The economy was real and the model was wrong. A conductor is singular by nature — one
 * screen, one speaker, one countdown — and a rest between sets is not: a superset has the
 * bench resting while the abs work, and with one countdown the second rest simply cancelled
 * the first. Rests are FLOORS now (domain/Floors.kt), several at a time, one per exercise,
 * and nothing in the app builds a rest program any more.
 *
 * What survives is the one-step program itself, which is a real shape the runner has to
 * handle — the boundary, finish and salvage paths all behave differently when there is no
 * next step — and this is where the tests get one. There is no prepare step.
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
 * A hangboard exercise already carries its work:rest protocol (§12-A puts it on the
 * exercise, not on the set), so "Hangs - 7:3" plus a rep count, a set count and a pause
 * between sets is a complete interval program with nothing left to ask.
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

/**
 * Everything the one-tap program needs from whatever it is launched FROM, bundled as one
 * value rather than passed as three loose arguments.
 *
 * ── Why bundled ──────────────────────────────────────────────────────────────────
 * [xyz.oleolegka.gachimuchi.ui.MainViewModel.startProgramForExercise] used to take
 * `exercise`, `addedKg` and `side` as three independent parameters, two of them defaulted
 * to null. That let a caller supply the exercise and silently skip the other two — which is
 * exactly what the standalone entry screen did, so a one-sided hangboard run started outside
 * a workout wrote sets that named no side and dropped out of both hands' records. Folding
 * the three into one required value removes the silent option: a screen that has not worked
 * out [side] and [addedKg] cannot build a [ProgramStart] at all, so it cannot call the
 * function that starts a run.
 *
 * [side] is nullable on purpose — most exercises are not trained one limb at a time, and for
 * those there is nothing to ask. It is NOT optional to supply, only optional in what it may
 * contain: every caller states an answer, even when that answer is "there is no side here".
 *
 * [addedKg] is the plate hung before the set, asked for only when there is a reason to
 * (§13.5) — see [xyz.oleolegka.gachimuchi.ui.screens.WorkoutLogScreen]'s `WeightDialog` for
 * where that question is actually put to the user.
 */
data class ProgramStart(
    val exercise: ExerciseRef,
    val side: HoldSide?,
    val addedKg: Double?,
)

// --- the two programs that ship with the app ------------------------------------------

/**
 * Starter programs, written on first launch and the only thing that is.
 *
 * Two, not ten: they exist so the program screen is not an empty list with a plus button,
 * and so the shape of a program is obvious from an example. Both are real protocols
 * rather than filler — 7:3 repeaters is the standard hangboard set, and Tabata is the
 * canonical 20:10.
 *
 * They are NOT what the demo seed was, which is why they outlived it: a program claims
 * nothing about what anybody has done. It is an offer to count time, editable and
 * deletable, and it puts no row in the journal until a run is confirmed into one.
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
