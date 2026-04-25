package org.alkaline.taskbrain.data

import android.util.Log

/**
 * Pure functions for reconstructing note content from raw Firestore documents.
 * Extracted from NoteStore for testability.
 *
 * Reconstruction walks the parentNoteId tree (not rootNoteId) so it auto-heals
 * data inconsistencies: stray children reachable via parentNoteId that the
 * parent's [Note.containedNotes] forgot about get appended at the end, and
 * orphan references in [Note.containedNotes] (IDs not in rawNotes, deleted, or
 * parented elsewhere) are dropped. Roots whose content required a fix are
 * reported via [RebuildResult.notesNeedingFix]; saving the note writes the
 * healed state back to Firestore.
 */

private const val TAG = "NoteReconstruction"

/**
 * Result of a rebuild pass.
 *
 * [notesNeedingFix] holds top-level note IDs whose reconstruction had to
 * auto-heal a discrepancy (orphan ref dropped or stray child appended).
 * These notes display with healed content; the UI should prompt a save.
 */
data class RebuildResult(
    val notes: List<Note>,
    val notesNeedingFix: Set<String>,
)

/**
 * Rebuild all top-level notes with content reconstructed from their
 * parentNoteId-linked descendants.
 */
fun rebuildAllNotes(rawNotes: Map<String, Note>): RebuildResult {
    val childrenByParent = indexChildrenByParent(rawNotes)
    val needsFix = mutableSetOf<String>()
    val notes = mutableListOf<Note>()
    for (note in rawNotes.values) {
        if (note.parentNoteId != null) continue
        val (reconstructed, fixed) = reconstructNoteContent(note, rawNotes, childrenByParent)
        notes.add(reconstructed)
        if (fixed) needsFix.add(note.id)
    }
    return RebuildResult(notes, needsFix)
}

/**
 * Rebuild only the top-level notes whose trees were affected by changes.
 * Returns a new list with affected notes updated; unaffected notes preserved
 * by reference. [RebuildResult.notesNeedingFix] reflects only the affected
 * roots — callers merge with previously-known needsFix state themselves.
 */
fun rebuildAffectedNotes(
    currentReconstructed: List<Note>,
    affectedRootIds: Set<String>,
    rawNotes: Map<String, Note>
): RebuildResult {
    val childrenByParent = indexChildrenByParent(rawNotes)
    var changed = false
    val newNotes = currentReconstructed.toMutableList()
    val needsFix = mutableSetOf<String>()

    for (rootId in affectedRootIds) {
        val rootNote = rawNotes[rootId]
        if (rootNote == null || rootNote.parentNoteId != null) {
            val idx = newNotes.indexOfFirst { it.id == rootId }
            if (idx >= 0) {
                newNotes.removeAt(idx)
                changed = true
            }
            continue
        }

        val (reconstructed, fixed) = reconstructNoteContent(rootNote, rawNotes, childrenByParent)
        if (fixed) needsFix.add(rootId)
        val idx = newNotes.indexOfFirst { it.id == rootId }
        if (idx >= 0) {
            newNotes[idx] = reconstructed
            changed = true
        } else {
            newNotes.add(reconstructed)
            changed = true
        }
    }

    return RebuildResult(
        notes = if (changed) newNotes else currentReconstructed,
        notesNeedingFix = needsFix,
    )
}

/**
 * Reconstruct a note by walking the parentNoteId tree.
 *
 * Ordering: each parent renders its [Note.containedNotes] entries in declared
 * order. Stray children (same parentNoteId but absent from containedNotes)
 * are appended at the parent's child level after declared entries — the
 * parent has no ordering information for them. Orphan containedNotes entries
 * (IDs not in rawNotes, deleted, or parented elsewhere) are dropped.
 *
 * Returns the per-line structure (content + noteId) and a flag indicating
 * whether any fix was applied (stray appended or orphan dropped). This is
 * the primitive the live editor uses via [NoteStore.getNoteLinesById];
 * [reconstructNoteContent] composes it into a joined-content Note.
 */
