package xyz.oleolegka.gachimuchi.domain

/**
 * Journal reads and reducers — a port of read_activities / strength_sets_by_* / last_*
 * from `bot/domain.py`.
 *
 * These are DELIBERATELY PURE FUNCTIONS over a list of events rather than database
 * queries: the reducers can then be tested on the JVM without Android and Room (no
 * emulator needed), and the data layer is only responsible for "give me the events of
 * this profile". The price is that the whole journal is loaded into memory; at personal
 * scale (thousands of events) that is nothing.
 */

/** A raw journal event: a row of the `events` table with the payload left unparsed. */
data class JournalEvent(
    val id: Long,
    val ts: String,
    val spaceId: Long,
    val authorId: Long,
    val type: String,
    val payload: String,
    /**
     * The workout this row was recorded during, or null — see
     * [xyz.oleolegka.gachimuchi.data.db.EventEntity.workoutId]. Defaulted so that the many
     * places which build an event by hand (tests, fixtures) keep meaning "no workout", which
     * is what they meant before workouts existed.
     *
     * SUPERSEDED BY [workoutUid], which is what the reducers read first. This stays for rows
     * written before schema version 9 and for rows whose workout is not in this journal.
     */
    val workoutId: Long? = null,
    /**
     * The identity of this row — see [xyz.oleolegka.gachimuchi.data.db.EventEntity.uid].
     *
     * Defaulted to a fresh one so that the fixtures which build events by hand each get a
     * distinct identity without having to say so. A shared default (the empty string, say)
     * would make every hand-built event look like the same row to anything matching on uid,
     * which is precisely what the tests here are for.
     */
    val uid: String = newUid(),
    /** The workout this row was recorded during, by identity rather than by local number. */
    val workoutUid: String? = null,
    /**
     * The day this row states it is about, off the column rather than out of the payload — see
     * [xyz.oleolegka.gachimuchi.data.db.EventEntity.opDate].
     *
     * Null for a row that is about no training day, and null for a row written before schema
     * version 16 whose payload the migration could not read. Both mean "ask the payload", which
     * is what [readActivities] does.
     *
     * NOT the last word on an amended entry: a correction may move an entry to another day, and
     * the correction is a different row. Nothing here should be compared against a date range
     * without first checking that nothing has amended this row.
     */
    val opDate: String? = null,
    /** The instant this row was written — see [xyz.oleolegka.gachimuchi.data.db.EventEntity.tsUtc]. */
    val tsUtc: String? = null,
    /** Minutes east of UTC when it was written — see [xyz.oleolegka.gachimuchi.data.db.EventEntity.tzOffsetMin]. */
    val tzOffsetMin: Int? = null,
)

/**
 * What a row says about the workout it belongs to — an identity where the row has one, and a
 * local number where it is old enough not to.
 *
 * ONE TYPE FOR BOTH so that "which workout is this" is answered in one place instead of at
 * every reader, each with its own idea of which link wins. Null is not represented here: a row
 * that names no workout has no [WorkoutRef] at all.
 */
data class WorkoutRef(val uid: String?, val id: Long?) {
    /**
     * Whether this reference points at the workout opened by [start].
     *
     * The uid decides whenever the reference has one — including when it does NOT match, which
     * is the important half: a row carrying a uid that names another workout must not fall
     * back to its stale number and land in this one. The number is consulted only for a row
     * that never had a uid to begin with.
     */
    fun matches(start: JournalEvent): Boolean =
        if (uid != null) uid == start.uid else id != null && id == start.id
}

/**
 * Which catalog exercise an entry is about — the identity where the entry has one, and the
 * local row number where it is old enough not to.
 *
 * ── One funnel, so that "the same exercise" means one thing ─────────────────────
 * Nine reducers used to compare `form.exerciseId` to a number by hand. Every one of them was
 * a place where the answer could be given differently, and the whole point of the catalog
 * (§11) is that an exercise has exactly one history. So the comparison lives here and the
 * reducers ask it.
 *
 * [key] is the grouping key: the identity when there is one, and the number otherwise. The
 * 9 -> 10 migration fills the identity in for every entry it can resolve, so a real journal is
 * keyed consistently rather than half by one and half by the other.
 */
data class ExerciseLink(val uid: String?, val id: Long?) {

    /** Stable key for grouping entries of one exercise together. */
    val key: String get() = uid ?: "id:$id"

    /**
     * Whether two references name the same exercise.
     *
     * IDENTITIES WIN WHENEVER BOTH SIDES CAN SPEAK THEM, and the numbers are consulted only
     * when one of the two cannot — an entry written before version 10, or a caller that holds
     * nothing but a row number. Falling back to the number while both sides carry uids would
     * undo the point of having them: two devices hand out the same numbers to different
     * exercises, and a merged journal would weld two histories into one.
     */
    fun matches(other: ExerciseLink): Boolean =
        if (uid != null && other.uid != null) uid == other.uid else id != null && id == other.id

