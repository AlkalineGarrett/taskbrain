package org.alkaline.taskbrain.ui.currentnote

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.dsl.cache.CachedDirectiveExecutor
import org.alkaline.taskbrain.dsl.cache.DirectiveCacheManager
import org.alkaline.taskbrain.dsl.cache.EditSessionManager
import org.alkaline.taskbrain.dsl.cache.MetadataHasher
import org.alkaline.taskbrain.dsl.cache.RefreshScheduler
import org.alkaline.taskbrain.dsl.cache.RefreshTriggerAnalyzer
import kotlinx.coroutines.tasks.await
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.onceAwareKey
import org.alkaline.taskbrain.dsl.directives.DirectiveResultRepository
import org.alkaline.taskbrain.dsl.directives.DirectiveSegment
import org.alkaline.taskbrain.dsl.directives.ScheduleManager
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.MutationType
import org.alkaline.taskbrain.dsl.runtime.NoteContext
import org.alkaline.taskbrain.dsl.runtime.NoteMutation
import org.alkaline.taskbrain.dsl.runtime.NoteRepositoryOperations
import org.alkaline.taskbrain.dsl.runtime.PersistableOnceCache
import org.alkaline.taskbrain.dsl.runtime.values.AlarmVal
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState
import org.alkaline.taskbrain.dsl.language.Expression
import org.alkaline.taskbrain.dsl.language.Assignment
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.dsl.language.RefreshExpr
import org.alkaline.taskbrain.dsl.language.StatementList

