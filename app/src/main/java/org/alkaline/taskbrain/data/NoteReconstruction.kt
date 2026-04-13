package org.alkaline.taskbrain.data

/**
 * Pure functions for reconstructing note content from raw Firestore documents.
 * Extracted from NoteStore for testability.
 */

/**
 * Rebuild all top-level notes with content reconstructed from their tree descendants.
 * @param rawNotes all Firestore docs indexed by ID (including descendants)
 * @return top-level notes with multi-line content
 */
fun rebuildAllNotes(rawNotes: Map<String, Note>): List<Note> {
    val topLevel = mutableListOf<Note>()
    val descendantsByRoot = mutableMapOf<String, MutableList<Note>>()

    for (note in rawNotes.values) {
        if (note.parentNoteId == null) {
            topLevel.add(note)
        }
        val rootId = note.rootNoteId
        if (rootId != null) {
            descendantsByRoot.getOrPut(rootId) { mutableListOf() }.add(note)
        }
    }

    return topLevel.map { note ->
        reconstructNoteContent(note, descendantsByRoot[note.id])
    }
}

/**
 * Rebuild only the top-level notes whose trees were affected by changes.
 * Returns a new list with affected notes updated; unaffected notes preserved by reference.
 * @param currentReconstructed the current reconstructed notes list
 * @param affectedRootIds IDs of root notes whose trees changed
 * @param rawNotes all Firestore docs indexed by ID
 * @return updated list, or [currentReconstructed] if nothing changed
 */
fun rebuildAffectedNotes(
    currentReconstructed: List<Note>,
    affectedRootIds: Set<String>,
    rawNotes: Map<String, Note>
): List<Note> {
    val descendantsByRoot = mutableMapOf<String, MutableList<Note>>()
    for (note in rawNotes.values) {
        val rootId = note.rootNoteId
        if (rootId != null && rootId in affectedRootIds) {
            descendantsByRoot.getOrPut(rootId) { mutableListOf() }.add(note)
        }
    }

    var changed = false
    val newNotes = currentReconstructed.toMutableList()

    for (rootId in affectedRootIds) {
        val rootNote = rawNotes[rootId]
        if (rootNote == null || rootNote.parentNoteId != null) {
            // Root was deleted or is not actually top-level — remove it
            val idx = newNotes.indexOfFirst { it.id == rootId }
            if (idx >= 0) {
                newNotes.removeAt(idx)
                changed = true
            }
            continue
        }

        val reconstructed = reconstructNoteContent(rootNote, descendantsByRoot[rootId])
        val idx = newNotes.indexOfFirst { it.id == rootId }
        if (idx >= 0) {
            newNotes[idx] = reconstructed
            changed = true
        } else {
            newNotes.add(reconstructed)
            changed = true
        }
    }

    return if (changed) newNotes else currentReconstructed
}

/**
 * Reconstruct a single note's content from its descendants.
 */
fun reconstructNoteContent(
    note: Note,
    descendants: List<Note>?,
): Note {
    if (note.containedNotes.isEmpty() || descendants.isNullOrEmpty()) return note

    val lines = flattenTreeToLines(note, descendants)
    return note.copy(content = lines.joinToString("\n") { it.content })
}

/**
 * IDs of all non-deleted notes whose [Note.rootNoteId] matches [noteId].
 * Pure function extracted from NoteStore for testability.
 */
fun descendantIdsOf(
    noteId: String,
    rawNotes: Map<String, Note>,
    includeDeleted: Boolean = false
): Set<String> =
    rawNotes.values
        .filter { it.rootNoteId == noteId && (includeDeleted || it.state != "deleted") }
        .map { it.id }
        .toSet()
