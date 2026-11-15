package com.example.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Represents a single user or assistant message inside a conversation.
 */
@Immutable
@Serializable
@Entity(
  tableName = "messages",
  foreignKeys = [
    ForeignKey(
      entity = ConversationEntity::class,
      parentColumns = ["id"],
      childColumns = ["conversationId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index(value = ["conversationId", "createdAt"])],
)
data class MessageEntity(
  @PrimaryKey val id: String,
  val conversationId: String,
  val role: String,
  val content: String,
  val modelId: String? = null,
  val isError: Boolean = false,
  val createdAt: Long,
  val imageUri: String? = null,
  val promptTokens: Int? = null,
  val completionTokens: Int? = null,
  val activeSkillName: String? = null,
) {
  companion object {
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"
  }
}
