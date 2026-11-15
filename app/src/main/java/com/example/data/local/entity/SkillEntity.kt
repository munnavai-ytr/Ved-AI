package com.example.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * User-created or imported Claude-style markdown skill definition.
 */
@Immutable
@Entity(
  tableName = "skills",
  indices = [Index(value = ["updatedAt"])],
)
data class SkillEntity(
  @PrimaryKey val id: String,
  val name: String,
  val description: String,
  val skillMarkdown: String,
  val isEnabled: Boolean = true,
  val source: String = SOURCE_USER,
  val createdAt: Long,
  val updatedAt: Long,
) {
  companion object {
    const val SOURCE_USER = "user"
    const val SOURCE_IMPORTED = "imported"
  }
}

/**
 * Supporting UTF-8 reference file attached to a [SkillEntity].
 */
@Immutable
@Entity(
  tableName = "skill_files",
  foreignKeys = [
    ForeignKey(
      entity = SkillEntity::class,
      parentColumns = ["id"],
      childColumns = ["skillId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index(value = ["skillId"])],
)
data class SkillFileEntity(
  @PrimaryKey val id: String,
  val skillId: String,
  val fileName: String,
  val content: String,
)

/**
 * Full skill aggregate including its supporting reference files.
 */
@Immutable
data class SkillWithFiles(
  @Embedded val skill: SkillEntity,
  @Relation(parentColumn = "id", entityColumn = "skillId")
  val files: List<SkillFileEntity> = emptyList(),
)
