package org.alkaline.taskbrain.ui.currentnote

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmStage
import org.alkaline.taskbrain.data.RecurringAlarmRepository
import org.alkaline.taskbrain.service.AlarmScheduler
import org.alkaline.taskbrain.service.AlarmStateManager
import org.alkaline.taskbrain.service.RecurrenceTemplateManager
import org.alkaline.taskbrain.ui.currentnote.components.RecurrenceConfig
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.data.PrompterAgent
import org.alkaline.taskbrain.dsl.cache.MetadataHasher
import org.alkaline.taskbrain.dsl.directives.DirectiveResultRepository
import org.alkaline.taskbrain.dsl.runtime.NoteRepositoryOperations
import org.alkaline.taskbrain.ui.currentnote.undo.AlarmSnapshot

class CurrentNoteViewModel @JvmOverloads constructor(
    application: Application,
    // External dependencies - injectable for testing, default to real implementations
    private val repository: NoteRepository = NoteRepository(),
    private val alarmRepository: AlarmRepository = AlarmRepository(),
    private val alarmScheduler: AlarmScheduler = AlarmScheduler(application),
    private val alarmStateManager: AlarmStateManager = AlarmStateManager(application),
    private val sharedPreferences: SharedPreferences = application.getSharedPreferences("taskbrain_prefs", Context.MODE_PRIVATE),
    private val agent: PrompterAgent = PrompterAgent(),
    private val directiveResultRepository: DirectiveResultRepository = DirectiveResultRepository(),
    // For testing: provide a way to override noteOperations without Firebase
    private val noteOperationsProvider: (() -> NoteRepositoryOperations?)? = null
) : AndroidViewModel(application) {

    private val recurringAlarmRepository = RecurringAlarmRepository()
    private val templateManager = RecurrenceTemplateManager(recurringAlarmRepository, alarmRepository, alarmStateManager)

    val directiveManager = NoteDirectiveManager(
        scope = viewModelScope,
        repository = repository,
        directiveResultRepository = directiveResultRepository,
        noteOperationsProvider = noteOperationsProvider,
        getCurrentNoteId = { currentNoteId }
    )

    private val _saveStatus = MutableLiveData<UnifiedSaveStatus>()
    val saveStatus: LiveData<UnifiedSaveStatus> = _saveStatus

    // Event emitted when a save completes successfully - allows other screens to refresh
    private val _saveCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveCompleted: SharedFlow<Unit> = _saveCompleted.asSharedFlow()

    /** Emitted after save when new noteIds are assigned to lines that had none. Map: lineIndex → newNoteId */
    private val _newlyAssignedNoteIds = MutableSharedFlow<Map<Int, String>>(extraBufferCapacity = 1)
    val newlyAssignedNoteIds: SharedFlow<Map<Int, String>> = _newlyAssignedNoteIds.asSharedFlow()

    private val _loadStatus = MutableLiveData<LoadStatus>()
    val loadStatus: LiveData<LoadStatus> = _loadStatus
    
    // Add a LiveData specifically to signal that content has been modified externally (e.g., by AI)
    private val _contentModified = MutableLiveData<Boolean>()
    val contentModified: LiveData<Boolean> = _contentModified
    
    private val _isAgentProcessing = MutableLiveData<Boolean>(false)
    val isAgentProcessing: LiveData<Boolean> = _isAgentProcessing

    val alarmManager = NoteAlarmManager(
        application = application,
        scope = viewModelScope,
        alarmRepository = alarmRepository,
        alarmScheduler = alarmScheduler,
        alarmStateManager = alarmStateManager,
        recurringAlarmRepository = recurringAlarmRepository,
        templateManager = templateManager,
        getCurrentNoteId = { currentNoteId },
        getCurrentNoteLines = { currentNoteLines },
        getNoteIdForLine = ::getNoteIdForLine
    )

    // Whether the current note is deleted
    private val _isNoteDeleted = MutableLiveData<Boolean>(false)
    val isNoteDeleted: LiveData<Boolean> = _isNoteDeleted

    // Per-note toggle: whether completed (checked) lines are visible
    private val _showCompleted = MutableLiveData<Boolean>(true)
    val showCompleted: LiveData<Boolean> = _showCompleted

    /**
     * Whether the editor has unsaved changes. Set by the Screen on edits,
     * cleared on save. Used by the changedNoteIds handler to skip external
     * reloads during active editing (same role as dirtyRef on web).
     */
    @Volatile
    var dirty: Boolean = false


    init {
        directiveManager.start()
        // Start the NoteStore collection listener so notes stay fresh
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null) {
            NoteStore.start(FirebaseFirestore.getInstance(), userId)
        }
        // Wire up the persist callback so every NoteStore.updateNote() automatically
        // saves to Firestore (fire-and-forget). Track in-flight persist jobs so a
        // newer save cancels any older one to prevent stale writes.
        val persistJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
        NoteStore.setPersistCallback { noteId, content ->
            persistJobs[noteId]?.cancel()
            persistJobs[noteId] = viewModelScope.launch {
                repository.saveNoteWithFullContent(noteId, content)
                persistJobs.remove(noteId)
            }
        }
        // Surface NoteStore errors to the UI via directiveManager's saveWarning
        viewModelScope.launch {
            NoteStore.error.collect { errorMsg ->
                if (errorMsg != null) {
                    directiveManager.setSaveWarning(errorMsg)
                    NoteStore.clearError()
                }
            }
        }
        // When an edit session ends, refresh the current view
        directiveManager.addSessionEndListener {
            directiveManager.bumpDirectiveCacheGeneration()
        }
        // React to external changes (e.g., web app edits).
        // Two responsibilities:
        // 1. Reload editor if the current note's content changed
        // 2. Invalidate directive cache if any displayed note changed (for view directives)
        viewModelScope.launch {
            NoteStore.changedNoteIds.collect { changedIds ->
                val noteId = currentNoteId.takeIf { it.isNotEmpty() } ?: return@collect

                directiveManager.invalidateDirectivesForChangedNotes(changedIds)

                // Reload editor if the current note itself changed
                if (noteId !in changedIds) return@collect
                if (dirty) return@collect

                val storeNote = NoteStore.getNoteById(noteId) ?: return@collect
                val currentContent = (_loadStatus.value as? LoadStatus.Success)?.content ?: return@collect
                if (storeNote.content == currentContent) return@collect

                val storeLines = NoteStore.getNoteLinesById(noteId) ?: return@collect
                applyNoteContent(
                    noteId, storeNote.content, storeLines,
                    isDeleted = storeNote.state == "deleted",
                    showCompleted = storeNote.showCompleted,
                )
            }
        }
    }

    /**
     * Saves the current note to Firestore.
     * On success, handles post-save bookkeeping: updating noteIds,
     * notifying the UI, and syncing alarm line content.
     */
    private suspend fun persistCurrentNote(noteId: String, trackedLines: List<NoteLine>): Result<Map<Int, String>> {
        val result = repository.saveNoteWithChildren(noteId, trackedLines)
        result.fold(
            onSuccess = { newIdsMap ->
                // Update currentNoteLines with newly assigned IDs
                if (newIdsMap.isNotEmpty()) {
                    val updated = currentNoteLines.toMutableList()
                    for ((index, newId) in newIdsMap) {
                        if (index < updated.size) {
                            updated[index] = updated[index].copy(noteId = newId)
                        }
                    }
                    currentNoteLines = updated
                    _newlyAssignedNoteIds.tryEmit(newIdsMap)
                }
                alarmManager.syncAlarmLineContent(trackedLines)
                _saveStatus.value = UnifiedSaveStatus.Saved(noteId)
                _saveCompleted.tryEmit(Unit)
                markAsSaved()
            },
            onFailure = { e ->
                Log.e(TAG, "Error saving note", e)
                _saveStatus.value = UnifiedSaveStatus.PartialError(listOf(noteId), e)
            }
        )
        return result
    }

    // Current Note ID being edited — initialized from SharedPreferences so that LiveData
    // exposes the correct value immediately, preventing a race where the sync LaunchedEffect
    // in CurrentNoteScreen sets displayedNoteId to a stale default before loadContent runs.
    // For new users (no stored pref), starts as null/empty — loadContent will create a note.
    private val LAST_VIEWED_NOTE_KEY = "last_viewed_note_id"
    private var currentNoteId = sharedPreferences.getString(LAST_VIEWED_NOTE_KEY, null) ?: ""

    // Expose current note ID for UI (e.g., undo state persistence).
    // Nullable: null means no note loaded yet (new user before first note is created).
    private val _currentNoteIdLiveData = MutableLiveData<String?>(
        sharedPreferences.getString(LAST_VIEWED_NOTE_KEY, null)
    )
    val currentNoteIdLiveData: LiveData<String?> = _currentNoteIdLiveData

    /**
     * Gets the current note ID synchronously.
     */
    fun getCurrentNoteId(): String = currentNoteId

    /**
     * The ViewModel's current view of note lines with their noteIds.
     * Updated on load, save, and when new IDs are assigned.
     * This is the ViewModel's single source of truth for note-line identity.
     */
    private var currentNoteLines: List<NoteLine> = emptyList()

    /**
     * Gets the current tracked lines for cache updates.
     * Returns a copy to prevent external modification.
     */
    fun getTrackedLines(): List<NoteLine> = currentNoteLines

    /** Extracts noteIds from NoteLines for passing to EditorState via LoadStatus. */
    private fun noteLinesToNoteIds(lines: List<NoteLine>): List<List<String>> =
        lines.map { listOfNotNull(it.noteId) }

    /**
     * Apply resolved note data to the editor: update metadata, lines, load status, and directives.
     * All load paths (dirty cache, NoteStore, Firestore, external change) converge here.
     */
    private fun applyNoteContent(
        noteId: String,
        content: String,
        lines: List<NoteLine>,
        isDeleted: Boolean,
        showCompleted: Boolean,
    ) {
        _isNoteDeleted.value = isDeleted
        _showCompleted.value = showCompleted
        currentNoteLines = lines
        _loadStatus.value = LoadStatus.Success(noteId, content, noteLinesToNoteIds(lines))
        viewModelScope.launch {
            repository.updateLastAccessed(noteId)
            directiveManager.loadDirectiveResults(content, noteId)
        }
        alarmManager.loadAlarmStates()
    }

    /** Returns per-line noteIds for directive key generation. */
    fun getLineNoteIds(): List<String?> =
        currentNoteLines.map { it.noteId }

    /**
     * Push the current editor content to NoteStore immediately.
     * Called on every user edit so NoteStore always has the latest content.
     * View directives and tab switches just read NoteStore — no flush needed.
     * persist=false because explicit save handles Firestore.
     */
    fun pushContentToNoteStore(noteId: String, content: String) {
        val existing = NoteStore.getNoteById(noteId) ?: return
        if (existing.content == content) return
        NoteStore.updateNote(noteId, existing.copy(content = content), persist = false)
    }

    fun loadContent(noteId: String? = null, recentTabsViewModel: RecentTabsViewModel? = null) {
        val resolvedId = noteId?.takeIf { it.isNotEmpty() }
            ?: sharedPreferences.getString(LAST_VIEWED_NOTE_KEY, null)

        // On tab switch: invalidate directive cache so view directives re-execute
        // with fresh NoteStore data (pushed by the remember block on every edit).
        if (resolvedId != null && resolvedId != currentNoteId) {
            MetadataHasher.invalidateCache()
        }

        if (resolvedId == null) {
            // New user with no notes — create their first note
            _loadStatus.value = LoadStatus.Loading
            viewModelScope.launch {
                repository.createNote().fold(
                    onSuccess = { newId ->
                        Log.d(TAG, "loadContent: created first note for new user: $newId")
                        loadContent(newId, recentTabsViewModel)
                    },
                    onFailure = { e ->
                        Log.e(TAG, "loadContent: failed to create first note", e)
                        _loadStatus.value = LoadStatus.Error(e)
                    }
                )
            }
            return
        }

        currentNoteId = resolvedId
        _currentNoteIdLiveData.value = currentNoteId

        // A leftover Saved status from the old note blocks the external
        // change detector (NoteStore collector) for the new note.
        _saveStatus.value = UnifiedSaveStatus.Idle

        // Capture noteId for all coroutines in this method. currentNoteId is a mutable
        // field that changes on the next loadContent call, so coroutines MUST NOT read it.
        val noteId = resolvedId

        Log.d(TAG, "loadContent: switching to noteId=$noteId, storeNotes=${NoteStore.notes.value.size}")

        // Save the current note as the last viewed note
        sharedPreferences.edit().putString(LAST_VIEWED_NOTE_KEY, noteId).apply()

        // Reset note lines for the new note
        currentNoteLines = emptyList()

        // Clear expanded state from previous note
        directiveManager.clearExpandedState()

        // --- Tab-switch loading priority (mirrors web's useEditor pattern) ---
        // 1. Dirty editor cache: restore unsaved edits immediately (user's in-progress work)
        // 2. NoteStore: always-fresh reconstructed content (instant, no Firestore round-trip)
        // 3. Firestore: canonical load with full noteId mappings
        //
        // The RecentTabsViewModel cache stores NoteLine[] with per-line noteId mappings.
        // It is ONLY used for dirty notes (unsaved edits).
        // For clean notes, NoteStore has the canonical content and Firestore provides
        // noteId mappings in a background refresh.

        // Path 1: Dirty editor cache — restore unsaved edits
        val cached = recentTabsViewModel?.getCachedContent(noteId)
        if (cached != null && cached.isDirty) {
            val fullContent = cached.noteLines.joinToString("\n") { it.content }
            Log.d(TAG, "loadContent: restoring dirty editor cache for $noteId")
            applyNoteContent(
                noteId, fullContent, cached.noteLines,
                isDeleted = cached.isDeleted,
                showCompleted = NoteStore.getNoteById(noteId)?.showCompleted ?: true,
            )

            // Background refresh for proper noteId mappings (the fire-and-forget save
            // may have created new child notes with IDs we don't have yet)
            viewModelScope.launch {
                repository.loadNoteWithChildren(noteId).onSuccess { result ->
                    if (noteId != currentNoteId) return@onSuccess
                    val freshContent = result.lines.joinToString("\n") { it.content }
                    if (freshContent == fullContent) {
                        // Content matches — update noteId mappings without changing editor
                        currentNoteLines = result.lines
                    }
                }
            }
            return
        }
        // Clean cache entries are not useful — NoteStore has fresher data
        recentTabsViewModel?.invalidateCache(noteId)

        // Path 2: NoteStore — instant display from the collection listener's data
        val storeNote = NoteStore.getNoteById(noteId)
        if (storeNote != null) {
            val content = storeNote.content
            // Use getNoteLinesById to get proper noteId mappings from the in-memory tree,
            // avoiding the race condition where a save could happen before a background
            // Firestore refresh provides the real IDs.
            val storeLines = NoteStore.getNoteLinesById(noteId) ?: content.lines().mapIndexed { index, line ->
                NoteLine(content = line, noteId = if (index == 0) noteId else null)
            }
            Log.d(TAG, "loadContent: using NoteStore content for $noteId (${storeLines.size} lines, noteIds: ${storeLines.count { it.noteId != null }}/${storeLines.size})")
            applyNoteContent(
                noteId, content, storeLines,
                isDeleted = storeNote.state == "deleted",
                showCompleted = storeNote.showCompleted,
            )
            return
        }

        // Path 3: Firestore — full load (first visit or store not ready)
        Log.d(TAG, "loadContent: cache miss for $noteId, fetching from Firebase")

        // Cache miss - fetch from Firebase
        _loadStatus.value = LoadStatus.Loading

        viewModelScope.launch {
            val result = repository.loadNoteWithChildren(noteId)
            result.fold(
                onSuccess = { (loadedLines, isDeleted, showCompleted) ->
                    val fullContent = loadedLines.joinToString("\n") { it.content }
                    applyNoteContent(noteId, fullContent, loadedLines, isDeleted, showCompleted)
                },
                onFailure = { e ->
                    if (isPermissionDenied(e)) {
                        // Note belongs to a different user (stale SharedPreferences
                        // from a previous sign-in). Clear the stale pref and create
                        // a fresh note for the current user.
                        Log.w(TAG, "Permission denied loading note $noteId — creating new note for current user")
                        sharedPreferences.edit().remove(LAST_VIEWED_NOTE_KEY).apply()
                        loadContent(null, recentTabsViewModel)
                    } else {
                        Log.e(TAG, "Error loading note", e)
                        _loadStatus.value = LoadStatus.Error(e)
                    }
                }
            )
        }
    }

    private fun isPermissionDenied(e: Throwable): Boolean {
        val firestoreException = e as? FirebaseFirestoreException
            ?: e.cause as? FirebaseFirestoreException
        return firestoreException?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    }

    /**
     * Updates currentNoteLines from raw content using content-based matching.
     * Used as a fallback when editor noteIds are not available.
     */
    fun updateTrackedLines(newContent: String) {
        val newLinesContent = newContent.lines()
        val oldLines = currentNoteLines

        if (oldLines.isEmpty()) {
            currentNoteLines = newLinesContent.mapIndexed { index, content ->
                NoteLine(content, if (index == 0) currentNoteId else null)
            }
            return
        }

        val contentToOldIndices = mutableMapOf<String, MutableList<Int>>()
        oldLines.forEachIndexed { index, line ->
            contentToOldIndices.getOrPut(line.content) { mutableListOf() }.add(index)
        }

        val newIds = arrayOfNulls<String>(newLinesContent.size)
        val oldConsumed = BooleanArray(oldLines.size)

        // Exact matches
        newLinesContent.forEachIndexed { index, content ->
            val indices = contentToOldIndices[content]
            if (!indices.isNullOrEmpty()) {
                val oldIdx = indices.removeAt(0)
                newIds[index] = oldLines[oldIdx].noteId
                oldConsumed[oldIdx] = true
            }
        }

        // Similarity-based matching for modifications and splits
        org.alkaline.taskbrain.data.performSimilarityMatching(
            unmatchedNewIndices = newLinesContent.indices.filter { newIds[it] == null }.toSet(),
            unconsumedOldIndices = oldLines.indices.filter { !oldConsumed[it] },
            getOldContent = { oldLines[it].content },
            getNewContent = { newLinesContent[it] },
        ) { oldIdx, newIdx ->
            newIds[newIdx] = oldLines[oldIdx].noteId
            oldConsumed[oldIdx] = true
        }

        val result = newLinesContent.mapIndexed { index, content ->
            NoteLine(content, newIds[index])
        }.toMutableList()

        // Ensure first line always has parent ID
        if (result.isNotEmpty() && result[0].noteId != currentNoteId) {
            result[0] = result[0].copy(noteId = currentNoteId)
        }

        currentNoteLines = result
    }

    fun toggleShowCompleted() {
        val newValue = !(_showCompleted.value ?: true)
        _showCompleted.value = newValue
        val capturedNoteId = currentNoteId
        viewModelScope.launch {
            repository.updateShowCompleted(capturedNoteId, newValue).onFailure { e ->
                Log.e(TAG, "Failed to persist showCompleted", e)
            }
        }
    }

    fun saveContent(content: String, lineNoteIds: List<List<String>> = emptyList()) {
        // Use editor noteIds when available (they track undo/redo and line moves).
        // Fall back to content-based matching when editor noteIds are empty
        // (can happen when loaded via updateFromText instead of initFromNoteLines).
        val capturedLines = if (lineNoteIds.any { it.isNotEmpty() }) {
            resolveNoteIds(content.lines(), lineNoteIds)
        } else {
            // Fallback: build from content with no noteId info
            updateTrackedLines(content)
            currentNoteLines
        }
        currentNoteLines = capturedLines

        _saveStatus.value = UnifiedSaveStatus.Saving

        // Capture noteId now — currentNoteId may change before the coroutine runs
        val savedNoteId = currentNoteId

        // Update NoteStore synchronously — before the async Firestore write.
        // markHot (called by updateNote) prevents the collection listener from
        // overwriting this with a potentially stale Firestore echo.
        val existing = NoteStore.getNoteById(savedNoteId)
        if (existing != null) {
            NoteStore.updateNote(savedNoteId, existing.copy(content = content), persist = false)
        }
        MetadataHasher.invalidateCache()

        // persistCurrentNote does a structured save (with noteId mappings) — more
        // precise than the auto-persist callback, so we passed persist = false above.
        viewModelScope.launch {
            val result = persistCurrentNote(savedNoteId, capturedLines)

            result.onSuccess {
                alarmManager.syncAlarmNoteIds(capturedLines)
                directiveManager.onSaveCompleted(content)
            }
        }
    }

    fun processAgentCommand(currentContent: String, command: String) {
        _isAgentProcessing.value = true
        val capturedNoteId = currentNoteId
        viewModelScope.launch {
            try {
                val updatedContent = agent.processCommand(currentContent, command)
                // Also update tracked lines since content changed externally
                updateTrackedLines(updatedContent)

                // Update the UI with the new content
                _loadStatus.value = LoadStatus.Success(
                    capturedNoteId,
                    updatedContent,
                    noteLinesToNoteIds(currentNoteLines)
                )
                
                // Signal that the content has been modified and is unsaved
                _contentModified.value = true
            } catch (e: Exception) {
                Log.e("CurrentNoteViewModel", "Agent processing failed", e)
                _loadStatus.value = LoadStatus.Error(e)
            } finally {
                _isAgentProcessing.value = false
            }
        }
    }

    /**
     * Gets the note ID for a given line index.
     * Returns the parent note ID if the line doesn't have an associated note.
     */
    fun getNoteIdForLine(lineIndex: Int): String {
        return if (lineIndex < currentNoteLines.size) {
            currentNoteLines[lineIndex].noteId ?: currentNoteId
        } else {
            currentNoteId
        }
    }

    /**
     * Saves the note, then creates a new alarm for the specified line.
     * For the main editor, saves via [persistCurrentNote]; for inline editors,
     * saves via [NoteDirectiveManager.saveInlineEditSession].
     */
    fun saveAndCreateAlarm(
        content: String,
        lineContent: String,
        lineIndex: Int? = null,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES,
        inlineSession: InlineEditSession? = null
    ) {
        viewModelScope.launch {
            val noteId = saveAndResolveNoteId(content, lineIndex, inlineSession)
            alarmManager.createAlarmForNote(noteId, lineContent, lineIndex, dueTime, stages)
        }
    }

    /**
     * Saves the note, then creates a recurring alarm for the specified line.
     * For the main editor, saves via [persistCurrentNote]; for inline editors,
     * saves via [NoteDirectiveManager.saveInlineEditSession].
     */
    fun saveAndCreateRecurringAlarm(
        content: String,
        lineContent: String,
        lineIndex: Int? = null,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES,
        recurrenceConfig: RecurrenceConfig,
        inlineSession: InlineEditSession? = null
    ) {
        viewModelScope.launch {
            val noteId = saveAndResolveNoteId(content, lineIndex, inlineSession)
            alarmManager.createRecurringAlarmForNote(noteId, lineContent, lineIndex, dueTime, stages, recurrenceConfig)
        }
    }

    /**
     * Saves the note and resolves the noteId for the given line.
     * Routes to the inline or main editor save path based on [inlineSession].
     */
    private suspend fun saveAndResolveNoteId(
        content: String,
        lineIndex: Int?,
        inlineSession: InlineEditSession?
    ): String {
        return if (inlineSession != null) {
            directiveManager.saveInlineEditSession(inlineSession)
            val line = lineIndex?.let { inlineSession.editorState.lines.getOrNull(it) }
            line?.noteIds?.firstOrNull() ?: inlineSession.noteId
        } else {
            updateTrackedLines(content)
            persistCurrentNote(currentNoteId, currentNoteLines)
            if (lineIndex != null) getNoteIdForLine(lineIndex) else currentNoteId
        }
    }

    override fun onCleared() {
        super.onCleared()
        directiveManager.stop()
    }

    companion object {
        private const val TAG = "CurrentNoteViewModel"
    }

    fun markAsSaved() {
        _contentModified.value = false
        dirty = false
    }

    fun clearSaveError() {
        if (_saveStatus.value is UnifiedSaveStatus.PartialError) {
            _saveStatus.value = UnifiedSaveStatus.Idle
        }
    }

    fun clearLoadError() {
        if (_loadStatus.value is LoadStatus.Error) {
            _loadStatus.value = null
        }
    }

    /**
     * Soft-deletes the current note.
     */
    fun deleteCurrentNote(onSuccess: () -> Unit) {
        val capturedNoteId = currentNoteId
        viewModelScope.launch {
            val result = repository.softDeleteNote(capturedNoteId)
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Note deleted successfully: $capturedNoteId")
                    _isNoteDeleted.value = true
                    onSuccess()
                },
                onFailure = { e ->
                    Log.e(TAG, "Error deleting note", e)
                    _saveStatus.value = UnifiedSaveStatus.PartialError(listOf(capturedNoteId), e)
                }
            )
        }
    }

    /**
     * Restores the current note from deleted state.
     */
    fun undeleteCurrentNote(onSuccess: () -> Unit) {
        val capturedNoteId = currentNoteId
        viewModelScope.launch {
            val result = repository.undeleteNote(capturedNoteId)
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Note restored successfully: $capturedNoteId")
                    _isNoteDeleted.value = false
                    onSuccess()
                },
                onFailure = { e ->
                    Log.e(TAG, "Error restoring note", e)
                    _saveStatus.value = UnifiedSaveStatus.PartialError(listOf(capturedNoteId), e)
                }
            )
        }
    }

    /**
     * Saves the main note and all dirty inline edit sessions at once.
     * On partial failure, saves what succeeds and reports the failures.
     */
    fun saveAll(
        content: String,
        lineNoteIds: List<List<String>>,
        dirtySessions: List<InlineEditSession>
    ) {
        _saveStatus.value = UnifiedSaveStatus.Saving

        val savedNoteId = currentNoteId

        // Prepare main note lines
        val capturedLines = if (lineNoteIds.any { it.isNotEmpty() }) {
            resolveNoteIds(content.lines(), lineNoteIds)
        } else {
            updateTrackedLines(content)
            currentNoteLines
        }
        currentNoteLines = capturedLines

        // Update NoteStore synchronously before async writes
        val existing = NoteStore.getNoteById(savedNoteId)
        if (existing != null) {
            NoteStore.updateNote(savedNoteId, existing.copy(content = content), persist = false)
        }
        MetadataHasher.invalidateCache()

        viewModelScope.launch {
            val failedNoteIds = mutableListOf<String>()
            var lastError: Throwable? = null

            // Save main note (without persistCurrentNote's status side effects)
            val mainResult = repository.saveNoteWithChildren(savedNoteId, capturedLines)
            mainResult.onSuccess { newIdsMap ->
                if (newIdsMap.isNotEmpty()) {
                    val updated = currentNoteLines.toMutableList()
                    for ((index, newId) in newIdsMap) {
                        if (index < updated.size) {
                            updated[index] = updated[index].copy(noteId = newId)
                        }
                    }
                    currentNoteLines = updated
                    _newlyAssignedNoteIds.tryEmit(newIdsMap)
                }
                alarmManager.syncAlarmLineContent(capturedLines)
                alarmManager.syncAlarmNoteIds(capturedLines)
                directiveManager.onSaveCompleted(content)
            }.onFailure { e ->
                failedNoteIds.add(savedNoteId)
                lastError = e
            }

            // Save all dirty inline sessions
            for (session in dirtySessions) {
                try {
                    directiveManager.saveInlineEditSessionSync(session)
                    session.markSaved()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save inline session ${session.noteId}", e)
                    failedNoteIds.add(session.noteId)
                    lastError = e
                }
            }

            if (failedNoteIds.isEmpty()) {
                _saveStatus.value = UnifiedSaveStatus.Saved(savedNoteId)
                _saveCompleted.tryEmit(Unit)
                markAsSaved()
            } else {
                _saveStatus.value = UnifiedSaveStatus.PartialError(failedNoteIds, lastError!!)
            }
        }
    }

    /**
     * Sets the save status to Dirty. Called by the screen when the user edits content.
     */
    fun markAsDirty() {
        if (_saveStatus.value !is UnifiedSaveStatus.Saving) {
            _saveStatus.value = UnifiedSaveStatus.Dirty
        }
    }
}

