package xyz.oleolegka.gachimuchi.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The journal DAO. Updates and deletes of events are absent ON PURPOSE — the journal is
 * append-only (the single exception is [deleteBySeedAuthor], wiping the demo seed: that
 * is not rewriting history but erasing something that was never part of it).
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

    /** Wipes the demo seed: ONLY the events of the seed author are deleted. */
    @Query("DELETE FROM events WHERE space_id = :spaceId AND author_id = :authorId")
    suspend fun deleteBySeedAuthor(spaceId: Long = LOCAL_SPACE_ID, authorId: Long = SEED_AUTHOR_ID): Int
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

    @Query("DELETE FROM exercises WHERE space_id = :spaceId AND id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>, spaceId: Long = LOCAL_SPACE_ID)
}

@Dao
interface AliasDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alias: AliasEntity)

    @Query("SELECT * FROM aliases WHERE space_id = :spaceId AND key = :key")
    suspend fun byKey(key: String, spaceId: Long = LOCAL_SPACE_ID): AliasEntity?

    /** Live aliases: the exercise picker searches by them alongside the names. */
    @Query("SELECT * FROM aliases WHERE space_id = :spaceId ORDER BY key")
    fun observeAll(spaceId: Long = LOCAL_SPACE_ID): Flow<List<AliasEntity>>

    @Query("SELECT * FROM aliases WHERE space_id = :spaceId ORDER BY key")
    suspend fun all(spaceId: Long = LOCAL_SPACE_ID): List<AliasEntity>

    @Query("DELETE FROM aliases WHERE space_id = :spaceId AND key IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>, spaceId: Long = LOCAL_SPACE_ID)
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
}
