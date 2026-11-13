package com.example.data.repository

import com.example.data.local.dao.ChatDao
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.model.AiModelInfo
import com.example.data.model.AiProvider
import com.example.data.model.FallbackOpenRouterModels
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.OpenRouterApiService
import com.example.data.remote.StreamChunk
import com.example.data.remote.StreamingAiClient
import com.example.data.security.ApiKeyStore
import com.example.data.security.KeySource
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Snapshot of a deleted conversation so it can be restored via Undo.
 */
data class DeletedConversationBackup(
  val conversation: ConversationEntity,
  val messages: List<MessageEntity>,
)

/**
 * Single source of truth coordinating Room chat history, preferences, encrypted API keys,
 * OpenRouter model discovery, and streaming AI completions.
 */
class ChatRepository(
  private val chatDao: ChatDao,
  private val streamingClient: StreamingAiClient,
  private val openRouterApi: OpenRouterApiService,
  private val apiKeyStore: ApiKeyStore,
  private val preferencesRepository: UserPreferencesRepository,
) {

  val preferencesFlow: Flow<UserPreferences> = preferencesRepository.preferencesFlow
  val keyVersionFlow: StateFlow<Int> = apiKeyStore.keyVersion

  fun observeConversations(): Flow<List<ConversationEntity>> = chatDao.observeConversations()

  fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
    chatDao.observeMessages(conversationId)

  suspend fun getKeySource(provider: AiProvider): KeySource = apiKeyStore.getKeySource(provider)

  suspend fun getEffectiveKey(provider: AiProvider): String = apiKeyStore.getEffectiveKey(provider)

  suspend fun setOverrideKey(provider: AiProvider, rawKey: String) =
    apiKeyStore.setOverrideKey(provider, rawKey)

  suspend fun clearOverrideKey(provider: AiProvider) = apiKeyStore.clearOverrideKey(provider)

  suspend fun getCurrentPreferences(): UserPreferences = preferencesFlow.first()

  suspend fun createConversation(
    provider: AiProvider,
    modelId: String,
    initialTitle: String = "New chat",
  ): ConversationEntity {
    val now = System.currentTimeMillis()
    val entity =
      ConversationEntity(
        id = UUID.randomUUID().toString(),
        title = initialTitle,
        provider = provider.id,
        modelId = modelId,
        createdAt = now,
        updatedAt = now,
      )
    chatDao.insertConversation(entity)
    return entity
  }

  suspend fun insertUserMessage(conversationId: String, content: String): MessageEntity {
    val now = System.currentTimeMillis()
    val message =
      MessageEntity(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        role = MessageEntity.ROLE_USER,
        content = content,
        createdAt = now,
      )
    chatDao.insertMessage(message)
    chatDao.touchConversation(conversationId, now)
    return message
  }

  suspend fun insertAssistantPlaceholder(conversationId: String, modelId: String): MessageEntity {
    val now = System.currentTimeMillis()
    val message =
      MessageEntity(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        role = MessageEntity.ROLE_ASSISTANT,
        content = "",
        modelId = modelId,
        createdAt = now,
      )
    chatDao.insertMessage(message)
    return message
  }

  suspend fun updateAssistantMessage(
    messageId: String,
    conversationId: String,
    content: String,
    isError: Boolean = false,
  ) {
    chatDao.updateMessageContent(messageId = messageId, content = content, isError = isError)
    chatDao.touchConversation(conversationId, System.currentTimeMillis())
  }

  suspend fun deleteMessage(messageId: String) {
    chatDao.deleteMessageById(messageId)
  }

  suspend fun getMessagesSync(conversationId: String): List<MessageEntity> =
    chatDao.getMessagesSync(conversationId)

  suspend fun updateConversationTitle(conversationId: String, title: String) {
    chatDao.updateConversationTitle(conversationId, title, System.currentTimeMillis())
  }

  suspend fun updateConversationEngine(
    conversationId: String,
    provider: AiProvider,
    modelId: String,
  ) {
    chatDao.updateConversationEngine(conversationId, provider.id, modelId, System.currentTimeMillis())
  }

  suspend fun deleteConversationWithBackup(conversationId: String): DeletedConversationBackup? {
    val conversation = chatDao.getConversation(conversationId) ?: return null
    val messages = chatDao.getMessagesSync(conversationId)
    chatDao.deleteConversationById(conversationId)
    return DeletedConversationBackup(conversation = conversation, messages = messages)
  }

  suspend fun restoreConversation(backup: DeletedConversationBackup) {
    chatDao.restoreConversation(backup.conversation, backup.messages)
  }

  fun streamResponse(
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
  ): Flow<StreamChunk> = streamingClient.streamChat(provider, modelId, apiKey, history)

  suspend fun generateTitle(
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    userPrompt: String,
    assistantReply: String,
  ): String? =
    streamingClient.generateTitle(provider, modelId, apiKey, userPrompt, assistantReply)

  suspend fun fetchOpenRouterModels(): Result<List<AiModelInfo>> =
    runCatching {
      val response = openRouterApi.getModels()
      val mapped =
        response.data
          .asSequence()
          .filter { it.id.isNotBlank() }
          .map { dto ->
            val isFree =
              dto.id.endsWith(":free") ||
                (dto.pricing?.prompt == "0" && dto.pricing.completion == "0")
            AiModelInfo(
              id = dto.id,
              name = dto.name?.takeIf { it.isNotBlank() } ?: dto.id,
              provider = AiProvider.OPENROUTER,
              contextLength = dto.contextLength,
              isFree = isFree,
            )
          }
          .sortedWith(compareByDescending<AiModelInfo> { isFeaturedOpenRouterModel(it.id) }.thenBy { it.name.lowercase() })
          .toList()

      if (mapped.isEmpty()) FallbackOpenRouterModels else mapped
    }

  suspend fun formatConversationAsText(conversationId: String): String? {
    val conversation = chatDao.getConversation(conversationId) ?: return null
    val messages = chatDao.getMessagesSync(conversationId)
    if (messages.isEmpty()) return null

    return buildString {
      appendLine(conversation.title)
      appendLine("Model: ${conversation.modelId}")
      appendLine()
      messages.forEachIndexed { index, msg ->
        val speaker = if (msg.role == MessageEntity.ROLE_USER) "You" else "Ved"
        appendLine("$speaker:")
        appendLine(msg.content.trim())
        if (index < messages.lastIndex) {
          appendLine()
          appendLine("---")
          appendLine()
        }
      }
    }
  }

  private fun isFeaturedOpenRouterModel(id: String): Boolean {
    return id.startsWith("google/gemini") ||
      id.startsWith("anthropic/claude") ||
      id.startsWith("openai/gpt-4") ||
      id.startsWith("deepseek/") ||
      id.endsWith(":free")
  }
}
