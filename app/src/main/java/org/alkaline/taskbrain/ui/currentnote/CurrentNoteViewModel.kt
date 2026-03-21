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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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
import org.alkaline.taskbrain.data.NoteLineTracker
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.PrompterAgent
import org.alkaline.taskbrain.dsl.cache.CachedDirectiveExecutor
import org.alkaline.taskbrain.dsl.cache.CachedDirectiveExecutorFactory
import org.alkaline.taskbrain.dsl.cache.DirectiveCacheManager
import org.alkaline.taskbrain.dsl.cache.EditSessionManager
import org.alkaline.taskbrain.dsl.cache.MetadataHasher
import org.alkaline.taskbrain.dsl.cache.RefreshScheduler
import org.alkaline.taskbrain.dsl.cache.RefreshTriggerAnalyzer
import org.alkaline.taskbrain.dsl.directives.DirectiveExecutionResult
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveInstance
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.DirectiveResultRepository
import org.alkaline.taskbrain.dsl.directives.ScheduleManager
import org.alkaline.taskbrain.dsl.directives.matchDirectiveInstances
import org.alkaline.taskbrain.dsl.runtime.values.AlarmVal
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import org.alkaline.taskbrain.dsl.directives.parseAllDirectiveLocations
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
import org.alkaline.taskbrain.dsl.runtime.ViewVal
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState
import org.alkaline.taskbrain.ui.currentnote.undo.AlarmSnapshot
import org.alkaline.taskbrain.ui.currentnote.util.AlarmOverlayMapping
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

    // Directive caching infrastructure (Phase 10 integration)
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

    // Note operations for DSL mutations (Milestone 7)
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

    // Directive execution results - maps directive UUID to result
    private val _directiveResults = MutableLiveData<Map<String, DirectiveResult>>(emptyMap())
    val directiveResults: LiveData<Map<String, DirectiveResult>> = _directiveResults

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

    // Directive instances with stable UUIDs - survives text edits
    private var directiveInstances: List<DirectiveInstance> = emptyList()

    // Cached notes for find() operations in directives
    private var cachedNotes: List<Note>? = null

    // Cached current note for [.] reference in directives (Milestone 6)
    private var cachedCurrentNote: Note? = null

    /**
     * Invalidate the notes cache so view directives get fresh content.
     * Call this when switching tabs after saving to ensure views show updated data.
     */
    fun invalidateNotesCache() {
        cachedNotes = null
        cachedCurrentNote = null
        // Phase 3: Invalidate metadata hash cache when notes change
        MetadataHasher.invalidateCache()
        // Also clear the directive cache to force re-execution with fresh data
        directiveCacheManager.clearAll()
    }

    // Emits when notes cache is refreshed (e.g., after save) so UI can refresh view directives
    private val _notesCacheRefreshed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notesCacheRefreshed: SharedFlow<Unit> = _notesCacheRefreshed.asSharedFlow()

    // Start refresh scheduler on ViewModel creation and set up edit session listener
    init {
        refreshScheduler.start()
        // When an edit session ends, refresh the current view
        editSessionManager.addSessionEndListener {
            viewModelScope.launch {
                // Re-execute directives to pick up any changes that were suppressed
                val content = (_loadStatus.value as? LoadStatus.Success)?.content
                if (content != null && DirectiveFinder.containsDirectives(content)) {
                    executeDirectivesLive(content)
                }
            }
        }
    }

    // Firestore snapshot listener for real-time external change detection
    private var snapshotListener: ListenerRegistration? = null
    private var suppressSnapshotUpdate = false

    /**
     * Saves the current note to Firestore. ALL saves of the current note's content
     * MUST go through this method — it sets [suppressSnapshotUpdate] to prevent
     * the snapshot listener from triggering a spurious reload that overwrites the editor.
     *
     * On success, handles common post-save bookkeeping: updating line tracker IDs,
     * notifying the UI, and syncing alarm line content.
     */
    private suspend fun persistCurrentNote(trackedLines: List<NoteLine>): Result<Map<Int, String>> {
        suppressSnapshotUpdate = true
        val result = repository.saveNoteWithChildren(currentNoteId, trackedLines)
        result.fold(
            onSuccess = { newIdsMap ->
                for ((index, newId) in newIdsMap) {
                    lineTracker.updateLineNoteId(index, newId)
                }
                if (newIdsMap.isNotEmpty()) _newlyAssignedNoteIds.tryEmit(newIdsMap)
                syncAlarmLineContent(trackedLines)
                _saveStatus.value = SaveStatus.Success
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
    private val LAST_VIEWED_NOTE_KEY = "last_viewed_note_id"
    private var currentNoteId = sharedPreferences.getString(LAST_VIEWED_NOTE_KEY, "root_note") ?: "root_note"

    // Expose current note ID for UI (e.g., undo state persistence)
    private val _currentNoteIdLiveData = MutableLiveData<String>(currentNoteId)
    val currentNoteIdLiveData: LiveData<String> = _currentNoteIdLiveData

    /**
     * Gets the current note ID synchronously.
     */
    fun getCurrentNoteId(): String = currentNoteId

    // Track lines with their corresponding note IDs
    private var lineTracker = NoteLineTracker(currentNoteId)

    /**
     * Gets the current tracked lines for cache updates.
     * Returns a copy to prevent external modification.
     */
    fun getTrackedLines(): List<NoteLine> = lineTracker.getTrackedLines()

    /** Extracts noteIds from NoteLines for passing to EditorState via LoadStatus. */
    private fun noteLinesToNoteIds(lines: List<NoteLine>): List<List<String>> =
        lines.map { listOfNotNull(it.noteId) }

    /**
     * Starts a Firestore snapshot listener on the current note's parent document.
     * When an external change is detected (not from our own save), reloads the full note.
     */
    private fun startSnapshotListener(noteId: String, recentTabsViewModel: RecentTabsViewModel?) {
        snapshotListener?.remove()
        // Suppress the initial snapshot that fires immediately on registration
        suppressSnapshotUpdate = true
        val db = FirebaseFirestore.getInstance()
        snapshotListener = db.collection("notes").document(noteId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Snapshot listener error for $noteId", error)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                if (noteId != currentNoteId) return@addSnapshotListener

                // Skip local pending writes (optimistic updates from our own writes)
                // Check before suppress so the flag isn't consumed by the pending-write event
                if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener

                // Skip the initial snapshot and our own saves
                if (suppressSnapshotUpdate) {
                    suppressSnapshotUpdate = false
                    return@addSnapshotListener
                }

                Log.d(TAG, "Snapshot listener detected external change for $noteId")
                // Reload the full note (parent + children) from Firestore
                viewModelScope.launch {
                    val result = repository.loadNoteWithChildren(noteId)
                    result.fold(
                        onSuccess = { freshLines ->
                            if (noteId != currentNoteId) return@fold
                            lineTracker.setTrackedLines(freshLines)
                            val freshContent = freshLines.joinToString("\n") { it.content }
                            _loadStatus.value = LoadStatus.Success(freshContent, noteLinesToNoteIds(freshLines))
                            recentTabsViewModel?.cacheNoteContent(
                                noteId, freshLines, _isNoteDeleted.value ?: false
                            )
                            loadDirectiveResults(freshContent)
                            loadAlarmStates()
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Snapshot-triggered reload failed for $noteId", e)
                        }
                    )
                }
            }
    }

    fun loadContent(noteId: String? = null, recentTabsViewModel: RecentTabsViewModel? = null) {
        // If noteId is provided, use it. Otherwise, load from preferences. If neither, default to "root_note"
        currentNoteId = noteId ?: sharedPreferences.getString(LAST_VIEWED_NOTE_KEY, "root_note") ?: "root_note"
        _currentNoteIdLiveData.value = currentNoteId
        Log.d(TAG, "loadContent: switching to noteId=$currentNoteId, cachedNotes=${cachedNotes?.size}, cachedNotesNull=${cachedNotes == null}")

        // Save the current note as the last viewed note
        sharedPreferences.edit().putString(LAST_VIEWED_NOTE_KEY, currentNoteId).apply()

        // Start real-time listener for external changes on this note
        startSnapshotListener(currentNoteId, recentTabsViewModel)

        // Note: Don't clear cachedNotes here - it persists until a save happens
        // This allows view directives to use cached content when switching tabs
        // The cache is refreshed in refreshNotesCache() after saves

        // Recreate line tracker with new parent note ID
        lineTracker = NoteLineTracker(currentNoteId)

        // Check cache first for instant tab switching
        val cached = recentTabsViewModel?.getCachedContent(currentNoteId)
        if (cached != null) {
            Log.d(TAG, "loadContent: using RecentTabsViewModel cache for $currentNoteId")
            // Use cached content - instant!
            _isNoteDeleted.value = cached.isDeleted
            lineTracker.setTrackedLines(cached.noteLines)
            val fullContent = cached.noteLines.joinToString("\n") { it.content }
            _loadStatus.value = LoadStatus.Success(fullContent, noteLinesToNoteIds(cached.noteLines))
            // Still update lastAccessedAt, load showCompleted, and directive results in background
            viewModelScope.launch {
                repository.updateLastAccessed(currentNoteId)
                repository.loadNoteById(currentNoteId).onSuccess { note ->
                    _showCompleted.value = note?.showCompleted ?: true
                }
                loadDirectiveResults(fullContent)
            }
            loadAlarmStates()

            // Background refresh: fetch from Firebase to pick up external changes (e.g. web edits)
            val cachedNoteId = currentNoteId
            viewModelScope.launch {
                val result = repository.loadNoteWithChildren(cachedNoteId)
                result.fold(
                    onSuccess = { freshLines ->
                        val freshContent = freshLines.joinToString("\n") { it.content }
                        if (freshContent != fullContent && cachedNoteId == currentNoteId) {
                            Log.d(TAG, "loadContent: background refresh found changes for $cachedNoteId")
                            lineTracker.setTrackedLines(freshLines)
                            _loadStatus.value = LoadStatus.Success(freshContent, noteLinesToNoteIds(freshLines))
                            recentTabsViewModel?.cacheNoteContent(
                                cachedNoteId,
                                freshLines,
                                _isNoteDeleted.value ?: false
                            )
                            loadDirectiveResults(freshContent)
                            loadAlarmStates()
                        }
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Background refresh failed for $cachedNoteId", e)
                    }
                )
            }
            return
        }
        Log.d(TAG, "loadContent: cache miss for $currentNoteId, fetching from Firebase")

        // Cache miss - fetch from Firebase
        _loadStatus.value = LoadStatus.Loading

        viewModelScope.launch {
            // Load note metadata (deleted state + showCompleted)
            repository.loadNoteById(currentNoteId).fold(
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
            launch { repository.updateLastAccessed(currentNoteId) }

            val result = repository.loadNoteWithChildren(currentNoteId)
            result.fold(
                onSuccess = { loadedLines ->
                    lineTracker.setTrackedLines(loadedLines)
                    val fullContent = loadedLines.joinToString("\n") { it.content }
                    _loadStatus.value = LoadStatus.Success(fullContent, noteLinesToNoteIds(loadedLines))

                    // Cache the loaded content for future tab switches
                    recentTabsViewModel?.cacheNoteContent(
                        currentNoteId,
                        loadedLines,
                        _isNoteDeleted.value ?: false
                    )

                    // Load cached directive results and execute any missing directives
                    loadDirectiveResults(fullContent)

                    loadAlarmStates()
                },
                onFailure = { e ->
                    Log.e("CurrentNoteViewModel", "Error loading note", e)
                    _loadStatus.value = LoadStatus.Error(e)
                }
            )
        }
    }

    /**
     * Updates the tracked lines based on the new content provided by the user.
     * It uses a heuristic to match lines and preserve Note IDs across edits, insertions, and deletions.
     */
    fun updateTrackedLines(newContent: String) {
        lineTracker.updateTrackedLines(newContent)
    }

    fun toggleShowCompleted() {
        val newValue = !(_showCompleted.value ?: true)
        _showCompleted.value = newValue
        suppressSnapshotUpdate = true
        viewModelScope.launch {
            repository.updateShowCompleted(currentNoteId, newValue).onFailure { e ->
                Log.e(TAG, "Failed to persist showCompleted", e)
            }
        }
    }

    fun saveContent(content: String, lineNoteIds: List<List<String>> = emptyList()) {
        // Update tracked lines - use editor noteIds if available, else fall back to content matching
        if (lineNoteIds.isNotEmpty()) {
            val contentLines = content.lines()
            val noteLines = resolveNoteIds(contentLines, lineNoteIds)
            lineTracker.setTrackedLines(noteLines)
        } else {
            updateTrackedLines(content)
        }

        _saveStatus.value = SaveStatus.Saving

        viewModelScope.launch {
            val trackedLines = lineTracker.getTrackedLines()
            val result = persistCurrentNote(trackedLines)

            result.onSuccess {
                syncAlarmNoteIds(trackedLines)

                // Invalidate notes cache so other notes' view directives get fresh data
                // This must happen BEFORE executeAndStoreDirectives to ensure
                // ensureNotesLoaded() fetches fresh notes when switching tabs
                cachedNotes = null
                // Phase 3: Invalidate metadata hash cache when notes change
                MetadataHasher.invalidateCache()

                // Execute directives and store results
                executeAndStoreDirectives(content)
            }
        }
    }

    fun processAgentCommand(currentContent: String, command: String) {
        _isAgentProcessing.value = true
        viewModelScope.launch {
            try {
                val updatedContent = agent.processCommand(currentContent, command)
                // Also update tracked lines since content changed externally
                updateTrackedLines(updatedContent)

                // Update the UI with the new content
                _loadStatus.value = LoadStatus.Success(
                    updatedContent,
                    noteLinesToNoteIds(lineTracker.getTrackedLines())
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
        val lines = lineTracker.getTrackedLines()
        return if (lineIndex < lines.size) {
            lines[lineIndex].noteId ?: currentNoteId
        } else {
            currentNoteId
        }
    }

    /**
     * Fetches alarms for a specific line by its note ID.
     */
    private val _lineAlarms = MutableLiveData<List<Alarm>>()
    val lineAlarms: LiveData<List<Alarm>> = _lineAlarms

    // Recurrence config and template for the currently selected alarm (if recurring)
    private val _recurrenceConfig = MutableLiveData<RecurrenceConfig?>()
    val recurrenceConfig: LiveData<RecurrenceConfig?> = _recurrenceConfig

    private val _recurringAlarm = MutableLiveData<RecurringAlarm?>()
    val recurringAlarm: LiveData<RecurringAlarm?> = _recurringAlarm

    fun fetchAlarmsForLine(lineIndex: Int, onComplete: (() -> Unit)? = null) {
        val noteId = getNoteIdForLine(lineIndex)
        viewModelScope.launch {
            val result = alarmRepository.getAlarmsForNote(noteId)
            result.fold(
                onSuccess = { alarms ->
                    _lineAlarms.value = alarms
                },
                onFailure = { e ->
                    Log.e("CurrentNoteViewModel", "Error fetching alarms for line", e)
                    _lineAlarms.value = emptyList()
                }
            )
            onComplete?.invoke()
        }
    }

    /**
     * Fetches a specific alarm by its document ID.
     * Also populates lineAlarms so the dialog has sibling context.
     */
    fun fetchAlarmById(alarmId: String, onComplete: (Alarm?) -> Unit) {
        viewModelScope.launch {
            val result = alarmRepository.getAlarm(alarmId)
            val alarm = result.getOrNull()
            if (alarm != null) {
                // Also fetch sibling alarms for the same note
                val siblingsResult = alarmRepository.getAlarmsForNote(alarm.noteId)
                siblingsResult.fold(
                    onSuccess = { alarms -> _lineAlarms.value = alarms },
                    onFailure = { _lineAlarms.value = listOfNotNull(alarm) }
                )
            } else {
                _lineAlarms.value = emptyList()
            }
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

    // Per-noteId alarm list for rendering symbol overlays
    private val _noteAlarms = MutableLiveData<Map<String, List<Alarm>>>(emptyMap())
    val noteAlarms: LiveData<Map<String, List<Alarm>>> = _noteAlarms

    /**
     * Loads all alarms for every noteId referenced by the current note's lines.
     * Called after note load and after alarm state changes.
     */
    fun loadAlarmStates() {
        val noteIds = lineTracker.getTrackedLines()
            .mapNotNull { it.noteId }
            .distinct()
        if (noteIds.isEmpty()) {
            _noteAlarms.value = emptyMap()
            return
        }
        viewModelScope.launch {
            alarmRepository.getAlarmsForNotes(noteIds).fold(
                onSuccess = { alarms ->
                    _noteAlarms.value = alarms.groupBy { it.noteId }
                },
                onFailure = { e ->
                    Log.e(TAG, "Error loading alarm states", e)
                    _noteAlarms.value = emptyMap()
                }
            )
        }
    }

    /**
     * Migrates plain ⏰ characters to [alarm("id")] directives.
     *
     * For each line with plain ⏰ characters, matches them to alarms for that line's
     * noteId (sorted by createdAt, matching the existing overlay order). Returns the
     * migrated content, or null if no migration was needed.
     */
    fun migrateAlarmSymbols(
        content: String,
        noteAlarms: Map<String, List<Alarm>>
    ): String? {
        val lines = content.lines()
        val trackedLines = lineTracker.getTrackedLines()
        val lineNoteIds = lines.indices.map { index ->
            if (index < trackedLines.size) {
                trackedLines[index].noteId ?: currentNoteId
            } else {
                currentNoteId
            }
        }

        val result = migrateAlarmSymbolLines(lines, lineNoteIds, noteAlarms) ?: return null
        return if (result.migrated) result.lines.joinToString("\n") else null
    }

    /**
     * Returns [SymbolOverlay] list for each alarm symbol on the given line.
     * The list is ordered by alarm creation time (matching symbol left-to-right order).
     */
    fun getSymbolOverlays(lineIndex: Int, noteAlarms: Map<String, List<Alarm>>): List<SymbolOverlay> {
        val noteId = getNoteIdForLine(lineIndex)
        val alarms = noteAlarms[noteId] ?: return emptyList()
        val now = Timestamp.now()

        // For recurring alarms, only show the most recent instance per recurringAlarmId
        // (old DONE/CANCELLED instances should not produce separate overlays)
        val filtered = filterToActiveRecurringInstances(alarms)

        return filtered
            .sortedBy { it.createdAt?.toDate()?.time ?: 0L }
            .map { alarm -> AlarmOverlayMapping.alarmToOverlay(alarm, now) }
    }

    // Delegated to package-level filterToActiveRecurringInstances() in ViewModelPureLogic.kt

    // Scheduling failure warning
    private val _schedulingWarning = MutableLiveData<String?>()
    val schedulingWarning: LiveData<String?> = _schedulingWarning

    fun clearSchedulingWarning() {
        _schedulingWarning.value = null
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
        viewModelScope.launch {
            updateTrackedLines(content)
            val trackedLines = lineTracker.getTrackedLines()
            val saveResult = persistCurrentNote(trackedLines)

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
        viewModelScope.launch {
            updateTrackedLines(content)
            val trackedLines = lineTracker.getTrackedLines()
            val saveResult = persistCurrentNote(trackedLines)

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

                _alarmCreated.value = AlarmCreatedEvent(alarmId, lineContent, alarmSnapshot)
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
     * Also caches the current note for [.] reference (Milestone 6).
     * Call this before executing directives that may use find().
     */
    private suspend fun ensureNotesLoaded(): List<Note> {
        Log.d(TAG, "ensureNotesLoaded: cachedNotes=${cachedNotes?.size}, cachedCurrentNote=${cachedCurrentNote?.id}, currentNoteId=$currentNoteId")

        // Only skip loading if both notes AND current note are cached
        if (cachedNotes != null && cachedCurrentNote != null) {
            Log.d(TAG, "ensureNotesLoaded: returning cached (notes=${cachedNotes?.size}, currentNote=${cachedCurrentNote?.id})")
            return cachedNotes!!
        }

        // Load notes if not cached
        if (cachedNotes == null) {
            Log.d(TAG, "ensureNotesLoaded: FETCHING FRESH from Firestore...")
            // Use loadNotesWithFullContent for directives that need complete note text (e.g., view())
            val result = repository.loadNotesWithFullContent()
            val notes = result.getOrNull() ?: emptyList()
            cachedNotes = notes
            cachedCurrentNote = notes.find { it.id == currentNoteId }
            Log.d(TAG, "ensureNotesLoaded: FETCHED ${notes.size} notes from Firestore, found currentNote in list: ${cachedCurrentNote != null}")
            // Log first few note contents for debugging
            notes.take(3).forEach { note ->
                Log.d(TAG, "ensureNotesLoaded: note ${note.id} content preview: '${note.content.take(40)}...'")
            }
        }

        // If current note not found in top-level notes (e.g., it's a child note), load it separately
        if (cachedCurrentNote == null) {
            Log.d(TAG, "ensureNotesLoaded: currentNote not in list, loading by ID: $currentNoteId")
            val loadResult = repository.loadNoteById(currentNoteId)
            cachedCurrentNote = loadResult.getOrNull()
            Log.d(TAG, "ensureNotesLoaded: loadNoteById result: ${cachedCurrentNote?.id}, error: ${loadResult.exceptionOrNull()?.message}")
        }

        Log.d(TAG, "ensureNotesLoaded: final cachedCurrentNote=${cachedCurrentNote?.id}")
        return cachedNotes ?: emptyList()
    }

    /**
     * Refreshes the cached notes for find() operations.
     * Also updates the cached current note for [.] reference (Milestone 6).
     * Call this when notes may have changed (e.g., after save).
     */
    private suspend fun refreshNotesCache(): List<Note> {
        // Use loadNotesWithFullContent for directives that need complete note text (e.g., view())
        val result = repository.loadNotesWithFullContent()
        val notes = result.getOrNull() ?: emptyList()
        cachedNotes = notes
        cachedCurrentNote = notes.find { it.id == currentNoteId }

        // If current note not found in top-level notes (e.g., it's a child note), load it separately
        if (cachedCurrentNote == null) {
            cachedCurrentNote = repository.loadNoteById(currentNoteId).getOrNull()
        }

        return notes
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

            // Update cache
            cachedNotes = cachedNotes?.map { note ->
                if (note.id == mutation.noteId) mutation.updatedNote else note
            }

            // Update current note cache if this note was mutated
            if (cachedCurrentNote?.id == mutation.noteId) {
                cachedCurrentNote = mutation.updatedNote
            }

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
    fun executeDirectivesLive(content: String) {
        if (!DirectiveFinder.containsDirectives(content)) {
            return
        }

        viewModelScope.launch {
            // Parse all directive locations from content
            val newLocations = parseAllDirectiveLocations(content)

            // Match to existing instances to preserve UUIDs
            val updatedInstances = matchDirectiveInstances(directiveInstances, newLocations)
            directiveInstances = updatedInstances

            // Find directives that need execution (no existing result)
            val currentResults = _directiveResults.value ?: emptyMap()
            val missingInstances = updatedInstances.filter { currentResults[it.uuid] == null }

            if (missingInstances.isEmpty()) {
                return@launch
            }

            // Load notes for find() operations
            val notes = ensureNotesLoaded()
            Log.d(TAG, "executeDirectivesLive: after ensureNotesLoaded, cachedCurrentNote=${cachedCurrentNote?.id}")

            // Execute missing directives with caching (pass current note for [.] reference - Milestone 6)
            // Pass noteOperations - idempotency analyzer blocks non-idempotent operations
            val allMutations = mutableListOf<NoteMutation>()
            val executedResults = missingInstances.associate { instance ->
                Log.d(TAG, "executeDirectivesLive: executing '${instance.sourceText}' with currentNote=${cachedCurrentNote?.id}")
                val cachedResult = cachedDirectiveExecutor.execute(instance.sourceText, notes, cachedCurrentNote, noteOperations)
                allMutations.addAll(cachedResult.mutations)
                // Register refresh triggers if this is a refresh directive
                registerRefreshTriggersIfNeeded(instance.sourceText, cachedCurrentNote?.id)
                instance.uuid to cachedResult.result
            }
            // Process mutations but skip editor callback during live execution to avoid index issues
            processMutations(allMutations, skipEditorCallback = true)

            // Merge new results into CURRENT state (not the captured snapshot)
            // This avoids overwriting changes made by toggleDirectiveCollapsed while
            // this coroutine was running (race condition fix)
            val latestResults = _directiveResults.value?.toMutableMap() ?: mutableMapOf()
            for ((uuid, result) in executedResults) {
                // Only add if still missing (another coroutine might have added it)
                if (latestResults[uuid] == null) {
                    latestResults[uuid] = result
                }
            }

            _directiveResults.value = latestResults
            Log.d(TAG, "Live executed ${executedResults.size} directives")
        }
    }

    /**
     * Force re-execute all directives with fresh data from Firestore.
     * Used after inline editing a viewed note to refresh the view.
     *
     * Unlike executeDirectivesLive, this:
     * 1. Always refreshes cachedNotes from Firestore first
     * 2. Clears the directive executor cache
     * 3. Re-executes ALL directives (doesn't skip existing results)
     * 4. Updates results in place (preserves collapsed state, no UI flicker)
     */
    fun forceRefreshAllDirectives(content: String, onComplete: (() -> Unit)? = null) {
        Log.d("InlineEditCache", "=== forceRefreshAllDirectives START ===")
        Log.d("InlineEditCache", "content preview: '${content.take(100).replace("\n", "\\n")}...'")
        if (!DirectiveFinder.containsDirectives(content)) {
            Log.d("InlineEditCache", "forceRefreshAllDirectives: no directives, returning")
            onComplete?.invoke()
            return
        }

        viewModelScope.launch {
            // 1. Refresh notes from Firestore FIRST to get fresh data
            Log.d("InlineEditCache", "forceRefreshAllDirectives: calling refreshNotesCache...")
            val notes = refreshNotesCache()
            Log.d("InlineEditCache", "forceRefreshAllDirectives: got ${notes.size} notes from Firestore")
            notes.forEach { note ->
                val firstLine = note.content.lines().firstOrNull() ?: ""
                Log.d("InlineEditCache", "  note ${note.id}: firstLine='$firstLine', content='${note.content.take(80).replace("\n", "\\n")}...'")
            }

            // 2. Clear executor cache so directives re-evaluate with fresh data
            Log.d("InlineEditCache", "forceRefreshAllDirectives: clearing directiveCacheManager...")
            directiveCacheManager.clearAll()
            Log.d("InlineEditCache", "forceRefreshAllDirectives: cache cleared")

            // 3. Parse and match directive instances
            val newLocations = parseAllDirectiveLocations(content)
            val updatedInstances = matchDirectiveInstances(directiveInstances, newLocations)
            directiveInstances = updatedInstances
            Log.d("InlineEditCache", "forceRefreshAllDirectives: found ${updatedInstances.size} directives to re-execute")
            updatedInstances.forEach { inst ->
                Log.d("InlineEditCache", "  directive: uuid=${inst.uuid}, sourceText='${inst.sourceText}'")
            }

            // 4. Re-execute ALL directives (not just missing ones)
            val freshResults = mutableMapOf<String, DirectiveResult>()
            for (instance in updatedInstances) {
                Log.d("InlineEditCache", "forceRefreshAllDirectives: executing '${instance.sourceText}'...")
                val cachedResult = cachedDirectiveExecutor.execute(
                    instance.sourceText, notes, cachedCurrentNote, noteOperations
                )
                freshResults[instance.uuid] = cachedResult.result
                Log.d("InlineEditCache", "  result: cacheHit=${cachedResult.cacheHit}, error=${cachedResult.result.error}")

                // Log view directive content
                val viewVal = cachedResult.result.toValue() as? ViewVal
                if (viewVal != null) {
                    Log.d("InlineEditCache", "  VIEW directive: ${viewVal.notes.size} notes")
                    viewVal.notes.forEachIndexed { idx, note ->
                        val noteContent = viewVal.renderedContents?.getOrNull(idx) ?: note.content
                        Log.d("InlineEditCache", "    note[$idx] id=${note.id}: '${noteContent.take(60).replace("\n", "\\n")}...'")
                    }
                }
            }

            // 5. Merge with current collapsed state (preserves UI state, avoids flicker)
            val currentResults = _directiveResults.value ?: emptyMap()
            Log.d("InlineEditCache", "forceRefreshAllDirectives: merging ${freshResults.size} fresh results with ${currentResults.size} current results")
            val mergedResults = freshResults.mapValues { (uuid, result) ->
                val currentCollapsed = currentResults[uuid]?.collapsed ?: result.collapsed
                result.copy(collapsed = currentCollapsed)
            }

            // 6. Update results
            Log.d("InlineEditCache", "forceRefreshAllDirectives: UPDATING _directiveResults NOW with ${mergedResults.size} entries")
            mergedResults.forEach { (key, result) ->
                val content = result.toValue()?.toDisplayString()?.take(50)
                Log.d("InlineEditCache", "  NEW [$key]: '$content...'")
            }
            _directiveResults.value = mergedResults
            Log.d("InlineEditCache", "=== forceRefreshAllDirectives DONE - _directiveResults UPDATED ===")

            // Call completion callback AFTER results are updated
            onComplete?.invoke()
        }
    }

    /**
     * Execute all directives in the content and store results.
     * Called after note save succeeds.
     * Preserves collapsed state from local results.
     *
     * Uses UUID-based keys for in-memory state, but stores with text hash in Firestore
     * for cross-session caching.
     */
    private fun executeAndStoreDirectives(content: String) {
        if (!DirectiveFinder.containsDirectives(content)) {
            return
        }

        viewModelScope.launch {
            // Parse all directive locations and match to existing instances
            val newLocations = parseAllDirectiveLocations(content)
            val updatedInstances = matchDirectiveInstances(directiveInstances, newLocations)
            directiveInstances = updatedInstances

            // Refresh notes cache (notes may have changed after save)
            // The staleness checker will detect which directives need re-execution
            // based on their dependencies (e.g., dependsOnAllNames for find(name:...))
            val notes = refreshNotesCache()

            // Notify observers that notes cache was refreshed (for view directive updates)
            _notesCacheRefreshed.tryEmit(Unit)

            // Execute all directives with caching (pass current note for [.] reference - Milestone 6)
            val allMutations = mutableListOf<NoteMutation>()
            val freshResults = mutableMapOf<String, DirectiveResult>()
            for (instance in updatedInstances) {
                val cachedResult = cachedDirectiveExecutor.execute(instance.sourceText, notes, cachedCurrentNote, noteOperations)
                allMutations.addAll(cachedResult.mutations)
                freshResults[instance.uuid] = cachedResult.result
                // Register refresh triggers if this is a refresh directive
                registerRefreshTriggersIfNeeded(instance.sourceText, cachedCurrentNote?.id)
            }

            // Process any mutations that occurred (alreadyPersisted=true since this runs during save)
            processMutations(allMutations, alreadyPersisted = true)

            // Merge fresh results with CURRENT collapsed state (read at merge time to avoid race)
            // This ensures we don't overwrite collapsed state changes made while executing
            val mergedResults = mergeDirectiveResults(freshResults, _directiveResults.value)

            // Update local state
            _directiveResults.value = mergedResults

            // Store results in Firestore using text hash as key for cross-session caching
            // Skip view directive results - they depend on other notes and can become stale
            for (instance in updatedInstances) {
                val result = mergedResults[instance.uuid] ?: continue
                val isViewResult = result.toValue() is ViewVal
                if (!isViewResult) {
                    val textHash = DirectiveResult.hashDirective(instance.sourceText)
                    directiveResultRepository.saveResult(currentNoteId, textHash, result)
                        .onFailure { e ->
                            Log.e(TAG, "Failed to save directive result: $textHash", e)
                        }
                }
            }

            Log.d(TAG, "Executed ${mergedResults.size} directives")

            // Register any schedule directives found in this note
            cachedCurrentNote?.let { note ->
                ScheduleManager.registerSchedulesFromNote(note)
            }

            // IMPORTANT: Invalidate notes cache after execution completes.
            // The refreshNotesCache() above may have fetched stale data from Firestore
            // due to eventual consistency (the save write hasn't propagated yet).
            // Setting cachedNotes = null ensures the next tab switch fetches truly fresh data.
            //
            // Phase 2 note: With Phase 1's dependency tracking in place, if we executed with
            // stale data, the content hashes stored in the cache entry won't match the fresh
            // data on next execution. The StalenessChecker will detect this and trigger
            // re-execution automatically.
            cachedNotes = null
            // Phase 3: Invalidate metadata hash cache when notes change
            MetadataHasher.invalidateCache()
        }
    }

    /**
     * Load cached directive results for the current note, and execute any missing directives.
     * Called when note is loaded.
     *
     * Cached results in Firestore are keyed by text hash. We create fresh UUID-based instances
     * and map cached results to them.
     */
    private suspend fun loadDirectiveResults(content: String) {
        // First load cached results (keyed by text hash in Firestore)
        val cachedByHash = directiveResultRepository.getResults(currentNoteId)
            .getOrElse { e ->
                Log.e(TAG, "Failed to load directive results", e)
                emptyMap()
            }

        if (!DirectiveFinder.containsDirectives(content)) {
            directiveInstances = emptyList()
            _directiveResults.postValue(emptyMap())
            return
        }

        // Parse all directive locations and create fresh instances with UUIDs
        val newLocations = parseAllDirectiveLocations(content)
        val newInstances = newLocations.map { loc ->
            DirectiveInstance.create(loc.lineIndex, loc.startOffset, loc.sourceText)
        }
        directiveInstances = newInstances

        // Build UUID-based results map from cached results
        val uuidResults = mutableMapOf<String, DirectiveResult>()
        val missingInstances = mutableListOf<DirectiveInstance>()

        for (instance in newInstances) {
            val textHash = DirectiveResult.hashDirective(instance.sourceText)
            val cached = cachedByHash[textHash]
            val cachedValue = cached?.toValue()
            val cachedResultType = cached?.result?.get("type") as? String

            // Skip cached view results - they depend on other notes and can become stale
            // Views should always be re-executed to get fresh data
            // Check BOTH the deserialized value AND the raw type field (in case deserialization fails)
            val isViewResult = cachedValue is ViewVal || cachedResultType == "view"

            // Skip cached bare temporal values - they are now invalid and need re-execution
            // to get the proper error message. This handles old cache entries from before
            // the "bare temporal not allowed" rule was added.
            val isBareTemporalResult = cachedValue is DateVal ||
                    cachedValue is TimeVal ||
                    cachedValue is DateTimeVal ||
                    cachedResultType in listOf("date", "time", "datetime")

            // Skip cached alarm results - alarm() is a trivial pure function and
            // cached results may fail to deserialize from Firestore, causing the
            // directive to show as raw text instead of the ⏰ symbol
            val isAlarmResult = cachedValue is AlarmVal || cachedResultType == "alarm"

            if (cached != null && !isViewResult && !isBareTemporalResult && !isAlarmResult) {
                uuidResults[instance.uuid] = cached
            } else {
                missingInstances.add(instance)
            }
        }

        if (missingInstances.isEmpty()) {
            // All directives have cached results
            _directiveResults.postValue(uuidResults)
            return
        }

        // Load notes for find() operations
        val notes = ensureNotesLoaded()

        // Execute missing directives with caching (pass current note for [.] reference - Milestone 6)
        // Pass noteOperations - idempotency analyzer blocks non-idempotent operations
        val allMutations = mutableListOf<NoteMutation>()
        for (instance in missingInstances) {
            val cachedResult = cachedDirectiveExecutor.execute(instance.sourceText, notes, cachedCurrentNote, noteOperations)
            allMutations.addAll(cachedResult.mutations)
            uuidResults[instance.uuid] = cachedResult.result

            val viewVal = cachedResult.result.toValue() as? ViewVal

            // Register refresh triggers if this is a refresh directive
            registerRefreshTriggersIfNeeded(instance.sourceText, cachedCurrentNote?.id)
            // Store in Firestore using text hash
            // Skip view directive results - they depend on other notes and can become stale
            // Skip alarm results - trivial to re-execute and avoids deserialization issues
            val isViewResult = viewVal != null
            val isAlarmResult = cachedResult.result.toValue() is AlarmVal
            if (!isViewResult && !isAlarmResult) {
                val textHash = DirectiveResult.hashDirective(instance.sourceText)
                directiveResultRepository.saveResult(currentNoteId, textHash, cachedResult.result)
                    .onFailure { e ->
                        Log.e(TAG, "Failed to save directive result: $textHash", e)
                    }
            }
        }
        // Process mutations (skip editor callback since we're loading)
        processMutations(allMutations, skipEditorCallback = true)

        _directiveResults.postValue(uuidResults)
    }

    /**
     * Toggle the collapsed state of a directive result.
     * If no result exists for this UUID, executes the directive first.
     *
     * @param directiveUuid The UUID of the directive instance
     * @param sourceText The source text of the directive (e.g., "[42]"), needed to execute if no result exists
     */
    fun toggleDirectiveCollapsed(directiveUuid: String, sourceText: String? = null) {
        val current = _directiveResults.value ?: mutableMapOf()
        val existingResult = current[directiveUuid]

        if (existingResult == null) {
            // No result exists - execute the directive and create result with collapsed = false
            if (sourceText == null) {
                Log.w(TAG, "Cannot toggle directive without sourceText when no result exists")
                return
            }

            // Launch coroutine to ensure notes are loaded before executing
            viewModelScope.launch {
                ensureNotesLoaded()
                Log.d(TAG, "toggleDirectiveCollapsed: executing '$sourceText' with cachedCurrentNote=${cachedCurrentNote?.id}")
                // Execute with caching - idempotency analyzer blocks non-idempotent operations
                val cachedResult = cachedDirectiveExecutor.execute(sourceText, cachedNotes ?: emptyList(), cachedCurrentNote, noteOperations)
                processMutations(cachedResult.mutations, skipEditorCallback = true)
                val updated = (_directiveResults.value ?: mutableMapOf()).toMutableMap()
                updated[directiveUuid] = cachedResult.result.copy(collapsed = false)
                _directiveResults.value = updated
            }
        } else {
            // Result exists - toggle collapsed state
            val newCollapsed = !existingResult.collapsed
            val updated = current.toMutableMap()
            updated[directiveUuid] = existingResult.copy(collapsed = newCollapsed)
            _directiveResults.value = updated
        }
        // Firestore sync happens on save via executeAndStoreDirectives
    }

    /**
     * Re-executes a directive and collapses it.
     * Called when user confirms a directive edit (including when no changes were made).
     * This ensures dynamic directives like [now] get fresh values on confirm.
     *
     * @param directiveUuid The UUID of the directive instance
     * @param sourceText The source text of the directive (e.g., "[now]")
     */
    fun confirmDirective(directiveUuid: String, sourceText: String) {
        viewModelScope.launch {
            ensureNotesLoaded()
            Log.d(TAG, "confirmDirective: executing '$sourceText' with cachedCurrentNote=${cachedCurrentNote?.id}")

            val current = _directiveResults.value?.toMutableMap() ?: mutableMapOf()

            // Always re-execute to get fresh value (important for dynamic directives like [now])
            // Execute with caching - idempotency analyzer blocks non-idempotent operations
            val cachedResult = cachedDirectiveExecutor.execute(sourceText, cachedNotes ?: emptyList(), cachedCurrentNote, noteOperations)
            processMutations(cachedResult.mutations, skipEditorCallback = true)
            current[directiveUuid] = cachedResult.result.copy(collapsed = true)
            _directiveResults.value = current

            Log.d(TAG, "Confirmed directive $directiveUuid with fresh result")
        }
    }

    /**
     * Re-executes a directive and keeps it expanded.
     * Called when user refreshes a directive edit (recompute without closing).
     *
     * @param directiveUuid The UUID of the directive instance
     * @param sourceText The source text of the directive (e.g., "[now]")
     */
    fun refreshDirective(directiveUuid: String, sourceText: String) {
        viewModelScope.launch {
            ensureNotesLoaded()
            Log.d(TAG, "refreshDirective: executing '$sourceText' with cachedCurrentNote=${cachedCurrentNote?.id}")

            val current = _directiveResults.value?.toMutableMap() ?: mutableMapOf()

            // Re-execute to get fresh value but keep expanded
            // Execute with caching - idempotency analyzer blocks non-idempotent operations
            val cachedResult = cachedDirectiveExecutor.execute(sourceText, cachedNotes ?: emptyList(), cachedCurrentNote, noteOperations)
            processMutations(cachedResult.mutations, skipEditorCallback = true)
            current[directiveUuid] = cachedResult.result.copy(collapsed = false)
            _directiveResults.value = current

            Log.d(TAG, "Refreshed directive $directiveUuid with fresh result (kept expanded)")
        }
    }

    /**
     * Executes a button directive's action.
     * Called when user clicks a button rendered from a ButtonVal.
     *
     * Note: The buttonVal passed here has a placeholder lambda (lambdas can't be serialized).
     * We need to re-parse the source text to get the real lambda for execution.
     *
     * @param directiveKey Position-based key (e.g., "3:15" for line 3, offset 15)
     * @param buttonVal The ButtonVal (used for label display, action is placeholder)
     * @param sourceText The original directive source text to re-parse for execution
     */
    fun executeButton(directiveKey: String, buttonVal: ButtonVal, sourceText: String? = null) {
        viewModelScope.launch {
            ensureNotesLoaded()

            // Set loading state
            updateButtonState(directiveKey, ButtonExecutionState.LOADING)

            try {
                // Create fresh environment for button execution with current context
                // This ensures mutations are properly captured
                val env = Environment(
                    NoteContext(
                        notes = cachedNotes ?: emptyList(),
                        currentNote = cachedCurrentNote,
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
     * Gets the UUID for a directive at the given position.
     * Returns null if no directive instance exists at that position.
     */
    fun getDirectiveUuid(lineIndex: Int, startOffset: Int): String? {
        return directiveInstances.find { it.lineIndex == lineIndex && it.startOffset == startOffset }?.uuid
    }

    /**
     * Gets directive results keyed by position (lineIndex:startOffset) for UI display.
     * This converts from the internal UUID-keyed results to position-keyed results.
     */
    fun getResultsByPosition(): Map<String, DirectiveResult> {
        val uuidResults = _directiveResults.value ?: return emptyMap()
        return mapResultsByPosition(directiveInstances, uuidResults)
    }

    /**
     * Gets the positions of all currently expanded directive edit rows.
     * Used before undo/redo to preserve expanded state by position.
     */
    fun getExpandedDirectivePositions(content: String): Set<DirectivePosition> {
        val current = _directiveResults.value ?: return emptySet()
        return findExpandedPositions(directiveInstances, current)
    }

    /**
     * Restores expanded state for directives at the given positions.
     * Called after undo/redo to preserve edit row state for directives that still exist.
     * Re-runs directive matching to assign UUIDs to the new content.
     */
    fun restoreExpandedDirectivesByPosition(content: String, positions: Set<DirectivePosition>) {
        if (positions.isEmpty()) return

        // Re-parse directives and match to existing instances
        val newLocations = parseAllDirectiveLocations(content)
        val updatedInstances = matchDirectiveInstances(directiveInstances, newLocations)
        directiveInstances = updatedInstances

        val current = _directiveResults.value?.toMutableMap() ?: mutableMapOf()

        // Find directives that need execution
        val needsExecution = mutableListOf<DirectiveInstance>()

        // Find directives at the target positions and expand them
        for (instance in updatedInstances) {
            val pos = DirectivePosition(instance.lineIndex, instance.startOffset)
            if (pos in positions) {
                val existing = current[instance.uuid]
                if (existing != null) {
                    current[instance.uuid] = existing.copy(collapsed = false)
                } else {
                    needsExecution.add(instance)
                }
            }
        }

        _directiveResults.value = current

        // Execute directives that don't have results yet (in coroutine to ensure notes are loaded)
        if (needsExecution.isNotEmpty()) {
            viewModelScope.launch {
                ensureNotesLoaded()
                Log.d(TAG, "restoreExpandedDirectivesByPosition: executing ${needsExecution.size} directives with cachedCurrentNote=${cachedCurrentNote?.id}")

                // Execute with caching - idempotency analyzer blocks non-idempotent operations
                val allMutations = mutableListOf<NoteMutation>()
                val updated = _directiveResults.value?.toMutableMap() ?: mutableMapOf()
                for (instance in needsExecution) {
                    val cachedResult = cachedDirectiveExecutor.execute(instance.sourceText, cachedNotes ?: emptyList(), cachedCurrentNote, noteOperations)
                    allMutations.addAll(cachedResult.mutations)
                    updated[instance.uuid] = cachedResult.result.copy(collapsed = false)
                }
                processMutations(allMutations, skipEditorCallback = true)
                _directiveResults.value = updated
            }
        }
    }

    /**
     * Execute directives for arbitrary content (e.g., a viewed note being edited inline).
     * Returns directive results keyed by "lineIndex:startOffset" for use in rendering.
     *
     * This is used when inline editing a viewed note to render its directives properly.
     * The results are separate from the main note's directive results.
     *
     * @param content The content to parse and execute directives for
     * @param onResults Callback with the results when execution completes
     */
    fun executeDirectivesForContent(content: String, onResults: (Map<String, DirectiveResult>) -> Unit) {
        Log.d("InlineEditCache", "=== executeDirectivesForContent START ===")
        Log.d("InlineEditCache", "content preview: '${content.take(100).replace("\n", "\\n")}...'")
        if (!DirectiveFinder.containsDirectives(content)) {
            Log.d("InlineEditCache", "executeDirectivesForContent: no directives found")
            onResults(emptyMap())
            return
        }

        viewModelScope.launch {
            Log.d("InlineEditCache", "executeDirectivesForContent: calling ensureNotesLoaded...")
            val notes = ensureNotesLoaded()
            Log.d("InlineEditCache", "executeDirectivesForContent: got ${notes.size} notes")
            val locations = parseAllDirectiveLocations(content)
            Log.d("InlineEditCache", "executeDirectivesForContent: found ${locations.size} directive locations")

            val results = mutableMapOf<String, DirectiveResult>()
            for (loc in locations) {
                val key = DirectiveFinder.directiveKey(loc.lineIndex, loc.startOffset)
                Log.d("InlineEditCache", "executeDirectivesForContent: executing '${loc.sourceText}' at key=$key")
                // Execute directive (mutations are skipped for inline view - read-only context)
                try {
                    val cachedResult = cachedDirectiveExecutor.execute(
                        loc.sourceText, notes, cachedCurrentNote, null // null noteOperations = read-only
                    )
                    results[key] = cachedResult.result
                    val displayText = cachedResult.result.toValue()?.toDisplayString()
                    Log.d("InlineEditCache", "  result: cacheHit=${cachedResult.cacheHit}, display='${displayText?.take(50)}', error=${cachedResult.result.error}")
                } catch (e: Exception) {
                    Log.e("InlineEditCache", "executeDirectivesForContent: error executing '${loc.sourceText}'", e)
                    // Store error result
                    results[key] = DirectiveResult.failure(e.message ?: "Execution error")
                }
            }

            Log.d("InlineEditCache", "=== executeDirectivesForContent DONE - returning ${results.size} results ===")
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
        viewModelScope.launch {
            val notes = ensureNotesLoaded()
            val cachedResult = cachedDirectiveExecutor.execute(
                sourceText, notes, cachedCurrentNote, null // null noteOperations = read-only
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
        val lines = newContent.lines()
        Log.d("InlineEditCache", "=== saveInlineNoteContent START ===")
        Log.d("InlineEditCache", "noteId=$noteId")
        Log.d("InlineEditCache", "content has ${lines.size} lines, first='${lines.firstOrNull()}'")
        Log.d("InlineEditCache", "full content: '${newContent.take(100).replace("\n", "\\n")}...'")
        Log.d("InlineEditCache", "cachedNotes was ${if (cachedNotes == null) "NULL" else "${cachedNotes?.size} notes"}")

        viewModelScope.launch {
            startInlineEditSession(noteId)

            Log.d("InlineEditCache", "saveInlineNoteContent: calling repository.saveNoteWithFullContent...")
            // Use saveNoteWithFullContent to properly handle multi-line notes
            // This preserves child note IDs and handles line additions/deletions
            //
            // Phase 2 (Caching Audit): AWAIT save completion before clearing caches.
            // This ensures that when we refresh, Firestore has the new data.
            val saveResult = repository.saveNoteWithFullContent(noteId, newContent)

            saveResult
                .onSuccess {
                    Log.d("InlineEditCache", "=== saveInlineNoteContent SUCCESS for $noteId ===")

                    // OPTIMISTIC UPDATE: Replace the saved note's content in the cache
                    // instead of clearing entirely. This prevents UI from showing stale
                    // content if it re-renders before forceRefreshAllDirectives completes.
                    Log.d("InlineEditCache", "saveInlineNoteContent: OPTIMISTIC UPDATE of cachedNotes...")
                    cachedNotes = cachedNotes?.map { note ->
                        if (note.id == noteId) {
                            Log.d("InlineEditCache", "  Updated note $noteId in cache with new content")
                            note.copy(content = newContent)
                        } else note
                    }
                    // Clear directive cache so they re-execute with the optimistic content
                    MetadataHasher.invalidateCache()
                    directiveCacheManager.clearAll()
                    Log.d("InlineEditCache", "saveInlineNoteContent: directive caches cleared, notes cache has optimistic content")

                    // DO NOT end session here - the caller must end it AFTER
                    // forceRefreshAllDirectives completes to avoid stale content.
                    // See the onComplete callback in forceRefreshAllDirectives.
                    Log.d("InlineEditCache", "saveInlineNoteContent: NOT ending session here (caller will end after refresh)")

                    onSuccess?.invoke()
                    Log.d("InlineEditCache", "saveInlineNoteContent: onSuccess callback DONE")
                }
                .onFailure { e ->
                    Log.e("InlineEditCache", "=== saveInlineNoteContent FAILED for $noteId ===", e)

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
        snapshotListener?.remove()
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
        viewModelScope.launch {
            val result = repository.softDeleteNote(currentNoteId)
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Note deleted successfully: $currentNoteId")
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
        viewModelScope.launch {
            val result = repository.undeleteNote(currentNoteId)
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Note restored successfully: $currentNoteId")
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
    object Success : SaveStatus()
    data class Error(val throwable: Throwable) : SaveStatus()
}

sealed class LoadStatus {
    object Loading : LoadStatus()
    data class Success(
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
    val alarmSnapshot: AlarmSnapshot? = null
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
