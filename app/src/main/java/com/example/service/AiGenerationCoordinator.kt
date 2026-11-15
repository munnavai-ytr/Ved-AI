package com.example.service

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.example.data.local.entity.MemoryEntity
import com.example.data.local.entity.MessageAttachmentEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.SkillEntity
import com.example.data.model.AiProvider
import com.example.data.remote.StreamChunk
import com.example.data.repository.ChatRepository
import com.example.data.util.ExtractedMemory
import com.example.data.util.MemoryPromptHelper
import com.example.data.util.SkillCreateParseResult
import com.example.data.util.SkillCreatorHelper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

@Immutable
data class GenerationMetaState(
  val isGenerating: Boolean = false,
  val conversationId: String? = null,
  val streamingMessageId: String? = null,
)

@Immutable
data class SuggestedRepliesState(
  val conversationId: String? = null,
  val messageId: String? = null,
  val replies: List<String> = emptyList(),
)

@Immutable
data class CreatedSkillBannerState(
  val conversationId: String,
  val messageId: String,
  val skillId: String,
  val skillName: String,
)

data class GenerationRequest(
  val conversationId: String,
  val conversationTitle: String,
  val provider: AiProvider,
  val modelId: String,
  val history: List<MessageEntity>,
  val attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>>,
  val systemPrompt: String,
  val memoryContext: String,
  val skillsIndexBlock: String,
  val activeSkillBlock: String,
  val activeSkillName: String?,
  val activeSkillId: String? = null,
  val shouldAutoTitle: Boolean,
  val firstUserPrompt: String,
  val includeSuggestedReplies: Boolean = true,
)

private sealed interface ThrottledStreamEvent {
  data class Progress(val accumulatedText: String) : ThrottledStreamEvent

  data class Completed(
    val finalText: String,
    val extractedMemories: List<ExtractedMemory>,
    val skillCreateResult: SkillCreateParseResult,
    val promptTokens: Int?,
    val completionTokens: Int?,
  ) : ThrottledStreamEvent

  data class Error(val partialText: String, val message: String) : ThrottledStreamEvent
}

/**
 * Application-scoped coordinator owning background AI streaming state and lifecycle so responses
 * keep streaming cleanly if the user backgrounds the app or locks the screen, while still driving
 * 48ms-throttled foreground UI updates via [streamingText].
 *
 * Security note: API keys are NEVER passed through Intents; they are resolved lazily inside the
 * app process from encrypted storage via [ChatRepository.getEffectiveKey].
 */