class NoteDirectiveManager(
    private val scope: CoroutineScope,
    private val repository: NoteRepository,
    private val directiveResultRepository: DirectiveResultRepository,
    private val noteOperationsProvider: (() -> NoteRepositoryOperations?)?,
    private val getCurrentNoteId: () -> String
) {

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

    // Tracks which directives are expanded (by hash key). Default state is collapsed.
    private val expandedDirectiveHashes = mutableSetOf<String>()

    // Staged once cache entries from computeDirectiveResults, persisted during save.
    private var pendingOnceCacheEntries: Map<String, Map<String, Any?>>? = null
    private var pendingOnceCacheNoteId: String? = null

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

    // Bumped after directive cache or expanded state changes, triggering recomposition
    // so the synchronous computeDirectiveResults() picks up new results/state.
    private val _directiveCacheGeneration = MutableLiveData(0)
    val directiveCacheGeneration: LiveData<Int> = _directiveCacheGeneration

    // Button execution states - maps directive key to execution state
    private val _buttonExecutionStates = MutableLiveData<Map<String, ButtonExecutionState>>(emptyMap())
    val buttonExecutionStates: LiveData<Map<String, ButtonExecutionState>> = _buttonExecutionStates

    // Button error messages - maps directive key to error message
    private val _buttonErrors = MutableLiveData<Map<String, String>>(emptyMap())
    val buttonErrors: LiveData<Map<String, String>> = _buttonErrors

    // Save warning (e.g., directive result save failures)
    private val _saveWarning = MutableLiveData<String?>()
    val saveWarning: LiveData<String?> = _saveWarning

    // Emits when notes cache is refreshed (e.g., after save) so UI can refresh view directives
    private val _notesCacheRefreshed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notesCacheRefreshed: SharedFlow<Unit> = _notesCacheRefreshed.asSharedFlow()

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
     * Invalidate the notes cache so view directives get fresh content.
     * Call this when switching tabs after saving to ensure views show updated data.
     */
    fun invalidateNotesCache() {
        // NoteStore is always up to date via its collection listener.
        // Only invalidate the derivative caches.
        MetadataHasher.invalidateCache()
        directiveCacheManager.clearAll()
    }

    /**
     * Bumps the directive cache generation counter, triggering recomposition
     * so computeDirectiveResults() re-runs with current cache state.
     */
    fun bumpDirectiveCacheGeneration() {
        _directiveCacheGeneration.value = (_directiveCacheGeneration.value ?: 0) + 1
    }

    /**
     * Invalidate directives affected by the given note changes and trigger recomposition.
     *
     * Two invalidation mechanisms work together:
     * - [invalidateForChangedNotes]: eagerly clears cache entries for the changed notes themselves
     * - [bumpDirectiveCacheGeneration]: forces [computeDirectiveResults] to re-run, where the
     *   StalenessChecker detects cross-note staleness (e.g., note A's [view B] directive
     *   is stale because note B changed). This lazy validation handles dependencies that
     *   per-note clearing cannot reach.
     *
     * The generation bump MUST be unconditional — do not gate it on whether
     * invalidateForChangedNotes found entries to clear. The per-note clear only handles
     * direct entries; cross-note dependencies rely on the staleness check at lookup time,
     * which only runs when computeDirectiveResults re-executes via the generation bump.
     */
    fun invalidateDirectivesForChangedNotes(changedNoteIds: Set<String>) {
        cachedDirectiveExecutor.invalidateForChangedNotes(changedNoteIds)
        bumpDirectiveCacheGeneration()
    }

    /**
     * Force re-execute all directives with fresh data from Firestore.
     * Used after inline editing a viewed note to refresh the view.
     *
     * Refreshes notes cache, clears directive executor cache, and bumps
     * cache generation so computeDirectiveResults() re-runs with fresh data.
     */
    fun forceRefreshAllDirectives(onComplete: (() -> Unit)? = null) {
        scope.launch {
            ensureNotesLoaded()
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
        val savedNoteId = getCurrentNoteId()

        scope.launch {
            // Refresh notes cache (notes may have changed after save)
            val notes = ensureNotesLoaded()
            val currentNote = NoteStore.getNoteById(savedNoteId)

            // Notify observers that notes cache was refreshed (for view directive updates)
            _notesCacheRefreshed.tryEmit(Unit)

            // Execute all directives and collect mutations
            val allMutations = mutableListOf<NoteMutation>()
            for (line in content.lines()) {
                for (directive in DirectiveFinder.findDirectives(line)) {
                    // Once-directives are handled by computeDirectiveResults with
                    // per-line PersistableOnceCache — skip in the post-save path.
                    if (directive.sourceText.contains("once[")) continue

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
            if (savedNoteId == getCurrentNoteId()) {
                bumpDirectiveCacheGeneration()
            }
        }
    }

    /**
     * Called by the ViewModel after a successful save to execute and store directives.
     */
    fun onSaveCompleted(content: String) {
        // Flush staged once cache entries to Firestore (safe here — save already wrote to the doc)
        val entries = pendingOnceCacheEntries
        val noteId = pendingOnceCacheNoteId
        if (entries != null && noteId != null) {
            persistOnceCacheEntries(noteId, entries)
            pendingOnceCacheEntries = null
            pendingOnceCacheNoteId = null
        }
        executeAndStoreDirectives(content)
    }

    /**
     * Loads cached directive results from Firestore into the L1 cache,
     * then executes any directives that aren't cached. Called when note is loaded.
     *
     * Alarm directives are trivial pure functions — computeDirectiveResults handles
     * them synchronously, so no pre-population is needed.
     */
    internal suspend fun loadDirectiveResults(content: String, noteId: String = getCurrentNoteId()) {
        if (!DirectiveFinder.containsDirectives(content)) {
            if (noteId == getCurrentNoteId()) {
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
            // Once-directives are handled by computeDirectiveResults with per-line
            // PersistableOnceCache — don't prime or re-execute them here.
            if (sourceText.contains("once[")) continue

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
            if (noteId == getCurrentNoteId()) {
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
        if (noteId == getCurrentNoteId()) {
            _directiveCacheGeneration.postValue((_directiveCacheGeneration.value ?: 0) + 1)
        }
    }

    /**
     * Compute directive results synchronously using the CachedDirectiveExecutor.
     * Results are keyed by directiveHash(sourceText) — same key the rendering layer uses.
     * Cache hits return instantly; misses execute and cache for next call.
     *
     * Applies collapsed state from [expandedDirectiveHashes].
     * When notes haven't loaded yet, still returns alarm results (trivial pure functions).
     */
    fun computeDirectiveResults(
        content: String,
        noteId: String? = null,
        lineNoteIds: List<String?> = emptyList()
    ): Map<String, DirectiveResult> {
        val effectiveNoteId = noteId ?: getCurrentNoteId()
        val notes = NoteStore.notes.value.takeIf { it.isNotEmpty() }
        val currentNote = NoteStore.getNoteById(effectiveNoteId)
        val onceCache = currentNote?.let { PersistableOnceCache(it.onceCache) }
        val hashResults = mutableMapOf<String, DirectiveResult>()
        val lines = content.lines()
        for ((lineIndex, line) in lines.withIndex()) {
            val lineNoteId = lineNoteIds.getOrNull(lineIndex)
            for (directive in DirectiveFinder.findDirectives(line)) {
                val isOnceDirective = directive.sourceText.contains("once[")
                val key = onceAwareKey(directive.sourceText, lineNoteId)
                if (hashResults.containsKey(key)) continue

                val result = if (notes != null) {
                    if (isOnceDirective && onceCache != null) {
                        // Once-directives bypass the CachedDirectiveExecutor — the
                        // PersistableOnceCache handles per-line caching via Firestore.
                        DirectiveFinder.executeDirective(
                            sourceText = directive.sourceText,
                            notes = notes,
                            currentNote = currentNote,
                            noteOperations = noteOperations,
                            onceCache = onceCache,
                            lineNoteId = lineNoteId
                        ).result
                    } else {
                        cachedDirectiveExecutor.execute(
                            directive.sourceText, notes, currentNote, noteOperations
                        ).result
                    }
                } else {
                    // Notes not loaded yet — only alarm directives can be resolved
                    val alarmId = DirectiveSegment.Directive.alarmIdFromSource(directive.sourceText)
                        ?: DirectiveSegment.Directive.recurringAlarmIdFromSource(directive.sourceText)
                    if (alarmId != null) DirectiveResult.success(AlarmVal(alarmId)) else continue
                }

                val hash = DirectiveResult.hashDirective(directive.sourceText)
                val collapsed = hash !in expandedDirectiveHashes
                hashResults[key] = result.copy(collapsed = collapsed)
            }
        }

        // Stage new once cache entries for persistence during save.
        // Do NOT write to Firestore here — this runs during rendering, and a
        // Firestore write would trigger a snapshot that can cause the editor
        // to rebuild state and lose noteId tracking.
        if (onceCache != null && onceCache.hasNewEntries()) {
            pendingOnceCacheEntries = onceCache.newEntries()
            pendingOnceCacheNoteId = effectiveNoteId
        }

        return hashResults
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
        cachedDirectiveExecutor.clearCacheEntry(sourceText, getCurrentNoteId())
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
        cachedDirectiveExecutor.clearCacheEntry(sourceText, getCurrentNoteId())
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
        val capturedNoteId = getCurrentNoteId()
        scope.launch {
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

    /** Clear the button error for a specific directive */
    fun clearButtonError(directiveKey: String) {
        val current = _buttonErrors.value?.toMutableMap() ?: mutableMapOf()
        current.remove(directiveKey)
        _buttonErrors.value = current
    }

    fun clearSaveWarning() {
        _saveWarning.value = null
    }

    fun setSaveWarning(message: String) {
        _saveWarning.postValue(message)
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

        val capturedNoteId = getCurrentNoteId()
        scope.launch {
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
        val capturedNoteId = getCurrentNoteId()
        scope.launch {
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
            originatingNoteId = getCurrentNoteId()
        )
        Log.d(TAG, "Started inline edit session: editing $editedNoteId from ${getCurrentNoteId()}")
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
     * Saves an inline edit session using editor-tracked noteIds directly.
     * Sorts completed checkboxes to bottom first, then passes tracked lines
     * to [NoteRepository.saveNoteWithChildren] (same path as the main editor).
     *
     * Prefer this over [saveInlineNoteContent] when an [InlineEditSession] is
     * available, as it preserves noteId identity through edits.
     */
    fun saveInlineEditSession(
        session: InlineEditSession,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        session.controller.sortCompletedToBottom()
        val trackedLines = session.getTrackedLines()
        val newContent = trackedLines.joinToString("\n") { it.content }
        launchInlineSave(
            noteId = session.noteId,
            newContent = newContent,
            saveAction = { repository.saveNoteWithFullContent(session.noteId, newContent) },
            onSuccess = onSuccess,
            onFailure = onFailure,
        )
    }

    /**
     * Inline-session save that splices caller-supplied extra batch ops into
     * the same WriteBatch as the note write — used by alarm creation to land
     * the note + alarm doc atomically. Suspends so the caller can branch on
     * success/failure.
     */
    suspend fun saveInlineEditSessionWithExtras(
        session: InlineEditSession,
        extraOpsBuilder: NoteRepository.ExtraOpsBuilder,
    ): Result<Map<Int, String>> {
        session.controller.sortCompletedToBottom()
        val trackedLines = session.getTrackedLines()
        val newContent = trackedLines.joinToString("\n") { it.content }

        val existing = NoteStore.getNoteById(session.noteId)
        if (existing != null) {
            NoteStore.updateNote(session.noteId, existing.copy(content = newContent), persist = false)
        }

        if (!editSessionManager.isEditSessionActive() ||
            editSessionManager.getEditContext()?.editedNoteId != session.noteId) {
            startInlineEditSession(session.noteId)
        }

        val result = repository.saveNoteWithChildren(session.noteId, trackedLines, extraOpsBuilder)
        result.onSuccess {
            MetadataHasher.invalidateCache()
            directiveCacheManager.clearAll()
            endInlineEditSession()
        }.onFailure { e ->
            Log.e(TAG, "saveInlineEditSessionWithExtras failed for ${session.noteId}", e)
            editSessionManager.abortEditSession()
        }
        return result
    }

    /**
     * Synchronous (suspend) variant of [saveInlineEditSession] for use in coroutines
     * that need to await completion before proceeding (e.g., unified save).
     */
    suspend fun saveInlineEditSessionSync(session: InlineEditSession) {
        session.controller.sortCompletedToBottom()
        val trackedLines = session.getTrackedLines()
        val newContent = trackedLines.joinToString("\n") { it.content }

        // Optimistic NoteStore update
        val existing = NoteStore.getNoteById(session.noteId)
        if (existing != null) {
            NoteStore.updateNote(session.noteId, existing.copy(content = newContent), persist = false)
        }

        // Use saveNoteWithFullContent: the inline editor only has this note's
        // direct lines, not nested sub-trees from view directives.
        // saveNoteWithFullContent loads the existing tree structure and matches
        // the editor content against it, preserving grandchild relationships.
        repository.saveNoteWithFullContent(session.noteId, newContent).getOrThrow()
        MetadataHasher.invalidateCache()
    }

    /**
     * Saves the content of a note edited inline within a view directive.
     * Falls back to content-based matching when no [InlineEditSession] is available.
     *
     * Prefer [saveInlineEditSession] when an [InlineEditSession] is available,
     * as it uses editor-tracked noteIds and handles sorting.
     */
    fun saveInlineNoteContent(
        noteId: String,
        newContent: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        launchInlineSave(
            noteId = noteId,
            newContent = newContent,
            saveAction = { repository.saveNoteWithFullContent(noteId, newContent) },
            onSuccess = onSuccess,
            onFailure = onFailure,
        )
    }

    /**
     * Shared inline save implementation: optimistic update, ensure edit session,
     * run the save action, then invalidate caches on success or abort on failure.
     *
     * Do NOT bump cache generation on success — that would force immediate
     * recomposition of the directive line, recreating ControlledLineView composables
     * and invalidating the focused line's IME InputConnection.
     */
    private fun launchInlineSave(
        noteId: String,
        newContent: String,
        saveAction: suspend () -> Result<*>,
        onSuccess: (() -> Unit)?,
        onFailure: ((Throwable) -> Unit)?,
    ) {
        // Optimistic update so tab-switching shows the edit before Firestore confirms
        val existing = NoteStore.getNoteById(noteId)
        if (existing != null) {
            NoteStore.updateNote(noteId, existing.copy(content = newContent), persist = false)
        }

        scope.launch {
            // Only start a new edit session if one isn't already active for this note.
            // Re-starting triggers a session switch (end + start) which fires the
            // session-end listener → bumpDirectiveCacheGeneration → directive re-execution
            // → session recreation → IME invalidation → focused line cleared.
            if (!editSessionManager.isEditSessionActive() ||
                editSessionManager.getEditContext()?.editedNoteId != noteId) {
                startInlineEditSession(noteId)
            }

            saveAction()
                .onSuccess {
                    MetadataHasher.invalidateCache()
                    directiveCacheManager.clearAll()
                    endInlineEditSession()
                    onSuccess?.invoke()
                }
                .onFailure { e ->
                    Log.e(TAG, "=== launchInlineSave FAILED for $noteId ===", e)
                    editSessionManager.abortEditSession()
                    onFailure?.invoke(e)
                }
        }
    }

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
            if (mutation.noteId == getCurrentNoteId()) {
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
    private fun findRefreshExpr(expr: Expression): RefreshExpr? {
        return when (expr) {
            is RefreshExpr -> expr
            is StatementList -> {
                expr.statements.asSequence()
                    .mapNotNull { findRefreshExpr(it) }
                    .firstOrNull()
            }
            is Assignment -> findRefreshExpr(expr.value)
            else -> null
        }
    }

    /**
     * Persist new once cache entries to Firestore as a merge update on the note document.
     * Fires asynchronously — does not block the calling thread.
     */
    private fun persistOnceCacheEntries(noteId: String, newEntries: Map<String, Map<String, Any?>>) {
        if (newEntries.isEmpty()) return
        scope.launch {
            try {
                val updates = newEntries.mapKeys { (key, _) -> "onceCache.$key" }
                FirebaseFirestore.getInstance()
                    .collection("notes")
                    .document(noteId)
                    .update(updates)
                    .await()
                Log.d(TAG, "Persisted ${newEntries.size} once cache entries for note $noteId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist once cache entries", e)
            }
        }
    }

    private suspend fun ensureNotesLoaded(): List<Note> {
        NoteStore.ensureLoaded()
        return NoteStore.notes.value
    }

    // region Lifecycle

    fun start() {
        refreshScheduler.start()
    }

    fun stop() {
        refreshScheduler.stop()
    }

    fun addSessionEndListener(listener: () -> Unit) {
        editSessionManager.addSessionEndListener(listener)
    }

    fun clearExpandedState() {
        expandedDirectiveHashes.clear()
    }

    // endregion

    companion object {
        private const val TAG = "NoteDirectiveManager"
    }
}
