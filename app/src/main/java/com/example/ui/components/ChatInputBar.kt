package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.util.ImageProcessor

/**
 * Fixed, elevated bottom composer bar with optional editing banner, image attachment preview,
 * Photo Picker trigger, and Send <-> Stop morphing action button.
 */
@Composable
fun ChatInputBar(
  text: String,
  onTextChange: (String) -> Unit,
  isGenerating: Boolean,
  onSend: () -> Unit,
  onStop: () -> Unit,
  isEditing: Boolean = false,
  onCancelEdit: () -> Unit = {},
  pendingImageUri: String? = null,
  isGeminiActive: Boolean = true,
  onAttachImageClick: () -> Unit = {},
  onRemoveImage: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current
  val canSend = text.isNotBlank() || pendingImageUri != null

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
      Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = isEditing) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                  .padding(start = 16.dp, end = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.chat_editing_banner),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
              )
              IconButton(
                onClick = {
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  onCancelEdit()
                },
                modifier = Modifier.size(48.dp).testTag("cancel_edit_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = stringResource(R.string.cd_cancel_edit),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          }
        }

        AnimatedVisibility(visible = !pendingImageUri.isNullOrBlank()) {
          if (!pendingImageUri.isNullOrBlank()) {
            PendingImagePreview(
              uriString = pendingImageUri,
              onRemove = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onRemoveImage()
              },
            )
          }
        }

        Row(
          modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
          verticalAlignment = Alignment.Bottom,
        ) {
          IconButton(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onAttachImageClick()
            },
            enabled = !isGenerating,
            modifier = Modifier.size(48.dp).testTag("attach_image_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.AddPhotoAlternate,
              contentDescription = stringResource(R.string.cd_attach_image),
              tint =
                if (isGeminiActive && !isGenerating) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
              modifier = Modifier.size(22.dp),
            )
          }

          Box(
            modifier = Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 4.dp),
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

          Spacer(modifier = Modifier.width(6.dp))

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
}

@Composable
private fun PendingImagePreview(
  uriString: String,
  onRemove: () -> Unit,
) {
  val context = LocalContext.current
  val bitmap by
    produceState<Bitmap?>(initialValue = null, key1 = uriString) {
      value = ImageProcessor.loadThumbnailBitmap(context, uriString, maxPx = 256)
    }

  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    val loaded = bitmap
    if (loaded != null) {
      Image(
        bitmap = loaded.asImageBitmap(),
        contentDescription = stringResource(R.string.cd_attached_image),
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .testTag("pending_image_preview"),
      )
    } else {
      Box(
        modifier =
          Modifier.size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      )
    }

    IconButton(
      onClick = onRemove,
      modifier = Modifier.size(48.dp).testTag("remove_attached_image_button"),
    ) {
      Icon(
        imageVector = Icons.Rounded.Close,
        contentDescription = stringResource(R.string.cd_remove_image),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
