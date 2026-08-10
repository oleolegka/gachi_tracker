package xyz.oleolegka.gachimuchi.domain

/**
 * The one-time conversion of a journal's LEGACY patch amendments (schema versions before 21)
 * into the full-version model — see domain/Amendments.kt's header for the model itself and
 * [TYPE_ENTRY_AMENDED]'s own KDoc for what is being replaced.
 *
 * ── What this does, in one sentence ──────────────────────────────────────────────
 * For every row that is currently LIVE and has at least one live legacy amendment applied to
 * it, write one new row carrying the FOLDED-TOGETHER result (exactly what [journalView] already
 * computes for it) and one [TYPE_ENTRY_DELETED] marking the original superseded by that new
 * row. Nothing else moves: a row nobody ever amended is left exactly as it was, and a row that
 * is currently DEAD (deleted, with or without amendments under it) is left exactly as it was
 * too — [EntryDeleted] with no successor already says "gone" correctly, and there is no current
 * version for a dead lineage to hand a successor to.
 *
 * ── One hop, not one per legacy amendment ────────────────────────────────────────
 * A row amended twice does not become a chain of two new versions here; it becomes ONE, holding
 * the fully-folded result — the same value [journalView]'s existing merge already produces today
 * for [EntryState.payload]. This is a deliberate simplification, not an oversight: the
 * acceptance test this migration has to pass is that the CURRENT state folds identically before
 * and after, and a single hop proves exactly that with less to get wrong. What is traded away is
 * the version-by-version granularity of which value held between the two corrections — a fact
 * nothing on this phone currently reads and the raw legacy rows still hold, unconverted, for
 * anyone who goes looking.
 *
 * ── The moment stamped on the new pair ───────────────────────────────────────────
 * [EntryState.amendedAt] — the ts of the winning amendment [journalView] already resolved this
 * lineage to. `tsUtc`/`tzOffsetMin` are taken from that SAME winning row when it can be found
 * again (an exact ts + [TYPE_ENTRY_AMENDED] + naming this uid + still live is enough to pick it
 * out uniquely on any journal this app ever wrote), and left null otherwise — the same "cannot
 * be recovered" answer schema version 16 already gives a row from before it existed, rather
 * than a fabricated instant.
 */

/** One row this migration wants appended — the shape a fresh insert needs. */
data class LegacyAmendmentMigrationRow(
    val uid: String = newUid(),
    val type: String,
    val payload: String,
    val ts: String,
    val tsUtc: String?,
    val tzOffsetMin: Int?,
    val workoutId: Long?,
    val workoutUid: String?,
)

/**
 * Plans the conversion described above. Pure, and safe to call more than once over the same
 * events: a row already converted (no live [TYPE_ENTRY_AMENDED] left applying to it — because,
 * say, this plan's own output was folded back in first) is not converted again.
 */
fun planLegacyAmendmentMigration(events: List<JournalEvent>): List<LegacyAmendmentMigrationRow> {
    val view = journalView(events)
    val out = ArrayList<LegacyAmendmentMigrationRow>()

    for (row in events) {
        if (row.isControlEvent()) continue // only a row that IS training or a service fact has a "current version" to hand off
        val state = view.stateOf(row)
        if (state.deleted) continue // superseded-with-nothing already says "gone" correctly; see this file's own KDoc
        if (state.amendedAt == null) continue // never touched: still a single, self-sufficient row

        val winner = events.firstOrNull { candidate ->
            candidate.type == TYPE_ENTRY_AMENDED && candidate.ts == state.amendedAt && view.isAlive(candidate) &&
                runCatching { payloadJson.decodeFromString<EntryAmended>(candidate.payload).targetUid }
                    .getOrNull() == row.uid
        }

        val newVersion = LegacyAmendmentMigrationRow(
            type = row.type, payload = state.payload, ts = state.amendedAt,
            tsUtc = winner?.tsUtc, tzOffsetMin = winner?.tzOffsetMin,
            workoutId = row.workoutId, workoutUid = row.workoutUid,
        )
        out += newVersion
        out += LegacyAmendmentMigrationRow(
            type = TYPE_ENTRY_DELETED,
            payload = payloadJson.encodeToString(EntryDeleted(targetUid = row.uid, successorUid = newVersion.uid)),
            ts = state.amendedAt, tsUtc = winner?.tsUtc, tzOffsetMin = winner?.tzOffsetMin,
            workoutId = null, workoutUid = null,
        )
    }
    return out
}
