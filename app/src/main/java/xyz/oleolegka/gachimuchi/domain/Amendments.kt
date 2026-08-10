package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Corrections and deletions, and THE ONE PLACE that applies them.
 *
 * ── The problem this file exists to end ─────────────────────────────────────────
 * The journal is append-only, so "undo" has always been a second event pointing back at the
 * first ([TYPE_SET_CANCEL]). That worked, and it was applied in exactly one reducer —
 * `readActivities` — which meant it worked for the readers that happened to go through it and
 * silently did not for the ones that did not. `workoutStarts` and `buildWorkout` read the raw
 * list; a cancelled row could not be seen by the logging feed and could still be seen inside a
 * workout. There was no way to remove a workout started by mistake, no way to remove an
 * exercise added to it by mistake, and no way at all to CORRECT a set: only to cancel the last
 * one and type it again.
 *
 * So the rule moved here, and it is a rule about EVENTS rather than about sets:
 *
 *  - [journalView] is the single answer to "is this event still there, and what does it say
 *    now"; [liveEvents] is that answer applied to a whole journal;
 *  - EVERY reader starts by calling one of the two. Not "should" — the ones that did not are
 *    fixed in this same change, and a reader that reads `List<JournalEvent>` without going
 *    through here is a bug of the kind this file is named after.
 *
 * ── The fold, written out because a verdict has to be predictable ───────────────
 * For one target event:
 *
 * 1. If any LIVE deletion names it, it is gone. DELETION WINS over every amendment, whenever
 *    it was written — a correction to something that should not exist is not a reason to keep
 *    it. There is no "restore" event: a deletion is undone by deleting the deletion.
 * 2. Otherwise every LIVE amendment naming it is laid over its payload IN TIME ORDER (`ts`,
 *    then journal order for the same second), so the last word wins — FIELD BY FIELD. Two
 *    amendments that touch different fields both count; two that touch the same field are
 *    settled by the later one. See the note below on what that is not.
 * 3. Keys in [AMENDMENT_PROTECTED_KEYS] are ignored here as well as refused on the way in, so
 *    an amendment arriving from another journal cannot move a set to another exercise either.
 * 4. `ts`, `type` and the workout columns are NOT amendable, because they are not payload. The
 *    honest write time of the original row stays what it was; an amendment records its own.
 *
 * "LIVE deletion" and "LIVE amendment" are the recursive part, and they are what makes undoing
 * an undo work: a control event is itself an event, so deleting it takes its effect away.
 * D deletes the set, D2 deletes D, and the set is back. D3 deletes D2 and it is gone again.
 * The chain is followed to its end rather than one step deep, which costs a memoised recursion
 * and buys a rule with no arbitrary depth in it.
 *
 * ── What "the last amendment wins" does NOT mean here ───────────────────────────
 * It is per FIELD and not per amendment: an earlier correction of the reps and a later one of
 * the weight both survive. Taking the last amendment WHOLE and dropping the earlier ones would
 * be a defensible reading too, and it would quietly undo a correction the user made and never
 * revisited — which is the worse of the two failures, so it is not the one chosen. The
 * difference only shows for partial patches; an editor that sends every field of the form
 * cannot tell the two rules apart.
 *
 * ── The costs, stated ──────────────────────────────────────────────────────────
 * An amendment can make a payload UNREADABLE (zero reps, a date that is not a date). The
 * readers skip what will not parse ([formFromEventOrNull]), so such an entry disappears rather
 * than throwing — which is a deletion nobody asked for. [ActivityRepository.amendEntry] refuses
 * to write one, so this can only arrive from outside; it is not defended against here, because
 * the alternative is inventing a payload the user never wrote.
 *
 * A cycle (two deletions naming each other) cannot be written by this app and would have to be
 * merged in. It is broken by treating the events in it as ALIVE, which is the answer that
 * loses no data.
 *
 * ── [TYPE_EXERCISE_DELETED] is a fifth step, and it is a CASCADE rather than a target ───
 * Every other control event names one row. This one names an EXERCISE, and the fold applies it
 * to every row that names the same exercise in its own payload — a set, a "this is part of that
 * workout now" row, a "this card is done" row — as if each of them had been given its own
 * [TYPE_ENTRY_DELETED]. One event, the whole exercise's history gone from here on; delete the
 * deletion (the ordinary way, naming ITS uid) and the whole history is back. See
 * [TYPE_EXERCISE_DELETED]'s own KDoc in domain/Forms.kt for why this lives here and not as a
 * second boolean beside [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.hidden].
 *
 * ── A correction is now a whole new row, not a patch on the old one ─────────────
 * [TYPE_ENTRY_AMENDED] (the PATCH shape above) is legacy: [ActivityRepository.amendEntry] no
 * longer writes one. A correction today is an ordinary new row — of the corrected entry's own
 * type, carrying every field, exactly as if it had been logged fresh — plus a
 * [TYPE_ENTRY_DELETED] naming the OLD row as its target and the NEW one as
 * [EntryDeleted.successorUid]. Nothing in the fold above changes for this: a superseded row is
 * simply DEAD, the same as a plainly deleted one, and the new row is simply a LIVE row nobody
 * has touched — [journalView] does not need to know the two are related to answer "is this
 * event still there, and what does it say now" correctly for either of them.
 *
 * It does need to know for ONE thing this file alone is responsible for: undoing a correction
 * the same way undoing a deletion already works. Deleting the [TYPE_ENTRY_DELETED] that links
 * old row to new brings the OLD row back (nothing new there — it is targeted, like any
 * deletion) and must ALSO take the NEW row back down, or both would read live at once and an
 * entry that was corrected once would appear to have happened twice. So a row that is the
 * SUCCESSOR named by a link mirrors that link's own liveness: alive exactly when the link
 * that created it is alive, dead the moment that link is undone. See `isDead`'s `creator`
 * branch below.
 *
 * ── Rows that other rows point at by uid: the identity that changes ─────────────
 * A workout's own `workout_started` row is such a target — every set and "exercise added" row
 * recorded into it carries that row's uid in its `workout_uid` COLUMN. Correcting the workout
 * itself (its date, its name) writes a NEW `workout_started` row with a NEW uid, exactly like
 * correcting anything else — and every row already pointing at the old uid would read as
 * belonging to nothing, the moment the old row goes dead, unless something resolves the old
 * uid forward. [JournalView.canonicalUid] is that resolver, and [JournalView.revised] applies
 * it to every live row's `workout_uid`/`workout_id` columns on the way out — so a reader never
 * has to know a workout was ever corrected at all; the children simply keep pointing at
 * whichever row is current.
 */

