package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Repository for managing recent tabs in Firestore.
 * Tabs are stored under /users/{userId}/openTabs/{noteId}
 */
class RecentTabsRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    private fun openTabsCollection(userId: String) =
        db.collection("users").document(userId).collection("openTabs")

    private fun tabRef(userId: String, noteId: String): DocumentReference =
        openTabsCollection(userId).document(noteId)

    /**
     * Adds or updates a tab. If more than MAX_TABS exist after adding, removes the oldest.
     * Uses the noteId as document ID for easy upsert.
     */
    suspend fun addOrUpdateTab(noteId: String, displayText: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val data = mapOf(
                "noteId" to noteId,
                "displayText" to displayText,
                "lastAccessedAt" to FieldValue.serverTimestamp()
            )
            tabRef(userId, noteId).set(data).await()

            // Enforce tab limit
            enforceTabLimit(userId)
            Log.d(TAG, "Tab added/updated: $noteId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error adding/updating tab", it) }

    /**
     * Gets all open tabs ordered by lastAccessedAt descending (most recent first).
     */
    suspend fun getOpenTabs(): Result<List<RecentTab>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = openTabsCollection(userId)
                .orderBy("lastAccessedAt", Query.Direction.DESCENDING)
                .limit(MAX_TABS.toLong())
                .get()
                .await()
            result.documents.mapNotNull { doc ->
                try {
                    doc.toObject(RecentTab::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing tab", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting open tabs", it) }

    /**
     * Removes a tab (user closes it with X button).
     */
    suspend fun removeTab(noteId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            tabRef(userId, noteId).delete().await()
            Log.d(TAG, "Tab removed: $noteId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error removing tab", it) }

    /**
     * Updates just the display text for a tab (e.g., after note content changes).
     */
    suspend fun updateTabDisplayText(noteId: String, displayText: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            tabRef(userId, noteId).update("displayText", displayText).await()
            Log.d(TAG, "Tab display text updated: $noteId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating tab display text", it) }

    /**
     * Removes tabs for notes that no longer exist (cleanup stale tabs).
     */
    suspend fun removeTabsNotIn(validNoteIds: Set<String>): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = openTabsCollection(userId).get().await()
            val batch = db.batch()
            var deletedCount = 0
            for (doc in result.documents) {
                val tabNoteId = doc.getString("noteId") ?: continue
                if (tabNoteId !in validNoteIds) {
                    batch.delete(doc.reference)
                    deletedCount++
                }
            }
            if (deletedCount > 0) {
                batch.commit().await()
                Log.d(TAG, "Removed $deletedCount stale tabs")
            }
            Unit
        }
    }.onFailure { Log.e(TAG, "Error removing stale tabs", it) }

    /**
     * Enforces the maximum tab limit by removing the oldest tabs.
     */
    private suspend fun enforceTabLimit(userId: String) {
        val result = openTabsCollection(userId)
            .orderBy("lastAccessedAt", Query.Direction.DESCENDING)
            .get()
            .await()

        if (result.size() > MAX_TABS) {
            val batch = db.batch()
            result.documents.drop(MAX_TABS).forEach { doc ->
                batch.delete(doc.reference)
            }
            batch.commit().await()
            Log.d(TAG, "Enforced tab limit, removed ${result.size() - MAX_TABS} old tabs")
        }
    }

    companion object {
        private const val TAG = "RecentTabsRepository"
        const val MAX_TABS = 5
    }
}
