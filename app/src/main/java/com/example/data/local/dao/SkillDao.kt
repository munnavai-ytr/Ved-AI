package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.SkillEntity
import com.example.data.local.entity.SkillFileEntity
import com.example.data.local.entity.SkillWithFiles
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Claude-style custom skills and their supporting files.
 */
@Dao
interface SkillDao {

  @Query("SELECT * FROM skills ORDER BY updatedAt DESC")
  fun observeAllSkills(): Flow<List<SkillEntity>>

  @Transaction
  @Query("SELECT * FROM skills ORDER BY updatedAt DESC")
  fun observeAllSkillsWithFiles(): Flow<List<SkillWithFiles>>

  @Query("SELECT * FROM skills WHERE isEnabled = 1 ORDER BY updatedAt DESC")
  suspend fun getAllEnabledSkillsSync(): List<SkillEntity>

  @Transaction
  @Query("SELECT * FROM skills WHERE id = :skillId LIMIT 1")
  suspend fun getSkillWithFiles(skillId: String): SkillWithFiles?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSkill(skill: SkillEntity)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertSkillIgnoreConflict(skill: SkillEntity)

  @Update
  suspend fun updateSkill(skill: SkillEntity)

  @Query("UPDATE skills SET isEnabled = :enabled, updatedAt = :updatedAt WHERE id = :skillId")
  suspend fun setSkillEnabled(skillId: String, enabled: Boolean, updatedAt: Long)

  @Query("DELETE FROM skills WHERE id = :skillId AND source != 'system'")
  suspend fun deleteSkillById(skillId: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSkillFile(file: SkillFileEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSkillFiles(files: List<SkillFileEntity>)

  @Query("DELETE FROM skill_files WHERE id = :fileId")
  suspend fun deleteSkillFileById(fileId: String)

  @Query("DELETE FROM skill_files WHERE skillId = :skillId")
  suspend fun deleteSkillFilesForSkill(skillId: String)

  @Transaction
  suspend fun saveSkillWithFiles(skill: SkillEntity, files: List<SkillFileEntity>) {
    insertSkill(skill)
    deleteSkillFilesForSkill(skill.id)
    if (files.isNotEmpty()) {
      insertSkillFiles(files)
    }
  }
}
