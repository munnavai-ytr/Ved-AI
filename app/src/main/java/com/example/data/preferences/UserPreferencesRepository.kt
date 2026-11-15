package com.example.data.preferences

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AiProvider
import com.example.data.model.FallbackGeminiModels
import com.example.data.model.FallbackOpenRouterModels
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vedDataStore: DataStore<Preferences> by preferencesDataStore(name = "ved_prefs")

/**
 * Snapshot of persisted user preferences.
 */
@Immutable
data class UserPreferences(
  val activeProvider: AiProvider = AiProvider.GEMINI,
  val geminiModel: String = FallbackGeminiModels.first().id,
  val openRouterModel: String = FallbackOpenRouterModels.first().id,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val onboardingCompleted: Boolean = false,
  val systemPrompt: String = "",
) {
  val activeModelId: String
    get() =
      when (activeProvider) {
        AiProvider.GEMINI -> geminiModel
        AiProvider.OPENROUTER -> openRouterModel
      }
}

/**
 * Jetpack DataStore repository for lightweight app configuration and preferences.
 */
class UserPreferencesRepository(private val context: Context) {

  val preferencesFlow: Flow<UserPreferences> =
    context.vedDataStore.data.map { prefs ->
      UserPreferences(
        activeProvider = AiProvider.fromId(prefs[KEY_ACTIVE_PROVIDER]),
        geminiModel = prefs[KEY_GEMINI_MODEL] ?: FallbackGeminiModels.first().id,
        openRouterModel = prefs[KEY_OPENROUTER_MODEL] ?: FallbackOpenRouterModels.first().id,
        themeMode = ThemeMode.fromId(prefs[KEY_THEME_MODE]),
        onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
        systemPrompt = prefs[KEY_SYSTEM_PROMPT] ?: "",
      )
    }

  suspend fun setActiveProvider(provider: AiProvider) {
    context.vedDataStore.edit { it[KEY_ACTIVE_PROVIDER] = provider.id }
  }

  suspend fun setGeminiModel(modelId: String) {
    context.vedDataStore.edit { it[KEY_GEMINI_MODEL] = modelId }
  }

  suspend fun setOpenRouterModel(modelId: String) {
    context.vedDataStore.edit { it[KEY_OPENROUTER_MODEL] = modelId }
  }

  suspend fun setThemeMode(mode: ThemeMode) {
    context.vedDataStore.edit { it[KEY_THEME_MODE] = mode.id }
  }

  suspend fun setOnboardingCompleted(completed: Boolean) {
    context.vedDataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
  }

  suspend fun setSystemPrompt(prompt: String) {
    context.vedDataStore.edit { it[KEY_SYSTEM_PROMPT] = prompt }
  }

  private companion object {
    val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
    val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
    val KEY_OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")
    val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
  }
}
