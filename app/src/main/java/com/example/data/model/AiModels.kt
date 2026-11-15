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
 * Last-resort fallback Gemini models used ONLY if the live `/v1beta/models` endpoint is
 * unreachable or no Gemini API key is configured yet. Not a primary model source.
 */
val FallbackGeminiModels: List<AiModelInfo> =
  listOf(
    AiModelInfo(
      id = "gemini-flash-latest",
      name = "Gemini Flash (Latest)",
      provider = AiProvider.GEMINI,
      contextLength = 1_048_576,
    ),
    AiModelInfo(
      id = "gemini-pro-latest",
      name = "Gemini Pro (Latest)",
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
