package com.example.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Durable long-term memory fact remembered across all conversations.
 */
@Immutable
@Serializable
@Entity(
  tableName = "memories",
  indices = [Index(value = ["updatedAt"])],
)
data class MemoryEntity(
  @PrimaryKey val id: String,
  val content: String,
  val category: String,
  val source: String,
  val createdAt: Long,
  val updatedAt: Long,
) {
  companion object {
    const val SOURCE_USER = "user"
    const val SOURCE_AI = "ai"

    const val CATEGORY_PROFILE = "profile"
    const val CATEGORY_PREFERENCE = "preference"
    const val CATEGORY_PROJECT = "project"
    const val CATEGORY_FACT = "fact"

    val DEFAULT_CATEGORIES =
      listOf(CATEGORY_PROFILE, CATEGORY_PREFERENCE, CATEGORY_PROJECT, CATEGORY_FACT)
  }
}
