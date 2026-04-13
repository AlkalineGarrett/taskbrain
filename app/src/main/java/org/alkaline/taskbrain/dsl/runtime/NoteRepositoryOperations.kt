package org.alkaline.taskbrain.dsl.runtime

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.NoteStore

/**
 * Implementation of NoteOperations that uses Firebase Firestore directly.
 *
 * This class provides the note mutation capabilities needed by Mindl
 * (new, maybe_new, append, property setting) by directly interacting
 * with Firestore.
 */
class NoteRepositoryOperations(
    private val db: FirebaseFirestore,
    private val userId: String
) : NoteOperations {

    private val notesCollection get() = db.collection("notes")

    /**
     * Convert a Firestore document to a Note, setting the id from the document.
     */
    private fun com.google.firebase.firestore.DocumentSnapshot.toNote(): Note? =
        toObject(Note::class.java)?.copy(id = id)

    /**
     * Update specific fields on a note and return the updated note.
     * Avoids a Firestore re-read that stalls offline (server-first timeout).
     */
    private suspend fun updateAndFetch(
        noteId: String,
        content: String? = null,
        path: String? = null
    ): Note {
        val updates = buildMap<String, Any> {
            content?.let { put("content", it) }
            path?.let { put("path", it) }
            put("updatedAt", FieldValue.serverTimestamp())
        }
        notesCollection.document(noteId).update(updates).await()

        val base = NoteStore.getRawNoteById(noteId)
            ?: throw NoteOperationException("Note not found after update: $noteId")
        return base.copy(
            content = content ?: base.content,
            path = path ?: base.path,
            updatedAt = Timestamp.now()
        )
    }

    override suspend fun createNote(path: String, content: String): Note {
        // Check if path already exists
        if (noteExistsAtPath(path)) {
            throw NoteOperationException("Note already exists at path: $path")
        }

        val noteRef = notesCollection.document()
        val noteData = hashMapOf(
            "userId" to userId,
            "content" to content,
            "path" to path,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        noteRef.set(noteData).await()

        // Return the created note
        return Note(
            id = noteRef.id,
            userId = userId,
            path = path,
            content = content,
            createdAt = Timestamp.now(),
            updatedAt = Timestamp.now()
        )
    }

    override suspend fun getNoteById(noteId: String): Note? {
        NoteStore.getRawNoteById(noteId)?.let { return it }
        val doc = notesCollection.document(noteId).get().await()
        return if (doc.exists()) doc.toNote() else null
    }

    override suspend fun findByPath(path: String): Note? {
        NoteStore.getNoteByPath(path)?.let { return it }
        val query = notesCollection
            .whereEqualTo("userId", userId)
            .whereEqualTo("path", path)
            .limit(1)
            .get()
            .await()

        return query.documents.firstOrNull()?.toNote()
    }

    override suspend fun noteExistsAtPath(path: String): Boolean =
        findByPath(path) != null

    override suspend fun updatePath(noteId: String, newPath: String): Note {
        // Check if new path is already taken by another note
        val existingAtPath = findByPath(newPath)
        if (existingAtPath != null && existingAtPath.id != noteId) {
            throw NoteOperationException("Path already in use: $newPath")
        }

        return updateAndFetch(noteId, path = newPath)
    }

    override suspend fun updateContent(noteId: String, newContent: String): Note {
        return updateAndFetch(noteId, content = newContent)
    }

    override suspend fun appendToNote(noteId: String, text: String): Note {
        // Get current content
        val note = getNoteById(noteId)
            ?: throw NoteOperationException("Note not found: $noteId")

        // Append text (with newline if content exists)
        val newContent = if (note.content.isEmpty()) text else "${note.content}\n$text"

        return updateAndFetch(noteId, content = newContent)
    }

    companion object {
        /**
         * Create a NoteRepositoryOperations instance if user is authenticated.
         * Returns null if no user is signed in.
         */
        fun createIfAuthenticated(db: FirebaseFirestore, userId: String?): NoteRepositoryOperations? {
            return userId?.let { NoteRepositoryOperations(db, it) }
        }
    }
}
