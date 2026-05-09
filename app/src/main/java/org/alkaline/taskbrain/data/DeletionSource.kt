package org.alkaline.taskbrain.data

import java.util.UUID

/**
 * What caused a note to be soft-deleted. Stamped (with a per-batch UUID)
 * onto every note transitioning to [NoteState.DELETED] so reconstruction,
 * undelete, and forensics can attribute deletions to their originating
 * operation.
 *
 * The format on the wire is `<label>_<uuid>` (see [Note.deletionBatchId]).
 *
 * **Vocabulary is closed.** Do not introduce free-form sources from call
 * sites — add an enum value here. `UNKNOWN` is reserved for paths that
 * haven't been instrumented yet; an `unknown_*` id appearing in data
 * means "a removal path leaked to the save layer without naming itself"
 * and should be tracked down.
 */
enum class DeletionSource(val label: String) {
    /** [NoteRepository.softDeleteNote] — whole-tree delete. */
    WHOLE_NOTE("whole-note"),

    /** Editor selection delete (Delete/Backspace over a multi-line range). */
    SELECTION_DELETE("selection-delete"),

    /** Backspace at line start, merging the line into the previous one. */
    BACKSPACE_MERGE("backspace-merge"),

    /** Delete at line end, merging the next line into this one. */
    DELETE_MERGE("delete-merge"),

    /** Paste with selection — the selection's lines are replaced. */
    PASTE_REPLACE("paste-replace"),

    /** Selection move (cut-and-paste-in-place via [EditorController.moveSelectionTo]). */
    MOVE("move"),

    /** Removal initiated by the local editor without naming a source. Drive to zero. */
    UNKNOWN("unknown");

    companion object {
        /**
         * Build a fresh `<source>_<uuid>` batch id. Different deletes within
         * the same source must each get their own id (call this once per
         * batch, then reuse the id across every doc in that batch).
         */
        fun newBatchId(source: DeletionSource): String =
            "${source.label}_${UUID.randomUUID()}"

        /** Extract the source label from a batchId, or null if malformed. */
        fun sourceOf(batchId: String?): String? {
            if (batchId == null) return null
            val sep = batchId.indexOf('_')
            if (sep <= 0 || sep == batchId.length - 1) return null
            return batchId.substring(0, sep)
        }
    }
}
