package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.navigation.VedNavHost
import com.example.ui.theme.VedTheme
import com.example.ui.viewmodel.AppBootstrapState
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.VedViewModelFactory

/**
 * Single-activity entry point hosting edge-to-edge Compose UI for Ved.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val appContainer = (application as VedApplication).container
    val factory = VedViewModelFactory(appContainer)

    setContent {
      val mainViewModel: MainViewModel = viewModel(factory = factory)
      val bootstrapState by mainViewModel.bootstrapState.collectAsStateWithLifecycle()

      when (val state = bootstrapState) {
        AppBootstrapState.Loading -> {
          VedTheme {
            Box(
              modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            )
          }
        }
        is AppBootstrapState.Ready -> {
          VedTheme(themeMode = state.themeMode) {
            VedNavHost(
              startWithOnboarding = !state.onboardingCompleted,
              factory = factory,
              onCompleteOnboarding = mainViewModel::completeOnboarding,
            )
          }
        }
      }
    }
  }
}