class AiGenerationCoordinator(
  private val appContext: Context,
  private val repository: ChatRepository,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _generationMeta = MutableStateFlow(GenerationMetaState())
  val generationMeta: StateFlow<GenerationMetaState> = _generationMeta.asStateFlow()

  private val _streamingText = MutableStateFlow("")
  val streamingText: StateFlow<String> = _streamingText.asStateFlow()

  private val _suggestedReplies = MutableStateFlow(SuggestedRepliesState())
  val suggestedReplies: StateFlow<SuggestedRepliesState> = _suggestedReplies.asStateFlow()

  private val _createdSkillBanner = MutableStateFlow<CreatedSkillBannerState?>(null)
  val createdSkillBanner: StateFlow<CreatedSkillBannerState?> = _createdSkillBanner.asStateFlow()

  private val _skillCreationError = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val skillCreationError: SharedFlow<String> = _skillCreationError.asSharedFlow()

  @Volatile private var activeJob: Job? = null
  @Volatile private var pendingRunData: ActiveRunData? = null
  @Volatile private var latestPartialText: String = ""
  @Volatile private var serviceCallbacks: ServiceCallbacks? = null
  @Volatile private var latestRunToken: Long = 0L

  interface ServiceCallbacks {
    fun onCompleted(conversationId: String, conversationTitle: String, isError: Boolean)

    fun onStoppedOrCanceled()
  }

  private data class ActiveRunData(
    val request: GenerationRequest,
    val placeholderMessageId: String,
    val started: AtomicBoolean = AtomicBoolean(false),
  )

  internal fun attachServiceCallbacks(callbacks: ServiceCallbacks?) {
    serviceCallbacks = callbacks
  }

  /**
   * Inserts the assistant placeholder, marks state as generating, immediately starts the HTTP
   * streaming run so connection establishment does not wait on Service IPC, and starts
   * [AiGenerationService] for background persistence.
   */
  suspend fun startGeneration(request: GenerationRequest) {
    cancelInternal(savePartial = false)
    clearSuggestedReplies()

    val placeholder =
      repository.insertAssistantPlaceholder(
        conversationId = request.conversationId,
        modelId = request.modelId,
        activeSkillName = request.activeSkillName,
      )

    val runToken = System.nanoTime()
    latestRunToken = runToken
    latestPartialText = ""
    _streamingText.value = ""
    _generationMeta.value =
      GenerationMetaState(
        isGenerating = true,
        conversationId = request.conversationId,
        streamingMessageId = placeholder.id,
      )
    pendingRunData = ActiveRunData(request = request, placeholderMessageId = placeholder.id)

    // Begin socket connection immediately without waiting for Service.onStartCommand IPC latency.
    executeActiveRunIfNeeded()

    try {
      val intent =
        AiGenerationService.createStartIntent(
          context = appContext,
          conversationId = request.conversationId,
          conversationTitle = request.conversationTitle,
        )
      ContextCompat.startForegroundService(appContext, intent)
    } catch (t: Throwable) {
      Log.w(TAG, "Foreground service unavailable; running generation directly in coordinator", t)
    }
  }

  internal fun executeActiveRunIfNeeded() {
    val runData = pendingRunData ?: return
    if (!runData.started.compareAndSet(false, true)) return
    if (activeJob?.isActive == true) return
    pendingRunData = null

    val req = runData.request
    val placeholderId = runData.placeholderMessageId

    activeJob =
      scope.launch {
        var finishedWithError = false
        var finishedNaturally = false

        try {
          val apiKey = repository.getEffectiveKey(req.provider)
          if (apiKey.isBlank()) {
            finishedWithError = true
            finishedNaturally = true
            repository.updateAssistantMessage(
              messageId = placeholderId,
              conversationId = req.conversationId,
              content = "No ${req.provider.displayName} API key configured. Add one in Settings.",
              isError = true,
              activeSkillName = req.activeSkillName,
            )
            return@launch
          }

          val buffer = StringBuilder(512)
          var lastEmitTimeMs = 0L
          val isSkillCreatorActive =
            req.activeSkillId == SkillEntity.SYSTEM_SKILL_CREATOR_ID ||
              req.activeSkillName == SkillCreatorHelper.SYSTEM_SKILL_CREATOR_NAME

          repository
            .streamResponse(
              provider = req.provider,
              modelId = req.modelId,
              apiKey = apiKey,
              history = req.history,
              systemPrompt = req.systemPrompt,
              memoryContext = req.memoryContext,
              skillsIndexBlock = req.skillsIndexBlock,
              activeSkillBlock = req.activeSkillBlock,
              attachmentsByMessageId = req.attachmentsByMessageId,
            )
            .transform { chunk ->
              when (chunk) {
                is StreamChunk.Delta -> {
                  buffer.append(chunk.text)
                  val now = System.currentTimeMillis()
                  if (now - lastEmitTimeMs >= STREAM_EMIT_INTERVAL_MS) {
                    val snapshot = buffer.toString()
                    latestPartialText = snapshot
                    lastEmitTimeMs = now
                    val visibleText =
                      if (isSkillCreatorActive) {
                        SkillCreatorHelper.stripStreamingSkillCreatorTags(
                          MemoryPromptHelper.stripStreamingTags(snapshot)
                        )
                      } else {
                        MemoryPromptHelper.stripStreamingTags(snapshot)
                      }
                    emit(ThrottledStreamEvent.Progress(visibleText))
                  }
                }
                is StreamChunk.Completed -> {
                  val parsedMemories = MemoryPromptHelper.extractAndStripMemories(buffer.toString())
                  val skillCreateResult =
                    if (isSkillCreatorActive || parsedMemories.cleanText.contains("[SKILL_CREATE]")) {
                      SkillCreatorHelper.extractAndStripSkillCreate(parsedMemories.cleanText)
                    } else {
                      SkillCreateParseResult.NotPresent(parsedMemories.cleanText)
                    }
                  val finalCleanText =
                    when (skillCreateResult) {
                      is SkillCreateParseResult.NotPresent -> skillCreateResult.cleanText
                      is SkillCreateParseResult.Created -> skillCreateResult.cleanText
                      is SkillCreateParseResult.Malformed -> skillCreateResult.cleanText
                    }
                  latestPartialText = finalCleanText
                  emit(
                    ThrottledStreamEvent.Completed(
                      finalText = finalCleanText,
                      extractedMemories = parsedMemories.extractedMemories,
                      skillCreateResult = skillCreateResult,
                      promptTokens = chunk.promptTokens,
                      completionTokens = chunk.completionTokens,
                    )
                  )
                }
                is StreamChunk.Error -> {
                  val strippedMem = MemoryPromptHelper.stripStreamingTags(buffer.toString())
                  val partial =
                    if (isSkillCreatorActive) {
                      SkillCreatorHelper.stripStreamingSkillCreatorTags(strippedMem).trim()
                    } else {
                      strippedMem.trim()
                    }
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
                  var createdSkillSavedName: String? = null
                  var skillCreationFailed = false

                  when (val skillRes = event.skillCreateResult) {
                    is SkillCreateParseResult.Created -> {
                      val savedResult =
                        runCatching {
                          repository.createSkillFromConversation(
                            name = skillRes.name,
                            description = skillRes.description,
                            skillMarkdown = skillRes.skillMarkdown,
                            files = skillRes.files,
                          )
                        }
                      savedResult
                        .onSuccess { createdSkill ->
                          createdSkillSavedName = createdSkill.name
                          _createdSkillBanner.value =
                            CreatedSkillBannerState(
                              conversationId = req.conversationId,
                              messageId = placeholderId,
                              skillId = createdSkill.id,
                              skillName = createdSkill.name,
                            )
                        }
                        .onFailure {
                          skillCreationFailed = true
                          _skillCreationError.tryEmit(SKILL_CREATION_FALLBACK_ERROR)
                        }
                    }
                    is SkillCreateParseResult.Malformed -> {
                      skillCreationFailed = true
                      _skillCreationError.tryEmit(SKILL_CREATION_FALLBACK_ERROR)
                    }
                    is SkillCreateParseResult.NotPresent -> Unit
                  }

                  val finalText =
                    when {
                      event.finalText.isNotEmpty() -> event.finalText
                      createdSkillSavedName != null -> "Saved skill \"$createdSkillSavedName\"."
                      skillCreationFailed ->
                        "I couldn't save the skill automatically, but you can still create it manually in Settings → Skills."
                      else -> ""
                    }
                  _streamingText.value = finalText

                  if (finalText.isNotEmpty()) {
                    repository.updateAssistantMessage(
                      messageId = placeholderId,
                      conversationId = req.conversationId,
                      content = finalText,
                      isError = false,
                      promptTokens = event.promptTokens,
                      completionTokens = event.completionTokens,
                      activeSkillName = req.activeSkillName,
                    )
                    if (req.shouldAutoTitle && req.firstUserPrompt.isNotBlank()) {
                      launchAutoTitle(
                        conversationId = req.conversationId,
                        provider = req.provider,
                        modelId = req.modelId,
                        apiKey = apiKey,
                        userPrompt = req.firstUserPrompt,
                        assistantReply = finalText,
                        systemPrompt = req.systemPrompt,
                      )
                    }
                    if (req.includeSuggestedReplies) {
                      launchSuggestedReplies(
                        runToken = latestRunToken,
                        conversationId = req.conversationId,
                        messageId = placeholderId,
                        provider = req.provider,
                        modelId = req.modelId,
                        apiKey = apiKey,
                        historyWithReply =
                          req.history +
                            MessageEntity(
                              id = placeholderId,
                              conversationId = req.conversationId,
                              role = MessageEntity.ROLE_ASSISTANT,
                              content = finalText,
                              modelId = req.modelId,
                              createdAt = System.currentTimeMillis(),
                            ),
                      )
                    }
                  } else if (event.extractedMemories.isNotEmpty()) {
                    repository.updateAssistantMessage(
                      messageId = placeholderId,
                      conversationId = req.conversationId,
                      content = "Remembered.",
                      isError = false,
                      promptTokens = event.promptTokens,
                      completionTokens = event.completionTokens,
                      activeSkillName = req.activeSkillName,
                    )
                  } else {
                    finishedWithError = true
                    repository.updateAssistantMessage(
                      messageId = placeholderId,
                      conversationId = req.conversationId,
                      content = "No response was returned by ${req.provider.displayName}.",
                      isError = true,
                      activeSkillName = req.activeSkillName,
                    )
                  }

                  if (event.extractedMemories.isNotEmpty()) {
                    for (mem in event.extractedMemories) {
                      repository.addMemory(
                        content = mem.content,
                        category = mem.category,
                        source = MemoryEntity.SOURCE_AI,
                      )
                    }
                  }
                  finishedNaturally = true
                }
                is ThrottledStreamEvent.Error -> {
                  finishedWithError = event.partialText.isEmpty()
                  val textToSave =
                    if (event.partialText.isNotEmpty()) {
                      "${event.partialText}\n\n_${event.message}_"
                    } else {
                      event.message
                    }
                  repository.updateAssistantMessage(
                    messageId = placeholderId,
                    conversationId = req.conversationId,
                    content = textToSave,
                    isError = event.partialText.isEmpty(),
                    activeSkillName = req.activeSkillName,
                  )
                  finishedNaturally = true
                }
              }
            }
        } catch (ce: CancellationException) {
          throw ce
        } catch (e: Exception) {
          finishedWithError = true
          finishedNaturally = true
          repository.updateAssistantMessage(
            messageId = placeholderId,
            conversationId = req.conversationId,
            content = e.message ?: "Unexpected streaming error.",
            isError = true,
            activeSkillName = req.activeSkillName,
          )
        } finally {
          latestPartialText = ""
          _streamingText.value = ""
          _generationMeta.value = GenerationMetaState()

          val latestTitle =
            runCatching { repository.getConversationTitle(req.conversationId) }.getOrNull()
              ?: req.conversationTitle

          if (finishedNaturally) {
            serviceCallbacks?.onCompleted(
              conversationId = req.conversationId,
              conversationTitle = latestTitle,
              isError = finishedWithError,
            )
          } else {
            serviceCallbacks?.onStoppedOrCanceled()
          }
        }
      }
  }

  fun stopGeneration() {
    cancelInternal(savePartial = true)
  }

  fun clearSuggestedReplies() {
    _suggestedReplies.value = SuggestedRepliesState()
  }

  fun clearCreatedSkillBanner() {
    _createdSkillBanner.value = null
  }

  private fun cancelInternal(savePartial: Boolean) {
    val currentMeta = _generationMeta.value
    val placeholderId = currentMeta.streamingMessageId
    val convId = currentMeta.conversationId
    val partial =
      SkillCreatorHelper.stripStreamingSkillCreatorTags(
        MemoryPromptHelper.stripStreamingTags(latestPartialText)
      ).trim()

    latestRunToken = System.nanoTime()
    pendingRunData = null
    activeJob?.cancel()
    activeJob = null
    latestPartialText = ""
    _streamingText.value = ""
    _generationMeta.value = GenerationMetaState()

    if (savePartial && placeholderId != null && convId != null) {
      scope.launch {
        if (partial.isNotEmpty()) {
          repository.updateAssistantMessage(placeholderId, convId, partial, isError = false)
        } else {
          repository.deleteMessage(placeholderId)
        }
      }
    }
    serviceCallbacks?.onStoppedOrCanceled()
  }

  private fun launchAutoTitle(
    conversationId: String,
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    userPrompt: String,
    assistantReply: String,
    systemPrompt: String,
  ) {
    scope.launch {
      val generated =
        repository.generateTitle(
          provider = provider,
          modelId = modelId,
          apiKey = apiKey,
          userPrompt = userPrompt,
          assistantReply = assistantReply,
          systemPrompt = systemPrompt,
        )
      if (!generated.isNullOrBlank()) {
        repository.updateConversationTitle(conversationId, generated)
      }
    }
  }

  private fun launchSuggestedReplies(
    runToken: Long,
    conversationId: String,
    messageId: String,
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    historyWithReply: List<MessageEntity>,
  ) {
    scope.launch {
      val suggestions =
        repository.generateSuggestedReplies(
          provider = provider,
          modelId = modelId,
          apiKey = apiKey,
          history = historyWithReply,
        )
      if (suggestions.isNotEmpty() && latestRunToken == runToken) {
        _suggestedReplies.value =
          SuggestedRepliesState(
            conversationId = conversationId,
            messageId = messageId,
            replies = suggestions,
          )
      }
    }
  }

  private companion object {
    const val TAG = "AiGenerationCoordinator"
    const val STREAM_EMIT_INTERVAL_MS = 32L
    const val SKILL_CREATION_FALLBACK_ERROR =
      "Couldn't save the skill automatically, you can still create it manually in Settings → Skills"
  }
}
