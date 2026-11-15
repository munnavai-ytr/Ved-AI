package com.example.ui.viewmodel

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.MessageWithAttachments
import com.example.data.local.entity.SkillEntity
import com.example.data.model.AiProvider
import com.example.data.model.FallbackGeminiModels
import com.example.data.preferences.UserPreferences
import com.example.data.repository.ChatRepository
import com.example.data.repository.DeletedConversationBackup
import com.example.data.util.AttachmentPrepareResult
import com.example.data.util.AttachmentTypes
import com.example.data.util.PendingAttachment
import com.example.service.AiGenerationCoordinator
import com.example.service.GenerationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Stable structural UI state for [ChatScreen]. Deliberately excludes rapidly changing
 * [ChatViewModel.streamingText] and [ChatViewModel.drawerSearchQuery] so unrelated message
 * items and chrome do not recompose per token or per search keystroke.
 */
@Immutable
data class ChatUiState(
  val conversations: List<ConversationEntity> = emptyList(),
  val activeConversationId: String? = null,
  val activeTitle: String = "Ved",
  val messages: List<MessageWithAttachments> = emptyList(),
  val streamingMessageId: String? = null,
  val isGenerating: Boolean = false,
  val activeProvider: AiProvider = AiProvider.GEMINI,
  val activeModelId: String = FallbackGeminiModels.first().id,
  val hasActiveApiKey: Boolean = true,
  val editingMessageId: String? = null,
  val pendingAttachments: List<PendingAttachment> = emptyList(),
  val enabledSkills: List<SkillEntity> = emptyList(),
  val pinnedSkillId: String? = null,
) {
  val pinnedSkill: SkillEntity?
    get() = pinnedSkillId?.let { id -> enabledSkills.firstOrNull { it.id == id } }
}

sealed interface ChatUiEvent {
  data class ShowMessage(val message: String) : ChatUiEvent

  data class ConversationDeleted(val backup: DeletedConversationBackup) : ChatUiEvent

  data class ShareText(val text: String, val title: String) : ChatUiEvent

  data class CopyText(val text: String, val feedback: String) : ChatUiEvent
}

