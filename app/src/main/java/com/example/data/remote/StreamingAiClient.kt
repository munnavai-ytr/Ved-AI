package com.example.data.remote

import com.example.data.local.entity.MessageEntity
import com.example.data.model.AiProvider
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

  data object Completed : StreamChunk

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
  ): Flow<StreamChunk> = callbackFlow {
    val request =
      when (provider) {
        AiProvider.GEMINI -> buildGeminiRequest(modelId = modelId, apiKey = apiKey, history = history, stream = true)
        AiProvider.OPENROUTER -> buildOpenRouterRequest(modelId = modelId, apiKey = apiKey, history = history, stream = true)
      }

    val call = okHttpClient.newCall(request)

    val job =
      launch(Dispatchers.IO) {
        try {
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

            while (!source.exhausted()) {
              val line = source.readUtf8Line() ?: break
              if (line.isBlank() || line.startsWith(":")) continue

              if (line.startsWith("data:")) {
                val payload = line.substring(5).trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break

                when (provider) {
                  AiProvider.GEMINI -> {
                    val parsed = parseGeminiSsePayload(payload)
                    if (parsed.error != null) {
                      trySend(StreamChunk.Error(parsed.error))
                      close()
                      return@launch
                    }
                    if (!parsed.text.isNullOrEmpty()) {
                      trySend(StreamChunk.Delta(parsed.text))
                    }
                  }
                  AiProvider.OPENROUTER -> {
                    val parsed = parseOpenRouterSsePayload(payload)
                    if (parsed.error != null) {
                      trySend(StreamChunk.Error(parsed.error))
                      close()
                      return@launch
                    }
                    if (!parsed.text.isNullOrEmpty()) {
                      trySend(StreamChunk.Delta(parsed.text))
                    }
                  }
                }
              }
            }

            trySend(StreamChunk.Completed)
            close()
          }
        } catch (e: IOException) {
          if (!call.isCanceled()) {
            trySend(StreamChunk.Error(e.message ?: "Network error while contacting ${provider.displayName}."))
          }
          close()
        } catch (e: Exception) {
          trySend(StreamChunk.Error(e.message ?: "Unexpected error while streaming response."))
          close()
        }
      }

    awaitClose {
      call.cancel()
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
            buildGeminiRequest(modelId = modelId, apiKey = apiKey, history = syntheticHistory, stream = false)
          AiProvider.OPENROUTER ->
            buildOpenRouterRequest(modelId = modelId, apiKey = apiKey, history = syntheticHistory, stream = false)
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
  ): Request {
    val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:$action"

    val contentsArray = JSONArray()
    for (msg in history) {
      if (msg.isError || msg.content.isBlank()) continue
      val role = if (msg.role == MessageEntity.ROLE_USER) "user" else "model"
      val partObj = JSONObject().put("text", msg.content)
      val partsArray = JSONArray().put(partObj)
      contentsArray.put(JSONObject().put("role", role).put("parts", partsArray))
    }

    val payload = JSONObject().put("contents", contentsArray)
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
  ): Request {
    val messagesArray = JSONArray()
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

  private data class ParsedSse(val text: String? = null, val error: String? = null)

  private fun parseGeminiSsePayload(rawJson: String): ParsedSse {
    return runCatching {
        val root = JSONObject(rawJson)
        root.optJSONObject("error")?.let { err ->
          val msg = err.optString("message").ifBlank { "Gemini API error" }
          return ParsedSse(error = msg)
        }
        val candidates = root.optJSONArray("candidates") ?: return ParsedSse()
        val first = candidates.optJSONObject(0) ?: return ParsedSse()
        val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return ParsedSse()
        val count = parts.length()
        if (count == 0) return ParsedSse()
        if (count == 1) {
          val text = parts.optJSONObject(0)?.optString("text", "")
          return ParsedSse(text = text)
        }
        val sb = StringBuilder()
        for (i in 0 until count) {
          val text = parts.optJSONObject(i)?.optString("text", "") ?: ""
          sb.append(text)
        }
        ParsedSse(text = sb.toString())
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
        val choices = root.optJSONArray("choices") ?: return ParsedSse()
        val first = choices.optJSONObject(0) ?: return ParsedSse()
        val delta = first.optJSONObject("delta") ?: return ParsedSse()
        val content =
          if (delta.isNull("content")) null else delta.optString("content", "")
        ParsedSse(text = content)
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
