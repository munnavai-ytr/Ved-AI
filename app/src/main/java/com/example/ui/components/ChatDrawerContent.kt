package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.entity.ConversationEntity
import com.example.data.model.AiProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val drawerDateFormat =
  object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
      SimpleDateFormat("MMM d", Locale.getDefault())
  }

/**
 * Navigation drawer listing saved local Room conversations with swipe-to-delete.
 */
@Composable
fun ChatDrawerContent(
  conversations: List<ConversationEntity>,
  activeConversationId: String?,
  onSelectConversation: (String) -> Unit,
  onDeleteConversation: (String) -> Unit,
  onNewChat: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current

  ModalDrawerSheet(
    modifier = modifier.width(308.dp),
    drawerContainerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          VedBrandMark(modifier = Modifier.size(28.dp))
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }

      // New Chat button
      Button(
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          onNewChat()
        },
        shape = RoundedCornerShape(16.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 48.dp)
            .testTag("drawer_new_chat_button"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Add,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = stringResource(R.string.chat_new))
      }

      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = stringResource(R.string.chat_drawer_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
      )

      if (conversations.isEmpty()) {
        Box(
          modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
              imageVector = Icons.Rounded.ChatBubbleOutline,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
              modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = stringResource(R.string.chat_drawer_empty),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.weight(1f),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(
            items = conversations,
            key = { it.id },
            contentType = { "conversation" },
          ) { conversation ->
            val onSelect =
              remember(conversation.id, onSelectConversation) {
                { onSelectConversation(conversation.id) }
              }
            val onDelete =
              remember(conversation.id, onDeleteConversation) {
                {
                  haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  onDeleteConversation(conversation.id)
                }
              }

            DrawerConversationItem(
              conversation = conversation,
              isSelected = conversation.id == activeConversationId,
              onClick = onSelect,
              onDelete = onDelete,
            )
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // Settings Footer
      Surface(
        onClick = onOpenSettings,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = 52.dp)
            .testTag("drawer_settings_button"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = stringResource(R.string.cd_settings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}

@Composable
private fun DrawerConversationItem(
  conversation: ConversationEntity,
  isSelected: Boolean,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  val dismissState =
    rememberSwipeToDismissBoxState(
      confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
          onDelete()
          true
        } else {
          false
        }
      }
    )

  val providerName =
    remember(conversation.provider) { AiProvider.fromId(conversation.provider).displayName }
  val relativeTime =
    remember(conversation.updatedAt) { formatRelativeTimestamp(conversation.updatedAt) }

  SwipeToDismissBox(
    state = dismissState,
    backgroundContent = {
      val bgColor by
        animateColorAsState(
          targetValue =
            if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) {
              MaterialTheme.colorScheme.surface
            } else {
              MaterialTheme.colorScheme.errorContainer
            },
          label = "swipe_delete_bg",
        )
      val alignment =
        if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
          Alignment.CenterStart
        } else {
          Alignment.CenterEnd
        }

      Box(
        modifier =
          Modifier.fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(horizontal = 18.dp),
        contentAlignment = alignment,
      ) {
        Icon(
          imageVector = Icons.Outlined.DeleteOutline,
          contentDescription = stringResource(R.string.cd_delete_chat),
          tint = MaterialTheme.colorScheme.error,
        )
      }
    },
  ) {
    val containerColor =
      if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
      else MaterialTheme.colorScheme.surface

    Surface(
      onClick = onClick,
      shape = RoundedCornerShape(16.dp),
      color = containerColor,
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(min = 54.dp)
          .testTag("drawer_conversation_${conversation.id}"),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = conversation.title,
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (isSelected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = providerName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "•",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = relativeTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
      }
    }
  }
}

private fun formatRelativeTimestamp(timestampMs: Long): String {
  val diff = System.currentTimeMillis() - timestampMs
  return when {
    diff < 60_000L -> "Just now"
    diff < 3_600_000L -> "${diff / 60_000L}m ago"
    diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
    else -> drawerDateFormat.get()?.format(Date(timestampMs)).orEmpty()
  }
}
