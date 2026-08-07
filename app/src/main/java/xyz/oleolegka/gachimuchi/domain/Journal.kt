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
)

/**
 * Identities of the sets reversed by [TYPE_SET_CANCEL] events.
 *
 * ── Uids, not row numbers ───────────────────────────────────────────────────────
 * The reversal states the identity of what it cancels whenever it has one, and this resolves
 * the rest: a payload from before schema version 9 carries only a local row number, which is
 * looked up in this same journal and turned into the uid it meant. A number that names no row
 * here resolves to nothing and cancels nothing — which is the honest answer, and a strictly
 * better one than the old behaviour of cancelling whatever row happened to hold that number.
 *
 * A reversal that cannot be read is skipped rather than thrown on — see [formFromEventOrNull]
 * for why one bad row must not be able to take the app down. The cost is stated plainly: the
 * set that reversal belonged to goes back to counting, which is visible in the feed and can
 * be cancelled again, whereas the alternative is four screens that do not open.
 */
fun cancelledEventUids(events: List<JournalEvent>): Set<String> {
    val reversals = events.filter { it.type == TYPE_SET_CANCEL }
    if (reversals.isEmpty()) return emptySet()
    // built only when something actually needs translating, since it walks the whole journal
    val uidOfNumber: Map<Long, String> by lazy { events.associate { it.id to it.uid } }
    return reversals.mapNotNullTo(HashSet()) { row ->
        val payload = runCatching { payloadJson.decodeFromString<SetCancel>(row.payload) }.getOrNull()
        payload?.cancelsUid ?: payload?.cancels?.let { uidOfNumber[it] }
    }
}

/**
 * Domain events from the journal, in journal order.
 *
 * The filters are combined with AND: [types] (all of [ACTIVITY_TYPES] by default), the
 * inclusive [dateFrom]..[dateTo] range over op_date, and an exact normalized [key]
 * (body weight is excluded whenever a key is given — it has no key).
 * [includeCancelled] = false drops reversed sets.
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
    includeCancelled: Boolean = false,
): List<ActivityEvent> {
    val typeSet = types.toSet()
    val wantKey = key?.let { normPhrase(it) }
    val cancelled = if (includeCancelled) emptySet() else cancelledEventUids(events)
    val out = ArrayList<ActivityEvent>()
    for (row in events) {
        if (row.type !in typeSet) continue
        if (row.uid in cancelled) continue
        val form = formFromEventOrNull(row.type, row.payload) ?: continue
        if (dateFrom != null && form.opDate < dateFrom) continue
        if (dateTo != null && form.opDate > dateTo) continue
        if (wantKey != null && form.key != wantKey) continue
        out.add(
            ActivityEvent(
                id = row.id, ts = row.ts, authorId = row.authorId, type = row.type,
                opDate = form.opDate, key = form.key, form = form, workout = row.workoutRef(),
                uid = row.uid,
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
 * All sets of a given canonical exercise (by exercise_id, §11), in journal order.
 * This crosses SPELLINGS: sets recorded under different words but with the same
 * exercise_id are collected together — grouping by key cannot achieve that.
 *
 * Caveat (the seam): records with exercise_id = null (written before the catalog was
 * introduced) will not show up here for any id.
 */
inline fun <reified T : ActivityForm> formsByExerciseId(
    events: List<JournalEvent>,
    exerciseId: Long,
    type: String,
): List<T> = readActivities(events, listOf(type))
    .mapNotNull { it.form as? T }
    .filter { it.exerciseId == exerciseId }

fun strengthSetsByExerciseId(events: List<JournalEvent>, exerciseId: Long): List<StrengthSet> =
    formsByExerciseId(events, exerciseId, TYPE_STRENGTH_SET)

fun holdSetsByExerciseId(events: List<JournalEvent>, exerciseId: Long): List<HoldSet> =
    formsByExerciseId(events, exerciseId, TYPE_HOLD_SET)

/** The last non-cancelled hold set of an exercise (the basis for prefilling the entry card). */
fun lastHoldSet(events: List<JournalEvent>, exerciseId: Long): HoldSet? =
    holdSetsByExerciseId(events, exerciseId).lastOrNull()

/** The last non-cancelled cardio entry of an exercise. */
fun lastCardio(events: List<JournalEvent>, exerciseId: Long): Cardio? =
    formsByExerciseId<Cardio>(events, exerciseId, TYPE_CARDIO).lastOrNull()

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
