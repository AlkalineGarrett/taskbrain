package org.alkaline.taskbrain.data

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Singleton reactive store for all notes with reconstructed content.
 * Sync architecture: see `docs/live-cross-platform-sync.md`.
 * - [rawNotes]: every Firestore doc indexed by id
 * - [_notes]: top-level notes with content reconstructed from their tree
 */
object NoteStore {
    private const val TAG = "NoteStore"

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    /** IDs of notes whose content changed in the most recent incremental snapshot. */
    private val _changedNoteIds = MutableSharedFlow<Set<String>>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val changedNoteIds: SharedFlow<Set<String>> = _changedNoteIds

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /**
     * Raise a user-visible warning from anywhere in the data layer. The
     * ViewModel's `NoteStore.error` collector routes it to
     * `directiveManager.setSaveWarning` which pops the warning dialog —
     * same channel the reconstruction errors used to flow through.
     */
    fun raiseWarning(message: String) {
        _error.value = message
    }

    /**
     * IDs of top-level notes whose reconstruction had to auto-heal a
     * discrepancy (orphan ref dropped or stray child appended). The editor
     * shows the healed content; saving a note in this set writes the fix
     * back to Firestore and removes it from the set on the next snapshot.
     */
    private val _notesNeedingFix = MutableStateFlow<Set<String>>(emptySet())
    val notesNeedingFix: StateFlow<Set<String>> = _notesNeedingFix.asStateFlow()

    /** All notes from Firestore, indexed by ID (including descendants). */
    private val rawNotes = mutableMapOf<String, Note>()

    /**
     * Guards [rawNotes] against data races between the Firestore listener
     * (main thread) and save operations (IO dispatcher). Reads use the read
     * lock for concurrent throughput; snapshot handlers use the write lock.
     */
    private val rawNotesLock = ReentrantReadWriteLock()

    private var userDocListener: ListenerRegistration? = null
    private var loaded = false
    private var loadDeferred: CompletableDeferred<Unit>? = null

    /** Captured by [start] for use by [pullDelta] / detection / [clear]. */
    private var db: FirebaseFirestore? = null
    private var userId: String? = null

    private var lastObservedSignal: Timestamp? = null
    private var detectionRanThisSession = false
    private var foregroundObserver: LifecycleEventObserver? = null

    /** Pulls run off any view-bound scope so Activity recreation doesn't
     *  cancel an in-flight sync. Recreated by [start] after [clear]. */
    private var pullScope: CoroutineScope = newPullScope()
    private val pullMutex = Mutex()

    /** Overlap on the watermark — covers clock skew and same-timestamp ties. */
    private const val PULL_OVERLAP_SECONDS = 5L

    private fun newPullScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-flight saves keyed by noteId, so loaders can await before reading. */
    private val pendingSaves = mutableMapOf<String, Deferred<Unit>>()