    /**
     * The same reference, said as fully as the two sources between them can say it.
     *
     * Used where entries of one exercise are gathered: the first entry may carry only a
     * number and a later one both, and the group should end up knowing everything that was
     * said about what it is.
     */
    fun mergedWith(other: ExerciseLink): ExerciseLink =
        ExerciseLink(uid ?: other.uid, id ?: other.id)

    companion object {
        /** For a caller that holds only a local row number — screens navigate by number. */
        fun ofId(id: Long): ExerciseLink = ExerciseLink(null, id)
    }
}

/** What an entry says about its exercise, or null when it names none (body weight). */
fun ActivityForm.exerciseLink(): ExerciseLink? =
    if (exerciseUid == null && exerciseId == null) null else ExerciseLink(exerciseUid, exerciseId)

/** A parsed domain event: the raw journal row plus its typed form. */
data class ActivityEvent(
    val id: Long,
    val ts: String,
    val authorId: Long,
    val type: String,
    val opDate: String,
    val key: String?,
    val form: ActivityForm,
    /** Carried through from the journal row so that a workout can be folded out of these. */
    val workout: WorkoutRef? = null,
    /** Identity of the underlying journal row — see [JournalEvent.uid]. */
    val uid: String = newUid(),
    /**
     * When this entry was last corrected, or null when nobody has — see [EntryState.amendedAt].
     *
     * Carried here so that a screen showing an entry can say it was edited without folding the
     * journal again for the answer. Nothing in the domain branches on it: [form] already holds
     * the corrected values, which is the whole point of applying them in one place.
     */
    val amendedAt: String? = null,
)

/**
 * Identities of the events the journal no longer holds — deleted by [TYPE_ENTRY_DELETED] or by
 * its predecessor [TYPE_SET_CANCEL], and not themselves deleted since.
 *
 * A thin reading of [journalView] rather than a second implementation of the same fold: it used
 * to BE the implementation, which is exactly how the reducers that did not call it ended up
 * disagreeing with the ones that did.
 */
fun deletedEventUids(events: List<JournalEvent>): Set<String> {
    val view = journalView(events)
    return events.filterNotTo(ArrayList()) { view.isAlive(it) }.mapTo(HashSet()) { it.uid }
}

/**
 * Exercises the journal currently says are DELETED (see [TYPE_EXERCISE_DELETED]) — the catalog
 * rows a screen listing exercises must not offer, on top of and independent from
 * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.hidden].
 *
 * A second, independent fold of the same journal rather than a value handed out of
 * [journalView]'s own cascade — the same trade [deletedEventUids] already makes, for the same
 * reason: [JournalView] answers about ROWS, and folding it a second time to ask about exercises
 * is cheaper than a second return channel out of the one funnel, at the size of journal this app
 * ever holds.
 *
 * Returned as [ExerciseLink]s and not bare uids because a caller compares them against a
 * catalog row that may itself carry only a number — a merged journal, or one written before
 * schema version 10 — and [ExerciseLink.matches] is the one place that comparison is allowed to
 * happen.
 */
fun deletedExerciseLinks(events: List<JournalEvent>): List<ExerciseLink> {
    val view = journalView(events)
    return events.asSequence()
        .filter { it.type == TYPE_EXERCISE_DELETED && view.isAlive(it) }
        .mapNotNull { row -> runCatching { payloadJson.decodeFromString<ExerciseDeleted>(row.payload) }.getOrNull() }
        .map { it.link() }
        .toList()
}

/**
 * Domain events from the journal, in journal order, WITH EVERY CORRECTION APPLIED.
 *
 * The filters are combined with AND: [types] (all of [ACTIVITY_TYPES] by default), the
 * inclusive [dateFrom]..[dateTo] range over op_date, and an exact normalized [key]
 * (body weight is excluded whenever a key is given — it has no key).
 *
 * Deleted entries are dropped and amended ones carry their amended values — both decided by
 * [journalView] and by nothing here, so that this reducer and the ones that do not go through
 * it cannot answer differently. [includeDeleted] = true asks for the history instead: the
 * deleted entries come back, still carrying their corrections. Note what it does NOT do — it
 * does not undo an amendment. A correction is what the entry says; a deletion is whether it is
 * there at all, and only the second one has a reason to be looked past.
 *
 * A row whose payload will not parse is SKIPPED, not thrown on ([formFromEventOrNull]).
 * This function is the floor every screen stands on, and one damaged row used to take all
 * of them down at once.
 */
