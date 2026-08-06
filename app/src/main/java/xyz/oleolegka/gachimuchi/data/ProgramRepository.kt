package xyz.oleolegka.gachimuchi.data

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
     */
    suspend fun save(program: WorkoutProgram): Long {
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
            db.programs().updateProgram(
                ProgramEntity(
                    id = program.id,
                    name = program.name,
                    prepareSec = program.prepareSec,
                    position = existing?.position ?: 0,
                    createdAt = existing?.createdAt ?: now(),
                    exerciseId = program.exerciseId,
                    category = program.category.trim(),
                )
            )
            db.programs().deleteGroupsOf(program.id)
            program.id
        }

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
        return id
    }

    suspend fun delete(id: Long) = db.programs().deleteProgram(id)

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
     */
    suspend fun seedStartersIfEmpty() {
        if (db.programs().countPrograms() > 0) return
        starterPrograms().forEach { save(it) }
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
