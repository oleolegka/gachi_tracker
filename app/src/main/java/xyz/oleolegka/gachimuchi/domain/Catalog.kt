package xyz.oleolegka.gachimuchi.domain

/**
 * What makes two catalog rows the same exercise — in one place, because the answer decides
 * whose history is whose.
 *
 * ── Why this is a type and not a comparison written where it is needed ──────────
 * §12-A says an exercise IS its name plus its protocol: "Hangs" at 7:3 and "Hangs" at 10:5 are
 * two exercises with two records and two charts. That rule was stated in the documentation,
 * honoured by the record reducers, honoured by the journal backup's merge — and NOT honoured by
 * the one place that creates rows, which looked a new exercise up by name alone and handed back
 * whatever it found. Somebody adding hangs on a different protocol while the old one existed got
 * the old row, silently, and two histories became one for good.
 *
 * (§12-A used to name a THIRD thing here too — the hangboard edge, in millimetres. That has
 * been removed from the app entirely: it was a climbing-specific value with no comparison the
 * owner wanted, and it lived in the name from now on, same as it always did in speech. See
 * `MIGRATION_17_18` in `data/db/AppDatabase.kt` for what happened to the exercises that had
 * one on file.)
 *
 * ── The protocol used to be two numbers here, and is now a program reference ────
 * A work:rest PAIR only describes the simplest cycle — it cannot say "seven seconds work, three
 * rest, six times, pause, switch hands, repeat", which is an ordinary hangboard session. The
 * program library (`domain/Program.kt`) already models exactly that, so the protocol half of
 * identity is now the STABLE UID of the library program an exercise references, not a pair of
 * doubles. The local row id (`Long`) is deliberately not what is compared: see
 * [xyz.oleolegka.gachimuchi.domain.PortableExercise.identity] for why a device-local number
 * would silently break identity across a merge, which is exactly the failure this type exists
 * to prevent.
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
    /** The stable `uid` of the library program this exercise's protocol is, or null for none. */
    val programUid: String?,
) {
    /**
     * The identity as one string, which is what the database indexes.
     *
     * ── Why a key column and not a UNIQUE index over the three columns ─────────
     * Two of the three are nullable, and SQLite treats NULLs in a unique index as DISTINCT
     * from each other. A UNIQUE index on (name, form, protocol_program_id) therefore permits
     * any number of rows called "Bench press" with no protocol — which is every strength
     * exercise there is, and the commonest duplicate by a wide margin. The constraint would
     * have looked like a constraint and enforced nothing where it was needed most.
     *
     * A single NOT NULL string has no such hole. The price is that it is DERIVED state and
     * can drift from the columns it was computed from; that is paid for by making it a
     * constructor default on the entity, so a row cannot be built without it, and by making
     * the one statement that edits an identity write the new key in the same UPDATE.
     */
    val key: String get() = "$name$SEPARATOR$form$SEPARATOR${programUid ?: ""}"

    private companion object {
        /**
         * A normalized name cannot contain this — [normPhrase] keeps letters, digits and
         * spaces and nothing else — so the parts cannot run into one another. A uid
         * ([xyz.oleolegka.gachimuchi.domain.newUid]) cannot contain it either.
         */
        const val SEPARATOR = "|"
    }
}

/**
 * The identity of an exercise described by its two defining values plus the protocol's
 * program, said by its stable uid.
 *
 * The name is normalized so that spacing and case cannot split a history in two; a name with
 * no letters or digits in it at all (which [normPhrase] has nothing to make a key out of)
 * falls back to its own trimmed, lower-cased text with the separator character taken out.
 */
fun exerciseIdentity(
    name: String,
    form: Int,
    programUid: String? = null,
): ExerciseIdentity = ExerciseIdentity(
    name = normPhrase(name) ?: name.trim().lowercase().replace("|", " "),
    form = form,
    programUid = programUid,
)

