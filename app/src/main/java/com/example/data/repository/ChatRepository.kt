package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.dao.ChatDao
import com.example.data.local.dao.MemoryDao
import com.example.data.local.dao.SkillDao
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.FolderEntity
import com.example.data.local.entity.MemoryEntity
import com.example.data.local.entity.MessageAttachmentEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.MessageWithAttachments
import com.example.data.local.entity.SkillEntity
import com.example.data.local.entity.SkillFileEntity
import com.example.data.local.entity.SkillWithFiles
import com.example.data.model.AiModelInfo
import com.example.data.model.AiProvider
import com.example.data.model.ChatBackup
import com.example.data.model.FallbackGeminiModels
import com.example.data.model.FallbackOpenRouterModels
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiModelDto
import com.example.data.remote.OpenRouterApiService
import com.example.data.remote.StreamChunk
import com.example.data.remote.StreamingAiClient
import com.example.data.security.ApiKeyStore
import com.example.data.security.KeySource
import com.example.data.util.AttachmentPrepareResult
import com.example.data.util.AttachmentProcessor
import com.example.data.util.AttachmentTypes
import com.example.data.util.ImageProcessor
import com.example.data.util.MemoryPromptHelper
import com.example.data.util.PendingAttachment
import com.example.data.util.SkillMatcher
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
  val attachments: List<MessageAttachmentEntity> = emptyList(),
)

/**
 * Summary of an imported JSON backup file.
 */
data class ImportBackupSummary(
  val conversationsImported: Int,
  val messagesImported: Int,
)

/**
 * Resolved skill blocks and activated skill name for a single AI turn.
 */
data class ResolvedSkillContext(
  val skillsIndexBlock: String = "",
  val activeSkillBlock: String = "",
  val activeSkillName: String? = null,
)

/**
 * Single source of truth coordinating Room chat history, multi-file attachments, long-term memory,
 * custom skills, preferences, encrypted API keys, dynamic Gemini & OpenRouter model discovery,
 * backups, and streaming AI completions.
 */
