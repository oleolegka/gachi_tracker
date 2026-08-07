package xyz.oleolegka.gachimuchi.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The journal DAO. Updates and deletes of events are absent ON PURPOSE — the journal is
 * append-only. There used to be one exception, a delete keyed on the demo seed's author id;
 * it went with the demo seed, and the journal now has no delete at all.
 */
@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Insert
    suspend fun insertAll(events: List<EventEntity>): List<Long>

    @Query("SELECT * FROM events WHERE space_id = :spaceId ORDER BY id")
    fun observeAll(spaceId: Long = LOCAL_SPACE_ID): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE space_id = :spaceId ORDER BY id")
    suspend fun all(spaceId: Long = LOCAL_SPACE_ID): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE space_id = :spaceId")
    suspend fun count(spaceId: Long = LOCAL_SPACE_ID): Int

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun byId(id: Long): EventEntity?
}

@Dao
interface ExerciseDao {
    @Insert
    suspend fun insert(exercise: ExerciseEntity): Long

    @Update
    suspend fun update(exercise: ExerciseEntity)

    @Delete
    suspend fun delete(exercise: ExerciseEntity)

    @Query("SELECT * FROM exercises WHERE space_id = :spaceId ORDER BY id")
    fun observeAll(spaceId: Long = LOCAL_SPACE_ID): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE space_id = :spaceId ORDER BY id")
    suspend fun all(spaceId: Long = LOCAL_SPACE_ID): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun byId(id: Long): ExerciseEntity?

    /**
     * Remembers the rest last chosen for an exercise.
     *
     * A one-column update rather than Room's @Update of a whole entity, for the same reason
     * [SlotDao.updateFields] is one: the caller here is "the user picked 2:30 while adding
     * this to a workout" and it has no business rewriting the name, the edge or the protocol.
     * Writing the entity back would overwrite those with whatever the caller happened to be
     * holding, which for a hangboard exercise means overwriting its IDENTITY (§12-A).
     */
    @Query("UPDATE exercises SET default_rest_sec = :sec WHERE space_id = :spaceId AND id = :id")
    suspend fun setDefaultRest(id: Long, sec: Int?, spaceId: Long = LOCAL_SPACE_ID)

    /** Same, for "run this by its protocol". Null puts the row back to inferring it. */
    @Query("UPDATE exercises SET led_by_protocol = :led WHERE space_id = :spaceId AND id = :id")
    suspend fun setLedByProtocol(id: Long, led: Boolean?, spaceId: Long = LOCAL_SPACE_ID)

    /**
     * Same, for "this one is trained one limb at a time".
     *
     * Turning it ON re-reads the whole history of the exercise: sets logged before it named
     * no side, and they become a defect the records report rather than hide (see
     * [xyz.oleolegka.gachimuchi.domain.holdRecord]). Nothing is rewritten to make that go
     * away — the old sets genuinely do not say which hand did them.
     */
    @Query("UPDATE exercises SET one_sided = :oneSided WHERE space_id = :spaceId AND id = :id")
    suspend fun setOneSided(id: Long, oneSided: Boolean, spaceId: Long = LOCAL_SPACE_ID)
}

/**
 * Interval programs. Groups and blocks are deleted by cascade when their parent goes, so
 * there is no delete for them here; rewriting a program replaces its groups wholesale
 * (see ProgramRepository) rather than diffing rows, because an editor that reorders blocks
 * makes a diff more code than a rewrite for no benefit at this size.
 */
@Dao
interface ProgramDao {
    @Insert
    suspend fun insertProgram(program: ProgramEntity): Long

    @Insert
    suspend fun insertGroup(group: ProgramGroupEntity): Long

    @Insert
    suspend fun insertBlock(block: ProgramBlockEntity): Long

    @Update
    suspend fun updateProgram(program: ProgramEntity)

    @Query("SELECT * FROM programs WHERE space_id = :spaceId ORDER BY position, id")
    fun observePrograms(spaceId: Long = LOCAL_SPACE_ID): Flow<List<ProgramEntity>>

    @Query("SELECT * FROM programs WHERE space_id = :spaceId ORDER BY position, id")
    suspend fun allPrograms(spaceId: Long = LOCAL_SPACE_ID): List<ProgramEntity>

    @Query("SELECT * FROM programs WHERE id = :id")
    suspend fun programById(id: Long): ProgramEntity?

    /**
     * Points a program at a catalog exercise without touching anything else about it.
     *
     * A one-column update rather than a [ProgramRepository.save], because this is called
     * from the offer that appears when a run ends: rewriting the program's groups and blocks
     * from a value the offer never loaded would be a way to lose an edit made in between.
     */
    @Query("UPDATE programs SET exercise_id = :exerciseId WHERE id = :id")
    suspend fun setProgramExercise(id: Long, exerciseId: Long?)

    @Query("SELECT COUNT(*) FROM programs WHERE space_id = :spaceId")
    suspend fun countPrograms(spaceId: Long = LOCAL_SPACE_ID): Int

    @Query(
        "SELECT * FROM program_groups WHERE program_id IN " +
            "(SELECT id FROM programs WHERE space_id = :spaceId) ORDER BY program_id, position, id"
    )
    fun observeGroups(spaceId: Long = LOCAL_SPACE_ID): Flow<List<ProgramGroupEntity>>

