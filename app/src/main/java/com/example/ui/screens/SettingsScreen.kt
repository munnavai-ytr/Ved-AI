package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.AiModelInfo
import com.example.data.model.AiProvider
import com.example.data.model.ThemeMode
import com.example.data.security.KeySource
import com.example.ui.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen for theme mode, AI provider switching, model selection (including dynamic
 * OpenRouter `/models` catalog), custom system instructions, backup export/import, and
 * Keystore-encrypted API key overrides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  viewModel: SettingsViewModel,
  onNavigateBack: () -> Unit,
  onOpenMemory: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val haptic = LocalHapticFeedback.current
  val context = LocalContext.current
  var showOpenRouterSheet by remember { mutableStateOf(false) }

  val exportLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
      if (uri != null) {
        viewModel.exportBackup(context, uri)
      }
    }

  val importLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
      if (uri != null) {
        viewModel.importBackup(context, uri)
      }
    }

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
            modifier = Modifier.testTag("settings_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
          )
        },
      )
    },
    modifier = modifier,
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().widthIn(max = 620.dp).navigationBarsPadding(),
      ) {
        // 1. Appearance
        item {
          SettingsSectionCard(title = stringResource(R.string.settings_appearance_section)) {
            val themes = ThemeMode.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
              themes.forEachIndexed { index, mode ->
                val label =
                  when (mode) {
                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                  }
                SegmentedButton(
                  selected = uiState.themeMode == mode,
                  onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.setThemeMode(mode)
                  },
                  shape = SegmentedButtonDefaults.itemShape(index = index, count = themes.size),
                  modifier = Modifier.testTag("theme_${mode.id}"),
                ) {
                  Text(text = label, maxLines = 1)
                }
              }
            }
          }
        }

        // 2. AI Engine & Model Selection
        item {
          SettingsSectionCard(title = stringResource(R.string.settings_engine_section)) {
            Text(
              text = stringResource(R.string.settings_provider_label),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val providers = AiProvider.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
              providers.forEachIndexed { index, provider ->
                SegmentedButton(
                  selected = uiState.activeProvider == provider,
                  onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.setProvider(provider)
                  },
                  shape = SegmentedButtonDefaults.itemShape(index = index, count = providers.size),
                  modifier = Modifier.testTag("provider_${provider.id}"),
                ) {
                  Text(text = provider.displayName, maxLines = 1)
                }
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
              text = stringResource(R.string.settings_model_label),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.activeProvider == AiProvider.GEMINI) {
              GeminiModelDropdown(
                selectedModelId = uiState.geminiModel,
                models = uiState.geminiModels,
                onSelectModel = viewModel::setModel,
              )
            } else {
              OpenRouterModelTrigger(
                selectedModelId = uiState.openRouterModel,
                isLoading = uiState.isLoadingOpenRouterModels,
                modelsCount = uiState.openRouterModels.size,
                onOpenSheet = { showOpenRouterSheet = true },
                onRefresh = viewModel::refreshOpenRouterModels,
              )
            }
          }
        }

        // 3. Custom Instructions (System Prompt)
        item {
          SettingsSectionCard(title = stringResource(R.string.settings_system_prompt_section)) {
            var promptDraft by remember(uiState.systemPrompt) { mutableStateOf(uiState.systemPrompt) }

            Text(
              text = stringResource(R.string.settings_system_prompt_note),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
              value = promptDraft,
              onValueChange = { promptDraft = it },
              placeholder = { Text(stringResource(R.string.settings_system_prompt_placeholder)) },
              minLines = 3,
              maxLines = 6,
              shape = RoundedCornerShape(16.dp),
              modifier = Modifier.fillMaxWidth().testTag("system_prompt_input"),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.saveSystemPrompt(promptDraft)
              },
              enabled = promptDraft.trim() != uiState.systemPrompt.trim(),
              shape = RoundedCornerShape(14.dp),
              modifier = Modifier.heightIn(min = 48.dp).testTag("save_system_prompt_button"),
            ) {
              Text(text = stringResource(R.string.settings_system_prompt_save))
            }
          }
        }

        // 4. Memory
        item {
          Surface(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onOpenMemory()
            },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("settings_memory_row"),
          ) {
            Row(
              modifier = Modifier.padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(R.string.memory_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = stringResource(R.string.settings_memory_subtitle),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cd_open_memory),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        // 5. Backup (Export / Import)
        item {
          SettingsSectionCard(title = stringResource(R.string.settings_backup_section)) {
            Text(
              text = stringResource(R.string.settings_backup_note),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              OutlinedButton(
                onClick = {
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  exportLauncher.launch(defaultBackupFilename())
                },
                enabled = !uiState.isBackupBusy,
                shape = RoundedCornerShape(14.dp),
                modifier =
                  Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export_backup_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.FileUpload,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.settings_export_chats))
              }

              OutlinedButton(
                onClick = {
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                enabled = !uiState.isBackupBusy,
                shape = RoundedCornerShape(14.dp),
                modifier =
                  Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import_backup_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.FileDownload,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.settings_import_chats))
              }
            }
          }
        }

        // 5. Encrypted API Key Overrides
        item {
          SettingsSectionCard(title = stringResource(R.string.settings_keys_section)) {
            Text(
              text = stringResource(R.string.settings_keys_note),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            ApiKeyOverrideBlock(
              provider = AiProvider.GEMINI,
              label = stringResource(R.string.settings_gemini_key_label),
              keySource = uiState.geminiKeySource,
              onSave = { key -> viewModel.saveKeyOverride(AiProvider.GEMINI, key) },
              onClear = { viewModel.clearKeyOverride(AiProvider.GEMINI) },
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            ApiKeyOverrideBlock(
              provider = AiProvider.OPENROUTER,
              label = stringResource(R.string.settings_openrouter_key_label),
              keySource = uiState.openRouterKeySource,
              onSave = { key -> viewModel.saveKeyOverride(AiProvider.OPENROUTER, key) },
              onClear = { viewModel.clearKeyOverride(AiProvider.OPENROUTER) },
            )
          }
        }
      }
    }
  }

  if (showOpenRouterSheet) {
    OpenRouterModelPickerSheet(
      selectedModelId = uiState.openRouterModel,
      models = uiState.filteredOpenRouterModels,
      searchQuery = uiState.modelSearchQuery,
      isLoading = uiState.isLoadingOpenRouterModels,
      errorMessage = uiState.openRouterModelsError,
      onSearchChange = viewModel::updateSearchQuery,
      onRefresh = viewModel::refreshOpenRouterModels,
      onSelectModel = { modelId ->
        viewModel.setModel(modelId)
        showOpenRouterSheet = false
      },
      onDismiss = { showOpenRouterSheet = false },
    )
  }
}

