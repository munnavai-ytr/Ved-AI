package com.example.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable

/**
 * Persisted file attachment associated with a chat [MessageEntity].
 * Stores only metadata and the local app-private file path (no base64 blobs in Room).
 */
@Immutable
@Serializable
@Entity(
  tableName = "message_attachments",
  foreignKeys = [
    ForeignKey(
      entity = MessageEntity::class,
      parentColumns = ["id"],
      childColumns = ["messageId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index(value = ["messageId"])],
)
data class MessageAttachmentEntity(
  @PrimaryKey val id: String,
  val messageId: String,
  val type: String,
  val displayName: String,
  val mimeType: String,
  val localPath: String,
  val sizeBytes: Long = 0L,
)

/**
 * Room relation pairing a [MessageEntity] with all attached files.
 */
@Immutable
data class MessageWithAttachments(
  @Embedded val message: MessageEntity,
  @Relation(parentColumn = "id", entityColumn = "messageId")
  val attachments: List<MessageAttachmentEntity> = emptyList(),
)
