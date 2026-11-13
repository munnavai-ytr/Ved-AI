package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ThemeMode
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppBootstrapState {
  data object Loading : AppBootstrapState

  data class Ready(
    val themeMode: ThemeMode,
    val onboardingCompleted: Boolean,
  ) : AppBootstrapState
}

/**
 * Top-level ViewModel managing theme mode and first-launch onboarding state.
 */
class MainViewModel(
  private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

  val bootstrapState: StateFlow<AppBootstrapState> =
    preferencesRepository.preferencesFlow
      .map { prefs ->
        AppBootstrapState.Ready(
          themeMode = prefs.themeMode,
          onboardingCompleted = prefs.onboardingCompleted,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppBootstrapState.Loading,
      )

  fun completeOnboarding() {
    viewModelScope.launch { preferencesRepository.setOnboardingCompleted(true) }
  }
}
