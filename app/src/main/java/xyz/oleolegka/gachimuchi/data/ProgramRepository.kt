package xyz.oleolegka.gachimuchi.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.starterPrograms
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
     * ── A REFERENCED program does not rewrite its content ────────────────────────
     * Once [isReferenced] says an exercise's protocol IS this program, `prepare_sec` and
     * every group and block are left exactly as stored — the loop below is skipped entirely,
     * `deleteGroupsOf` included, so nothing about the CONTENT of a running exercise's protocol
     * can move underneath it. The owner's rule, verbatim: "such a thing cannot happen: it
     * breaks the statistics. If yesterday it was one protocol and today another, that is a NEW
     * exercise" — and the library editor used to be exactly that hole, because the same stored
     * program is what several exercises' protocols collapse onto (identical numbers share one
     * row), so editing it by content moved every one of them at once, silently, under
     * `identity_key`s that never changed to say so.
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
            val frozen = isReferenced(program.id)
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

    suspend fun delete(id: Long) = db.programs().deleteProgram(id)

    /**
     * Whether some exercise's protocol currently IS this program — the live fact [save] freezes
     * a program's content against, and the same fact
     * [xyz.oleolegka.gachimuchi.ui.screens.ProgramEditorScreen] is shown so its content
     * controls can be locked before a doomed edit is even typed.
     *
     * Public rather than private for that second reason: the screen that hosts the editor has
     * to ask this BEFORE calling [save], not learn about the freeze from a save that silently
     * did less than it was asked.
     */
    suspend fun isReferenced(programId: Long): Boolean =
        programId != 0L && db.exercises().existsWithProtocolProgram(programId)

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
