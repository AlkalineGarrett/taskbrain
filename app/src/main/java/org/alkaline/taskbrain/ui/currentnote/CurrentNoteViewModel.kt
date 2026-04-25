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
import org.alkaline.taskbrain.service.RecurrenceConfigMapper
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

    /**
     * True when the current note's reconstruction had to auto-heal
     * a discrepancy (stray appended or orphan ref dropped). The save
     * button switches to the "needs fix" color and becomes enabled
     * so the user can persist the healed content.
     */
    private val _noteNeedsFix = MutableLiveData<Boolean>(false)
    val noteNeedsFix: LiveData<Boolean> = _noteNeedsFix

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

    /**
     * Whether a save is currently in flight. The changedNoteIds handler skips
     * while this is true — mirrors the web's `savingRef` pattern — so the
     * echo of our own save can't trigger a reload that races the save's
     * post-completion bookkeeping (noteId reassignment, `_loadStatus` update).
     */
    @Volatile
    private var saving: Boolean = false

    // Current Note ID being edited — initialized from SharedPreferences so that LiveData
    // exposes the correct value immediately, preventing a race where the sync LaunchedEffect
    // in CurrentNoteScreen sets displayedNoteId to a stale default before loadContent runs.
    // For new users (no stored pref), starts as null/empty — loadContent will create a note.
    //
    // Must be initialized BEFORE `init {}` because the init block launches coroutines
    // that subscribe to StateFlows whose first emission is synchronous; those lambdas
    // dereference currentNoteId and would NPE on the uninitialized JVM backing field.
    private val LAST_VIEWED_NOTE_KEY = "last_viewed_note_id"
    private var currentNoteId = sharedPreferences.getString(LAST_VIEWED_NOTE_KEY, null) ?: ""

    // Expose current note ID for UI (e.g., undo state persistence).
    // Nullable: null means no note loaded yet (new user before first note is created).
    private val _currentNoteIdLiveData = MutableLiveData<String?>(
        sharedPreferences.getString(LAST_VIEWED_NOTE_KEY, null)
    )
    val currentNoteIdLiveData: LiveData<String?> = _currentNoteIdLiveData

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
        // Update noteNeedsFix when the current note moves in/out of the needs-fix set.
        viewModelScope.launch {
            NoteStore.notesNeedingFix.collect { set ->
                _noteNeedsFix.value = currentNoteId.isNotEmpty() && currentNoteId in set
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
                if (saving) return@collect

                val storeNote = NoteStore.getNoteById(noteId) ?: return@collect
                val currentContent = (_loadStatus.value as? LoadStatus.Success)?.content ?: return@collect
                if (storeNote.content == currentContent) return@collect

                val storeLines = NoteStore.getNoteLinesById(noteId) ?: return@collect
                applyNoteContent(
                    noteId, storeLines,
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
    private suspend fun persistCurrentNote(
        noteId: String,
        trackedLines: List<NoteLine>,
        extraOpsBuilder: NoteRepository.ExtraOpsBuilder?,
    ): Result<Map<Int, String>> {
        val result = repository.saveNoteWithChildren(noteId, trackedLines, extraOpsBuilder)
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

    /**
     * Apply resolved note data to the editor: update metadata, lines, load status, and directives.
     * All load paths (dirty cache, NoteStore, Firestore, external change) converge here.
     */
    private fun applyNoteContent(
        noteId: String,
        lines: List<NoteLine>,
        isDeleted: Boolean,
        showCompleted: Boolean,
    ) {
        _isNoteDeleted.value = isDeleted
        _showCompleted.value = showCompleted
        currentNoteLines = lines
        val content = lines.joinToString("\n") { it.content }
        _loadStatus.value = LoadStatus.Success(noteId, lines)
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
        _noteNeedsFix.value = resolvedId in NoteStore.notesNeedingFix.value

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
            Log.d(TAG, "loadContent: restoring dirty editor cache for $noteId")
            applyNoteContent(
                noteId, cached.noteLines,
                isDeleted = cached.isDeleted,
                showCompleted = NoteStore.getNoteById(noteId)?.showCompleted ?: true,
            )

            // Background refresh for proper noteId mappings (the fire-and-forget save
            // may have created new child notes with IDs we don't have yet)
            val cachedContent = cached.noteLines.joinToString("\n") { it.content }
            viewModelScope.launch {
                repository.loadNoteWithChildren(noteId).onSuccess { result ->
                    if (noteId != currentNoteId) return@onSuccess
                    val freshContent = result.lines.joinToString("\n") { it.content }
                    if (freshContent == cachedContent) {
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
            // Use getNoteLinesById to get proper noteId mappings from the in-memory tree,
            // avoiding the race condition where a save could happen before a background
            // Firestore refresh provides the real IDs.
            val storeLines = NoteStore.getNoteLinesById(noteId) ?: storeNote.content.lines().mapIndexed { index, line ->
                NoteLine(content = line, noteId = if (index == 0) noteId else null)
            }
            Log.d(TAG, "loadContent: using NoteStore content for $noteId (${storeLines.size} lines, noteIds: ${storeLines.count { it.noteId != null }}/${storeLines.size})")
            applyNoteContent(
                noteId, storeLines,
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
                    applyNoteContent(noteId, loadedLines, isDeleted, showCompleted)
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
     * Used as a fallback when editor noteIds are not available — currently the
     * agent-rewrite path, which hands back a plain-text rewrite with no line
     * identity information. Any unmatched-non-empty line loses its noteId; the
     * save layer then allocates fresh docs (losing alarm bindings, etc.). A
     * structured agent output API would remove this lossy reconciliation.
     *
     * Delegates to the shared [reconcileLineNoteIds] / [enforceParentNoteId] helpers.
     */
    fun updateTrackedLines(newContent: String) {
        val newLinesContent = newContent.lines()
        val oldLines = currentNoteLines

        if (oldLines.isEmpty()) {
            // This path allocates null noteIds for every line except the root —
            // if any save fires off this state, the save layer has to recover ids
            // via content match (reconcileNullNoteIdsByContent). Log loudly so
            // the caller that hit the empty-oldLines race shows up in logs.
            Log.w(
                TAG,
                buildUpdateTrackedLinesDiagnostics(
                    reason = "oldLines is empty at updateTrackedLines entry",
                    oldLines = emptyList(),
                    newLinesContent = newLinesContent,
                    unmatched = newLinesContent.mapIndexedNotNull { i, c ->
                        if (c.isNotEmpty() && i != 0) i to c else null
                    },
                ),
                Throwable("updateTrackedLines stack")
            )
            currentNoteLines = newLinesContent.mapIndexed { index, content ->
                NoteLine(content, if (index == 0) currentNoteId else null)
            }
            return
        }

        val oldContents = oldLines.map { it.content }
        val oldNoteIds = oldLines.map { listOfNotNull(it.noteId) }

        val unmatched = mutableListOf<Pair<Int, String>>()
        val reconciled = org.alkaline.taskbrain.data.reconcileLineNoteIds(
            oldContents = oldContents,
            oldNoteIds = oldNoteIds,
            newContents = newLinesContent,
            onUnmatchedNonEmpty = { idx, content -> unmatched.add(idx to content) },
        )
        val withParent = org.alkaline.taskbrain.data.enforceParentNoteId(reconciled, currentNoteId)

        if (unmatched.isNotEmpty()) {
            Log.w(
                TAG,
                buildUpdateTrackedLinesDiagnostics(
                    reason = "content-based reconciliation left non-empty lines unmatched",
                    oldLines = oldLines,
                    newLinesContent = newLinesContent,
                    unmatched = unmatched,
                ),
                Throwable("updateTrackedLines stack")
            )
        }

        currentNoteLines = newLinesContent.mapIndexed { index, content ->
            NoteLine(content, withParent[index].firstOrNull())
        }
    }

    private fun buildUpdateTrackedLinesDiagnostics(
        reason: String,
        oldLines: List<NoteLine>,
        newLinesContent: List<String>,
        unmatched: List<Pair<Int, String>>,
    ): String = buildString {
        appendLine("=== UPDATE TRACKED LINES (LOSSY) ===")
        appendLine("reason: $reason")
        appendLine("currentNoteId: $currentNoteId")
        appendLine("oldLines: ${oldLines.size}")
        appendLine("newLines: ${newLinesContent.size}")
        appendLine("unmatched (non-empty new lines with no old match): ${unmatched.size}")

        appendLine("--- unmatched new-line detail ---")
        for ((idx, content) in unmatched.take(20)) {
            appendLine("  [$idx] '${content.take(80)}'")
        }
        if (unmatched.size > 20) appendLine("  ... (${unmatched.size - 20} more)")

        appendLine("--- oldLines ---")
        for ((i, line) in oldLines.withIndex().take(40)) {
            val preview = line.content.take(60).replace("\n", "\\n")
            appendLine("  [$i] noteId=${line.noteId ?: "null"} content='$preview'")
        }
        if (oldLines.size > 40) appendLine("  ... (${oldLines.size - 40} more)")

        appendLine("--- newLines ---")
        for ((i, content) in newLinesContent.withIndex().take(40)) {
            val preview = content.take(60).replace("\n", "\\n")
            appendLine("  [$i] '$preview'")
        }
        if (newLinesContent.size > 40) appendLine("  ... (${newLinesContent.size - 40} more)")

        appendLine("=== END UPDATE TRACKED LINES ===")
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

    /**
     * Saves the main note from a pre-built list of [NoteLine]s.
     *
     * Callers must build [trackedLines] via [EditorState.toNoteLines] (or equivalent)
     * — we no longer accept `(text, lineNoteIds)` as parallel arrays because that shape
     * is fragile when text and noteIds drift apart.
     */
    fun saveContent(trackedLines: List<NoteLine>) {
        currentNoteLines = trackedLines
        val content = trackedLines.joinToString("\n") { it.content }

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
            val result = persistCurrentNote(savedNoteId, trackedLines, extraOpsBuilder = null)

            result.onSuccess {
                alarmManager.syncAlarmNoteIds(trackedLines)
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
                _loadStatus.value = LoadStatus.Success(capturedNoteId, currentNoteLines)
                
                // Signal that the content has been modified and is unsaved
                _contentModified.value = true
            } catch (e: org.alkaline.taskbrain.data.OfflineException) {
                Log.w("CurrentNoteViewModel", "Agent unavailable offline", e)
                _loadStatus.value = LoadStatus.Error(e)
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

    /** Mints a fresh alarm doc ID client-side, no Firestore write. */
    fun newAlarmId(): String = alarmRepository.newAlarmId()

    /** Mints a fresh recurringAlarm doc ID client-side, no Firestore write. */
    fun newRecurringAlarmId(): String = recurringAlarmRepository.newRecurringAlarmId()

    /**
     * Saves the note and creates the alarm doc atomically in one batch, then
     * schedules and emits [NoteAlarmManager.alarmCreated]. The directive must
     * already be in [trackedLines] (inserted by the caller using [newAlarmId]).
     */
    fun saveAndCreateAlarm(
        trackedLines: List<NoteLine>,
        lineContent: String,
        lineIndex: Int?,
        alarmId: String,
        dueTime: Timestamp?,
        stages: List<AlarmStage>,
        inlineSession: InlineEditSession?,
    ) = saveAndCreateAlarmInBatch(trackedLines, lineContent, lineIndex, inlineSession) { lineNoteId ->
        val alarm = Alarm(
            id = alarmId,
            noteId = lineNoteId,
            lineContent = lineContent,
            dueTime = dueTime,
            stages = stages,
        )
        alarm to listOf(alarmRepository.buildCreateBatchOp(alarm))
    }

    /**
     * Saves the note + recurringAlarm template + first instance alarm
     * atomically in one batch, then schedules and emits the create event.
     */
    fun saveAndCreateRecurringAlarm(
        trackedLines: List<NoteLine>,
        lineContent: String,
        lineIndex: Int?,
        recurringAlarmId: String,
        alarmId: String,
        dueTime: Timestamp?,
        stages: List<AlarmStage>,
        recurrenceConfig: RecurrenceConfig,
        inlineSession: InlineEditSession?,
    ) = saveAndCreateAlarmInBatch(trackedLines, lineContent, lineIndex, inlineSession) { lineNoteId ->
        val recurring = RecurrenceConfigMapper.toRecurringAlarm(
            noteId = lineNoteId,
            lineContent = lineContent,
            dueTime = dueTime,
            stages = stages,
            config = recurrenceConfig,
        ).copy(id = recurringAlarmId, currentAlarmId = alarmId)
        val instance = Alarm(
            id = alarmId,
            noteId = lineNoteId,
            lineContent = lineContent,
            dueTime = dueTime,
            stages = stages,
            recurringAlarmId = recurringAlarmId,
        )
        instance to listOf(
            recurringAlarmRepository.buildCreateBatchOp(recurring),
            alarmRepository.buildCreateBatchOp(instance),
        )
    }

    /**
     * Shared driver for the alarm/recurring-alarm batch-create paths.
     * [build] runs after the save resolves the line's noteId; it returns the
     * Alarm to schedule plus the batch ops to splice into the note save.
     */
    private fun saveAndCreateAlarmInBatch(
        trackedLines: List<NoteLine>,
        lineContent: String,
        lineIndex: Int?,
        inlineSession: InlineEditSession?,
        build: (lineNoteId: String) -> Pair<Alarm, List<NoteRepository.BatchExtraOp>>,
    ) {
        viewModelScope.launch {
            var alarmToSchedule: Alarm? = null
            val builder = NoteRepository.ExtraOpsBuilder { resolveLineId, _ ->
                val lineNoteId = when {
                    lineIndex != null -> resolveLineId(lineIndex)
                    inlineSession != null -> inlineSession.noteId
                    else -> currentNoteId
                }
                val (alarm, ops) = build(lineNoteId)
                alarmToSchedule = alarm
                ops
            }
            val result = if (inlineSession != null) {
                directiveManager.saveInlineEditSessionWithExtras(inlineSession, builder)
            } else {
                currentNoteLines = trackedLines
                persistCurrentNote(currentNoteId, currentNoteLines, builder)
            }
            if (result.isSuccess) {
                alarmToSchedule?.let { alarmManager.completeAlarmCreation(it, lineContent) }
            }
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
    /**
     * Saves the main note and all dirty inline edit sessions.
     *
     * Callers must build [trackedLines] via [EditorState.toNoteLines]; the parallel-array
     * `(text, lineNoteIds)` shape was removed because it was fragile when text and noteIds
     * drift apart (e.g., the editor's noteIds list got mostly emptied via a lossy reload).
     */
    fun saveAll(
        trackedLines: List<NoteLine>,
        dirtySessions: List<InlineEditSession>
    ) {
        _saveStatus.value = UnifiedSaveStatus.Saving

        val savedNoteId = currentNoteId
        val content = trackedLines.joinToString("\n") { it.content }
        currentNoteLines = trackedLines

        // Update NoteStore synchronously before async writes
        val existing = NoteStore.getNoteById(savedNoteId)
        if (existing != null) {
            NoteStore.updateNote(savedNoteId, existing.copy(content = content), persist = false)
        }
        MetadataHasher.invalidateCache()

        saving = true
        viewModelScope.launch {
            try {
                val failedNoteIds = mutableListOf<String>()
                var lastError: Throwable? = null

                // Save main note (without persistCurrentNote's status side effects)
                val mainResult = repository.saveNoteWithChildren(savedNoteId, trackedLines, extraOpsBuilder = null)
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
                    alarmManager.syncAlarmLineContent(trackedLines)
                    alarmManager.syncAlarmNoteIds(trackedLines)
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
                    // Bring _loadStatus up to date with the just-saved content so
                    // the external change handler's content-equality check
                    // suppresses our own save echo. currentNoteLines has the
                    // freshly assigned noteIds at this point.
                    _loadStatus.value = LoadStatus.Success(savedNoteId, currentNoteLines)
                    // The save wrote the healed containedNotes — clear the
                    // needs-fix flag optimistically so the UI flips back now
                    // instead of waiting for the Firestore echo.
                    NoteStore.markNoteFixed(savedNoteId)
                } else {
                    _saveStatus.value = UnifiedSaveStatus.PartialError(failedNoteIds, lastError!!)
                }
            } finally {
                saving = false
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
    /**
     * A note has been loaded.
     *
     * Carries the full [NoteLine] list (text + noteId per line) so the editor can
     * always be initialized via [EditorState.initFromNoteLines]. The earlier shape
     * passed `content: String` and `lineNoteIds: List<List<String>>` as parallel
     * arrays — that shape is fragile because the two could drift out of sync, and
     * forced a lossy `updateFromText` fallback when `lineNoteIds` was missing.
     *
     * [content] is a convenience accessor that joins line texts with newlines.
     */
    data class Success(
        val noteId: String,
        val lines: List<NoteLine>,
    ) : LoadStatus() {
        val content: String get() = lines.joinToString("\n") { it.content }
    }
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
