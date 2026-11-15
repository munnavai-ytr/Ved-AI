package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.withContext

@Immutable
data class SettingsUiState(
  val activeProvider: AiProvider = AiProvider.GEMINI,
  val geminiModel: String = DefaultGeminiModels.first().id,
  val openRouterModel: String = FallbackOpenRouterModels.first().id,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val systemPrompt: String = "",
  val geminiModels: List<AiModelInfo> = DefaultGeminiModels,
  val openRouterModels: List<AiModelInfo> = FallbackOpenRouterModels,
  val filteredOpenRouterModels: List<AiModelInfo> = FallbackOpenRouterModels,
  val isLoadingOpenRouterModels: Boolean = false,
  val openRouterModelsError: String? = null,
  val modelSearchQuery: String = "",
  val geminiKeySource: KeySource = KeySource.MISSING,
  val openRouterKeySource: KeySource = KeySource.MISSING,
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
 * ViewModel for provider switching, dynamic OpenRouter model discovery, theme selection,
 * custom system instructions, backup export/import, and Keystore-encrypted API key overrides.
 */
class SettingsViewModel(
  private val repository: ChatRepository,
  private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

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

  private val statusMetaFlow =
    combine(_isBackupBusy, _feedbackMessage) { backupBusy, feedback -> backupBusy to feedback }

  val uiState: StateFlow<SettingsUiState> =
    combine(prefsFlow, modelsMetaFlow, keyStatusesFlow, statusMetaFlow) {
        prefs,
        catalog,
        keySources,
        statusMeta ->
        SettingsUiState(
          activeProvider = prefs.activeProvider,
          geminiModel = prefs.geminiModel,
          openRouterModel = prefs.openRouterModel,
          themeMode = prefs.themeMode,
          systemPrompt = prefs.systemPrompt,
          geminiModels = DefaultGeminiModels,
          openRouterModels = catalog.models,
          filteredOpenRouterModels = catalog.filteredModels,
          isLoadingOpenRouterModels = catalog.isLoading,
          openRouterModelsError = catalog.error,
          modelSearchQuery = catalog.query,
          geminiKeySource = keySources.first,
          openRouterKeySource = keySources.second,
          isBackupBusy = statusMeta.first,
          feedbackMessage = statusMeta.second,
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
