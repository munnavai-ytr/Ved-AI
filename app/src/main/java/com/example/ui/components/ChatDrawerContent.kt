package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.data.local.entity.FolderEntity
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
 * Navigation drawer listing saved local Room conversations grouped into Pinned, collapsible
 * user-defined Folders, and Recent chats, with debounced search, long-press context menu
 * (Pin/Unpin, Move to folder, Delete), and swipe-to-delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDrawerContent(
  conversations: List<ConversationEntity>,
  activeConversationId: String?,
  searchQuery: String = "",
  onSearchQueryChange: (String) -> Unit = {},
  onClearSearch: () -> Unit = {},
  onSelectConversation: (String) -> Unit,
  onDeleteConversation: (String) -> Unit,
  onNewChat: () -> Unit,
  onOpenSettings: () -> Unit,
  folders: List<FolderEntity> = emptyList(),
  onTogglePinConversation: (String, Boolean) -> Unit = { _, _ -> },
  onMoveConversationToFolder: (String, String?) -> Unit = { _, _ -> },
  onCreateFolder: (String, String?) -> Unit = { _, _ -> },
  onDeleteFolder: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current
  var isSearchExpanded by remember { mutableStateOf(false) }
  var showCreateFolderDialog by remember { mutableStateOf(false) }
  var pendingAssignConversationId by remember { mutableStateOf<String?>(null) }
  var moveFolderTargetConversation by remember { mutableStateOf<ConversationEntity?>(null) }
  var collapsedFolderIds by remember { mutableStateOf<Set<String>>(emptySet()) }

  val folderIds = remember(folders) { folders.map { it.id }.toSet() }
  val isSearching = searchQuery.isNotBlank()

  val pinnedConversations =
    remember(conversations) { conversations.filter { it.isPinned } }
  val unpinnedConversations =
    remember(conversations) { conversations.filter { !it.isPinned } }
  val recentConversations =
    remember(unpinnedConversations, folderIds, isSearching) {
      if (isSearching) unpinnedConversations
      else unpinnedConversations.filter { it.folderId == null || it.folderId !in folderIds }
    }

  ModalDrawerSheet(
    modifier = modifier.width(308.dp),
    drawerContainerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()
    ) {
      // Header
      Row(
        modifier =
          Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
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

        IconButton(
          onClick = {
            if (isSearchExpanded || searchQuery.isNotEmpty()) {
              isSearchExpanded = false
              onClearSearch()
            } else {
              isSearchExpanded = true
            }
          },
          modifier = Modifier.size(48.dp).testTag("drawer_search_toggle"),
        ) {
          Icon(
            imageVector =
              if (isSearchExpanded || searchQuery.isNotEmpty()) Icons.Rounded.Close
              else Icons.Rounded.Search,
            contentDescription = stringResource(R.string.cd_search_chats),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      AnimatedVisibility(visible = isSearchExpanded || searchQuery.isNotEmpty()) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = onSearchQueryChange,
          placeholder = {
            Text(
              text = stringResource(R.string.chat_drawer_search_placeholder),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          leadingIcon = {
            Icon(
              imageVector = Icons.Rounded.Search,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
            )
          },
          trailingIcon = {
            if (searchQuery.isNotEmpty()) {
              IconButton(
                onClick = onClearSearch,
                modifier = Modifier.size(48.dp).testTag("drawer_search_clear"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = stringResource(R.string.cd_clear_search),
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          modifier =
            Modifier.fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 12.dp)
              .testTag("drawer_search_input"),
        )
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

      Spacer(modifier = Modifier.height(12.dp))

      // Section Header + Create Folder
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.chat_drawer_title),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            pendingAssignConversationId = null
            showCreateFolderDialog = true
          },
          modifier = Modifier.size(48.dp).testTag("drawer_new_folder_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.CreateNewFolder,
            contentDescription = stringResource(R.string.cd_create_folder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
        }
      }

      if (conversations.isEmpty() && (folders.isEmpty() || isSearching)) {
        Box(
          modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
              imageVector =
                if (isSearching) Icons.Rounded.Search
                else Icons.Rounded.ChatBubbleOutline,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
              modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text =
                if (isSearching) stringResource(R.string.chat_drawer_search_empty)
                else stringResource(R.string.chat_drawer_empty),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.weight(1f).testTag("drawer_conversations_list"),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (pinnedConversations.isNotEmpty()) {
            item(key = "section_pinned", contentType = "section_header") {
              DrawerSectionLabel(
                text = stringResource(R.string.chat_drawer_pinned),
                modifier = Modifier.testTag("drawer_pinned_section"),
              )
            }
            items(
              items = pinnedConversations,
              key = { "pinned_${it.id}" },
              contentType = { "conversation" },
            ) { conversation ->
              DrawerConversationRow(
                conversation = conversation,
                isSelected = conversation.id == activeConversationId,
                onSelectConversation = onSelectConversation,
                onDeleteConversation = onDeleteConversation,
                onTogglePinConversation = onTogglePinConversation,
                onRequestMoveFolder = { moveFolderTargetConversation = conversation },
              )
            }
          }

          if (!isSearching && folders.isNotEmpty()) {
            folders.forEach { folder ->
              val folderConvs = unpinnedConversations.filter { it.folderId == folder.id }
              val isExpanded = folder.id !in collapsedFolderIds
              item(key = "folder_${folder.id}", contentType = "folder_header") {
                DrawerFolderHeader(
                  folder = folder,
                  count = folderConvs.size,
                  isExpanded = isExpanded,
                  onToggle = {
                    collapsedFolderIds =
                      if (isExpanded) collapsedFolderIds + folder.id
                      else collapsedFolderIds - folder.id
                  },
                )
              }
              if (isExpanded && folderConvs.isNotEmpty()) {
                items(
                  items = folderConvs,
                  key = { "folder_${folder.id}_${it.id}" },
                  contentType = { "conversation" },
                ) { conversation ->
                  DrawerConversationRow(
                    conversation = conversation,
                    isSelected = conversation.id == activeConversationId,
                    onSelectConversation = onSelectConversation,
                    onDeleteConversation = onDeleteConversation,
                    onTogglePinConversation = onTogglePinConversation,
                    onRequestMoveFolder = { moveFolderTargetConversation = conversation },
                    indentForFolder = true,
                  )
                }
              }
            }
          }

          if (recentConversations.isNotEmpty()) {
            if (!isSearching && (pinnedConversations.isNotEmpty() || folders.isNotEmpty())) {
              item(key = "section_recent", contentType = "section_header") {
                DrawerSectionLabel(
                  text = stringResource(R.string.chat_drawer_recent),
                  modifier = Modifier.testTag("drawer_recent_section"),
                )
              }
            }
            items(
              items = recentConversations,
              key = { it.id },
              contentType = { "conversation" },
            ) { conversation ->
              DrawerConversationRow(
                conversation = conversation,
                isSelected = conversation.id == activeConversationId,
                onSelectConversation = onSelectConversation,
                onDeleteConversation = onDeleteConversation,
                onTogglePinConversation = onTogglePinConversation,
                onRequestMoveFolder = { moveFolderTargetConversation = conversation },
              )
            }
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

  val targetConvForFolder = moveFolderTargetConversation
  if (targetConvForFolder != null) {
    FolderPickerSheet(
      conversation = targetConvForFolder,
      folders = folders,
      onSelectFolder = { folderId ->
        onMoveConversationToFolder(targetConvForFolder.id, folderId)
        moveFolderTargetConversation = null
      },
      onCreateNewFolderClick = {
        pendingAssignConversationId = targetConvForFolder.id
        moveFolderTargetConversation = null
        showCreateFolderDialog = true
      },
      onDeleteFolder = onDeleteFolder,
      onDismiss = { moveFolderTargetConversation = null },
    )
  }

  if (showCreateFolderDialog) {
    CreateFolderDialog(
      onCreate = { name ->
        onCreateFolder(name, pendingAssignConversationId)
        pendingAssignConversationId = null
        showCreateFolderDialog = false
      },
      onDismiss = {
        pendingAssignConversationId = null
        showCreateFolderDialog = false
      },
    )
  }
}

@Composable
private fun DrawerSectionLabel(
  text: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
  )
}

@Composable
private fun DrawerFolderHeader(
  folder: FolderEntity,
  count: Int,
  isExpanded: Boolean,
  onToggle: () -> Unit,
) {
  Surface(
    onClick = onToggle,
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = 48.dp)
        .testTag("drawer_folder_header_${folder.id}"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.Folder,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
      )
      Text(
        text = folder.name,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Icon(
        imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

@Composable
private fun DrawerConversationRow(
  conversation: ConversationEntity,
  isSelected: Boolean,
  onSelectConversation: (String) -> Unit,
  onDeleteConversation: (String) -> Unit,
  onTogglePinConversation: (String, Boolean) -> Unit,
  onRequestMoveFolder: () -> Unit,
  indentForFolder: Boolean = false,
) {
  val haptic = LocalHapticFeedback.current
  val onSelect =
    remember(conversation.id, onSelectConversation) { { onSelectConversation(conversation.id) } }
  val onDelete =
    remember(conversation.id, onDeleteConversation) {
      {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onDeleteConversation(conversation.id)
      }
    }
  val onTogglePin =
    remember(conversation.id, conversation.isPinned, onTogglePinConversation) {
      { onTogglePinConversation(conversation.id, conversation.isPinned) }
    }

  DrawerConversationItem(
    conversation = conversation,
    isSelected = isSelected,
    indentForFolder = indentForFolder,
    onClick = onSelect,
    onDelete = onDelete,
    onTogglePin = onTogglePin,
    onMoveToFolder = onRequestMoveFolder,
  )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerConversationItem(
  conversation: ConversationEntity,
  isSelected: Boolean,
  indentForFolder: Boolean,
  onClick: () -> Unit,
  onDelete: () -> Unit,
  onTogglePin: () -> Unit,
  onMoveToFolder: () -> Unit,
) {
  val haptic = LocalHapticFeedback.current
  var showMenu by remember { mutableStateOf(false) }

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

    Box(modifier = Modifier.fillMaxWidth().padding(start = if (indentForFolder) 10.dp else 0.dp)) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
              onClick = onClick,
              onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showMenu = true
              },
            )
            .testTag("drawer_conversation_${conversation.id}"),
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            if (conversation.branchedFromConversationId != null) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.CallSplit,
                contentDescription = stringResource(R.string.cd_branched_conversation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp).testTag("branched_conversation_icon"),
              )
            }
            Text(
              text = conversation.title,
              style = MaterialTheme.typography.bodyMedium,
              color =
                if (isSelected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            if (conversation.isPinned) {
              Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = stringResource(R.string.cd_pin_conversation),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp).testTag("pinned_conversation_icon"),
              )
            }
          }
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

      DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false },
        modifier = Modifier.testTag("drawer_conversation_menu_${conversation.id}"),
      ) {
        DropdownMenuItem(
          text = {
            Text(
              text =
                if (conversation.isPinned) stringResource(R.string.chat_drawer_unpin)
                else stringResource(R.string.chat_drawer_pin)
            )
          },
          leadingIcon = { Icon(imageVector = Icons.Rounded.PushPin, contentDescription = null) },
          onClick = {
            showMenu = false
            onTogglePin()
          },
          modifier = Modifier.testTag("drawer_option_pin"),
        )
        DropdownMenuItem(
          text = { Text(stringResource(R.string.chat_drawer_move_folder)) },
          leadingIcon = {
            Icon(imageVector = Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null)
          },
          onClick = {
            showMenu = false
            onMoveToFolder()
          },
          modifier = Modifier.testTag("drawer_option_move_folder"),
        )
        DropdownMenuItem(
          text = { Text(stringResource(R.string.chat_drawer_delete)) },
          leadingIcon = {
            Icon(
              imageVector = Icons.Outlined.DeleteOutline,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
            )
          },
          onClick = {
            showMenu = false
            onDelete()
          },
          modifier = Modifier.testTag("drawer_option_delete"),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerSheet(
  conversation: ConversationEntity,
  folders: List<FolderEntity>,
  onSelectFolder: (String?) -> Unit,
  onCreateNewFolderClick: () -> Unit,
  onDeleteFolder: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 16.dp)
          .padding(bottom = 24.dp)
          .testTag("folder_picker_sheet"),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.chat_folder_picker_title),
        style = MaterialTheme.typography.titleLarge,
      )

      Surface(
        onClick = { onSelectFolder(null) },
        shape = RoundedCornerShape(14.dp),
        color =
          if (conversation.folderId == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
          else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("folder_option_unfiled"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.chat_folder_unfiled),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
          )
          if (conversation.folderId == null) {
            Icon(
              imageVector = Icons.Rounded.Check,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }

      folders.forEach { folder ->
        val isSelected = conversation.folderId == folder.id
        Surface(
          onClick = { onSelectFolder(folder.id) },
          shape = RoundedCornerShape(14.dp),
          color =
            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceContainer,
          modifier =
            Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("folder_option_${folder.id}"),
        ) {
          Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Outlined.Folder,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = folder.name,
              style = MaterialTheme.typography.titleSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            if (isSelected) {
              Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            IconButton(
              onClick = { onDeleteFolder(folder.id) },
              modifier = Modifier.size(48.dp).testTag("delete_folder_${folder.id}"),
            ) {
              Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.cd_delete_folder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
      }

      Surface(
        onClick = onCreateNewFolderClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("folder_option_new"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.CreateNewFolder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = stringResource(R.string.chat_folder_new_option),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
  }
}

@Composable
private fun CreateFolderDialog(
  onCreate: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var folderName by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.chat_folder_create_title)) },
    text = {
      OutlinedTextField(
        value = folderName,
        onValueChange = { folderName = it },
        label = { Text(stringResource(R.string.chat_folder_name_label)) },
        placeholder = { Text(stringResource(R.string.chat_folder_name_placeholder)) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("new_folder_name_input"),
      )
    },
    confirmButton = {
      TextButton(
        onClick = { onCreate(folderName) },
        enabled = folderName.isNotBlank(),
        modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_create_folder_button"),
      ) {
        Text(stringResource(R.string.chat_folder_create_action))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
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
