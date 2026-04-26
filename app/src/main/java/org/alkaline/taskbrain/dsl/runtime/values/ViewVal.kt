package org.alkaline.taskbrain.dsl.runtime.values

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note

/**
 * A view value representing inline note content.
 * Created by the view() function to display notes inline with the directive.
 *
 * View functionality for rendering notes inline.
 *
 * Display: Notes' content is inlined as raw text, separated by dividers.
 * No path headers are shown because the first line of each note serves as its title.
 *
 * Recursion: Viewed notes' directives also execute, including nested view directives.
 * Circular dependency: If a view creates a cycle (A views B views A), an error is shown.
 *
 * @param notes The source notes being viewed
 * @param renderedContents The rendered content for each note (directives evaluated).
 *        If null, falls back to raw note content (for backward compatibility).
 */
data class ViewVal(
    val notes: List<Note>,
    val renderedContents: List<String>? = null
) : DslValue() {
    override val typeName: String = "view"

    /**
     * Display string shows the rendered content of viewed notes.
     * Uses renderedContents if available (directives evaluated), otherwise raw content.
     */
    override fun toDisplayString(): String {
        val contents = renderedContents ?: notes.map { it.content }
        return when (contents.size) {
            0 -> "[empty view]"
            1 -> contents.first()
            else -> contents.joinToString("\n---\n")
        }
    }

    override fun serializeValue(): Any = mapOf(
        "notes" to notes.map { note ->
            mapOf(
                "id" to note.id,
                "userId" to note.userId,
                "path" to note.path,
                "content" to note.content,
                "createdAt" to note.createdAt?.toDate()?.time,
                "updatedAt" to note.updatedAt?.toDate()?.time,
            )
        },
        "renderedContents" to renderedContents
    )

    /** Number of notes in the view. */
    val size: Int get() = notes.size

    /** Check if the view is empty. */
    fun isEmpty(): Boolean = notes.isEmpty()

    /** Check if the view is not empty. */
    fun isNotEmpty(): Boolean = notes.isNotEmpty()

    companion object {
        /**
         * Deserialize a ViewVal from a Firestore map.
         */
        @Suppress("UNCHECKED_CAST")
        fun deserialize(map: Map<String, Any?>): ViewVal {
            val notesData = map["notes"] as? List<Map<String, Any?>> ?: emptyList()
            val notes = notesData.map { noteMap ->
                val createdAtMillis = noteMap["createdAt"] as? Long
                val updatedAtMillis = noteMap["updatedAt"] as? Long

                Note(
                    id = noteMap["id"] as? String ?: "",
                    userId = noteMap["userId"] as? String ?: "",
                    path = noteMap["path"] as? String ?: "",
                    content = noteMap["content"] as? String ?: "",
                    createdAt = createdAtMillis?.let { Timestamp(java.util.Date(it)) },
                    updatedAt = updatedAtMillis?.let { Timestamp(java.util.Date(it)) },
                )
            }
            val renderedContents = map["renderedContents"] as? List<String>
            return ViewVal(notes, renderedContents)
        }
    }
}
