package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-user view tracking. Lives apart from the note doc so writes don't echo through
 * the notes collection listener and trigger the directive cache invalidation path.
 */
class NoteStatsRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    private fun statsRef(userId: String, noteId: String) =
        db.collection("users").document(userId).collection("noteStats").document(noteId)

    /** Embedded `[view ...]` directive renders MUST NOT call this — only top-level note opens. */
    suspend fun recordView(noteId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val today = todayLocalDate()
            val updates = mapOf(
                "lastAccessedAt" to FieldValue.serverTimestamp(),
                "viewCount" to FieldValue.increment(1),
                "viewedDays" to mapOf(today to true),
            )
            statsRef(userId, noteId).set(updates, SetOptions.merge()).await()
            Unit
        }
    }.onFailure { Log.e(TAG, "Error recording view", it) }

    @Suppress("UNCHECKED_CAST")
    suspend fun loadAllNoteStats(): Result<Map<String, NoteStats>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val snapshot = db.collection("users").document(userId)
                .collection("noteStats").get().await()
            snapshot.associate { doc ->
                doc.id to NoteStats(
                    lastAccessedAt = doc.getTimestamp("lastAccessedAt"),
                    viewCount = (doc.getLong("viewCount") ?: 0).toInt(),
                    viewedDays = (doc.get("viewedDays") as? Map<String, Boolean>) ?: emptyMap(),
                )
            }
        }
    }.onFailure { Log.e(TAG, "Error loading note stats", it) }

    private fun todayLocalDate(): String =
        LocalDate.ofInstant(Instant.now(), ZoneId.systemDefault()).toString()

    companion object {
        private const val TAG = "NoteStatsRepository"
    }
}