/** What the journal says about one event NOW, once every correction has been applied. */
data class EntryState(
    /** Deleted by [TYPE_ENTRY_DELETED] or by the older [TYPE_SET_CANCEL]. */
    val deleted: Boolean,
    /** The payload to read, which is the original with every live amendment laid over it. */
    val payload: String,
    /**
     * `ts` of the last amendment applied, or null when nobody corrected this event.
     *
     * Here so that a screen can say "edited" without folding the journal a second time to find
     * out; nothing in the domain branches on it.
     */
    val amendedAt: String?,
) {
    val amended: Boolean get() = amendedAt != null
}

/**
 * The journal's corrections, resolved once and then asked about individual rows.
 *
 * Built by [journalView]. Holding it lets a caller ask about many rows for the price of one
 * fold; [liveEvents] is the answer for a caller that just wants the list.
 */
class JournalView internal constructor(
    private val states: Map<String, EntryState>,
    /** `targetUid -> successorUid`, LIVE links only — see [canonicalUid]. */
    private val liveSuccessorOfUid: Map<String, String> = emptyMap(),
    private val idOfUid: Map<String, Long> = emptyMap(),
    private val uidOfId: Map<Long, String> = emptyMap(),
) {

    /** What the journal now says about [row]. An event nobody touched answers for itself. */
    fun stateOf(row: JournalEvent): EntryState =
        states[row.uid] ?: EntryState(deleted = false, payload = row.payload, amendedAt = null)

    /** Whether this event is still to be read at all. */
    fun isAlive(row: JournalEvent): Boolean = !stateOf(row).deleted

    /**
     * Follows [uid] forward through however many LIVE full-version corrections it has been
     * through, and hands back whichever uid is CURRENT — [uid] itself when nothing ever
     * corrected it, or when the chain that once did has since been undone.
     *
     * What this is for: a row that other rows reference by uid (a `workout_started` event is
     * the one this app writes) can itself be corrected, which gives it a NEW uid — see the
     * class KDoc's "A correction is now a whole new row" section. A child recorded before that
     * correction still carries the OLD uid in its own `workout_uid` column, forever, because
     * the journal is append-only. This is how that column is read as still meaning the same
     * workout: not by rewriting it, but by resolving it forward on the way out — see [revised].
     *
     * A cycle cannot be written by this app (a chain only grows, one hop per correction) and is
     * broken the same defensive way a deletion cycle is: the guard simply stops following and
     * returns wherever it had reached, rather than looping forever over a merged-in journal.
     */
    fun canonicalUid(uid: String): String {
        var current = uid
        val seen = HashSet<String>()
        while (true) {
            val next = liveSuccessorOfUid[current] ?: return current
            if (!seen.add(current)) return current
            current = next
        }
    }

    /** [canonicalUid], said in the numeric id a column carries alongside its uid. */
    fun canonicalId(id: Long): Long {
        val uid = uidOfId[id] ?: return id
        return idOfUid[canonicalUid(uid)] ?: id
    }

    /**
     * [row] as it should now be read, or null when it has been deleted.
     *
     * The identity, the id, the type and the write time are the row's own and are never
     * rewritten. The payload is, by a live correction naming this row; the `workout_id`/
     * `workout_uid` columns are, by [canonicalUid] — see its own KDoc for why a column that
     * names another row has to be resolved forward rather than trusted as written.
     */
    fun revised(row: JournalEvent): JournalEvent? {
        val state = stateOf(row)
        if (state.deleted) return null
        val workoutUid = row.workoutUid?.let(::canonicalUid)
        val workoutId = row.workoutId?.let(::canonicalId)
        return if (state.payload == row.payload && workoutUid == row.workoutUid && workoutId == row.workoutId) {
            row
        } else {
            row.copy(
                payload = state.payload,
                workoutUid = workoutUid ?: row.workoutUid,
                workoutId = workoutId ?: row.workoutId,
            )
        }
    }
}

