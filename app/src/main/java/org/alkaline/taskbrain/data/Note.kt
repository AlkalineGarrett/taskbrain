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
     * Monotonically-increasing per-note write counter. Bumped on every successful save.
     * Used by listeners to drop out-of-order or stale echoes (apply only if
     * version > lastAppliedVersion). Advisory — not enforced via transactions;
     * concurrent writes from two clients can both produce the same version, in
     * which case last-write-wins at Firestore. Defaults to 0 for legacy docs.
     */
    val version: Long = 0,
    /**
     * Identity stamp written by the save that produced this revision. The local
     * listener checks each echo's `lastWriterOpId` against an in-memory set of
     * in-flight saves; matches are treated as our own echo (raw cache update
     * only, no editor reload). Distinct UUID per save batch. Null on legacy
     * docs and on writes from clients predating this field.
     */
    val lastWriterOpId: String? = null,
    /**
     * Snapshot of `containedNotes` as the saving client read it before applying
     * its diff. Receiving clients use this for 3-way merge: base = this field,
     * local = client's current view, remote = the new `containedNotes`. Only
     * present on writes that staged a 3-way-merge-eligible change. Null
     * otherwise.
     */
    val containedNotesBase: List<String>? = null,
)

fun Note.firstLine(): String = content.lineSequence().firstOrNull() ?: ""