fun reconstructNoteLines(
    note: Note,
    rawNotes: Map<String, Note>,
    childrenByParent: Map<String, List<Note>>,
): Pair<List<NoteLine>, Boolean> {
    val hasRealChildren = childrenByParent[note.id]?.isNotEmpty() == true
    if (note.containedNotes.isEmpty() && !hasRealChildren) {
        return listOf(NoteLine(note.content, note.id)) to false
    }

    val lines = mutableListOf(NoteLine(note.content, note.id))
    val visited = hashSetOf(note.id)
    val fixed = renderChildrenOf(
        parent = note,
        rawNotes = rawNotes,
        childrenByParent = childrenByParent,
        lines = lines,
        childDepth = 0,
        visited = visited,
    )

    if (lines.size == 1 && note.containedNotes.isNotEmpty()) {
        val diagnosis = note.containedNotes.joinToString(", ") { id ->
            val child = rawNotes[id]
            when {
                id.isEmpty() -> "<empty-string>"
                child == null -> "$id=absent"
                child.state == "deleted" -> "$id=deleted"
                child.parentNoteId != note.id ->
                    "$id=mis-parented(parentNoteId=${child.parentNoteId})"
                else -> "$id=ok?"
            }
        }
        Log.w(
            TAG,
            "reconstructNoteLines: note ${note.id} has ${note.containedNotes.size} " +
                "declared children but rendered zero — ${diagnosis}. " +
                "strays=${childrenByParent[note.id]?.size ?: 0}, " +
                "rootContent='${note.content.take(40)}'"
        )
    }
    return lines to fixed
}

/**
 * Thin wrapper around [reconstructNoteLines] that joins the lines into a
 * single [Note.content] string. Preserves reference identity when the join
 * produces the original content, so callers' change-detection (e.g.,
 * [rebuildAffectedNotes]' unaffected-by-reference check) continues to work.
 */
fun reconstructNoteContent(
    note: Note,
    rawNotes: Map<String, Note>,
    childrenByParent: Map<String, List<Note>>,
): Pair<Note, Boolean> {
    val (lines, fixed) = reconstructNoteLines(note, rawNotes, childrenByParent)
    val joined = lines.joinToString("\n") { it.content }
    val result = if (joined == note.content) note else note.copy(content = joined)
    return result to fixed
}

/**
 * Renders all children of [parent] into [lines] at [childDepth] tabs of indent.
 * Returns true if any fix was applied at this level or deeper.
 */
private fun renderChildrenOf(
    parent: Note,
    rawNotes: Map<String, Note>,
    childrenByParent: Map<String, List<Note>>,
    lines: MutableList<NoteLine>,
    childDepth: Int,
    visited: MutableSet<String>,
): Boolean {
    var fixed = false
    val childPrefix = "\t".repeat(childDepth)
    val placed = mutableSetOf<String>()

    // Declared order from containedNotes.
    for (childId in parent.containedNotes) {
        if (childId.isEmpty()) {
            fixed = true
            Log.e(
                TAG,
                "reconstructNoteLines: empty-string entry in containedNotes of ${parent.id} — " +
                    "data corruption (post-migration, every line must have a real noteId). " +
                    "parentContent='${parent.content.take(40)}'"
            )
            continue
        }
        val child = rawNotes[childId]
        if (child == null || child.state == "deleted" || child.parentNoteId != parent.id) {
            fixed = true
            Log.w(
                TAG,
                "reconstructNoteLines: dropping orphan ref $childId from parent ${parent.id} " +
                    "(missing/deleted/mis-parented). parentContent='${parent.content.take(40)}'"
            )
            continue
        }
        if (!visited.add(child.id)) {
            fixed = true
            continue
        }
        lines.add(NoteLine(childPrefix + child.content, child.id))
        placed.add(child.id)
        if (renderChildrenOf(child, rawNotes, childrenByParent, lines, childDepth + 1, visited)) {
            fixed = true
        }
    }

    // Strays: children linked via parentNoteId but absent from containedNotes.
    // Append after declared entries — parent has no order information for them.
    val direct = childrenByParent[parent.id].orEmpty()
    for (stray in direct) {
        if (stray.id in placed) continue
        if (!visited.add(stray.id)) continue
        fixed = true
        Log.w(
            TAG,
            "reconstructNoteLines: appending stray child ${stray.id} to parent ${parent.id} " +
                "(parentNoteId points here but id not in containedNotes). " +
                "strayContent='${stray.content.take(40)}'"
        )
        lines.add(NoteLine(childPrefix + stray.content, stray.id))
        placed.add(stray.id)
        renderChildrenOf(stray, rawNotes, childrenByParent, lines, childDepth + 1, visited)
    }

    return fixed
}

/** Group non-deleted notes by their parentNoteId. */
fun indexChildrenByParent(rawNotes: Map<String, Note>): Map<String, List<Note>> {
    val result = mutableMapOf<String, MutableList<Note>>()
    for (note in rawNotes.values) {
        val parentId = note.parentNoteId ?: continue
        if (note.state == "deleted") continue
        result.getOrPut(parentId) { mutableListOf() }.add(note)
    }
    return result
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
