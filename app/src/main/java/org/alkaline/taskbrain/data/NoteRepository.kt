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
                    isDeleted = rawNote.state == "deleted",
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
                isDeleted = note.state == "deleted",
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

        val descendants = descendantDocs.mapNotNull { doc ->
            try {
                doc.toObject(Note::class.java).copy(id = doc.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing descendant", e)
                null
            }
        }.filter { isLive(it.state) }
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
        document.exists() && document.toObject(Note::class.java)?.state == "deleted"
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
    )

    /**
     * Save a single note. Computes parentNoteId and containedNotes from
     * indentation, sets rootNoteId on all descendants, and strips tabs from
     * stored content. Returns a map of line indices to newly created note IDs.
     * Uses batch writes (not transactions) so saves queue offline; batches
     * chunk at 500 ops (Firestore limit).
     */
    suspend fun saveNoteWithChildren(
        noteId: String,
        trackedLines: List<NoteLine>,
        extraOpsBuilder: ExtraOpsBuilder?,
    ): Result<Map<Int, String>> = ioLogged("saveNoteWithChildren") body@{
        if (trackedLines.isEmpty()) return@body emptyMap()
        val opId = newClientOpId()
        NoteStore.registerPendingOp(opId)
        try {
            val plan = planSaveNoteWithChildren(noteId, trackedLines, extraOpsBuilder, opId)
            commitInBatches("saveNoteWithChildren", plan.ops)
            plan.createdIds
        } finally {
            NoteStore.releasePendingOp(opId)
        }
    }

    /**
     * Saves multiple notes atomically as a single Firestore batch (chunked at
     * 500 ops). Use when more than one note has unsaved edits — e.g. the main
     * editor + dirty inline view-directive sessions — so partial commits can't
     * leave stale content in some notes and saved content in others. Returns
     * per-noteId line-index→new-id maps.
     */
    suspend fun saveMultipleNotes(
        items: List<Pair<String, List<NoteLine>>>,
    ): Result<Map<String, Map<Int, String>>> = ioLogged("saveMultipleNotes") body@{
        if (items.isEmpty()) return@body emptyMap()
        val opId = newClientOpId()
        NoteStore.registerPendingOp(opId)
        try {
            val plans = items
                .filter { it.second.isNotEmpty() }
                .map { (noteId, trackedLines) ->
                    planSaveNoteWithChildren(noteId, trackedLines, extraOpsBuilder = null, opId = opId)
                }
            val allOps = plans.flatMap { it.ops }
            commitInBatches("saveMultipleNotes", allOps)
            plans.associate { it.noteId to it.createdIds }
        } finally {
            NoteStore.releasePendingOp(opId)
        }
    }

    /**
     * Builds the writes for a single-note save without committing them. Pure
     * planning step shared by [saveNoteWithChildren] (single note) and
     * [saveMultipleNotes] (combined batch). Runs the same assertions and
     * content-drop guard as before — assertions throw inline so a bad item in
     * a multi-note save aborts the whole batch before any commit.
     *
     * [opId] is the same value for every doc in the batch; the listener uses
     * it to identify our own server echo and skip the editor reload.
     */
    private fun planSaveNoteWithChildren(
        noteId: String,
        trackedLines: List<NoteLine>,
        extraOpsBuilder: ExtraOpsBuilder?,
        opId: String,
    ): SavePlan {
        assertNoteStoreLoaded("saveNoteWithChildren", noteId)
        warnIfDescendantsLikelyStale("saveNoteWithChildren", noteId)

        // One pass: categorize each descendant line as real-id, sentinel
        // (with origin), or null (upstream-bug signal). Sentinels are an
        // expected "needs allocation" marker; null = some lossy path
        // wiped a real id and we can't tell where. Empty lines are
        // first-class docs and must carry an id or sentinel like any other.
        var descendantCount = 0
        var nullCount = 0
        val sentinelByOrigin = HashMap<String, Int>()
        val firstNulls = ArrayList<Pair<Int, String>>(3)
        for (idx in 1 until trackedLines.size) {
            val line = trackedLines[idx]
            descendantCount++
            val id = line.noteId
            when {
                id == null -> {
                    nullCount++
                    if (firstNulls.size < 3) firstNulls.add(idx to line.content.take(40))
                }
                NoteIdSentinel.isSentinel(id) -> {
                    val origin = NoteIdSentinel.originOf(id) ?: "unknown"
                    sentinelByOrigin.merge(origin, 1) { a, b -> a + b }
                }
            }
        }
        if (sentinelByOrigin.isNotEmpty()) {
            Log.d(TAG, "saveNoteWithChildren($noteId): sentinel origins=$sentinelByOrigin")
        }
        if (nullCount > 0) {
            // Promote to error when the ratio is bug-shaped (>= half of
            // descendant lines); warn otherwise (likely un-migrated creation site).
            val bugShaped = nullCount * 2 >= descendantCount && descendantCount >= 3
            val msg = "saveNoteWithChildren($noteId): $nullCount of $descendantCount " +
                "descendant lines have noteId=null. First: " +
                firstNulls.joinToString { "[${it.first}] '${it.second}'" }
            if (bugShaped) Log.e(TAG, msg, Throwable("noteId=null at saveNoteWithChildren entry"))
            else Log.w(TAG, msg)
        }

        val userId = requireUserId()
        val parentRef = noteRef(noteId)
        val rootContent = trackedLines[0].content.trimStart('\t')

        val linesToSaveUnreconciled = trackedLines

        // Recover noteIds that the editor lost along the way: if a null-id
        // line sits at the same tree position and has identical content to
        // an existing descendant, reuse that descendant's id. Without this
        // the save would allocate a fresh doc and orphan the real one,
        // tripping the content-drop guard even though the user's structure
        // is unchanged.
        val linesToSave = reconcileNullNoteIdsByContent(noteId, linesToSaveUnreconciled)

        // Pre-allocate refs for lines that need a fresh Firestore doc —
        // either null (bug path — id was wiped) or a sentinel (expected —
        // marks a new line from paste/split/agent/typed/etc.).
        val newRefs = mutableMapOf<Int, DocumentReference>()
        for (i in 1 until linesToSave.size) {
            val id = linesToSave[i].noteId
            if (id == null || NoteIdSentinel.isSentinel(id)) {
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
        val toDelete = existingDescendantIds - survivingIds

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

        // Root note. Skip the merge if the in-memory copy already matches what
        // we'd write — saves both the doc write and the listener echo/fresh
        // reads it would trigger across all clients.
        val rootContainedList = childrenOfLine[0].toList()
        val existingRoot = NoteStore.getRawNoteById(noteId)
        val rootUnchanged = !hasCycle &&
            existingRoot != null &&
            existingRoot.content == rootContent &&
            existingRoot.containedNotes == rootContainedList &&
            existingRoot.state == null
        if (rootUnchanged) {
            skippedUnchanged++
        } else {
            val rootData = baseNoteData(userId, rootContent).apply {
                put("containedNotes", rootContainedList)
                putAll(stampWrite(opId, existingRoot))
                if (hasCycle) {
                    put("parentNoteId", FieldValue.delete())
                    put("rootNoteId", FieldValue.delete())
                }
            }
            ops.add(BatchOp(parentRef, rootData, merge = true))
        }

        // Descendants
        for (i in 1 until linesToSave.size) {
            val content = linesToSave[i].content.trimStart('\t')

            val parentId = effectiveId(parentOfLine[i])
            val containedList = childrenOfLine[i].toList()

            if (NoteIdSentinel.isRealNoteId(linesToSave[i].noteId)) {
                val existingDescendant = NoteStore.getRawNoteById(effectiveId(i))
                val unchanged = existingDescendant != null &&
                    existingDescendant.content == content &&
                    existingDescendant.parentNoteId == parentId &&
                    existingDescendant.rootNoteId == noteId &&
                    existingDescendant.containedNotes == containedList &&
                    existingDescendant.state == null
                if (unchanged) {
                    skippedUnchanged++
                    continue
                }
                ops.add(BatchOp(
                    noteRef(effectiveId(i)),
                    mapOf(
                        "content" to content,
                        "parentNoteId" to parentId,
                        "rootNoteId" to noteId,
                        "containedNotes" to containedList,
                        "state" to null,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ) + stampWrite(opId, existingDescendant),
                    merge = true
                ))
            } else {
                ops.add(BatchOp(
                    newRefs[i]!!,
                    hashMapOf<String, Any?>(
                        "userId" to userId,
                        "content" to content,
                        "parentNoteId" to parentId,
                        "rootNoteId" to noteId,
                        "containedNotes" to containedList,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ).apply { putAll(stampWrite(opId, existing = null)) },
                    merge = false
                ))
            }
        }
        if (skippedUnchanged > 0) {
            Log.d(TAG, "saveNoteWithChildren($noteId): skipped $skippedUnchanged unchanged docs of ${linesToSave.size}, writing ${ops.size}")
        }

        // Soft-delete removed notes. Preserve parentNoteId/rootNoteId so the
        // deleted-notes view can distinguish removed child lines (have a parent)
        // from deleted top-level notes (don't).
        for (id in toDelete) {
            ops.add(BatchOp(
                noteRef(id),
                mapOf(
                    "state" to "deleted",
                    "updatedAt" to FieldValue.serverTimestamp(),
                ) + stampWrite(opId, NoteStore.getRawNoteById(id)),
                merge = true
            ))
        }

        extraOpsBuilder?.build(::effectiveId, userId)?.forEach { extra ->
            ops.add(BatchOp(extra.ref, extra.data, extra.merge))
        }

        return SavePlan(
            noteId = noteId,
            ops = ops,
            createdIds = newRefs.mapValues { it.value.id },
        )
    }

    /**
     * For each non-empty line whose [NoteLine.noteId] is null, try to recover
     * a real id from rawNotes by matching (parent id, trimmed content) against
     * existing live descendants. Preserves the original order of candidates so
     * duplicate-content siblings bind left-to-right.
     *
     * This is defensive: the editor is supposed to carry noteIds through edits,
     * but pastes, lossy re-inits, or stale snapshots have been observed to wipe
     * them. Without recovery, the save would allocate fresh docs for these
     * lines and the existing descendants would end up in the soft-delete set —
     * the content-drop guard would abort and the user would be stuck.
     */
    private fun reconcileNullNoteIdsByContent(
        rootNoteId: String,
        linesToSave: List<NoteLine>,
    ): List<NoteLine> {
        if (linesToSave.size <= 1) return linesToSave

        // Parent line-index per line, via indentation (independent of noteIds).
        val parentLineOf = IntArray(linesToSave.size)
        val stack = ArrayDeque<Pair<Int, Int>>().apply { addLast(0 to 0) } // (depth, lineIndex)
        for (i in 1 until linesToSave.size) {
            val depth = linesToSave[i].content.takeWhile { it == '\t' }.length
            val content = linesToSave[i].content.trimStart('\t')
            while (stack.size > 1 && stack.last().first >= depth) stack.removeLast()
            parentLineOf[i] = stack.last().second
            if (content.isNotEmpty()) stack.addLast(depth to i)
        }

        val byParent = NoteStore.getLiveDescendantsByParent(rootNoteId)
        if (byParent.isEmpty()) return linesToSave

        val usedIds = HashSet<String>()
        for (l in linesToSave) l.noteId?.let { usedIds.add(it) }

        val result = linesToSave.toMutableList()
        val lineIds = arrayOfNulls<String>(linesToSave.size)
        lineIds[0] = rootNoteId

        var reconciledFromNull = 0   // id was bare null — upstream bug signal
        var unrecoveredNullCount = 0
        // Detail list is lazily populated (only when we know we'll log), so the
        // common all-ids-real save path allocates nothing.
        var nullTrace: ArrayList<NullIdRecovery>? = null
        for (i in 1 until result.size) {
            val content = result[i].content.trimStart('\t')
            val existing = result[i].noteId
            // A real id means we already know which doc this line maps to.
            // A sentinel means "this is a brand-new line, allocate a fresh
            // doc downstream" — never match by content (would alias new
            // typed/pasted content to an unrelated existing sibling). Only
            // bare nulls go through content matching, since null is the
            // signal of an upstream lossy path that wiped a real id.
            if (existing != null) {
                lineIds[i] = existing
                continue
            }
            val parentId = lineIds[parentLineOf[i]]
            val candidates = parentId?.let { byParent[it] }
            var match: Note? = null
            if (candidates != null) {
                val iter = candidates.iterator()
                while (iter.hasNext()) {
                    val c = iter.next()
                    if (c.content == content && c.id !in usedIds) {
                        match = c
                        iter.remove()
                        break
                    }
                }
            }
            if (match != null) {
                usedIds.add(match.id)
                lineIds[i] = match.id
                result[i] = result[i].copy(noteId = match.id)
                reconciledFromNull++
            } else {
                unrecoveredNullCount++
            }
            val trace = nullTrace ?: ArrayList<NullIdRecovery>().also { nullTrace = it }
            trace.add(
                NullIdRecovery(
                    lineIndex = i,
                    parentLineIndex = parentLineOf[i],
                    parentId = parentId,
                    contentPreview = content.take(60),
                    recoveredId = match?.id,
                )
            )
        }

        val trace = nullTrace
        if ((reconciledFromNull > 0 || unrecoveredNullCount > 0) && trace != null) {
            // Upstream lossy path produced bare null ids on non-empty lines.
            // Log a diagnostic block (matching the content-drop guard style)
            // so the failure site can be investigated without repro. Logged at
            // ERROR level — null arrival at save means an editor or load path
            // is dropping ids and should be fixed upstream. Raise a
            // user-visible warning only when at least one was actually
            // recovered; unmatched nulls fall through to fresh-doc allocation.
            val diagnostics = buildNullIdRecoveryDiagnostics(
                rootNoteId, linesToSave, byParent, trace,
                reconciledFromNull,
            )
            Log.e(TAG, diagnostics, Throwable("null noteId reached saveNoteWithChildren"))
        }
        if (reconciledFromNull > 0) {
            NoteStore.raiseWarning(
                "Recovered $reconciledFromNull line ID(s) during save. Your content " +
                    "is safe, but an editor path may have dropped line identities — " +
                    "please double-check the note after saving."
            )
        }
        return result
    }

    private data class NullIdRecovery(
        val lineIndex: Int,
        val parentLineIndex: Int,
        val parentId: String?,
        val contentPreview: String,
        val recoveredId: String?,
    )

    /**
     * Builds a detailed diagnostic string for null-id recovery events.
     * Mirrors [buildContentDropDiagnostics] so the same debugging workflow
     * (logcat tag '$TAG') works for both. Emitted at WARN level because
     * content is typically safe after recovery.
     */
    private fun buildNullIdRecoveryDiagnostics(
        rootNoteId: String,
        linesToSave: List<NoteLine>,
        byParent: Map<String, ArrayDeque<Note>>,
        nullTrace: List<NullIdRecovery>,
        reconciledFromNull: Int,
    ): String = buildString {
        val unrecovered = nullTrace.count { it.recoveredId == null }
        appendLine("=== NULL NOTE-ID RECOVERY ===")
        appendLine("rootNoteId: $rootNoteId")
        appendLine("linesToSave: ${linesToSave.size}")
        appendLine("null-id lines: ${nullTrace.size} (recovered=$reconciledFromNull, unrecovered=$unrecovered)")

        appendLine("--- null-id line detail ---")
        for (entry in nullTrace) {
            val status = entry.recoveredId?.let { "RECOVERED → $it" } ?: "UNMATCHED (will allocate fresh doc)"
            val parent = entry.parentId ?: "<no parent id resolved>"
            appendLine(
                "  [${entry.lineIndex}] parentLine=${entry.parentLineIndex} parentId=$parent " +
                    "content='${entry.contentPreview}' → $status"
            )
        }

        appendLine("--- linesToSave ---")
        for ((i, line) in linesToSave.withIndex().take(40)) {
            val preview = line.content.take(60).replace("\n", "\\n")
            val idLabel = when {
                line.noteId == null -> "null"
                NoteIdSentinel.isSentinel(line.noteId) -> "sentinel=${line.noteId}"
                else -> line.noteId!!
            }
            appendLine("  [$i] noteId=$idLabel content='$preview'")
        }
        if (linesToSave.size > 40) appendLine("  ... (${linesToSave.size - 40} more)")

        appendLine("--- live descendants grouped by parent ---")
        if (byParent.isEmpty()) {
            appendLine("  (no live descendants in NoteStore for root $rootNoteId)")
        } else {
            for ((parentId, children) in byParent) {
                appendLine("  parent=$parentId (${children.size} unmatched candidate(s))")
                for (child in children) {
                    val preview = child.content.take(60)
                    appendLine("    child=${child.id} content='$preview'")
                }
            }
        }

        appendLine("--- NoteStore state ---")
        val storeNote = NoteStore.getNoteById(rootNoteId)
        if (storeNote != null) {
            appendLine("  parentNoteId: ${storeNote.parentNoteId ?: "null"}")
            appendLine("  rootNoteId: ${storeNote.rootNoteId ?: "null"}")
            appendLine("  state: ${storeNote.state ?: "active"}")
            val containedNotes = storeNote.containedNotes
            appendLine("  containedNotes: ${containedNotes.size} $containedNotes")
        } else {
            appendLine("  NoteStore has NO entry for $rootNoteId")
        }

        appendLine("--- Thread ---")
        appendLine("  ${Thread.currentThread().name}")
        appendLine("--- Stack trace ---")
        for (frame in Thread.currentThread().stackTrace.drop(2).take(15)) {
            appendLine("  at $frame")
        }
        appendLine("=== END NULL NOTE-ID RECOVERY ===")
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
        val trackedLines = prepareInlineEditTrackedLines(noteId, newContent, "saveNoteWithFullContent")
        saveNoteWithChildren(noteId, trackedLines, extraOpsBuilder = null).getOrThrow()
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
    ): List<NoteLine> {
        requireUserId()
        assertNoteStoreLoaded(operation, noteId)
        warnIfDescendantsLikelyStale(operation, noteId)

        val existingLines = NoteStore.getNoteLinesById(noteId)
            ?: loadNoteWithChildren(noteId).getOrThrow().lines

        return matchLinesToIds(noteId, existingLines, newContent.lines())
    }

    /**
     * Creates a new empty note.
     */
    suspend fun createNote(): Result<String> = ioLogged("createNote") {
        val userId = requireUserId()
        val ref = notesCollection.add(newNoteData(userId, "")).await()
        FirestoreUsage.recordWrite("createNote", FirestoreUsage.WriteType.SET)
        Log.d(TAG, "Note created with ID: ${ref.id}")
        ref.id
    }

    /**
     * Creates a new multi-line note with tree structure derived from indentation.
     */
    suspend fun createMultiLineNote(content: String): Result<String> = ioLogged("createMultiLineNote") body@{
        val userId = requireUserId()
        val lines = content.lines()
        val firstLine = lines.firstOrNull()?.trimStart('\t') ?: ""
        val childLines = lines.drop(1)

        if (childLines.isEmpty() || childLines.all { it.trimStart('\t').isEmpty() }) {
            val ref = notesCollection.add(newNoteData(userId, firstLine)).await()
            FirestoreUsage.recordWrite("createMultiLineNote", FirestoreUsage.WriteType.SET)
            return@body ref.id
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
            batch.set(node.ref, hashMapOf(
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
        parentRef.id
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
        val deleteData = mapOf("state" to "deleted", "updatedAt" to FieldValue.serverTimestamp())
        commitInBatches("softDeleteNote", idsToDelete.map { BatchOp(noteRef(it), deleteData, merge = true) })
    }

    /**
     * Restores a deleted note and all its descendants.
     */
    suspend fun undeleteNote(noteId: String): Result<Unit> = ioLogged("undeleteNote") {
        requireUserId()
        assertNoteStoreLoaded("undeleteNote", noteId)
        // Skip warnIfDescendantsLikelyStale: a deleted note's containedNotes
        // only lists active children, so it can't detect missing soft-deleted
        // descendants — the heuristic doesn't apply here.
        val idsToRestore = NoteStore.getAllDescendantIds(noteId) + noteId
        val restoreData = mapOf<String, Any?>("state" to null, "updatedAt" to FieldValue.serverTimestamp())
        commitInBatches("undeleteNote", idsToRestore.map { BatchOp(noteRef(it), restoreData, merge = true) })
    }

    // ── Utility operations ──────────────────────────────────────────────

    suspend fun updateShowCompleted(noteId: String, showCompleted: Boolean): Result<Unit> = ioLogged("updateShowCompleted") {
        requireUserId()
        noteRef(noteId).update(
            mapOf(
                "showCompleted" to showCompleted,
                "updatedAt" to FieldValue.serverTimestamp()
            )
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
        newLinesContent: List<String>
    ): List<NoteLine> {
        if (existingLines.isEmpty()) {
            return newLinesContent.mapIndexed { index, content ->
                NoteLine(content, if (index == 0) parentNoteId else null)
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
            NoteLine(content, withParent[index].firstOrNull())
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
     * Generates a fresh `clientOpId` for a save operation. Stamped on every
     * doc the save writes; the listener uses it to identify and suppress the
     * server echo so the editor doesn't reload over characters typed since
     * the save started.
     */
    private fun newClientOpId(): String = java.util.UUID.randomUUID().toString()

    /**
     * Per-write fields tagging this commit so the listener can identify our
     * own server echo and skip the editor reload that would otherwise clobber
     * characters typed since the save started. Also bumps `version` (monotonic
     * per-doc counter; new docs start at 1).
     */
    private fun stampWrite(opId: String, existing: Note?): Map<String, Any> = mapOf(
        FIELD_LAST_WRITER_OP_ID to opId,
        FIELD_VERSION to (existing?.version ?: 0L) + 1L,
    )

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
        private const val FIELD_LAST_WRITER_OP_ID = "lastWriterOpId"
        private const val FIELD_VERSION = "version"
    }
}

/**
 * Thrown when [NoteRepository.saveNoteWithChildren] aborts because the save
 * would soft-delete an unreasonable number of descendant notes, indicating
 * a likely race condition or stale editor state.
 */
class ContentDropAbortException(message: String) : Exception(message)
