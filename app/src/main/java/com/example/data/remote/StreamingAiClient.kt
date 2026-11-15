package com.example.data.remote

import com.example.data.local.entity.MessageEntity
import com.example.data.model.AiProvider
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
    imageBase64ByUri: Map<String, String> = emptyMap(),
  ): Flow<StreamChunk> = callbackFlow {
    var activeCall: okhttp3.Call? = null

    val job =
      launch(Dispatchers.IO) {
        try {
          val combinedSystemInstruction =
            MemoryPromptHelper.buildCombinedSystemInstruction(
              customSystemPrompt = systemPrompt,
              memoryContextBlock = memoryContext,
            )

          val request =
            when (provider) {
              AiProvider.GEMINI ->
                buildGeminiRequest(
                  modelId = modelId,
                  apiKey = apiKey,
                  history = history,
                  stream = true,
                  systemPrompt = combinedSystemInstruction,
                  imageBase64ByUri = imageBase64ByUri,
                )
              AiProvider.OPENROUTER ->
                buildOpenRouterRequest(
                  modelId = modelId,
                  apiKey = apiKey,
                  history = history,
                  stream = true,
                  systemPrompt = combinedSystemInstruction,
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
            )
          AiProvider.OPENROUTER ->
            buildOpenRouterRequest(
              modelId = modelId,
              apiKey = apiKey,
              history = syntheticHistory,
              stream = false,
              systemPrompt = systemPrompt,
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

  private fun buildGeminiRequest(
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
    stream: Boolean,
    systemPrompt: String = "",
    imageBase64ByUri: Map<String, String> = emptyMap(),
  ): Request {
    val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:$action"

    val contentsArray = JSONArray()
    for (msg in history) {
      if (msg.isError) continue
      val partsArray = JSONArray()
      if (msg.content.isNotBlank()) {
        partsArray.put(JSONObject().put("text", msg.content))
      }
      val imageBase64 = msg.imageUri?.let { imageBase64ByUri[it] }
      if (!imageBase64.isNullOrBlank()) {
        partsArray.put(
          JSONObject()
            .put(
              "inlineData",
              JSONObject().put("mimeType", "image/jpeg").put("data", imageBase64),
            )
        )
      }
      if (partsArray.length() == 0) continue

      val role = if (msg.role == MessageEntity.ROLE_USER) "user" else "model"
      contentsArray.put(JSONObject().put("role", role).put("parts", partsArray))
    }

    val payload = JSONObject().put("contents", contentsArray)
    val trimmedSystemPrompt = systemPrompt.trim()
    if (trimmedSystemPrompt.isNotEmpty()) {
      payload.put(
        "systemInstruction",
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", trimmedSystemPrompt))),
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

  private fun buildOpenRouterRequest(
    modelId: String,
    apiKey: String,
    history: List<MessageEntity>,
    stream: Boolean,
    systemPrompt: String = "",
  ): Request {
    val messagesArray = JSONArray()
    val trimmedSystemPrompt = systemPrompt.trim()
    if (trimmedSystemPrompt.isNotEmpty()) {
      messagesArray.put(JSONObject().put("role", "system").put("content", trimmedSystemPrompt))
    }

    for (msg in history) {
      if (msg.isError || msg.content.isBlank()) continue
      val role = if (msg.role == MessageEntity.ROLE_USER) "user" else "assistant"
      messagesArray.put(JSONObject().put("role", role).put("content", msg.content))
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
  }
}
