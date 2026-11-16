package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AiModelInfo
import com.example.data.model.AiProvider
import com.example.data.model.FallbackGeminiModels
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
import kotlinx.coroutines.withContext

@Immutable
data class SettingsUiState(
  val activeProvider: AiProvider = AiProvider.GEMINI,
  val geminiModel: String = FallbackGeminiModels.first().id,
  val openRouterModel: String = FallbackOpenRouterModels.first().id,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val systemPrompt: String = "",
  val geminiModels: List<AiModelInfo> = FallbackGeminiModels,
  val isLoadingGeminiModels: Boolean = false,
  val geminiModelsError: String? = null,
  val openRouterModels: List<AiModelInfo> = FallbackOpenRouterModels,
  val filteredOpenRouterModels: List<AiModelInfo> = FallbackOpenRouterModels,
  val isLoadingOpenRouterModels: Boolean = false,
  val openRouterModelsError: String? = null,
  val modelSearchQuery: String = "",
  val geminiKeySource: KeySource = KeySource.MISSING,
  val openRouterKeySource: KeySource = KeySource.MISSING,
  val enabledSkillsCount: Int = 0,
  val totalSkillsCount: Int = 0,
  val showSuggestedReplies: Boolean = true,
  val voiceInputLanguage: String = "system",
  val isBackupBusy: Boolean = false,
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
 * ViewModel for provider switching, dynamic Gemini & OpenRouter model discovery, theme selection,
 * custom system instructions, backup export/import, skills summary, and Keystore-encrypted API key
 * overrides.
 */
class SettingsViewModel(
  private val repository: ChatRepository,
  private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  private val _geminiModels = MutableStateFlow(FallbackGeminiModels)
  private val _isLoadingGeminiModels = MutableStateFlow(false)
  private val _geminiModelsError = MutableStateFlow<String?>(null)

  private val _openRouterModels = MutableStateFlow(FallbackOpenRouterModels)
  private val _isLoadingModels = MutableStateFlow(false)
  private val _modelsError = MutableStateFlow<String?>(null)
  private val _searchQuery = MutableStateFlow("")
  private val _isBackupBusy = MutableStateFlow(false)
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

  private val geminiCatalogFlow =
    combine(_geminiModels, _isLoadingGeminiModels, _geminiModelsError) {
      models,
      loading,
      error ->
      GeminiCatalogState(models, loading, error)
    }

  private val openRouterCatalogFlow =
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
        OpenRouterCatalogState(models, filtered, loading, error, query)
      }
      .flowOn(Dispatchers.Default)

  private val modelsMetaFlow =
    combine(geminiCatalogFlow, openRouterCatalogFlow) { gemini, openRouter -> gemini to openRouter }

  private val skillsCountFlow =
    repository.observeSkills().combine(_isBackupBusy) { skills, _ ->
      skills.count { it.isEnabled } to skills.size
    }

  private val statusMetaFlow =
    combine(_isBackupBusy, _feedbackMessage, skillsCountFlow) {
      backupBusy,
      feedback,
      skillsCount ->
      StatusMeta(backupBusy, feedback, skillsCount.first, skillsCount.second)
    }

  val uiState: StateFlow<SettingsUiState> =
    combine(prefsFlow, modelsMetaFlow, keyStatusesFlow, statusMetaFlow) {
        prefs,
        catalogs,
        keySources,
        statusMeta ->
        val (geminiCatalog, openRouterCatalog) = catalogs
        SettingsUiState(
          activeProvider = prefs.activeProvider,
          geminiModel = prefs.geminiModel,
          openRouterModel = prefs.openRouterModel,
          themeMode = prefs.themeMode,
          systemPrompt = prefs.systemPrompt,
          geminiModels = geminiCatalog.models,
          isLoadingGeminiModels = geminiCatalog.isLoading,
          geminiModelsError = geminiCatalog.error,
          openRouterModels = openRouterCatalog.models,
          filteredOpenRouterModels = openRouterCatalog.filteredModels,
          isLoadingOpenRouterModels = openRouterCatalog.isLoading,
          openRouterModelsError = openRouterCatalog.error,
          modelSearchQuery = openRouterCatalog.query,
          geminiKeySource = keySources.first,
          openRouterKeySource = keySources.second,
          enabledSkillsCount = statusMeta.enabledSkillsCount,
          totalSkillsCount = statusMeta.totalSkillsCount,
          showSuggestedReplies = prefs.showSuggestedReplies,
          voiceInputLanguage = prefs.voiceInputLanguage,
          isBackupBusy = statusMeta.isBackupBusy,
          feedbackMessage = statusMeta.feedbackMessage,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
      )

  init {
    refreshGeminiModels(forceRefresh = false)
    refreshOpenRouterModels()
  }

  fun setProvider(provider: AiProvider) {
    viewModelScope.launch {
      preferencesRepository.setActiveProvider(provider)
      if (provider == AiProvider.GEMINI) {
        refreshGeminiModels(forceRefresh = false)
      }
    }
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

  fun setShowSuggestedReplies(enabled: Boolean) {
    viewModelScope.launch { preferencesRepository.setShowSuggestedReplies(enabled) }
  }

  fun setVoiceInputLanguage(lang: String) {
    viewModelScope.launch { preferencesRepository.setVoiceInputLanguage(lang) }
  }

  fun saveSystemPrompt(prompt: String) {
    viewModelScope.launch {
      preferencesRepository.setSystemPrompt(prompt.trim())
      _feedbackMessage.value = "Custom instructions saved"
    }
  }

  fun exportBackup(context: Context, uri: Uri) {
    if (_isBackupBusy.value) return
    val appContext = context.applicationContext
    viewModelScope.launch {
      _isBackupBusy.value = true
      val result =
        runCatching {
          val json = repository.exportBackupJson()
          withContext(Dispatchers.IO) {
            appContext.contentResolver.openOutputStream(uri)?.use { stream ->
              stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(json) }
            } ?: error("Could not open destination file for writing.")
          }
        }
      _isBackupBusy.value = false
      result
        .onSuccess { _feedbackMessage.value = "Chat history exported successfully" }
        .onFailure { err ->
          _feedbackMessage.value = err.message ?: "Failed to export chat history."
        }
    }
  }

  fun importBackup(context: Context, uri: Uri) {
    if (_isBackupBusy.value) return
    val appContext = context.applicationContext
    viewModelScope.launch {
      _isBackupBusy.value = true
      val result =
        runCatching {
          val rawJson =
            withContext(Dispatchers.IO) {
              appContext.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
              } ?: error("Could not read selected backup file.")
            }
          repository.importBackupJson(rawJson).getOrThrow()
        }
      _isBackupBusy.value = false
      result
        .onSuccess { summary ->
          _feedbackMessage.value =
            "Imported ${summary.conversationsImported} conversations (${summary.messagesImported} messages)"
        }
        .onFailure {
          _feedbackMessage.value = "Invalid backup file. Please choose a valid Ved JSON export."
        }
    }
  }

  fun updateSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun refreshGeminiModels(forceRefresh: Boolean = true) {
    if (_isLoadingGeminiModels.value) return
    viewModelScope.launch {
      val apiKey = repository.getEffectiveKey(AiProvider.GEMINI)
      if (apiKey.isBlank()) {
        _geminiModels.value = FallbackGeminiModels
        _geminiModelsError.value = null
        return@launch
      }

      _isLoadingGeminiModels.value = true
      _geminiModelsError.value = null
      repository
        .fetchGeminiModels(apiKey = apiKey, forceRefresh = forceRefresh)
        .onSuccess { models ->
          _geminiModels.value = models
          // If the currently stored model is no longer in the live list, auto-select the top
          // available model (unless it's a fallback-only session).
          val currentPrefs = repository.getCurrentPreferences()
          if (models.isNotEmpty() && models.none { it.id == currentPrefs.geminiModel }) {
            val preferredDefault =
              models.firstOrNull { it.id == "gemini-flash-latest" } ?: models.first()
            preferencesRepository.setGeminiModel(preferredDefault.id)
          }
        }
        .onFailure { err ->
          _geminiModelsError.value = err.message ?: "Could not load Gemini models."
          if (_geminiModels.value.isEmpty()) {
            _geminiModels.value = FallbackGeminiModels
          }
        }
      _isLoadingGeminiModels.value = false
    }
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
      if (provider == AiProvider.GEMINI) {
        refreshGeminiModels(forceRefresh = true)
      }
    }
  }

  fun clearKeyOverride(provider: AiProvider) {
    viewModelScope.launch {
      repository.clearOverrideKey(provider)
      _feedbackMessage.value = "${provider.displayName} local override removed"
      if (provider == AiProvider.GEMINI) {
        refreshGeminiModels(forceRefresh = true)
      }
    }
  }

  fun clearFeedback() {
    _feedbackMessage.value = null
  }

  private data class GeminiCatalogState(
    val models: List<AiModelInfo>,
    val isLoading: Boolean,
    val error: String?,
  )

  private data class OpenRouterCatalogState(
    val models: List<AiModelInfo>,
    val filteredModels: List<AiModelInfo>,
    val isLoading: Boolean,
    val error: String?,
    val query: String,
  )

  private data class StatusMeta(
    val isBackupBusy: Boolean,
    val feedbackMessage: String?,
    val enabledSkillsCount: Int,
    val totalSkillsCount: Int,
  )
}
