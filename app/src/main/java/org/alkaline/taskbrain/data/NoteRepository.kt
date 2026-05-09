package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Repository for managing composable notes in Firestore.
 *
 * Notes form a tree: parentNoteId points to immediate parent, rootNoteId enables
 * single-query loading of all descendants. Indentation is derived from tree depth
 * (no tabs stored in Firestore content).
 */
class NoteRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val notesCollection get() = db.collection("notes")

    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    /**
     * Wraps an IO-bound operation in `runCatching` + `withContext(IO)` and logs
     * any failure with the given operation name. Mirrors the web NoteRepository's
     * `logged()` helper.
     */
    private suspend fun <T> ioLogged(
        op: String,
        block: suspend CoroutineScope.() -> T,
    ): Result<T> = runCatching {
        withContext(Dispatchers.IO, block)
    }.onFailure { Log.e(TAG, "$op failed", it) }

    private fun noteRef(noteId: String): DocumentReference = notesCollection.document(noteId)

    private fun newNoteRef(): DocumentReference = notesCollection.document()

    /**
     * Hard guard for ops that read descendants from NoteStore. Throws a
     * [NoteStore.NoteStoreNotLoadedException] (Throwable auto-captures the
     * stack) when violated; [ioLogged] then logs the failure with `Log.e` and
     * the message propagates to the caller via [Result.failure] — the
     * ViewModel's [UnifiedSaveStatus.PartialError] / [LoadStatus.Error] paths
     * surface it to the user.
     */
    private fun assertNoteStoreLoaded(operation: String, noteId: String) {
        if (NoteStore.isLoaded()) return
        throw NoteStore.NoteStoreNotLoadedException(operation, noteId)
    }

    /**
     * Soft guard: detect the brief race window where a note's [containedNotes]
     * declares children whose docs haven't arrived in the listener's snapshot
     * yet. Save would proceed with an incomplete descendant set, so we warn the
     * user (banner via [NoteStore.raiseWarning]) and log a full diagnostic with
     * stack to logcat for debugging.
     */
    private fun warnIfDescendantsLikelyStale(operation: String, noteId: String) {
        val rawNote = NoteStore.getRawNoteById(noteId) ?: return
        val declared = rawNote.containedNotes
        if (declared.isEmpty()) return

        val missing = declared.filter { NoteStore.getRawNoteById(it) == null }
        if (missing.isEmpty()) return

        val sample = missing.take(5).joinToString(", ")
        val ellipsis = if (missing.size > 5) ", ... (+${missing.size - 5} more)" else ""
        val stack = Throwable("warnIfDescendantsLikelyStale callsite").stackTraceToString()
        Log.w(
            TAG,
            "[NoteStore stale] $operation(noteId=$noteId): note declares " +
                "${declared.size} child note(s) but ${missing.size} are not in " +
                "the local store yet: [$sample$ellipsis]. The descendant set used " +
                "for soft-delete tracking may be incomplete; if any of these were " +
                "removed in this save, their old docs will remain active until " +
                "the next save after the listener catches up.\n$stack"
        )
        NoteStore.raiseWarning(
            "Note has ${missing.size} child note(s) not yet visible in the local " +
                "store; recent edits may not be fully synced. " +
                "Check logcat tag '$TAG' for full diagnostic."
        )
    }

    private fun baseNoteData(userId: String, content: String) = hashMapOf(
        "userId" to userId,
        "content" to content,
        "updatedAt" to FieldValue.serverTimestamp()
    )

    private fun newNoteData(userId: String, content: String, parentNoteId: String? = null): HashMap<String, Any?> =
        hashMapOf(
            "userId" to userId,
            "content" to content,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "parentNoteId" to parentNoteId
        )

    // ── Load operations ─────────────────────────────────────────────────

    data class NoteLoadResult(
        val lines: List<NoteLine>,
        val isDeleted: Boolean,
        val showCompleted: Boolean,
    )

    /**
     * Loads a note and its descendants, returning lines plus note metadata.
     *
     * Prefers the in-memory NoteStore when loaded — the live listener already
     * holds every note for the user, so the parent + descendant Firestore
     * reads are redundant. Falls back to Firestore only when the listener
     * hasn't synced yet or doesn't contain this note (e.g., immediately after
     * createNote or a deep-link to a brand-new doc).
     */
    /**
     * Editor session-init entry point. Awaits the listener for [noteId]; on
     * timeout, falls back to a one-shot Firestore read. Throws on hard
     * failure. Replaces the deleted synth-on-miss path so sessions only
     * ever start from structurally-valid lines.
     */
    suspend fun loadNoteLinesAwait(noteId: String): Result<List<NoteLine>> = ioLogged("loadNoteLinesAwait") {
        if (NoteStore.awaitNoteLoaded(noteId)) {
            NoteStore.getNoteLinesById(noteId)
                ?: error("NoteStore awaitNoteLoaded($noteId) returned true but getNoteLinesById was null")
        } else {
            Log.w(TAG, "awaitNoteLoaded($noteId) timed out — falling back to Firestore read")
            loadNoteWithChildren(noteId).getOrThrow().lines
        }
    }

    suspend fun loadNoteWithChildren(noteId: String): Result<NoteLoadResult> = ioLogged("loadNoteWithChildren") {
        requireUserId()
        val emptyResult = NoteLoadResult(listOf(NoteLine("", noteId)), isDeleted = false, showCompleted = true)

        // Cold start: the snapshot listener is started in the VM init but the
        // first cached snapshot hasn't landed yet. Wait briefly so the in-memory
        // path can serve this load instead of issuing a parallel Firestore fetch
        // for data the listener is about to deliver.
        if (!NoteStore.isLoaded()) {
            withTimeoutOrNull(NOTE_STORE_AWAIT_MS) { NoteStore.ensureLoaded() }
        }

        if (NoteStore.isLoaded()) {
            val rawNote = NoteStore.getRawNoteById(noteId)
            val storeLines = if (rawNote != null) NoteStore.getNoteLinesById(noteId) else null
            if (rawNote != null && storeLines != null) {
                return@ioLogged NoteLoadResult(
                    lines = storeLines,
                    isDeleted = rawNote.state == NoteState.DELETED,
                    showCompleted = rawNote.showCompleted,
                )
            }
            // Invariant: getRawNoteById and getNoteLinesById should agree on
            // existence — both look at rawNotes. A mismatch points at a race
            // (note removed mid-call) or a reconstruction bug.
            if (rawNote != null && storeLines == null) {
                val stack = Throwable("loadNoteWithChildren NoteStore mismatch").stackTraceToString()
                Log.w(
                    TAG,
                    "[NoteStore inconsistency] loadNoteWithChildren(noteId=$noteId): " +
                        "getRawNoteById returned a note but getNoteLinesById returned null. " +
                        "Falling back to Firestore. State: parentNoteId=${rawNote.parentNoteId}, " +
                        "rootNoteId=${rawNote.rootNoteId}, state=${rawNote.state}, " +
                        "containedNotes=${rawNote.containedNotes.size}.\n$stack"
                )
            }
        }

        val document = noteRef(noteId).get().await()
        FirestoreUsage.recordRead("loadNoteWithChildren", FirestoreUsage.ReadType.DOC_GET)
        val note = if (document.exists()) document.toObject(Note::class.java)?.copy(id = noteId) else null
        if (note == null) {
            emptyResult
        } else {
            NoteLoadResult(
                lines = loadNoteLines(note),
                isDeleted = note.state == NoteState.DELETED,
                showCompleted = note.showCompleted,
            )
        }
    }

    /**
     * Loads note lines via the same parentNoteId walk used by [reconstructNoteLines].
     * Shares heal semantics with [NoteStore.getNoteLinesById]: orphans are dropped,
     * strays linked by parentNoteId are appended, so the Firestore-fallback load
     * stays consistent with the reconstructed snapshot.
     */
    private suspend fun loadNoteLines(note: Note): List<NoteLine> {
        val userId = requireUserId()
        val descendantDocs = notesCollection
            .whereEqualTo("rootNoteId", note.id)
            .whereEqualTo("userId", userId)
            .get().await()

        val parsed = descendantDocs.mapNotNull { doc ->
            try {
                doc.toObject(Note::class.java).copy(id = doc.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing descendant", e)
                null
            }
        }
        // Include same-batch deleted descendants when the note itself is
        // deleted, mirroring NoteStore.getNoteLinesById. Live notes only
        // see live descendants.
        val noteDeleted = !isLive(note.state)
        val noteBatchId = note.deletionBatchId
        val descendants = parsed.filter {
            isLive(it.state) || (
                noteDeleted &&
                    it.state == NoteState.DELETED &&
                    noteBatchId != null &&
                    it.deletionBatchId == noteBatchId
            )
        }
        FirestoreUsage.recordRead("loadNoteLines", FirestoreUsage.ReadType.GET_DOCS, descendants.size)

        if (descendants.isEmpty()) {
            return listOf(NoteLine(note.content, note.id))
        }

        val rawById = HashMap<String, Note>(descendants.size + 1).apply {
            put(note.id, note)
            for (d in descendants) put(d.id, d)
        }
        val childrenByParent = descendants
            .filter { it.parentNoteId != null }
            .groupBy { it.parentNoteId!! }
        val (lines, _) = reconstructNoteLines(note, rawById, childrenByParent)
        return lines
    }

    /**
     * Loads all top-level notes with full content reconstructed.
     * Uses the already-loaded notes collection to avoid extra queries.
     */
    suspend fun loadNotesWithFullContent(): Result<List<Note>> = ioLogged("loadNotesWithFullContent") {
        val userId = requireUserId()
        val result = notesCollection.whereEqualTo("userId", userId).get().await()

        val parsed = result.mapNotNull { doc ->
            try {
                doc.toObject(Note::class.java).copy(id = doc.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing note", e)
                null
            }
        }
        FirestoreUsage.recordRead("loadNotesWithFullContent", FirestoreUsage.ReadType.GET_DOCS, parsed.size)
        val allNotes = parsed.filter { isLive(it.state) }

        val topLevelNotes = allNotes.filter { it.parentNoteId == null }

        // Re-index by parentNoteId to match NoteStore's reconstruction algorithm.
        val childrenByParent = allNotes
            .filter { it.parentNoteId != null }
            .groupBy { it.parentNoteId!! }
        val rawById = allNotes.associateBy { it.id }
        topLevelNotes.map { note ->
            val (reconstructed, _) = reconstructNoteContent(note, rawById, childrenByParent)
            reconstructed
        }
    }

    suspend fun loadUserNotes(): Result<List<Note>> = ioLogged("loadUserNotes") {
        val userId = requireUserId()
        val result = notesCollection.whereEqualTo("userId", userId).get().await()

        val parsed = result.mapNotNull { doc ->
            try {
                doc.toObject(Note::class.java).copy(id = doc.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing note", e)
                null
            }
        }
        FirestoreUsage.recordRead("loadUserNotes", FirestoreUsage.ReadType.GET_DOCS, parsed.size)
        parsed.filter { it.parentNoteId == null && isLive(it.state) }
    }

    suspend fun loadAllUserNotes(): Result<List<Note>> = ioLogged("loadAllUserNotes") {
        val userId = requireUserId()
        val result = notesCollection.whereEqualTo("userId", userId).get().await()

        val parsed = result.mapNotNull { doc ->
            try {
                doc.toObject(Note::class.java).copy(id = doc.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing note", e)
                null
            }
        }
        FirestoreUsage.recordRead("loadAllUserNotes", FirestoreUsage.ReadType.GET_DOCS, parsed.size)
        parsed.filter { it.parentNoteId == null }
    }

    suspend fun loadNoteById(noteId: String): Result<Note?> = ioLogged("loadNoteById") {
        requireUserId()
        val document = noteRef(noteId).get().await()
        FirestoreUsage.recordRead("loadNoteById", FirestoreUsage.ReadType.DOC_GET)
        if (document.exists()) document.toObject(Note::class.java)?.copy(id = document.id) else null
    }

    suspend fun isNoteDeleted(noteId: String): Result<Boolean> = ioLogged("isNoteDeleted") {
        requireUserId()
        val document = noteRef(noteId).get().await()
        FirestoreUsage.recordRead("isNoteDeleted", FirestoreUsage.ReadType.DOC_GET)
        document.exists() && document.toObject(Note::class.java)?.state == NoteState.DELETED
    }

    // ── Save operations ─────────────────────────────────────────────────

    /**
     * Extra batch op the caller can splice into the same WriteBatch as the note save.
     * Used to atomically combine note writes with related cross-collection writes
     * (e.g. an alarm doc) so a single commit lands both. The [data] map is written
     * via `batch.set(ref, data)` (or merged when [merge] is true).
     */
    data class BatchExtraOp(
        val ref: DocumentReference,
        val data: Map<String, Any?>,
        val merge: Boolean,
    )

    /**
     * Invoked after line IDs are resolved (sentinels swapped for real refs), so the
     * builder can ask [resolveLineId] for the resolved noteId of any line — including
     * newly allocated ones — when constructing cross-collection writes that point at it.
     */
    fun interface ExtraOpsBuilder {
        fun build(resolveLineId: (Int) -> String, userId: String): List<BatchExtraOp>
    }

    /**
     * Save plan: ops ready to commit as a Firestore WriteBatch (chunked at
     * 500 if needed) plus a map of newly-allocated ids by line index.
     */
    private data class SavePlan(
        val noteId: String,
        val ops: List<BatchOp>,
        val createdIds: Map<Int, String>,
        /** Doc ids the plan keeps alive: local survivors + concurrent-subtree
         *  preservation. Used by [buildCutDeleteOps] to decide whether a
         *  pendingCut is being revived in this batch. */
        val survivingIds: Set<String>,
        /** Post-write `containedNotes` for every saved id (root + each
         *  descendant in the plan), keyed by the post-allocate effective
         *  id. The editor refreshes its `localBases` from this so the next
         *  save's 3-way merge has an accurate base — independent of the
         *  Firestore listener echo, which races with applyResult and can
         *  leave `rawNotes[noteId].containedNotes` empty for a brief
         *  window after a newly-created note's first save. */
        val postSaveContainedNotes: Map<String, List<String>>,
    )

    /**
     * Outcome of a successful save: the line-index → newly-allocated-id
     * map [createdIds], plus [postSaveContainedNotes] (see [SavePlan])
     * which the editor uses to refresh its localBases without going
     * through the listener cache.
     */
    data class SaveResult(
        val createdIds: Map<Int, String>,
        val postSaveContainedNotes: Map<String, List<String>>,
    )

    /**
     * One unit of work for [saveMultipleNotes]: the note id, the editor's
     * tracked lines, and the `containedNotes` snapshots captured at edit-
     * session start (root + every live descendant, keyed by id). The
     * snapshots anchor a 3-way merge in [planSaveNoteWithChildren] at every
     * depth so concurrent edits from other clients aren't silently
     * overwritten. Null on legacy paths (e.g. RecoverScreen) — the planner
     * then writes through without merging.
     */
    data class SaveItem(
        val noteId: String,
        val trackedLines: List<NoteLine>,
        val localBases: Map<String, List<String>>?,
        /** Per-line deletion source recorded by the editor (noteId → source).
         *  Used by the save planner to stamp source-tagged deletionBatchIds
         *  on this item's `toDelete` set. Empty when no source-recording
         *  removals happened in this session. */
        val deletionSources: Map<String, DeletionSource> = emptyMap(),
    )

    /**
     * Per-batch context shared by every [planSaveNoteWithChildren] in a
     * single `saveNoteWithChildren` or `saveMultipleNotes` invocation.
     * Bundling these together keeps the planner signatures from sprawling
     * and ensures every plan in the batch sees the same userId / cut-buffer
     * snapshot.
     */
    private data class SaveContext(
        val userId: String,
        val pendingCuts: Map<String, String>,
        /** Real noteIds claimed by ANY session in the same `saveMultipleNotes`
         *  batch. A line moved cross-note via paste-with-tryReclaim consumes
         *  its pendingCut entry at paste time, so by save time the cut buffer
         *  is empty — the source's planSave would otherwise add the line to
         *  its toDelete (because it's no longer in source's trackedLines and
         *  the buffer can't tell us it's being kept by another session).
         *  Excluding this set from toDelete prevents the soft-delete from
         *  racing against the destination's reparent write in the same batch.
         *  Empty for single-note saves where cross-session coordination
         *  doesn't apply. */
        val globalSurvivingIds: Set<String>,
        /** Per-line deletion source contributed by the editor (noteId →
         *  source). Lines in this save's toDelete look up their source here;
         *  unmatched ids fall back to UNKNOWN. Empty when the caller didn't
         *  instrument the editor's removal sites. */
        val deletionSources: Map<String, DeletionSource> = emptyMap(),
    )

    /**
     * Save a single note. Computes parentNoteId and containedNotes from
     * indentation, sets rootNoteId on all descendants, and strips tabs from
     * stored content. Returns a map of line indices to newly created note IDs.
     * Uses batch writes (not transactions) so saves queue offline; batches
     * chunk at 500 ops (Firestore limit).
     *
     * [localBases] is the `containedNotes` snapshot the editor captured at
     * edit-session start (root + every live descendant, keyed by id), used
     * by [planSaveNoteWithChildren] for a 3-way merge at every depth so
     * concurrent edits from other clients aren't silently overwritten.
     */
    suspend fun saveNoteWithChildren(
        noteId: String,
        trackedLines: List<NoteLine>,
        extraOpsBuilder: ExtraOpsBuilder?,
        localBases: Map<String, List<String>>?,
        deletionSources: Map<String, DeletionSource> = emptyMap(),
    ): Result<SaveResult> = ioLogged("saveNoteWithChildren") body@{
        if (trackedLines.isEmpty()) return@body SaveResult(emptyMap(), emptyMap())
        val userId = requireUserId()
        // Single-note save: no cross-session reparent coordination needed.
        val ctx = SaveContext(userId, NoteStore.getPendingCuts(), emptySet(), deletionSources)
        val plan = planSaveNoteWithChildren(
            noteId, trackedLines, extraOpsBuilder, localBases, ctx,
        )
        val cutDelete = buildCutDeleteOps(listOf(plan.survivingIds), ctx)
        commitInBatches("saveNoteWithChildren", plan.ops + cutDelete.ops)
        for (id in cutDelete.committedCutIds) NoteStore.clearPendingCut(id)
        SaveResult(plan.createdIds, plan.postSaveContainedNotes)
    }

    /**
     * Saves multiple notes atomically as a single Firestore batch (chunked at
     * 500 ops). Use when more than one note has unsaved edits — e.g. the main
     * editor + dirty inline view-directive sessions — so partial commits can't
     * leave stale content in some notes and saved content in others. Returns
     * per-noteId line-index→new-id maps.
     */
    suspend fun saveMultipleNotes(
        items: List<SaveItem>,
    ): Result<Map<String, SaveResult>> = ioLogged("saveMultipleNotes") body@{
        if (items.isEmpty()) return@body emptyMap()
        val userId = requireUserId()
        // Pre-compute the union of real noteIds claimed across every session's
        // trackedLines so each session's planSave can exclude them from
        // toDelete — see SaveContext.globalSurvivingIds for the race this
        // prevents.
        val globalSurvivingIds = items.asSequence()
            .flatMap { it.trackedLines.asSequence() }
            .map { it.noteId }
            .filter { NoteIdSentinel.isRealNoteId(it) }
            .toSet()
        val baseCtx = SaveContext(
            userId,
            NoteStore.getPendingCuts(),
            globalSurvivingIds,
        )
        val plans = items
            .filter { it.trackedLines.isNotEmpty() }
            .map { item ->
                planSaveNoteWithChildren(
                    item.noteId, item.trackedLines, extraOpsBuilder = null,
                    localBases = item.localBases,
                    ctx = baseCtx.copy(deletionSources = item.deletionSources),
                )
            }
        val cutDelete = buildCutDeleteOps(plans.map { it.survivingIds }, baseCtx)
        val allOps = plans.flatMap { it.ops } + cutDelete.ops
        commitInBatches("saveMultipleNotes", allOps)
        for (id in cutDelete.committedCutIds) NoteStore.clearPendingCut(id)
        plans.associate {
            it.noteId to SaveResult(it.createdIds, it.postSaveContainedNotes)
        }
    }

    private data class CutDeleteOps(val ops: List<BatchOp>, val committedCutIds: List<String>)

    /**
     * For each pendingCut whose lineId isn't a survivor in any of [survivingIdSets]
     * (i.e., not being revived as a child of some destination note in this batch),
     * append a `state='cut-delete'` write so the line is parked rather than
     * left orphaned in its old parent's tree. Returns every input id in
     * [CutDeleteOps.committedCutIds] — including ids whose underlying doc
     * has vanished from NoteStore (where [buildStateChangeOps] silently
     * skipped the write) — so the cut buffer is fully drained on commit and
     * doesn't leak phantoms.
     */
    private fun buildCutDeleteOps(
        survivingIdSets: List<Set<String>>,
        ctx: SaveContext,
    ): CutDeleteOps {
        // Partition pendingCuts: ids being revived in this batch (skip the
        // cut-delete write but still clear from buffer) vs ids needing parking.
        val idsToPark = mutableListOf<String>()
        val committedCutIds = mutableListOf<String>()
        for ((lineId, _) in ctx.pendingCuts) {
            committedCutIds.add(lineId)
            val reviving = survivingIdSets.any { lineId in it }
            // The destination's planSave writes state=null for revived ids;
            // we only need to schedule the cut-delete write for the rest.
            if (!reviving) idsToPark.add(lineId)
        }
        return CutDeleteOps(buildStateChangeOps(idsToPark, NoteState.CUT_DELETE), committedCutIds)
    }

    /**
     * Builds the writes for a single-note save without committing them. Pure
     * planning step shared by [saveNoteWithChildren] (single note) and
     * [saveMultipleNotes] (combined batch). Runs the same assertions and
     * content-drop guard as before — assertions throw inline so a bad item in
     * a multi-note save aborts the whole batch before any commit.
     *
     * [localBases], when non-null, is the `containedNotes` snapshot the
     * editor observed at edit-session start (root + every live descendant);
     * used to 3-way merge against the current remote at every depth so
     * concurrent edits from other clients aren't silently overwritten.
     * [ctx.pendingCuts] is the editor's cut buffer; planSave excludes its
     * keys from soft-delete so cross-note moves aren't lost when this
     * note's save runs ahead of the destination's.
     */
    private fun planSaveNoteWithChildren(
        noteId: String,
        trackedLines: List<NoteLine>,
        extraOpsBuilder: ExtraOpsBuilder?,
        localBases: Map<String, List<String>>?,
        ctx: SaveContext,
    ): SavePlan {
        val (userId, pendingCuts) = ctx
        assertNoteStoreLoaded("saveNoteWithChildren", noteId)
        warnIfDescendantsLikelyStale("saveNoteWithChildren", noteId)

        // [NoteLine.noteId] is non-nullable, so every descendant arrives with
        // a real id or a sentinel — no null possible. Tally sentinel origins
        // for diagnostics.
        val sentinelByOrigin = HashMap<String, Int>()
        for (idx in 1 until trackedLines.size) {
            val id = trackedLines[idx].noteId
            if (NoteIdSentinel.isSentinel(id)) {
                val origin = NoteIdSentinel.originOf(id) ?: "unknown"
                sentinelByOrigin.merge(origin, 1) { a, b -> a + b }
            }
        }
        if (sentinelByOrigin.isNotEmpty()) {
            Log.d(TAG, "saveNoteWithChildren($noteId): sentinel origins=$sentinelByOrigin")
        }

        val parentRef = noteRef(noteId)
        val rootContent = trackedLines[0].content.trimStart('\t')
        val linesToSave = trackedLines

        // Pre-allocate refs for lines that need a fresh Firestore doc —
        // either null (bug path — id was wiped) or a sentinel (expected —
        // marks a new line from paste/split/agent/typed/etc.).
        val newRefs = mutableMapOf<Int, DocumentReference>()
        for (i in 1 until linesToSave.size) {
            if (NoteIdSentinel.isSentinel(linesToSave[i].noteId)) {
                newRefs[i] = newNoteRef()
            }
        }

        fun effectiveId(lineIndex: Int): String = when {
            lineIndex == 0 -> noteId
            lineIndex in newRefs -> newRefs[lineIndex]!!.id
            else -> linesToSave[lineIndex].noteId!!
        }

        // Empty lines don't push the indent stack — indented children
        // below them attach to the last content-bearing ancestor.
        val parentOfLine = IntArray(linesToSave.size)
        val childrenOfLine = Array(linesToSave.size) { mutableListOf<String>() }

        data class StackEntry(val depth: Int, val lineIndex: Int)
        val stack = mutableListOf(StackEntry(0, 0))

        for (i in 1 until linesToSave.size) {
            val depth = linesToSave[i].content.takeWhile { it == '\t' }.length
            val content = linesToSave[i].content.trimStart('\t')

            while (stack.size > 1 && stack.last().depth >= depth) {
                stack.removeLast()
            }
            parentOfLine[i] = stack.last().lineIndex
            childrenOfLine[parentOfLine[i]].add(effectiveId(i))
            if (content.isNotEmpty()) {
                stack.add(StackEntry(depth, i))
            }
        }

        // Get existing descendants from in-memory NoteStore (works offline,
        // unlike the Firestore query that fetchExistingDescendantIds used).
        val existingDescendantIds = NoteStore.getDescendantIds(noteId)

        // Compute surviving IDs upfront for the content-drop guard.
        val survivingIds = mutableSetOf<String>()
        for (i in 1 until linesToSave.size) {
            survivingIds.add(effectiveId(i))
        }
        // Subtrees a concurrent client added — at any depth — since the editor
        // captured [localBases] aren't represented in our trackedLines.
        // Without this, the soft-delete pass below would wipe their work.
        survivingIds += findConcurrentSubtree(localBases, existingDescendantIds, NoteStore::getRawNoteById)
        // Cut lines awaiting paste in this session aren't local survivors here,
        // but their destination's planSave (or the cut-delete pass) handles
        // them — exclude from toDelete so this save doesn't soft-delete a
        // line that's being moved cross-note.
        // Cross-session reparent: see SaveContext.globalSurvivingIds. A
        // line claimed by another session in the same batch is being kept
        // (not deleted from existence), even if pendingCuts is already
        // empty (e.g., paste consumed it via tryReclaim).
        val toDelete = existingDescendantIds - survivingIds - pendingCuts.keys - ctx.globalSurvivingIds

        // Content-drop guard: compare against the note's containedNotes
        // intersected with real descendants. Orphan refs (containedNotes
        // entries without a live child) are routinely dropped by the
        // auto-fix reconstruction; counting them as deletions would cause
        // a save of a healed note to falsely trip the guard.
        val declaredContainedNotes = (NoteStore.getNoteById(noteId)?.containedNotes
            ?: emptyList()).toSet()
        val realContainedNotes = declaredContainedNotes.intersect(existingDescendantIds)

        val orphanRefs = declaredContainedNotes - existingDescendantIds
        if (orphanRefs.isNotEmpty()) {
            Log.w(
                TAG,
                "saveNoteWithChildren($noteId): ignoring ${orphanRefs.size} orphan " +
                    "containedNotes refs for content-drop guard (no live child): $orphanRefs"
            )
        }

        val directToDelete = realContainedNotes - survivingIds
        if (realContainedNotes.size >= 3 && directToDelete.size > realContainedNotes.size / 2) {
            val diagnostics = buildContentDropDiagnostics(
                noteId, trackedLines, linesToSave, existingDescendantIds, survivingIds, toDelete
            )
            Log.e(TAG, diagnostics)
            launchDescendantDiagnostics(noteId, toDelete)
            throw ContentDropAbortException(
                "Save aborted: would delete ${toDelete.size} of " +
                    "${existingDescendantIds.size} child notes " +
                    "(saving ${linesToSave.size} lines). " +
                    "This was blocked to prevent data loss. " +
                    "Your note content is still safe — please save again. " +
                    "Connect to a computer and check logcat tag '$TAG' for diagnostics."
            )
        }

        // Fix parent cycles: if this note's parent chain loops back
        // to itself, clear parentNoteId/rootNoteId to make it a root note.
        val hasCycle = hasParentCycle(noteId)

        // Collect all write operations, then commit in batches of 500
        // (Firestore batch limit). Batch writes queue offline, unlike
        // transactions which require a server roundtrip.
        val ops = mutableListOf<BatchOp>()
        var skippedUnchanged = 0

        // 3-way merge of `containedNotes` runs uniformly at the root and every
        // real-id descendant: combine our local children list with the doc's
        // remote `containedNotes` using its base from edit-session start.
        // Picks up concurrent additions by other clients and respects their
        // removals. Sentinel (new) lines have no remote — fall through to
        // the local list.
        val mergedContained: List<List<String>> = childrenOfLine.mapIndexed { i, local ->
            if (i > 0 && !NoteIdSentinel.isRealNoteId(linesToSave[i].noteId)) return@mapIndexed local.toList()
            val id = effectiveId(i)
            val base = localBases?.get(id)
            val remote = NoteStore.getRawNoteById(id)?.containedNotes ?: emptyList()
            mergeContainedNotes(local = local.toList(), base = base, remote = remote)
        }
        val existingRoot = NoteStore.getRawNoteById(noteId)
        val rootUnchanged = !hasCycle &&
            existingRoot != null &&
            existingRoot.content == rootContent &&
            existingRoot.containedNotes == mergedContained[0] &&
            existingRoot.state == null
        if (rootUnchanged) {
            skippedUnchanged++
        } else {
            val rootBase = localBases?.get(noteId)
            val rootData = baseNoteData(userId, rootContent).apply {
                put("containedNotes", mergedContained[0])
                // Stamp containedNotesBase only when an anchor was recorded —
                // skipping when null avoids writing a tautological field on
                // legacy paths that don't track an edit-session anchor.
                if (rootBase != null) put("containedNotesBase", rootBase)
                if (hasCycle) {
                    put("parentNoteId", FieldValue.delete())
                    put("rootNoteId", FieldValue.delete())
                }
            }
            ops.add(BatchOp(parentRef, rootData, merge = true))
        }

        for (i in 1 until linesToSave.size) {
            val content = linesToSave[i].content.trimStart('\t')
            val parentId = effectiveId(parentOfLine[i])

            if (NoteIdSentinel.isRealNoteId(linesToSave[i].noteId)) {
                val descId = effectiveId(i)
                val base = localBases?.get(descId)
                val existingDescendant = NoteStore.getRawNoteById(descId)
                val unchanged = existingDescendant != null &&
                    existingDescendant.content == content &&
                    existingDescendant.parentNoteId == parentId &&
                    existingDescendant.rootNoteId == noteId &&
                    existingDescendant.containedNotes == mergedContained[i] &&
                    existingDescendant.state == null
                if (unchanged) {
                    skippedUnchanged++
                    continue
                }
                val descData = HashMap<String, Any?>().apply {
                    put("content", content)
                    put("parentNoteId", parentId)
                    put("rootNoteId", noteId)
                    put("containedNotes", mergedContained[i])
                    put("state", null)
                    put("updatedAt", FieldValue.serverTimestamp())
                    if (base != null) put("containedNotesBase", base)
                }
                ops.add(BatchOp(noteRef(descId), descData, merge = true))
            } else {
                ops.add(BatchOp(
                    newRefs[i]!!,
                    hashMapOf<String, Any?>(
                        "userId" to userId,
                        "content" to content,
                        "parentNoteId" to parentId,
                        "rootNoteId" to noteId,
                        "containedNotes" to mergedContained[i],
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                    merge = false
                ))
            }
        }
        if (skippedUnchanged > 0) {
            Log.d(TAG, "saveNoteWithChildren($noteId): skipped $skippedUnchanged unchanged docs of ${linesToSave.size}, writing ${ops.size}")
        }

        // Soft-delete removed notes. Preserve parentNoteId/rootNoteId so the
        // deleted-notes view can distinguish removed child lines (have a parent)
        // from deleted top-level notes (don't). Group by deletion source so
        // each source within this save gets its own batchId; lines without a
        // recorded source fall back to UNKNOWN (an `unknown_*` batchId in
        // the data flags an uninstrumented removal path — drive to zero).
        val batchIdsBySource = HashMap<DeletionSource, String>()
        for (id in toDelete) {
            val source = ctx.deletionSources[id] ?: DeletionSource.UNKNOWN
            val batchId = batchIdsBySource.getOrPut(source) {
                DeletionSource.newBatchId(source)
            }
            ops.add(BatchOp(
                noteRef(id),
                mapOf(
                    "state" to NoteState.DELETED,
                    "deletionBatchId" to batchId,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                merge = true
            ))
        }

        extraOpsBuilder?.build(::effectiveId, userId)?.forEach { extra ->
            ops.add(BatchOp(extra.ref, extra.data, extra.merge))
        }

        val postSaveContainedNotes = HashMap<String, List<String>>(linesToSave.size).apply {
            put(noteId, mergedContained[0])
            for (i in 1 until linesToSave.size) {
                put(effectiveId(i), mergedContained[i])
            }
        }
        return SavePlan(
            noteId = noteId,
            ops = ops,
            createdIds = newRefs.mapValues { it.value.id },
            survivingIds = survivingIds,
            postSaveContainedNotes = postSaveContainedNotes,
        )
    }

    /**
     * Checks if a note's parent chain contains a cycle (using NoteStore's in-memory data).
     */
    private fun hasParentCycle(noteId: String): Boolean {
        val visited = mutableSetOf<String>()
        var current: String? = noteId
        while (current != null) {
            if (!visited.add(current)) return true
            current = NoteStore.getRawNoteById(current)?.parentNoteId
        }
        return false
    }

    /**
     * Saves a note with full multi-line content, properly handling child notes.
     * Used for inline editing of notes within view directives.
     */
    suspend fun saveNoteWithFullContent(noteId: String, newContent: String): Result<Unit> = ioLogged("saveNoteWithFullContent") {
        // No editorNoteIds on this legacy unmount path — content is the only
        // signal we have. Save will fall back to content/positional matching.
        val trackedLines = prepareInlineEditTrackedLines(
            noteId, newContent, "saveNoteWithFullContent", editorNoteIds = null,
        )
        // No edit-session anchor on this legacy path — pass null so planSave
        // skips the 3-way merge and writes through.
        saveNoteWithChildren(
            noteId, trackedLines, extraOpsBuilder = null, localBases = null,
        ).getOrThrow()
        Log.d(TAG, "Saved note with full content: $noteId (${trackedLines.size} lines)")
    }

    /**
     * Builds tracked lines for an inline-edited note by matching its flat
     * editor content against the existing tree (preserving grandchild
     * relationships). Exposed so callers can pre-build the planning input
     * for [saveMultipleNotes] without committing each session separately.
     * Mirrors the load+match step inside [saveNoteWithFullContent].
     *
     * [operation] tags any [NoteStoreNotLoadedException] thrown from the load
     * step with the caller's name; pass an explicit value so the user-facing
     * error reflects the originating call site.
     */
    suspend fun prepareInlineEditTrackedLines(
        noteId: String,
        newContent: String,
        operation: String,
        editorNoteIds: List<String?>?,
    ): List<NoteLine> {
        requireUserId()
        assertNoteStoreLoaded(operation, noteId)
        warnIfDescendantsLikelyStale(operation, noteId)

        val existingLines = NoteStore.getNoteLinesById(noteId)
            ?: loadNoteWithChildren(noteId).getOrThrow().lines

        return matchLinesToIds(noteId, existingLines, newContent.lines(), editorNoteIds)
    }

    /**
     * Creates a new empty note.
     */
    suspend fun createNote(): Result<String> = ioLogged("createNote") {
        val userId = requireUserId()
        val data = newNoteData(userId, "")
        val ref = notesCollection.add(data).await()
        FirestoreUsage.recordWrite("createNote", FirestoreUsage.WriteType.SET)
        Log.d(TAG, "Note created with ID: ${ref.id}")
        ref.id
    }

    /**
     * Creates a new multi-line note with tree structure derived from indentation.
     */
    suspend fun createMultiLineNote(content: String): Result<String> = ioLogged("createMultiLineNote") body@{
        val userId = requireUserId()
        return@body createMultiLineNoteInner(userId, content)
    }

    private suspend fun createMultiLineNoteInner(
        userId: String,
        content: String,
    ): String {
        val lines = content.lines()
        val firstLine = lines.firstOrNull()?.trimStart('\t') ?: ""
        val childLines = lines.drop(1)

        if (childLines.isEmpty() || childLines.all { it.trimStart('\t').isEmpty() }) {
            val data = newNoteData(userId, firstLine)
            val ref = notesCollection.add(data).await()
            FirestoreUsage.recordWrite("createMultiLineNote", FirestoreUsage.WriteType.SET)
            return ref.id
        }

        val parentRef = newNoteRef()

        // Two-pass: first compute tree structure with allocated refs, then write
        data class NodeInfo(
            val ref: DocumentReference,
            val content: String,
            val parentId: String,
            val children: MutableList<String>,
        )

        val nodes = mutableListOf<NodeInfo>()
        val rootChildren = mutableListOf<String>()

        data class StackEntry(val depth: Int, val id: String, val children: MutableList<String>)
        val stack = mutableListOf(StackEntry(0, parentRef.id, rootChildren))

        for (i in 1 until lines.size) {
            val depth = lines[i].takeWhile { it == '\t' }.length
            val lineContent = lines[i].trimStart('\t')

            while (stack.size > 1 && stack.last().depth >= depth) {
                stack.removeLast()
            }
            val parent = stack.last()

            // Empty lines don't push the indent stack — children below
            // attach to the last content-bearing ancestor.
            val ref = newNoteRef()
            val nodeChildren = mutableListOf<String>()
            nodes.add(NodeInfo(ref, lineContent, parent.id, nodeChildren))
            parent.children.add(ref.id)
            if (lineContent.isNotEmpty()) {
                stack.add(StackEntry(depth, ref.id, nodeChildren))
            }
        }

        val batch = db.batch()

        batch.set(parentRef, newNoteData(userId, firstLine).apply {
            put("containedNotes", rootChildren)
        })

        for (node in nodes) {
            batch.set(node.ref, hashMapOf<String, Any?>(
                "userId" to userId,
                "content" to node.content,
                "parentNoteId" to node.parentId,
                "rootNoteId" to parentRef.id,
                "containedNotes" to node.children.toList(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ))
        }

        batch.commit().await()
        // Root note + N descendants written in a single batch.
        FirestoreUsage.recordWrite("createMultiLineNote", FirestoreUsage.WriteType.BATCH_COMMIT, nodes.size + 1)
        Log.d(TAG, "Multi-line note created with ID: ${parentRef.id}")
        return parentRef.id
    }

    // ── Delete/restore operations ───────────────────────────────────────

    /**
     * Soft-deletes a note and all its descendants.
     */
    suspend fun softDeleteNote(noteId: String): Result<Unit> = ioLogged("softDeleteNote") {
        requireUserId()
        assertNoteStoreLoaded("softDeleteNote", noteId)
        warnIfDescendantsLikelyStale("softDeleteNote", noteId)
        val idsToDelete = NoteStore.getDescendantIds(noteId) + noteId
        val batchId = DeletionSource.newBatchId(DeletionSource.WHOLE_NOTE)
        commitInBatches(
            "softDeleteNote",
            buildStateChangeOps(idsToDelete, NoteState.DELETED, batchId),
        )
    }

    /**
     * Restores a deleted note and all its descendants.
     */
    suspend fun undeleteNote(noteId: String): Result<Unit> = ioLogged("undeleteNote") {
        val userId = requireUserId()
        assertNoteStoreLoaded("undeleteNote", noteId)
        // Skip warnIfDescendantsLikelyStale: a deleted note's containedNotes
        // only lists active children, so it can't detect missing soft-deleted
        // descendants — the heuristic doesn't apply here.
        //
        // Read root + descendants from Firestore — NOT from NoteStore.
        // Reason: the Firestore listener that updates NoteStore can lag the
        // actual Firestore state by a snapshot tick. A user clicking
        // Restore right after Delete can observe a stale NoteStore where
        // the just-committed softDelete hasn't propagated; rootBatchId
        // would read as null, falling into the legacy "restore everything"
        // path and resurrecting older-deleted descendants. One extra doc
        // read + one descendant query per restore is a fine cost to pay
        // for race-free correctness.
        val rootSnap = noteRef(noteId).get().await()
        FirestoreUsage.recordRead("undeleteNote", FirestoreUsage.ReadType.DOC_GET)
        val rootBatchId = rootSnap.getString("deletionBatchId")
        val idsToRestore: Set<String> = if (rootBatchId != null) {
            // Scope the restore to descendants from the same delete batch.
            // Older-deleted children (different batchId) stay deleted —
            // they weren't part of this delete operation; bringing them
            // back would resurrect notes the user removed on purpose.
            val descendantsSnap = notesCollection
                .whereEqualTo("userId", userId)
                .whereEqualTo("rootNoteId", noteId)
                .whereEqualTo("deletionBatchId", rootBatchId)
                .get().await()
            FirestoreUsage.recordRead(
                "undeleteNote", FirestoreUsage.ReadType.GET_DOCS, descendantsSnap.size(),
            )
            descendantsSnap.documents.map { it.id }.toSet() + noteId
        } else {
            // Legacy deletes pre-batchId have no `deletionBatchId`. Restore
            // every descendant — preserves pre-feature behavior. NoteStore
            // is fine here: a legacy deletion is by definition not racing
            // with a just-fired softDelete.
            NoteStore.getAllDescendantIds(noteId) + noteId
        }
        commitInBatches("undeleteNote", buildStateChangeOps(idsToRestore, null))
    }

    /**
     * Restores parked cut-delete docs by flipping `state` back to null. The
     * stray-child healing inside reconstruction picks each restored doc up
     * under its preserved parentNoteId; the next save of that root writes
     * the healed `containedNotes` back to Firestore.
     */
    suspend fun restoreCutDeletedNotes(noteIds: List<String>): Result<Unit> = ioLogged("restoreCutDeletedNotes") body@{
        requireUserId()
        if (noteIds.isEmpty()) return@body
        commitInBatches("restoreCutDeletedNotes", buildStateChangeOps(noteIds, null))
    }

    // ── Utility operations ──────────────────────────────────────────────

    suspend fun updateShowCompleted(noteId: String, showCompleted: Boolean): Result<Unit> = ioLogged("updateShowCompleted") {
        requireUserId()
        noteRef(noteId).update(
            mapOf(
                "showCompleted" to showCompleted,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        FirestoreUsage.recordWrite("updateShowCompleted", FirestoreUsage.WriteType.UPDATE)
    }

    /**
     * Builds tracked lines from existing lines + new content via the shared
     * [reconcileLineNoteIds] / [enforceParentNoteId] helpers.
     *
     * Logs a warning if any non-empty new line loses its noteId during matching —
     * those lines will allocate fresh ids on save, which is correct semantically but
     * worth surfacing because it almost always indicates either editor corruption or
     * a substantial content rewrite.
     */
    private fun matchLinesToIds(
        parentNoteId: String,
        existingLines: List<NoteLine>,
        newLinesContent: List<String>,
        editorNoteIds: List<String?>?,
    ): List<NoteLine> {
        // Phase 0 — foreign-id detection. When the editor hands us a real
        // noteId for a line that ISN'T part of this note's tree, trust it.
        // Lines pasted from another inline session carry the source note's
        // real id; without this phase, content matching falls through to a
        // fresh sentinel, the planner allocates a new doc, and the original
        // gets parked as cut-delete instead of reparented. Mirrors the web
        // matchLinesToIds Phase 0. Sentinel ids are excluded — they're
        // per-allocation unique and would spuriously clear the
        // existingNoteIdSet check; the save planner is responsible for
        // resolving those.
        val foreignIds: Array<String?>? = editorNoteIds?.let { ids ->
            val existingNoteIdSet = existingLines.mapNotNull { it.noteId }.toSet()
            Array(newLinesContent.size) { i ->
                val editorId = ids.getOrNull(i) ?: return@Array null
                if (!NoteIdSentinel.isRealNoteId(editorId)) return@Array null
                if (editorId in existingNoteIdSet) return@Array null
                editorId
            }
        }

        if (existingLines.isEmpty()) {
            return newLinesContent.mapIndexed { index, content ->
                NoteLine(
                    content,
                    foreignIds?.get(index)
                        ?: if (index == 0) parentNoteId
                        else NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED),
                )
            }
        }

        val oldContents = existingLines.map { it.content }
        val oldNoteIds = existingLines.map { listOfNotNull(it.noteId) }

        val unmatched = mutableListOf<Pair<Int, String>>()
        val reconciled = reconcileLineNoteIds(
            oldContents = oldContents,
            oldNoteIds = oldNoteIds,
            newContents = newLinesContent,
            onUnmatchedNonEmpty = { idx, content -> unmatched.add(idx to content) },
        )
        val withParent = enforceParentNoteId(reconciled, parentNoteId)

        if (unmatched.isNotEmpty()) {
            Log.w(
                TAG,
                "matchLinesToIds: ${unmatched.size} non-empty new line(s) lost noteIds " +
                    "(no exact or similarity match). parentNoteId=$parentNoteId, " +
                    "existing.size=${existingLines.size}, new.size=${newLinesContent.size}. " +
                    "First: ${unmatched.take(3).joinToString { "[${it.first}] '${it.second.take(40)}'" }}"
            )
        }

        return newLinesContent.mapIndexed { index, content ->
            NoteLine(
                content,
                foreignIds?.get(index)
                    ?: withParent[index].firstOrNull()
                    ?: NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED),
            )
        }
    }

    /**
     * Builds a detailed diagnostic string for content-drop guard violations.
     * Logged at ERROR level so it can be retrieved via logcat for debugging.
     */
    private fun buildContentDropDiagnostics(
        noteId: String,
        originalTrackedLines: List<NoteLine>,
        linesToSave: List<NoteLine>,
        existingDescendantIds: Set<String>,
        survivingIds: Set<String>,
        toDelete: Set<String>,
    ): String = buildString {
        appendLine("=== CONTENT DROP GUARD TRIGGERED ===")
        appendLine("noteId: $noteId")
        appendLine("originalTrackedLines: ${originalTrackedLines.size}")
        appendLine("linesToSave: ${linesToSave.size}")
        appendLine("existingDescendants (rootNoteId query): ${existingDescendantIds.size} $existingDescendantIds")
        appendLine("survivingIds: ${survivingIds.size} $survivingIds")
        appendLine("toDelete: ${toDelete.size} $toDelete")

        appendLine("--- containedNotes guard ---")
        val storeNote = NoteStore.getNoteById(noteId)
        val containedNotes = storeNote?.containedNotes ?: emptyList()
        appendLine("  containedNotes: ${containedNotes.size} $containedNotes")
        val directToDelete = containedNotes.toSet() - survivingIds
        appendLine("  directToDelete (containedNotes - surviving): ${directToDelete.size} $directToDelete")

        appendLine("--- trackedLines detail ---")
        for ((i, line) in originalTrackedLines.withIndex()) {
            val preview = line.content.take(60).replace("\n", "\\n")
            appendLine("  [$i] noteId=${line.noteId ?: "null"} content='$preview'")
        }

        appendLine("--- NoteStore state ---")
        if (storeNote != null) {
            appendLine("  parentNoteId: ${storeNote.parentNoteId ?: "null"}")
            appendLine("  rootNoteId: ${storeNote.rootNoteId ?: "null"}")
            appendLine("  state: ${storeNote.state ?: "active"}")
            val storeLines = storeNote.content.lines()
            appendLine("  content lines: ${storeLines.size}")
            for ((i, line) in storeLines.withIndex()) {
                appendLine("  [$i] '${line.take(60)}'")
            }
        } else {
            appendLine("  NoteStore has NO entry for $noteId")
        }

        appendLine("--- Thread ---")
        appendLine("  ${Thread.currentThread().name}")
        appendLine("--- Stack trace ---")
        for (frame in Thread.currentThread().stackTrace.drop(2).take(15)) {
            appendLine("  at $frame")
        }
        appendLine("=== END CONTENT DROP GUARD ===")
    }

    /**
     * Fetches Firestore details for each note in [toDelete] and logs them.
     * Called after the main diagnostics to provide full context without
     * blocking the guard (runs as a separate coroutine).
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun launchDescendantDiagnostics(noteId: String, toDelete: Set<String>) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                sb.appendLine("=== DESCENDANT DETAIL FOR $noteId (${toDelete.size} notes to delete) ===")
                // Fetch in batches of 30 (Firestore whereIn limit)
                val sorted = toDelete.sorted()
                for (batch in sorted.chunked(30)) {
                    try {
                        val snapshot = notesCollection
                            .whereIn(com.google.firebase.firestore.FieldPath.documentId(), batch)
                            .get().await()
                        val fetched = snapshot.documents.associateBy { it.id }
                        FirestoreUsage.recordRead(
                            "launchDescendantDiagnostics",
                            FirestoreUsage.ReadType.GET_DOCS,
                            fetched.size,
                        )
                        for (id in batch) {
                            val doc = fetched[id]
                            if (doc == null || !doc.exists()) {
                                sb.appendLine("  $id: DOES NOT EXIST in Firestore")
                                continue
                            }
                            val data = doc.data ?: emptyMap()
                            val parentId = data["parentNoteId"] as? String
                            val rootId = data["rootNoteId"] as? String
                            val state = data["state"] as? String
                            val content = (data["content"] as? String)?.take(60) ?: ""
                            @Suppress("UNCHECKED_CAST")
                            val contained = (data["containedNotes"] as? List<String>)?.size ?: 0
                            sb.appendLine("  $id: parent=$parentId root=$rootId state=${state ?: "active"} containedNotes=$contained content='$content'")
                        }
                    } catch (e: Exception) {
                        sb.appendLine("  batch ${batch.first()}..${batch.last()}: FETCH ERROR: ${e.message}")
                    }
                }
                sb.appendLine("=== END DESCENDANT DETAIL ===")
                Log.e(TAG, sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch descendant diagnostics", e)
            }
        }
    }

    private data class BatchOp(
        val ref: DocumentReference,
        val data: Map<String, Any?>,
        val merge: Boolean
    )

    /**
     * 3-way merge of `containedNotes` ID lists. Returns:
     *   (local ∪ remote_added) − remote_removed
     * where remote_added = remote − base and remote_removed = base − remote.
     *
     * Behavior:
     * - [base] is null (legacy / no session tracking): returns [local] unchanged.
     * - [base] equals [remote] (no concurrent edit): returns [local].
     * - Otherwise: keeps everything in [local], appends remote-only additions
     *   in their relative remote order at the end, and drops items removed by
     *   remote even if local still had them.
     */
    private fun mergeContainedNotes(
        local: List<String>,
        base: List<String>?,
        remote: List<String>,
    ): List<String> {
        if (base == null) return local
        // Fast path: no concurrent edit since base was captured. Skip the Set
        // construction — this is the dominant case in practice.
        if (base == remote) return local

        val baseSet = base.toSet()
        val remoteSet = remote.toSet()
        val localSet = local.toSet()
        val remoteRemoved = baseSet - remoteSet

        val result = ArrayList<String>(local.size + remote.size)
        for (id in local) if (id !in remoteRemoved) result.add(id)
        for (id in remote) if (id !in baseSet && id !in localSet) result.add(id)
        return result
    }

    /**
     * IDs of every doc whose ancestor chain reaches a `containedNotes` entry
     * added by a concurrent client at any anchored parent in [bases] — i.e.
     * an item present in that parent's remote `containedNotes` but not in
     * its recorded base. Used to extend the surviving set in the planner so
     * the soft-delete pass doesn't wipe subtrees a different client just
     * created.
     *
     * The BFS walks within [existingDescendantIds] so survivors are bounded
     * to the current subtree. Returns an empty set when [bases] is null/empty.
     */
    private fun findConcurrentSubtree(
        bases: Map<String, List<String>>?,
        existingDescendantIds: Set<String>,
        getNote: (String) -> Note?,
    ): Set<String> {
        if (bases.isNullOrEmpty()) return emptySet()
        val result = HashSet<String>()
        val queue = ArrayDeque<String>()
        for ((parentId, base) in bases) {
            val remote = getNote(parentId)?.containedNotes.orEmpty()
            if (remote.isEmpty()) continue
            val baseSet = base.toSet()
            for (id in remote) {
                if (id !in baseSet && id in existingDescendantIds && result.add(id)) {
                    queue.addLast(id)
                }
            }
        }
        if (queue.isEmpty()) return result

        val childrenByParent = HashMap<String, MutableList<String>>()
        for (id in existingDescendantIds) {
            val parent = getNote(id)?.parentNoteId ?: continue
            childrenByParent.getOrPut(parent) { mutableListOf() }.add(id)
        }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            for (child in childrenByParent[id].orEmpty()) {
                if (result.add(child)) queue.addLast(child)
            }
        }
        return result
    }

    /**
     * Build merge writes that flip `state` for each id. Skips ids absent
     * from NoteStore (defends against the doc being hard-deleted between
     * caller's id-collection and write). Shared by softDeleteNote,
     * undeleteNote, restoreCutDeletedNotes, and buildCutDeleteOps.
     */
    private fun buildStateChangeOps(
        ids: Iterable<String>,
        newState: String?,
        deletionBatchId: String? = null,
    ): List<BatchOp> {
        // Stamp `deletionBatchId` only when entering DELETED; clear it on
        // any other transition (LIVE on undelete, CUT_DELETE on park) so a
        // subsequent re-delete starts a fresh batch. Pass-through writes
        // (newState == DELETED with a null batchId) shouldn't happen but
        // we tolerate them — the field just stays whatever it was.
        val data: Map<String, Any?> = when {
            newState == NoteState.DELETED && deletionBatchId != null -> mapOf(
                "state" to newState,
                "deletionBatchId" to deletionBatchId,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            newState == NoteState.DELETED -> mapOf(
                "state" to newState,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            else -> mapOf(
                "state" to newState,
                "deletionBatchId" to null,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        }
        val ops = mutableListOf<BatchOp>()
        for (id in ids) {
            if (NoteStore.getRawNoteById(id) == null) continue
            ops.add(BatchOp(noteRef(id), data, merge = true))
        }
        return ops
    }

    private suspend fun commitInBatches(operation: String, ops: List<BatchOp>) {
        for (chunk in ops.chunked(MAX_BATCH_SIZE)) {
            val batch = db.batch()
            for (op in chunk) {
                if (op.merge) {
                    batch.set(op.ref, op.data, SetOptions.merge())
                } else {
                    batch.set(op.ref, op.data)
                }
            }
            batch.commit().await()
            FirestoreUsage.recordWrite(operation, FirestoreUsage.WriteType.BATCH_COMMIT, chunk.size)
        }
    }

    companion object {
        private const val TAG = "NoteRepository"
        private const val MAX_BATCH_SIZE = 500
        private const val NOTE_STORE_AWAIT_MS = 1500L
    }
}

/**
 * Thrown when [NoteRepository.saveNoteWithChildren] aborts because the save
 * would soft-delete an unreasonable number of descendant notes, indicating
 * a likely race condition or stale editor state.
 */
class ContentDropAbortException(message: String) : Exception(message)
