package xyz.oleolegka.gachimuchi.domain

/**
 * "Last time": what was done for one exercise the previous time it was trained.
 *
 * ── Why a screen cannot just read the last set ──────────────────────────────────
 * The entry card already prefills itself from the LAST SET of the exercise
 * ([lastStrengthSet] and its siblings), and that set is usually one this very workout wrote a
 * minute ago. It answers "what did I just do", which is the wrong question at the bar: the
 * decision being made is sixty for nine again, or sixty-two and a half for eight, and that is
 * settled by what the PREVIOUS session managed — the whole session of it, not its last set.
 *
 * So this is a different reducer with a different exclusion, and the two are deliberately not
 * folded into one. The prefill wants the newest set including this workout's; the line above
 * the prefill wants the newest DAY that is not this workout's.
 *
 * Pure, like the rest of the domain: a list of events in, a value out, no clock and no
 * Android, so what the line will say is decided on the JVM rather than on a phone.
 */
data class LastTime(
    /** The day those sets belong to — the workout's own date, not the day they were typed. */
    val opDate: String,
    /**
     * Every set of the exercise on that day, in the order they were actually done — by
     * [happenedAt], not journal order. "60 kg x 9, 60 kg x 8, 60 kg x 6" tells a story about
     * how the session went; a correction to the first of the three, written after the other
     * two, must not read "60 kg x 8, 60 kg x 6, 60 kg x 9" just because its own row landed at
     * the end of the journal. Never empty.
     */
    val sets: List<ActivityEvent>,
)

/**
 * The most recent day this exercise was trained, and what was recorded on it.
 *
 * [onOrBefore] is the day of the workout asking. Days AFTER it are excluded, which only
 * matters for training typed up late (§13.6) and matters a great deal there: a workout being
 * backfilled into June must not be told that "last time" was in August. It is `onOrBefore`
 * rather than `before` on purpose — a second workout on the same day is a real thing, and the
 * morning's bench is exactly what the evening's wants to know about.
 *
 * [excludingWorkoutId] is the workout doing the asking. Its own sets are dropped whatever day
 * they are on, because they are already drawn on the card two centimetres above this line;
 * repeating them there as "last time" would be the app telling you what you can see.
 *
 * Sets recorded outside any workout count. A set logged on its own is training that happened,
 * and there is no version of "what did I lift last time" in which it does not count.
 */
fun lastTimeOf(
    events: List<JournalEvent>,
    exercise: ExerciseLink,
    onOrBefore: String? = null,
    excludingWorkoutId: Long? = null,
): LastTime? {
    // through the funnel, like every other reader: a workout that has been deleted is not a
    // workout whose sets should still be held back from "last time"
    val journal = liveEvents(events)
    // resolved to the START ROW rather than compared as a number, because that is the only
    // form WorkoutRef.matches accepts — a row carrying a uid must not be judged by its number
    val start = excludingWorkoutId?.let { id ->
        journal.firstOrNull { it.id == id && it.type == TYPE_WORKOUT_STARTED }
    }
    val done = readActivities(journal, dateTo = onOrBefore)
        .filter { entry -> entry.form.exerciseLink()?.matches(exercise) == true }
        .filter { entry -> start == null || entry.workout?.matches(start) != true }

    val day = done.maxOfOrNull { it.opDate } ?: return null
    return LastTime(
        opDate = day,
        // happenedAt first, then id (journal order) as the tie-break for two sets the clock
        // cannot tell apart — see [LastTime.sets] for why journal order alone is the wrong
        // reading now that a correction can land anywhere in the journal
        sets = done.filter { it.opDate == day }.sortedWith(compareBy({ it.happenedAt }, { it.id })),
    )
}
