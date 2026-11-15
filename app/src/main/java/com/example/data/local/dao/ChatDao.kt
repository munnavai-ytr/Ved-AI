package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageAttachmentEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.MessageWithAttachments
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for conversations, messages, and file attachments.
 */
@Dao
interface ChatDao {

  @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
  fun observeConversations(): Flow<List<ConversationEntity>>

  @Query(
    """
    SELECT DISTINCT c.* FROM conversations c
    LEFT JOIN messages m ON c.id = m.conversationId
    WHERE c.title LIKE '%' || :escapedQuery || '%' ESCAPE '\'
       OR m.content LIKE '%' || :escapedQuery || '%' ESCAPE '\'
    ORDER BY c.updatedAt DESC
    """
  )
  fun searchConversations(escapedQuery: String): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
  fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

  @Transaction
  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
  fun observeMessagesWithAttachments(conversationId: String): Flow<List<MessageWithAttachments>>

  @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
  suspend fun getConversation(conversationId: String): ConversationEntity?

  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
  suspend fun getMessagesSync(conversationId: String): List<MessageEntity>

  @Transaction
  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
  suspend fun getMessagesWithAttachmentsSync(conversationId: String): List<MessageWithAttachments>

  @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
  suspend fun getAllConversationsSync(): List<ConversationEntity>

  @Query("SELECT * FROM messages ORDER BY createdAt ASC")
  suspend fun getAllMessagesSync(): List<MessageEntity>

  @Query("SELECT * FROM message_attachments")
  suspend fun getAllAttachmentsSync(): List<MessageAttachmentEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertConversation(conversation: ConversationEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertConversations(conversations: List<ConversationEntity>)

  @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :conversationId")
  suspend fun updateConversationTitle(conversationId: String, title: String, updatedAt: Long)

  @Query(
    "UPDATE conversations SET provider = :provider, modelId = :modelId, updatedAt = :updatedAt WHERE id = :conversationId"
  )
  suspend fun updateConversationEngine(
    conversationId: String,
    provider: String,
    modelId: String,
    updatedAt: Long,
  )

  @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :conversationId")
  suspend fun touchConversation(conversationId: String, updatedAt: Long)

  @Query("DELETE FROM conversations WHERE id = :conversationId")
  suspend fun deleteConversationById(conversationId: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMessage(message: MessageEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMessages(messages: List<MessageEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAttachments(attachments: List<MessageAttachmentEntity>)

  @Transaction
  suspend fun insertMessageWithAttachments(
    message: MessageEntity,
    attachments: List<MessageAttachmentEntity>,
  ) {
    insertMessage(message)
    if (attachments.isNotEmpty()) {
      insertAttachments(attachments)
    }
  }

  @Query(
    """
    UPDATE messages
    SET content = :content,
        isError = :isError,
        promptTokens = :promptTokens,
        completionTokens = :completionTokens,
        activeSkillName = COALESCE(:activeSkillName, activeSkillName)
    WHERE id = :messageId
    """
  )
  suspend fun updateMessageResult(
    messageId: String,
    content: String,
    isError: Boolean,
    promptTokens: Int?,
    completionTokens: Int?,
    activeSkillName: String? = null,
  )

  @Query("DELETE FROM messages WHERE id = :messageId")
  suspend fun deleteMessageById(messageId: String)

  @Query(
    "DELETE FROM messages WHERE conversationId = :conversationId AND createdAt >= :fromTimestamp"
  )
  suspend fun deleteMessagesFrom(conversationId: String, fromTimestamp: Long)

  @Transaction
  suspend fun restoreConversation(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
    attachments: List<MessageAttachmentEntity> = emptyList(),
  ) {
    insertConversation(conversation)
    if (messages.isNotEmpty()) {
      insertMessages(messages)
    }
    if (attachments.isNotEmpty()) {
      insertAttachments(attachments)
    }
  }

  @Transaction
  suspend fun importBackup(
    conversations: List<ConversationEntity>,
    messages: List<MessageEntity>,
    attachments: List<MessageAttachmentEntity> = emptyList(),
  ) {
    if (conversations.isNotEmpty()) {
      insertConversations(conversations)
    }
    if (messages.isNotEmpty()) {
      insertMessages(messages)
    }
    if (attachments.isNotEmpty()) {
      insertAttachments(attachments)
    }
  }
}
