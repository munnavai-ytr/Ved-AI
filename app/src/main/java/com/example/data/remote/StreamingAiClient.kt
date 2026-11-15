package com.example.data.remote

import com.example.data.local.entity.MessageAttachmentEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.model.AiProvider
import com.example.data.util.AttachmentProcessor
import com.example.data.util.AttachmentTypes
import com.example.data.util.MemoryPromptHelper
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Events emitted while streaming a chat completion from Gemini or OpenRouter.
 */
sealed interface StreamChunk {
  data class Delta(val text: String) : StreamChunk

  data class Completed(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
  ) : StreamChunk

  data class Error(val message: String) : StreamChunk
}

/**
 * Low-overhead Server-Sent Events (SSE) client for Gemini and OpenRouter streaming chat APIs.
 */
class StreamingAiClient(private val okHttpClient: OkHttpClient) {

  fun streamChat(
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
    systemPrompt: String = "",
    memoryContext: String = "",
    skillsIndexBlock: String = "",
    activeSkillBlock: String = "",
    imageBase64ByUri: Map<String, String> = emptyMap(),
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
    binaryBase64ByPath: Map<String, String> = emptyMap(),
    textContentByPath: Map<String, String> = emptyMap(),
  ): Flow<StreamChunk> = callbackFlow {
    var activeCall: okhttp3.Call? = null

    val job =
      launch(Dispatchers.IO) {
        try {
          val request =
            when (provider) {
              AiProvider.GEMINI ->
                buildGeminiRequest(
                  modelId = modelId,
                  apiKey = apiKey,
                  history = history,
                  stream = true,
                  systemPrompt = systemPrompt,
                  memoryContext = memoryContext,
                  skillsIndexBlock = skillsIndexBlock,
                  activeSkillBlock = activeSkillBlock,
                  includeAccuracyAndMemory = true,
                  imageBase64ByUri = imageBase64ByUri,
                  attachmentsByMessageId = attachmentsByMessageId,
                  binaryBase64ByPath = binaryBase64ByPath,
                  textContentByPath = textContentByPath,
                )
              AiProvider.OPENROUTER ->
                buildOpenRouterRequest(
                  modelId = modelId,
                  apiKey = apiKey,
                  history = history,
                  stream = true,
                  systemPrompt = systemPrompt,
                  memoryContext = memoryContext,
                  skillsIndexBlock = skillsIndexBlock,
                  activeSkillBlock = activeSkillBlock,
                  includeAccuracyAndMemory = true,
                  attachmentsByMessageId = attachmentsByMessageId,
                  textContentByPath = textContentByPath,
                )
            }

          val call = okHttpClient.newCall(request)
          activeCall = call

          call.execute().use { response ->
            if (!response.isSuccessful) {
              val errorBody = response.body?.string().orEmpty()
              trySend(StreamChunk.Error(parseApiError(response.code, errorBody, provider)))
              close()
              return@launch
            }

            val source = response.body?.source()
            if (source == null) {
              trySend(StreamChunk.Error("Empty response stream from ${provider.displayName}."))
              close()
              return@launch
            }

            var latestPromptTokens: Int? = null
            var latestCompletionTokens: Int? = null

            while (!source.exhausted()) {
              val line = source.readUtf8Line() ?: break
              if (line.isBlank() || line.startsWith(":")) continue

              if (line.startsWith("data:")) {
                val payload = line.substring(5).trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break

                val parsed =
                  when (provider) {
                    AiProvider.GEMINI -> parseGeminiSsePayload(payload)
                    AiProvider.OPENROUTER -> parseOpenRouterSsePayload(payload)
                  }

                if (parsed.error != null) {
                  trySend(StreamChunk.Error(parsed.error))
                  close()
                  return@launch
                }
                if (parsed.promptTokens != null) {
                  latestPromptTokens = parsed.promptTokens
                }
                if (parsed.completionTokens != null) {
                  latestCompletionTokens = parsed.completionTokens
                }
                if (!parsed.text.isNullOrEmpty()) {
                  trySend(StreamChunk.Delta(parsed.text))
                }
              }
            }

            trySend(
              StreamChunk.Completed(
                promptTokens = latestPromptTokens,
                completionTokens = latestCompletionTokens,
              )
            )
            close()
          }
        } catch (e: IOException) {
          if (activeCall?.isCanceled() != true) {
            trySend(
              StreamChunk.Error(e.message ?: "Network error while contacting ${provider.displayName}.")
            )
          }
          close()
        } catch (e: Exception) {
          trySend(StreamChunk.Error(e.message ?: "Unexpected error while streaming response."))
          close()
        }
      }

    awaitClose {
      activeCall?.cancel()
      job.cancel()
    }
  }

