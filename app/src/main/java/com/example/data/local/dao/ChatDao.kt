package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for conversations and messages.
 */
@Dao
interface ChatDao {

  @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
  fun observeConversations(): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
  fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

  @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
  suspend fun getConversation(conversationId: String): ConversationEntity?

  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
  suspend fun getMessagesSync(conversationId: String): List<MessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertConversation(conversation: ConversationEntity)

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

  @Query("UPDATE messages SET content = :content, isError = :isError WHERE id = :messageId")
  suspend fun updateMessageContent(messageId: String, content: String, isError: Boolean)

  @Query("DELETE FROM messages WHERE id = :messageId")
  suspend fun deleteMessageById(messageId: String)

  @Transaction
  suspend fun restoreConversation(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
  ) {
    insertConversation(conversation)
    if (messages.isNotEmpty()) {
      insertMessages(messages)
    }
  }
}
