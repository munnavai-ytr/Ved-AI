package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for durable cross-conversation memories.
 */
@Dao
interface MemoryDao {

  @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
  fun observeAllMemories(): Flow<List<MemoryEntity>>

  @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
  suspend fun getAllMemoriesSync(): List<MemoryEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMemory(memory: MemoryEntity)

  @Query("UPDATE memories SET content = :content, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateMemoryContent(id: String, content: String, updatedAt: Long)

  @Query("DELETE FROM memories WHERE id = :id")
  suspend fun deleteMemoryById(id: String)

  @Query("DELETE FROM memories")
  suspend fun deleteAllMemories()

  @Query(
    "SELECT * FROM memories WHERE content LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY updatedAt DESC"
  )
  fun searchMemories(query: String): Flow<List<MemoryEntity>>
}
