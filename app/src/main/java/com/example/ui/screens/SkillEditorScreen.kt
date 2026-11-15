package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.viewmodel.SkillsUiEvent
import com.example.ui.viewmodel.SkillsViewModel

/**
 * Editor screen for creating or updating a Claude-style skill and its supporting UTF-8
 * reference files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(
  viewModel: SkillsViewModel,
  skillId: String?,
  isImportedDraft: Boolean,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val draft by viewModel.editorDraft.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current

  LaunchedEffect(skillId, isImportedDraft) {
    if (skillId != null && draft.skillId != skillId) {
      viewModel.loadSkillForEditing(skillId)
    } else if (skillId == null && !isImportedDraft && draft.skillId != null) {
      viewModel.startNewDraft()
    }
  }

  LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
      if (event is SkillsUiEvent.ShowMessage) {
        snackbarHostState.showSnackbar(event.message)
      }
    }
  }

  val refFileLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
      if (uris.isNotEmpty()) {
        viewModel.addReferenceFiles(context, uris)
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
            modifier = Modifier.testTag("skill_editor_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Text(
            text =
              if (skillId == null && !isImportedDraft) {
                stringResource(R.string.skills_editor_create_title)
              } else {
                stringResource(R.string.skills_editor_edit_title)
              },
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
      Column(
        modifier =
          Modifier.fillMaxSize()
            .widthIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        if (draft.importNeedsDetailsNotice) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = stringResource(R.string.skills_import_review_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(14.dp),
            )
          }
        }

        OutlinedTextField(
          value = draft.name,
          onValueChange = viewModel::updateDraftName,
          label = { Text(stringResource(R.string.skills_name_label)) },
          placeholder = { Text(stringResource(R.string.skills_name_placeholder)) },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth().testTag("skill_name_input"),
        )

        OutlinedTextField(
          value = draft.description,
          onValueChange = viewModel::updateDraftDescription,
          label = { Text(stringResource(R.string.skills_desc_label)) },
          placeholder = { Text(stringResource(R.string.skills_desc_placeholder)) },
          minLines = 2,
          maxLines = 4,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth().testTag("skill_description_input"),
        )

        OutlinedTextField(
          value = draft.skillMarkdown,
          onValueChange = viewModel::updateDraftMarkdown,
          label = { Text(stringResource(R.string.skills_markdown_label)) },
          placeholder = { Text(stringResource(R.string.skills_markdown_placeholder)) },
          minLines = 8,
          maxLines = 18,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth().testTag("skill_markdown_input"),
        )

        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 1.dp,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = stringResource(R.string.skills_ref_files_section),
              style = MaterialTheme.typography.titleSmall,
            )

            draft.files.forEach { file ->
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                  )
                  Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  IconButton(
                    onClick = { viewModel.removeReferenceFile(file.id) },
                    modifier = Modifier.size(48.dp).testTag("remove_ref_file_${file.id}"),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.Close,
                      contentDescription = stringResource(R.string.cd_remove_ref_file),
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              }
            }

            OutlinedButton(
              onClick = { refFileLauncher.launch(arrayOf("text/*", "application/json", "*/*")) },
              shape = RoundedCornerShape(14.dp),
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_ref_file_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(text = stringResource(R.string.skills_add_ref_file))
            }
          }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
          onClick = { viewModel.saveDraft(onSaved = onNavigateBack) },
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("save_skill_button"),
        ) {
          Text(text = stringResource(R.string.skills_save_button))
        }
      }
    }
  }
}
