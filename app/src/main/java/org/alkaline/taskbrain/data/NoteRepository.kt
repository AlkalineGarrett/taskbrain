package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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

    private fun noteRef(noteId: String): DocumentReference = notesCollection.document(noteId)

    private fun newNoteRef(): DocumentReference = notesCollection.document()

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
     * Queries descendants via rootNoteId and flattens the tree with tabs from depth.
     */
    suspend fun loadNoteWithChildren(noteId: String): Result<NoteLoadResult> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()
            val document = noteRef(noteId).get().await()
            val emptyResult = NoteLoadResult(listOf(NoteLine("", noteId)), isDeleted = false, showCompleted = true)

            if (!document.exists()) return@withContext emptyResult

            val note = document.toObject(Note::class.java)?.copy(id = noteId)
                ?: return@withContext emptyResult

            val allLines = loadNoteLines(note)

            NoteLoadResult(
                lines = allLines,
                isDeleted = note.state == "deleted",
                showCompleted = note.showCompleted,
            )
        }
    }.onFailure { Log.e(TAG, "Error loading note", it) }

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
        }.filter { it.state != "deleted" }

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
    suspend fun loadNotesWithFullContent(): Result<List<Note>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = notesCollection.whereEqualTo("userId", userId).get().await()

            val allNotes = result.mapNotNull { doc ->
                try {
                    doc.toObject(Note::class.java).copy(id = doc.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing note", e)
                    null
                }
            }.filter { it.state != "deleted" }

            val topLevelNotes = allNotes.filter { it.parentNoteId == null }
            val descendantsByRoot = allNotes
                .filter { it.rootNoteId != null }
                .groupBy { it.rootNoteId!! }

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
    }.onFailure { Log.e(TAG, "Error loading notes with full content", it) }

    suspend fun loadUserNotes(): Result<List<Note>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = notesCollection.whereEqualTo("userId", userId).get().await()

            result.mapNotNull { doc ->
                try {
                    doc.toObject(Note::class.java).copy(id = doc.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing note", e)
                    null
                }
            }.filter { it.parentNoteId == null && it.state != "deleted" }
        }
    }.onFailure { Log.e(TAG, "Error loading notes", it) }

    suspend fun loadAllUserNotes(): Result<List<Note>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = notesCollection.whereEqualTo("userId", userId).get().await()

            result.mapNotNull { doc ->
                try {
                    doc.toObject(Note::class.java).copy(id = doc.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing note", e)
                    null
                }
            }.filter { it.parentNoteId == null }
        }
    }.onFailure { Log.e(TAG, "Error loading all notes", it) }

    suspend fun loadNoteById(noteId: String): Result<Note?> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()
            val document = noteRef(noteId).get().await()
            if (!document.exists()) return@withContext null
            document.toObject(Note::class.java)?.copy(id = document.id)
        }
    }.onFailure { Log.e(TAG, "Error loading note by ID: $noteId", it) }

    suspend fun isNoteDeleted(noteId: String): Result<Boolean> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()
            val document = noteRef(noteId).get().await()
            if (!document.exists()) return@withContext false
            val note = document.toObject(Note::class.java)
            note?.state == "deleted"
        }
    }.onFailure { Log.e(TAG, "Error checking note state", it) }

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
     * Saves a note with tree structure derived from tab-indented lines.
     *
     * Computes parentNoteId and containedNotes from indentation, sets rootNoteId
     * on all descendants, and strips tabs from stored content.
     *
     * Returns a map of line indices to newly created note IDs.
     *
     * Uses batch writes (not transactions) so saves queue offline.
     * Batches are chunked at 500 operations (Firestore limit).
     */
    suspend fun saveNoteWithChildren(
        noteId: String,
        trackedLines: List<NoteLine>,
        extraOpsBuilder: ExtraOpsBuilder?,
    ): Result<Map<Int, String>> = runCatching {
        withContext(Dispatchers.IO) {
            if (trackedLines.isEmpty()) return@withContext emptyMap()

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

            // Root note
            val rootData = baseNoteData(userId, rootContent).apply {
                put("containedNotes", childrenOfLine[0].toList())
                if (hasCycle) {
                    put("parentNoteId", FieldValue.delete())
                    put("rootNoteId", FieldValue.delete())
                }
            }
            ops.add(BatchOp(parentRef, rootData, merge = true))

            // Descendants
            for (i in 1 until linesToSave.size) {
                val content = linesToSave[i].content.trimStart('\t')

                val parentId = effectiveId(parentOfLine[i])

                if (NoteIdSentinel.isRealNoteId(linesToSave[i].noteId)) {
                    ops.add(BatchOp(
                        noteRef(effectiveId(i)),
                        mapOf(
                            "content" to content,
                            "parentNoteId" to parentId,
                            "rootNoteId" to noteId,
                            "containedNotes" to childrenOfLine[i].toList(),
                            "state" to null,
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                        merge = true
                    ))
                } else {
                    ops.add(BatchOp(
                        newRefs[i]!!,
                        hashMapOf(
                            "userId" to userId,
                            "content" to content,
                            "parentNoteId" to parentId,
                            "rootNoteId" to noteId,
                            "containedNotes" to childrenOfLine[i].toList(),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                        merge = false
                    ))
                }
            }

            // Soft-delete removed notes
            for (id in toDelete) {
                ops.add(BatchOp(
                    noteRef(id),
                    mapOf(
                        "state" to "deleted",
                        "parentNoteId" to FieldValue.delete(),
                        "rootNoteId" to FieldValue.delete(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                    merge = true
                ))
            }

            extraOpsBuilder?.build(::effectiveId, userId)?.forEach { extra ->
                ops.add(BatchOp(extra.ref, extra.data, extra.merge))
            }

            commitInBatches(ops)

            newRefs.mapValues { it.value.id }
        }
    }.onFailure { Log.e(TAG, "Error saving note", it) }

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
        var reconciledFromSentinel = 0 // id was a sentinel — normal placeholder
        var unrecoveredNullCount = 0
        // Detail list is lazily populated (only when we know we'll log), so the
        // common all-ids-real save path allocates nothing.
        var nullTrace: ArrayList<NullIdRecovery>? = null
        for (i in 1 until result.size) {
            val content = result[i].content.trimStart('\t')
            val existing = result[i].noteId
            // A real id (non-sentinel, non-null) means we already know which
            // doc this line maps to. Sentinels and nulls are placeholders —
            // try to reconcile them against an existing descendant by content.
            if (NoteIdSentinel.isRealNoteId(existing)) {
                lineIds[i] = existing
                continue
            }
            val existingIsSentinel = NoteIdSentinel.isSentinel(existing)
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
                if (existingIsSentinel) reconciledFromSentinel++ else reconciledFromNull++
            }
            if (!existingIsSentinel) {
                if (match == null) unrecoveredNullCount++
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
        }

        if (reconciledFromSentinel > 0) {
            // Normal: user cut+pasted lines that matched existing siblings, so
            // the sentinel is resolved to the original id instead of allocating
            // a fresh doc. No upstream bug implied.
            Log.d(
                TAG,
                "reconcileNullNoteIdsByContent($rootNoteId): matched " +
                    "$reconciledFromSentinel sentinel line(s) to existing docs."
            )
        }
        val trace = nullTrace
        if ((reconciledFromNull > 0 || unrecoveredNullCount > 0) && trace != null) {
            // Upstream lossy path produced bare null ids on non-empty lines.
            // Log a diagnostic block (matching the content-drop guard style)
            // so the failure site can be investigated without repro. Raise a
            // user-visible warning only when at least one was actually
            // recovered — unmatched nulls will fall through to the normal
            // "fresh doc" creation path.
            val diagnostics = buildNullIdRecoveryDiagnostics(
                rootNoteId, linesToSave, byParent, trace,
                reconciledFromNull, reconciledFromSentinel,
            )
            Log.w(TAG, diagnostics)
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
        reconciledFromSentinel: Int,
    ): String = buildString {
        val unrecovered = nullTrace.count { it.recoveredId == null }
        appendLine("=== NULL NOTE-ID RECOVERY ===")
        appendLine("rootNoteId: $rootNoteId")
        appendLine("linesToSave: ${linesToSave.size}")
        appendLine("null-id lines: ${nullTrace.size} (recovered=$reconciledFromNull, unrecovered=$unrecovered)")
        appendLine("sentinel lines matched to existing docs: $reconciledFromSentinel")

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
    suspend fun saveNoteWithFullContent(noteId: String, newContent: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()

            // Prefer NoteStore in-memory data (works offline) with Firestore fallback.
            val existingLines = NoteStore.getNoteLinesById(noteId)
                ?: loadNoteWithChildren(noteId).getOrThrow().lines

            val newLinesContent = newContent.lines()
            val trackedLines = matchLinesToIds(noteId, existingLines, newLinesContent)
            saveNoteWithChildren(noteId, trackedLines, extraOpsBuilder = null).getOrThrow()

            Log.d(TAG, "Saved note with full content: $noteId (${trackedLines.size} lines)")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error saving note with full content", it) }

    /**
     * Creates a new empty note.
     */
    suspend fun createNote(): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = notesCollection.add(newNoteData(userId, "")).await()
            Log.d(TAG, "Note created with ID: ${ref.id}")
            ref.id
        }
    }.onFailure { Log.e(TAG, "Error creating note", it) }

    /**
     * Creates a new multi-line note with tree structure derived from indentation.
     */
    suspend fun createMultiLineNote(content: String): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val lines = content.lines()
            val firstLine = lines.firstOrNull()?.trimStart('\t') ?: ""
            val childLines = lines.drop(1)

            if (childLines.isEmpty() || childLines.all { it.trimStart('\t').isEmpty() }) {
                val ref = notesCollection.add(newNoteData(userId, firstLine)).await()
                return@withContext ref.id
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
            Log.d(TAG, "Multi-line note created with ID: ${parentRef.id}")
            parentRef.id
        }
    }.onFailure { Log.e(TAG, "Error creating multi-line note", it) }

    // ── Delete/restore operations ───────────────────────────────────────

    /**
     * Soft-deletes a note and all its descendants.
     */
    suspend fun softDeleteNote(noteId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()
            val idsToDelete = NoteStore.getDescendantIds(noteId) + noteId
            val deleteData = mapOf("state" to "deleted", "updatedAt" to FieldValue.serverTimestamp())
            commitInBatches(idsToDelete.map { BatchOp(noteRef(it), deleteData, merge = true) })
        }
    }.onFailure { Log.e(TAG, "Error soft-deleting note", it) }

    /**
     * Restores a deleted note and all its descendants.
     */
    suspend fun undeleteNote(noteId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()
            // Use getDescendantIds which excludes deleted notes, plus the root itself.
            // Also include all rawNotes with matching rootNoteId regardless of state,
            // since deleted descendants should also be restored.
            val idsToRestore = NoteStore.getAllDescendantIds(noteId) + noteId
            val restoreData = mapOf<String, Any?>("state" to null, "updatedAt" to FieldValue.serverTimestamp())
            commitInBatches(idsToRestore.map { BatchOp(noteRef(it), restoreData, merge = true) })
        }
    }.onFailure { Log.e(TAG, "Error undeleting note", it) }

    // ── Utility operations ──────────────────────────────────────────────

    suspend fun updateShowCompleted(noteId: String, showCompleted: Boolean): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()
            noteRef(noteId).update(
                mapOf(
                    "showCompleted" to showCompleted,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating showCompleted", it) }

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

    private suspend fun commitInBatches(ops: List<BatchOp>) {
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
        }
    }

    companion object {
        private const val TAG = "NoteRepository"
        private const val MAX_BATCH_SIZE = 500
    }
}

/**
 * Thrown when [NoteRepository.saveNoteWithChildren] aborts because the save
 * would soft-delete an unreasonable number of descendant notes, indicating
 * a likely race condition or stale editor state.
 */
class ContentDropAbortException(message: String) : Exception(message)
