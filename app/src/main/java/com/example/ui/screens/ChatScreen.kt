package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.entity.MessageEntity
import com.example.ui.components.ChatDrawerContent
import com.example.ui.components.ChatInputBar
import com.example.ui.components.ChatMessageItem
import com.example.ui.components.StreamingChatMessageItem
import com.example.ui.components.VedBrandMark
import com.example.ui.viewmodel.ChatUiEvent
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

private val SuggestedPrompts =
  listOf(
    "Explain quantum entanglement like I'm curious, not a physicist",
    "Write a concise Kotlin Coroutines & Flow cheat sheet",
    "Help me plan a focused 3-day weekend build project",
    "Compare SQLite indexing strategies for faster chat queries",
  )

/**
 * Primary chat screen with history drawer, isolated streaming markdown messages, suggested
 * prompts, copy/share export, and fixed bottom input bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  viewModel: ChatViewModel,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val inputText by viewModel.inputText.collectAsStateWithLifecycle()

  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val haptic = LocalHapticFeedback.current
  var showShareMenu by remember { mutableStateOf(false) }

  BackHandler(enabled = drawerState.isOpen) {
    scope.launch { drawerState.close() }
  }

  val undoLabel = stringResource(R.string.action_undo)
  val deletedMessage = stringResource(R.string.chat_deleted)
  val shareChooserTitle = stringResource(R.string.chat_share_title)

  LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
      when (event) {
        is ChatUiEvent.ShowMessage -> {
          snackbarHostState.showSnackbar(event.message)
        }
        is ChatUiEvent.CopyText -> {
          clipboard.setText(AnnotatedString(event.text))
          snackbarHostState.showSnackbar(event.feedback)
        }
        is ChatUiEvent.ShareText -> {
          val intent =
            Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              putExtra(Intent.EXTRA_SUBJECT, event.title)
              putExtra(Intent.EXTRA_TEXT, event.text)
            }
          context.startActivity(Intent.createChooser(intent, shareChooserTitle))
        }
        is ChatUiEvent.ConversationDeleted -> {
          val result =
            snackbarHostState.showSnackbar(
              message = deletedMessage,
              actionLabel = undoLabel,
              duration = SnackbarDuration.Short,
            )
          if (result == SnackbarResult.ActionPerformed) {
            viewModel.restoreConversation(event.backup)
          }
        }
      }
    }
  }

  val isNearBottom by remember {
    derivedStateOf {
      val layoutInfo = listState.layoutInfo
      val total = layoutInfo.totalItemsCount
      if (total == 0) return@derivedStateOf true
      val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
      lastVisible.index >= total - 2
    }
  }

  // Smoothly scroll into view when a new message row is added
  LaunchedEffect(uiState.messages.size) {
    if (uiState.messages.isNotEmpty()) {
      listState.animateScrollToItem(uiState.messages.lastIndex)
    }
  }

  // Throttled, layout-aware scroll adjustment during active streaming: runs only while
  // generating, never restarts per token, and only scrolls when the last item actually grows
  // past the bottom viewport edge while the user is near the bottom.
  LaunchedEffect(uiState.isGenerating) {
    if (!uiState.isGenerating) return@LaunchedEffect

    snapshotFlow {
        val layoutInfo = listState.layoutInfo
        val total = layoutInfo.totalItemsCount
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
        if (total == 0 || lastVisible == null) {
          0
        } else if (lastVisible.index == total - 1) {
          (lastVisible.offset + lastVisible.size) - layoutInfo.viewportEndOffset
        } else if (lastVisible.index == total - 2) {
          Int.MAX_VALUE
        } else {
          0
        }
      }
      .conflate()
      .collect { overflowPx ->
        if (isNearBottom && !listState.isScrollInProgress) {
          if (overflowPx == Int.MAX_VALUE) {
            val lastIdx = listState.layoutInfo.totalItemsCount - 1
            if (lastIdx >= 0) {
              listState.scrollToItem(lastIdx)
            }
          } else if (overflowPx > 6) {
            listState.scrollBy(overflowPx.toFloat())
          }
        }
        // Cap auto-scroll adjustments to ~5 Hz so layout passes stay smooth on low-end devices.
        delay(180L)
      }
  }

  val lastAssistantIndex =
    remember(uiState.messages) {
      uiState.messages.indexOfLast { it.role == MessageEntity.ROLE_ASSISTANT }
    }

  val activeModelChipText =
    remember(uiState.activeModelId) { uiState.activeModelId.substringAfterLast('/') }

  val onRegenerate = remember(viewModel) { { viewModel.regenerateLastResponse() } }
  val onSend = remember(viewModel) { { viewModel.sendMessage() } }
  val onStop = remember(viewModel) { { viewModel.stopGeneration() } }
  val onSelectConversation =
    remember(viewModel, scope, drawerState) {
      { id: String ->
        viewModel.selectConversation(id)
        scope.launch { drawerState.close() }
        Unit
      }
    }
  val onDeleteConversation =
    remember(viewModel) { { id: String -> viewModel.deleteConversation(id) } }
  val onDrawerNewChat =
    remember(viewModel, scope, drawerState) {
      {
        viewModel.startNewChat()
        scope.launch { drawerState.close() }
        Unit
      }
    }
  val onDrawerOpenSettings =
    remember(scope, drawerState, onOpenSettings) {
      {
        scope.launch { drawerState.close() }
        onOpenSettings()
      }
    }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ChatDrawerContent(
        conversations = uiState.conversations,
        activeConversationId = uiState.activeConversationId,
        onSelectConversation = onSelectConversation,
        onDeleteConversation = onDeleteConversation,
        onNewChat = onDrawerNewChat,
        onOpenSettings = onDrawerOpenSettings,
      )
    },
    modifier = modifier,
  ) {
    Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      topBar = {
        TopAppBar(
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.background,
              scrolledContainerColor = MaterialTheme.colorScheme.surface,
            ),
          navigationIcon = {
            IconButton(
              onClick = { scope.launch { drawerState.open() } },
              modifier = Modifier.testTag("open_drawer_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = stringResource(R.string.cd_open_drawer),
              )
            }
          },
          title = {
            Surface(
              onClick = onOpenSettings,
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.surfaceContainer,
              modifier = Modifier.heightIn(min = 40.dp).testTag("model_chip_button"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = activeModelChipText,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Icon(
                  imageVector = Icons.Rounded.KeyboardArrowDown,
                  contentDescription = stringResource(R.string.cd_settings),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          },
          actions = {
            if (uiState.messages.isNotEmpty()) {
              Box {
                IconButton(
                  onClick = { showShareMenu = true },
                  modifier = Modifier.testTag("share_chat_button"),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = stringResource(R.string.cd_share_chat),
                  )
                }
                DropdownMenu(
                  expanded = showShareMenu,
                  onDismissRequest = { showShareMenu = false },
                ) {
                  DropdownMenuItem(
                    text = { Text(stringResource(R.string.cd_copy_chat)) },
                    leadingIcon = {
                      Icon(imageVector = Icons.Rounded.ContentCopy, contentDescription = null)
                    },
                    onClick = {
                      showShareMenu = false
                      haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                      viewModel.copyActiveConversation()
                    },
                  )
                  DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share)) },
                    leadingIcon = {
                      Icon(imageVector = Icons.Rounded.Share, contentDescription = null)
                    },
                    onClick = {
                      showShareMenu = false
                      haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                      viewModel.shareActiveConversation()
                    },
                  )
                }
              }
            }

            IconButton(
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.startNewChat()
              },
              modifier = Modifier.testTag("new_chat_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.EditNote,
                contentDescription = stringResource(R.string.cd_new_chat),
              )
            }
          },
        )
      },
    ) { innerPadding ->
      Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (!uiState.hasActiveApiKey) {
          MissingKeyBanner(
            providerName = uiState.activeProvider.displayName,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
          )
        }

        Box(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentAlignment = Alignment.TopCenter,
        ) {
          if (uiState.messages.isEmpty()) {
            ChatEmptyState(
              onPromptSelected = { prompt ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.sendMessage(prompt)
              },
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            LazyColumn(
              state = listState,
              contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
              modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
            ) {
              itemsIndexed(
                items = uiState.messages,
                key = { _, msg -> msg.id },
                contentType = { _, msg -> msg.role },
              ) { index, message ->
                if (message.id == uiState.streamingMessageId) {
                  StreamingChatMessageItem(
                    message = message,
                    streamingTextFlow = viewModel.streamingText,
                    isLastAssistantMessage = index == lastAssistantIndex,
                    onRegenerate = onRegenerate,
                  )
                } else {
                  ChatMessageItem(
                    message = message,
                    displayContent = message.content,
                    isStreaming = false,
                    isLastAssistantMessage = index == lastAssistantIndex,
                    onRegenerate = onRegenerate,
                  )
                }
              }
            }
          }
        }

        ChatInputBar(
          text = inputText,
          onTextChange = viewModel::onInputChanged,
          isGenerating = uiState.isGenerating,
          onSend = onSend,
          onStop = onStop,
        )
      }
    }
  }
}

@Composable
private fun MissingKeyBanner(
  providerName: String,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = modifier.fillMaxWidth().widthIn(max = 680.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.Key,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.chat_missing_key_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = stringResource(R.string.chat_missing_key_body, providerName),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Button(
        onClick = onOpenSettings,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag("missing_key_settings_button"),
      ) {
        Text(text = stringResource(R.string.chat_open_settings))
      }
    }
  }
}

@Composable
private fun ChatEmptyState(
  onPromptSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Column(
      modifier = Modifier.widthIn(max = 520.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      VedBrandMark(modifier = Modifier.size(54.dp))
      Spacer(modifier = Modifier.height(18.dp))
      Text(
        text = stringResource(R.string.chat_empty_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.chat_empty_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(26.dp))

      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SuggestedPrompts.forEachIndexed { index, prompt ->
          Surface(
            onClick = { onPromptSelected(prompt) },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier =
              Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("prompt_chip_$index"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }
    }
  }
}
