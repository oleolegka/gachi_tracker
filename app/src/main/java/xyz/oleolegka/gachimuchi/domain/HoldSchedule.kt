package xyz.oleolegka.gachimuchi.domain

/**
 * Which of the three ways a hold exercise is timed — read off the data, not off a column.
 *
 * ── Why there is no column and no migration ─────────────────────────────────────
 * The three branches were always there, they were just never NAMED: an exercise either has no
 * protocol program, or one that is the minimal "work : rest" pair the create form used to be
 * able to produce, or a richer one built in the library editor. All three record the identical
 * thing in the journal (a set with a duration), and the form is part of an exercise's identity
 * (§12-A) and sits in every journal row, so splitting `ExerciseForm.HOLD` into three would
 * rewrite history to say something it never said. §18.15 settles it: one form, three branches,
 * told apart by the shape of the schedule they point at.
 *
 * ── The ambiguity, stated rather than hidden ────────────────────────────────────
 * A schedule hand-built in the editor out of exactly one group, one block and no repeats is
 * INDISTINGUISHABLE from one the two-number form produced, and this classifier will call it
 * [SIMPLE_PAIR]. That is the price of having no column, and it was accepted knowingly (§18.15):
 * the two really do behave identically everywhere downstream, so the only thing lost is the
 * label the user would have picked, not any behaviour they were promised.
 */
enum class ScheduleKind(val title: String) {
    /**
     * No schedule at all. Nothing counts time for this exercise; how long each hold lasted is
     * typed in by hand.
     */
    FREE("Free"),

    /**
     * One work time and one rest time. How many holds and how many sets is asked before every
     * run, because the schedule does not say.
     */
    SIMPLE_PAIR("Simple pair"),

    /**
     * Every timing fixed in advance: which efforts, how long, in what order, the gaps between
     * them, the repeats and the sets. Nothing is left to ask before a run except the weight.
     */
    STRICT("Strict schedule"),
}

/**
 * True when this program is the minimal shape a plain "work, rest" pair produces: one group,
 * one block, and no repeats on either.
 *
 * This is the same shape `ActivityRepository.resolveOrCreateProtocolProgram` writes and
 * [WorkoutProgram.firstBlock] reads back, which is exactly why it is the dividing line: a
 * program of this shape carries no more information than the two numbers do, so a run started
 * from it still has to ask how many holds and how many sets.
 */
fun WorkoutProgram.isSimplePair(): Boolean {
    val group = groups.singleOrNull() ?: return false
    val block = group.blocks.singleOrNull() ?: return false
    return group.repeats <= 1 && block.repeats <= 1
}

/**
 * True when there is no effort in here to count at all: no groups, or a group with no blocks,
 * or blocks whose work time is zero. The library editor will store such a program — nothing
 * validates a program into being runnable — and [flatten] turns it into an empty list of steps.
 */
private fun WorkoutProgram.countsNothing(): Boolean =
    groups.none { group -> group.blocks.any { it.workSec > 0 } }

/**
 * Which branch an exercise pointing at [program] (or at nothing) belongs to. THE one classifier:
 * the create form and the run path both come here, because an exercise created as one branch and
 * conducted as another is the worst failure this file can have.
 *
 * ── The empty schedule is [FREE], not [STRICT] ──────────────────────────────────
 * This rule was settled by comparing two independently written classifiers that met in a merge
 * (2026-08-11). They agreed everywhere a schedule has any work in it — repeats on the block,
 * repeats on the group, a second block, a second group, a block with no rest — and disagreed
 * only on schedules with nothing to count, which the shape test alone calls [STRICT]: a program
 * with no groups has no single group, so it is "not a simple pair", so it falls to the last
 * branch.
 *
 * [STRICT] is the wrong answer there, and it is wrong in a way that reaches the screen.
 * [ScheduleKind.STRICT] is what makes `ExerciseRef.canBeConducted` true, which is what draws the
 * button that hands the screen to the conductor — and `TimerController.start` drops a run whose
 * steps are empty. The tap would have done nothing at all, with no message. [FREE] is also the
 * truthful answer: a schedule that counts nothing does not count time for this exercise, which
 * is exactly what the free branch means.
 */
fun scheduleKindOf(program: WorkoutProgram?): ScheduleKind = when {
    program == null -> ScheduleKind.FREE
    program.countsNothing() -> ScheduleKind.FREE
    program.isSimplePair() -> ScheduleKind.SIMPLE_PAIR
    else -> ScheduleKind.STRICT
}

/**
 * One line saying what a schedule actually contains, for the list it is picked from.
 *
 * A name alone is not enough to pick between two schedules — "Fingerboard" and "Fingerboard
 * 2" tell nobody which is the 7:3 and which is the 10:5 — and the full expansion is far too
 * much for a row. So: the first effort's pair, how many efforts in total, and how long the
 * whole thing runs, all of which come off the same [flatten] the timer counts down, so the
 * line cannot drift away from what would actually be run.
 */
fun WorkoutProgram.scheduleSummary(): String {
    val block = firstBlock()
    val head = if (block != null) "${block.workSec}:${block.restSec}" else "empty"
    return "$head - ${workStepCount()} efforts, ${formatClock(totalSec())}"
}
