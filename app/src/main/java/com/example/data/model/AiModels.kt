package com.example.data.model

import androidx.compose.runtime.Immutable

/**
 * Supported conversational AI backend providers.
 */
enum class AiProvider(val id: String, val displayName: String) {
  GEMINI(id = "gemini", displayName = "Gemini"),
  OPENROUTER(id = "openrouter", displayName = "OpenRouter");

  companion object {
    fun fromId(id: String?): AiProvider =
      entries.firstOrNull { it.id == id } ?: GEMINI
  }
}

/**
 * App-wide theme preference.
 */
enum class ThemeMode(val id: String) {
  SYSTEM("system"),
  LIGHT("light"),
  DARK("dark");

  companion object {
    fun fromId(id: String?): ThemeMode =
      entries.firstOrNull { it.id == id } ?: SYSTEM
  }
}

/**
 * Descriptor for a selectable model under a specific [AiProvider].
 */
@Immutable
data class AiModelInfo(
  val id: String,
  val name: String,
  val provider: AiProvider,
  val contextLength: Int? = null,
  val isFree: Boolean = false,
)

/**
 * Built-in curated Gemini models available via Google AI Studio API.
 */
val DefaultGeminiModels: List<AiModelInfo> =
  listOf(
    AiModelInfo(
      id = "gemini-2.5-flash",
      name = "Gemini 2.5 Flash",
      provider = AiProvider.GEMINI,
      contextLength = 1_048_576,
    ),
    AiModelInfo(
      id = "gemini-2.5-pro",
      name = "Gemini 2.5 Pro",
      provider = AiProvider.GEMINI,
      contextLength = 1_048_576,
    ),
    AiModelInfo(
      id = "gemini-2.5-flash-lite",
      name = "Gemini 2.5 Flash-Lite",
      provider = AiProvider.GEMINI,
      contextLength = 1_048_576,
    ),
    AiModelInfo(
      id = "gemini-2.0-flash",
      name = "Gemini 2.0 Flash",
      provider = AiProvider.GEMINI,
      contextLength = 1_048_576,
    ),
  )

/**
 * Initial fallback list for OpenRouter before remote `/models` data is fetched.
 */
val FallbackOpenRouterModels: List<AiModelInfo> =
  listOf(
    AiModelInfo(
      id = "google/gemini-2.5-flash",
      name = "Google: Gemini 2.5 Flash",
      provider = AiProvider.OPENROUTER,
      contextLength = 1_048_576,
    ),
    AiModelInfo(
      id = "openai/gpt-4o-mini",
      name = "OpenAI: GPT-4o Mini",
      provider = AiProvider.OPENROUTER,
      contextLength = 128_000,
    ),
    AiModelInfo(
      id = "anthropic/claude-sonnet-4",
      name = "Anthropic: Claude Sonnet 4",
      provider = AiProvider.OPENROUTER,
      contextLength = 200_000,
    ),
    AiModelInfo(
      id = "meta-llama/llama-3.3-70b-instruct:free",
      name = "Meta: Llama 3.3 70B (Free)",
      provider = AiProvider.OPENROUTER,
      contextLength = 131_072,
      isFree = true,
    ),
  )