/** The kinds of event that speak about another event rather than about training. */
private val CONTROL_TYPES =
    setOf(TYPE_SET_CANCEL, TYPE_ENTRY_DELETED, TYPE_ENTRY_AMENDED, TYPE_EXERCISE_DELETED)

/**
 * Whether this row is a correction or a deletion rather than something that happened.
 *
 * Public because "is this an event, or a statement about one" is a question the feed and any
 * future history screen will ask, and because the answer must be the same everywhere.
 */
fun JournalEvent.isControlEvent(): Boolean = type in CONTROL_TYPES

/**
 * THE FUNNEL. Resolves every deletion and amendment in the journal into one answer per event.
 *
 * The header of this file is the rule; this is it in code. Callers that want the whole journal
 * back should use [liveEvents] instead — it is this plus one map.
 */
fun journalView(events: List<JournalEvent>): JournalView {
    val controls = events.filter { it.isControlEvent() }
    if (controls.isEmpty()) return JournalView(emptyMap())

    // built lazily because only the pre-uid spelling of set_cancel needs translating, and
    // building it walks the whole journal
    val uidOfNumber: Map<Long, String> by lazy { events.associate { it.id to it.uid } }

    val deletionsOf = HashMap<String, MutableList<JournalEvent>>()
    val amendmentsOf = HashMap<String, MutableList<Pair<JournalEvent, Map<String, JsonElement>>>>()
    // TYPE_EXERCISE_DELETED rows, kept aside rather than folded into deletionsOf: they do not
    // name an EVENT's uid, they name an EXERCISE's — see the cascade below.
    val exerciseDeletions = ArrayList<Pair<JournalEvent, ExerciseLink>>()
    // TYPE_ENTRY_DELETED rows that also name a successor — the marker half of a full-version
    // correction (see the class KDoc's "A correction is now a whole new row" section) — kept
    // by the SUCCESSOR's uid, alongside the row that created the link and what it targets, so
    // isDead's mirror rule below and canonicalUid's chain can both be built off it.
    val successorCreator = HashMap<String, Pair<JournalEvent, String>>()

    for (row in controls) {
        when (row.type) {
            TYPE_SET_CANCEL -> {
                // the older spelling: an identity when it has one, a row number when it is old
                // enough not to. A reversal naming neither names nothing and is dropped, which
                // is what it has always done.
                val payload = runCatching { payloadJson.decodeFromString<SetCancel>(row.payload) }.getOrNull()
                val target = payload?.cancelsUid ?: payload?.cancels?.let { uidOfNumber[it] }
                if (target != null) deletionsOf.getOrPut(target) { mutableListOf() } += row
            }

            TYPE_ENTRY_DELETED -> {
                val payload = runCatching { payloadJson.decodeFromString<EntryDeleted>(row.payload) }.getOrNull()
                if (payload != null) {
                    deletionsOf.getOrPut(payload.targetUid) { mutableListOf() } += row
                    payload.successorUid?.let { successorCreator[it] = row to payload.targetUid }
                }
            }

            TYPE_ENTRY_AMENDED -> {
                val payload = runCatching { payloadJson.decodeFromString<EntryAmended>(row.payload) }.getOrNull()
                if (payload != null) {
                    amendmentsOf.getOrPut(payload.targetUid) { mutableListOf() } += row to payload.allowedFields
                }
            }

            TYPE_EXERCISE_DELETED -> {
                val payload = runCatching { payloadJson.decodeFromString<ExerciseDeleted>(row.payload) }.getOrNull()
                if (payload != null) exerciseDeletions += row to payload.link()
            }
        }
    }

    /*
     * Deadness is recursive: a deletion counts only while it has not itself been deleted. The
     * memo makes a chain cost one walk rather than one per reader, and `visiting` breaks a
     * cycle in favour of "alive" — a corrupt pair of events naming each other must not be able
     * to hide training.
     *
     * A SUCCESSOR uid mirrors its creating link instead of being targeted by one: it has no
     * `killers` of its own from this, but it must go dead the instant the link that brought it
     * into being is undone, or the old row that link superseded would come back to a journal
     * that also still shows the new one — one correction, read as two entries. Both rules are
     * combined with OR because a uid can be both: the middle row of a chain of two corrections
     * is a live SUCCESSOR of the first link and the live TARGET of the second, and either fact
     * alone is enough to say it is not the current version.
     */
    val dead = HashMap<String, Boolean>()
    val visiting = HashSet<String>()

    fun isDead(uid: String): Boolean {
        dead[uid]?.let { return it }
        val killers = deletionsOf[uid]
        val creator = successorCreator[uid]?.first
        if (killers == null && creator == null) return false
        if (!visiting.add(uid)) return false
        val answer = (killers?.any { !isDead(it.uid) } ?: false) || (creator != null && isDead(creator.uid))
        visiting.remove(uid)
        dead[uid] = answer
        return answer
    }

    /*
     * Exercises currently deleted, as the LIVE ones only — an exercise_deleted row is an event
     * like any other, and [isDead] already knows how to ask whether IT has itself been deleted
     * (by a TYPE_ENTRY_DELETED naming its own uid), with no special case needed here for that.
     */
    val deadExercises = exerciseDeletions.filter { (row, _) -> !isDead(row.uid) }.map { it.second }

    val byUid = events.associateBy { it.uid }
    val states = HashMap<String, EntryState>()

    /*
     * THE CASCADE: every row whose OWN exercise reference names a dead exercise — a set, a
     * "this is part of that workout now" row, a "this card is done" row — is folded dead here
     * too, from the single exercise_deleted event rather than one deletion written per row.
     *
     * Read GENERICALLY off the payload ([rawExerciseLink]) rather than through a typed form on
     * a `when` of every event type: the two field names are the same on every shape that names
     * an exercise at all (see [AMENDMENT_PROTECTED_KEYS], which is what keeps them from ever
     * meaning something else), so reading them once here is what lets this cascade cover a form
     * this file has never heard of, the same way [mergePayload] does not need to know one either.
     */
    val deadFromExercise: Set<String> = if (deadExercises.isEmpty()) {
        emptySet()
    } else {
        events.asSequence()
            .filter { row -> row.rawExerciseLink()?.let { link -> deadExercises.any { it.matches(link) } } == true }
            .mapTo(HashSet()) { it.uid }
    }

    for (uid in deletionsOf.keys + amendmentsOf.keys + deadFromExercise) {
        val row = byUid[uid] ?: continue // names an event this journal does not hold: inert
        val deleted = isDead(uid) || uid in deadFromExercise
        /*
         * The corrections are folded EVEN FOR A DELETED ENTRY, rather than skipped as a saving.
         * The two facts are independent: "is it there" and "what does it say". A reader asking
         * for the history (readActivities with includeDeleted) wants the entry as it last read,
         * not as it was first typed — and the alternative would make deleting an entry quietly
         * roll back every correction ever made to it, which is a second thing happening on a
         * button that promised one.
         */
        val live = amendmentsOf[uid].orEmpty().filter { (amendment, _) -> !isDead(amendment.uid) }
        if (live.isEmpty()) {
            if (deleted) states[uid] = EntryState(true, row.payload, amendedAt = null)
            continue
        }
        // ts first, then journal order, so two corrections written in the same second still
        // settle the same way on every reading
        val ordered = live.sortedWith(compareBy({ it.first.ts }, { it.first.id }))
        val merged = mergePayload(row.payload, ordered.map { it.second })
        states[uid] = EntryState(
            deleted = deleted,
            payload = merged ?: row.payload,
            amendedAt = if (merged == null) null else ordered.last().first.ts,
        )
    }

    // the chain [JournalView.canonicalUid] walks: only LIVE links count, on the same grounds
    // every other "which of these control events is in effect" question in this fold is asked
    if (successorCreator.isEmpty()) return JournalView(states)
    val liveSuccessorOfUid = successorCreator
        .filterValues { (creator, _) -> !isDead(creator.uid) }
        .entries.associate { (successorUid, targetPair) -> targetPair.second to successorUid }
    val idOfUid = events.associate { it.uid to it.id }
    val uidOfId = events.associate { it.id to it.uid }
    return JournalView(states, liveSuccessorOfUid, idOfUid, uidOfId)
}

