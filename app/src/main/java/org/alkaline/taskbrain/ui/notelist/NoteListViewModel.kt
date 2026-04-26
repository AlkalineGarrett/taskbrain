package org.alkaline.taskbrain.ui.notelist

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.NoteFilteringUtils
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteSearchUtils
import org.alkaline.taskbrain.data.NoteSearchResult
import org.alkaline.taskbrain.data.NoteSortMode
import org.alkaline.taskbrain.data.NoteStats
import org.alkaline.taskbrain.data.NoteStatsRepository
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.data.SearchHistoryEntry
import org.alkaline.taskbrain.data.SearchHistoryRepository

data class SearchState(
    val query: String = "",
    val searchByName: Boolean = true,
    val searchByContent: Boolean = true,
    val isSearchOpen: Boolean = false,
)

class NoteListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NoteRepository()
    private val statsRepository = NoteStatsRepository()
    private val searchHistoryRepository = SearchHistoryRepository(application)

    private val _notes = MutableLiveData<List<Note>>()
    val notes: LiveData<List<Note>> = _notes

    private val _deletedNotes = MutableLiveData<List<Note>>()
    val deletedNotes: LiveData<List<Note>> = _deletedNotes

    private val _loadStatus = MutableLiveData<LoadStatus>()
    val loadStatus: LiveData<LoadStatus> = _loadStatus

    private val _createNoteStatus = MutableLiveData<CreateNoteStatus>()
    val createNoteStatus: LiveData<CreateNoteStatus> = _createNoteStatus

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState

    private val _activeSearchResults = MutableLiveData<List<NoteSearchResult>>(emptyList())
    val activeSearchResults: LiveData<List<NoteSearchResult>> = _activeSearchResults

    private val _deletedSearchResults = MutableLiveData<List<NoteSearchResult>>(emptyList())
    val deletedSearchResults: LiveData<List<NoteSearchResult>> = _deletedSearchResults

    private val _searchHistory = MutableLiveData<List<SearchHistoryEntry>>(emptyList())
    val searchHistory: LiveData<List<SearchHistoryEntry>> = _searchHistory

    private val _sortMode = MutableLiveData(NoteSortMode.RECENT)
    val sortMode: LiveData<NoteSortMode> = _sortMode

    private val _noteStats = MutableLiveData<Map<String, NoteStats>>(emptyMap())
    val noteStatsLive: LiveData<Map<String, NoteStats>> = _noteStats

    private var searchDebounceJob: Job? = null

    fun setSortMode(mode: NoteSortMode) {
        if (_sortMode.value == mode) return
        _sortMode.value = mode
        refreshNotes()
    }

    init {
        viewModelScope.launch {
            NoteStore.notes.collect { storeNotes ->
                if (storeNotes.isNotEmpty()) {
                    applyFilters(storeNotes)
                    if (_loadStatus.value == LoadStatus.Loading) {
                        _loadStatus.value = LoadStatus.Success
                    }
                }
            }
        }
    }

    fun loadNotes() {
        _loadStatus.value = LoadStatus.Loading

        viewModelScope.launch {
            try {
                val statsDeferred = async { statsRepository.loadAllNoteStats() }
                NoteStore.ensureLoaded()
                statsDeferred.await().onSuccess { stats ->
                    _noteStats.value = stats
                    val storeNotes = NoteStore.notes.value
                    if (storeNotes.isNotEmpty()) applyFilters(storeNotes)
                }
                if (_loadStatus.value == LoadStatus.Loading) {
                    _loadStatus.value = LoadStatus.Success
                }
            } catch (e: Exception) {
                Log.d("NoteListViewModel", "Error loading notes: ", e)
                _loadStatus.value = LoadStatus.Error(e)
            }
        }
    }

    fun refreshNotes() {
        val storeNotes = NoteStore.notes.value
        if (storeNotes.isNotEmpty()) {
            applyFilters(storeNotes)
        }
    }

    private fun applyFilters(storeNotes: List<Note>) {
        val mode = _sortMode.value ?: NoteSortMode.RECENT
        val stats = _noteStats.value ?: emptyMap()
        _notes.value = NoteFilteringUtils.filterAndSortNotesByMode(
            storeNotes, stats, mode, System.currentTimeMillis()
        )
        _deletedNotes.value = NoteFilteringUtils.filterAndSortDeletedNotes(storeNotes)
    }

    fun createNote(onSuccess: (String) -> Unit) {
        _createNoteStatus.value = CreateNoteStatus.Loading

        viewModelScope.launch {
            val result = repository.createNote()
            result.fold(
                onSuccess = { noteId ->
                    Log.d("NoteListViewModel", "Note created with ID: $noteId")
                    _createNoteStatus.value = CreateNoteStatus.Success(noteId)
                    onSuccess(noteId)
                },
                onFailure = { e ->
                    Log.w("NoteListViewModel", "Error adding document", e)
                    _createNoteStatus.value = CreateNoteStatus.Error(e)
                }
            )
        }
    }

    fun saveNewNote(content: String, onSuccess: (String) -> Unit) {
        _createNoteStatus.value = CreateNoteStatus.Loading

        viewModelScope.launch {
            val result = repository.createMultiLineNote(content)
            result.fold(
                onSuccess = { noteId ->
                    Log.d("NoteListViewModel", "Multi-line note created with ID: $noteId")
                    _createNoteStatus.value = CreateNoteStatus.Success(noteId)
                    onSuccess(noteId)
                },
                onFailure = { e ->
                    Log.w("NoteListViewModel", "Error creating multi-line note", e)
                    _createNoteStatus.value = CreateNoteStatus.Error(e)
                }
            )
        }
    }

    fun softDeleteNote(noteId: String) {
        viewModelScope.launch {
            repository.softDeleteNote(noteId).fold(
                onSuccess = { refreshNotes() },
                onFailure = { e ->
                    Log.w("NoteListViewModel", "Error deleting note", e)
                    _loadStatus.value = LoadStatus.Error(e)
                }
            )
        }
    }

    fun undeleteNote(noteId: String) {
        viewModelScope.launch {
            repository.undeleteNote(noteId).fold(
                onSuccess = { refreshNotes() },
                onFailure = { e ->
                    Log.w("NoteListViewModel", "Error restoring note", e)
                    _loadStatus.value = LoadStatus.Error(e)
                }
            )
        }
    }

    fun clearLoadError() {
        if (_loadStatus.value is LoadStatus.Error) {
            _loadStatus.value = null
        }
    }

    fun clearCreateNoteError() {
        if (_createNoteStatus.value is CreateNoteStatus.Error) {
            _createNoteStatus.value = null
        }
    }

    fun toggleSearch() {
        val current = _searchState.value
        if (current.isSearchOpen) {
            _searchState.value = SearchState()
            _activeSearchResults.value = emptyList()
            _deletedSearchResults.value = emptyList()
        } else {
            _searchState.value = current.copy(isSearchOpen = true)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchState.value = _searchState.value.copy(query = query)
        if (query.length >= 3) {
            searchDebounceJob?.cancel()
            searchDebounceJob = viewModelScope.launch {
                delay(300)
                runSearch()
            }
        } else {
            searchDebounceJob?.cancel()
            _activeSearchResults.value = emptyList()
            _deletedSearchResults.value = emptyList()
        }
    }

    fun setSearchByName(enabled: Boolean) {
        _searchState.value = _searchState.value.copy(searchByName = enabled)
        if (_searchState.value.query.isNotEmpty()) {
            runSearch()
        }
    }

    fun setSearchByContent(enabled: Boolean) {
        _searchState.value = _searchState.value.copy(searchByContent = enabled)
        if (_searchState.value.query.isNotEmpty()) {
            runSearch()
        }
    }

    /** Explicit search triggered by Go button or Enter key. Saves to history. */
    fun executeSearch() {
        runSearch()
        saveToHistory()
    }

    private fun runSearch() {
        val state = _searchState.value
        if (state.query.isEmpty()) return

        val allNotes = NoteStore.notes.value
        val (active, deleted) = NoteSearchUtils.searchNotes(
            allNotes, state.query, state.searchByName, state.searchByContent,
        )
        _activeSearchResults.value = active
        _deletedSearchResults.value = deleted
    }

    private fun saveToHistory() {
        val state = _searchState.value
        if (state.query.isEmpty()) return

        val entry = SearchHistoryEntry(
            query = state.query,
            criteria = mapOf("name" to state.searchByName, "content" to state.searchByContent),
            timestamp = System.currentTimeMillis(),
        )
        searchHistoryRepository.saveEntry(entry)
        _searchHistory.value = searchHistoryRepository.getHistory()
    }

    fun loadSearchHistory() {
        _searchHistory.value = searchHistoryRepository.getHistory()
        viewModelScope.launch {
            searchHistoryRepository.syncFromFirebase()
            _searchHistory.value = searchHistoryRepository.getHistory()
        }
    }

    fun replaySearch(entry: SearchHistoryEntry) {
        _searchState.value = SearchState(
            query = entry.query,
            searchByName = entry.criteria["name"] ?: true,
            searchByContent = entry.criteria["content"] ?: true,
            isSearchOpen = true,
        )
        runSearch()
        // History entry already exists — update its timestamp
        saveToHistory()
    }
}

sealed class LoadStatus {
    object Loading : LoadStatus()
    object Success : LoadStatus()
    data class Error(val throwable: Throwable) : LoadStatus()
}

sealed class CreateNoteStatus {
    object Loading : CreateNoteStatus()
    data class Success(val noteId: String) : CreateNoteStatus()
    data class Error(val throwable: Throwable) : CreateNoteStatus()
}
