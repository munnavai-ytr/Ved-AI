package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.VedBrandMark
import kotlinx.coroutines.launch

/**
 * First-launch onboarding experience (3 concise slides with skip option).
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit, modifier: Modifier = Modifier) {
  val pagerState = rememberPagerState(pageCount = { 3 })
  val scope = rememberCoroutineScope()
  val haptic = LocalHapticFeedback.current

  Surface(
    color = MaterialTheme.colorScheme.background,
    modifier = modifier.fillMaxSize(),
  ) {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .safeDrawingPadding()
          .padding(horizontal = 24.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Top Skip action
      Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        TextButton(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onFinish()
          },
          modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding_skip_button"),
        ) {
          Text(
            text = stringResource(R.string.onboarding_skip),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      HorizontalPager(
        state = pagerState,
        modifier = Modifier.weight(1f).fillMaxWidth().widthIn(max = 520.dp),
      ) { page ->
        OnboardingSlide(page = page)
      }

      // Indicators + primary CTA
      Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          repeat(3) { index ->
            val selected = pagerState.currentPage == index
            Box(
              modifier =
                Modifier.height(8.dp)
                  .width(if (selected) 24.dp else 8.dp)
                  .clip(CircleShape)
                  .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                  )
            )
          }
        }

        val isLast = pagerState.currentPage == 2
        Button(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (isLast) {
              onFinish()
            } else {
              scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            }
          },
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding_next_button"),
        ) {
          Text(
            text =
              if (isLast) stringResource(R.string.onboarding_get_started)
              else stringResource(R.string.onboarding_next)
          )
        }
      }
    }
  }
}

@Composable
private fun OnboardingSlide(page: Int) {
  val title =
    when (page) {
      0 -> stringResource(R.string.onboarding_slide_1_title)
      1 -> stringResource(R.string.onboarding_slide_2_title)
      else -> stringResource(R.string.onboarding_slide_3_title)
    }
  val body =
    when (page) {
      0 -> stringResource(R.string.onboarding_slide_1_body)
      1 -> stringResource(R.string.onboarding_slide_2_body)
      else -> stringResource(R.string.onboarding_slide_3_body)
    }

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
      modifier = Modifier.size(96.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        when (page) {
          0 -> VedBrandMark(modifier = Modifier.size(52.dp))
          1 ->
            Icon(
              imageVector = Icons.Rounded.Hub,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(44.dp),
            )
          else ->
            Icon(
              imageVector = Icons.Rounded.Lock,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(44.dp),
            )
        }
      }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Text(
      text = title,
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onBackground,
      textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(14.dp))

    Text(
      text = body,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}
