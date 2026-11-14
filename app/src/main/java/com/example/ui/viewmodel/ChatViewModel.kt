package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.model.AiProvider
import com.example.data.preferences.UserPreferences
import com.example.data.remote.StreamChunk
import com.example.data.repository.ChatRepository
import com.example.data.repository.DeletedConversationBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * Stable structural UI state for [ChatScreen]. Deliberately excludes rapidly changing
 * [ChatViewModel.streamingText] so unrelated message items and chrome do not recompose per token.
 */
@Immutable
data class ChatUiState(
  val conversations: List<ConversationEntity> = emptyList(),
  val activeConversationId: String? = null,
  val activeTitle: String = "Ved",
  val messages: List<MessageEntity> = emptyList(),
  val streamingMessageId: String? = null,
  val isGenerating: Boolean = false,
  val activeProvider: AiProvider = AiProvider.GEMINI,
  val activeModelId: String = "gemini-2.5-flash",
  val hasActiveApiKey: Boolean = true,
)

sealed interface ChatUiEvent {
  data class ShowMessage(val message: String) : ChatUiEvent

  data class ConversationDeleted(val backup: DeletedConversationBackup) : ChatUiEvent

  data class ShareText(val text: String, val title: String) : ChatUiEvent

  data class CopyText(val text: String, val feedback: String) : ChatUiEvent
}

private sealed interface ThrottledStreamEvent {
  data class Progress(val accumulatedText: String) : ThrottledStreamEvent

  data class Completed(val finalText: String) : ThrottledStreamEvent

  data class Error(val partialText: String, val message: String) : ThrottledStreamEvent
}

