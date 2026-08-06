package xyz.oleolegka.gachimuchi.data.seed

import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.db.AliasEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.SEED_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.exerciseIdsReferencedBy
import xyz.oleolegka.gachimuchi.domain.normPhrase

/**
 * Taking the demo data back out.
 *
 * ── What this is defending against ──────────────────────────────────────────────
 * The demo seed writes into the SAME journal, the same catalog and the same plan the user's
 * own records live in. There is no second profile to put it in and there should not be one:
 * the whole app is one journal, and a demo that lived somewhere else would prove nothing
 * about how the screens behave when they are full.
 *
 * So removal has to be exact, and "exact" has a direction. Leaving a demo row behind is an
 * annoyance the user can see and press the button about again. Deleting a real one is
 * unrecoverable — there is no backup — so every rule here is written to fail towards
 * leaving something rather than towards taking something.
 *
 * ── Three ways a row is recognised, in decreasing confidence ────────────────────
 * 1. EVENTS carry [SEED_AUTHOR_ID] and always have. This is exact, in both directions.
 * 2. The CATALOG, the ALIASES and the SLOTS carry a mark from schema version 4 on. Exact
 *    for anything written by this build or later.
 * 3. Data written by an EARLIER BUILD has no mark, and this is the case that matters,
 *    because that is what is on the phone right now. It is recognised by matching the known
 *    demo set: exercise names ([DemoSeed.demoExerciseKeys]) and slot shapes
 *    ([DemoSeed.demoSlotShapes]). This is a guess, and it is a guess that can be wrong in
 *    one direction — a user who created their own "Squat" or their own weekly "Gym" at 18:00
 *    would see it offered for removal. Hence the guard below, and hence the count shown
 *    before anything happens rather than a button that just does it.
 *
 * ── The guard: an exercise with real records is never deleted ───────────────────
 * A catalog row is only removed when NOTHING outside the seed's own events points at it —
 * cancelled sets included, since a cancelled set still names its exercise. An exercise that
 * fails that test is kept and its mark cleared: it stops being demo data and becomes the
 * user's, which is what it in fact already is. This is what makes the wipe safe on the one
 * scenario that would otherwise be a disaster: demo history seeded months ago, real sets
 * logged against "Bench press" ever since.
 *
 * Aliases are removed only when the exercise they point at is going, so a word can never
 * outlive its meaning and a word the user taught is never taken away from an exercise that
 * stays. That does leave demo aliases pointing at kept exercises in place; a spare word is
 * not worth a rule that can delete the user's.
 */

/** What a wipe would take, worked out before anything is deleted. */
data class DemoWipePlan(
    /** Journal events written by the seed author. */
    val eventCount: Int,
    val exerciseIds: List<Long>,
    val exerciseNames: List<String>,
    val aliasKeys: List<String>,
    val slotIds: List<Long>,
    /** Seeded exercises that carry real records and are therefore kept, by name. */
    val keptExerciseNames: List<String>,
    val keptExerciseIds: List<Long>,
) {
    val exerciseCount: Int get() = exerciseIds.size
    val slotCount: Int get() = slotIds.size

    /** Nothing to do: no demo data was found. */
    val isEmpty: Boolean
        get() = eventCount == 0 && exerciseIds.isEmpty() && aliasKeys.isEmpty() && slotIds.isEmpty()
}

/**
 * Works out what would go, from data alone. Pure, so the rules above can be tested against
 * awkward journals without a database.
 *
 * [matchByName] switches off the third recognition rule — the one that reads names and slot
 * shapes to find demo data written before the mark existed. It is the only rule here that
 * can be wrong about the user's own rows, so it is only used where the answer is shown
 * before it is acted on; see [removeDemoData].
 */
