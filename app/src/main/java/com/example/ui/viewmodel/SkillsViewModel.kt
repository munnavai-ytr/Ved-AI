package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.SkillEntity
import com.example.data.local.entity.SkillFileEntity
import com.example.data.local.entity.SkillWithFiles
import com.example.data.repository.ChatRepository
import com.example.data.util.AttachmentProcessor
import com.example.data.util.ParsedSkillImport
import com.example.data.util.SkillMatcher
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class SkillsUiState(
  val skills: List<SkillWithFiles> = emptyList(),
  val feedbackMessage: String? = null,
)

@Immutable
data class SkillEditorDraft(
  val skillId: String? = null,
  val name: String = "",
  val description: String = "",
  val skillMarkdown: String = "",
  val source: String = SkillEntity.SOURCE_USER,
  val files: List<SkillFileEntity> = emptyList(),
  val isLoading: Boolean = false,
  val importNeedsDetailsNotice: Boolean = false,
)

sealed interface SkillsUiEvent {
  data class OpenImportedDraft(val parsed: ParsedSkillImport) : SkillsUiEvent

  data class ShowMessage(val message: String) : SkillsUiEvent
}

/**
 * ViewModel powering the Skills management list, YAML `.md` import/export, enable toggling,
 * and Skill Editor with supporting UTF-8 reference files.
 */
class SkillsViewModel(private val repository: ChatRepository) : ViewModel() {

  private val _feedbackMessage = MutableStateFlow<String?>(null)
  private val _editorDraft = MutableStateFlow(SkillEditorDraft())
  private val _events = MutableSharedFlow<SkillsUiEvent>(extraBufferCapacity = 8)

  val editorDraft: StateFlow<SkillEditorDraft> = _editorDraft
  val events: SharedFlow<SkillsUiEvent> = _events.asSharedFlow()

  val uiState: StateFlow<SkillsUiState> =
    combine(repository.observeSkillsWithFiles(), _feedbackMessage) { skills, feedback ->
        SkillsUiState(skills = skills, feedbackMessage = feedback)
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SkillsUiState(),
      )

  fun setSkillEnabled(skillId: String, enabled: Boolean) {
    viewModelScope.launch { repository.setSkillEnabled(skillId, enabled) }
  }

  fun deleteSkill(skillId: String) {
    val target = uiState.value.skills.firstOrNull { it.skill.id == skillId }?.skill
    if (target?.source == SkillEntity.SOURCE_SYSTEM) return
    viewModelScope.launch {
      repository.deleteSkill(skillId)
      _feedbackMessage.value = "Skill deleted"
    }
  }

  fun importSkillFromUri(context: Context, uri: Uri) {
    val appContext = context.applicationContext
    viewModelScope.launch {
      val result =
        withContext(Dispatchers.IO) {
          AttachmentProcessor.readUriAsValidatedUtf8(appContext, uri)
        }
      result
        .onSuccess { (fileName, rawContent) ->
          val parsed = SkillMatcher.parseSkillMarkdown(rawContent, fallbackFileName = fileName)
          if (parsed.hasValidFrontmatter) {
            repository.saveSkill(
              name = parsed.name,
              description = parsed.description,
              skillMarkdown = parsed.skillMarkdown,
              source = SkillEntity.SOURCE_IMPORTED,
            )
            _feedbackMessage.value = "Imported skill \"${parsed.name}\""
          } else {
            // Prompt the user cleanly in the editor to verify name/description before saving.
            _editorDraft.value =
              SkillEditorDraft(
                skillId = null,
                name = parsed.name,
                description = parsed.description,
                skillMarkdown = parsed.skillMarkdown,
                source = SkillEntity.SOURCE_IMPORTED,
                files = emptyList(),
                isLoading = false,
                importNeedsDetailsNotice = true,
              )
            _events.tryEmit(SkillsUiEvent.OpenImportedDraft(parsed))
          }
        }
        .onFailure { err ->
          _feedbackMessage.value = err.message ?: "Could not read selected skill file."
        }
    }
  }