/** The stored identity key for a row described by these values — see [ExerciseIdentity.key]. */
fun exerciseIdentityKey(
    name: String,
    form: Int,
    programUid: String? = null,
): String = exerciseIdentity(name, form, programUid).key

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
    programUid: String?,
    taken: Set<String>,
): String {
    if (exerciseIdentityKey(name, form, programUid) !in taken) return name
    for (n in 2..MAX_NAME_ATTEMPTS) {
        val candidate = "$name ($n)"
        if (exerciseIdentityKey(candidate, form, programUid) !in taken) return candidate
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
 * is derived from [name], [form] and [protocolProgramId] and would only ever be recomputed by
 * a reader, never trusted from one.
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
 *
 * [protocolProgramId] is the LOCAL row id of the linked library program, kept as a raw `Long?`
 * the same shape the entity carries it in — this type mirrors columns, it does not resolve
 * them. A caller that needs the program itself, or its portable uid, resolves it separately
 * (see [toRef] and `CatalogRow.toPortable` in domain/JournalTransfer.kt) and hands the answer
 * in, because resolving a program id needs a database and this type is plain data with none.
 */
data class CatalogRow(
    val id: Long,
    val uid: String,
    val name: String,
    /** Form code, the values of [ExerciseForm]. */
    val form: Int,
    val createdAt: String,
    val protocolProgramId: Long? = null,
    val defaultRestSec: Int? = null,
    val ledByProtocol: Boolean? = null,
    val oneSided: Boolean = false,
    val bodyweightShare: Double? = null,
    val hidden: Boolean = false,
    /**
     * Which picture shows the machine this exercise is trained on — see
     * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.pictureId].
     *
     * DELIBERATELY absent from [toRef] and [toCatalogExercise]: neither the entry card nor the
     * dashboard draws it. It IS carried into the exercise picker, which reads
     * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity] directly rather than through this type
     * (see `ui/screens/ExercisePicker.kt`) — this field exists on [CatalogRow] anyway, for the
     * same reason every other column does: a view that starts wanting it later finds it already
     * wired in here instead of being the second place that has to remember the column exists.
     * It is also deliberately absent from `PortableExercise` (domain/JournalTransfer.kt) — a
     * local file reference is not portable data, the same decision already made for the
     * celebration gallery, which the journal backup does not carry either.
     */
    val pictureId: String? = null,
)

/**
 * The entry card's view of a catalog row — see [ExerciseRef].
 *
 * [program] is the RESOLVED library program [protocolProgramId] points at, or null for no
 * protocol at all (either no id, or an id nothing in the library holds any more — a dangling
 * `protocol_program_id` reads as "no protocol", exactly as
 * [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity.protocolProgramId]'s own KDoc says it must).
 * The caller resolves it, rather than this function reaching for a database: everything else
 * here is a pure mapping, and [xyz.oleolegka.gachimuchi.data.ActivityRepository] is what holds
 * the program repository this needs.
 *
 * [ExerciseRef.workSec]/[ExerciseRef.restSec] come from the program's FIRST block ([firstBlock])
 * — the only shape a plain two-number protocol ever produces — converted from the block's `Int`
 * seconds to `Double` without rounding, which is the lossless direction; going the other way
 * (`Double` typed into a form -> `Int` block seconds) is where truncation happens, in
 * `ActivityRepository`'s find-or-create-protocol-program logic.
 *
 * An unreadable form code degrades to [ExerciseForm.TICK] rather than throwing: that is the
 * only form whose entry card cannot write a wrong-shaped payload, so a corrupted row costs a
 * useless card instead of a crash on the screen the user is standing in the gym with.
 */
fun CatalogRow.toRef(program: WorkoutProgram? = null): ExerciseRef {
    val block = program?.firstBlock()
    return ExerciseRef(
        id = id,
        uid = uid,
        name = name,
        form = runCatching { ExerciseForm.fromCode(form) }.getOrDefault(ExerciseForm.TICK),
        workSec = block?.workSec?.toDouble(),
        restSec = block?.restSec?.toDouble(),
        defaultRestSec = defaultRestSec,
        ledByProtocolFlag = ledByProtocol,
        oneSided = oneSided,
    )
}

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