/**
 * ViewModel driving the primary chat experience, drawer history & search, isolated streaming
 * state (delegated to [AiGenerationCoordinator] for foreground-service persistence), message
 * editing/regeneration, multi-file attachments, and custom skill activation.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(
  private val repository: ChatRepository,
  private val generationCoordinator: AiGenerationCoordinator,
) : ViewModel() {

  private val _activeConversationId = MutableStateFlow<String?>(null)

  /**
   * Dedicated StateFlow for active streaming text so ONLY the single streaming message item
   * observes and recomposes on streamed updates.
   */
  val streamingText: StateFlow<String> = generationCoordinator.streamingText

  private val _inputText = MutableStateFlow("")
  val inputText: StateFlow<String> = _inputText.asStateFlow()

  private val _drawerSearchQuery = MutableStateFlow("")
  val drawerSearchQuery: StateFlow<String> = _drawerSearchQuery.asStateFlow()

  private val _editingMessageId = MutableStateFlow<String?>(null)
  private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
  private val _pinnedSkillId = MutableStateFlow<String?>(null)

  private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 8)
  val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

  private val preferencesFlow: StateFlow<UserPreferences> =
    repository.preferencesFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = UserPreferences(),
    )

  private val hasActiveApiKeyFlow: StateFlow<Boolean> =
    combine(preferencesFlow, repository.keyVersionFlow) { prefs, _ ->
        repository.getEffectiveKey(prefs.activeProvider).isNotBlank()
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
      )

  private val allConversationsFlow =
    repository
      .observeConversations()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
      )

  private val drawerConversationsFlow =
    _drawerSearchQuery
      .map { it.trim() }
      .distinctUntilChanged()
      .debounce { query -> if (query.isEmpty()) 0L else 280L }
      .flatMapLatest { query ->
        if (query.isEmpty()) repository.observeConversations()
        else repository.searchConversations(query)
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
      )

  private val messagesFlow =
    _activeConversationId
      .flatMapLatest { convId ->
        if (convId == null) flowOf(emptyList())
        else repository.observeMessagesWithAttachments(convId)
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
      )

  private val enabledSkillsFlow =
    repository
      .observeSkills()
      .map { skills -> skills.filter { it.isEnabled } }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
      )

  private val composerMetaFlow =
    combine(
      generationCoordinator.generationMeta,
      _editingMessageId,
      _pendingAttachments,
      _pinnedSkillId,
    ) { genMeta, editingId, attachments, pinnedSkillId ->
      ComposerMeta(genMeta, editingId, attachments, pinnedSkillId)
    }

  private val engineMetaFlow =
    combine(preferencesFlow, hasActiveApiKeyFlow, enabledSkillsFlow) { prefs, hasKey, skills ->
      EngineMeta(prefs, hasKey, skills)
    }

  private val conversationListsFlow =
    combine(drawerConversationsFlow, allConversationsFlow) { drawer, all -> drawer to all }

  val uiState: StateFlow<ChatUiState> =
    combine(
        conversationListsFlow,
        _activeConversationId,
        messagesFlow,
        composerMetaFlow,
        engineMetaFlow,
      ) { convLists, activeId, messages, composer, engineMeta ->
        val (drawerConversations, allConversations) = convLists
        val activeConv = allConversations.firstOrNull { it.id == activeId }
        val genMeta = composer.generationMeta
        val validPinnedSkillId =
          composer.pinnedSkillId?.takeIf { id -> engineMeta.enabledSkills.any { it.id == id } }
        val activeStreamingId =
          if (genMeta.isGenerating && genMeta.conversationId == activeId) {
            genMeta.streamingMessageId
          } else {
            null
          }
        ChatUiState(
          conversations = drawerConversations,
          activeConversationId = activeId,
          activeTitle = activeConv?.title ?: "Ved",
          messages = messages,
          streamingMessageId = activeStreamingId,
          isGenerating = genMeta.isGenerating,
          activeProvider = engineMeta.prefs.activeProvider,
          activeModelId = engineMeta.prefs.activeModelId,
          hasActiveApiKey = engineMeta.hasKey,
          editingMessageId = composer.editingMessageId,
          pendingAttachments = composer.pendingAttachments,
          enabledSkills = engineMeta.enabledSkills,
          pinnedSkillId = validPinnedSkillId,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(),
      )

  fun onInputChanged(value: String) {
    _inputText.value = value
  }

  fun onSearchQueryChanged(query: String) {
    _drawerSearchQuery.value = query
  }

  fun clearSearch() {
    _drawerSearchQuery.value = ""
  }

  fun setPinnedSkill(skillId: String?) {
    _pinnedSkillId.value = if (_pinnedSkillId.value == skillId) null else skillId
  }

  fun startEditingMessage(messageWithAttachments: MessageWithAttachments) {
    val msg = messageWithAttachments.message
    if (uiState.value.isGenerating || msg.role != MessageEntity.ROLE_USER) return
    _editingMessageId.value = msg.id
    _inputText.value = msg.content
    _pendingAttachments.value =
      messageWithAttachments.attachments.map { att ->
        PendingAttachment(
          id = att.id,
          type = att.type,
          displayName = att.displayName,
          mimeType = att.mimeType,
          localPath = att.localPath,
          sizeBytes = att.sizeBytes,
        )
      }
  }

  fun cancelEditing() {
    _editingMessageId.value = null
    _inputText.value = ""
    _pendingAttachments.value = emptyList()
  }

  fun attachFiles(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      val prefs = repository.getCurrentPreferences()
      if (prefs.activeProvider != AiProvider.GEMINI) {
        _events.tryEmit(ChatUiEvent.ShowMessage("File attachments are only supported with Gemini"))
        return@launch
      }

      var currentList = _pendingAttachments.value
      var firstError: String? = null
      for (uri in uris) {
        when (val result = repository.prepareAttachment(uri, currentList)) {
          is AttachmentPrepareResult.Success -> {
            currentList = currentList + result.attachment
            _pendingAttachments.value = currentList
          }
          is AttachmentPrepareResult.Failure -> {
            if (firstError == null) {
              firstError = result.userMessage
            }
          }
        }
      }
      if (firstError != null) {
        _events.tryEmit(ChatUiEvent.ShowMessage(firstError!!))
      }
    }
  }

  fun removePendingAttachment(attachmentId: String) {
    _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == attachmentId }
  }

  fun notifyAttachmentsUnsupportedForOpenRouter() {
    _events.tryEmit(ChatUiEvent.ShowMessage("File attachments are only supported with Gemini"))
  }

  fun selectConversation(conversationId: String) {
    val genMeta = generationCoordinator.generationMeta.value
    if (genMeta.isGenerating && genMeta.conversationId != conversationId) {
      generationCoordinator.stopGeneration()
    }
    if (_activeConversationId.value != conversationId) {
      _editingMessageId.value = null
      _pendingAttachments.value = emptyList()
      _inputText.value = ""
    }
    _activeConversationId.value = conversationId
  }

  fun startNewChat() {
    if (generationCoordinator.generationMeta.value.isGenerating) {
      generationCoordinator.stopGeneration()
    }
    _editingMessageId.value = null
    _pendingAttachments.value = emptyList()
    _inputText.value = ""
    _activeConversationId.value = null
  }

  fun sendMessage(overridePrompt: String? = null) {
    val rawPrompt = (overridePrompt ?: _inputText.value).trim()
    val attachmentsToSend = if (overridePrompt == null) _pendingAttachments.value else emptyList()
    if ((rawPrompt.isEmpty() && attachmentsToSend.isEmpty()) ||
      generationCoordinator.generationMeta.value.isGenerating
    ) {
      return
    }

    val pendingEditId = if (overridePrompt == null) _editingMessageId.value else null
    val pinnedSkillId = _pinnedSkillId.value

    viewModelScope.launch {
      val prefs = repository.getCurrentPreferences()
      val provider = prefs.activeProvider
      val modelId = prefs.activeModelId
      val apiKey = repository.getEffectiveKey(provider)

      if (apiKey.isBlank()) {
        _events.tryEmit(
          ChatUiEvent.ShowMessage(
            "No ${provider.displayName} API key configured. Add one in Settings."
          )
        )
        return@launch
      }

      val hasBinaryAttachments = attachmentsToSend.any { it.type != AttachmentTypes.TEXT }
      if (hasBinaryAttachments && provider != AiProvider.GEMINI) {
        _events.tryEmit(ChatUiEvent.ShowMessage("File attachments are only supported with Gemini"))
        return@launch
      }

      if (overridePrompt == null) {
        _inputText.value = ""
        _pendingAttachments.value = emptyList()
        _editingMessageId.value = null
      }

      val titleSource =
        rawPrompt.ifBlank {
          attachmentsToSend.firstOrNull()?.displayName ?: "Attachment question"
        }

      val prepared =
        coroutineScope {
          val memoryDeferred = async(Dispatchers.IO) { repository.buildMemoryContextBlock() }
          val skillDeferred =
            async(Dispatchers.IO) {
              repository.resolveSkillContext(
                userPrompt = rawPrompt,
                pinnedSkillId = pinnedSkillId,
              )
            }
          val dbPrepDeferred =
            async(Dispatchers.IO) {
              val existingId = _activeConversationId.value
              if (existingId != null && pendingEditId != null) {
                val currentMsgs = repository.getMessagesSync(existingId)
                val targetMsg = currentMsgs.firstOrNull { it.id == pendingEditId }
                if (targetMsg != null) {
                  repository.deleteMessagesFrom(existingId, targetMsg.createdAt)
                }
              }

              val firstTurn = existingId == null || repository.getMessagesSync(existingId).isEmpty()
              val convTitle: String
              val convId =
                if (existingId != null) {
                  repository.updateConversationEngine(existingId, provider, modelId)
                  if (firstTurn) {
                    convTitle = buildPreliminaryTitle(titleSource)
                    repository.updateConversationTitle(existingId, convTitle)
                  } else {
                    convTitle = repository.getConversationTitle(existingId) ?: "Chat"
                  }
                  existingId
                } else {
                  convTitle = buildPreliminaryTitle(titleSource)
                  val created = repository.createConversation(provider, modelId, convTitle)
                  _activeConversationId.value = created.id
                  created.id
                }

              repository.insertUserMessage(
                conversationId = convId,
                content = rawPrompt,
                attachments = attachmentsToSend,
              )
              val withAttachments = repository.getMessagesWithAttachmentsSync(convId)
              Triple(convId, convTitle, Pair(firstTurn, withAttachments))
            }

          val (convId, convTitle, turnAndMessages) = dbPrepDeferred.await()
          val (firstTurn, withAttachments) = turnAndMessages
          val memoryContext = memoryDeferred.await()
          val skillContext = skillDeferred.await()

          GenerationRequest(
            conversationId = convId,
            conversationTitle = convTitle,
            provider = provider,
            modelId = modelId,
            history = withAttachments.map { it.message },
            attachmentsByMessageId = withAttachments.associate { it.message.id to it.attachments },
            systemPrompt = prefs.systemPrompt,
            memoryContext = memoryContext,
            skillsIndexBlock = skillContext.skillsIndexBlock,
            activeSkillBlock = skillContext.activeSkillBlock,
            activeSkillName = skillContext.activeSkillName,
            shouldAutoTitle = firstTurn,
            firstUserPrompt = titleSource,
          )
        }

      generationCoordinator.startGeneration(prepared)
    }
  }

  fun regenerateLastResponse() {
    if (generationCoordinator.generationMeta.value.isGenerating) return
    val conversationId = _activeConversationId.value ?: return
    val currentMessages = uiState.value.messages
    val lastAssistant = currentMessages.lastOrNull()?.message ?: return
    if (lastAssistant.role != MessageEntity.ROLE_ASSISTANT) return
    val pinnedSkillId = _pinnedSkillId.value

    viewModelScope.launch {
      val prefs = repository.getCurrentPreferences()
      val provider = prefs.activeProvider
      val modelId = prefs.activeModelId
      val apiKey = repository.getEffectiveKey(provider)

      if (apiKey.isBlank()) {
        _events.tryEmit(
          ChatUiEvent.ShowMessage(
            "No ${provider.displayName} API key configured. Add one in Settings."
          )
        )
        return@launch
      }

      val lastUserPromptInMemory =
        currentMessages.lastOrNull { it.message.role == MessageEntity.ROLE_USER }?.message?.content
          .orEmpty()

      val prepared =
        coroutineScope {
          val memoryDeferred = async(Dispatchers.IO) { repository.buildMemoryContextBlock() }
          val skillDeferred =
            async(Dispatchers.IO) {
              repository.resolveSkillContext(
                userPrompt = lastUserPromptInMemory,
                pinnedSkillId = pinnedSkillId,
              )
            }
          val dbPrepDeferred =
            async(Dispatchers.IO) {
              repository.deleteMessage(lastAssistant.id)
              repository.updateConversationEngine(conversationId, provider, modelId)
              val withAttachments = repository.getMessagesWithAttachmentsSync(conversationId)
              val title = repository.getConversationTitle(conversationId) ?: "Chat"
              withAttachments to title
            }

          val (withAttachments, conversationTitle) = dbPrepDeferred.await()
          val memoryContext = memoryDeferred.await()
          val skillContext = skillDeferred.await()
          val updatedHistory = withAttachments.map { it.message }
          val attachmentsMap = withAttachments.associate { it.message.id to it.attachments }
          val firstPrompt =
            updatedHistory.firstOrNull { it.role == MessageEntity.ROLE_USER }?.content
              ?.ifBlank { "Attachment question" }
              .orEmpty()

          GenerationRequest(
            conversationId = conversationId,
            conversationTitle = conversationTitle,
            provider = provider,
            modelId = modelId,
            history = updatedHistory,
            attachmentsByMessageId = attachmentsMap,
            systemPrompt = prefs.systemPrompt,
            memoryContext = memoryContext,
            skillsIndexBlock = skillContext.skillsIndexBlock,
            activeSkillBlock = skillContext.activeSkillBlock,
            activeSkillName = skillContext.activeSkillName,
            shouldAutoTitle = updatedHistory.count { it.role == MessageEntity.ROLE_USER } == 1,
            firstUserPrompt = firstPrompt,
          )
        }

      generationCoordinator.startGeneration(prepared)
    }
  }

  fun stopGeneration() {
    generationCoordinator.stopGeneration()
  }

  fun deleteConversation(conversationId: String) {
    viewModelScope.launch {
      val genMeta = generationCoordinator.generationMeta.value
      if (genMeta.isGenerating && genMeta.conversationId == conversationId) {
        generationCoordinator.stopGeneration()
      }
      val backup = repository.deleteConversationWithBackup(conversationId)
      if (_activeConversationId.value == conversationId) {
        _activeConversationId.value = null
        _editingMessageId.value = null
        _pendingAttachments.value = emptyList()
      }
      if (backup != null) {
        _events.tryEmit(ChatUiEvent.ConversationDeleted(backup))
      }
    }
  }

  fun restoreConversation(backup: DeletedConversationBackup) {
    viewModelScope.launch {
      repository.restoreConversation(backup)
      _activeConversationId.value = backup.conversation.id
    }
  }

  fun shareActiveConversation() {
    val convId = _activeConversationId.value ?: return
    viewModelScope.launch {
      val formatted = repository.formatConversationAsText(convId)
      if (formatted != null) {
        _events.tryEmit(
          ChatUiEvent.ShareText(text = formatted, title = uiState.value.activeTitle)
        )
      }
    }
  }

  fun copyActiveConversation() {
    val convId = _activeConversationId.value ?: return
    viewModelScope.launch {
      val formatted = repository.formatConversationAsText(convId)
      if (formatted != null) {
        _events.tryEmit(
          ChatUiEvent.CopyText(text = formatted, feedback = "Full conversation copied")
        )
      }
    }
  }

  private fun buildPreliminaryTitle(prompt: String): String {
    val cleaned = prompt.replace(WHITESPACE_REGEX, " ").trim()
    return if (cleaned.length <= 42) cleaned else cleaned.take(40).trimEnd() + "…"
  }

  private data class ComposerMeta(
    val generationMeta: com.example.service.GenerationMetaState,
    val editingMessageId: String?,
    val pendingAttachments: List<PendingAttachment>,
    val pinnedSkillId: String?,
  )

  private data class EngineMeta(
    val prefs: UserPreferences,
    val hasKey: Boolean,
    val enabledSkills: List<SkillEntity>,
  )

  private companion object {
    val WHITESPACE_REGEX = Regex("\\s+")
  }
}
