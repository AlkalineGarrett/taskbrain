package org.alkaline.taskbrain.data

import com.google.firebase.Timestamp

/** IMPORTANT: Keep in sync with docs/schema.md and web NoteStats.ts */
data class NoteStats(
    val lastAccessedAt: Timestamp? = null,
    val viewCount: Int = 0,
    /** YYYY-MM-DD keys in the device's local timezone, so a midnight crossing reads as the user-perceived calendar day. */
    val viewedDays: Map<String, Boolean> = emptyMap(),
)
