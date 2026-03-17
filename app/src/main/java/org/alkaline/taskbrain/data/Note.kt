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
    val lastAccessedAt: Timestamp? = null,
    val tags: List<String> = emptyList(),
    val containedNotes: List<String> = emptyList(),
    val state: String? = null,
    /** Unique path identifier for this note (URL-safe: alphanumeric, -, _, /). */
    val path: String = "",
    /** Root note ID for tree queries. Null for root notes, set on all descendants. */
    val rootNoteId: String? = null,
    /** Whether completed (checked) lines are shown. Per-note toggle, defaults to true. */
    val showCompleted: Boolean = true,
)
