package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.model.ThemeMode

@Immutable
data class VedExtendedColors(
  val userBubble: Color,
  val onUserBubble: Color,
  val codeBackground: Color,
)

val LocalVedColors =
  staticCompositionLocalOf {
    VedExtendedColors(
      userBubble = VedLightUserBubble,
      onUserBubble = VedLightOnUserBubble,
      codeBackground = VedLightCodeBackground,
    )
  }

private val LightColorScheme =
  lightColorScheme(
    primary = VedLightPrimary,
    onPrimary = VedLightOnPrimary,
    primaryContainer = VedLightPrimaryContainer,
    onPrimaryContainer = VedLightOnPrimaryContainer,
    secondary = VedLightPrimary,
    onSecondary = VedLightOnPrimary,
    secondaryContainer = VedLightSurfaceVariant,
    onSecondaryContainer = VedLightOnSurface,
    background = VedLightBackground,
    onBackground = VedLightOnBackground,
    surface = VedLightSurface,
    onSurface = VedLightOnSurface,
    surfaceVariant = VedLightSurfaceVariant,
    onSurfaceVariant = VedLightOnSurfaceVariant,
    surfaceContainerLow = VedLightSurfaceContainer,
    surfaceContainer = VedLightSurfaceContainer,
    surfaceContainerHigh = VedLightSurfaceContainerHigh,
    outline = VedLightOutline,
    outlineVariant = VedLightOutlineVariant,
    error = VedLightError,
    errorContainer = VedLightErrorContainer,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = VedDarkPrimary,
    onPrimary = VedDarkOnPrimary,
    primaryContainer = VedDarkPrimaryContainer,
    onPrimaryContainer = VedDarkOnPrimaryContainer,
    secondary = VedDarkPrimary,
    onSecondary = VedDarkOnPrimary,
    secondaryContainer = VedDarkSurfaceVariant,
    onSecondaryContainer = VedDarkOnSurface,
    background = VedDarkBackground,
    onBackground = VedDarkOnBackground,
    surface = VedDarkSurface,
    onSurface = VedDarkOnSurface,
    surfaceVariant = VedDarkSurfaceVariant,
    onSurfaceVariant = VedDarkOnSurfaceVariant,
    surfaceContainerLow = VedDarkSurfaceContainer,
    surfaceContainer = VedDarkSurfaceContainer,
    surfaceContainerHigh = VedDarkSurfaceContainerHigh,
    outline = VedDarkOutline,
    outlineVariant = VedDarkOutlineVariant,
    error = VedDarkError,
    errorContainer = VedDarkErrorContainer,
  )

private val VedShapes =
  Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
  )

@Composable
fun VedTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val isDark =
    when (themeMode) {
      ThemeMode.SYSTEM -> isSystemInDarkTheme()
      ThemeMode.LIGHT -> false
      ThemeMode.DARK -> true
    }

  val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
  val extendedColors =
    if (isDark) {
      VedExtendedColors(
        userBubble = VedDarkUserBubble,
        onUserBubble = VedDarkOnUserBubble,
        codeBackground = VedDarkCodeBackground,
      )
    } else {
      VedExtendedColors(
        userBubble = VedLightUserBubble,
        onUserBubble = VedLightOnUserBubble,
        codeBackground = VedLightCodeBackground,
      )
    }

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window
      if (window != null) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
      }
    }
  }

  CompositionLocalProvider(LocalVedColors provides extendedColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = VedTypography,
      shapes = VedShapes,
      content = content,
    )
  }
}