  fun exportSkillToUri(context: Context, skill: SkillEntity, uri: Uri) {
    val appContext = context.applicationContext
    viewModelScope.launch {
      val formatted = SkillMatcher.formatSkillForExport(skill)
      val result =
        runCatching {
          withContext(Dispatchers.IO) {
            appContext.contentResolver.openOutputStream(uri)?.use { stream ->
              stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(formatted) }
            } ?: error("Could not open destination file for writing.")
          }
        }
      result
        .onSuccess { _feedbackMessage.value = "Exported \"${skill.name}\"" }
        .onFailure { err ->
          _feedbackMessage.value = err.message ?: "Failed to export skill."
        }
    }
  }

  fun startNewDraft() {
    _editorDraft.value = SkillEditorDraft()
  }

  fun loadSkillForEditing(skillId: String) {
    viewModelScope.launch {
      _editorDraft.value = SkillEditorDraft(skillId = skillId, isLoading = true)
      val loaded = repository.getSkillWithFiles(skillId)
      if (loaded != null && loaded.skill.source != SkillEntity.SOURCE_SYSTEM) {
        _editorDraft.value =
          SkillEditorDraft(
            skillId = loaded.skill.id,
            name = loaded.skill.name,
            description = loaded.skill.description,
            skillMarkdown = loaded.skill.skillMarkdown,
            source = loaded.skill.source,
            files = loaded.files,
            isLoading = false,
          )
      } else {
        _editorDraft.value = SkillEditorDraft()
      }
    }
  }

  fun updateDraftName(name: String) {
    _editorDraft.value = _editorDraft.value.copy(name = name)
  }

  fun updateDraftDescription(description: String) {
    _editorDraft.value = _editorDraft.value.copy(description = description)
  }

  fun updateDraftMarkdown(markdown: String) {
    _editorDraft.value = _editorDraft.value.copy(skillMarkdown = markdown)
  }

  fun addReferenceFiles(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val appContext = context.applicationContext
    viewModelScope.launch {
      val current = _editorDraft.value
      val draftSkillId = current.skillId ?: "pending"
      val added = mutableListOf<SkillFileEntity>()
      var firstError: String? = null

      withContext(Dispatchers.IO) {
        for (uri in uris) {
          val res = AttachmentProcessor.readUriAsValidatedUtf8(appContext, uri)
          res
            .onSuccess { (fileName, content) ->
              added.add(
                SkillFileEntity(
                  id = UUID.randomUUID().toString(),
                  skillId = draftSkillId,
                  fileName = fileName,
                  content = content,
                )
              )
            }
            .onFailure { err ->
              if (firstError == null) {
                firstError = err.message ?: "Could not read supporting file."
              }
            }
        }
      }

      if (added.isNotEmpty()) {
        val latest = _editorDraft.value
        _editorDraft.value = latest.copy(files = latest.files + added)
      }
      if (firstError != null) {
        _events.tryEmit(SkillsUiEvent.ShowMessage(firstError!!))
      }
    }
  }

  fun removeReferenceFile(fileId: String) {
    val latest = _editorDraft.value
    _editorDraft.value = latest.copy(files = latest.files.filterNot { it.id == fileId })
  }

  fun saveDraft(onSaved: () -> Unit) {
    val draft = _editorDraft.value
    if (draft.source == SkillEntity.SOURCE_SYSTEM) return
    if (draft.name.isBlank()) {
      _events.tryEmit(SkillsUiEvent.ShowMessage("Please enter a skill name."))
      return
    }
    if (draft.skillMarkdown.isBlank()) {
      _events.tryEmit(SkillsUiEvent.ShowMessage("Please enter skill instructions."))
      return
    }

    viewModelScope.launch {
      repository.saveSkill(
        id = draft.skillId,
        name = draft.name,
        description = draft.description,
        skillMarkdown = draft.skillMarkdown,
        source = draft.source,
        files = draft.files,
      )
      _feedbackMessage.value = "Skill saved"
      onSaved()
    }
  }

  fun clearFeedback() {
    _feedbackMessage.value = null
  }
}
