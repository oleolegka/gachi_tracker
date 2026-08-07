package xyz.oleolegka.gachimuchi.domain

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
class JournalView internal constructor(private val states: Map<String, EntryState>) {

    /** What the journal now says about [row]. An event nobody touched answers for itself. */
    fun stateOf(row: JournalEvent): EntryState =
        states[row.uid] ?: EntryState(deleted = false, payload = row.payload, amendedAt = null)

    /** Whether this event is still to be read at all. */
    fun isAlive(row: JournalEvent): Boolean = !stateOf(row).deleted

    /**
     * [row] as it should now be read, or null when it has been deleted.
     *
     * The identity, the id, the type, the write time and the workout links are the row's own
     * and are never rewritten — only the payload is.
     */
    fun revised(row: JournalEvent): JournalEvent? {
        val state = stateOf(row)
        if (state.deleted) return null
        return if (state.payload == row.payload) row else row.copy(payload = state.payload)
    }
}

/** The kinds of event that speak about another event rather than about training. */
private val CONTROL_TYPES = setOf(TYPE_SET_CANCEL, TYPE_ENTRY_DELETED, TYPE_ENTRY_AMENDED)

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
                if (payload != null) deletionsOf.getOrPut(payload.targetUid) { mutableListOf() } += row
            }

            TYPE_ENTRY_AMENDED -> {
                val payload = runCatching { payloadJson.decodeFromString<EntryAmended>(row.payload) }.getOrNull()
                if (payload != null) {
                    amendmentsOf.getOrPut(payload.targetUid) { mutableListOf() } += row to payload.allowedFields
                }
            }
        }
    }

    /*
     * Deadness is recursive: a deletion counts only while it has not itself been deleted. The
     * memo makes a chain cost one walk rather than one per reader, and `visiting` breaks a
     * cycle in favour of "alive" — a corrupt pair of events naming each other must not be able
     * to hide training.
     */
    val dead = HashMap<String, Boolean>()
    val visiting = HashSet<String>()

    fun isDead(uid: String): Boolean {
        dead[uid]?.let { return it }
        val killers = deletionsOf[uid] ?: return false
        if (!visiting.add(uid)) return false
        val answer = killers.any { !isDead(it.uid) }
        visiting.remove(uid)
        dead[uid] = answer
        return answer
    }

    val byUid = events.associateBy { it.uid }
    val states = HashMap<String, EntryState>()

    for (uid in deletionsOf.keys + amendmentsOf.keys) {
        val row = byUid[uid] ?: continue // names an event this journal does not hold: inert
        val deleted = isDead(uid)
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
    return JournalView(states)
}

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
