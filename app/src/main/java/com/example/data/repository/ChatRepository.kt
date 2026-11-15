package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.dao.ChatDao
import com.example.data.local.dao.MemoryDao
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MemoryEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.model.AiModelInfo
import com.example.data.model.AiProvider
import com.example.data.model.ChatBackup
import com.example.data.model.FallbackOpenRouterModels
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.OpenRouterApiService
import com.example.data.remote.StreamChunk
import com.example.data.remote.StreamingAiClient
import com.example.data.security.ApiKeyStore
import com.example.data.security.KeySource
import com.example.data.util.ImageProcessor
import com.example.data.util.MemoryPromptHelper
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Snapshot of a deleted conversation so it can be restored via Undo.
 */
data class DeletedConversationBackup(
  val conversation: ConversationEntity,
  val messages: List<MessageEntity>,
)

/**
 * Summary of an imported JSON backup file.
 */
data class ImportBackupSummary(
  val conversationsImported: Int,
  val messagesImported: Int,
)

/**
 * Single source of truth coordinating Room chat history, long-term memory, preferences,
 * encrypted API keys, OpenRouter model discovery, multimodal image handling, backups,
 * and streaming AI completions.
 */
class ChatRepository(
  private val appContext: Context,
  private val chatDao: ChatDao,
  private val memoryDao: MemoryDao,
  private val streamingClient: StreamingAiClient,
  private val openRouterApi: OpenRouterApiService,
  private val apiKeyStore: ApiKeyStore,
  private val preferencesRepository: UserPreferencesRepository,
) {

  private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  val preferencesFlow: Flow<UserPreferences> = preferencesRepository.preferencesFlow
  val keyVersionFlow: StateFlow<Int> = apiKeyStore.keyVersion

  fun observeConversations(): Flow<List<ConversationEntity>> = chatDao.observeConversations()

  fun searchConversations(query: String): Flow<List<ConversationEntity>> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return observeConversations()
    val escaped =
      trimmed
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return chatDao.searchConversations(escaped)
  }

  fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
    chatDao.observeMessages(conversationId)

  // --- Long-Term Memory ---

  fun observeMemories(): Flow<List<MemoryEntity>> = memoryDao.observeAllMemories()

  fun searchMemories(query: String): Flow<List<MemoryEntity>> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return observeMemories()
    val escaped =
      trimmed
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return memoryDao.searchMemories(escaped)
  }

  suspend fun addMemory(
    content: String,
    category: String = MemoryEntity.CATEGORY_FACT,
    source: String = MemoryEntity.SOURCE_USER,
  ): MemoryEntity? {
    val trimmedContent = content.trim()
    if (trimmedContent.isEmpty()) return null

    val normalizedCategory =
      category.trim().lowercase().ifEmpty { MemoryEntity.CATEGORY_FACT }
    val normalizedSource =
      if (source == MemoryEntity.SOURCE_AI) MemoryEntity.SOURCE_AI else MemoryEntity.SOURCE_USER
    val now = System.currentTimeMillis()

    // Deduplicate case-insensitive exact content matches so repeated AI extractions refresh
    // timestamp/category rather than creating duplicate entries.
    val existing =
      memoryDao.getAllMemoriesSync().firstOrNull {
        it.content.trim().equals(trimmedContent, ignoreCase = true)
      }
    if (existing != null) {
      val updated = existing.copy(category = normalizedCategory, updatedAt = now)
      memoryDao.insertMemory(updated)
      return updated
    }

    val entity =
      MemoryEntity(
        id = UUID.randomUUID().toString(),
        content = trimmedContent,
        category = normalizedCategory,
        source = normalizedSource,
        createdAt = now,
        updatedAt = now,
      )
    memoryDao.insertMemory(entity)
    return entity
  }

  suspend fun updateMemory(id: String, content: String) {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return
    memoryDao.updateMemoryContent(id = id, content = trimmed, updatedAt = System.currentTimeMillis())
  }

  suspend fun deleteMemory(id: String) {
    memoryDao.deleteMemoryById(id)
  }

  suspend fun clearAllMemories() {
    memoryDao.deleteAllMemories()
  }

  suspend fun buildMemoryContextBlock(): String {
    val memories = memoryDao.getAllMemoriesSync()
    return MemoryPromptHelper.formatMemoryContextBlock(memories)
  }

  // --- Keys & Preferences ---

  suspend fun getKeySource(provider: AiProvider): KeySource = apiKeyStore.getKeySource(provider)

  suspend fun getEffectiveKey(provider: AiProvider): String = apiKeyStore.getEffectiveKey(provider)

  suspend fun setOverrideKey(provider: AiProvider, rawKey: String) =
    apiKeyStore.setOverrideKey(provider, rawKey)

  suspend fun clearOverrideKey(provider: AiProvider) = apiKeyStore.clearOverrideKey(provider)

  suspend fun getCurrentPreferences(): UserPreferences = preferencesFlow.first()

  suspend fun prepareChatImage(uri: Uri): String? = ImageProcessor.prepareChatImage(appContext, uri)

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

  suspend fun insertUserMessage(
    conversationId: String,
    content: String,
    imageUri: String? = null,
  ): MessageEntity {
    val existingMax = chatDao.getMessagesSync(conversationId).maxOfOrNull { it.createdAt } ?: 0L
    val timestamp = maxOf(System.currentTimeMillis(), existingMax + 1L)
    val message =
      MessageEntity(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        role = MessageEntity.ROLE_USER,
        content = content,
        createdAt = timestamp,
        imageUri = imageUri,
      )
    chatDao.insertMessage(message)
    chatDao.touchConversation(conversationId, timestamp)
    return message
  }

  suspend fun insertAssistantPlaceholder(conversationId: String, modelId: String): MessageEntity {
    val existingMax = chatDao.getMessagesSync(conversationId).maxOfOrNull { it.createdAt } ?: 0L
    val timestamp = maxOf(System.currentTimeMillis(), existingMax + 1L)
    val message =
      MessageEntity(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        role = MessageEntity.ROLE_ASSISTANT,
        content = "",
        modelId = modelId,
        createdAt = timestamp,
      )
    chatDao.insertMessage(message)
    return message
  }

  suspend fun updateAssistantMessage(
    messageId: String,
    conversationId: String,
    content: String,
    isError: Boolean = false,
    promptTokens: Int? = null,
    completionTokens: Int? = null,
  ) {
    chatDao.updateMessageResult(
      messageId = messageId,
      content = content,
      isError = isError,
      promptTokens = promptTokens,
      completionTokens = completionTokens,
    )
    chatDao.touchConversation(conversationId, System.currentTimeMillis())
  }

  suspend fun deleteMessage(messageId: String) {
    chatDao.deleteMessageById(messageId)
  }

  suspend fun deleteMessagesFrom(conversationId: String, fromTimestamp: Long) {
    chatDao.deleteMessagesFrom(conversationId, fromTimestamp)
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

  suspend fun exportBackupJson(): String =
    withContext(Dispatchers.IO) {
      val conversations = chatDao.getAllConversationsSync()
      val messages = chatDao.getAllMessagesSync()
      val payload =
        ChatBackup(
          version = ChatBackup.CURRENT_VERSION,
          exportedAt = System.currentTimeMillis(),
          conversations = conversations,
          messages = messages,
        )
      backupJson.encodeToString(ChatBackup.serializer(), payload)
    }

  suspend fun importBackupJson(rawJson: String): Result<ImportBackupSummary> =
    withContext(Dispatchers.IO) {
      runCatching {
        val decoded = backupJson.decodeFromString(ChatBackup.serializer(), rawJson)
        val existingConversationIds = chatDao.getAllConversationsSync().mapTo(HashSet()) { it.id }
        val importedConversationIds = decoded.conversations.mapTo(HashSet()) { it.id }
        val validConversationIds = existingConversationIds + importedConversationIds
        val validMessages =
          decoded.messages.filter { it.conversationId in validConversationIds }

        chatDao.importBackup(decoded.conversations, validMessages)
        ImportBackupSummary(
          conversationsImported = decoded.conversations.size,
          messagesImported = validMessages.size,
        )
      }
    }

  fun streamResponse(
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
    systemPrompt: String = "",
    memoryContext: String = "",
  ): Flow<StreamChunk> = flow {
    val imageMap =
      if (provider == AiProvider.GEMINI) {
        val uris = history.mapNotNull { it.imageUri }.distinct()
        if (uris.isEmpty()) {
          emptyMap()
        } else {
          withContext(Dispatchers.IO) {
            buildMap {
              for (uriStr in uris) {
                val base64 = ImageProcessor.encodeUriToBase64(appContext, uriStr)
                if (!base64.isNullOrBlank()) {
                  put(uriStr, base64)
                }
              }
            }
          }
        }
      } else {
        emptyMap()
      }

    emitAll(
      streamingClient.streamChat(
        provider = provider,
        modelId = modelId,
        apiKey = apiKey,
        history = history,
        systemPrompt = systemPrompt,
        memoryContext = memoryContext,
        imageBase64ByUri = imageMap,
      )
    )
  }

  suspend fun generateTitle(
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    userPrompt: String,
    assistantReply: String,
    systemPrompt: String = "",
  ): String? =
    streamingClient.generateTitle(
      provider = provider,
      modelId = modelId,
      apiKey = apiKey,
      userPrompt = userPrompt,
      assistantReply = assistantReply,
      systemPrompt = systemPrompt,
    )

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
          .sortedWith(
            compareByDescending<AiModelInfo> { isFeaturedOpenRouterModel(it.id) }
              .thenBy { it.name.lowercase() }
          )
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
