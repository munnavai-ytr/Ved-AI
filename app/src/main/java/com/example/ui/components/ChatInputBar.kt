package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * Fixed, elevated bottom composer bar with Send <-> Stop morphing action button.
 */
@Composable
fun ChatInputBar(
  text: String,
  onTextChange: (String) -> Unit,
  isGenerating: Boolean,
  onSend: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current
  val canSend = text.isNotBlank()

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 14.dp, vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 4.dp,
      shadowElevation = 6.dp,
      modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
    ) {
      Row(
        modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        Box(
          modifier = Modifier.weight(1f).padding(vertical = 10.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          if (text.isEmpty()) {
            Text(
              text = stringResource(R.string.chat_input_placeholder),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle =
              MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
              ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 5,
            keyboardOptions =
              KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().testTag("chat_input_field"),
          )
        }

        Spacer(modifier = Modifier.width(8.dp))

        FilledIconButton(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (isGenerating) onStop() else onSend()
          },
          enabled = isGenerating || canSend,
          shape = CircleShape,
          colors =
            IconButtonDefaults.filledIconButtonColors(
              containerColor =
                if (isGenerating) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primary,
              contentColor =
                if (isGenerating) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onPrimary,
              disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            ),
          modifier = Modifier.size(48.dp).testTag("send_or_stop_button"),
        ) {
          AnimatedContent(
            targetState = isGenerating,
            transitionSpec = {
              (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.85f)) togetherWith
                (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f))
            },
            label = "send_stop_morph",
          ) { streaming ->
            if (streaming) {
              Icon(
                imageVector = Icons.Rounded.Stop,
                contentDescription = stringResource(R.string.cd_stop_generation),
                modifier = Modifier.size(22.dp),
              )
            } else {
              Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(R.string.cd_send_message),
                modifier = Modifier.size(22.dp),
              )
            }
          }
        }
      }
    }
  }
}
