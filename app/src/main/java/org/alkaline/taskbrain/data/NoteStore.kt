package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton reactive store for all notes with reconstructed content.
 * Single source of truth for directive execution context on Android.
 *
 * Uses a Firestore collection listener for real-time incremental updates.
 * Compose components observe via [notes] StateFlow.
 *
 * Internal state:
 * - [rawNotes]: every Firestore doc (including descendants), indexed by ID
 * - [_notes]: top-level notes with content rebuilt from their tree
 */
object NoteStore {
    private const val TAG = "NoteStore"
    private const val HOT_COOLDOWN_MS = 5_000L

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /** All notes from Firestore, indexed by ID (including descendants). */
    private val rawNotes = mutableMapOf<String, Note>()

    /**
     * Notes recently modified locally, keyed by rootNoteId → timestamp.
     * The collection listener skips rebuilding hot notes so local edits
     * aren't overwritten by Firestore echoes. After the cooldown expires,
     * the next snapshot applies Firestore truth.
     */
    private val hotNotes = mutableMapOf<String, Long>()

    /** Mark a note as recently modified locally. */
    fun markHot(noteId: String) {
        hotNotes[noteId] = System.currentTimeMillis()
    }

    /** Returns true if the note was locally modified within the cooldown window. */
    fun isHot(noteId: String): Boolean {
        val timestamp = hotNotes[noteId] ?: return false
        if (System.currentTimeMillis() - timestamp < HOT_COOLDOWN_MS) return true
        hotNotes.remove(noteId)
        return false
    }

    private var listenerRegistration: ListenerRegistration? = null
    private var loaded = false
    private var loadDeferred: CompletableDeferred<Unit>? = null

    /** In-flight saves keyed by noteId, so loaders can await before reading. */
    private val pendingSaves = mutableMapOf<String, Deferred<Unit>>()

    /**
     * Debounced fire-and-forget save. Set via [setPersistCallback] during app init.
     * Called automatically by [updateNote] to ensure every local edit reaches Firestore.
     * Debouncing prevents stale-content races: if the user types "4" then "5" quickly,
     * only one save fires with the final content ("4 5"), not two competing saves.
     * Callers with their own structured save pass `persist = false` to avoid double-writes.
     */
    private var persistCallback: ((noteId: String, content: String) -> Unit)? = null
    private val pendingPersistContent = mutableMapOf<String, String>()
    private val pendingPersistRunnables = mutableMapOf<String, Runnable>()
    private val persistHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private const val PERSIST_DEBOUNCE_MS = 500L

    fun setPersistCallback(callback: (noteId: String, content: String) -> Unit) {
        persistCallback = callback
    }

    /**
     * Schedule a debounced Firestore save. Each call captures the content and
     * replaces any pending save for this note. When the debounce fires, it saves
     * the LAST captured content — never re-reads from NoteStore, which might have
     * been overwritten by the collection listener.
     */
    private fun debouncePersist(noteId: String, content: String) {
        pendingPersistContent[noteId] = content
        // Cancel any previously scheduled persist for this note
        pendingPersistRunnables.remove(noteId)?.let { persistHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingPersistRunnables.remove(noteId)
            val savedContent = pendingPersistContent.remove(noteId) ?: return@Runnable
            persistCallback?.invoke(noteId, savedContent)
        }
        pendingPersistRunnables[noteId] = runnable
        persistHandler.postDelayed(runnable, PERSIST_DEBOUNCE_MS)
    }

