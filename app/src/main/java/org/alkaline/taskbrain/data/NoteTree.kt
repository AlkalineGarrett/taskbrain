package org.alkaline.taskbrain.data

import android.util.Log

private const val TAG = "NoteTree"

/**
 * Pure tree algorithms for converting between Firestore's tree structure
 * and the editor's flat indented-line format.
 *
 * Load path: Tree → flat indented lines (flattenTreeToLines)
 * Save path: Flat indented lines → tree (buildTreeFromLines)
 */

/**
 * A node in the save-ready tree. Represents a non-root descendant note.
 */
data class TreeSaveNode(
    val noteId: String?,
    val content: String,
    val parentNoteId: String,
    val containedNoteIds: List<String>,
)

/**
 * Complete tree structure ready for saving to Firestore.
 */
data class TreeSaveData(
    val rootContent: String,
    val rootContainedNoteIds: List<String>,
    val nodes: List<TreeSaveNode>,
)

/**
 * Converts a tree of notes into flat indented lines for the editor.
 *
 * The root note is a container — its content is the title line (no tabs).
 * Root's direct children appear at depth 0 (no tabs). Sub-children get 1 tab. Etc.
 * Spacers (empty strings in containedNotes) appear at the depth of their siblings.
 */
fun flattenTreeToLines(rootNote: Note, descendants: List<Note>): List<NoteLine> {
    val lookup = descendants.associateBy { it.id }
    val lines = mutableListOf<NoteLine>()

    fun dfs(note: Note, depth: Int) {
        val prefix = "\t".repeat(depth)
        lines.add(NoteLine(prefix + note.content, note.id))

        for (childId in note.containedNotes) {
            if (childId.isEmpty()) {
                lines.add(NoteLine("\t".repeat(depth + 1), null))
            } else {
                val child = lookup[childId]
                if (child != null) {
                    dfs(child, depth + 1)
                } else {
                    Log.e(TAG, "flattenTreeToLines: child $childId referenced by ${note.id} not found in descendants — line will be missing")
                }
            }
        }
    }

    // Root line (title, no tabs)
    lines.add(NoteLine(rootNote.content, rootNote.id))

    // Root's children start at depth 0 — root is a container, not an indentation level
    for (childId in rootNote.containedNotes) {
        if (childId.isEmpty()) {
            lines.add(NoteLine("", null))
        } else {
            val child = lookup[childId]
            if (child != null) {
                dfs(child, 0)
            } else {
                Log.e(TAG, "flattenTreeToLines: root child $childId not found in descendants — line will be missing")
            }
        }
    }

    return lines
}

/**
 * Converts flat indented editor lines back into tree relationships for saving.
 *
 * Tabs determine parentage: each line's parent is the nearest preceding line
 * with one fewer tab level.
 */
fun buildTreeFromLines(rootNoteId: String, trackedLines: List<NoteLine>): TreeSaveData {
    if (trackedLines.isEmpty()) {
        return TreeSaveData("", emptyList(), emptyList())
    }

    val rootContent = trackedLines[0].content.trimStart('\t')

    // Stack entries: (depth, nodeId, containedNoteIds)
    data class StackEntry(
        val depth: Int,
        val noteId: String,
        val containedNoteIds: MutableList<String>,
    )

    val stack = mutableListOf(StackEntry(0, rootNoteId, mutableListOf()))
    val nodes = mutableListOf<TreeSaveNode>()

    for (i in 1 until trackedLines.size) {
        val line = trackedLines[i]
        val depth = line.content.takeWhile { it == '\t' }.length
        val content = line.content.trimStart('\t')

        // Pop stack until top has depth < current
        while (stack.size > 1 && stack.last().depth >= depth) {
            stack.removeLast()
        }

        val parent = stack.last()

        if (content.isEmpty()) {
            // Spacer: add empty string to parent's containedNoteIds
            parent.containedNoteIds.add("")
        } else {
            val noteId = line.noteId
            val nodeContainedIds = mutableListOf<String>()

            nodes.add(
                TreeSaveNode(
                    noteId = noteId,
                    content = content,
                    parentNoteId = parent.noteId,
                    containedNoteIds = nodeContainedIds,
                )
            )

            val effectiveId = noteId ?: "placeholder_$i"
            parent.containedNoteIds.add(effectiveId)
            stack.add(StackEntry(depth, effectiveId, nodeContainedIds))
        }
    }

    return TreeSaveData(
        rootContent = rootContent,
        rootContainedNoteIds = stack[0].containedNoteIds.toList(),
        nodes = nodes.toList(),
    )
}

/**
 * Detects whether descendants are in old format (flat, no rootNoteId).
 */
fun isOldFormat(descendants: List<Note>): Boolean {
    if (descendants.isEmpty()) return false
    return descendants.none { it.rootNoteId != null }
}
