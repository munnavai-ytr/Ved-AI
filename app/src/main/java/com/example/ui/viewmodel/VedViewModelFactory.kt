package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.di.AppContainer

/**
 * Simple constructor-injection factory for Ved ViewModels.
 */
class VedViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return when {
      modelClass.isAssignableFrom(MainViewModel::class.java) ->
        MainViewModel(container.preferencesRepository) as T
      modelClass.isAssignableFrom(ChatViewModel::class.java) ->
        ChatViewModel(container.chatRepository, container.aiGenerationCoordinator) as T
      modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
        SettingsViewModel(container.chatRepository, container.preferencesRepository) as T
      modelClass.isAssignableFrom(MemoryViewModel::class.java) ->
        MemoryViewModel(container.chatRepository) as T
      modelClass.isAssignableFrom(SkillsViewModel::class.java) ->
        SkillsViewModel(container.chatRepository) as T
      else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
  }
}
