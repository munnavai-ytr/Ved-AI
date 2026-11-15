package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.MemoryEntity
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class MemoryUiState(
  val memories: List<MemoryEntity> = emptyList(),
  val totalCount: Int = 0,
  val feedbackMessage: String? = null,
)

/**
 * ViewModel driving the Memory management screen: debounced search, manual creation,
 * inline editing, deletion, and full memory reset.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MemoryViewModel(private val repository: ChatRepository) : ViewModel() {

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _feedbackMessage = MutableStateFlow<String?>(null)

  private val allMemoriesFlow =
    repository
      .observeMemories()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
      )

  private val filteredMemoriesFlow =
    _searchQuery
      .map { it.trim() }
      .distinctUntilChanged()
      .debounce { query -> if (query.isEmpty()) 0L else 250L }
      .flatMapLatest { query ->
        if (query.isEmpty()) repository.observeMemories()
        else repository.searchMemories(query)
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
      )

  val uiState: StateFlow<MemoryUiState> =
    combine(filteredMemoriesFlow, allMemoriesFlow, _feedbackMessage) {
        filtered,
        all,
        feedback ->
        MemoryUiState(
          memories = filtered,
          totalCount = all.size,
          feedbackMessage = feedback,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MemoryUiState(),
      )

  fun onSearchQueryChanged(query: String) {
    _searchQuery.value = query
  }

  fun clearSearch() {
    _searchQuery.value = ""
  }

  fun addMemory(content: String, category: String) {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return
    viewModelScope.launch {
      repository.addMemory(
        content = trimmed,
        category = category.trim().ifEmpty { MemoryEntity.CATEGORY_FACT },
        source = MemoryEntity.SOURCE_USER,
      )
      _feedbackMessage.value = "Memory saved"
    }
  }

  fun updateMemory(id: String, content: String) {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return
    viewModelScope.launch {
      repository.updateMemory(id, trimmed)
      _feedbackMessage.value = "Memory updated"
    }
  }

  fun deleteMemory(id: String) {
    viewModelScope.launch {
      repository.deleteMemory(id)
      _feedbackMessage.value = "Memory deleted"
    }
  }

  fun clearAllMemories() {
    viewModelScope.launch {
      repository.clearAllMemories()
      _searchQuery.value = ""
      _feedbackMessage.value = "All memories cleared"
    }
  }

  fun clearFeedback() {
    _feedbackMessage.value = null
  }
}
