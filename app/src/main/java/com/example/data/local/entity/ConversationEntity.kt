package com.example.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Represents a persisted chat session stored locally in Room.
 */
@Immutable
@Serializable
@Entity(
  tableName = "conversations",
  foreignKeys = [
    ForeignKey(
      entity = FolderEntity::class,
      parentColumns = ["id"],
      childColumns = ["folderId"],
      onDelete = ForeignKey.SET_NULL,
    )
  ],
  indices = [
    Index(value = ["folderId"]),
    Index(value = ["isPinned", "updatedAt"]),
  ],
)
data class ConversationEntity(
  @PrimaryKey val id: String,
  val title: String,
  val provider: String,
  val modelId: String,
  val createdAt: Long,
  val updatedAt: Long,
  val isPinned: Boolean = false,
  val folderId: String? = null,
  val branchedFromConversationId: String? = null,
)
