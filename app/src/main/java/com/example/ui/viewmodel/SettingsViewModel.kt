package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AiModelInfo
import com.example.data.model.AiProvider
import com.example.data.model.DefaultGeminiModels
import com.example.data.model.FallbackOpenRouterModels
import com.example.data.model.ThemeMode
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.ChatRepository
import com.example.data.security.KeySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
  val activeProvider: AiProvider = AiProvider.GEMINI,
  val geminiModel: String = DefaultGeminiModels.first().id,
  val openRouterModel: String = FallbackOpenRouterModels.first().id,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val geminiModels: List<AiModelInfo> = DefaultGeminiModels,
  val openRouterModels: List<AiModelInfo> = FallbackOpenRouterModels,
  val filteredOpenRouterModels: List<AiModelInfo> = FallbackOpenRouterModels,
  val isLoadingOpenRouterModels: Boolean = false,
  val openRouterModelsError: String? = null,
  val modelSearchQuery: String = "",
  val geminiKeySource: KeySource = KeySource.MISSING,
  val openRouterKeySource: KeySource = KeySource.MISSING,
  val feedbackMessage: String? = null,
) {
  val activeModelId: String
    get() =
      when (activeProvider) {
        AiProvider.GEMINI -> geminiModel
        AiProvider.OPENROUTER -> openRouterModel
      }
}

/**
 * ViewModel for provider switching, dynamic OpenRouter model discovery, theme selection,
 * and Keystore-encrypted API key overrides.
 */
class SettingsViewModel(
  private val repository: ChatRepository,
  private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  private val _openRouterModels = MutableStateFlow(FallbackOpenRouterModels)
  private val _isLoadingModels = MutableStateFlow(false)
  private val _modelsError = MutableStateFlow<String?>(null)
  private val _searchQuery = MutableStateFlow("")
  private val _feedbackMessage = MutableStateFlow<String?>(null)

  private val prefsFlow =
    preferencesRepository.preferencesFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = UserPreferences(),
    )

  private val keyStatusesFlow =
    repository.keyVersionFlow
      .combine(prefsFlow) { _, _ ->
        repository.getKeySource(AiProvider.GEMINI) to repository.getKeySource(AiProvider.OPENROUTER)
      }

  private val modelsMetaFlow =
    combine(_openRouterModels, _isLoadingModels, _modelsError, _searchQuery) {
        models,
        loading,
        error,
        query ->
        val q = query.trim()
        val filtered =
          if (q.isEmpty()) models
          else
            models.filter {
              it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
            }
        ModelCatalogState(models, filtered, loading, error, query)
      }
      .flowOn(Dispatchers.Default)

  val uiState: StateFlow<SettingsUiState> =
    combine(prefsFlow, modelsMetaFlow, keyStatusesFlow, _feedbackMessage) {
        prefs,
        catalog,
        keySources,
        feedback ->
        SettingsUiState(
          activeProvider = prefs.activeProvider,
          geminiModel = prefs.geminiModel,
          openRouterModel = prefs.openRouterModel,
          themeMode = prefs.themeMode,
          geminiModels = DefaultGeminiModels,
          openRouterModels = catalog.models,
          filteredOpenRouterModels = catalog.filteredModels,
          isLoadingOpenRouterModels = catalog.isLoading,
          openRouterModelsError = catalog.error,
          modelSearchQuery = catalog.query,
          geminiKeySource = keySources.first,
          openRouterKeySource = keySources.second,
          feedbackMessage = feedback,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
      )

  init {
    refreshOpenRouterModels()
  }

  fun setProvider(provider: AiProvider) {
    viewModelScope.launch { preferencesRepository.setActiveProvider(provider) }
  }

  fun setModel(modelId: String) {
    viewModelScope.launch {
      when (uiState.value.activeProvider) {
        AiProvider.GEMINI -> preferencesRepository.setGeminiModel(modelId)
        AiProvider.OPENROUTER -> preferencesRepository.setOpenRouterModel(modelId)
      }
    }
  }

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
  }

  fun updateSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun refreshOpenRouterModels() {
    if (_isLoadingModels.value) return
    viewModelScope.launch {
      _isLoadingModels.value = true
      _modelsError.value = null
      repository
        .fetchOpenRouterModels()
        .onSuccess { models -> _openRouterModels.value = models }
        .onFailure { err ->
          _modelsError.value = err.message ?: "Could not load OpenRouter models."
        }
      _isLoadingModels.value = false
    }
  }

  fun saveKeyOverride(provider: AiProvider, key: String) {
    viewModelScope.launch {
      val trimmed = key.trim()
      if (trimmed.isEmpty()) return@launch
      repository.setOverrideKey(provider, trimmed)
      _feedbackMessage.value = "${provider.displayName} encrypted key saved"
    }
  }

  fun clearKeyOverride(provider: AiProvider) {
    viewModelScope.launch {
      repository.clearOverrideKey(provider)
      _feedbackMessage.value = "${provider.displayName} local override removed"
    }
  }

  fun clearFeedback() {
    _feedbackMessage.value = null
  }

  private data class ModelCatalogState(
    val models: List<AiModelInfo>,
    val filteredModels: List<AiModelInfo>,
    val isLoading: Boolean,
    val error: String?,
    val query: String,
  )
}