/**
 * What [row]'s own payload says about the exercise it belongs to, read directly rather than
 * through one of the six typed forms — see the cascade in [journalView] for why: decoding into
 * a class that carries only the two fields every such payload agrees on
 * ([AMENDMENT_PROTECTED_KEYS] is where that agreement is enforced on write) works for a set, an
 * "added" row and a "finished" row alike, with `ignoreUnknownKeys` doing the rest.
 *
 * Null for a row that names no exercise at all (body weight, and every service event that is
 * not about one particular exercise) and for a payload that will not even parse — the same
 * "skip, do not throw" rule [formFromEventOrNull] follows.
 */
private fun JournalEvent.rawExerciseLink(): ExerciseLink? {
    val fields = runCatching { payloadJson.decodeFromString<RawExerciseFields>(payload) }.getOrNull() ?: return null
    return if (fields.exerciseUid == null && fields.exerciseId == null) {
        null
    } else {
        ExerciseLink(fields.exerciseUid, fields.exerciseId)
    }
}

/** The two keys [rawExerciseLink] reads off an arbitrary payload — see its own KDoc. */
@Serializable
private data class RawExerciseFields(
    @SerialName("exercise_id") val exerciseId: Long? = null,
    @SerialName("exercise_uid") val exerciseUid: String? = null,
)

