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
     */
    val workoutId: Long? = null,
)

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
    val workoutId: Long? = null,
)

/**
 * Ids of the sets reversed by [TYPE_SET_CANCEL] events.
 *
 * A reversal that cannot be read is skipped rather than thrown on — see [formFromEventOrNull]
 * for why one bad row must not be able to take the app down. The cost is stated plainly: the
 * set that reversal belonged to goes back to counting, which is visible in the feed and can
 * be cancelled again, whereas the alternative is four screens that do not open.
 */
fun cancelledEventIds(events: List<JournalEvent>): Set<Long> =
    events.filter { it.type == TYPE_SET_CANCEL }
        .mapNotNull { runCatching { payloadJson.decodeFromString<SetCancel>(it.payload).cancels }.getOrNull() }
        .toSet()

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
    val cancelled = if (includeCancelled) emptySet() else cancelledEventIds(events)
    val out = ArrayList<ActivityEvent>()
    for (row in events) {
        if (row.type !in typeSet) continue
        if (row.id in cancelled) continue
        val form = formFromEventOrNull(row.type, row.payload) ?: continue
        if (dateFrom != null && form.opDate < dateFrom) continue
        if (dateTo != null && form.opDate > dateTo) continue
        if (wantKey != null && form.key != wantKey) continue
        out.add(
            ActivityEvent(
                id = row.id, ts = row.ts, authorId = row.authorId, type = row.type,
                opDate = form.opDate, key = form.key, form = form, workoutId = row.workoutId,
            )
        )
    }
    return out
}

/**
 * Every catalog exercise these events point at — CANCELLED SETS INCLUDED.
 *
 * Deliberately not built on [readActivities]: that drops reversed sets, and this answers a
 * different question. A cancelled set is still a row in the journal that names its exercise,
 * so deleting the catalog row underneath it would leave a record nothing can label. Used by
 * the demo wipe to decide which of its own exercises have to be spared.
 */
fun exerciseIdsReferencedBy(events: List<JournalEvent>): Set<Long> =
    events.mapNotNullTo(HashSet()) { formFromEventOrNull(it.type, it.payload)?.exerciseId }

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
 * This crosses ALIASES: sets recorded under different words but with the same
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
