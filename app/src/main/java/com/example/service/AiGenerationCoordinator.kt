package com.example.service

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.example.data.local.entity.MemoryEntity
import com.example.data.local.entity.MessageAttachmentEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.model.AiProvider
import com.example.data.remote.StreamChunk
import com.example.data.repository.ChatRepository
import com.example.data.util.ExtractedMemory
import com.example.data.util.MemoryPromptHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
  val shouldAutoTitle: Boolean,
  val firstUserPrompt: String,
)

private sealed interface ThrottledStreamEvent {
  data class Progress(val accumulatedText: String) : ThrottledStreamEvent

  data class Completed(
    val finalText: String,
    val extractedMemories: List<ExtractedMemory>,
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

  @Volatile private var activeJob: Job? = null
  @Volatile private var pendingRunData: ActiveRunData? = null
  @Volatile private var latestPartialText: String = ""
  @Volatile private var serviceCallbacks: ServiceCallbacks? = null

  interface ServiceCallbacks {
    fun onCompleted(conversationId: String, conversationTitle: String, isError: Boolean)

    fun onStoppedOrCanceled()
  }

  private data class ActiveRunData(
    val request: GenerationRequest,
    val placeholderMessageId: String,
  )

  internal fun attachServiceCallbacks(callbacks: ServiceCallbacks?) {
    serviceCallbacks = callbacks
  }

  /**
   * Inserts the assistant placeholder, marks state as generating, and launches
   * [AiGenerationService] (with in-process fallback if the OS blocks FGS start).
   */
  suspend fun startGeneration(request: GenerationRequest) {
    cancelInternal(savePartial = false)

    val placeholder =
      repository.insertAssistantPlaceholder(
        conversationId = request.conversationId,
        modelId = request.modelId,
        activeSkillName = request.activeSkillName,
      )

    latestPartialText = ""
    _streamingText.value = ""
    _generationMeta.value =
      GenerationMetaState(
        isGenerating = true,
        conversationId = request.conversationId,
        streamingMessageId = placeholder.id,
      )
    pendingRunData = ActiveRunData(request = request, placeholderMessageId = placeholder.id)

    val serviceStarted =
      try {
        val intent =
          AiGenerationService.createStartIntent(
            context = appContext,
            conversationId = request.conversationId,
            conversationTitle = request.conversationTitle,
          )
        ContextCompat.startForegroundService(appContext, intent)
        true
      } catch (t: Throwable) {
        Log.w(TAG, "Foreground service unavailable; running generation directly in coordinator", t)
        false
      }

    if (!serviceStarted) {
      executeActiveRunIfNeeded()
    }
  }

  internal fun executeActiveRunIfNeeded() {
    val runData = pendingRunData ?: return
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
                    emit(
                      ThrottledStreamEvent.Progress(
                        MemoryPromptHelper.stripStreamingTags(snapshot)
                      )
                    )
                  }
                }
                is StreamChunk.Completed -> {
                  val parsed = MemoryPromptHelper.extractAndStripMemories(buffer.toString())
                  latestPartialText = parsed.cleanText
                  emit(
                    ThrottledStreamEvent.Completed(
                      finalText = parsed.cleanText,
                      extractedMemories = parsed.extractedMemories,
                      promptTokens = chunk.promptTokens,
                      completionTokens = chunk.completionTokens,
                    )
                  )
                }
                is StreamChunk.Error -> {
                  val partial = MemoryPromptHelper.stripStreamingTags(buffer.toString()).trim()
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

  private fun cancelInternal(savePartial: Boolean) {
    val currentMeta = _generationMeta.value
    val placeholderId = currentMeta.streamingMessageId
    val convId = currentMeta.conversationId
    val partial = MemoryPromptHelper.stripStreamingTags(latestPartialText).trim()

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

  private companion object {
    const val TAG = "AiGenerationCoordinator"
    const val STREAM_EMIT_INTERVAL_MS = 48L
  }
}
