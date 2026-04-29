package org.alkaline.taskbrain.ui.currentnote

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.data.RecentTab
import org.alkaline.taskbrain.data.RecentTabsRepository
import org.alkaline.taskbrain.data.TabState

/**
 * Cached note content for instant tab switching.
 */
data class CachedNoteContent(
    val noteLines: List<NoteLine>,
    val isDeleted: Boolean = false,
    val isDirty: Boolean = false
)

/**
 * ViewModel for managing the recent tabs bar.
 * Coordinates between tab UI and Firebase persistence.
 * Also maintains an in-memory cache of note content for instant tab switching.
 */
class RecentTabsViewModel : ViewModel() {

    private val repository = RecentTabsRepository()

    private val _tabs = MutableLiveData<List<RecentTab>>(emptyList())
    val tabs: LiveData<List<RecentTab>> = _tabs

    init {
        // Kick off the lazy listener so other devices' writes start flowing.
        viewModelScope.launch { repository.getOpenTabs() }
        // The listener-backed cache is the sole writer to [_tabs] — this
        // device's own writes echo through the local-echo path in
        // RecentTabsRepository's listener, and other devices' writes flow in
        // once the server confirms.
        viewModelScope.launch {
            repository.cachedTabs.collect { shared ->
                _tabs.value = refreshDisplayTexts(shared)
            }
        }
    }

    private val _error = MutableLiveData<TabsError?>(null)
    val error: LiveData<TabsError?> = _error

    // In-memory cache of note content for instant tab switching
    // Cache is bounded by max tabs (5) so memory usage is naturally limited
    private val noteCache = mutableMapOf<String, CachedNoteContent>()

    fun clearError() {
        _error.value = null
    }

    /**
     * Gets cached note content if available.
     */
    fun getCachedContent(noteId: String): CachedNoteContent? = noteCache[noteId]

    /**
     * Caches note content for instant tab switching.
     */
    fun cacheNoteContent(noteId: String, noteLines: List<NoteLine>, isDeleted: Boolean = false, isDirty: Boolean = false) {
        noteCache[noteId] = CachedNoteContent(noteLines, isDeleted, isDirty)
    }

    /**
     * Invalidates cache for a specific note.
     * Call this when external changes occur (e.g., AI modification).
     */
    fun invalidateCache(noteId: String) {
        noteCache.remove(noteId)
    }

    /**
     * Called when a note is opened. Skips the write when the note is already
     * at the front of the shared history with matching displayText. The UI
     * update flows through the listener-backed cache, not from here — see
     * the [init] block.
     */
    fun onNoteOpened(noteId: String, content: String) {
        val displayText = TabState.extractDisplayText(content)
        val front = _tabs.value?.firstOrNull()
        if (front?.noteId == noteId && front.displayText == displayText) return

        viewModelScope.launch {
            repository.addOrUpdateTab(noteId, displayText).onFailure { e ->
                Log.e(TAG, "Error adding tab for note: $noteId", e)
                _error.value = TabsError("Failed to sync tab", e)
            }
        }
    }

    /** Called when user closes a tab with the X button. */
    fun closeTab(noteId: String) {
        noteCache.remove(noteId)
        pendingDisplayTextCorrections.remove(noteId)
        viewModelScope.launch {
            repository.removeTab(noteId).onFailure { e ->
                Log.e(TAG, "Error closing tab: $noteId", e)
                _error.value = TabsError("Failed to close tab", e)
            }
        }
    }

    /** Called when a note is deleted. Removes its tab. */
    fun onNoteDeleted(noteId: String) {
        noteCache.remove(noteId)
        pendingDisplayTextCorrections.remove(noteId)
        viewModelScope.launch {
            repository.removeTab(noteId).onFailure { e ->
                Log.e(TAG, "Error removing tab for deleted note: $noteId", e)
                // Background cleanup — don't surface errors.
            }
        }
    }

    /** Persists a fresh display text. Skips no-op writes. */
    fun updateTabDisplayText(noteId: String, content: String) {
        val displayText = TabState.extractDisplayText(content)
        val existing = (_tabs.value ?: emptyList()).find { it.noteId == noteId }
        if (existing != null && existing.displayText == displayText) return

        viewModelScope.launch {
            repository.updateTabDisplayText(noteId, displayText).onFailure { e ->
                Log.e(TAG, "Error updating tab display text: $noteId", e)
                // Display-text updates are non-critical; suppress UI error.
            }
        }
    }

    /**
     * Per-noteId memo of the last correction we wrote to Firestore so a
     * repeated stale snapshot (e.g. while another writer keeps overwriting
     * the same wrong value) doesn't trigger a write loop. Cleared once the
     * tab settles to the corrected value.
     */
    private val pendingDisplayTextCorrections = mutableMapOf<String, String>()

    /**
     * Cross-references tab displayTexts with NoteStore content and fixes stale values.
     * Persists corrections to Firestore in the background.
     */
    private fun refreshDisplayTexts(tabs: List<RecentTab>): List<RecentTab> {
        return tabs.map { tab ->
            val note = NoteStore.getNoteById(tab.noteId) ?: return@map tab
            val freshDisplayText = TabState.extractDisplayText(note.content)
            if (freshDisplayText != tab.displayText) {
                if (pendingDisplayTextCorrections[tab.noteId] != freshDisplayText) {
                    pendingDisplayTextCorrections[tab.noteId] = freshDisplayText
                    Log.d(TAG, "refreshDisplayTexts: stale displayText for ${tab.noteId}: " +
                        "\"${tab.displayText}\" -> \"$freshDisplayText\"")
                    viewModelScope.launch {
                        repository.updateTabDisplayText(tab.noteId, freshDisplayText)
                    }
                }
                tab.copy(displayText = freshDisplayText)
            } else {
                pendingDisplayTextCorrections.remove(tab.noteId)
                tab
            }
        }
    }

    companion object {
        private const val TAG = "RecentTabsViewModel"
    }
}

/**
 * Represents an error that occurred during tab operations.
 */
data class TabsError(
    val message: String,
    val cause: Throwable
)
