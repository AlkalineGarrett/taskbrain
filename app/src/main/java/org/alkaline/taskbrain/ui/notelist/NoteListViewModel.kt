package org.alkaline.taskbrain.ui.notelist

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.NoteFilteringUtils
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore

class NoteListViewModel : ViewModel() {

    private val repository = NoteRepository()

    private val _notes = MutableLiveData<List<Note>>()
    val notes: LiveData<List<Note>> = _notes

    private val _deletedNotes = MutableLiveData<List<Note>>()
    val deletedNotes: LiveData<List<Note>> = _deletedNotes

    private val _loadStatus = MutableLiveData<LoadStatus>()
    val loadStatus: LiveData<LoadStatus> = _loadStatus

    private val _createNoteStatus = MutableLiveData<CreateNoteStatus>()
    val createNoteStatus: LiveData<CreateNoteStatus> = _createNoteStatus

    init {
        // Observe NoteStore for real-time updates to the note list
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
                NoteStore.ensureLoaded()
                // ensureLoaded() resolved — if the collector hasn't already
                // set Success (e.g. user has zero notes), do it now.
                if (_loadStatus.value == LoadStatus.Loading) {
                    _loadStatus.value = LoadStatus.Success
                }
            } catch (e: Exception) {
                Log.d("NoteListViewModel", "Error loading notes: ", e)
                _loadStatus.value = LoadStatus.Error(e)
            }
        }
    }

    /**
     * Refreshes the notes list without showing loading indicator.
     * The NoteStore's collection listener keeps data fresh, so this
     * is essentially a no-op — the collector above handles updates.
     */
    fun refreshNotes() {
        val storeNotes = NoteStore.notes.value
        if (storeNotes.isNotEmpty()) {
            applyFilters(storeNotes)
        }
    }

    private fun applyFilters(storeNotes: List<Note>) {
        _notes.value = NoteFilteringUtils.filterAndSortNotesByLastAccessed(storeNotes)
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
