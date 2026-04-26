package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.util.Sha256Hasher

/**
 * Utilities for hashing note content.
 */
object ContentHasher {

    /**
     * Hash the first line (name) of note content.
     */
    fun hashFirstLine(content: String): String {
        val firstLine = content.lineSequence().firstOrNull() ?: ""
        return Sha256Hasher.hash(firstLine)
    }

    /**
     * Hash everything after the first line of note content.
     */
    fun hashNonFirstLine(content: String): String {
        val lines = content.lines()
        val nonFirstLines = if (lines.size > 1) {
            lines.drop(1).joinToString("\n")
        } else {
            ""
        }
        return Sha256Hasher.hash(nonFirstLines)
    }

    /**
     * Hash a specific field of a note.
     * Used for hierarchy dependency checks.
     */
    fun hashField(note: Note, field: NoteField): String {
        val value = when (field) {
            NoteField.NAME -> note.content.lineSequence().firstOrNull() ?: ""
            NoteField.PATH -> note.path
            NoteField.MODIFIED -> note.updatedAt?.toDate()?.time?.toString() ?: ""
            NoteField.CREATED -> note.createdAt?.toDate()?.time?.toString() ?: ""
        }
        return Sha256Hasher.hash(value)
    }

    /**
     * Compute content hashes for a note based on which fields are depended on.
     */
    fun computeContentHashes(
        note: Note,
        needsFirstLine: Boolean,
        needsNonFirstLine: Boolean
    ): ContentHashes {
        return ContentHashes(
            firstLineHash = if (needsFirstLine) hashFirstLine(note.content) else null,
            nonFirstLineHash = if (needsNonFirstLine) hashNonFirstLine(note.content) else null
        )
    }
}