    /**
     * Serializes all save operations so concurrent saves of different notes
     * (e.g., A's autosave on tab switch racing B's autosave after a cross-note
     * paste) run sequentially rather than overlapping on shared state. See
     * [enqueueSave] for rationale.
     */
    private val saveQueueMutex = Mutex()

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
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private const val PERSIST_DEBOUNCE_MS = 500L
    /** Cap for waiting on the listener to surface a specific note. */
    private const val NOTE_STORE_AWAIT_MS = 1500L

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
        pendingPersistRunnables.remove(noteId)?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingPersistRunnables.remove(noteId)
            val savedContent = pendingPersistContent.remove(noteId) ?: return@Runnable
            persistCallback?.invoke(noteId, savedContent)
        }
        pendingPersistRunnables[noteId] = runnable
        mainHandler.postDelayed(runnable, PERSIST_DEBOUNCE_MS)
    }

    /**
     * Idempotent. Attaches the [users/{uid}](#) snapshot listener, fires the
     * initial delta pull, and registers the foreground observer. Re-calling
     * after a successful start is a no-op.
     */
    fun start(db: FirebaseFirestore, userId: String) {
        if (userDocListener != null) return

        this.db = db
        this.userId = userId

        if (loadDeferred == null) {
            loadDeferred = CompletableDeferred()
        }

        userDocListener = db.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "user-doc signal listener error", error)
                    _error.value = error.message ?: "Note sync failed"
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.metadata.hasPendingWrites()) {
                    FirestoreUsage.recordRead(
                        "NoteStore.signalListener",
                        FirestoreUsage.ReadType.LISTENER_LOCAL_ECHO,
                        1,
                    )
                    return@addSnapshotListener
                }
                val fromCache = snapshot.metadata.isFromCache
                FirestoreUsage.recordRead(
                    "NoteStore.signalListener",
                    if (fromCache) FirestoreUsage.ReadType.LISTENER_UPDATE_CACHED
                    else FirestoreUsage.ReadType.LISTENER_UPDATE_FRESH,
                    1,
                )
                val newSignal = snapshot.getTimestamp("lastNoteChange")
                if (newSignal == lastObservedSignal) return@addSnapshotListener
                lastObservedSignal = newSignal
                pullScope.launch { pullDelta("signal") }
            }

        pullScope.launch { pullDelta("attach") }

        // Foreground observer: every ON_START re-pulls; detection runs
        // once-per-process to catch hard-delete divergence.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                pullScope.launch {
                    pullDelta("foreground")
                    runForegroundDetectionOnce()
                }
            }
        }
        foregroundObserver = observer
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        } catch (e: Throwable) {
            // ProcessLifecycleOwner.get() throws when called outside an
            // Android process (e.g., JVM unit tests). Pulls still fire via
            // the signal listener and `attach`; only foreground triggers
            // are missing in that environment.
            Log.w(TAG, "ProcessLifecycleOwner observer add failed; foreground triggers disabled", e)
            foregroundObserver = null
        }
    }

    /** Serialized through [pullMutex] so attach + signal don't double-pull. */
    private suspend fun pullDelta(reason: String) = pullMutex.withLock {
        val db = this.db ?: return@withLock
        val uid = this.userId ?: return@withLock
        val watermark = LastSyncStorage.read(uid)
        val isFullPull = watermark.seconds == 0L && watermark.nanoseconds == 0
        val baseQuery = db.collection("notes").whereEqualTo("userId", uid)
        val query: Query = if (isFullPull) {
            baseQuery
        } else {
            baseQuery
                .whereGreaterThan("updatedAt", overlapBuffered(watermark))
                .orderBy("updatedAt")
        }
        val snapshot = try {
            query.get().await()
        } catch (e: Exception) {
            Log.e(TAG, "pullDelta(reason=$reason, full=$isFullPull) failed", e)
            _error.value = e.message ?: "Note sync failed"
            return@withLock
        }
        val readType = if (isFullPull) FirestoreUsage.ReadType.PULL_FULL_REPAIR
        else FirestoreUsage.ReadType.PULL_DELTA
        FirestoreUsage.recordRead("NoteStore.pull.$reason", readType, snapshot.size())
        val notes = snapshot.documents.mapNotNull { parseNote(it) }
        if (isFullPull) {
            applyFullPull(notes)
        } else {
            applyDelta(notes)
        }
        // Advance lastSync to max(updatedAt) of returned docs. Persist only
        // after a successful apply so a transient failure can't push the
        // watermark past data we haven't actually durably reflected.
        val maxUpdatedAt = notes.maxByOrNull { note ->
            note.updatedAt?.let { ts -> ts.seconds * 1_000_000_000L + ts.nanoseconds } ?: 0L
        }?.updatedAt
        if (maxUpdatedAt != null) {
            LastSyncStorage.write(uid, maxUpdatedAt)
        }
    }

    private fun overlapBuffered(watermark: Timestamp): Timestamp {
        val seconds = watermark.seconds - PULL_OVERLAP_SECONDS
        return if (seconds < 0) Timestamp(0, 0) else Timestamp(seconds, watermark.nanoseconds)
    }

    private fun applyFullPull(notes: List<Note>) {
        rawNotesLock.write {
            rawNotes.clear()
            for (note in notes) rawNotes[note.id] = note
            rebuildAll()
        }
        markLoaded()
    }

    private fun applyDelta(notes: List<Note>) {
        if (notes.isEmpty()) {
            // Even an empty delta means the initial pull completed — flag
            // loaded so editors waiting on ensureLoaded() can proceed.
            markLoaded()
            return
        }
        val affectedRoots = mutableSetOf<String>()
        rawNotesLock.write {
            for (note in notes) {
                rawNotes[note.id] = note
                affectedRoots.add(note.rootNoteId ?: note.id)
            }
            rebuildAffected(affectedRoots)
        }
        markLoaded()
        if (affectedRoots.isNotEmpty()) {
            _changedNoteIds.tryEmit(affectedRoots)
        }
    }

    private fun markLoaded() {
        if (loaded) return
        loaded = true
        loadDeferred?.complete(Unit)
        loadDeferred = null
    }

    /**
     * One count() aggregation vs `rawNotes.size`; mismatch triggers a full
     * repair pull and a user-visible warning. Gated to once per process —
     * cheap if a single foreground per app lifetime is enough to catch
     * divergence, expensive (more reads) if run on every foreground.
     */
    private suspend fun runForegroundDetectionOnce() {
        if (detectionRanThisSession) return
        if (!loaded) return // nothing to compare against yet
        detectionRanThisSession = true
        val db = this.db ?: return
        val uid = this.userId ?: return
        val serverCount = try {
            db.collection("notes").whereEqualTo("userId", uid).count()
                .get(AggregateSource.SERVER).await().count
        } catch (e: Exception) {
            Log.e(TAG, "detection count() failed", e)
            detectionRanThisSession = false // allow retry on next foreground
            return
        }
        FirestoreUsage.recordRead(
            "NoteStore.detection",
            FirestoreUsage.ReadType.COUNT_AGGREGATION,
            1,
        )
        val localCount = rawNotesLock.read { rawNotes.size }.toLong()
        if (serverCount == localCount) {
            Log.d(TAG, "detection: count match ($localCount); no repair needed")
            return
        }
        Log.w(
            TAG,
            "detection: count mismatch (local=$localCount, server=$serverCount); " +
                "triggering full repair pull",
        )
        raiseWarning(
            "Note sync inconsistency: local has $localCount notes, server has $serverCount. " +
                "Re-syncing now.",
        )
        // Drop the watermark so the next pull is a full one.
        LastSyncStorage.clear(uid)
        pullDelta("repair")
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

    /** Whether the first Firestore snapshot has arrived. */
    fun isLoaded(): Boolean = loaded

    /** Stop listening and clear all data (e.g., on logout). */
    fun clear() {
        userDocListener?.remove()
        userDocListener = null
        foregroundObserver?.let { obs ->
            try {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
            } catch (e: Throwable) {
                // Unit test environment; observer was never registered.
            }
        }
        foregroundObserver = null
        pullScope.cancel()
        pullScope = newPullScope()
        rawNotesLock.write { rawNotes.clear() }
        pendingPersistContent.clear()
        pendingPersistRunnables.clear()
        mainHandler.removeCallbacksAndMessages(null)
        // persistCallback is NOT cleared: it's set once in CurrentNoteViewModel.init
        // (which doesn't re-run on sign-out → sign-in within the same Activity), and
        // the callback dispatches via FirebaseAuth.currentUser at fire time, so it
        // works correctly under the new user. Nulling it here silently breaks saves
        // after re-login.
        _notes.value = emptyList()
        _error.value = null
        _notesNeedingFix.value = emptySet()
        loaded = false
        loadDeferred = null
        lastObservedSignal = null
        detectionRanThisSession = false
        db = null
        userId = null
    }

    /** Clear a note from [notesNeedingFix] (e.g., after a successful save). */
    fun markNoteFixed(noteId: String) {
        val current = _notesNeedingFix.value
        if (noteId !in current) return
        _notesNeedingFix.value = current - noteId
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
            _notes.value = current.toMutableList().apply { set(index, updatedNote) }
            if (persist) {
                debouncePersist(noteId, updatedNote.content)
            }
        }
    }

    /**
     * Optimistic content-only update for save paths. Skips when the in-memory
     * content already matches so listeners don't recompose for no-op writes.
     * Always [persist]=false — the caller drives the structured save.
     */
    fun updateContentIfChanged(noteId: String, content: String) {
        val existing = getNoteById(noteId) ?: return
        if (existing.content == content) return
        updateNote(noteId, existing.copy(content = content), persist = false)
    }

    /**
     * `containedNotes` snapshot for [rootNoteId] and every live descendant
     * under it, keyed by id. Editors capture this at edit-session start so
     * the 3-way merge in [NoteRepository.planSaveNoteWithChildren] runs
     * uniformly at every depth (concurrent additions another client made
     * under any descendant survive, not just additions to the root). Returns
     * an empty Map if the root isn't loaded yet.
     */
    fun snapshotLocalBases(rootNoteId: String): Map<String, List<String>> = rawNotesLock.read {
        val result = HashMap<String, List<String>>()
        rawNotes[rootNoteId]?.let { result[rootNoteId] = it.containedNotes.toList() }
        for (id in descendantIdsOf(rootNoteId, rawNotes)) {
            rawNotes[id]?.let { result[id] = it.containedNotes.toList() }
        }
        result
    }

    /** Mirrors web NoteStore.pendingCuts — see that file for full rationale. */
    private val pendingCuts = ConcurrentHashMap<String, String>()

    /** Add a cut line's id + content to the reclaim buffer. */
    fun recordCut(lineId: String, content: String) {
        pendingCuts[lineId] = content
    }

    /**
     * Find a pendingCut with matching content; remove it from the buffer and
     * return its lineId. Returns `null` on miss (the paste then falls back
     * to sentinel allocation). Removal is one-shot so duplicate-content
     * paste doesn't double-claim a single cut line.
     */
    fun tryReclaim(content: String): String? {
        for ((lineId, c) in pendingCuts) {
            if (c == content) {
                pendingCuts.remove(lineId)
                return lineId
            }
        }
        return null
    }

    /** Snapshot of pending cuts for save planning. */
    fun getPendingCuts(): Map<String, String> = pendingCuts.toMap()

    /** Drop a single entry — used after the cut-delete write commits. */
    fun clearPendingCut(lineId: String) {
        pendingCuts.remove(lineId)
    }

    /** Test-only hatch — singleton state leaks across tests. */
    @androidx.annotation.VisibleForTesting
    fun clearPendingCutsForTest() {
        pendingCuts.clear()
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

    /** Get a raw (unreconstructed) note by ID — includes notes filtered from the top-level list. */
    fun getRawNoteById(noteId: String): Note? = rawNotesLock.read { rawNotes[noteId] }

    /** Find a note by its path from in-memory data. */
    fun getNoteByPath(path: String): Note? = rawNotesLock.read {
        rawNotes.values.find { it.path == path && isLive(it.state) }
    }

    /** IDs of all non-deleted descendants of [noteId] from in-memory state. */
    fun getDescendantIds(noteId: String): Set<String> = rawNotesLock.read {
        descendantIdsOf(noteId, rawNotes)
    }

    /** IDs of all descendants of [noteId] including deleted ones. */
    fun getAllDescendantIds(noteId: String): Set<String> = rawNotesLock.read {
        descendantIdsOf(noteId, rawNotes, includeDeleted = true)
    }

    /**
     * Live descendants of [rootNoteId] grouped by parent id. Used by the
     * save-side null-id recovery to look up candidate matches in O(1).
     * Mirrors the web [NoteStore.getLiveDescendantsByParent].
     */
    fun getLiveDescendantsByParent(rootNoteId: String): Map<String, ArrayDeque<Note>> = rawNotesLock.read {
        val byParent = HashMap<String, ArrayDeque<Note>>()
        for (id in descendantIdsOf(rootNoteId, rawNotes)) {
            val d = rawNotes[id] ?: continue
            val p = d.parentNoteId ?: continue
            byParent.getOrPut(p) { ArrayDeque() }.addLast(d)
        }
        byParent
    }

    /** In-memory note by ID, falling back to Firestore if not cached. */
    suspend fun getNoteOrLoad(noteId: String, repository: NoteRepository): Note? =
        getRawNoteById(noteId) ?: repository.loadNoteById(noteId).getOrNull()

    /** In-memory top-level notes, falling back to Firestore if empty. */
    suspend fun getNotesOrLoad(repository: NoteRepository): List<Note> =
        notes.value.ifEmpty { repository.loadAllUserNotes().getOrNull() ?: emptyList() }

    /**
     * Returns flattened note lines with proper noteId mappings from the in-memory tree.
     * Uses containedNotes arrays and rawNotes to reconstruct the same output as
     * NoteRepository.loadNoteWithChildren, but without a Firestore round-trip.
     * Returns null if the note or its descendants aren't loaded yet.
     */
    fun getNoteLinesById(noteId: String): List<NoteLine>? {
        val (lines, fixed) = rawNotesLock.read {
            val rootNote = rawNotes[noteId] ?: return null
            // When the note itself is deleted, the strays loop in
            // reconstruction needs DELETED children whose deletionBatchId
            // matches the parent's — they were part of the same delete.
            val includeBatch = if (!isLive(rootNote.state)) rootNote.deletionBatchId else null
            val childrenByParent = indexChildrenByParent(rawNotes, includeBatch)
            reconstructNoteLines(rootNote, rawNotes, childrenByParent)
        }
        // Keep the editor view in sync with rebuildAffected: if the shared walk
        // dropped a declared child (missing from rawNotes — typically a fresh
        // save whose descendant echo hasn't arrived) mark the note as needing a
        // fix so the save button flips to the warning state.
        if (fixed && noteId !in _notesNeedingFix.value) {
            _notesNeedingFix.value = _notesNeedingFix.value + noteId
        }
        return lines
    }

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

    /**
     * Suspend until [noteId] is loaded into [rawNotes] or [timeoutMs] elapses.
     * Editors call this at session start so the listener has a chance to
     * deliver the doc before we fall through to a Firestore one-shot read.
     */
    suspend fun awaitNoteLoaded(noteId: String, timeoutMs: Long = NOTE_STORE_AWAIT_MS): Boolean {
        if (getRawNoteById(noteId) != null) return true
        awaitPendingSave(noteId)
        if (getRawNoteById(noteId) != null) return true
        return withTimeoutOrNull(timeoutMs) {
            changedNoteIds.first { ids -> noteId in ids && getRawNoteById(noteId) != null }
            true
        } ?: false
    }

    /**
     * Run [operation] after every previously enqueued save has settled.
     *
     * Cross-note operations (cut/paste, move) leave shared docs in flight
     * between two trees. Two concurrent `saveNoteWithChildren` transactions
     * computing `existingDescendantIds` / `toDelete` / `assertNotContentDrop`
     * from the same NoteStore snapshot can clobber each other — e.g., A's
     * save soft-deletes the moved doc after B has already reparented it.
     * Serializing through this queue means each save reads NoteStore after
     * the prior save's write has committed (and Firestore's local cache
     * reflects it), so the second save sees the up-to-date tree.
     *
     * A failure in one operation does not block subsequent operations —
     * [Mutex.withLock] releases the lock on either return or exception.
     */
    suspend fun <T> enqueueSave(operation: suspend () -> T): T =
        saveQueueMutex.withLock { operation() }

    // --- Internal reconstruction ---
    // Callers must hold rawNotesLock (write).

    private fun rebuildAll() {
        val result = rebuildAllNotes(rawNotes)
        _notes.value = result.notes
        _notesNeedingFix.value = result.notesNeedingFix
    }

    private fun rebuildAffected(rootIds: Set<String>) {
        val previous = _notes.value
        val result = rebuildAffectedNotes(previous, rootIds, rawNotes)
        val defended = preservePartialReconstructions(previous, result, rootIds)
        if (defended.notes !== _notes.value) {
            _notes.value = defended.notes
        }
        mergeNotesNeedingFix(rootIds, defended.notesNeedingFix)
    }

    /**
     * Guard against partial-snapshot windows where descendants haven't arrived
     * in [rawNotes] yet. If reconstruction would shrink a note from many lines
     * to a near-empty single line, the descendants are likely in flight: keep
     * the previous reconstructed entry unchanged and mark it [notesNeedingFix]
     * so the UI surfaces the inconsistency without wiping the editor.
     *
     * Symptoms we protect against: editor loads the emptied content, user
     * autosaves on tab switch, and the content-drop guard blocks the save.
     */
    private fun preservePartialReconstructions(
        previous: List<Note>,
        result: RebuildResult,
        affectedRootIds: Set<String>,
    ): RebuildResult {
        if (result.notes === previous) return result

        var defendedNotes: MutableList<Note>? = null
        val needsFix = result.notesNeedingFix.toMutableSet()

        for (rootId in affectedRootIds) {
            val prev = previous.find { it.id == rootId } ?: continue
            val next = result.notes.find { it.id == rootId } ?: continue
            if (prev === next) continue

            val prevLineCount = prev.content.lines().size
            val nextLineCount = next.content.lines().size
            // Only defend when we'd drop from a meaningful tree to ≤1 line,
            // AND rootNoteId-indexed descendants still exist in rawNotes (the
            // children are loaded but parentNoteId walking didn't find them,
            // which points to a partial-sync window).
            val suspicious = prevLineCount >= 3 && nextLineCount <= 1 &&
                rawNotes.values.any { it.rootNoteId == rootId && isLive(it.state) }
            if (!suspicious) continue

            Log.w(
                TAG,
                "preservePartialReconstructions: keeping previous content for $rootId " +
                    "(prev=$prevLineCount lines, new=$nextLineCount). " +
                    "rootNoteId descendants present but parentNoteId walk found none — " +
                    "likely partial Firestore sync."
            )

            val list = defendedNotes ?: result.notes.toMutableList().also { defendedNotes = it }
            val idx = list.indexOfFirst { it.id == rootId }
            if (idx >= 0) list[idx] = prev
            needsFix.add(rootId)
        }

        return RebuildResult(
            notes = defendedNotes ?: result.notes,
            notesNeedingFix = needsFix,
        )
    }

    /**
     * For the incremental path only: each rebuild sees only the affected roots,
     * so we merge — clear needsFix for any affected root that now reconstructs
     * cleanly, and add any that newly need fixing.
     */
    private fun mergeNotesNeedingFix(affectedRootIds: Set<String>, stillNeedFix: Set<String>) {
        val current = _notesNeedingFix.value
        val updated = (current - affectedRootIds) + stillNeedFix
        if (updated != current) {
            _notesNeedingFix.value = updated
        }
    }

    // --- Parsing ---

    /**
     * Thrown when a code path that depends on a fully-loaded NoteStore runs
     * before the live listener has received its first snapshot. Save/delete
     * operations read the descendant set from NoteStore — running them before
     * the snapshot has arrived would silently miss soft-deleting removed
     * descendants, leaving orphaned documents in Firestore.
     *
     * The Throwable superclass auto-captures the stack, so [Log.e] on the
     * caller side prints the full call chain.
     */
    class NoteStoreNotLoadedException(
        val operation: String,
        val noteId: String,
    ) : IllegalStateException(
        "[NoteStore not loaded] $operation(noteId=$noteId) ran before the " +
            "live note listener received its first snapshot. " +
            "Save/delete operations read descendants from the in-memory NoteStore; " +
            "running them now would silently miss soft-deleting removed descendants, " +
            "leaving orphaned documents. Try again after the note list has loaded."
    )

    @Suppress("UNCHECKED_CAST")
    /**
     * Snapshot doc → [Note]. Delegates to Firestore's POJO mapper so new
     * fields on [Note] are picked up automatically via reflection on the
     * data class — no parallel hand-rolled parser to keep in sync.
     *
     * History: this used to be a manual `Map<String, Any>` extractor. Adding
     * `deletionBatchId` exposed the maintenance hazard — the manual parser
     * silently dropped the new field, breaking the deleted-parent
     * reconstruction path. The mapper here calls the same code that
     * `NoteRepository.toObject(Note::class.java)` already used elsewhere.
     */
    private fun parseNote(doc: DocumentSnapshot): Note? {
        return try {
            doc.toObject(Note::class.java)?.copy(id = doc.id)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing note ${doc.id}", e)
            _error.value = "Failed to parse note ${doc.id}: ${e.message}"
            null
        }
    }
}
