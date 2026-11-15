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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.entity.SkillEntity
import com.example.data.local.entity.SkillWithFiles
import com.example.ui.viewmodel.SkillsUiEvent
import com.example.ui.viewmodel.SkillsViewModel

/**
 * Management screen for Claude-style markdown skills: list, enable/disable toggle, `.md` import,
 * `.md` export, delete, and navigation to [SkillEditorScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
  viewModel: SkillsViewModel,
  onNavigateBack: () -> Unit,
  onCreateSkill: () -> Unit,
  onEditSkill: (String) -> Unit,
  onOpenImportedDraft: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  var pendingExportSkill by remember { mutableStateOf<SkillEntity?>(null) }

  val importSkillLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
      if (uri != null) {
        viewModel.importSkillFromUri(context, uri)
      }
    }

  val exportSkillLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
      val target = pendingExportSkill
      pendingExportSkill = null
      if (uri != null && target != null) {
        viewModel.exportSkillToUri(context, target, uri)
      }
    }

  LaunchedEffect(uiState.feedbackMessage) {
    val msg = uiState.feedbackMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearFeedback()
    }
  }

  LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
      when (event) {
        is SkillsUiEvent.OpenImportedDraft -> onOpenImportedDraft()
        is SkillsUiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
      }
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
            modifier = Modifier.testTag("skills_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Text(
            text = stringResource(R.string.skills_title),
            style = MaterialTheme.typography.titleLarge,
          )
        },
        actions = {
          TextButton(
            onClick = { importSkillLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*")) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("import_skill_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.FileDownload,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = stringResource(R.string.skills_import_button))
          }
        },
      )
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = onCreateSkill,
        modifier = Modifier.testTag("create_skill_fab"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Add,
          contentDescription = stringResource(R.string.skills_create_button),
        )
      }
    },
    modifier = modifier,
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      if (uiState.skills.isEmpty()) {
        SkillsEmptyState(
          modifier = Modifier.fillMaxSize().padding(24.dp),
        )
      } else {
        LazyColumn(
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxSize().widthIn(max = 620.dp).navigationBarsPadding(),
        ) {
          items(items = uiState.skills, key = { it.skill.id }) { item ->
            SkillCard(
              item = item,
              onClick = { onEditSkill(item.skill.id) },
              onToggleEnabled = { enabled -> viewModel.setSkillEnabled(item.skill.id, enabled) },
              onExport = {
                pendingExportSkill = item.skill
                val slug =
                  item.skill.name
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .ifEmpty { "skill" }
                exportSkillLauncher.launch("$slug.md")
              },
              onDelete = { viewModel.deleteSkill(item.skill.id) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SkillCard(
  item: SkillWithFiles,
  onClick: () -> Unit,
  onToggleEnabled: (Boolean) -> Unit,
  onExport: () -> Unit,
  onDelete: () -> Unit,
) {
  val skill = item.skill
  val sourceLabel =
    if (skill.source == SkillEntity.SOURCE_IMPORTED) stringResource(R.string.skills_source_imported)
    else stringResource(R.string.skills_source_user)

  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    modifier = Modifier.fillMaxWidth().testTag("skill_card_${skill.id}"),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = skill.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (skill.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = skill.description,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Switch(
          checked = skill.isEnabled,
          onCheckedChange = onToggleEnabled,
          modifier = Modifier.testTag("skill_toggle_${skill.id}"),
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Text(
            text = sourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }

        if (item.files.isNotEmpty()) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
          ) {
            Text(
              text = stringResource(R.string.skills_files_count, item.files.size),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
          onClick = onExport,
          modifier = Modifier.size(48.dp).testTag("export_skill_${skill.id}"),
        ) {
          Icon(
            imageVector = Icons.Outlined.FileUpload,
            contentDescription = stringResource(R.string.cd_export_skill),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        IconButton(
          onClick = onDelete,
          modifier = Modifier.size(48.dp).testTag("delete_skill_${skill.id}"),
        ) {
          Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = stringResource(R.string.cd_delete_skill),
            tint = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

@Composable
private fun SkillsEmptyState(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = Icons.Outlined.AutoAwesome,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(44.dp),
    )
    Spacer(modifier = Modifier.height(14.dp))
    Text(
      text = stringResource(R.string.skills_empty_title),
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = stringResource(R.string.skills_empty_body),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(max = 360.dp),
    )
  }
}