sealed class UnifiedSaveStatus {
    object Idle : UnifiedSaveStatus()
    object Dirty : UnifiedSaveStatus()
    object Saving : UnifiedSaveStatus()
    /** @param noteId the note that was saved (may differ from currentNoteId after a tab switch) */
    data class Saved(val noteId: String) : UnifiedSaveStatus()
    data class PartialError(val failedNoteIds: List<String>, val error: Throwable) : UnifiedSaveStatus()
}

sealed class LoadStatus {
    object Loading : LoadStatus()
    data class Success(
        val noteId: String,
        val content: String,
        val lineNoteIds: List<List<String>> = emptyList(),
    ) : LoadStatus()
    data class Error(val throwable: Throwable) : LoadStatus()
}

/**
 * Event emitted when an alarm is successfully created.
 */
data class AlarmCreatedEvent(
    val alarmId: String,
    val lineContent: String,
    val alarmSnapshot: AlarmSnapshot? = null,
    val recurringAlarmId: String? = null
)

/**
 * Warning shown when alarm recreation fails during redo and the document is rolled back.
 * @param rollbackSucceeded true if the cleanup undo succeeded, false if document may be inconsistent
 * @param errorMessage the underlying error message from the alarm creation failure
 */
data class RedoRollbackWarning(
    val rollbackSucceeded: Boolean,
    val errorMessage: String
)
