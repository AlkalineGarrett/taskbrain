package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Repository for managing composable notes in Firestore.
 *
 * Notes form a tree: parentNoteId points to immediate parent, rootNoteId enables
 * single-query loading of all descendants. Indentation is derived from tree depth
 * (no tabs stored in Firestore content).
 *
 * Old-format notes (flat children, tabs in content) are migrated lazily on save.
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

    /**
     * Loads a note and its descendants, returning a flat list of tab-prefixed NoteLines.
     *
     * New format: single query via rootNoteId, tree flattened with tabs from depth.
     * Old format: individual child reads, content already has tabs.
     */
    suspend fun loadNoteWithChildren(noteId: String): Result<List<NoteLine>> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()
            val document = noteRef(noteId).get().await()

            if (!document.exists()) {
                return@withContext listOf(NoteLine("", noteId))
            }

            val note = document.toObject(Note::class.java)?.copy(id = noteId)
                ?: return@withContext listOf(NoteLine("", noteId))

            val allLines = loadNoteLines(note)

            // Append an empty line for user to type on, unless the note is already
            // a single empty line (new note case — the existing empty line suffices)
            if (allLines.size == 1 && allLines[0].content.isEmpty()) {
                allLines
            } else {
                allLines + NoteLine("", null)
            }
        }
    }.onFailure { Log.e(TAG, "Error loading note", it) }

    /**
     * Loads note lines using tree query (new format) or individual reads (old format).
     */
    private suspend fun loadNoteLines(note: Note): List<NoteLine> {
        // Try tree query first
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

        if (descendants.isNotEmpty()) {
            return flattenTreeToLines(note, descendants)
        }

        // Old format or no children
        if (note.containedNotes.isEmpty()) {
            return listOf(NoteLine(note.content, note.id))
        }

        // Old format: load children individually
        val parentLine = NoteLine(note.content, note.id)
        val childLines = loadOldFormatChildren(note.containedNotes)
        return listOf(parentLine) + childLines
    }

    private suspend fun loadOldFormatChildren(childIds: List<String>): List<NoteLine> =
        coroutineScope {
            childIds.map { childId -> async { loadOldFormatChild(childId) } }.awaitAll()
        }

    private suspend fun loadOldFormatChild(childId: String): NoteLine {
        if (childId.isEmpty()) return NoteLine("", null)

        return try {
            val childDoc = noteRef(childId).get().await()
            if (childDoc.exists()) {
                val content = childDoc.toObject(Note::class.java)?.content ?: ""
                NoteLine(content, childId)
            } else {
                NoteLine("", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching child note $childId", e)
            NoteLine("", null)
        }
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

            val notesById = allNotes.associateBy { it.id }
            val topLevelNotes = allNotes.filter { it.parentNoteId == null }
            val descendantsByRoot = allNotes
                .filter { it.rootNoteId != null }
                .groupBy { it.rootNoteId!! }

            topLevelNotes.map { note ->
                reconstructNoteContent(note, notesById, descendantsByRoot[note.id])
            }
        }
    }.onFailure { Log.e(TAG, "Error loading notes with full content", it) }

    /**
     * Reconstructs full content from tree or flat structure.
     * No additional queries needed — uses data already loaded.
     */
    private fun reconstructNoteContent(
        note: Note,
        allNotesById: Map<String, Note>,
        treeDescendants: List<Note>?,
    ): Note {
        if (note.containedNotes.isEmpty()) return note

        if (treeDescendants != null) {
            val lines = flattenTreeToLines(note, treeDescendants)
            return note.copy(content = lines.joinToString("\n") { it.content })
        }

        // Old format: look up children from the loaded notes
        val fullContent = buildString {
            append(note.content)
            for (childId in note.containedNotes) {
                append('\n')
                if (childId.isNotEmpty()) {
                    append(allNotesById[childId]?.content ?: "")
                }
            }
        }
        return note.copy(content = fullContent)
    }

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
     * Saves a note with tree structure derived from tab-indented lines.
     *
     * Computes parentNoteId and containedNotes from indentation, sets rootNoteId
     * on all descendants, and strips tabs from stored content.
     * Lazily migrates old-format notes to tree format.
     *
     * Returns a map of line indices to newly created note IDs.
     *
     * Note: Firestore transactions can read/write at most 500 documents.
     * Notes with >500 descendants will fail. Add batched transaction support if needed.
     */
    suspend fun saveNoteWithChildren(
        noteId: String,
        trackedLines: List<NoteLine>
    ): Result<Map<Int, String>> = runCatching {
        withContext(Dispatchers.IO) {
            if (trackedLines.isEmpty()) return@withContext emptyMap()

            val userId = requireUserId()
            val parentRef = noteRef(noteId)
            val rootContent = trackedLines[0].content.trimStart('\t')

            // Drop trailing empty lines (editor's typing line)
            val childPortion = trackedLines.drop(1).dropLastWhile { it.content.isEmpty() }
            val linesToSave = listOf(trackedLines[0]) + childPortion

            // Pre-allocate refs for new notes
            val newRefs = mutableMapOf<Int, DocumentReference>()
            for (i in 1 until linesToSave.size) {
                val content = linesToSave[i].content.trimStart('\t')
                if (linesToSave[i].noteId == null && content.isNotEmpty()) {
                    newRefs[i] = newNoteRef()
                }
            }

            fun effectiveId(lineIndex: Int): String = when {
                lineIndex == 0 -> noteId
                linesToSave[lineIndex].noteId != null -> linesToSave[lineIndex].noteId!!
                else -> newRefs[lineIndex]?.id ?: ""
            }

            // Compute tree structure from indentation
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

                if (content.isEmpty()) {
                    childrenOfLine[parentOfLine[i]].add("")
                } else {
                    childrenOfLine[parentOfLine[i]].add(effectiveId(i))
                    stack.add(StackEntry(depth, i))
                }
            }

            // Fetch existing descendants for deletion tracking
            val existingDescendantIds = fetchExistingDescendantIds(noteId)

            // Compute surviving IDs upfront for the content-drop guard
            val survivingIds = mutableSetOf<String>()
            for (i in 1 until linesToSave.size) {
                val content = linesToSave[i].content.trimStart('\t')
                if (content.isNotEmpty()) {
                    survivingIds.add(effectiveId(i))
                }
            }
            val toDelete = existingDescendantIds - survivingIds

            // Content-drop guard: if saving would soft-delete most existing
            // descendants, something has likely gone wrong (race condition,
            // stale editor state, etc.). Abort to prevent data loss.
            if (existingDescendantIds.size >= 3 && toDelete.size > existingDescendantIds.size / 2) {
                val diagnostics = buildContentDropDiagnostics(
                    noteId, trackedLines, linesToSave, existingDescendantIds, survivingIds, toDelete
                )
                Log.e(TAG, diagnostics)
                throw ContentDropAbortException(
                    "Save aborted: would delete ${toDelete.size} of " +
                        "${existingDescendantIds.size} child notes " +
                        "(saving ${linesToSave.size} lines). " +
                        "This was blocked to prevent data loss. " +
                        "Your note content is still safe — please save again. " +
                        "Connect to a computer and check logcat tag '$TAG' for diagnostics."
                )
            }

            val result = db.runTransaction { transaction ->
                // Update root
                val rootData = baseNoteData(userId, rootContent).apply {
                    put("containedNotes", childrenOfLine[0].toList())
                }
                transaction.set(parentRef, rootData, SetOptions.merge())

                // Write each descendant
                for (i in 1 until linesToSave.size) {
                    val content = linesToSave[i].content.trimStart('\t')
                    if (content.isEmpty()) continue // spacer

                    val id = effectiveId(i)
                    val parentId = effectiveId(parentOfLine[i])

                    if (linesToSave[i].noteId != null) {
                        transaction.set(
                            noteRef(id),
                            mapOf(
                                "content" to content,
                                "parentNoteId" to parentId,
                                "rootNoteId" to noteId,
                                "containedNotes" to childrenOfLine[i].toList(),
                                "updatedAt" to FieldValue.serverTimestamp(),
                            ),
                            SetOptions.merge()
                        )
                    } else {
                        transaction.set(
                            newRefs[i]!!,
                            hashMapOf(
                                "userId" to userId,
                                "content" to content,
                                "parentNoteId" to parentId,
                                "rootNoteId" to noteId,
                                "containedNotes" to childrenOfLine[i].toList(),
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp(),
                            )
                        )
                    }
                }

                // Soft-delete removed notes
                for (id in toDelete) {
                    transaction.update(
                        noteRef(id),
                        mapOf("state" to "deleted", "updatedAt" to FieldValue.serverTimestamp())
                    )
                }

                newRefs.mapValues { it.value.id }
            }.await()

            result
        }
    }.onFailure { Log.e(TAG, "Error saving note", it) }

    /**
     * Fetches IDs of all existing descendants for deletion tracking.
     * Tries tree query first (new format), falls back to containedNotes (old format).
     */
    private suspend fun fetchExistingDescendantIds(noteId: String): Set<String> {
        val userId = requireUserId()
        val descendants = notesCollection
            .whereEqualTo("rootNoteId", noteId)
            .whereEqualTo("userId", userId)
            .get().await()

        if (descendants.documents.isNotEmpty()) {
            return descendants.documents
                .filter { it.getString("state") != "deleted" }
                .map { it.id }
                .toSet()
        }

        // Old format fallback
        val rootDoc = noteRef(noteId).get().await()
        if (!rootDoc.exists()) return emptySet()

        @Suppress("UNCHECKED_CAST")
        val containedNotes = rootDoc.get("containedNotes") as? List<String> ?: emptyList()
        return containedNotes.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Saves a note with full multi-line content, properly handling child notes.
     * Used for inline editing of notes within view directives.
     */
    suspend fun saveNoteWithFullContent(noteId: String, newContent: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()

            // Load existing structure (tree-aware)
            val existingLines = loadNoteWithChildren(noteId).getOrThrow()
            // Remove trailing empty line that loadNoteWithChildren appends for the editor
            val existingLinesNoTrailing = if (existingLines.size > 1 && existingLines.last().content.isEmpty()) {
                existingLines.dropLast(1)
            } else {
                existingLines
            }

            val newLinesContent = newContent.lines()
            val trackedLines = matchLinesToIds(noteId, existingLinesNoTrailing, newLinesContent)
            saveNoteWithChildren(noteId, trackedLines).getOrThrow()

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

                if (lineContent.isEmpty()) {
                    parent.children.add("")
                } else {
                    val ref = newNoteRef()
                    val nodeChildren = mutableListOf<String>()
                    nodes.add(NodeInfo(ref, lineContent, parent.id, nodeChildren))
                    parent.children.add(ref.id)
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
            val userId = requireUserId()

            val idsToDelete = mutableSetOf(noteId)

            // New-format descendants
            val descendants = notesCollection
                .whereEqualTo("rootNoteId", noteId)
                .whereEqualTo("userId", userId)
                .get().await()
            for (doc in descendants) {
                idsToDelete.add(doc.id)
            }

            // Old-format fallback
            if (descendants.isEmpty()) {
                val rootDoc = noteRef(noteId).get().await()
                if (rootDoc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val containedNotes = rootDoc.get("containedNotes") as? List<String> ?: emptyList()
                    for (childId in containedNotes) {
                        if (childId.isNotEmpty()) idsToDelete.add(childId)
                    }
                }
            }

            val batch = db.batch()
            for (id in idsToDelete) {
                batch.update(
                    noteRef(id),
                    mapOf("state" to "deleted", "updatedAt" to FieldValue.serverTimestamp())
                )
            }
            batch.commit().await()
            Unit
        }
    }.onFailure { Log.e(TAG, "Error soft-deleting note", it) }

    /**
     * Restores a deleted note and all its descendants.
     */
    suspend fun undeleteNote(noteId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()

            val idsToRestore = mutableSetOf(noteId)

            // Deleted descendants still have rootNoteId set
            val descendants = notesCollection
                .whereEqualTo("rootNoteId", noteId)
                .whereEqualTo("userId", userId)
                .get().await()
            for (doc in descendants) {
                idsToRestore.add(doc.id)
            }

            // Old-format fallback
            if (descendants.isEmpty()) {
                val rootDoc = noteRef(noteId).get().await()
                if (rootDoc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val containedNotes = rootDoc.get("containedNotes") as? List<String> ?: emptyList()
                    for (childId in containedNotes) {
                        if (childId.isNotEmpty()) idsToRestore.add(childId)
                    }
                }
            }

            val batch = db.batch()
            for (id in idsToRestore) {
                batch.update(
                    noteRef(id),
                    mapOf("state" to null, "updatedAt" to FieldValue.serverTimestamp())
                )
            }
            batch.commit().await()
            Unit
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

    suspend fun updateLastAccessed(noteId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            requireUserId()
            noteRef(noteId).update("lastAccessedAt", FieldValue.serverTimestamp()).await()
            Log.d(TAG, "Updated lastAccessedAt for note: $noteId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating lastAccessedAt", it) }

    /**
     * Two-phase line matching: exact content match first, then positional fallback.
     * Preserves note IDs across edits for alarm/DSL reference stability.
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

        val contentToOldIndices = mutableMapOf<String, MutableList<Int>>()
        existingLines.forEachIndexed { index, line ->
            contentToOldIndices.getOrPut(line.content) { mutableListOf() }.add(index)
        }

        val newIds = arrayOfNulls<String>(newLinesContent.size)
        val oldConsumed = BooleanArray(existingLines.size)

        // Exact matches
        newLinesContent.forEachIndexed { index, content ->
            val indices = contentToOldIndices[content]
            if (!indices.isNullOrEmpty()) {
                val oldIdx = indices.removeAt(0)
                newIds[index] = existingLines[oldIdx].noteId
                oldConsumed[oldIdx] = true
            }
        }

        // Positional matches for modifications
        newLinesContent.forEachIndexed { index, _ ->
            if (newIds[index] == null) {
                if (index < existingLines.size && !oldConsumed[index]) {
                    newIds[index] = existingLines[index].noteId
                    oldConsumed[index] = true
                }
            }
        }

        val trackedLines = newLinesContent.mapIndexed { index, content ->
            NoteLine(content, newIds[index])
        }.toMutableList()

        if (trackedLines.isNotEmpty() && trackedLines[0].noteId != parentNoteId) {
            trackedLines[0] = trackedLines[0].copy(noteId = parentNoteId)
        }

        return trackedLines
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
        appendLine("linesToSave (after trailing-empty drop): ${linesToSave.size}")
        appendLine("existingDescendants: ${existingDescendantIds.size} $existingDescendantIds")
        appendLine("survivingIds: ${survivingIds.size} $survivingIds")
        appendLine("toDelete: ${toDelete.size} $toDelete")
        appendLine("--- trackedLines detail ---")
        for ((i, line) in originalTrackedLines.withIndex()) {
            val preview = line.content.take(60).replace("\n", "\\n")
            appendLine("  [$i] noteId=${line.noteId ?: "null"} content='$preview'")
        }
        appendLine("--- NoteStore state ---")
        val storeNote = NoteStore.getNoteById(noteId)
        if (storeNote != null) {
            val storeLines = storeNote.content.lines()
            appendLine("  NoteStore has ${storeLines.size} lines for this note")
            for ((i, line) in storeLines.withIndex()) {
                val preview = line.take(60).replace("\n", "\\n")
                appendLine("  [$i] '$preview'")
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

    companion object {
        private const val TAG = "NoteRepository"
    }
}

/**
 * Thrown when [NoteRepository.saveNoteWithChildren] aborts because the save
 * would soft-delete an unreasonable number of descendant notes, indicating
 * a likely race condition or stale editor state.
 */
class ContentDropAbortException(message: String) : Exception(message)
