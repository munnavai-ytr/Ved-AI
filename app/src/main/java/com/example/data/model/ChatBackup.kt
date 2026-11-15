package com.example.data.model

import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import kotlinx.serialization.Serializable

/**
 * Portable JSON envelope for exporting and importing Ved chat history.
 */
@Serializable
data class ChatBackup(
  val version: Int = CURRENT_VERSION,
  val exportedAt: Long = System.currentTimeMillis(),
  val conversations: List<ConversationEntity>,
  val messages: List<MessageEntity>,
) {
  companion object {
    const val CURRENT_VERSION = 1
  }
}