class ChatRepository(
  private val appContext: Context,
  private val chatDao: ChatDao,
  private val memoryDao: MemoryDao,
  private val skillDao: SkillDao,
  private val streamingClient: StreamingAiClient,
  private val geminiApi: GeminiApiService,
  private val openRouterApi: OpenRouterApiService,
  private val apiKeyStore: ApiKeyStore,
  private val preferencesRepository: UserPreferencesRepository,
) {

  private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Volatile private var cachedGeminiModels: List<AiModelInfo>? = null
  @Volatile private var cachedGeminiModelsKeyHash: Int = 0

  val preferencesFlow: Flow<UserPreferences> = preferencesRepository.preferencesFlow
  val keyVersionFlow: StateFlow<Int> = apiKeyStore.keyVersion

  fun observeConversations(): Flow<List<ConversationEntity>> = chatDao.observeConversations()

  fun observeFolders(): Flow<List<FolderEntity>> = chatDao.observeFolders()

  suspend fun createFolder(name: String, colorTag: String? = null): FolderEntity? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    val existing =
      chatDao.getAllFoldersSync().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    if (existing != null) return existing
    val folder =
      FolderEntity(
        id = UUID.randomUUID().toString(),
        name = trimmed,
        colorTag = colorTag,
        createdAt = System.currentTimeMillis(),
      )
    chatDao.insertFolder(folder)
    return folder
  }

  suspend fun deleteFolder(folderId: String) {
    chatDao.deleteFolderById(folderId)
  }

  suspend fun setConversationPinned(conversationId: String, isPinned: Boolean) {
    chatDao.setConversationPinned(conversationId, isPinned)
  }

  suspend fun moveConversationToFolder(conversationId: String, folderId: String?) {
    chatDao.setConversationFolder(conversationId, folderId)
  }

  suspend fun getConversation(conversationId: String): ConversationEntity? =
    chatDao.getConversation(conversationId)

  /**
   * Creates a branched copy of [sourceConversationId] containing all messages and attachment
   * metadata rows up to and including [upToMessageId], returning the newly created conversation.
   */
  suspend fun branchConversation(
    sourceConversationId: String,
    upToMessageId: String,
  ): ConversationEntity =
    withContext(Dispatchers.IO) {
      val source =
        chatDao.getConversation(sourceConversationId)
          ?: throw IllegalArgumentException("Source conversation not found: $sourceConversationId")
      val allWithAttachments = chatDao.getMessagesWithAttachmentsSync(sourceConversationId)
      val targetIndex = allWithAttachments.indexOfFirst { it.message.id == upToMessageId }
      if (targetIndex < 0) {
        throw IllegalArgumentException("Target message not found in conversation: $upToMessageId")
      }

      val slice = allWithAttachments.subList(0, targetIndex + 1)
      val now = System.currentTimeMillis()
      val newConversationId = UUID.randomUUID().toString()
      val newConversation =
        ConversationEntity(
          id = newConversationId,
          title = "${source.title} (branch)",
          provider = source.provider,
          modelId = source.modelId,
          createdAt = now,
          updatedAt = now,
          isPinned = false,
          folderId = source.folderId,
          branchedFromConversationId = source.id,
        )

      val clonedMessages = ArrayList<MessageEntity>(slice.size)
      val clonedAttachments = ArrayList<MessageAttachmentEntity>()

      for (item in slice) {
        val newMsgId = UUID.randomUUID().toString()
        clonedMessages.add(
          item.message.copy(
            id = newMsgId,
            conversationId = newConversationId,
          )
        )
        for (att in item.attachments) {
          clonedAttachments.add(
            att.copy(
              id = UUID.randomUUID().toString(),
              messageId = newMsgId,
            )
          )
        }
      }

      chatDao.insertBranchedConversation(newConversation, clonedMessages, clonedAttachments)
      newConversation
    }

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

  fun observeMessagesWithAttachments(conversationId: String): Flow<List<MessageWithAttachments>> =
    chatDao.observeMessagesWithAttachments(conversationId)

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

  // --- Custom Skills ---

  fun observeSkills(): Flow<List<SkillEntity>> = skillDao.observeAllSkills()

  fun observeSkillsWithFiles(): Flow<List<SkillWithFiles>> = skillDao.observeAllSkillsWithFiles()

  suspend fun getSkillWithFiles(skillId: String): SkillWithFiles? = skillDao.getSkillWithFiles(skillId)

  suspend fun saveSkill(
    id: String? = null,
    name: String,
    description: String,
    skillMarkdown: String,
    source: String = SkillEntity.SOURCE_USER,
    isEnabled: Boolean = true,
    files: List<SkillFileEntity> = emptyList(),
  ): SkillEntity {
    val now = System.currentTimeMillis()
    val existing = id?.let { skillDao.getSkillWithFiles(it)?.skill }
    val skillId = existing?.id ?: (id ?: UUID.randomUUID().toString())
    val entity =
      SkillEntity(
        id = skillId,
        name = name.trim(),
        description = description.trim(),
        skillMarkdown = skillMarkdown.trim(),
        isEnabled = existing?.isEnabled ?: isEnabled,
        source = existing?.source ?: source,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
      )
    val normalizedFiles = files.map { it.copy(skillId = skillId) }
    skillDao.saveSkillWithFiles(entity, normalizedFiles)
    return entity
  }

  suspend fun setSkillEnabled(skillId: String, enabled: Boolean) {
    skillDao.setSkillEnabled(skillId, enabled, System.currentTimeMillis())
  }

  suspend fun deleteSkill(skillId: String) {
    skillDao.deleteSkillById(skillId)
  }

  suspend fun resolveSkillContext(
    userPrompt: String,
    pinnedSkillId: String? = null,
  ): ResolvedSkillContext {
    val enabledSkills = skillDao.getAllEnabledSkillsSync()
    if (enabledSkills.isEmpty() && pinnedSkillId == null) {
      return ResolvedSkillContext()
    }

    val indexBlock = SkillMatcher.formatSkillsIndexBlock(enabledSkills)
    val pinnedSkill =
      pinnedSkillId?.let { id -> skillDao.getSkillWithFiles(id) }
    val matchedSkill =
      if (pinnedSkill != null) {
        pinnedSkill
      } else {
        val best = SkillMatcher.findBestMatchingSkill(userPrompt, enabledSkills)
        best?.let { skillDao.getSkillWithFiles(it.id) }
      }

    return if (matchedSkill != null) {
      ResolvedSkillContext(
        skillsIndexBlock = indexBlock,
        activeSkillBlock = SkillMatcher.formatActiveSkillBlock(matchedSkill),
        activeSkillName = matchedSkill.skill.name,
      )
    } else {
      ResolvedSkillContext(skillsIndexBlock = indexBlock)
    }
  }

  // --- Keys & Preferences ---

  suspend fun getKeySource(provider: AiProvider): KeySource = apiKeyStore.getKeySource(provider)

  suspend fun getEffectiveKey(provider: AiProvider): String = apiKeyStore.getEffectiveKey(provider)

  suspend fun setOverrideKey(provider: AiProvider, rawKey: String) =
    apiKeyStore.setOverrideKey(provider, rawKey)

  suspend fun clearOverrideKey(provider: AiProvider) = apiKeyStore.clearOverrideKey(provider)

  suspend fun getCurrentPreferences(): UserPreferences = preferencesFlow.first()

  suspend fun prepareChatImage(uri: Uri): String? = ImageProcessor.prepareChatImage(appContext, uri)

  suspend fun prepareAttachment(
    uri: Uri,
    existingAttachments: List<PendingAttachment> = emptyList(),
  ): AttachmentPrepareResult =
    AttachmentProcessor.prepareAttachment(appContext, uri, existingAttachments)

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
    attachments: List<PendingAttachment> = emptyList(),
  ): MessageEntity {
    val existingMax = chatDao.getMessagesSync(conversationId).maxOfOrNull { it.createdAt } ?: 0L
    val timestamp = maxOf(System.currentTimeMillis(), existingMax + 1L)
    val messageId = UUID.randomUUID().toString()
    val firstImagePath =
      imageUri ?: attachments.firstOrNull { it.type == AttachmentTypes.IMAGE }?.localPath
    val message =
      MessageEntity(
        id = messageId,
        conversationId = conversationId,
        role = MessageEntity.ROLE_USER,
        content = content,
        createdAt = timestamp,
        imageUri = firstImagePath,
      )
    val attachmentEntities =
      if (attachments.isNotEmpty()) {
        attachments.map { pending ->
          MessageAttachmentEntity(
            id = pending.id,
            messageId = messageId,
            type = pending.type,
            displayName = pending.displayName,
            mimeType = pending.mimeType,
            localPath = pending.localPath,
            sizeBytes = pending.sizeBytes,
          )
        }
      } else if (!imageUri.isNullOrBlank()) {
        listOf(
          MessageAttachmentEntity(
            id = UUID.randomUUID().toString(),
            messageId = messageId,
            type = AttachmentTypes.IMAGE,
            displayName = "image.jpg",
            mimeType = "image/jpeg",
            localPath = imageUri,
            sizeBytes = 0L,
          )
        )
      } else {
        emptyList()
      }

    chatDao.insertMessageWithAttachments(message, attachmentEntities)
    chatDao.touchConversation(conversationId, timestamp)
    return message
  }

  suspend fun insertAssistantPlaceholder(
    conversationId: String,
    modelId: String,
    activeSkillName: String? = null,
  ): MessageEntity {
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
        activeSkillName = activeSkillName,
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
    activeSkillName: String? = null,
  ) {
    chatDao.updateMessageResult(
      messageId = messageId,
      content = content,
      isError = isError,
      promptTokens = promptTokens,
      completionTokens = completionTokens,
      activeSkillName = activeSkillName,
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

  suspend fun getMessagesWithAttachmentsSync(conversationId: String): List<MessageWithAttachments> =
    chatDao.getMessagesWithAttachmentsSync(conversationId)

  suspend fun getConversationTitle(conversationId: String): String? =
    chatDao.getConversation(conversationId)?.title

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
    val withAttachments = chatDao.getMessagesWithAttachmentsSync(conversationId)
    val messages = withAttachments.map { it.message }
    val attachments = withAttachments.flatMap { it.attachments }
    chatDao.deleteConversationById(conversationId)
    return DeletedConversationBackup(
      conversation = conversation,
      messages = messages,
      attachments = attachments,
    )
  }

  suspend fun restoreConversation(backup: DeletedConversationBackup) {
    chatDao.restoreConversation(backup.conversation, backup.messages, backup.attachments)
  }

  suspend fun exportBackupJson(): String =
    withContext(Dispatchers.IO) {
      val folders = chatDao.getAllFoldersSync()
      val conversations = chatDao.getAllConversationsSync()
      val messages = chatDao.getAllMessagesSync()
      val attachments = chatDao.getAllAttachmentsSync()
      val payload =
        ChatBackup(
          version = ChatBackup.CURRENT_VERSION,
          exportedAt = System.currentTimeMillis(),
          conversations = conversations,
          messages = messages,
          attachments = attachments,
          folders = folders,
        )
      backupJson.encodeToString(ChatBackup.serializer(), payload)
    }

  suspend fun importBackupJson(rawJson: String): Result<ImportBackupSummary> =
    withContext(Dispatchers.IO) {
      runCatching {
        val decoded = backupJson.decodeFromString(ChatBackup.serializer(), rawJson)
        val existingFolderIds = chatDao.getAllFoldersSync().mapTo(HashSet()) { it.id }
        val importedFolderIds = decoded.folders.mapTo(HashSet()) { it.id }
        val validFolderIds = existingFolderIds + importedFolderIds

        val existingConversationIds = chatDao.getAllConversationsSync().mapTo(HashSet()) { it.id }
        val importedConversationIds = decoded.conversations.mapTo(HashSet()) { it.id }
        val validConversationIds = existingConversationIds + importedConversationIds
        val sanitizedConversations =
          decoded.conversations.map { conv ->
            if (conv.folderId != null && conv.folderId !in validFolderIds) {
              conv.copy(folderId = null)
            } else {
              conv
            }
          }
        val validMessages =
          decoded.messages.filter { it.conversationId in validConversationIds }
        val validMessageIds = validMessages.mapTo(HashSet()) { it.id }
        val validAttachments = decoded.attachments.filter { it.messageId in validMessageIds }

        chatDao.importBackup(
          conversations = sanitizedConversations,
          messages = validMessages,
          attachments = validAttachments,
          folders = decoded.folders,
        )
        ImportBackupSummary(
          conversationsImported = sanitizedConversations.size,
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
    skillsIndexBlock: String = "",
    activeSkillBlock: String = "",
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
  ): Flow<StreamChunk> = flow {
    val binaryBase64ByPath = mutableMapOf<String, String>()
    val textContentByPath = mutableMapOf<String, String>()
    var totalBase64Bytes = 0L

    val allAttachments = attachmentsByMessageId.values.flatten()
    if (allAttachments.isNotEmpty() || history.any { !it.imageUri.isNullOrBlank() }) {
      var budgetError: String? = null
      withContext(Dispatchers.IO) {
        if (allAttachments.isNotEmpty()) {
          for (att in allAttachments) {
            when (att.type) {
              AttachmentTypes.IMAGE,
              AttachmentTypes.PDF,
              AttachmentTypes.AUDIO,
              AttachmentTypes.VIDEO -> {
                if (provider == AiProvider.GEMINI && !binaryBase64ByPath.containsKey(att.localPath)) {
                  val b64 = AttachmentProcessor.readFileAsBase64(appContext, att.localPath)
                  if (!b64.isNullOrBlank()) {
                    totalBase64Bytes += b64.length
                    if (totalBase64Bytes > AttachmentProcessor.MAX_REQUEST_BASE64_BYTES) {
                      budgetError =
                        "Combined attachments in this conversation exceed the 15 MB request payload limit."
                      return@withContext
                    }
                    binaryBase64ByPath[att.localPath] = b64
                  }
                }
              }
              AttachmentTypes.TEXT -> {
                if (!textContentByPath.containsKey(att.localPath)) {
                  val content = AttachmentProcessor.readTextAttachmentContent(att.localPath)
                  if (content != null) {
                    textContentByPath[att.localPath] = content
                  }
                }
              }
            }
          }
        }

        // Legacy fallback for messages with imageUri but no rows in message_attachments
        if (provider == AiProvider.GEMINI) {
          for (msg in history) {
            if (attachmentsByMessageId[msg.id].isNullOrEmpty() && !msg.imageUri.isNullOrBlank()) {
              val uriStr = msg.imageUri
              if (!binaryBase64ByPath.containsKey(uriStr)) {
                val b64 = ImageProcessor.encodeUriToBase64(appContext, uriStr)
                if (!b64.isNullOrBlank()) {
                  totalBase64Bytes += b64.length
                  if (totalBase64Bytes > AttachmentProcessor.MAX_REQUEST_BASE64_BYTES) {
                    budgetError =
                      "Combined attachments in this conversation exceed the 15 MB request payload limit."
                    return@withContext
                  }
                  binaryBase64ByPath[uriStr] = b64
                }
              }
            }
          }
        }
      }

      if (budgetError != null) {
        emit(StreamChunk.Error(budgetError!!))
        return@flow
      }
    }

    emitAll(
      streamingClient.streamChat(
        provider = provider,
        modelId = modelId,
        apiKey = apiKey,
        history = history,
        systemPrompt = systemPrompt,
        memoryContext = memoryContext,
        skillsIndexBlock = skillsIndexBlock,
        activeSkillBlock = activeSkillBlock,
        imageBase64ByUri = binaryBase64ByPath,
        attachmentsByMessageId = attachmentsByMessageId,
        binaryBase64ByPath = binaryBase64ByPath,
        textContentByPath = textContentByPath,
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

  suspend fun generateSuggestedReplies(
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
  ): List<String> =
    streamingClient.generateSuggestedReplies(
      provider = provider,
      modelId = modelId,
      apiKey = apiKey,
      history = history,
    )

  /**
   * Fetches live Gemini models supporting `generateContent` from `v1beta/models`, caching
   * successful results in memory for the active API key until [forceRefresh] is requested.
   */
  suspend fun fetchGeminiModels(
    apiKey: String,
    forceRefresh: Boolean = false,
  ): Result<List<AiModelInfo>> {
    val trimmedKey = apiKey.trim()
    if (trimmedKey.isEmpty()) {
      return Result.success(FallbackGeminiModels)
    }

    val keyHash = trimmedKey.hashCode()
    val cached = cachedGeminiModels
    if (!forceRefresh && cached != null && cachedGeminiModelsKeyHash == keyHash) {
      return Result.success(cached)
    }

    return runCatching {
      val collectedDtos = mutableListOf<GeminiModelDto>()
      var pageToken: String? = null
      var guard = 0
      do {
        val response = geminiApi.getModels(apiKey = trimmedKey, pageSize = 100, pageToken = pageToken)
        collectedDtos.addAll(response.models)
        pageToken = response.nextPageToken?.takeIf { it.isNotBlank() }
        guard++
      } while (pageToken != null && guard < 5)

      val mapped = mapGeminiModels(collectedDtos)
      val finalModels = if (mapped.isEmpty()) FallbackGeminiModels else mapped
      cachedGeminiModels = finalModels
      cachedGeminiModelsKeyHash = keyHash
      finalModels
    }
  }

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

  companion object {
    internal fun mapGeminiModels(dtos: List<GeminiModelDto>): List<AiModelInfo> {
      return dtos
        .asSequence()
        .filter { dto ->
          dto.name.isNotBlank() &&
            dto.supportedGenerationMethods.any { it.equals("generateContent", ignoreCase = true) }
        }
        .map { dto ->
          val cleanId = dto.name.removePrefix("models/").trim()
          AiModelInfo(
            id = cleanId,
            name = dto.displayName?.takeIf { it.isNotBlank() } ?: cleanId,
            provider = AiProvider.GEMINI,
            contextLength = dto.inputTokenLimit,
          )
        }
        .distinctBy { it.id }
        .sortedWith(
          compareByDescending<AiModelInfo> { geminiModelPriority(it.id) }
            .thenBy { it.name.lowercase() }
        )
        .toList()
    }

    private fun geminiModelPriority(id: String): Int {
      val lower = id.lowercase()
      return when {
        lower == "gemini-flash-latest" -> 100
        lower.contains("gemini-3") && lower.contains("flash") -> 96
        lower == "gemini-pro-latest" -> 92
        lower.contains("gemini-2.5-flash") && !lower.contains("lite") -> 90
        lower.contains("gemini-2.5-flash") && lower.contains("lite") -> 88
        lower.contains("gemini-3") && lower.contains("pro") -> 86
        lower.contains("gemini-3") -> 85
        lower.contains("gemini-2.5-pro") -> 78
        lower.contains("gemini-2.5") -> 75
        lower.contains("gemini-2.0") && lower.contains("flash") -> 70
        lower.contains("gemini-2.0") -> 60
        else -> 10
      }
    }
  }
}
