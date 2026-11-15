package com.example.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Represents a persisted chat session stored locally in Room.
 */
@Immutable
@Serializable
@Entity(tableName = "conversations")
data class ConversationEntity(
  @PrimaryKey val id: String,
  val title: String,
  val provider: String,
  val modelId: String,
  val createdAt: Long,
  val updatedAt: Long,
)
