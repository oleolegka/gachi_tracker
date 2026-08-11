package xyz.oleolegka.gachimuchi.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.scheduleFrozen
import xyz.oleolegka.gachimuchi.domain.starterPrograms
import xyz.oleolegka.gachimuchi.domain.trainedExercises
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Reading and writing interval programs.
 *
 * The three tables are assembled into the domain [WorkoutProgram] here so that nothing
 * above this layer ever sees a group id or a position column: the editor edits a nested
 * value, and saving it is one call. Everything in domain/Program.kt stays free of Room.
 *
 * ── Saving replaces, it does not diff ───────────────────────────────────────────
 * [save] deletes the program's groups (blocks go with them by cascade) and writes the new
 * ones. Reordering, inserting and deleting blocks are all the same operation that way,
 * and there is no path where a stale row survives an edit. Programs are a handful of rows
 * each, so the rewrite costs nothing; the ids of groups and blocks are not stable across a
 * save, which is fine because nothing refers to them.
 */
class ProgramRepository(private val db: AppDatabase) {

    private fun now(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    /** Every program, assembled. Emits again whenever any of the three tables changes. */
    val programs: Flow<List<WorkoutProgram>> = combine(
        db.programs().observePrograms(),
        db.programs().observeGroups(),
        db.programs().observeBlocks(),
    ) { programs, groups, blocks -> assemble(programs, groups, blocks) }

    suspend fun allPrograms(): List<WorkoutProgram> = assemble(
        db.programs().allPrograms(),
        db.programs().allGroups(),
        db.programs().allBlocks(),
    )

    suspend fun programById(id: Long): WorkoutProgram? = allPrograms().firstOrNull { it.id == id }

    /**
     * Inserts a new program (id 0) or rewrites an existing one. Returns the program id.
     * Groups and blocks keep the order they have in the value — [ProgramGroup] and
     * [ProgramBlock] carry no position of their own, the list order IS the order.
     *
     * ── A TRAINED-ON program does not rewrite its content ────────────────────────
     * Once [isFrozen] says a set has been recorded by some exercise this program is the
     * schedule of, `prepare_sec` and every group and block are left exactly as stored — the
     * loop below is skipped entirely, `deleteGroupsOf` included, so nothing about the CONTENT
     * of a running exercise's protocol can move underneath it. The owner's rule, verbatim:
     * "such a thing cannot happen: it breaks the statistics. If yesterday it was one protocol
     * and today another, that is a NEW exercise" — and the library editor used to be exactly
     * that hole, because the same stored program is what several exercises' protocols collapse
     * onto (identical numbers share one row), so editing it by content moved every one of them
     * at once, silently, under `identity_key`s that never changed to say so.
     *
     * UNTIL THEN IT IS EDITABLE, which is the change §18.19 made: a schedule assembled and not
     * yet trained on has no history under it to put out of step, so correcting a number in it
     * is a correction. The rule this replaced froze it in the second the exercise was created,
     * and the only repair left was to abandon the exercise and build another.
     *
     * NAME, CATEGORY and the exercise link are NOT frozen — identity is keyed on the program's
     * `uid`, never on what it is called or filed under, and correcting a name a migration
     * generated ("Hangs 20mm protocol", identical across five unrelated exercises) is exactly
     * the thing the owner asked to keep. `hidden` is untouched here on purpose too: it is its
     * own one-column write ([setHidden]), on the same footing `uid` already has above — an
     * ordinary Save must not silently flip it either way.
     *
     * This is enforcement, not merely a UI choice: [xyz.oleolegka.gachimuchi.ui.screens.
     * ProgramEditorScreen] locks the content controls for a referenced program so nobody is
     * ever shown a field that would then do nothing, but the refusal lives HERE as well, on
     * the same reasoning [saveSlot]'s own KDoc gives for validating at both ends — a caller
     * that reaches this method some other way must not be able to walk around the rule.
     *
     * ── One program, one transaction ──────────────────────────────────────────────
     * Every branch below is DELETE-THEN-INSERT or INSERT-THEN-INSERT across three tables, and
     * a process dying midway used to leave a program that briefly (or, if it happened to be the
     * moment the phone died for good, permanently) had no groups and no blocks at all — a
     * program the library editor would show as empty, not as the one it replaced a moment
     * before. `withTransaction` makes the whole rewrite atomic: the old shape, or the new one,
     * and nothing in between ever readable.
     */
    suspend fun save(program: WorkoutProgram): Long = db.withTransaction {
        val id = if (program.id == 0L) {
            db.programs().insertProgram(
                ProgramEntity(
                    name = program.name,
                    prepareSec = program.prepareSec,
                    position = db.programs().countPrograms(),
                    createdAt = now(),
                    exerciseId = program.exerciseId,
                    category = program.category.trim(),
                )
            )
        } else {
            val existing = db.programs().programById(program.id)
            val frozen = isFrozen(program.id)
            db.programs().updateProgram(
                ProgramEntity(
                    id = program.id,
                    name = program.name,
                    // the stored value survives untouched on a frozen program, whatever the
                    // caller's value carries — see this method's own KDoc
                    prepareSec = if (frozen) existing?.prepareSec ?: program.prepareSec else program.prepareSec,
                    position = existing?.position ?: 0,
                    createdAt = existing?.createdAt ?: now(),
                    exerciseId = program.exerciseId,
                    category = program.category.trim(),
                    // PRESERVED, not left to the entity's own `newUid()` default. A program's
                    // uid is now what an exercise's protocol is keyed on
                    // (domain/Catalog.kt's ExerciseIdentity), and letting an ordinary edit here
                    // regenerate it would silently strand every exercise identity pointing at
                    // this program the moment its name, category or blocks were next corrected
                    // in the library editor.
                    uid = existing?.uid ?: program.uid,
                    // its own one-column write ([setHidden]) — see this method's own KDoc
                    hidden = existing?.hidden ?: false,
                )
            )
            if (!frozen) {
                db.programs().deleteGroupsOf(program.id)
                for ((groupIndex, group) in program.groups.withIndex()) {
                    val groupId = db.programs().insertGroup(
                        ProgramGroupEntity(
                            programId = program.id,
                            name = group.name,
                            position = groupIndex,
                            repeats = group.repeats,
                            restBetweenRepeatsSec = group.restBetweenRepeatsSec,
                            restAfterSec = group.restAfterSec,
                        )
                    )
                    for ((blockIndex, block) in group.blocks.withIndex()) {
                        db.programs().insertBlock(
                            ProgramBlockEntity(
                                groupId = groupId,
                                name = block.name,
                                position = blockIndex,
                                workSec = block.workSec,
                                restSec = block.restSec,
                                repeats = block.repeats,
                            )
                        )
                    }
                }
            }
            return@withTransaction program.id
        }

        // only reached for an INSERT — an update returns above, frozen or not, because a
        // frozen update's groups must not be touched at all, not even by the loop that writes
        // them for a brand new row
        for ((groupIndex, group) in program.groups.withIndex()) {
            val groupId = db.programs().insertGroup(
                ProgramGroupEntity(
                    programId = id,
                    name = group.name,
                    position = groupIndex,
                    repeats = group.repeats,
                    restBetweenRepeatsSec = group.restBetweenRepeatsSec,
                    restAfterSec = group.restAfterSec,
                )
            )
            for ((blockIndex, block) in group.blocks.withIndex()) {
                db.programs().insertBlock(
                    ProgramBlockEntity(
                        groupId = groupId,
                        name = block.name,
                        position = blockIndex,
                        workSec = block.workSec,
                        restSec = block.restSec,
                        repeats = block.repeats,
                    )
                )
            }
        }
        id
    }

    /**
     * Removes a program, unless it is a schedule that has been trained on. Returns whether it
     * went.
     *
     * ── Why a frozen program cannot be deleted at all ────────────────────────────
     * [save] already refuses to move the CONTENT of a program an exercise with history is keyed
     * to, on the owner's rule that a protocol which changes is a new exercise. Deleting the
     * whole row was the hole left beside that door: the `exercises` row keeps
     * `protocol_program_id` — nothing cascades, there is no foreign key — so the exercise came
     * out the other side with a dangling reference, reading as "no protocol", while its
     * `identity_key` still carried the uid of a program that no longer existed. That is the
     * same identity break the freeze exists to prevent, only unrecoverable rather than silent.
     *
     * ── The same question all three doors ask, and what it now costs ─────────────
     * [isFrozen] rather than "is it referenced" (§18.19): the editor, this method and the
     * library's delete entry are required to agree, and a delete refused on a rule the screen
     * does not share is a menu entry that does nothing. The consequence, named rather than
     * hidden: a schedule that IS somebody's and has no sets yet can now be deleted, and its
     * exercise comes out with a dangling `protocol_program_id` reading as "no protocol". That
     * is a real loss of the schedule — but of a schedule nothing was ever recorded against, on
     * an exercise with no history to break, which is the whole premise of the mild freeze. It
     * is not cleared here on purpose: `identity_key` is derived from that column, so clearing
     * it would silently move the exercise's identity as a side effect of a library delete.
     *
     * The exercise's own removal does NOT free it either, and that is deliberate rather than
     * overlooked: [xyz.oleolegka.gachimuchi.data.ActivityRepository.deleteExercise] writes an
     * EVENT and leaves the catalog row in place forever, so a schedule stays referenced for as
     * long as the phone lives. Getting one out of sight is [setHidden]'s job, which is the
     * control the library offers in place of the delete button.
     *
     * Enforcement here as well as on the screen — see
     * [xyz.oleolegka.gachimuchi.ui.screens.TimerScreen], which draws no delete button for a
     * schedule — for the reason [save] gives at greater length: a caller reaching this method
     * some other way must not be able to walk around the rule. In a transaction so the answer
     * cannot go stale between the question and the DELETE.
     */
    suspend fun delete(id: Long): Boolean = db.withTransaction {
        if (isFrozen(id)) return@withTransaction false
        db.programs().deleteProgram(id)
        true
    }

    /**
     * Whether this program's content has hardened into history — the live fact [save] freezes a
     * program against, [delete] refuses on, and
     * [xyz.oleolegka.gachimuchi.ui.screens.ProgramEditorScreen] is shown so its content controls
     * can be locked before a doomed edit is even typed.
     *
     * ── The freeze is MILD, and this is where that is decided ────────────────────
     * True once SOME EXERCISE POINTING HERE HAS A RECORDED SET, not from the mere fact of a
     * reference (decisions §18.19, superseding §18.9). A schedule assembled and not yet trained
     * on is still a draft: correcting one number in it is a correction, not a rewriting of
     * history, because there is no history under it yet.
     *
     * SOME exercise, not the one being asked about — a schedule is deliberately shared by twins
     * (§18.15), so an edit made for the untouched 15 mm hang would land under the 20 mm hang's
     * sets. [db.exercises().withProtocolProgram] is what hands back all of them and
     * [xyz.oleolegka.gachimuchi.domain.scheduleFrozen] is what asks the journal about the lot.
     *
     * ── The cost of folding the journal here ────────────────────────────────────
     * This is no longer one `SELECT EXISTS`: it reads the whole event table and folds it
     * ([trainedExercises]). It is called on a save, on a delete and nowhere in a loop, at
     * personal scale (thousands of rows), which is the same trade
     * [xyz.oleolegka.gachimuchi.data.ActivityRepository.record] already makes to keep ONE
     * definition of a rule instead of a second one written in SQL that would drift from it.
     * The screen does not call this at all — it folds once for the whole library, see
     * [xyz.oleolegka.gachimuchi.domain.frozenScheduleIds].
     *
     * Public rather than private because the screen that hosts the editor has to know the
     * answer BEFORE calling [save], not learn about the freeze from a save that silently did
     * less than it was asked.
     */
    suspend fun isFrozen(programId: Long): Boolean {
        if (programId == 0L) return false
        val owners = db.exercises().withProtocolProgram(programId)
        if (owners.isEmpty()) return false
        val events = db.events().all().map { it.toJournalEvent() }
        return scheduleFrozen(owners.map { ExerciseLink(it.uid, it.id) }, trainedExercises(events))
    }

    /** Keeps a program out of the library list, or brings it back — see [setHidden] callers. */
    suspend fun setHidden(id: Long, hidden: Boolean) = db.programs().setHidden(id, hidden)

    /**
     * Remembers which catalog exercise a program trains, so that finishing it offers to log
     * straight away instead of asking again. Called both from the editor and from the offer
     * itself, which is where the answer is most likely to be given.
     */
    suspend fun linkExercise(programId: Long, exerciseId: Long?) {
        if (programId == 0L) return
        db.programs().setProgramExercise(programId, exerciseId)
    }

    suspend fun count(): Int = db.programs().countPrograms()

    /**
     * Writes the starter programs, but only into an empty list.
     *
     * Guarded rather than idempotent-by-overwrite on purpose: if the user has edited
     * "Tabata 20:10" into their own thing, restoring the shipped version on the next
     * launch would be the app quietly undoing their work.
     *
     * ── All the starters, or none — the guard above is why ─────────────────────────
     * A process dying after the third starter and before the fourth used to leave exactly
     * three programs in the library forever: the NEXT launch reads `countPrograms() > 0` and
     * never tries again, on the same "do not overwrite what the user may have already touched"
     * reasoning this method exists for. There is no way to tell "three because the seed was
     * cut short" from "three because the owner deleted the rest" after the fact, so the
     * first launch that seeds anything has to seed everything.
     */
    suspend fun seedStartersIfEmpty() {
        if (db.programs().countPrograms() > 0) return
        db.withTransaction { starterPrograms().forEach { save(it) } }
    }

    private fun assemble(
        programs: List<ProgramEntity>,
        groups: List<ProgramGroupEntity>,
        blocks: List<ProgramBlockEntity>,
    ): List<WorkoutProgram> {
        val blocksByGroup = blocks.groupBy { it.groupId }
        val groupsByProgram = groups.groupBy { it.programId }
        return programs.map { program ->
            WorkoutProgram(
                id = program.id,
                name = program.name,
                prepareSec = program.prepareSec,
                exerciseId = program.exerciseId,
                category = program.category,
                uid = program.uid,
                hidden = program.hidden,
                groups = groupsByProgram[program.id].orEmpty().map { group ->
                    ProgramGroup(
                        name = group.name,
                        repeats = group.repeats,
                        restBetweenRepeatsSec = group.restBetweenRepeatsSec,
                        restAfterSec = group.restAfterSec,
                        blocks = blocksByGroup[group.id].orEmpty().map { block ->
                            ProgramBlock(
                                name = block.name,
                                workSec = block.workSec,
                                restSec = block.restSec,
                                repeats = block.repeats,
                            )
                        },
                    )
                },
            )
        }
    }
}
