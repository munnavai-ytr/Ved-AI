package com.example.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SkillEditorScreen
import com.example.ui.screens.SkillsScreen
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.MemoryViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.SkillsViewModel
import com.example.ui.viewmodel.VedViewModelFactory
import kotlinx.serialization.Serializable

@Serializable data object OnboardingRoute

@Serializable data object ChatRoute

@Serializable data object SettingsRoute

@Serializable data object MemoryRoute

@Serializable data object SkillsRoute

@Serializable data class SkillEditorRoute(val skillId: String? = null, val isImportedDraft: Boolean = false)

/**
 * Type-safe Compose Navigation graph for Ved supporting deep-linking from completion
 * notifications directly into the target conversation.
 */
@Composable
fun VedNavHost(
  startWithOnboarding: Boolean,
  factory: VedViewModelFactory,
  onCompleteOnboarding: () -> Unit,
  deepLinkConversationId: String? = null,
  onConsumeDeepLink: () -> Unit = {},
) {
  val navController = rememberNavController()
  val startDestination: Any = if (startWithOnboarding) OnboardingRoute else ChatRoute

  LaunchedEffect(deepLinkConversationId) {
    if (!deepLinkConversationId.isNullOrBlank()) {
      navController.navigate(ChatRoute) {
        popUpTo<ChatRoute> { inclusive = false }
        launchSingleTop = true
      }
    }
  }

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
      LaunchedEffect(deepLinkConversationId) {
        if (!deepLinkConversationId.isNullOrBlank()) {
          chatViewModel.selectConversation(deepLinkConversationId)
          onConsumeDeepLink()
        }
      }
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
        onOpenSkills = {
          navController.navigate(SkillsRoute) {
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

    composable<SkillsRoute> { backStackEntry ->
      val skillsViewModel: SkillsViewModel = viewModel(backStackEntry, factory = factory)
      SkillsScreen(
        viewModel = skillsViewModel,
        onNavigateBack = { navController.popBackStack() },
        onCreateSkill = {
          skillsViewModel.startNewDraft()
          navController.navigate(SkillEditorRoute(skillId = null, isImportedDraft = false)) {
            launchSingleTop = true
          }
        },
        onEditSkill = { skillId ->
          skillsViewModel.loadSkillForEditing(skillId)
          navController.navigate(SkillEditorRoute(skillId = skillId, isImportedDraft = false)) {
            launchSingleTop = true
          }
        },
        onOpenImportedDraft = {
          navController.navigate(SkillEditorRoute(skillId = null, isImportedDraft = true)) {
            launchSingleTop = true
          }
        },
      )
    }

    composable<SkillEditorRoute> { backStackEntry ->
      val route = backStackEntry.toRoute<SkillEditorRoute>()
      // Share SkillsViewModel scoped to SkillsRoute so drafts stay in sync seamlessly
      val skillsBackStack =
        try {
          navController.getBackStackEntry<SkillsRoute>()
        } catch (_: IllegalArgumentException) {
          backStackEntry
        }
      val skillsViewModel: SkillsViewModel = viewModel(skillsBackStack, factory = factory)
      SkillEditorScreen(
        viewModel = skillsViewModel,
        skillId = route.skillId,
        isImportedDraft = route.isImportedDraft,
        onNavigateBack = { navController.popBackStack() },
      )
    }
  }
}
