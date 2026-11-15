package com.example.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * User-created conversation folder for organizing chat history in the drawer.
 */
@Immutable
@Serializable
@Entity(tableName = "folders")
data class FolderEntity(
  @PrimaryKey val id: String,
  val name: String,
  val colorTag: String? = null,
  val createdAt: Long,
)