/**
 * The original payload with each patch laid over it in turn, or null when the original is not
 * a JSON object at all.
 *
 * Null rather than a throw for the usual reason (see [formFromEventOrNull]): a row that cannot
 * be read is one row, and it must not be able to take down the screen that was reading past it.
 * The caller then leaves that event exactly as it was written.
 */
private fun mergePayload(original: String, patches: List<Map<String, JsonElement>>): String? {
    val base = runCatching { payloadJson.parseToJsonElement(original) as? JsonObject }.getOrNull() ?: return null
    val merged = LinkedHashMap<String, JsonElement>(base)
    for (patch in patches) {
        for ((key, value) in patch) {
            if (key in AMENDMENT_PROTECTED_KEYS) continue
            merged[key] = value
        }
    }
    return payloadJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
}

/**
 * The journal as it now reads: deleted events gone, corrected events carrying their corrected
 * values, everything else untouched and in the order it was written.
 *
 * This is what a reducer should take as its first line. It is idempotent — applying it to its
 * own output changes nothing — so a reducer calling it after a caller already did is wasteful
 * and never wrong.
 *
 * The control events themselves are KEPT when they are still live. They are not training and
 * every reducer filters them out by type anyway, but they are the record of what was corrected,
 * and a function called "the live journal" that quietly dropped part of the journal would be
 * the wrong tool for the history screen that will want them.
 */
fun liveEvents(events: List<JournalEvent>): List<JournalEvent> {
    val view = journalView(events)
    return events.mapNotNull { view.revised(it) }
}
