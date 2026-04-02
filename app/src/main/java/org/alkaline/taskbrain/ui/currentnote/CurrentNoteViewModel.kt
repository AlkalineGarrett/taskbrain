package org.alkaline.taskbrain.ui.currentnote

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmStage
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.RecurringAlarm
import org.alkaline.taskbrain.data.RecurringAlarmRepository
import org.alkaline.taskbrain.data.toTimeOfDay

import org.alkaline.taskbrain.service.AlarmScheduleResult
import org.alkaline.taskbrain.service.AlarmScheduler
import org.alkaline.taskbrain.service.AlarmStateManager
import org.alkaline.taskbrain.service.RecurrenceConfigMapper
import org.alkaline.taskbrain.service.RecurrenceTemplateManager
import org.alkaline.taskbrain.service.RecurrenceScheduler
import org.alkaline.taskbrain.ui.currentnote.components.RecurrenceConfig
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.data.PrompterAgent
import org.alkaline.taskbrain.dsl.cache.CachedDirectiveExecutor
import org.alkaline.taskbrain.dsl.cache.CachedDirectiveExecutorFactory
import org.alkaline.taskbrain.dsl.cache.DirectiveCacheManager
import org.alkaline.taskbrain.dsl.cache.EditSessionManager
import org.alkaline.taskbrain.dsl.cache.MetadataHasher
import org.alkaline.taskbrain.dsl.cache.RefreshScheduler
import org.alkaline.taskbrain.dsl.cache.RefreshTriggerAnalyzer
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.DirectiveSegment
import org.alkaline.taskbrain.dsl.directives.DirectiveResultRepository
import org.alkaline.taskbrain.dsl.directives.ScheduleManager
import org.alkaline.taskbrain.dsl.runtime.values.AlarmVal
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.dsl.language.RefreshExpr
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.MutationType
import org.alkaline.taskbrain.dsl.runtime.NoteContext
import org.alkaline.taskbrain.dsl.runtime.NoteMutation
import org.alkaline.taskbrain.dsl.runtime.NoteRepositoryOperations
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState
import org.alkaline.taskbrain.ui.currentnote.undo.AlarmSnapshot
import org.alkaline.taskbrain.ui.currentnote.util.SymbolOverlay
import org.alkaline.taskbrain.util.PermissionHelper

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

    // Directive caching infrastructure
    private val directiveCacheManager = DirectiveCacheManager()
    private val editSessionManager = EditSessionManager(directiveCacheManager)
    private val cachedDirectiveExecutor = CachedDirectiveExecutor(
        cacheManager = directiveCacheManager,
        editSessionManager = editSessionManager
    )
    private val refreshScheduler = RefreshScheduler(
        onTrigger = { cacheKey, noteId ->
            // When a refresh trigger fires, clear the cache entry
            if (noteId != null) {
                directiveCacheManager.clearNote(noteId)
            } else {
                directiveCacheManager.clearAll()
            }
        }
    )

    // Note operations for DSL mutations
    // Use injected provider for testing, or create from Firebase for production
    private val noteOperations: NoteRepositoryOperations?
        get() {
            // If a provider was injected (for testing), use it
            noteOperationsProvider?.let { return it() }
            // Otherwise, use Firebase (production)
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return null
            return NoteRepositoryOperations(FirebaseFirestore.getInstance(), userId)
        }

    private val _saveStatus = MutableLiveData<SaveStatus>()
    val saveStatus: LiveData<SaveStatus> = _saveStatus

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

    // Alarm creation status - signals when to insert alarm symbol
    private val _alarmCreated = MutableLiveData<AlarmCreatedEvent?>()
    val alarmCreated: LiveData<AlarmCreatedEvent?> = _alarmCreated

    // Alarm error state
    private val _alarmError = MutableLiveData<Throwable?>()
    val alarmError: LiveData<Throwable?> = _alarmError

    // Alarm permission warning
    private val _alarmPermissionWarning = MutableLiveData<Boolean>(false)
    val alarmPermissionWarning: LiveData<Boolean> = _alarmPermissionWarning

    // Notification permission warning
    private val _notificationPermissionWarning = MutableLiveData<Boolean>(false)
    val notificationPermissionWarning: LiveData<Boolean> = _notificationPermissionWarning

    // Alarm undo/redo operation in progress - used to disable buttons during async operations
    private val _isAlarmOperationPending = MutableLiveData<Boolean>(false)
    val isAlarmOperationPending: LiveData<Boolean> = _isAlarmOperationPending

    // Warning shown when alarm redo fails and document is rolled back
    private val _redoRollbackWarning = MutableLiveData<RedoRollbackWarning?>()
    val redoRollbackWarning: LiveData<RedoRollbackWarning?> = _redoRollbackWarning

    // Whether the current note is deleted
    private val _isNoteDeleted = MutableLiveData<Boolean>(false)
    val isNoteDeleted: LiveData<Boolean> = _isNoteDeleted

    // Per-note toggle: whether completed (checked) lines are visible
    private val _showCompleted = MutableLiveData<Boolean>(true)
    val showCompleted: LiveData<Boolean> = _showCompleted

    // Bumped after directive cache or expanded state changes, triggering recomposition
    // so the synchronous computeDirectiveResults() picks up new results/state.
    private val _directiveCacheGeneration = MutableLiveData(0)
    val directiveCacheGeneration: LiveData<Int> = _directiveCacheGeneration

    // Tracks which directives are expanded (by hash key). Default state is collapsed.
    private val expandedDirectiveHashes = mutableSetOf<String>()

    // Button execution states - maps directive key to execution state
    private val _buttonExecutionStates = MutableLiveData<Map<String, ButtonExecutionState>>(emptyMap())
    val buttonExecutionStates: LiveData<Map<String, ButtonExecutionState>> = _buttonExecutionStates

    // Button error messages - maps directive key to error message
    private val _buttonErrors = MutableLiveData<Map<String, String>>(emptyMap())
    val buttonErrors: LiveData<Map<String, String>> = _buttonErrors

    /** Clear the button error for a specific directive */
    fun clearButtonError(directiveKey: String) {
        val current = _buttonErrors.value?.toMutableMap() ?: mutableMapOf()
        current.remove(directiveKey)
        _buttonErrors.value = current
    }

    /**
     * Invalidate the notes cache so view directives get fresh content.
     * Call this when switching tabs after saving to ensure views show updated data.
     */
    fun invalidateNotesCache() {
        // NoteStore is always up to date via its collection listener.
        // Only invalidate the derivative caches.
        MetadataHasher.invalidateCache()
        directiveCacheManager.clearAll()
    }

    // Emits when notes cache is refreshed (e.g., after save) so UI can refresh view directives
    private val _notesCacheRefreshed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notesCacheRefreshed: SharedFlow<Unit> = _notesCacheRefreshed.asSharedFlow()

    // Start refresh scheduler on ViewModel creation and set up edit session listener
    init {
        refreshScheduler.start()
        // Start the NoteStore collection listener so notes stay fresh
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null) {
            NoteStore.start(FirebaseFirestore.getInstance(), userId)
        }
        // Wire up the persist callback so every NoteStore.updateNote() automatically
        // saves to Firestore (fire-and-forget). This eliminates the need for callers
        // to remember to pair NoteStore updates with separate Firestore writes.
        // Track in-flight persist jobs so a newer save cancels any older one.
        // Without this, two concurrent saveNoteWithFullContent calls can race,
        // and the older (stale) write might complete last, overwriting the newer content.
        val persistJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
        NoteStore.setPersistCallback { noteId, content ->
            persistJobs[noteId]?.cancel()
            persistJobs[noteId] = viewModelScope.launch {
                repository.saveNoteWithFullContent(noteId, content)
                persistJobs.remove(noteId)
            }
        }
        // Surface NoteStore errors (snapshot listener failures, parse errors) to the UI
        viewModelScope.launch {
            NoteStore.error.collect { errorMsg ->
                if (errorMsg != null) {
                    _saveWarning.postValue(errorMsg)
                    NoteStore.clearError()
                }
            }
        }
        // When an edit session ends, refresh the current view
        editSessionManager.addSessionEndListener {
            // Re-execute directives to pick up any changes that were suppressed
            bumpDirectiveCacheGeneration()
        }
    }

    // External change detection is handled by NoteStore's collection listener
    // with hot/cool protection. No per-note snapshot listener needed.

    /**
     * Saves the current note to Firestore.
     * On success, handles post-save bookkeeping: updating noteIds,
     * notifying the UI, and syncing alarm line content.
     * NoteStore's hot/cool mechanism prevents the collection listener from
     * overwriting local state with a stale Firestore echo.
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
                syncAlarmLineContent(trackedLines)
                _saveStatus.value = SaveStatus.Success(noteId)
                _saveCompleted.tryEmit(Unit)
                markAsSaved()
            },
            onFailure = { e ->
                Log.e(TAG, "Error saving note", e)
                _saveStatus.value = SaveStatus.Error(e)
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

        // A leftover SaveStatus.Success from the old note blocks the external
        // change detector (NoteStore collector) for the new note.
        _saveStatus.value = null

        // Capture noteId for all coroutines in this method. currentNoteId is a mutable
        // field that changes on the next loadContent call, so coroutines MUST NOT read it.
        val noteId = resolvedId

        Log.d(TAG, "loadContent: switching to noteId=$noteId, storeNotes=${NoteStore.notes.value.size}")

        // Save the current note as the last viewed note
        sharedPreferences.edit().putString(LAST_VIEWED_NOTE_KEY, noteId).apply()

        // Reset note lines for the new note
        currentNoteLines = emptyList()

        // Clear expanded state from previous note
        expandedDirectiveHashes.clear()

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
            _isNoteDeleted.value = cached.isDeleted
            _showCompleted.value = NoteStore.getNoteById(noteId)?.showCompleted ?: true
            currentNoteLines = cached.noteLines
            _loadStatus.value = LoadStatus.Success(noteId, fullContent, noteLinesToNoteIds(cached.noteLines))
            viewModelScope.launch {
                repository.updateLastAccessed(noteId)
                loadDirectiveResults(fullContent, noteId)
            }
            loadAlarmStates()

            // Background refresh for proper noteId mappings (the fire-and-forget save
            // may have created new child notes with IDs we don't have yet)
            viewModelScope.launch {
                repository.loadNoteWithChildren(noteId).onSuccess { freshLines ->
                    if (noteId != currentNoteId) return@onSuccess
                    val freshContent = freshLines.joinToString("\n") { it.content }
                    if (freshContent == fullContent) {
                        // Content matches — update noteId mappings without changing editor
                        currentNoteLines = freshLines
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
            _isNoteDeleted.value = storeNote.state == "deleted"
            _showCompleted.value = storeNote.showCompleted
            currentNoteLines = storeLines
            _loadStatus.value = LoadStatus.Success(noteId, content, noteLinesToNoteIds(storeLines))
            viewModelScope.launch {
                repository.updateLastAccessed(noteId)
                loadDirectiveResults(content, noteId)
            }
            loadAlarmStates()
            return
        }

        // Path 3: Firestore — full load (first visit or store not ready)
        Log.d(TAG, "loadContent: cache miss for $noteId, fetching from Firebase")

        // Cache miss - fetch from Firebase
        _loadStatus.value = LoadStatus.Loading

        viewModelScope.launch {
            // Load note metadata (deleted state + showCompleted)
            repository.loadNoteById(noteId).fold(
                onSuccess = { note ->
                    _isNoteDeleted.value = note?.state == "deleted"
                    _showCompleted.value = note?.showCompleted ?: true
                },
                onFailure = {
                    _isNoteDeleted.value = false
                    _showCompleted.value = true
                }
            )

            // Update lastAccessedAt (fire-and-forget, doesn't block loading)
            launch { repository.updateLastAccessed(noteId) }

            val result = repository.loadNoteWithChildren(noteId)
            result.fold(
                onSuccess = { loadedLines ->
                    currentNoteLines = loadedLines
                    val fullContent = loadedLines.joinToString("\n") { it.content }
                    _loadStatus.value = LoadStatus.Success(noteId, fullContent, noteLinesToNoteIds(loadedLines))

                    // Load cached directive results and execute any missing directives
                    loadDirectiveResults(fullContent)

                    loadAlarmStates()
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

        _saveStatus.value = SaveStatus.Saving

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
                syncAlarmNoteIds(capturedLines)
                executeAndStoreDirectives(content)
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
     * Syncs alarm line content with the current note content.
     * Called after saving to keep alarm display text up to date.
     */
    private fun syncAlarmLineContent(trackedLines: List<org.alkaline.taskbrain.data.NoteLine>) {
        viewModelScope.launch {
            for (line in trackedLines) {
                line.noteId?.let { noteId ->
                    alarmRepository.updateLineContentForNote(noteId, line.content)
                }
            }
        }
    }

    /**
     * Syncs alarm noteIds with line noteIds.
     * For each line containing alarm directives, updates the alarm's noteId
     * if it doesn't match the line's current noteId.
     */
    private fun syncAlarmNoteIds(trackedLines: List<NoteLine>) {
        viewModelScope.launch {
            val updates = findAlarmNoteIdUpdates(trackedLines) { alarmId ->
                alarmRepository.getAlarm(alarmId).getOrNull()?.noteId
            }
            for (update in updates) {
                alarmRepository.updateAlarmNoteId(update.alarmId, update.lineNoteId)
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

    // Recurrence config and template for the currently selected alarm (if recurring)
    private val _recurrenceConfig = MutableLiveData<RecurrenceConfig?>()
    val recurrenceConfig: LiveData<RecurrenceConfig?> = _recurrenceConfig

    private val _recurringAlarm = MutableLiveData<RecurringAlarm?>()
    val recurringAlarm: LiveData<RecurringAlarm?> = _recurringAlarm

    /**
     * Fetches a specific alarm by its document ID.
     */
    fun fetchAlarmById(alarmId: String, onComplete: (Alarm?) -> Unit) {
        viewModelScope.launch {
            val alarm = alarmRepository.getAlarm(alarmId).getOrNull()
            onComplete(alarm)
        }
    }

    /**
     * Fetches the recurrence config for an alarm, if it's recurring.
     * Call this when opening the alarm dialog for an existing alarm.
     */
    fun fetchRecurrenceConfig(alarm: Alarm?) {
        if (alarm?.recurringAlarmId == null) {
            _recurrenceConfig.value = null
            _recurringAlarm.value = null
            return
        }
        viewModelScope.launch {
            val recurringRepo = recurringAlarmRepository
            val recurring = recurringRepo.get(alarm.recurringAlarmId).getOrNull()
            _recurringAlarm.value = recurring
            _recurrenceConfig.value = recurring?.let { RecurrenceConfigMapper.toRecurrenceConfig(it) }
        }
    }

    suspend fun getInstancesForRecurring(recurringAlarmId: String): List<Alarm> {
        return alarmRepository.getInstancesForRecurring(recurringAlarmId)
            .onFailure { _alarmError.value = it }
            .getOrDefault(emptyList())
    }

    // Alarm data keyed by alarm document ID, for rendering symbol overlays.
    // The LiveData values are scoped to the current note's referenced alarms.
    // The backing stores persist across note navigations so previously-fetched
    // alarm data is available immediately on revisit.
    private val _alarmCache = MutableLiveData<Map<String, Alarm>>(emptyMap())
    val alarmCache: LiveData<Map<String, Alarm>> = _alarmCache
    private val alarmCacheStore = mutableMapOf<String, Alarm>()

    // Recurring alarm cache: maps recurrence ID → current instance alarm
    private val _recurringAlarmCache = MutableLiveData<Map<String, Alarm>>(emptyMap())
    val recurringAlarmCache: LiveData<Map<String, Alarm>> = _recurringAlarmCache
    private val recurringAlarmCacheStore = mutableMapOf<String, Alarm>()

    /**
     * Loads all alarms referenced by alarm directives in the current note's lines.
     * Handles both [alarm("id")] and [recurringAlarm("id")] directives.
     * For recurring directives, fetches the RecurringAlarm → currentAlarmId → alarm instance.
     *
     * Uses a persistent cache so previously-fetched alarm data renders immediately
     * on the first frame, then refreshes from Firestore in the background.
     */
    fun loadAlarmStates() {
        val extracted = extractAlarmIds(currentNoteLines)
        if (extracted.alarmIds.isEmpty() && extracted.recurringAlarmIds.isEmpty()) {
            _alarmCache.value = emptyMap()
            _recurringAlarmCache.value = emptyMap()
            return
        }

        // Serve from persistent cache immediately so overlays render on the first frame.
        // Filter to only alarm IDs referenced by this note's content.
        val cachedDirect = extracted.alarmIds
            .mapNotNull { id -> alarmCacheStore[id]?.let { id to it } }.toMap()
        val cachedRecurring = extracted.recurringAlarmIds
            .mapNotNull { id -> recurringAlarmCacheStore[id]?.let { id to it } }.toMap()
        if (cachedDirect.isNotEmpty()) _alarmCache.value = cachedDirect
        if (cachedRecurring.isNotEmpty()) _recurringAlarmCache.value = cachedRecurring

        viewModelScope.launch {
            coroutineScope {
                // Load direct alarm references and recurring alarm references in parallel
                val directDeferred = async {
                    if (extracted.alarmIds.isNotEmpty()) {
                        alarmRepository.getAlarmsByIds(extracted.alarmIds).fold(
                            onSuccess = { it },
                            onFailure = { e ->
                                Log.e(TAG, "Error loading alarm states", e)
                                emptyMap()
                            }
                        )
                    } else {
                        emptyMap()
                    }
                }

                val recurringDeferred = async {
                    if (extracted.recurringAlarmIds.isNotEmpty()) {
                        // Resolve each recurring alarm in parallel
                        val entries = extracted.recurringAlarmIds.map { recId ->
                            async { recId to alarmRepository.resolveCurrentInstance(recId) }
                        }.awaitAll()
                        entries.filter { it.second != null }.associate { it.first to it.second!! }
                    } else {
                        emptyMap()
                    }
                }

                val freshDirect = directDeferred.await()
                val freshRecurring = recurringDeferred.await()

                // Update persistent cache with fresh data
                alarmCacheStore.putAll(freshDirect)
                recurringAlarmCacheStore.putAll(freshRecurring)

                _alarmCache.value = freshDirect
                _recurringAlarmCache.value = freshRecurring
            }
        }
    }

    /**
     * Fetches the current alarm instance for a recurring alarm ID.
     * Used when tapping a [recurringAlarm("id")] directive.
     */
    fun fetchRecurringAlarmInstance(recurringAlarmId: String, onComplete: (Alarm?) -> Unit) {
        viewModelScope.launch {
            onComplete(alarmRepository.resolveCurrentInstance(recurringAlarmId))
        }
    }

    /**
     * Returns [SymbolOverlay] list for each alarm directive on the given line.
     * Delegates to pure [computeSymbolOverlays] in ViewModelPureLogic.
     */
    fun getSymbolOverlays(
        lineIndex: Int,
        lineContent: String,
        alarmCache: Map<String, Alarm>,
        recurringAlarmCache: Map<String, Alarm>
    ): List<SymbolOverlay> =
        computeSymbolOverlays(lineContent, alarmCache, recurringAlarmCache, Timestamp.now())

    // Scheduling failure warning
    private val _schedulingWarning = MutableLiveData<String?>()
    val schedulingWarning: LiveData<String?> = _schedulingWarning

    fun clearSchedulingWarning() {
        _schedulingWarning.value = null
    }

    // Save warning (e.g., directive result save failures)
    private val _saveWarning = MutableLiveData<String?>()
    val saveWarning: LiveData<String?> = _saveWarning

    fun clearSaveWarning() {
        _saveWarning.value = null
    }

    /**
     * Saves content if needed, then creates a new alarm for the current line.
     * This ensures the line tracker has correct note IDs before alarm creation.
     */
    fun saveAndCreateAlarm(
        content: String,
        lineContent: String,
        lineIndex: Int? = null,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES
    ) {
        val savedNoteId = currentNoteId
        viewModelScope.launch {
            updateTrackedLines(content)
            val saveResult = persistCurrentNote(savedNoteId, currentNoteLines)

            saveResult.onSuccess {
                createAlarmInternal(lineContent, lineIndex, dueTime, stages)
            }
        }
    }

    /**
     * Saves content, then creates a recurring alarm template and its first instance.
     */
    fun saveAndCreateRecurringAlarm(
        content: String,
        lineContent: String,
        lineIndex: Int? = null,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES,
        recurrenceConfig: RecurrenceConfig
    ) {
        val savedNoteId = currentNoteId
        viewModelScope.launch {
            updateTrackedLines(content)
            val saveResult = persistCurrentNote(savedNoteId, currentNoteLines)

            saveResult.onSuccess {
                createRecurringAlarmInternal(
                    lineContent, lineIndex, dueTime, stages, recurrenceConfig
                )
            }
        }
    }

    private suspend fun createRecurringAlarmInternal(
        lineContent: String,
        lineIndex: Int?,
        dueTime: Timestamp?,
        stages: List<AlarmStage>,
        recurrenceConfig: RecurrenceConfig
    ) {
        val noteId = if (lineIndex != null) getNoteIdForLine(lineIndex) else currentNoteId
        val context = getApplication<Application>()

        // Create the recurring alarm template
        val recurringAlarm = RecurrenceConfigMapper.toRecurringAlarm(
            noteId = noteId,
            lineContent = lineContent,
            dueTime = dueTime,
            stages = stages,
            config = recurrenceConfig
        )

        val recurringRepo = recurringAlarmRepository
        val createResult = recurringRepo.create(recurringAlarm)
        val recurringId = createResult.getOrNull()
        if (recurringId == null) {
            val cause = createResult.exceptionOrNull()
            Log.e(TAG, "Failed to create recurring alarm", cause)
            _alarmError.value = cause ?: Exception("Failed to create recurring alarm")
            return
        }

        // Create the first alarm instance directly with the dialog's due time and stages
        val firstAlarm = Alarm(
            noteId = noteId,
            lineContent = lineContent,
            dueTime = dueTime,
            stages = stages,
            recurringAlarmId = recurringId
        )

        // Check permissions
        if (!alarmScheduler.canScheduleExactAlarms()) {
            _alarmPermissionWarning.value = true
        }
        if (!PermissionHelper.hasNotificationPermission(context)) {
            _notificationPermissionWarning.value = true
        }

        alarmStateManager.create(firstAlarm).fold(
            onSuccess = { (alarmId, scheduleResult) ->
                // Update recurring alarm with current instance ID
                recurringRepo.updateCurrentAlarmId(recurringId, alarmId, null)

                if (!scheduleResult.success) {
                    _schedulingWarning.value = scheduleResult.message
                }

                val alarmSnapshot = AlarmSnapshot(
                    id = alarmId,
                    noteId = firstAlarm.noteId,
                    lineContent = firstAlarm.lineContent,
                    dueTime = firstAlarm.dueTime,
                    stages = firstAlarm.stages
                )

                _alarmCreated.value = AlarmCreatedEvent(alarmId, lineContent, alarmSnapshot, recurringAlarmId = recurringId)
                loadAlarmStates()
            },
            onFailure = { e ->
                // Clean up the recurring alarm if first instance creation failed
                recurringRepo.delete(recurringId)
                _alarmError.value = e
            }
        )
    }

    /**
     * Creates a new alarm for the current line.
     * Uses the line tracker to get the note ID for the current line.
     */
    fun createAlarm(
        lineContent: String,
        lineIndex: Int? = null,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES
    ) {
        viewModelScope.launch {
            createAlarmInternal(lineContent, lineIndex, dueTime, stages)
        }
    }

    private suspend fun createAlarmInternal(
        lineContent: String,
        lineIndex: Int?,
        dueTime: Timestamp?,
        stages: List<AlarmStage>
    ) {
        // Use the note ID from line tracker if available
        val noteId = if (lineIndex != null) getNoteIdForLine(lineIndex) else currentNoteId

        val alarm = Alarm(
            noteId = noteId,
            lineContent = lineContent,
            dueTime = dueTime,
            stages = stages
        )

        // Check permissions and show warnings
        val context = getApplication<Application>()
        if (!alarmScheduler.canScheduleExactAlarms()) {
            _alarmPermissionWarning.value = true
        }
        if (!PermissionHelper.hasNotificationPermission(context)) {
            _notificationPermissionWarning.value = true
        }

        alarmStateManager.create(alarm).fold(
            onSuccess = { (alarmId, scheduleResult) ->
                if (!scheduleResult.success) {
                    _schedulingWarning.value = scheduleResult.message
                }

                val alarmSnapshot = AlarmSnapshot(
                    id = alarmId,
                    noteId = alarm.noteId,
                    lineContent = alarm.lineContent,
                    dueTime = alarm.dueTime,
                    stages = alarm.stages
                )

                // Signal to insert alarm symbol (even if scheduling partially failed,
                // the alarm exists in the DB)
                _alarmCreated.value = AlarmCreatedEvent(alarmId, lineContent, alarmSnapshot)
                loadAlarmStates()
            },
            onFailure = { e ->
                _alarmError.value = e
            }
        )
    }

    // region Directive Execution

    /**
     * Loads notes for use in find() operations within directives.
     * Notes are cached and reused until explicitly refreshed.
     * Also caches the current note for [.] reference.
     * Call this before executing directives that may use find().
     */
    private suspend fun ensureNotesLoaded(): List<Note> {
        NoteStore.ensureLoaded()
        return NoteStore.notes.value
    }

    /**
     * Refreshes the cached notes for find() operations.
     * Also updates the cached current note for [.] reference.
     * Call this when notes may have changed (e.g., after save).
     */
    private suspend fun refreshNotesCache(): List<Note> {
        NoteStore.ensureLoaded()
        return NoteStore.notes.value
    }

    /**
     * Callback for when editor content should be updated due to directive mutations.
     * Set by CurrentNoteScreen to bridge ViewModel mutations to EditorController.
     *
     * Parameters:
     * - noteId: ID of the mutated note
     * - newContent: The new content from the note (first line only for notes with children)
     * - mutationType: The type of mutation that occurred
     * - alreadyPersisted: If true, the mutation was part of a save operation and is already
     *   persisted to Firestore, so isSaved should not be set to false
     * - appendedText: For CONTENT_APPENDED, the text that was appended (without leading newline)
     */
    var onEditorContentMutated: ((noteId: String, newContent: String, mutationType: MutationType, alreadyPersisted: Boolean, appendedText: String?) -> Unit)? = null

    /**
     * Process mutations that occurred during directive execution.
     * Updates the cache and notifies the UI if necessary.
     *
     * @param mutations List of mutations that occurred
     * @param alreadyPersisted If true, mutations are already persisted (e.g., during save)
     * @param skipEditorCallback If true, skip notifying the editor (e.g., during live execution
     *        where updating the editor could cause index issues with in-progress event handlers)
     * @return true if the current note was mutated (requiring undo history clear)
     */
    private fun processMutations(
        mutations: List<NoteMutation>,
        alreadyPersisted: Boolean = false,
        skipEditorCallback: Boolean = false
    ): Boolean {
        if (mutations.isEmpty()) return false

        var currentNoteMutated = false

        for (mutation in mutations) {
            Log.d(TAG, "Processing mutation: ${mutation.mutationType} on note ${mutation.noteId}, alreadyPersisted=$alreadyPersisted")

            // Update NoteStore optimistically (already persisted by directive executor)
            NoteStore.updateNote(mutation.noteId, mutation.updatedNote, persist = false)

            // If this mutation affects the note currently being edited, notify the UI
            if (mutation.noteId == currentNoteId) {
                currentNoteMutated = true
                // Only notify the editor if not skipping (e.g., during live execution)
                if (!skipEditorCallback) {
                    when (mutation.mutationType) {
                        MutationType.CONTENT_CHANGED, MutationType.CONTENT_APPENDED -> {
                            // Notify editor to update its content
                            onEditorContentMutated?.invoke(
                                mutation.noteId,
                                mutation.updatedNote.content,
                                mutation.mutationType,
                                alreadyPersisted,
                                mutation.appendedText
                            )
                        }
                        MutationType.PATH_CHANGED -> {
                            // Path changes don't affect editor content
                        }
                    }
                }
            }
        }

        return currentNoteMutated
    }

    /**
     * Execute directives in the content and update local state immediately.
     * Called when content changes (e.g., when user types a closing bracket).
     * Results are NOT persisted to Firestore until save.
     *
     * Uses UUID-based keys for stable identity across text edits.
     * UUIDs are preserved when directives move due to text insertions/deletions.
     */
    /**
     * Bumps the directive cache generation counter, triggering recomposition
     * so computeDirectiveResults() re-runs with current cache state.
     */
    fun bumpDirectiveCacheGeneration() {
        _directiveCacheGeneration.value = (_directiveCacheGeneration.value ?: 0) + 1
    }

    /**
     * Force re-execute all directives with fresh data from Firestore.
     * Used after inline editing a viewed note to refresh the view.
     *
     * Refreshes notes cache, clears directive executor cache, and bumps
     * cache generation so computeDirectiveResults() re-runs with fresh data.
     */
    fun forceRefreshAllDirectives(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            refreshNotesCache()
            directiveCacheManager.clearAll()
            bumpDirectiveCacheGeneration()
            onComplete?.invoke()
        }
    }

    /**
     * Execute all directives in the content and store results in Firestore.
     * Called after note save succeeds.
     *
     * Refreshes notes cache, executes directives via the cached executor,
     * stores results in Firestore keyed by text hash, and bumps cache generation.
     */
    private fun executeAndStoreDirectives(content: String) {
        if (!DirectiveFinder.containsDirectives(content)) {
            return
        }

        // Capture noteId now — currentNoteId may change during the coroutine
        val savedNoteId = currentNoteId

        viewModelScope.launch {
            // Refresh notes cache (notes may have changed after save)
            val notes = refreshNotesCache()
            val currentNote = NoteStore.getNoteById(savedNoteId)

            // Notify observers that notes cache was refreshed (for view directive updates)
            _notesCacheRefreshed.tryEmit(Unit)

            // Execute all directives and collect mutations
            val allMutations = mutableListOf<NoteMutation>()
            for (line in content.lines()) {
                for (directive in DirectiveFinder.findDirectives(line)) {
                    val cachedResult = cachedDirectiveExecutor.execute(
                        directive.sourceText, notes, currentNote, noteOperations
                    )
                    allMutations.addAll(cachedResult.mutations)
                    registerRefreshTriggersIfNeeded(directive.sourceText, currentNote?.id)

                    // Store in Firestore using text hash (skip view/alarm results)
                    val resultValue = cachedResult.result.toValue()
                    val isViewResult = resultValue is ViewVal
                    val isAlarmResult = resultValue is AlarmVal
                    if (!isViewResult && !isAlarmResult) {
                        val textHash = DirectiveResult.hashDirective(directive.sourceText)
                        directiveResultRepository.saveResult(savedNoteId, textHash, cachedResult.result)
                            .onFailure { e ->
                                Log.e(TAG, "Failed to save directive result: $textHash", e)
                                _saveWarning.postValue("Failed to save directive result: ${e.message}")
                            }
                    }
                }
            }

            // Process any mutations that occurred (alreadyPersisted=true since this runs during save)
            processMutations(allMutations, alreadyPersisted = true)

            // Register any schedule directives found in this note
            currentNote?.let { note ->
                ScheduleManager.registerSchedulesFromNote(note)
            }

            MetadataHasher.invalidateCache()

            // Only bump generation if we're still on the same note.
            // If the user already switched tabs, the new note's directives
            // were computed fresh — a stale generation bump would cause them
            // to re-run with this coroutine's cached results (which are for
            // the OLD note and may have stale content).
            if (savedNoteId == currentNoteId) {
                bumpDirectiveCacheGeneration()
            }
        }
    }

    /**
     * Loads cached directive results from Firestore into the L1 cache,
     * then executes any directives that aren't cached. Called when note is loaded.
     *
     * Alarm directives are trivial pure functions — computeDirectiveResults handles
     * them synchronously, so no pre-population is needed.
     */
    private suspend fun loadDirectiveResults(content: String, noteId: String = currentNoteId) {
        if (!DirectiveFinder.containsDirectives(content)) {
            if (noteId == currentNoteId) {
                _directiveCacheGeneration.postValue((_directiveCacheGeneration.value ?: 0) + 1)
            }
            return
        }

        // Load cached results from Firestore (keyed by text hash)
        val cachedByHash = directiveResultRepository.getResults(noteId)
            .getOrElse { e ->
                Log.e(TAG, "Failed to load directive results", e)
                emptyMap()
            }

        // Prime the L1 cache with Firestore results (skip stale/unreliable types)
        val directiveTexts = mutableSetOf<String>()
        for (line in content.lines()) {
            for (directive in DirectiveFinder.findDirectives(line)) {
                directiveTexts.add(directive.sourceText)
            }
        }

        val missingTexts = mutableListOf<String>()
        for (sourceText in directiveTexts) {
            val textHash = DirectiveResult.hashDirective(sourceText)
            val cached = cachedByHash[textHash]
            val cachedValue = cached?.toValue()
            val cachedResultType = cached?.result?.get("type") as? String

            // Skip view/temporal/alarm results — re-execute these fresh
            val isViewResult = cachedValue is ViewVal || cachedResultType == "view"
            val isBareTemporalResult = cachedValue is DateVal ||
                    cachedValue is TimeVal ||
                    cachedValue is DateTimeVal ||
                    cachedResultType in listOf("date", "time", "datetime")
            val isAlarmResult = cachedValue is AlarmVal || cachedResultType == "alarm"

            if (cached != null && !isViewResult && !isBareTemporalResult && !isAlarmResult) {
                cachedDirectiveExecutor.primeCache(sourceText, noteId, cached)
            } else {
                missingTexts.add(sourceText)
            }
        }

        if (missingTexts.isEmpty()) {
            if (noteId == currentNoteId) {
                _directiveCacheGeneration.postValue((_directiveCacheGeneration.value ?: 0) + 1)
            }
            return
        }

        // Load notes and execute missing directives
        val notes = ensureNotesLoaded()
        val currentNote = NoteStore.getNoteById(noteId)
        val allMutations = mutableListOf<NoteMutation>()
        for (sourceText in missingTexts) {
            val cachedResult = cachedDirectiveExecutor.execute(sourceText, notes, currentNote, noteOperations)
            allMutations.addAll(cachedResult.mutations)
            registerRefreshTriggersIfNeeded(sourceText, currentNote?.id)

            val resultValue = cachedResult.result.toValue()
            val isViewResult = resultValue is ViewVal
            val isAlarmResult = resultValue is AlarmVal
            if (!isViewResult && !isAlarmResult) {
                val textHash = DirectiveResult.hashDirective(sourceText)
                directiveResultRepository.saveResult(noteId, textHash, cachedResult.result)
                    .onFailure { e ->
                        Log.e(TAG, "Failed to save directive result: $textHash", e)
                        _saveWarning.postValue("Failed to save directive result: ${e.message}")
                    }
            }
        }
        processMutations(allMutations, skipEditorCallback = true)

        // Only bump generation if still on the same note — avoids triggering
        // stale directive re-computation on a different note after tab switch.
        if (noteId == currentNoteId) {
            _directiveCacheGeneration.postValue((_directiveCacheGeneration.value ?: 0) + 1)
        }
    }

    /**
     * Toggle the collapsed/expanded state of a directive.
     *
     * @param sourceText The source text of the directive (e.g., "[42]")
     */
    fun toggleDirectiveCollapsed(sourceText: String) {
        val hash = DirectiveResult.hashDirective(sourceText)
        if (hash in expandedDirectiveHashes) {
            expandedDirectiveHashes.remove(hash)
        } else {
            expandedDirectiveHashes.add(hash)
        }
        bumpDirectiveCacheGeneration()
    }

    /**
     * Re-executes a directive and collapses it.
     * Called when user confirms a directive edit (including when no changes were made).
     * This ensures dynamic directives like [now] get fresh values on confirm.
     *
     * @param sourceText The source text of the directive (e.g., "[now]")
     */
    fun confirmDirective(sourceText: String) {
        val hash = DirectiveResult.hashDirective(sourceText)
        expandedDirectiveHashes.remove(hash)
        // Clear L1 cache so computeDirectiveResults re-executes with fresh value
        cachedDirectiveExecutor.clearCacheEntry(sourceText, currentNoteId)
        bumpDirectiveCacheGeneration()
    }

    /**
     * Re-executes a directive and keeps it expanded.
     * Called when user refreshes a directive edit (recompute without closing).
     *
     * @param sourceText The source text of the directive (e.g., "[now]")
     */
    fun refreshDirective(sourceText: String) {
        // Keep expanded — hash stays in expandedDirectiveHashes
        // Clear L1 cache so computeDirectiveResults re-executes with fresh value
        cachedDirectiveExecutor.clearCacheEntry(sourceText, currentNoteId)
        bumpDirectiveCacheGeneration()
    }

    /**
     * Executes a button directive's action.
     * Called when user clicks a button rendered from a ButtonVal.
     *
     * Note: The buttonVal passed here has a placeholder lambda (lambdas can't be serialized).
     * We need to re-parse the source text to get the real lambda for execution.
     *
     * @param directiveKey Directive key (e.g., "noteId:15")
     * @param buttonVal The ButtonVal (used for label display, action is placeholder)
     * @param sourceText The original directive source text to re-parse for execution
     */
    fun executeButton(directiveKey: String, buttonVal: ButtonVal, sourceText: String? = null) {
        val capturedNoteId = currentNoteId
        viewModelScope.launch {
            ensureNotesLoaded()

            // Set loading state
            updateButtonState(directiveKey, ButtonExecutionState.LOADING)

            try {
                // Create fresh environment for button execution with current context
                // This ensures mutations are properly captured
                val env = Environment(
                    NoteContext(
                        notes = NoteStore.notes.value,
                        currentNote = NoteStore.getNoteById(capturedNoteId),
                        noteOperations = noteOperations
                    )
                )

                // Re-parse the directive source to get the fresh ButtonVal with real lambda
                // The deserialized buttonVal has a placeholder lambda that can't execute the real action
                if (sourceText != null) {
                    val tokens = Lexer(sourceText).tokenize()
                    val directive = Parser(tokens, sourceText).parseDirective()
                    val executor = Executor()
                    val freshValue = executor.execute(directive, env)

                    // The freshValue should be a ButtonVal with the real lambda
                    if (freshValue is ButtonVal) {
                        // Execute the button's lambda in the environment
                        executor.evaluate(freshValue.action.body, env)
                    } else {
                        Log.w(TAG, "Re-parsed directive did not produce ButtonVal: ${freshValue::class.simpleName}")
                    }
                } else {
                    // Fallback: try to use the placeholder (will likely fail or do nothing)
                    Log.w(TAG, "No sourceText provided, using placeholder lambda")
                    val executor = Executor()
                    executor.evaluate(buttonVal.action.body, env)
                }

                // Get and process mutations from the fresh environment
                // For button clicks, we DO want to update the editor since we're not
                // in the middle of an editing operation (unlike live directive execution)
                val mutations = env.getMutations()
                if (mutations.isNotEmpty()) {
                    processMutations(mutations, skipEditorCallback = false)
                }

                // Clear any previous error and flash success state
                clearButtonError(directiveKey)
                updateButtonState(directiveKey, ButtonExecutionState.SUCCESS)
                delay(500)
                updateButtonState(directiveKey, ButtonExecutionState.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Button execution failed: ${e.message}", e)
                // Keep button in error state (don't reset to IDLE)
                updateButtonState(directiveKey, ButtonExecutionState.ERROR)

                // Store the error message for display
                val current = _buttonErrors.value?.toMutableMap() ?: mutableMapOf()
                current[directiveKey] = e.message ?: "Unknown error"
                _buttonErrors.postValue(current)
            }
        }
    }

    /**
     * Updates the execution state for a button directive.
     */
    private fun updateButtonState(directiveKey: String, state: ButtonExecutionState) {
        val current = _buttonExecutionStates.value?.toMutableMap() ?: mutableMapOf()
        if (state == ButtonExecutionState.IDLE) {
            current.remove(directiveKey)
        } else {
            current[directiveKey] = state
        }
        _buttonExecutionStates.value = current
    }

    /**
     * Compute directive results synchronously using the CachedDirectiveExecutor.
     * Results are keyed by directiveHash(sourceText) — same key the rendering layer uses.
     * Cache hits return instantly; misses execute and cache for next call.
     *
     * Applies collapsed state from [expandedDirectiveHashes].
     * When notes haven't loaded yet, still returns alarm results (trivial pure functions).
     */
    fun computeDirectiveResults(content: String, noteId: String? = null): Map<String, DirectiveResult> {
        val effectiveNoteId = noteId ?: currentNoteId
        val notes = NoteStore.notes.value.takeIf { it.isNotEmpty() }
        val currentNote = NoteStore.getNoteById(effectiveNoteId)
        val hashResults = mutableMapOf<String, DirectiveResult>()
        for (line in content.lines()) {
            for (directive in DirectiveFinder.findDirectives(line)) {
                val hash = DirectiveResult.hashDirective(directive.sourceText)
                if (hashResults.containsKey(hash)) continue

                val result = if (notes != null) {
                    cachedDirectiveExecutor.execute(
                        directive.sourceText, notes, currentNote, noteOperations
                    ).result
                } else {
                    // Notes not loaded yet — only alarm directives can be resolved
                    val alarmId = DirectiveSegment.Directive.alarmIdFromSource(directive.sourceText)
                        ?: DirectiveSegment.Directive.recurringAlarmIdFromSource(directive.sourceText)
                    if (alarmId != null) DirectiveResult.success(AlarmVal(alarmId)) else continue
                }

                val collapsed = hash !in expandedDirectiveHashes
                hashResults[hash] = result.copy(collapsed = collapsed)
            }
        }
        return hashResults
    }

    /**
     * Gets a snapshot of currently expanded directive hashes.
     * Used before undo/redo to preserve expanded state.
     */
    fun getExpandedDirectiveHashes(): Set<String> = expandedDirectiveHashes.toSet()

    /**
     * Restores expanded state from a snapshot of directive hashes.
     * Called after undo/redo to preserve edit row state.
     */
    fun restoreExpandedDirectiveHashes(hashes: Set<String>) {
        expandedDirectiveHashes.clear()
        expandedDirectiveHashes.addAll(hashes)
        bumpDirectiveCacheGeneration()
    }

    /**
     * Execute directives for arbitrary content (e.g., a viewed note being edited inline).
     * Returns directive results keyed by "lineId:startOffset" for use in rendering.
     *
     * This is used when inline editing a viewed note to render its directives properly.
     * The results are separate from the main note's directive results.
     *
     * @param content The content to parse and execute directives for
     * @param onResults Callback with the results when execution completes
     */
    fun executeDirectivesForContent(content: String, onResults: (Map<String, DirectiveResult>) -> Unit) {
        if (!DirectiveFinder.containsDirectives(content)) {
            onResults(emptyMap())
            return
        }

        val capturedNoteId = currentNoteId
        viewModelScope.launch {
            val notes = ensureNotesLoaded()

            val results = mutableMapOf<String, DirectiveResult>()
            for (line in content.lines()) {
                for (directive in DirectiveFinder.findDirectives(line)) {
                    val hash = DirectiveResult.hashDirective(directive.sourceText)
                    if (results.containsKey(hash)) continue
                    try {
                        val cachedResult = cachedDirectiveExecutor.execute(
                            directive.sourceText, notes, NoteStore.getNoteById(capturedNoteId), null
                        )
                        results[hash] = cachedResult.result
                    } catch (e: Exception) {
                        Log.e(TAG, "executeDirectivesForContent: error executing '${directive.sourceText}'", e)
                        results[hash] = DirectiveResult.failure(e.message ?: "Execution error")
                    }
                }
            }

            onResults(results)
        }
    }

    /**
     * Execute a single directive and return its result.
     * Used for refresh/confirm actions in inline directive editing.
     *
     * @param sourceText The directive source text (e.g., "[add(1,2)]")
     * @param onResult Callback with the result when execution completes
     */
    fun executeSingleDirective(sourceText: String, onResult: (DirectiveResult) -> Unit) {
        val capturedNoteId = currentNoteId
        viewModelScope.launch {
            val notes = ensureNotesLoaded()
            val cachedResult = cachedDirectiveExecutor.execute(
                sourceText, notes, NoteStore.getNoteById(capturedNoteId), null // null noteOperations = read-only
            )
            Log.d(TAG, "executeSingleDirective: executed '$sourceText'")
            onResult(cachedResult.result)
        }
    }

    /**
     * Start an edit session for inline editing within a view.
     * Call this when the user starts editing content that belongs to another note
     * (e.g., editing a note displayed in a view directive).
     *
     * This suppresses cache invalidation for the current note during editing
     * to prevent UI flicker.
     *
     * @param editedNoteId The ID of the note being edited
     */
    fun startInlineEditSession(editedNoteId: String) {
        editSessionManager.startEditSession(
            editedNoteId = editedNoteId,
            originatingNoteId = currentNoteId
        )
        Log.d(TAG, "Started inline edit session: editing $editedNoteId from $currentNoteId")
    }

    /**
     * End the current inline edit session.
     * Call this when the user finishes editing (blur, save, navigation).
     * This will apply any pending cache invalidations and refresh affected views.
     */
    fun endInlineEditSession() {
        if (editSessionManager.isEditSessionActive()) {
            editSessionManager.endEditSession()
            Log.d(TAG, "Ended inline edit session")
        }
    }

    /**
     * Check if an inline edit session is currently active.
     */
    fun isInlineEditSessionActive(): Boolean = editSessionManager.isEditSessionActive()

    /**
     * Saves the content of a note edited inline within a view directive.
     * This is a simple content update that doesn't affect child note structure.
     *
     * The method:
     * 1. Starts an inline edit session to suppress cache invalidation during edit
     * 2. Updates the note content in Firestore
     * 3. Invalidates the directive cache so views refresh
     * 4. Ends the edit session which triggers view refresh
     *
     * @param noteId The ID of the note to update
     * @param newContent The new content for the note
     * @param onSuccess Optional callback when save succeeds
     * @param onFailure Optional callback when save fails
     */
    fun saveInlineNoteContent(
        noteId: String,
        newContent: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        // Optimistic update immediately so tab-switching shows the edit
        // before the async Firestore save completes (persist = false because
        // saveNoteWithFullContent below does a more appropriate save)
        val existing = NoteStore.getNoteById(noteId)
        if (existing != null) {
            NoteStore.updateNote(noteId, existing.copy(content = newContent), persist = false)
        }

        viewModelScope.launch {
            // Only start a new edit session if one isn't already active for this note.
            // Re-starting triggers a session switch (end + start) which fires the
            // session-end listener → bumpDirectiveCacheGeneration → directive re-execution
            // → session recreation → IME invalidation → focused line cleared.
            if (!editSessionManager.isEditSessionActive() ||
                editSessionManager.getEditContext()?.editedNoteId != noteId) {
                startInlineEditSession(noteId)
            }

            // Use saveNoteWithFullContent to properly handle multi-line notes
            // This preserves child note IDs and handles line additions/deletions
            //
            // AWAIT save completion before clearing caches.
            // This ensures that when we refresh, Firestore has the new data.
            val saveResult = repository.saveNoteWithFullContent(noteId, newContent)

            saveResult
                .onSuccess {
                    // NoteStore was already updated synchronously (before the coroutine)
                    // with the new content. Clear directive caches so the next re-execution
                    // uses fresh data. Do NOT bump cache generation here — that would force
                    // immediate recomposition of the directive line, which recreates the
                    // ControlledLineView composables and invalidates the focused line's IME
                    // InputConnection, causing the keyboard to clear the focused line's text.
                    // The cache will be naturally re-evaluated when the user defocuses.
                    MetadataHasher.invalidateCache()
                    directiveCacheManager.clearAll()

                    onSuccess?.invoke()
                }
                .onFailure { e ->
                    Log.e("NoteRepository", "=== saveInlineNoteContent FAILED for $noteId ===", e)

                    // Abort edit session on failure (don't apply pending invalidations)
                    editSessionManager.abortEditSession()

                    onFailure?.invoke(e)
                }
        }
    }

    /**
     * Register refresh triggers for a directive if it contains refresh[...].
     * This enables automatic cache invalidation at computed trigger times.
     */
    private fun registerRefreshTriggersIfNeeded(sourceText: String, noteId: String?) {
        if (!sourceText.contains("refresh[")) return

        try {
            val tokens = Lexer(sourceText).tokenize()
            val directive = Parser(tokens, sourceText).parseDirective()

            // Find RefreshExpr in the AST
            val refreshExpr = findRefreshExpr(directive.expression)
            if (refreshExpr != null) {
                val analysis = RefreshTriggerAnalyzer.analyze(refreshExpr)
                if (analysis.success && analysis.triggers.isNotEmpty()) {
                    val cacheKey = DirectiveResult.hashDirective(sourceText)
                    refreshScheduler.register(cacheKey, noteId, analysis.triggers)
                    Log.d(TAG, "Registered ${analysis.triggers.size} refresh triggers for directive")
                }
            }
        } catch (e: Exception) {
            // Ignore analysis errors - directive will still execute
            Log.w(TAG, "Failed to analyze refresh triggers: ${e.message}")
        }
    }

    /**
     * Find a RefreshExpr in an expression tree.
     */
    private fun findRefreshExpr(expr: org.alkaline.taskbrain.dsl.language.Expression): RefreshExpr? {
        return when (expr) {
            is RefreshExpr -> expr
            is org.alkaline.taskbrain.dsl.language.StatementList -> {
                expr.statements.asSequence()
                    .mapNotNull { findRefreshExpr(it) }
                    .firstOrNull()
            }
            is org.alkaline.taskbrain.dsl.language.Assignment -> findRefreshExpr(expr.value)
            else -> null
        }
    }

    // endregion

    override fun onCleared() {
        super.onCleared()
        refreshScheduler.stop()
    }

    companion object {
        private const val TAG = "CurrentNoteViewModel"
    }

    /**
     * Updates an existing alarm's due time and stages, then reschedules it.
     */
    fun updateAlarm(
        alarm: Alarm,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES
    ) {
        viewModelScope.launch {
            alarmStateManager.update(alarm, dueTime, stages).fold(
                onSuccess = { scheduleResult ->
                    if (!scheduleResult.success) {
                        _schedulingWarning.value = scheduleResult.message
                    }
                    loadAlarmStates()
                },
                onFailure = { e ->
                    _alarmError.value = e
                }
            )
        }
    }

    /**
     * Updates an existing recurring alarm's thresholds and recurrence config.
     * Updates both the template and the current instance.
     */
    fun updateRecurringAlarm(
        alarm: Alarm,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES,
        recurrenceConfig: RecurrenceConfig
    ) {
        viewModelScope.launch {
            val recurringAlarmId = alarm.recurringAlarmId
            if (recurringAlarmId == null) {
                // Not actually recurring — fall back to normal update
                updateAlarm(alarm, dueTime, stages)
                return@launch
            }

            val recurringRepo = recurringAlarmRepository
            val existing = recurringRepo.get(recurringAlarmId).getOrNull()
            if (existing == null) {
                Log.e(TAG, "Recurring alarm template not found: $recurringAlarmId")
                _alarmError.value = Exception("Recurring alarm template not found")
                return@launch
            }

            // Update the template with new recurrence config
            val updatedTemplate = RecurrenceConfigMapper.toRecurringAlarm(
                noteId = alarm.noteId,
                lineContent = alarm.lineContent,
                dueTime = dueTime,
                stages = stages,
                config = recurrenceConfig
            ).copy(
                id = existing.id,
                userId = existing.userId,
                completionCount = existing.completionCount,
                lastCompletionDate = existing.lastCompletionDate,
                currentAlarmId = existing.currentAlarmId,
                status = if (recurrenceConfig.enabled) existing.status
                         else org.alkaline.taskbrain.data.RecurringAlarmStatus.ENDED,
                createdAt = existing.createdAt
            )

            recurringRepo.update(updatedTemplate).fold(
                onSuccess = {
                    // Also update the current alarm instance
                    alarmStateManager.update(alarm, dueTime, stages).fold(
                        onSuccess = { scheduleResult ->
                            if (!scheduleResult.success) {
                                _schedulingWarning.value = scheduleResult.message
                            }
                            loadAlarmStates()
                        },
                        onFailure = { e -> _alarmError.value = e }
                    )
                },
                onFailure = { e -> _alarmError.value = e }
            )
        }
    }

    /**
     * Updates an instance's times, optionally propagating the change to the recurrence template.
     */
    fun updateInstanceTimes(
        alarm: Alarm,
        dueTime: Timestamp?,
        stages: List<AlarmStage>,
        alsoUpdateRecurrence: Boolean
    ) {
        viewModelScope.launch {
            templateManager.updateInstanceTimes(alarm, dueTime, stages, alsoUpdateRecurrence).fold(
                onSuccess = { scheduleResult ->
                    if (!scheduleResult.success) {
                        _schedulingWarning.value = scheduleResult.message
                    }
                    loadAlarmStates()
                },
                onFailure = { e -> _alarmError.value = e }
            )
        }
    }

    /**
     * Updates the recurrence template's times and pattern, optionally propagating
     * time changes to all pending instances that still match the old template times.
     */
    fun updateRecurrenceTemplate(
        recurringAlarmId: String,
        dueTime: Timestamp?,
        stages: List<AlarmStage>,
        recurrenceConfig: RecurrenceConfig,
        alsoUpdateMatchingInstances: Boolean
    ) {
        viewModelScope.launch {
            templateManager.updateRecurrenceTemplate(
                recurringAlarmId, dueTime, stages, recurrenceConfig, alsoUpdateMatchingInstances
            ).fold(
                onSuccess = { loadAlarmStates() },
                onFailure = { e -> _alarmError.value = e }
            )
        }
    }

    /**
     * Marks an existing alarm as done.
     */
    fun markAlarmDone(alarmId: String) {
        viewModelScope.launch {
            alarmStateManager.markDone(alarmId).fold(
                onSuccess = { loadAlarmStates() },
                onFailure = { e -> _alarmError.value = e }
            )
        }
    }

    /**
     * Cancels an existing alarm (sets status to CANCELLED).
     */
    fun cancelAlarm(alarmId: String) {
        viewModelScope.launch {
            alarmStateManager.markCancelled(alarmId).fold(
                onSuccess = { loadAlarmStates() },
                onFailure = { e -> _alarmError.value = e }
            )
        }
    }

    fun reactivateAlarm(alarmId: String) {
        viewModelScope.launch {
            alarmStateManager.reactivate(alarmId).fold(
                onSuccess = { loadAlarmStates() },
                onFailure = { e -> _alarmError.value = e }
            )
        }
    }

    /**
     * Clears the alarm created event after it has been handled.
     */
    fun clearAlarmCreatedEvent() {
        _alarmCreated.value = null
    }

    /**
     * Deletes an alarm permanently (for undo operation).
     * This completely removes the alarm from Firestore and cancels any scheduled notifications.
     * Sets isAlarmOperationPending during the operation to prevent race conditions.
     */
    fun deleteAlarmPermanently(alarmId: String, onComplete: (() -> Unit)? = null) {
        _isAlarmOperationPending.value = true
        viewModelScope.launch {
            alarmStateManager.delete(alarmId).fold(
                onSuccess = {
                    onComplete?.invoke()
                    loadAlarmStates()
                },
                onFailure = { e -> _alarmError.value = e }
            )
            _isAlarmOperationPending.value = false
        }
    }

    /**
     * Recreates an alarm with the same configuration (for redo operation).
     * The alarm will get a new ID. Calls onAlarmCreated with the new ID.
     * Sets isAlarmOperationPending during the operation to prevent race conditions.
     * Calls onFailure with the error message if alarm creation fails (e.g., to clean up the alarm symbol).
     */
    fun recreateAlarm(
        alarmSnapshot: AlarmSnapshot,
        onAlarmCreated: (String) -> Unit,
        onFailure: ((String) -> Unit)? = null
    ) {
        _isAlarmOperationPending.value = true
        viewModelScope.launch {
            val alarm = Alarm(
                noteId = alarmSnapshot.noteId,
                lineContent = alarmSnapshot.lineContent,
                dueTime = alarmSnapshot.dueTime,
                stages = alarmSnapshot.stages
            )

            alarmStateManager.create(alarm).fold(
                onSuccess = { (newAlarmId, _) ->
                    // Notify caller of new ID for future undo/redo cycles
                    onAlarmCreated(newAlarmId)
                },
                onFailure = { e ->
                    // Pass error message to callback instead of showing generic error dialog
                    // The screen will show a specific redo rollback warning
                    onFailure?.invoke(e.message ?: "Unknown error")
                }
            )
            _isAlarmOperationPending.value = false
        }
    }

    /**
     * Clears the alarm error after it has been shown.
     */
    fun clearAlarmError() {
        _alarmError.value = null
    }

    /**
     * Clears the alarm permission warning after it has been shown.
     */
    fun clearAlarmPermissionWarning() {
        _alarmPermissionWarning.value = false
    }

    /**
     * Clears the notification permission warning after it has been shown.
     */
    fun clearNotificationPermissionWarning() {
        _notificationPermissionWarning.value = false
    }

    /**
     * Shows a warning that alarm recreation failed during redo and the document was rolled back.
     */
    fun showRedoRollbackWarning(rollbackSucceeded: Boolean, errorMessage: String) {
        _redoRollbackWarning.value = RedoRollbackWarning(rollbackSucceeded, errorMessage)
    }

    /**
     * Clears the redo rollback warning after it has been shown.
     */
    fun clearRedoRollbackWarning() {
        _redoRollbackWarning.value = null
    }

    // Call this when content is manually edited or when a save completes
    fun markAsSaved() {
        _contentModified.value = false
    }

    fun clearSaveError() {
        if (_saveStatus.value is SaveStatus.Error) {
            _saveStatus.value = null
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
                    _saveStatus.value = SaveStatus.Error(e)
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
                    _saveStatus.value = SaveStatus.Error(e)
                }
            )
        }
    }
}

sealed class SaveStatus {
    object Saving : SaveStatus()
    /** @param noteId the note that was saved (may differ from currentNoteId after a tab switch) */
    data class Success(val noteId: String) : SaveStatus()
    data class Error(val throwable: Throwable) : SaveStatus()
}

sealed class LoadStatus {
    object Loading : LoadStatus()
    data class Success(
        val noteId: String,
        val content: String,
        val lineNoteIds: List<List<String>> = emptyList()
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
