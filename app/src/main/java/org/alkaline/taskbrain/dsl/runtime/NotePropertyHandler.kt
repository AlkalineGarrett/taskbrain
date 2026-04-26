package org.alkaline.taskbrain.dsl.runtime

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.values.NoteVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.UndefinedVal
import java.time.ZoneId

/**
 * Handles property access and assignment for NoteVal.
 *
 * Supported readable properties:
 * - `id`: String - Firebase ID
 * - `path`: String - Unique path identifier
 * - `name`: String - First line of content
 * - `created`: DateTime - Creation timestamp
 * - `modified`: DateTime - Last modification timestamp
 * - `up`: Note - Parent note (1 level up)
 * - `root`: Note - Root ancestor (top-level note with no parent)
 *
 * Writable properties:
 * - `path`: String - Unique path identifier
 * - `name`: String - First line of content (updates content)
 */
object NotePropertyHandler {

    /**
     * Get a property value from the note.
     */
    fun getProperty(noteVal: NoteVal, property: String, env: Environment): DslValue {
        val note = noteVal.note
        return when (property) {
            "id" -> StringVal(note.id)
            "path" -> StringVal(note.path)
            "name" -> StringVal(note.content.lines().firstOrNull() ?: "")
            "created" -> note.createdAt?.let {
                DateTimeVal(it.toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime())
            } ?: throw ExecutionException("Note has no created date")
            "modified" -> note.updatedAt?.let {
                DateTimeVal(it.toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime())
            } ?: throw ExecutionException("Note has no modified date")
            "up" -> getUp(note, 1, env)
            "root" -> getRoot(noteVal, env)
            else -> throw ExecutionException("Unknown property '$property' on note")
        }
    }

    /**
     * Set a property value on the note.
     */
    fun setProperty(noteVal: NoteVal, property: String, value: DslValue, env: Environment) {
        val note = noteVal.note
        val ops = env.getNoteOperations()
            ?: throw ExecutionException(
                "Cannot modify note properties: note operations not available"
            )

        when (property) {
            "path" -> {
                val newPath = (value as? StringVal)?.value
                    ?: throw ExecutionException("path must be a string")
                val updatedNote = kotlinx.coroutines.runBlocking {
                    ops.updatePath(note.id, newPath)
                }
                env.registerMutation(NoteMutation(note.id, updatedNote, MutationType.PATH_CHANGED))
            }
            "name" -> {
                val newName = (value as? StringVal)?.value
                    ?: throw ExecutionException("name must be a string")
                // Fetch fresh content from Firestore to avoid stale cache issues
                val freshNote = kotlinx.coroutines.runBlocking {
                    ops.getNoteById(note.id)
                } ?: throw ExecutionException("Note not found: ${note.id}")
                // Update the first line of content while preserving the rest
                val lines = freshNote.content.lines()
                val newContent = if (lines.isEmpty() || lines.size == 1) {
                    newName
                } else {
                    (listOf(newName) + lines.drop(1)).joinToString("\n")
                }
                val updatedNote = kotlinx.coroutines.runBlocking {
                    ops.updateContent(note.id, newContent)
                }
                env.registerMutation(NoteMutation(note.id, updatedNote, MutationType.CONTENT_CHANGED))
            }
            "id", "created", "modified" -> {
                throw ExecutionException("Cannot set read-only property '$property' on note")
            }
            else -> throw ExecutionException("Unknown property '$property' on note")
        }
    }

    /**
     * Navigate up the note hierarchy by the specified number of levels.
     * Returns UndefinedVal if the requested ancestor doesn't exist.
     */
    internal fun getUp(note: Note, levels: Int, env: Environment): DslValue {
        if (levels == 0) return NoteVal(note)
        val parent = env.getParentNote(note) ?: return UndefinedVal
        return if (levels == 1) {
            NoteVal(parent)
        } else {
            getUp(parent, levels - 1, env)
        }
    }

    /**
     * Navigate to the root ancestor (top-level note with no parent).
     * If this note has no parent, returns itself.
     */
    private fun getRoot(noteVal: NoteVal, env: Environment): NoteVal {
        val parent = env.getParentNote(noteVal.note) ?: return noteVal
        return getRoot(NoteVal(parent), env)
    }
}
