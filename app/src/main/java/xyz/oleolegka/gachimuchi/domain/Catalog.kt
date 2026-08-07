package xyz.oleolegka.gachimuchi.domain

/**
 * What makes two catalog rows the same exercise — in one place, because the answer decides
 * whose history is whose.
 *
 * ── Why this is a type and not a comparison written where it is needed ──────────
 * §12-A says an exercise IS its name plus its edge plus its work:rest protocol: "Hangs 20 mm
 * 7:3" and "Hangs 15 mm 7:3" are two exercises with two records and two charts. That rule was
 * stated in the documentation, honoured by the record reducers, honoured by the journal
 * backup's merge — and NOT honoured by the one place that creates rows, which looked a new
 * exercise up by name alone and handed back whatever it found. Somebody adding hangs on a
 * 15 mm edge while 20 mm hangs existed got the 20 mm row, silently, and two histories became
 * one for good. The only thing standing between the user and that was the habit of typing the
 * edge into the name.
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
 * history that a change to it could strand. Identity is only the four things that decide
 * which sets belong together.
 */
data class ExerciseIdentity(
    /** Normalized name — see [normPhrase]. */
    val name: String,
    /** Form code, the values of [ExerciseForm]. */
    val form: Int,
    val edgeMm: Double?,
    val workSec: Double?,
    val restSec: Double?,
) {
    /**
     * The identity as one string, which is what the database indexes.
     *
     * ── Why a key column and not a UNIQUE index over the four columns ──────────
     * Three of the four are nullable, and SQLite treats NULLs in a unique index as DISTINCT
     * from each other. A UNIQUE index on (name, form, edge_mm, protocol_work_sec,
     * protocol_rest_sec) therefore permits any number of rows called "Bench press" with no
     * edge and no protocol — which is every strength exercise there is, and the commonest
     * duplicate by a wide margin. The constraint would have looked like a constraint and
     * enforced nothing where it was needed most.
     *
     * A single NOT NULL string has no such hole. The price is that it is DERIVED state and
     * can drift from the columns it was computed from; that is paid for by making it a
     * constructor default on the entity, so a row cannot be built without it, and by making
     * the one statement that edits an identity write the new key in the same UPDATE.
     */
    val key: String get() = "$name$SEPARATOR$form$SEPARATOR${num(edgeMm)}$SEPARATOR${num(workSec)}$SEPARATOR${num(restSec)}"

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
 * The identity of an exercise described by its four defining values.
 *
 * The name is normalized so that spacing and case cannot split a history in two; a name with
 * no letters or digits in it at all (which [normPhrase] has nothing to make a key out of)
 * falls back to its own trimmed, lower-cased text with the separator character taken out.
 */
fun exerciseIdentity(
    name: String,
    form: Int,
    edgeMm: Double? = null,
    workSec: Double? = null,
    restSec: Double? = null,
): ExerciseIdentity = ExerciseIdentity(
    name = normPhrase(name) ?: name.trim().lowercase().replace("|", " "),
    form = form,
    edgeMm = edgeMm,
    workSec = workSec,
    restSec = restSec,
)

/** The stored identity key for a row described by these values — see [ExerciseIdentity.key]. */
fun exerciseIdentityKey(
    name: String,
    form: Int,
    edgeMm: Double? = null,
    workSec: Double? = null,
    restSec: Double? = null,
): String = exerciseIdentity(name, form, edgeMm, workSec, restSec).key

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
    edgeMm: Double?,
    workSec: Double?,
    restSec: Double?,
    taken: Set<String>,
): String {
    if (exerciseIdentityKey(name, form, edgeMm, workSec, restSec) !in taken) return name
    for (n in 2..MAX_NAME_ATTEMPTS) {
        val candidate = "$name ($n)"
        if (exerciseIdentityKey(candidate, form, edgeMm, workSec, restSec) !in taken) return candidate
    }
    // beyond absurd, and still better than failing an upgrade: the row keeps a name nobody
    // will mistake for another one
    return "$name (${System.nanoTime()})"
}

private const val MAX_NAME_ATTEMPTS = 999