    /**
     * Start the Firestore collection listener. Idempotent — calling multiple
     * times is safe (subsequent calls are no-ops if already started).
     */
    fun start(db: FirebaseFirestore, userId: String) {
        if (listenerRegistration != null) return

        if (loadDeferred == null) {
            loadDeferred = CompletableDeferred()
        }

        val q = db.collection("notes").whereEqualTo("userId", userId)

        listenerRegistration = q.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Snapshot listener error", error)
                _error.value = error.message ?: "Note sync failed"
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            // Skip local-echo snapshots, but always process the first snapshot
            // so ensureLoaded() can resolve even if there are pending writes.
            if (loaded && snapshot.metadata.hasPendingWrites()) return@addSnapshotListener

            val isFirstSnapshot = !loaded

            if (isFirstSnapshot) {
                handleFirstSnapshot(snapshot.documents.mapNotNull { doc ->
                    parseNote(doc.id, doc.data)
                })
            } else {
                handleIncrementalSnapshot(snapshot.documentChanges)
            }
        }
    }

    /**
     * Returns after the first snapshot arrives.
     * Call after [start] to wait for initial data.
     */
    suspend fun ensureLoaded() {
        if (loaded) return
        val deferred = loadDeferred ?: CompletableDeferred<Unit>().also { loadDeferred = it }
        deferred.await()
    }

    /** Stop listening and clear all data (e.g., on logout). */
    fun clear() {
        listenerRegistration?.remove()
        listenerRegistration = null
        rawNotes.clear()
        hotNotes.clear()
        pendingPersistContent.clear()
        pendingPersistRunnables.clear()
        persistHandler.removeCallbacksAndMessages(null)
        persistCallback = null
        _notes.value = emptyList()
        _error.value = null
        loaded = false
        loadDeferred = null
    }

    /**
     * Update a note locally and (by default) persist to Firestore.
     * Marks the note as "hot" so the collection listener won't overwrite it.
     *
     * @param persist If true (default), fires a background Firestore save via [persistCallback].
     *   Pass false when the caller has its own structured save (e.g., saveNoteWithChildren).
     */
    fun updateNote(noteId: String, updatedNote: Note, persist: Boolean = true) {
        val current = _notes.value
        val index = current.indexOfFirst { it.id == noteId }
        if (index >= 0) {
            markHot(noteId)
            _notes.value = current.toMutableList().apply { set(index, updatedNote) }
            if (persist) {
                debouncePersist(noteId, updatedNote.content)
            }
        }
    }

    /** Add a new note to the reconstructed list. */
    fun addNote(note: Note) {
        _notes.value = _notes.value + note
    }

    /** Remove a note from the reconstructed list by ID. */
    fun removeNote(noteId: String) {
        val filtered = _notes.value.filter { it.id != noteId }
        if (filtered.size != _notes.value.size) {
            _notes.value = filtered
        }
    }

    fun getNoteById(noteId: String): Note? =
        _notes.value.find { it.id == noteId }

    /** Track an in-flight save so loaders can await it before reading from Firestore. */
    fun trackSave(noteId: String, deferred: Deferred<Unit>) {
        pendingSaves[noteId] = deferred
    }

    /** Await any pending save for a noteId. Call before loading from Firestore. */
    suspend fun awaitPendingSave(noteId: String) {
        val pending = pendingSaves[noteId] ?: return
        try {
            pending.await()
        } finally {
            if (pendingSaves[noteId] === pending) {
                pendingSaves.remove(noteId)
            }
        }
    }

    // --- Snapshot handlers ---

    private fun handleFirstSnapshot(notes: List<Note>) {
        rawNotes.clear()
        for (note in notes) {
            rawNotes[note.id] = note
        }
        rebuildAll()
        loaded = true
        loadDeferred?.complete(Unit)
        loadDeferred = null
    }

    private fun handleIncrementalSnapshot(changes: List<DocumentChange>) {
        val affectedRoots = mutableSetOf<String>()

        for (change in changes) {
            if (change.document.metadata.hasPendingWrites()) continue

            val note = parseNote(change.document.id, change.document.data) ?: continue
            val rootId = note.rootNoteId ?: note.id

            // Always update rawNotes (Firestore data is never re-delivered if skipped).
            if (change.type == DocumentChange.Type.REMOVED) {
                rawNotes.remove(change.document.id)
            } else {
                rawNotes[change.document.id] = note
            }
            affectedRoots.add(rootId)
        }

        // Don't rebuild hot notes — local state is authoritative.
        // rawNotes may temporarily have stale data from a racing persist echo,
        // but the correct persist (always the last one debounced) will eventually
        // deliver the right data to rawNotes via a later snapshot.
        affectedRoots.removeAll { isHot(it) }

        if (affectedRoots.isNotEmpty()) {
            rebuildAffected(affectedRoots)
        }
    }

    // --- Internal reconstruction ---

    private fun rebuildAll() {
        _notes.value = rebuildAllNotes(rawNotes)
    }

    private fun rebuildAffected(rootIds: Set<String>) {
        val result = rebuildAffectedNotes(_notes.value, rootIds, rawNotes)
        if (result !== _notes.value) {
            _notes.value = result
        }
    }

    // --- Parsing ---

    @Suppress("UNCHECKED_CAST")
    private fun parseNote(id: String, data: Map<String, Any>?): Note? {
        if (data == null) return null
        return try {
            Note(
                id = id,
                userId = data["userId"] as? String ?: "",
                parentNoteId = data["parentNoteId"] as? String,
                content = data["content"] as? String ?: "",
                createdAt = data["createdAt"] as? com.google.firebase.Timestamp,
                updatedAt = data["updatedAt"] as? com.google.firebase.Timestamp,
                lastAccessedAt = data["lastAccessedAt"] as? com.google.firebase.Timestamp,
                tags = data["tags"] as? List<String> ?: emptyList(),
                containedNotes = data["containedNotes"] as? List<String> ?: emptyList(),
                state = data["state"] as? String,
                path = data["path"] as? String ?: "",
                rootNoteId = data["rootNoteId"] as? String,
                showCompleted = data["showCompleted"] as? Boolean ?: true,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing note $id", e)
            _error.value = "Failed to parse note $id: ${e.message}"
            null
        }
    }
}