    @Query(
        "SELECT * FROM program_groups WHERE program_id IN " +
            "(SELECT id FROM programs WHERE space_id = :spaceId) ORDER BY program_id, position, id"
    )
    suspend fun allGroups(spaceId: Long = LOCAL_SPACE_ID): List<ProgramGroupEntity>

    @Query(
        "SELECT * FROM program_blocks WHERE group_id IN (SELECT g.id FROM program_groups g " +
            "JOIN programs p ON p.id = g.program_id WHERE p.space_id = :spaceId) " +
            "ORDER BY group_id, position, id"
    )
    fun observeBlocks(spaceId: Long = LOCAL_SPACE_ID): Flow<List<ProgramBlockEntity>>

    @Query(
        "SELECT * FROM program_blocks WHERE group_id IN (SELECT g.id FROM program_groups g " +
            "JOIN programs p ON p.id = g.program_id WHERE p.space_id = :spaceId) " +
            "ORDER BY group_id, position, id"
    )
    suspend fun allBlocks(spaceId: Long = LOCAL_SPACE_ID): List<ProgramBlockEntity>

    @Query("DELETE FROM program_groups WHERE program_id = :programId")
    suspend fun deleteGroupsOf(programId: Long)

    @Query("DELETE FROM programs WHERE space_id = :spaceId AND id = :id")
    suspend fun deleteProgram(id: Long, spaceId: Long = LOCAL_SPACE_ID)
}

/**
 * Plan slots. Unlike the journal these are edited in place: one row is the master record
 * of a whole series, so changing the time of a weekly session is one UPDATE rather than a
 * rewrite of its occurrences (there are none to rewrite — they are computed).
 */
@Dao
interface SlotDao {
    @Insert
    suspend fun insert(slot: SlotEntity): Long

    @Delete
    suspend fun delete(slot: SlotEntity)

    /**
     * Edits the four fields the editor owns, by id.
     *
     * A column list rather than Room's @Update of a whole entity, because the editor never
     * loads `created_at` or `space_id` and an entity rebuilt from a draft would overwrite
     * them with whatever the rebuild made up. Returns the number of rows touched, so a
     * caller can tell "saved" from "that slot is gone".
     */
    @Query(
        "UPDATE slots SET name = :name, at_time = :atTime, repeat_rule = :repeatRule, " +
            "anchor_date = :anchorDate WHERE space_id = :spaceId AND id = :id"
    )
    suspend fun updateFields(
        id: Long,
        name: String,
        atTime: String?,
        repeatRule: String,
        anchorDate: String,
        spaceId: Long = LOCAL_SPACE_ID,
    ): Int

    @Query("SELECT * FROM slots WHERE id = :id")
    suspend fun byId(id: Long): SlotEntity?

    @Query("SELECT * FROM slots WHERE space_id = :spaceId ORDER BY id")
    fun observeAll(spaceId: Long = LOCAL_SPACE_ID): Flow<List<SlotEntity>>

    @Query("SELECT * FROM slots WHERE space_id = :spaceId ORDER BY id")
    suspend fun all(spaceId: Long = LOCAL_SPACE_ID): List<SlotEntity>

    @Query("DELETE FROM slots WHERE space_id = :spaceId AND id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>, spaceId: Long = LOCAL_SPACE_ID)

    // --- what a slot is made of (schema version 6) ---
    //
    // There is no delete-one-row and no update here, and there does not need to be: the
    // composition is REPLACED wholesale on every save, exactly as a program's groups are
    // (see ProgramRepository). Reordering, inserting and removing then all reduce to the
    // same two statements, and no path exists on which a stale row survives an edit.
    //
    // Nor is there a delete keyed on the slot going away: `slot_exercises` cascades from
    // `slots`, so [deleteByIds] takes the composition with it.

    @Insert
    suspend fun insertExercises(rows: List<SlotExerciseEntity>)

    @Query("DELETE FROM slot_exercises WHERE slot_id = :slotId")
    suspend fun deleteExercisesOf(slotId: Long)

    @Query("SELECT * FROM slot_exercises WHERE slot_id = :slotId ORDER BY position, id")
    suspend fun exercisesOf(slotId: Long): List<SlotExerciseEntity>

    /**
     * Every slot's composition at once, for assembling the whole plan in one pass. Scoped
     * through `slots` rather than carrying its own `space_id`: the row belongs to a slot and
     * has no independent existence, so its profile is whatever its slot's is.
     */
    @Query(
        "SELECT * FROM slot_exercises WHERE slot_id IN " +
            "(SELECT id FROM slots WHERE space_id = :spaceId) ORDER BY slot_id, position, id"
    )
    fun observeExercises(spaceId: Long = LOCAL_SPACE_ID): Flow<List<SlotExerciseEntity>>

    @Query(
        "SELECT * FROM slot_exercises WHERE slot_id IN " +
            "(SELECT id FROM slots WHERE space_id = :spaceId) ORDER BY slot_id, position, id"
    )
    suspend fun allExercises(spaceId: Long = LOCAL_SPACE_ID): List<SlotExerciseEntity>
}
