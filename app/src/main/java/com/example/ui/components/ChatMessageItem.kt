package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.entity.MessageEntity
import com.example.data.util.ImageProcessor
import com.example.ui.theme.LocalVedColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow-scope wrapper for the currently streaming assistant message so collecting
 * [streamingTextFlow] only recomposes this single item rather than the parent LazyColumn.
 */
@Composable
fun StreamingChatMessageItem(
  message: MessageEntity,
  streamingTextFlow: StateFlow<String>,
  isLastAssistantMessage: Boolean,
  onRegenerate: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val streamedText by streamingTextFlow.collectAsStateWithLifecycle()
  ChatMessageItem(
    message = message,
    displayContent = streamedText,
    isStreaming = true,
    isLastAssistantMessage = isLastAssistantMessage,
    onRegenerate = onRegenerate,
    modifier = modifier,
  )
}

/**
 * Renders a single user or assistant chat turn with markdown, image thumbnails, edit, copy,
 * regenerate, and token usage controls.
 */
@Composable
fun ChatMessageItem(
  message: MessageEntity,
  displayContent: String,
  isStreaming: Boolean,
  isLastAssistantMessage: Boolean,
  onRegenerate: () -> Unit,
  canEdit: Boolean = true,
  onEditMessage: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  if (message.role == MessageEntity.ROLE_USER) {
    UserMessageBubble(
      content = displayContent,
      imageUri = message.imageUri,
      canEdit = canEdit,
      onEdit = onEditMessage,
      modifier = modifier,
    )
  } else {
    AssistantMessageBlock(
      content = displayContent,
      modelId = message.modelId,
      isError = message.isError,
      isStreaming = isStreaming,
      canRegenerate = isLastAssistantMessage && !isStreaming,
      completionTokens = message.completionTokens,
      onRegenerate = onRegenerate,
      modifier = modifier,
    )
  }
}

@Composable
private fun UserMessageBubble(
  content: String,
  imageUri: String?,
  canEdit: Boolean,
  onEdit: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val clipboard = LocalClipboardManager.current
  val haptic = LocalHapticFeedback.current
  val vedColors = LocalVedColors.current

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.End,
  ) {
    Surface(
      onClick = {
        if (content.isNotBlank()) {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          clipboard.setText(AnnotatedString(content))
        }
      },
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
      color = vedColors.userBubble,
      modifier = Modifier.widthIn(max = 310.dp),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (!imageUri.isNullOrBlank()) {
          MessageImageThumbnail(uriString = imageUri)
        }
        if (content.isNotBlank()) {
          Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            color = vedColors.onUserBubble,
          )
        }
      }
    }

    if (onEdit != null) {
      IconButton(
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          onEdit()
        },
        enabled = canEdit,
        modifier = Modifier.size(48.dp).testTag("edit_message_button"),
      ) {
        Icon(
          imageVector = Icons.Outlined.Edit,
          contentDescription = stringResource(R.string.cd_edit_message),
          tint =
            if (canEdit) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
          modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}

@Composable
private fun MessageImageThumbnail(uriString: String) {
  val context = LocalContext.current
  val bitmap by
    produceState<Bitmap?>(initialValue = null, key1 = uriString) {
      value = ImageProcessor.loadThumbnailBitmap(context, uriString, maxPx = 640)
    }

  val loaded = bitmap
  if (loaded != null) {
    Image(
      bitmap = loaded.asImageBitmap(),
      contentDescription = stringResource(R.string.cd_attached_image),
      contentScale = ContentScale.Crop,
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(max = 220.dp)
          .clip(RoundedCornerShape(12.dp))
          .testTag("user_message_image"),
    )
  }
}

@Composable
private fun AssistantMessageBlock(
  content: String,
  modelId: String?,
  isError: Boolean,
  isStreaming: Boolean,
  canRegenerate: Boolean,
  completionTokens: Int?,
  onRegenerate: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val clipboard = LocalClipboardManager.current
  val haptic = LocalHapticFeedback.current
  var copied by remember { mutableStateOf(false) }
  val modelDisplayName = remember(modelId) { modelId?.substringAfterLast('/') }

  LaunchedEffect(copied) {
    if (copied) {
      delay(1600)
      copied = false
    }
  }

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Subtle speaker header
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      VedBrandMark(modifier = Modifier.size(18.dp))
      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (!modelDisplayName.isNullOrBlank()) {
        Text(
          text = modelDisplayName,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // Body
    if (content.isBlank() && isStreaming) {
      Text(
        text = "Thinking…",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else if (isError) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = content,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(14.dp),
        )
      }
    } else {
      MarkdownText(
        markdown = content,
        isStreaming = isStreaming,
        textColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    // Message actions & token usage indicator
    if (!isStreaming && content.isNotBlank()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        TextButton(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            clipboard.setText(AnnotatedString(content))
            copied = true
          },
          modifier = Modifier.heightIn(min = 48.dp).testTag("copy_message_button"),
        ) {
          Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = stringResource(R.string.cd_copy_message),
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text =
              if (copied) stringResource(R.string.action_copied)
              else stringResource(R.string.action_copy),
            style = MaterialTheme.typography.labelMedium,
          )
        }

        if (canRegenerate) {
          TextButton(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onRegenerate()
            },
            modifier = Modifier.heightIn(min = 48.dp).testTag("regenerate_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Refresh,
              contentDescription = stringResource(R.string.cd_regenerate),
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = stringResource(R.string.action_regenerate),
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }

        if (completionTokens != null && completionTokens > 0) {
          Spacer(modifier = Modifier.weight(1f))
          Text(
            text = stringResource(R.string.message_tokens_count, completionTokens),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(end = 4.dp).testTag("message_token_count"),
          )
        }
      }
    }
  }
}
