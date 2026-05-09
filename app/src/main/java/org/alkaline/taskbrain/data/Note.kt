package org.alkaline.taskbrain.data

import com.google.firebase.Timestamp

/** IMPORTANT: Keep in sync with docs/schema.md and web Note.ts **/
data class Note(
    val id: String = "",
    val userId: String = "",
    val parentNoteId: String? = null,
    val content: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val tags: List<String> = emptyList(),
    val containedNotes: List<String> = emptyList(),
    val state: String? = null,
    /** Unique path identifier for this note (URL-safe: alphanumeric, -, _, /). */
    val path: String = "",
    /** Root note ID for tree queries. Null for root notes, set on all descendants. */
    val rootNoteId: String? = null,
    /** Whether completed (checked) lines are shown. Per-note toggle, defaults to true. */
    val showCompleted: Boolean = true,
    /** Persistent cache for once[...] expression results. Keys are normalized AST strings, values are serialized DslValues. */
    val onceCache: Map<String, Map<String, Any>> = emptyMap(),
    /**
     * Snapshot of `containedNotes` as the saving client read it before applying
     * its diff. Receiving clients use this for 3-way merge: base = this field,
     * local = client's current view, remote = the new `containedNotes`. Only
     * present on writes that staged a 3-way-merge-eligible change. Null
     * otherwise.
     */
    val containedNotesBase: List<String>? = null,
    /**
     * Source-tagged batch identifier stamped when this note's `state` flips
     * to a removed state (DELETED). Format: `<source>_<uuid>` — e.g.
     * `whole-note_<uuid>` for `softDeleteNote`, `selection-delete_<uuid>`
     * for an editor selection-delete. Notes deleted as part of the same
     * operation share the same id; later operations get fresh ids.
     *
     * Used by reconstruction to render the structure of a deleted note
     * (children with matching id are part of the same delete batch),
     * by undelete to scope the restore set, and by forensics to attribute
     * unaccounted-for losses to their originating code path. `unknown_*`
     * is the fallback for paths that haven't been instrumented.
     *
     * Cleared (set to null) on undelete so a subsequent re-delete starts
     * a fresh batch.
     */
    val deletionBatchId: String? = null,
)

fun Note.firstLine(): String = content.lineSequence().firstOrNull() ?: ""
