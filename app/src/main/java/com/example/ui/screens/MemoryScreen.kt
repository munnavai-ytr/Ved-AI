package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.entity.MemoryEntity
import com.example.ui.viewmodel.MemoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val memoryDateFormat =
  object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
      SimpleDateFormat("MMM d", Locale.getDefault())
  }

/**
 * Dedicated screen for viewing, searching, adding, inline-editing, and clearing Ved's
 * durable cross-conversation long-term memories.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
  viewModel: MemoryViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val haptic = LocalHapticFeedback.current

  var showAddDialog by remember { mutableStateOf(false) }
  var showClearConfirmDialog by remember { mutableStateOf(false) }
  var editingMemoryId by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(uiState.feedbackMessage) {
    val msg = uiState.feedbackMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearFeedback()
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.testTag("memory_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Text(
            text = stringResource(R.string.memory_title),
            style = MaterialTheme.typography.titleLarge,
          )
        },
        actions = {
          if (uiState.totalCount > 0) {
            TextButton(
              onClick = { showClearConfirmDialog = true },
              modifier = Modifier.heightIn(min = 48.dp).testTag("clear_all_memories_button"),
            ) {
              Text(
                text = stringResource(R.string.memory_clear_all),
                color = MaterialTheme.colorScheme.error,
              )
            }
          }
        },
      )
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          showAddDialog = true
        },
        icon = {
          Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
          )
        },
        text = { Text(text = stringResource(R.string.memory_add_button)) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.navigationBarsPadding().testTag("add_memory_button"),
      )
    },
    modifier = modifier,
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.fillMaxSize().widthIn(max = 640.dp).padding(horizontal = 16.dp),
      ) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = viewModel::onSearchQueryChanged,
          placeholder = {
            Text(
              text = stringResource(R.string.memory_search_placeholder),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          leadingIcon = {
            Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
          },
          trailingIcon = {
            if (searchQuery.isNotEmpty()) {
              IconButton(
                onClick = viewModel::clearSearch,
                modifier = Modifier.size(48.dp).testTag("memory_search_clear"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = stringResource(R.string.cd_clear_search),
                )
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          modifier =
            Modifier.fillMaxWidth()
              .padding(top = 8.dp, bottom = 12.dp)
              .testTag("memory_search_input"),
        )

        if (uiState.memories.isEmpty()) {
          MemoryEmptyState(
            isSearching = searchQuery.isNotBlank(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
          )
        } else {
          LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(
              items = uiState.memories,
              key = { it.id },
              contentType = { "memory" },
            ) { memory ->
              val isEditing = editingMemoryId == memory.id
              MemoryListItem(
                memory = memory,
                isEditing = isEditing,
                onStartEdit = { editingMemoryId = memory.id },
                onCancelEdit = { editingMemoryId = null },
                onSaveEdit = { updatedContent ->
                  viewModel.updateMemory(memory.id, updatedContent)
                  editingMemoryId = null
                },
                onDelete = {
                  haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  if (editingMemoryId == memory.id) {
                    editingMemoryId = null
                  }
                  viewModel.deleteMemory(memory.id)
                },
              )
            }
          }
        }
      }
    }
  }

  if (showAddDialog) {
    AddMemoryDialog(
      onDismiss = { showAddDialog = false },
      onConfirm = { content, category ->
        viewModel.addMemory(content, category)
        showAddDialog = false
      },
    )
  }

  if (showClearConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showClearConfirmDialog = false },
      title = { Text(text = stringResource(R.string.memory_clear_confirm_title)) },
      text = { Text(text = stringResource(R.string.memory_clear_confirm_body)) },
      confirmButton = {
        Button(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.clearAllMemories()
            showClearConfirmDialog = false
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
          modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_clear_memories_button"),
        ) {
          Text(text = stringResource(R.string.memory_clear_confirm_action))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showClearConfirmDialog = false },
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text(text = stringResource(R.string.action_cancel))
        }
      },
    )
  }
}

@Composable
private fun MemoryListItem(
  memory: MemoryEntity,
  isEditing: Boolean,
  onStartEdit: () -> Unit,
  onCancelEdit: () -> Unit,
  onSaveEdit: (String) -> Unit,
  onDelete: () -> Unit,
) {
  val dismissState =
    rememberSwipeToDismissBoxState(
      confirmValueChange = { value ->
        if (
          !isEditing &&
            (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd)
        ) {
          onDelete()
          true
        } else {
          false
        }
      }
    )

  val relativeTime = remember(memory.updatedAt) { formatRelativeMemoryTime(memory.updatedAt) }
  val isAiSource = memory.source == MemoryEntity.SOURCE_AI

  SwipeToDismissBox(
    state = dismissState,
    gesturesEnabled = !isEditing,
    backgroundContent = {
      val bgColor by
        animateColorAsState(
          targetValue =
            if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) {
              MaterialTheme.colorScheme.background
            } else {
              MaterialTheme.colorScheme.errorContainer
            },
          label = "memory_swipe_delete_bg",
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
            .clip(RoundedCornerShape(18.dp))
            .background(bgColor)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
      ) {
        Icon(
          imageVector = Icons.Outlined.DeleteOutline,
          contentDescription = stringResource(R.string.cd_delete_memory),
          tint = MaterialTheme.colorScheme.error,
        )
      }
    },
  ) {
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 1.dp,
      modifier = Modifier.fillMaxWidth().testTag("memory_item_${memory.id}"),
    ) {
      Column(
        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (isEditing) {
          var editDraft by remember(memory.id, memory.content) { mutableStateOf(memory.content) }
          OutlinedTextField(
            value = editDraft,
            onValueChange = { editDraft = it },
            minLines = 2,
            maxLines = 5,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp).testTag("edit_memory_input"),
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(
              onClick = onCancelEdit,
              modifier = Modifier.size(48.dp).testTag("cancel_edit_memory_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.cd_cancel_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(
              onClick = { onSaveEdit(editDraft) },
              enabled = editDraft.isNotBlank(),
              modifier = Modifier.size(48.dp).testTag("save_edit_memory_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.cd_save_memory),
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          }
        } else {
          Text(
            text = memory.content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 8.dp),
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.weight(1f),
            ) {
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
              ) {
                Text(
                  text = memory.category,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
              }

              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(
                  imageVector =
                    if (isAiSource) Icons.Outlined.AutoAwesome else Icons.Outlined.PersonOutline,
                  contentDescription =
                    if (isAiSource) stringResource(R.string.memory_source_ai)
                    else stringResource(R.string.memory_source_user),
                  tint =
                    if (isAiSource) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  text =
                    if (isAiSource) stringResource(R.string.memory_source_ai)
                    else stringResource(R.string.memory_source_user),
                  style = MaterialTheme.typography.labelSmall,
                  color =
                    if (isAiSource) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              Text(
                text = "• $relativeTime",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
              )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(
                onClick = onStartEdit,
                modifier = Modifier.size(48.dp).testTag("edit_memory_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Edit,
                  contentDescription = stringResource(R.string.cd_edit_memory),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
                )
              }
              IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp).testTag("delete_memory_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.DeleteOutline,
                  contentDescription = stringResource(R.string.cd_delete_memory),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
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
private fun AddMemoryDialog(
  onDismiss: () -> Unit,
  onConfirm: (content: String, category: String) -> Unit,
) {
  var content by remember { mutableStateOf("") }
  var category by remember { mutableStateOf(MemoryEntity.CATEGORY_FACT) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.memory_add_dialog_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = content,
          onValueChange = { content = it },
          label = { Text(stringResource(R.string.memory_fact_label)) },
          placeholder = { Text(stringResource(R.string.memory_fact_placeholder)) },
          minLines = 2,
          maxLines = 5,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().testTag("add_memory_content_input"),
        )

        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          MemoryEntity.DEFAULT_CATEGORIES.forEach { preset ->
            FilterChip(
              selected = category.equals(preset, ignoreCase = true),
              onClick = { category = preset },
              label = { Text(text = preset) },
            )
          }
        }

        OutlinedTextField(
          value = category,
          onValueChange = { category = it },
          label = { Text(stringResource(R.string.memory_category_label)) },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().testTag("add_memory_category_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(content, category) },
        enabled = content.isNotBlank(),
        modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_add_memory_button"),
      ) {
        Text(text = stringResource(R.string.settings_key_save))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text(text = stringResource(R.string.action_cancel))
      }
    },
  )
}

@Composable
private fun MemoryEmptyState(
  isSearching: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.padding(horizontal = 24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.widthIn(max = 360.dp),
    ) {
      Icon(
        imageVector = if (isSearching) Icons.Rounded.Search else Icons.Outlined.Psychology,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.size(40.dp),
      )
      Text(
        text =
          if (isSearching) stringResource(R.string.memory_search_empty_title)
          else stringResource(R.string.memory_empty_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Text(
        text =
          if (isSearching) stringResource(R.string.memory_search_empty_body)
          else stringResource(R.string.memory_empty_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

private fun formatRelativeMemoryTime(timestampMs: Long): String {
  val diff = System.currentTimeMillis() - timestampMs
  return when {
    diff < 60_000L -> "Just now"
    diff < 3_600_000L -> "${diff / 60_000L}m ago"
    diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
    else -> memoryDateFormat.get()?.format(Date(timestampMs)).orEmpty()
  }
}
