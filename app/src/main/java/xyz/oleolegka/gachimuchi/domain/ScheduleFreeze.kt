package xyz.oleolegka.gachimuchi.domain

/**
 * When a schedule stops being editable.
 *
 * ── The rule, and the one it replaces ───────────────────────────────────────────
 * Decisions §18.19: a schedule may be edited FOR AS LONG AS NO SET HAS BEEN RECORDED against
 * it. From the first set it is frozen for good — the temporal structure has become part of
 * what the exercise IS, and moving it would file today's sets under yesterday's history.
 *
 * This supersedes §18.9's "frozen from the mere fact of a reference", which the code used to
 * enforce through a single `SELECT EXISTS(... WHERE protocol_program_id = :id)` that never
 * looked at the journal at all: creating the exercise closed its schedule to editing in the
 * same second, and a typo in one number could only be corrected by starting a new exercise
 * and moving everything to it. That is a punishment for a typo, and the owner retired it.
 *
 * ── Why a LIST of owners rather than one ────────────────────────────────────────
 * A schedule can be SHARED, deliberately: twins (a hang on 20 mm and the same hang on 15 mm)
 * point at one row on purpose (§18.15). So "no sets yet" has to mean "at none of the
 * exercises pointing here" — asking only about the one being edited would let an edit made
 * for the untouched 15 mm hang rewrite the history of the 20 mm one behind its back.
 *
 * ── One answer, three doors ─────────────────────────────────────────────────────
 * The freeze is enforced in three places — the program editor draws its content as text, the
 * repository refuses the save, and the library offers no delete — and all three ask THESE
 * functions. A screen that answered the question its own way is a screen that permits what
 * the repository then throws away.
 */

/**
 * Every exercise the journal holds at least one live entry for, as the journal names it.
 *
 * LIVE, through [readActivities]: a set recorded and then deleted is a set that "should not
 * be there", the meaning deletion has everywhere else in this app, so it does not hold a
 * schedule frozen. The consequence, stated rather than hidden: deleting every set of an
 * exercise thaws its schedule again. That is the same reversal every other deletion in this
 * journal gives, and the alternative — a freeze that outlives the training that caused it —
 * would be a state with no way back and nothing on screen to explain it.
 *
 * Entries naming no exercise (a weigh-in) are skipped: stepping on the scales is not a set of
 * anything, and it carries no exercise link to match against in the first place.
 *
 * Deduplicated by [ExerciseLink.key] so the answer is one entry per exercise however many
 * sets it has; the FIRST mention is kept, which is enough because [ExerciseLink.matches] is
 * what compares them, not equality.
 */
fun trainedExercises(events: List<JournalEvent>): List<ExerciseLink> {
    val seen = LinkedHashMap<String, ExerciseLink>()
    for (activity in readActivities(events)) {
        val link = activity.form.exerciseLink() ?: continue
        seen.putIfAbsent(link.key, link)
    }
    return seen.values.toList()
}

/**
 * Whether a schedule owned by [owners] is frozen — see this file's header.
 *
 * Matched through [ExerciseLink.matches] rather than by equality, because a set written
 * before schema version 10 names its exercise by row number and the catalog names it by
 * identity; comparing the two as values would read an old journal as "nothing trained here"
 * and unfreeze a schedule with ten years of hangs under it.
 */
fun scheduleFrozen(owners: List<ExerciseLink>, trained: List<ExerciseLink>): Boolean =
    owners.any { owner -> trained.any { it.matches(owner) } }

/**
 * Of [owners] — every schedule and the exercises pointing at it — the ids that are frozen.
 *
 * The bulk form, for the screen that has to decide the same thing for a whole library at
 * once: the journal is folded ONCE here instead of per program.
 */
fun frozenScheduleIds(events: List<JournalEvent>, owners: Map<Long, List<ExerciseLink>>): Set<Long> {
    if (owners.isEmpty()) return emptySet()
    val trained = trainedExercises(events)
    return owners.filterValues { scheduleFrozen(it, trained) }.keys
}
