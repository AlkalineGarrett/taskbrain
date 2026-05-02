package org.alkaline.taskbrain.data

/**
 * A line within a note tree at save time. [noteId] is always present —
 * either a real Firestore doc id or a sentinel ("new line, allocate fresh").
 * The save planner enforces this at runtime; the type system captures it
 * here so upstream paths can't silently produce nulls.
 */
data class NoteLine(
    val content: String,
    val noteId: String,
)
