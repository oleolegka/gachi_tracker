package xyz.oleolegka.gachimuchi.domain

/**
 * What makes two catalog rows the same exercise — in one place, because the answer decides
 * whose history is whose.
 *
 * ── Why this is a type and not a comparison written where it is needed ──────────
 * §12-A says an exercise IS its name plus its work:rest protocol: "Hangs" at 7:3 and "Hangs"
 * at 10:5 are two exercises with two records and two charts. That rule was stated in the
 * documentation, honoured by the record reducers, honoured by the journal backup's merge —
 * and NOT honoured by the one place that creates rows, which looked a new exercise up by name
 * alone and handed back whatever it found. Somebody adding hangs on a different protocol while
 * the old one existed got the old row, silently, and two histories became one for good.
 *
 * (§12-A used to name a THIRD thing here too — the hangboard edge, in millimetres. That has
 * been removed from the app entirely: it was a climbing-specific value with no comparison the
 * owner wanted, and it lived in the name from now on, same as it always did in speech. See
 * `MIGRATION_17_18` in `data/db/AppDatabase.kt` for what happened to the exercises that had
 * one on file.)
 *
 * So the rule gets one implementation, and everything that has an opinion about identity
 * asks it: [xyz.oleolegka.gachimuchi.data.ActivityRepository.ensureExercise] before it
 * inserts, [mergeExercises] when a backup arrives, and the database itself through
 * [ExerciseIdentity.key], which is stored on the row and carries a UNIQUE index.
 *
 * ── The form is in here too, and that is an addition to §12-A ───────────────────
 * A "Plank" logged as a duration and a "Plank" logged as strength write different payload
 * shapes. Welding them produces one history that half the readers cannot read, which is the
 * same failure §12-A is about, arriving through a door §12-A did not name.
 *
 * ── What is deliberately NOT in here ───────────────────────────────────────────
 * The default rest, "led by protocol", the side, the body-weight share and whether the row is
 * hidden. Every one of them is a PREFERENCE or a presentation choice: it answers "how do I
 * want this trained/shown", it replaces its own previous answer by design, and it carries no
 * history that a change to it could strand. Identity is only the three things that decide
 * which sets belong together.
 */
data class ExerciseIdentity(
    /** Normalized name — see [normPhrase]. */
    val name: String,
    /** Form code, the values of [ExerciseForm]. */
    val form: Int,
    val workSec: Double?,
    val restSec: Double?,
) {
    /**
     * The identity as one string, which is what the database indexes.
     *
     * ── Why a key column and not a UNIQUE index over the three columns ─────────
     * Two of the three are nullable, and SQLite treats NULLs in a unique index as DISTINCT
     * from each other. A UNIQUE index on (name, form, protocol_work_sec, protocol_rest_sec)
     * therefore permits any number of rows called "Bench press" with no protocol — which is
     * every strength exercise there is, and the commonest duplicate by a wide margin. The
     * constraint would have looked like a constraint and enforced nothing where it was needed
     * most.
     *
     * A single NOT NULL string has no such hole. The price is that it is DERIVED state and
     * can drift from the columns it was computed from; that is paid for by making it a
     * constructor default on the entity, so a row cannot be built without it, and by making
     * the one statement that edits an identity write the new key in the same UPDATE.
     */
    val key: String get() = "$name$SEPARATOR$form$SEPARATOR${num(workSec)}$SEPARATOR${num(restSec)}"

    private fun num(value: Double?): String = value?.toString() ?: ""

    private companion object {
        /**
         * A normalized name cannot contain this — [normPhrase] keeps letters, digits and
         * spaces and nothing else — so the parts cannot run into one another.
         */
        const val SEPARATOR = "|"
    }
}

/**
 * The identity of an exercise described by its three defining values.
 *
 * The name is normalized so that spacing and case cannot split a history in two; a name with
 * no letters or digits in it at all (which [normPhrase] has nothing to make a key out of)
 * falls back to its own trimmed, lower-cased text with the separator character taken out.
 */
fun exerciseIdentity(
    name: String,
    form: Int,
    workSec: Double? = null,
    restSec: Double? = null,
): ExerciseIdentity = ExerciseIdentity(
    name = normPhrase(name) ?: name.trim().lowercase().replace("|", " "),
    form = form,
    workSec = workSec,
    restSec = restSec,
)