private fun defaultBackupFilename(): String {
  val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
  return "ved_backup_$dateStr.json"
}

@Composable
private fun SettingsSectionCard(
  title: String,
  content: @Composable () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(18.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(14.dp))
      content()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiModelDropdown(
  selectedModelId: String,
  models: List<AiModelInfo>,
  onSelectModel: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val selectedModel = models.firstOrNull { it.id == selectedModelId }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = !expanded },
  ) {
    OutlinedTextField(
      value = selectedModel?.name ?: selectedModelId,
      onValueChange = {},
      readOnly = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      shape = RoundedCornerShape(16.dp),
      modifier =
        Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
          .fillMaxWidth()
          .testTag("gemini_model_dropdown"),
    )

    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      models.forEach { model ->
        DropdownMenuItem(
          text = {
            Column {
              Text(text = model.name, style = MaterialTheme.typography.bodyLarge)
              Text(
                text = model.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          trailingIcon = {
            if (model.id == selectedModelId) {
              Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          },
          onClick = {
            onSelectModel(model.id)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun OpenRouterModelTrigger(
  selectedModelId: String,
  isLoading: Boolean,
  modelsCount: Int,
  onOpenSheet: () -> Unit,
  onRefresh: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Surface(
      onClick = onOpenSheet,
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
      modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("openrouter_model_selector"),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = selectedModelId,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text =
              if (isLoading) stringResource(R.string.settings_loading_models)
              else stringResource(R.string.settings_models_count, modelsCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Icon(
          imageVector = Icons.Rounded.KeyboardArrowDown,
          contentDescription = stringResource(R.string.settings_select_model),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    IconButton(
      onClick = onRefresh,
      enabled = !isLoading,
      modifier = Modifier.size(48.dp).testTag("refresh_openrouter_models_button"),
    ) {
      if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
      } else {
        Icon(
          imageVector = Icons.Rounded.Refresh,
          contentDescription = stringResource(R.string.cd_refresh_models),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenRouterModelPickerSheet(
  selectedModelId: String,
  models: List<AiModelInfo>,
  searchQuery: String,
  isLoading: Boolean,
  errorMessage: String?,
  onSearchChange: (String) -> Unit,
  onRefresh: () -> Unit,
  onSelectModel: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.settings_select_model),
          style = MaterialTheme.typography.titleLarge,
        )
        IconButton(onClick = onRefresh, enabled = !isLoading) {
          Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = stringResource(R.string.cd_refresh_models),
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        placeholder = { Text(stringResource(R.string.settings_search_models)) },
        leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("openrouter_search_input"),
      )

      if (errorMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        items(
          items = models,
          key = { it.id },
          contentType = { "openrouter_model" },
        ) { model ->
          val isSelected = model.id == selectedModelId
          val onItemClick = remember(model.id, onSelectModel) { { onSelectModel(model.id) } }
          Surface(
            onClick = onItemClick,
            shape = RoundedCornerShape(14.dp),
            color =
              if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
              else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = model.name,
                  style = MaterialTheme.typography.bodyMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = model.id,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                  imageVector = Icons.Rounded.Check,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
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
private fun ApiKeyOverrideBlock(
  provider: AiProvider,
  label: String,
  keySource: KeySource,
  onSave: (String) -> Unit,
  onClear: () -> Unit,
) {
  var keyInput by remember { mutableStateOf("") }
  var keyVisible by remember { mutableStateOf(false) }
  val haptic = LocalHapticFeedback.current

  val statusText =
    when (keySource) {
      KeySource.OVERRIDE -> stringResource(R.string.settings_key_status_override)
      KeySource.BUILD_CONFIG -> stringResource(R.string.settings_key_status_build)
      KeySource.MISSING -> stringResource(R.string.settings_key_status_missing)
    }

  val statusColor =
    when (keySource) {
      KeySource.OVERRIDE,
      KeySource.BUILD_CONFIG -> MaterialTheme.colorScheme.primary
      KeySource.MISSING -> MaterialTheme.colorScheme.error
    }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = statusText,
      style = MaterialTheme.typography.labelSmall,
      color = statusColor,
    )

    OutlinedTextField(
      value = keyInput,
      onValueChange = { keyInput = it },
      placeholder = { Text(stringResource(R.string.settings_key_placeholder)) },
      singleLine = true,
      visualTransformation =
        if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        IconButton(onClick = { keyVisible = !keyVisible }) {
          Icon(
            imageVector =
              if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = stringResource(R.string.cd_toggle_key_visibility),
          )
        }
      },
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth().testTag("key_input_${provider.id}"),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          onSave(keyInput)
          keyInput = ""
        },
        enabled = keyInput.isNotBlank(),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag("save_${provider.id}_key"),
      ) {
        Text(text = stringResource(R.string.settings_key_save))
      }

      if (keySource == KeySource.OVERRIDE) {
        OutlinedButton(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClear()
          },
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.heightIn(min = 48.dp).testTag("clear_${provider.id}_key"),
        ) {
          Text(text = stringResource(R.string.settings_key_clear))
        }
      }
    }
  }
}