fun demoWipePlan(
    events: List<JournalEvent>,
    exercises: List<ExerciseEntity>,
    aliases: List<AliasEntity>,
    slots: List<SlotEntity>,
    matchByName: Boolean = true,
): DemoWipePlan {
    val seedEvents = events.filter { it.authorId == SEED_AUTHOR_ID }
    val realEvents = events.filter { it.authorId != SEED_AUTHOR_ID }
    val spokenFor = exerciseIdsReferencedBy(realEvents)

    fun looksLikeDemo(exercise: ExerciseEntity) =
        exercise.seeded || (matchByName && normPhrase(exercise.name) in DemoSeed.demoExerciseKeys)

    val candidates = exercises.filter { looksLikeDemo(it) }
    val (kept, doomed) = candidates.partition { it.id in spokenFor }

    val doomedIds = doomed.map { it.id }.toSet()
    val liveExerciseIds = exercises.map { it.id }.toSet()
    val aliasKeys = aliases
        .filter { it.value in doomedIds || (it.seeded && it.value !in liveExerciseIds) }
        .map { it.key }

    val shapes = DemoSeed.demoSlotShapes.toSet()
    val slotIds = slots
        .filter {
            it.seeded || (matchByName && DemoSeed.SlotShape(it.name, it.atTime, it.repeatRule) in shapes)
        }
        .map { it.id }

    return DemoWipePlan(
        eventCount = seedEvents.size,
        exerciseIds = doomedIds.toList(),
        exerciseNames = doomed.map { it.name },
        aliasKeys = aliasKeys,
        slotIds = slotIds,
        keptExerciseNames = kept.map { it.name },
        keptExerciseIds = kept.map { it.id },
    )
}

/** Reads the current state and works out what a wipe would take. Writes nothing. */
suspend fun planDemoWipe(repo: ActivityRepository, matchByName: Boolean = true): DemoWipePlan =
    demoWipePlan(
        events = repo.allEvents(),
        exercises = repo.allExercises(),
        aliases = repo.allAliases(),
        slots = repo.allSlotRows(),
        matchByName = matchByName,
    )

/**
 * Carries out a plan.
 *
 * The order matters: the events go first, so that if anything below fails the journal is
 * already clean of synthetic sets and a second attempt has less to do. Aliases go before the
 * exercises they point at, so there is no window in which a word resolves to a row that has
 * just been deleted.
 */
suspend fun applyDemoWipe(repo: ActivityRepository, plan: DemoWipePlan) {
    if (plan.eventCount > 0) repo.clearSeedEvents()
    repo.deleteAliases(plan.aliasKeys)
    repo.deleteExercises(plan.exerciseIds)
    repo.deleteSlots(plan.slotIds)
    repo.keepExercises(plan.keptExerciseIds)
}

/**
 * Plans and applies in one go.
 *
 * [matchByName] is what separates the two callers. The button in Settings has it on: the
 * user is looking at a dialog stating what will go, so the by-name guess is offered with its
 * consequences visible, which is the only setting in which a guess is acceptable. The SEED
 * has it off when it tidies up before writing a fresh demo, because there is no dialog on
 * that path — nobody is being shown anything — and quietly deleting a user's own empty
 * "Squat" because the demo happens to use that word would be a delete nobody asked for and
 * nobody saw. Marked rows are removed either way; those are not a guess.
 */
suspend fun removeDemoData(repo: ActivityRepository, matchByName: Boolean = true): DemoWipePlan {
    val plan = planDemoWipe(repo, matchByName)
    applyDemoWipe(repo, plan)
    return plan
}

/**
 * What the user is told a removal WILL do, and afterwards what it DID. The same sentence
 * serves both, because the plan is worked out once and then carried out unchanged — a
 * confirmation that describes something other than what happens is worse than none.
 *
 * It counts rather than reassures. "Your own records are untouched" is a claim; "142
 * entries, 12 exercises, 6 planned sessions" is a number the user can compare against what
 * they see afterwards, which is the only form of trust worth offering about a delete button.
 */
fun demoInventory(plan: DemoWipePlan): String {
    val parts = buildList {
        if (plan.eventCount > 0) {
            add("${plan.eventCount} journal ${if (plan.eventCount == 1) "entry" else "entries"}")
        }
        if (plan.exerciseCount > 0) {
            add("${plan.exerciseCount} ${if (plan.exerciseCount == 1) "exercise" else "exercises"}")
        }
        if (plan.slotCount > 0) {
            add("${plan.slotCount} planned ${if (plan.slotCount == 1) "session" else "sessions"}")
        }
    }
    return when (parts.size) {
        0 -> ""
        1 -> parts.single()
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}

/** The sentence about exercises the wipe will spare, or empty when it spares none. */
fun keptExercisesNote(plan: DemoWipePlan): String {
    val kept = plan.keptExerciseNames
    if (kept.isEmpty()) return ""
    val names = kept.joinToString(", ")
    return if (kept.size == 1) {
        "$names is kept - it carries sets of yours."
    } else {
        "$names are kept - they carry sets of yours."
    }
}

/** What a finished removal reports. */
fun removalSummary(plan: DemoWipePlan): String =
    if (plan.isEmpty) "No demo data was found. Nothing was changed."
    else "Removed ${demoInventory(plan)}."
