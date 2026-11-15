package com.example.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.MemoryViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.VedViewModelFactory
import kotlinx.serialization.Serializable

@Serializable data object OnboardingRoute

@Serializable data object ChatRoute

@Serializable data object SettingsRoute

@Serializable data object MemoryRoute

/**
 * Type-safe Compose Navigation graph for Ved.
 */
@Composable
fun VedNavHost(
  startWithOnboarding: Boolean,
  factory: VedViewModelFactory,
  onCompleteOnboarding: () -> Unit,
) {
  val navController = rememberNavController()
  val startDestination: Any = if (startWithOnboarding) OnboardingRoute else ChatRoute

  NavHost(
    navController = navController,
    startDestination = startDestination,
    enterTransition = { fadeIn(animationSpec = tween(180)) },
    exitTransition = { fadeOut(animationSpec = tween(150)) },
  ) {
    composable<OnboardingRoute> {
      OnboardingScreen(
        onFinish = {
          onCompleteOnboarding()
          navController.navigate(ChatRoute) {
            popUpTo<OnboardingRoute> { inclusive = true }
          }
        }
      )
    }

    composable<ChatRoute> {
      val chatViewModel: ChatViewModel = viewModel(factory = factory)
      ChatScreen(
        viewModel = chatViewModel,
        onOpenSettings = {
          navController.navigate(SettingsRoute) {
            launchSingleTop = true
          }
        },
      )
    }

    composable<SettingsRoute> {
      val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
      SettingsScreen(
        viewModel = settingsViewModel,
        onNavigateBack = { navController.popBackStack() },
        onOpenMemory = {
          navController.navigate(MemoryRoute) {
            launchSingleTop = true
          }
        },
      )
    }

    composable<MemoryRoute> {
      val memoryViewModel: MemoryViewModel = viewModel(factory = factory)
      MemoryScreen(
        viewModel = memoryViewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }
  }
}