fun readActivities(
    events: List<JournalEvent>,
    types: Collection<String> = ACTIVITY_TYPES,
    dateFrom: String? = null,
    dateTo: String? = null,
    key: String? = null,
    includeDeleted: Boolean = false,
): List<ActivityEvent> {
    val typeSet = types.toSet()
    val wantKey = key?.let { normPhrase(it) }
    val view = journalView(events)
    val out = ArrayList<ActivityEvent>()
    for (row in events) {
        if (row.type !in typeSet) continue
        val state = view.stateOf(row)
        if (state.deleted && !includeDeleted) continue
        /*
         * THE COLUMN DECIDES THE RANGE WHERE IT IS ALLOWED TO, which is the point of it being a
         * column (see EventEntity.opDate): a row outside the window is rejected without its
         * payload ever being parsed, and parsing every payload in the journal to find out which
         * week a set was in is what this used to cost.
         *
         * Only where NOTHING HAS AMENDED THE ROW. An amendment may re-date an entry, the
         * amendment is a separate row, and the append-only journal does not go back and rewrite
         * the column on the original — so for a corrected entry the amended payload is the only
         * thing that knows which day it belongs to, and it is read in full. Rows written before
         * schema version 16 carry no column at all and take the same path, which is the one the
         * whole journal used to take.
         */
        if (state.amendedAt == null && row.opDate != null) {
            if (dateFrom != null && row.opDate < dateFrom) continue
            if (dateTo != null && row.opDate > dateTo) continue
        }
        val form = formFromEventOrNull(row.type, state.payload) ?: continue
        if (dateFrom != null && form.opDate < dateFrom) continue
        if (dateTo != null && form.opDate > dateTo) continue
        if (wantKey != null && form.key != wantKey) continue
        out.add(
            ActivityEvent(
                id = row.id, ts = row.ts, authorId = row.authorId, type = row.type,
                opDate = form.opDate, key = form.key, form = form, workout = row.workoutRef(),
                uid = row.uid, amendedAt = state.amendedAt,
            )
        )
    }
    return out
}

/** Strength sets of one exercise on one day (a plain reducer, no record detection). */
data class StrengthDayGroup(
    val exerciseKey: String,
    val opDate: String,
    val sets: List<StrengthSet>,
)

/** Groups strength sets by (exercise, day); ordered by (day, key). */
fun strengthSetsByExerciseDay(
    events: List<JournalEvent>,
    dateFrom: String? = null,
    dateTo: String? = null,
    key: String? = null,
): List<StrengthDayGroup> {
    val groups = LinkedHashMap<Pair<String, String>, MutableList<StrengthSet>>()
    for (ev in readActivities(events, listOf(TYPE_STRENGTH_SET), dateFrom, dateTo, key)) {
        val set = ev.form as StrengthSet
        groups.getOrPut(set.exerciseKey to ev.opDate) { mutableListOf() }.add(set)
    }
    return groups.entries
        .sortedWith(compareBy({ it.key.second }, { it.key.first }))
        .map { StrengthDayGroup(it.key.first, it.key.second, it.value) }
}

/**
 * All sets of a given canonical exercise (§11), in journal order.
 *
 * This crosses SPELLINGS: sets recorded under different words but naming the same exercise
 * are collected together — grouping by the written key cannot achieve that.
 *
 * Which link decides is [ExerciseLink.matches] and nothing here. Caveat (the seam): entries
 * naming no exercise at all — written before the catalog existed — belong to no exercise and
 * show up for none.
 */
inline fun <reified T : ActivityForm> formsOfExercise(
    events: List<JournalEvent>,
    exercise: ExerciseLink,
    type: String,
): List<T> = readActivities(events, listOf(type))
    .mapNotNull { it.form as? T }
    .filter { it.exerciseLink()?.matches(exercise) == true }

fun strengthSetsOfExercise(events: List<JournalEvent>, exercise: ExerciseLink): List<StrengthSet> =
    formsOfExercise(events, exercise, TYPE_STRENGTH_SET)

fun holdSetsOfExercise(events: List<JournalEvent>, exercise: ExerciseLink): List<HoldSet> =
    formsOfExercise(events, exercise, TYPE_HOLD_SET)

/** The last non-cancelled hold set of an exercise (the basis for prefilling the entry card). */
fun lastHoldSet(events: List<JournalEvent>, exercise: ExerciseLink): HoldSet? =
    holdSetsOfExercise(events, exercise).lastOrNull()

/** The last non-cancelled cardio entry of an exercise. */
fun lastCardio(events: List<JournalEvent>, exercise: ExerciseLink): Cardio? =
    formsOfExercise<Cardio>(events, exercise, TYPE_CARDIO).lastOrNull()

/** The body weight series by day (in journal order). */
fun bodyweightSeries(events: List<JournalEvent>): List<Bodyweight> =
    readActivities(events, listOf(TYPE_BODYWEIGHT)).map { it.form as Bodyweight }

/**
 * Days within the range that carry an actual training FACT (ISO strings).
 *
 * Body weight is excluded on purpose (a port of `schedule.FACT_TYPES`): stepping on the
 * scales is not training, otherwise a morning weigh-in would cover up a missed evening
 * gym session.
 */
val FACT_TYPES: List<String> = ACTIVITY_TYPES.filter { it != TYPE_BODYWEIGHT }

fun activeDays(
    events: List<JournalEvent>,
    dateFrom: String,
    dateTo: String,
    types: Collection<String> = FACT_TYPES,
): Set<String> = readActivities(events, types, dateFrom, dateTo).map { it.opDate }.toSet()