  /**
   * Generates a concise 3-5 word conversation title using a single non-streaming call.
   * Returns null silently on any failure so callers can fall back cleanly.
   */
  suspend fun generateTitle(
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    userPrompt: String,
    assistantReply: String,
    systemPrompt: String = "",
  ): String? =
    withContext(Dispatchers.IO) {
      if (apiKey.isBlank()) return@withContext null

      val titlePrompt =
        "Summarize the following conversation topic as a concise 3 to 5 word title. " +
          "Output only the title with no quotes or trailing punctuation.\n\n" +
          "User: ${userPrompt.take(300)}\nAssistant: ${assistantReply.take(300)}"

      val syntheticHistory =
        listOf(
          MessageEntity(
            id = "title_prompt",
            conversationId = "title",
            role = MessageEntity.ROLE_USER,
            content = titlePrompt,
            createdAt = 0L,
          )
        )

      val request =
        when (provider) {
          AiProvider.GEMINI ->
            buildGeminiRequest(
              modelId = modelId,
              apiKey = apiKey,
              history = syntheticHistory,
              stream = false,
              systemPrompt = systemPrompt,
              includeAccuracyAndMemory = false,
            )
          AiProvider.OPENROUTER ->
            buildOpenRouterRequest(
              modelId = modelId,
              apiKey = apiKey,
              history = syntheticHistory,
              stream = false,
              systemPrompt = systemPrompt,
              includeAccuracyAndMemory = false,
            )
        }

      runCatching {
          okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val raw = response.body?.string().orEmpty()
            val extracted =
              when (provider) {
                AiProvider.GEMINI -> parseGeminiSsePayload(raw).text
                AiProvider.OPENROUTER -> {
                  val root = JSONObject(raw)
                  root
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                }
              }
            extracted
              ?.trim()
              ?.trim('"', '\'', '.', ':')
              ?.replace(WHITESPACE_REGEX, " ")
              ?.take(52)
              ?.takeIf { it.isNotBlank() }
          }
        }
        .getOrNull()
    }

  /**
   * Generates 2-3 concise follow-up questions/replies after an assistant response completes,
   * failing silently with an empty list so it never delays or blocks primary streaming output.
   */
  suspend fun generateSuggestedReplies(
    provider: AiProvider,
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
  ): List<String> =
    withContext(Dispatchers.IO) {
      if (apiKey.isBlank() || history.isEmpty()) return@withContext emptyList()

      val snippet =
        history
          .filter { !it.isError && it.content.isNotBlank() }
          .takeLast(4)
          .joinToString("\n\n") { msg ->
            val label = if (msg.role == MessageEntity.ROLE_USER) "User" else "Assistant"
            "$label: ${msg.content.trim().take(450)}"
          }
      if (snippet.isBlank()) return@withContext emptyList()

      val prompt =
        "Based on this conversation, suggest 2 to 3 short, helpful follow-up questions or replies " +
          "the user might tap next. Output ONLY the 2 to 3 suggestions, one per line, under 60 " +
          "characters each, with no numbering, bullets, quotes, or extra commentary.\n\n$snippet"

      val syntheticHistory =
        listOf(
          MessageEntity(
            id = "followups_prompt",
            conversationId = "followups",
            role = MessageEntity.ROLE_USER,
            content = prompt,
            createdAt = 0L,
          )
        )

      val request =
        when (provider) {
          AiProvider.GEMINI ->
            buildGeminiRequest(
              modelId = modelId,
              apiKey = apiKey,
              history = syntheticHistory,
              stream = false,
              includeAccuracyAndMemory = false,
            )
          AiProvider.OPENROUTER ->
            buildOpenRouterRequest(
              modelId = modelId,
              apiKey = apiKey,
              history = syntheticHistory,
              stream = false,
              includeAccuracyAndMemory = false,
            )
        }

      runCatching {
          okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val raw = response.body?.string().orEmpty()
            val extracted =
              when (provider) {
                AiProvider.GEMINI -> parseGeminiSsePayload(raw).text
                AiProvider.OPENROUTER -> {
                  val root = JSONObject(raw)
                  root
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                }
              }
            parseSuggestedRepliesText(extracted.orEmpty())
          }
        }
        .getOrElse { emptyList() }
    }

  internal fun parseSuggestedRepliesText(rawText: String): List<String> {
    if (rawText.isBlank()) return emptyList()
    return rawText
      .lineSequence()
      .map { line ->
        line
          .trim()
          .replace(LIST_PREFIX_REGEX, "")
          .trim('"', '\'', ' ')
          .replace(WHITESPACE_REGEX, " ")
      }
      .filter { it.length in 3..80 }
      .distinct()
      .take(3)
      .toList()
  }

  internal fun buildGeminiRequest(
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
    stream: Boolean,
    systemPrompt: String = "",
    memoryContext: String = "",
    skillsIndexBlock: String = "",
    activeSkillBlock: String = "",
    includeAccuracyAndMemory: Boolean = true,
    imageBase64ByUri: Map<String, String> = emptyMap(),
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
    binaryBase64ByPath: Map<String, String> = emptyMap(),
    textContentByPath: Map<String, String> = emptyMap(),
  ): Request {
    val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:$action"

    val contentsArray = JSONArray()
    for (msg in history) {
      if (msg.isError) continue
      val partsArray = JSONArray()
      val msgAttachments = attachmentsByMessageId[msg.id].orEmpty()

      val combinedText = buildMessageTextWithTextAttachments(msg.content, msgAttachments, textContentByPath)
      if (combinedText.isNotBlank()) {
        partsArray.put(JSONObject().put("text", combinedText))
      }

      if (msgAttachments.isNotEmpty()) {
        for (att in msgAttachments) {
          if (
            att.type == AttachmentTypes.IMAGE ||
              att.type == AttachmentTypes.PDF ||
              att.type == AttachmentTypes.AUDIO ||
              att.type == AttachmentTypes.VIDEO
          ) {
            val base64 = binaryBase64ByPath[att.localPath]
            if (!base64.isNullOrBlank()) {
              val mime =
                att.mimeType.takeIf { it.isNotBlank() }
                  ?: when (att.type) {
                    AttachmentTypes.PDF -> "application/pdf"
                    AttachmentTypes.AUDIO -> "audio/mpeg"
                    AttachmentTypes.VIDEO -> "video/mp4"
                    else -> "image/jpeg"
                  }
              partsArray.put(
                JSONObject()
                  .put("inlineData", JSONObject().put("mimeType", mime).put("data", base64))
              )
            }
          }
        }
      } else {
        // Legacy fallback when attachments list is empty but imageUri exists
        val imageBase64 = msg.imageUri?.let { imageBase64ByUri[it] ?: binaryBase64ByPath[it] }
        if (!imageBase64.isNullOrBlank()) {
          partsArray.put(
            JSONObject()
              .put(
                "inlineData",
                JSONObject().put("mimeType", "image/jpeg").put("data", imageBase64),
              )
          )
        }
      }

      if (partsArray.length() == 0) continue

      val role = if (msg.role == MessageEntity.ROLE_USER) "user" else "model"
      contentsArray.put(JSONObject().put("role", role).put("parts", partsArray))
    }

    val payload = JSONObject().put("contents", contentsArray)
    val finalSystemInstruction =
      resolveSystemInstruction(
        customSystemPrompt = systemPrompt,
        memoryContext = memoryContext,
        skillsIndexBlock = skillsIndexBlock,
        activeSkillBlock = activeSkillBlock,
        includeAccuracyAndMemory = includeAccuracyAndMemory,
      )
    if (finalSystemInstruction.isNotEmpty()) {
      payload.put(
        "systemInstruction",
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", finalSystemInstruction))),
      )
    }

    val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

    return Request.Builder()
      .url(url)
      .addHeader("x-goog-api-key", apiKey)
      .addHeader("Content-Type", "application/json")
      .post(body)
      .build()
  }

  internal fun buildOpenRouterRequest(
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
    stream: Boolean,
    systemPrompt: String = "",
    memoryContext: String = "",
    skillsIndexBlock: String = "",
    activeSkillBlock: String = "",
    includeAccuracyAndMemory: Boolean = true,
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
    textContentByPath: Map<String, String> = emptyMap(),
  ): Request {
    val messagesArray = JSONArray()
    val finalSystemInstruction =
      resolveSystemInstruction(
        customSystemPrompt = systemPrompt,
        memoryContext = memoryContext,
        skillsIndexBlock = skillsIndexBlock,
        activeSkillBlock = activeSkillBlock,
        includeAccuracyAndMemory = includeAccuracyAndMemory,
      )
    if (finalSystemInstruction.isNotEmpty()) {
      messagesArray.put(JSONObject().put("role", "system").put("content", finalSystemInstruction))
    }

    for (msg in history) {
      if (msg.isError) continue
      val combinedText =
        buildMessageTextWithTextAttachments(
          msg.content,
          attachmentsByMessageId[msg.id].orEmpty(),
          textContentByPath,
        )
      if (combinedText.isBlank()) continue
      val role = if (msg.role == MessageEntity.ROLE_USER) "user" else "assistant"
      messagesArray.put(JSONObject().put("role", role).put("content", combinedText))
    }

    val payload =
      JSONObject()
        .put("model", modelId)
        .put("stream", stream)
        .put("messages", messagesArray)
    val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

    return Request.Builder()
      .url("https://openrouter.ai/api/v1/chat/completions")
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("Content-Type", "application/json")
      .addHeader("HTTP-Referer", "https://github.com/ved-android")
      .addHeader("X-Title", "Ved")
      .post(body)
      .build()
  }

  private fun buildMessageTextWithTextAttachments(
    baseContent: String,
    attachments: List<MessageAttachmentEntity>,
    textContentByPath: Map<String, String>,
  ): String {
    val textBlocks =
      attachments
        .filter { it.type == AttachmentTypes.TEXT }
        .mapNotNull { att ->
          val content = textContentByPath[att.localPath]
          if (content != null) {
            AttachmentProcessor.formatTextAttachmentForPrompt(att.displayName, content)
          } else {
            null
          }
        }
    if (textBlocks.isEmpty()) return baseContent

    return buildString {
      if (baseContent.isNotBlank()) {
        append(baseContent.trimEnd())
        append("\n\n")
      }
      append(textBlocks.joinToString(separator = "\n\n"))
    }
  }

  private fun resolveSystemInstruction(
    customSystemPrompt: String,
    memoryContext: String,
    skillsIndexBlock: String,
    activeSkillBlock: String,
    includeAccuracyAndMemory: Boolean,
  ): String {
    return if (includeAccuracyAndMemory) {
      MemoryPromptHelper.buildCombinedSystemInstruction(
        customSystemPrompt = customSystemPrompt,
        memoryContextBlock = memoryContext,
        leadingInstruction = ACCURACY_SYSTEM_INSTRUCTION,
        skillsIndexBlock = skillsIndexBlock,
        activeSkillBlock = activeSkillBlock,
      )
    } else {
      customSystemPrompt.trim()
    }
  }

  private data class ParsedSse(
    val text: String? = null,
    val error: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
  )

  private fun parseGeminiSsePayload(rawJson: String): ParsedSse {
    return runCatching {
        val root = JSONObject(rawJson)
        root.optJSONObject("error")?.let { err ->
          val msg = err.optString("message").ifBlank { "Gemini API error" }
          return ParsedSse(error = msg)
        }

        val usage = root.optJSONObject("usageMetadata")
        val promptTokens =
          if (usage != null && usage.has("promptTokenCount")) usage.optInt("promptTokenCount")
          else null
        val completionTokens =
          if (usage != null && usage.has("candidatesTokenCount")) usage.optInt("candidatesTokenCount")
          else null

        val parts =
          root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")

        val text =
          if (parts == null || parts.length() == 0) {
            null
          } else if (parts.length() == 1) {
            parts.optJSONObject(0)?.optString("text", "")
          } else {
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
              sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
            }
            sb.toString()
          }

        ParsedSse(
          text = text,
          promptTokens = promptTokens,
          completionTokens = completionTokens,
        )
      }
      .getOrElse { ParsedSse() }
  }

  private fun parseOpenRouterSsePayload(rawJson: String): ParsedSse {
    return runCatching {
        val root = JSONObject(rawJson)
        root.optJSONObject("error")?.let { err ->
          val msg = err.optString("message").ifBlank { "OpenRouter API error" }
          return ParsedSse(error = msg)
        }

        val usage = root.optJSONObject("usage")
        val promptTokens =
          if (usage != null && usage.has("prompt_tokens")) usage.optInt("prompt_tokens") else null
        val completionTokens =
          if (usage != null && usage.has("completion_tokens")) usage.optInt("completion_tokens")
          else null

        val delta =
          root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
        val content =
          if (delta == null || delta.isNull("content")) null else delta.optString("content", "")

        ParsedSse(
          text = content,
          promptTokens = promptTokens,
          completionTokens = completionTokens,
        )
      }
      .getOrElse { ParsedSse() }
  }

  private fun parseApiError(statusCode: Int, errorBody: String, provider: AiProvider): String {
    val parsedMessage =
      runCatching {
          val json = JSONObject(errorBody)
          json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
        }
        .getOrNull()

    return when {
      statusCode == 401 || statusCode == 403 ->
        "${provider.displayName} authentication failed ($statusCode). Check your API key in Settings."
      statusCode == 429 ->
        "${provider.displayName} rate limit reached (429). Please wait a moment and try again."
      parsedMessage != null -> "${provider.displayName} error ($statusCode): $parsedMessage"
      else -> "${provider.displayName} request failed with HTTP $statusCode."
    }
  }

  private companion object {
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    val WHITESPACE_REGEX = Regex("\\s+")
    val LIST_PREFIX_REGEX = Regex("^(?:[-*•]+|\\d+[.)])\\s*")

    // Note: Actual model behavior ultimately depends on which underlying model is selected
    // (e.g., Gemini vs. an OpenRouter-routed Claude or GPT model). This baseline instruction
    // improves honesty and calibration across providers, but cannot guarantee zero hallucination
    // because hallucination resistance is an inherent property of the underlying model itself,
    // not something any system prompt can fully control.
    private const val ACCURACY_SYSTEM_INSTRUCTION =
      "Follow these standards for every response:\n" +
        "1. If you are not confident about a fact, number, date, name, or claim, say so explicitly rather than presenting it as certain. Use phrases like \"I'm not certain, but...\" or \"I don't have reliable information on this\" instead of guessing confidently.\n" +
        "2. Never invent sources, statistics, citations, URLs, or specific numbers you cannot verify. If asked for a source and you don't have one, say you don't have one rather than fabricating one.\n" +
        "3. Distinguish clearly between what is well-established fact, what is your best inference, and what is genuinely uncertain or disputed.\n" +
        "4. If a question depends on information that may have changed after your training data, or requires real-time data you don't have access to, say so plainly instead of answering as if you have current information.\n" +
        "5. If a request is ambiguous, ask a clarifying question rather than assuming and answering confidently based on a guess.\n" +
        "6. Prioritize correctness over sounding impressive. A short, honest \"I don't know\" or \"I'm not sure\" is always better than a confident wrong answer.\n" +
        "7. When giving code, technical steps, or instructions, double check logical consistency before presenting it as final — do not present untested assumptions as verified facts."
  }
}
