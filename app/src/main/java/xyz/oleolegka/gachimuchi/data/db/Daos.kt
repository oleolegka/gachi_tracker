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

@Dao
interface SlotDao {
    @Insert
    suspend fun insert(slot: SlotEntity): Long

    @Delete
    suspend fun delete(slot: SlotEntity)

    @Query("SELECT * FROM slots WHERE space_id = :spaceId ORDER BY id")
    fun observeAll(spaceId: Long = LOCAL_SPACE_ID): Flow<List<SlotEntity>>

    @Query("SELECT * FROM slots WHERE space_id = :spaceId ORDER BY id")
    suspend fun all(spaceId: Long = LOCAL_SPACE_ID): List<SlotEntity>

    @Query("DELETE FROM slots WHERE space_id = :spaceId AND id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>, spaceId: Long = LOCAL_SPACE_ID)
}
