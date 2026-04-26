package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Repository for managing recent tabs in Firestore.
 * Tabs are stored under `/users/{userId}/openTabs/{noteId}`.
 *
 * Reads are served from a listener-backed StateFlow: the first call lazily
 * attaches a snapshot listener to the user's `openTabs` subcollection, and
 * subsequent calls return cached data without a Firestore round-trip.
 * Per-note-open reads (formerly `enforceTabLimit`'s `.get()`) now consult
 * the cached state instead of hitting the server.
 *
 * Writes go directly to Firestore — the listener echoes the change back
 * into the cache so optimistic UI and persisted state stay in sync.
 *
 * Call [clear] on logout to detach the listener and drop cached tabs.
 */
class RecentTabsRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val cacheLock = Any()
    private val cachedTabs = MutableStateFlow<List<RecentTab>>(emptyList())
    private var listener: ListenerRegistration? = null
    private var loadDeferred: CompletableDeferred<Unit>? = null

    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    private fun openTabsCollection(userId: String) =
        db.collection("users").document(userId).collection("openTabs")

    private fun tabRef(userId: String, noteId: String): DocumentReference =
        openTabsCollection(userId).document(noteId)

    /**
     * Lazily attach the snapshot listener for the current user's openTabs
     * subcollection. Idempotent — subsequent calls are no-ops once attached.
     */
    private fun ensureListenerAttached(): CompletableDeferred<Unit> {
        synchronized(cacheLock) {
            loadDeferred?.let { if (listener != null) return it }
            val userId = auth.currentUser?.uid
                ?: throw IllegalStateException("User not signed in")

            val deferred = loadDeferred ?: CompletableDeferred<Unit>().also { loadDeferred = it }
            if (listener != null) return deferred

            // No `.limit(MAX_TABS)` here: the cache holds the full openTabs
            // list so [enforceTabLimit] can compute trim deletes from memory
            // instead of hitting Firestore on every tab open.
            listener = openTabsCollection(userId)
                .orderBy("lastAccessedAt", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        val stack = error.stackTraceToString()
                        Log.e(
                            TAG,
                            "[openTabs listener failed] userId=$userId — recent tabs " +
                                "won't refresh until next sign-in. The cached list will " +
                                "remain at the last successful snapshot.\n$stack",
                            error,
                        )
                        NoteStore.raiseWarning(
                            "Recent-tabs sync failed. Tab list may be stale until you " +
                            "restart the app. Check logcat tag '$TAG' for full diagnostic."
                        )
                        if (!deferred.isCompleted) deferred.complete(Unit)
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    // After first snapshot, skip the local-write echo: addOrUpdateTab
                    // / removeTab fire the listener twice (pending + confirmed), and
                    // rebuilding the cache list on the pending pass is wasted work.
                    if (deferred.isCompleted && snapshot.metadata.hasPendingWrites()) {
                        return@addSnapshotListener
                    }
                    val tabs = snapshot.documents.mapNotNull { doc ->
                        try {
                            doc.toObject(RecentTab::class.java)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing tab", e)
                            null
                        }
                    }
                    cachedTabs.value = tabs
                    if (!deferred.isCompleted) deferred.complete(Unit)
                }
            return deferred
        }
    }

    /**
     * Detach the listener and drop cached tabs. Intended for logout cleanup;
     * not currently wired to any signOut flow (a future improvement).
     */
    fun clear() {
        synchronized(cacheLock) {
            listener?.remove()
            listener = null
            loadDeferred = null
            cachedTabs.value = emptyList()
        }
    }

    /**
     * Adds or updates a tab. If more than [MAX_TABS] exist after adding,
     * removes the oldest. Uses the noteId as document ID for easy upsert.
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
            enforceTabLimit(userId)
            Log.d(TAG, "Tab added/updated: $noteId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error adding/updating tab", it) }

    /**
     * Gets all open tabs ordered by lastAccessedAt descending (most recent first).
     * Served from the listener cache; lazily attaches on first call.
     */
    suspend fun getOpenTabs(): Result<List<RecentTab>> = runCatching {
        ensureListenerAttached().await()
        cachedTabs.value.take(MAX_TABS)
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
     * Reads from the listener cache; if the cache hasn't loaded yet, awaits
     * the first snapshot before computing the delete set.
     */
    suspend fun removeTabsNotIn(validNoteIds: Set<String>): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            ensureListenerAttached().await()
            val staleTabs = cachedTabs.value.filter { it.noteId !in validNoteIds }
            if (staleTabs.isEmpty()) return@withContext
            val batch = db.batch()
            for (tab in staleTabs) {
                batch.delete(tabRef(userId, tab.noteId))
            }
            batch.commit().await()
            Log.d(TAG, "Removed ${staleTabs.size} stale tabs")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error removing stale tabs", it) }

    /**
     * Enforces the maximum tab limit by removing the oldest tabs. Reads from
     * the listener cache (no Firestore read per tab open). Awaits the first
     * snapshot so the count reflects writes from other devices that the local
     * client has seen at least once.
     */
    private suspend fun enforceTabLimit(userId: String) {
        ensureListenerAttached().await()
        val all = cachedTabs.value
        if (all.size <= MAX_TABS) return
        val toRemove = all.drop(MAX_TABS)
        val batch = db.batch()
        for (tab in toRemove) {
            batch.delete(tabRef(userId, tab.noteId))
        }
        batch.commit().await()
        Log.d(TAG, "Enforced tab limit, removed ${toRemove.size} old tabs")
    }

    companion object {
        private const val TAG = "RecentTabsRepository"
        const val MAX_TABS = 5
    }
}