/**
 * ViewModel driving the primary chat experience, drawer history, isolated streaming state,
 * stop/regenerate, and auto-titling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val repository: ChatRepository) : ViewModel() {

  private val _activeConversationId = MutableStateFlow<String?>(null)
  private val _streamingMessageId = MutableStateFlow<String?>(null)

  /**
   * Dedicated StateFlow for active streaming text so ONLY the single streaming message item
   * observes and recomposes on streamed updates.
   */
  private val _streamingText = MutableStateFlow("")
  val streamingText: StateFlow<String> = _streamingText.asStateFlow()

  private val _isGenerating = MutableStateFlow(false)
  private val _inputText = MutableStateFlow("")
  val inputText: StateFlow<String> = _inputText.asStateFlow()

  private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 8)
  val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

  private var generationJob: Job? = null
  private var activeStreamingConversationId: String? = null
  @Volatile private var latestPartialText: String = ""

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

  private val conversationsFlow =
    repository
      .observeConversations()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
      )

  private val messagesFlow =
    _activeConversationId
      .flatMapLatest { convId ->
        if (convId == null) flowOf(emptyList()) else repository.observeMessages(convId)
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
      )

  private val generationStatusFlow =
    combine(_streamingMessageId, _isGenerating) { msgId, generating -> msgId to generating }

  private val engineMetaFlow =
    combine(preferencesFlow, hasActiveApiKeyFlow) { prefs, hasKey -> prefs to hasKey }

  val uiState: StateFlow<ChatUiState> =
    combine(
        conversationsFlow,
        _activeConversationId,
        messagesFlow,
        generationStatusFlow,
        engineMetaFlow,
      ) { conversations, activeId, messages, genStatus, engineMeta ->
        val (prefs, hasKey) = engineMeta
        val activeConv = conversations.firstOrNull { it.id == activeId }
        ChatUiState(
          conversations = conversations,
          activeConversationId = activeId,
          activeTitle = activeConv?.title ?: "Ved",
          messages = messages,
          streamingMessageId = genStatus.first,
          isGenerating = genStatus.second,
          activeProvider = prefs.activeProvider,
          activeModelId = prefs.activeModelId,
          hasActiveApiKey = hasKey,
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

  fun selectConversation(conversationId: String) {
    if (_isGenerating.value && activeStreamingConversationId != conversationId) {
      stopGeneration()
    }
    _activeConversationId.value = conversationId
  }

  fun startNewChat() {
    if (_isGenerating.value) {
      stopGeneration()
    }
    _activeConversationId.value = null
  }

  fun sendMessage(overridePrompt: String? = null) {
    val rawPrompt = (overridePrompt ?: _inputText.value).trim()
    if (rawPrompt.isEmpty() || _isGenerating.value) return

    if (overridePrompt == null) {
      _inputText.value = ""
    }

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

      val existingId = _activeConversationId.value
      val isFirstTurn = existingId == null || uiState.value.messages.isEmpty()

      val conversationId =
        if (existingId != null) {
          repository.updateConversationEngine(existingId, provider, modelId)
          existingId
        } else {
          val preliminaryTitle = buildPreliminaryTitle(rawPrompt)
          val created = repository.createConversation(provider, modelId, preliminaryTitle)
          _activeConversationId.value = created.id
          created.id
        }

      if (isFirstTurn && existingId != null) {
        repository.updateConversationTitle(conversationId, buildPreliminaryTitle(rawPrompt))
      }

      repository.insertUserMessage(conversationId, rawPrompt)
      val history = repository.getMessagesSync(conversationId)
      startStreamingTurn(
        conversationId = conversationId,
        provider = provider,
        modelId = modelId,
        apiKey = apiKey,
        history = history,
        shouldAutoTitle = isFirstTurn,
        firstUserPrompt = rawPrompt,
      )
    }
  }

  fun regenerateLastResponse() {
    if (_isGenerating.value) return
    val conversationId = _activeConversationId.value ?: return
    val currentMessages = uiState.value.messages
    val lastAssistant = currentMessages.lastOrNull() ?: return
    if (lastAssistant.role != MessageEntity.ROLE_ASSISTANT) return

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

      repository.deleteMessage(lastAssistant.id)
      repository.updateConversationEngine(conversationId, provider, modelId)
      val updatedHistory = repository.getMessagesSync(conversationId)
      val firstPrompt =
        updatedHistory.firstOrNull { it.role == MessageEntity.ROLE_USER }?.content.orEmpty()

      startStreamingTurn(
        conversationId = conversationId,
        provider = provider,
        modelId = modelId,
        apiKey = apiKey,
        history = updatedHistory,
        shouldAutoTitle = updatedHistory.count { it.role == MessageEntity.ROLE_USER } == 1,
        firstUserPrompt = firstPrompt,
      )
    }
  }

  fun stopGeneration() {
    val job = generationJob ?: return
    val placeholderId = _streamingMessageId.value
    val convId = activeStreamingConversationId
    val partial = latestPartialText.trim()

    job.cancel()
    generationJob = null
    latestPartialText = ""
    _isGenerating.value = false
    _streamingMessageId.value = null
    _streamingText.value = ""

    if (placeholderId != null && convId != null) {
      viewModelScope.launch {
        if (partial.isNotEmpty()) {
          repository.updateAssistantMessage(placeholderId, convId, partial, isError = false)
        } else {
          repository.deleteMessage(placeholderId)
        }
      }
    }
  }

  fun deleteConversation(conversationId: String) {
    viewModelScope.launch {
      if (_isGenerating.value && activeStreamingConversationId == conversationId) {
        stopGeneration()
      }
      val backup = repository.deleteConversationWithBackup(conversationId)
      if (_activeConversationId.value == conversationId) {
        _activeConversationId.value = null
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

  private fun startStreamingTurn(
    conversationId: String,
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
    shouldAutoTitle: Boolean,
    firstUserPrompt: String,
  ) {
    generationJob?.cancel()
    latestPartialText = ""
    activeStreamingConversationId = conversationId
    _isGenerating.value = true
    _streamingText.value = ""

    generationJob =
      viewModelScope.launch {
        val placeholder = repository.insertAssistantPlaceholder(conversationId, modelId)
        _streamingMessageId.value = placeholder.id

        try {
          // Accumulate tokens and throttle snapshots on Dispatchers.Default so the main thread
          // is never woken up for individual network SSE deltas.
          val buffer = StringBuilder(512)
          var lastEmitTimeMs = 0L

          repository
            .streamResponse(provider, modelId, apiKey, history)
            .transform { chunk ->
              when (chunk) {
                is StreamChunk.Delta -> {
                  buffer.append(chunk.text)
                  val now = System.currentTimeMillis()
                  if (now - lastEmitTimeMs >= STREAM_EMIT_INTERVAL_MS) {
                    val snapshot = buffer.toString()
                    latestPartialText = snapshot
                    lastEmitTimeMs = now
                    emit(ThrottledStreamEvent.Progress(snapshot))
                  }
                }
                is StreamChunk.Completed -> {
                  val finalText = buffer.toString().trim()
                  latestPartialText = finalText
                  emit(ThrottledStreamEvent.Completed(finalText))
                }
                is StreamChunk.Error -> {
                  val partial = buffer.toString().trim()
                  latestPartialText = partial
                  emit(ThrottledStreamEvent.Error(partial, chunk.message))
                }
              }
            }
            .flowOn(Dispatchers.Default)
            .collect { event ->
              when (event) {
                is ThrottledStreamEvent.Progress -> {
                  _streamingText.value = event.accumulatedText
                }
                is ThrottledStreamEvent.Completed -> {
                  val finalText = event.finalText
                  _streamingText.value = finalText
                  if (finalText.isNotEmpty()) {
                    repository.updateAssistantMessage(
                      messageId = placeholder.id,
                      conversationId = conversationId,
                      content = finalText,
                      isError = false,
                    )
                    if (shouldAutoTitle && firstUserPrompt.isNotBlank()) {
                      launchAutoTitle(
                        conversationId = conversationId,
                        provider = provider,
                        modelId = modelId,
                        apiKey = apiKey,
                        userPrompt = firstUserPrompt,
                        assistantReply = finalText,
                      )
                    }
                  } else {
                    repository.updateAssistantMessage(
                      messageId = placeholder.id,
                      conversationId = conversationId,
                      content = "No response was returned by ${provider.displayName}.",
                      isError = true,
                    )
                  }
                }
                is ThrottledStreamEvent.Error -> {
                  val textToSave =
                    if (event.partialText.isNotEmpty()) {
                      "${event.partialText}\n\n_${event.message}_"
                    } else {
                      event.message
                    }
                  repository.updateAssistantMessage(
                    messageId = placeholder.id,
                    conversationId = conversationId,
                    content = textToSave,
                    isError = event.partialText.isEmpty(),
                  )
                }
              }
            }
        } catch (ce: CancellationException) {
          throw ce
        } catch (e: Exception) {
          repository.updateAssistantMessage(
            messageId = placeholder.id,
            conversationId = conversationId,
            content = e.message ?: "Unexpected streaming error.",
            isError = true,
          )
        } finally {
          latestPartialText = ""
          _isGenerating.value = false
          _streamingMessageId.value = null
          _streamingText.value = ""
          activeStreamingConversationId = null
        }
      }
  }

  private fun launchAutoTitle(
    conversationId: String,
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    userPrompt: String,
    assistantReply: String,
  ) {
    viewModelScope.launch {
      val generated =
        repository.generateTitle(
          provider = provider,
          modelId = modelId,
          apiKey = apiKey,
          userPrompt = userPrompt,
          assistantReply = assistantReply,
        )
      if (!generated.isNullOrBlank()) {
        repository.updateConversationTitle(conversationId, generated)
      }
    }
  }

  private fun buildPreliminaryTitle(prompt: String): String {
    val cleaned = prompt.replace(WHITESPACE_REGEX, " ").trim()
    return if (cleaned.length <= 42) cleaned else cleaned.take(40).trimEnd() + "…"
  }

  private companion object {
    const val STREAM_EMIT_INTERVAL_MS = 48L
    val WHITESPACE_REGEX = Regex("\\s+")
  }
}
