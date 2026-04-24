package org.alkaline.taskbrain.data

import java.util.UUID

/**
 * Placeholder noteIds attached to lines that don't yet have a Firestore doc.
 *
 * A plain `null` noteId is ambiguous — it can mean "new line the user just typed"
 * (expected) or "some lossy path dropped the id" (bug). Sentinel ids disambiguate:
 *
 *  - `@typed_xxx`     — user typed a fresh line (Enter, first keystroke on empty line)
 *  - `@paste_xxx`     — pasted from clipboard
 *  - `@split_xxx`     — line split via Enter mid-line
 *  - `@agent_xxx`     — produced by the AI agent
 *  - `@directive_xxx` — produced by a directive runtime
 *  - `@surgical_xxx`  — middle line of a multi-line replace-range
 *
 * Save path: any noteId starting with the sentinel prefix is stripped and a fresh
 * Firestore doc is allocated. The origin tag is logged so fresh-doc allocations
 * can be attributed to a feature. Any `null` that reaches save is logged as an
 * error — it means an upstream path wiped a real id.
 *
 * The prefix is `@`, which never appears in Firestore's auto-generated doc ids
 * (alphanumeric only), so sentinels are trivially distinguishable at runtime.
 */
object NoteIdSentinel {
    const val PREFIX = "@"

    enum class Origin(val code: String) {
        TYPED("typed"),
        PASTE("paste"),
        SPLIT("split"),
        AGENT("agent"),
        DIRECTIVE("directive"),
        SURGICAL("surgical"),
    }

    /** Create a fresh sentinel noteId tagged with the given [origin]. */
    fun new(origin: Origin): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(10)
        return "$PREFIX${origin.code}_$token"
    }

    fun isSentinel(id: String?): Boolean = id != null && id.startsWith(PREFIX)

    /**
     * True iff [id] is a real Firestore doc id (non-null and not a sentinel).
     * Use this to decide whether a line already maps to an existing doc.
     */
    fun isRealNoteId(id: String?): Boolean = id != null && !id.startsWith(PREFIX)

    /** Return the origin code (e.g., "paste") for a sentinel id, or null if the id is real. */
    fun originOf(id: String?): String? {
        if (id == null || !id.startsWith(PREFIX)) return null
        val rest = id.substring(PREFIX.length)
        val underscore = rest.indexOf('_')
        return if (underscore >= 0) rest.substring(0, underscore) else null
    }
}