/** The stored identity key for a row described by these values — see [ExerciseIdentity.key]. */
fun exerciseIdentityKey(
    name: String,
    form: Int,
    workSec: Double? = null,
    restSec: Double? = null,
): String = exerciseIdentity(name, form, workSec, restSec).key

/**
 * A name that is free, given the identities already taken — used where a collision has to be
 * broken rather than reported, which is the database migration that introduces the constraint
 * and nothing else.
 *
 * Marks the NAME rather than inventing a key, because a key nobody can see is a duplicate
 * nobody can find: two rows called "Hangs" of which one silently receives every future set is
 * worse than "Hangs" and "Hangs (2)" sitting next to each other where they can be told apart,
 * renamed or hidden.
 */
fun freeExerciseName(
    name: String,
    form: Int,
    workSec: Double?,
    restSec: Double?,
    taken: Set<String>,
): String {
    if (exerciseIdentityKey(name, form, workSec, restSec) !in taken) return name
    for (n in 2..MAX_NAME_ATTEMPTS) {
        val candidate = "$name ($n)"
        if (exerciseIdentityKey(candidate, form, workSec, restSec) !in taken) return candidate
    }
    // beyond absurd, and still better than failing an upgrade: the row keeps a name nobody
    // will mistake for another one
    return "$name (${System.nanoTime()})"
}

private const val MAX_NAME_ATTEMPTS = 999

/**
 * A catalog row exactly as the database holds it, minus the columns that mean nothing off
 * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity] itself: the local row number's own space
 * ([xyz.oleolegka.gachimuchi.data.db.LOCAL_SPACE_ID] never varies) and the identity key, which
 * is derived from [name], [form], [protocolWorkSec] and [protocolRestSec] and would only ever
 * be recomputed by a reader, never trusted from one.
 *
 * ── The one place a new column has to be wired in ───────────────────────────────
 * Every view the app takes of a catalog row — the entry card's [ExerciseRef], the dashboard's
 * [CatalogExercise], the backup's `PortableExercise` — used to be read off
 * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity] independently, by mappers that each
 * remembered a different subset of its columns. A column that a mapper forgot still compiled,
 * because every field on every one of those types has a default: the column simply came back
 * empty on that one view and nowhere else, which is how the same bug shipped twice in three
 * days (§12-A one-sidedness and body-weight share, then hiding).
 *
 * [xyz.oleolegka.gachimuchi.data.toCatalogRow] is now the ONLY function that reads the entity,
 * and [toRef], [toCatalogExercise] and [xyz.oleolegka.gachimuchi.domain.toPortable] each build
 * their narrower view out of this one instead — so a column added to the entity and to this
 * class is a column every view already has, and a column added to the entity and forgotten
 * here is the one place left to go wrong.
 */
data class CatalogRow(
    val id: Long,
    val uid: String,
    val name: String,
    /** Form code, the values of [ExerciseForm]. */
    val form: Int,
    val createdAt: String,
    val protocolWorkSec: Double? = null,
    val protocolRestSec: Double? = null,
    val defaultRestSec: Int? = null,
    val ledByProtocol: Boolean? = null,
    val oneSided: Boolean = false,
    val bodyweightShare: Double? = null,
    val hidden: Boolean = false,
)

/**
 * The entry card's view of a catalog row — see [ExerciseRef].
 *
 * An unreadable form code degrades to [ExerciseForm.TICK] rather than throwing: that is the
 * only form whose entry card cannot write a wrong-shaped payload, so a corrupted row costs a
 * useless card instead of a crash on the screen the user is standing in the gym with.
 */
fun CatalogRow.toRef(): ExerciseRef = ExerciseRef(
    id = id,
    uid = uid,
    name = name,
    form = runCatching { ExerciseForm.fromCode(form) }.getOrDefault(ExerciseForm.TICK),
    workSec = protocolWorkSec,
    restSec = protocolRestSec,
    defaultRestSec = defaultRestSec,
    ledByProtocolFlag = ledByProtocol,
    oneSided = oneSided,
)

/**
 * The dashboard's view of a catalog row — see [CatalogExercise]. Null for a form code this
 * build cannot read, so an unreadable row drops out of the feed rather than crashing it.
 */
fun CatalogRow.toCatalogExercise(): CatalogExercise? =
    runCatching { ExerciseForm.fromCode(form) }.getOrNull()?.let { readableForm ->
        CatalogExercise(
            id = id,
            name = name,
            form = readableForm,
            uid = uid,
            oneSided = oneSided,
            bodyweightShare = bodyweightShare,
        )
    }
