package org.alkaline.taskbrain.dsl.directives

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.alkaline.taskbrain.data.NoteStore

/**
 * Repository for storing and retrieving directive execution results.
 *
 * Results are stored in Firestore as a subcollection under each note:
 * `notes/{noteId}/directiveResults/{directiveHash}`.
 *
 * Reads are served from a per-note in-memory cache backed by a Firestore
 * snapshot listener. The first [getResults]/[getResult] call for a noteId
 * lazily attaches the listener; subsequent calls return cached data without
 * a Firestore round-trip. The listener also delivers updates from other
 * devices, keeping the cache fresh.
 *
 * Writes ([saveResult], [updateCollapsed]) go directly to Firestore — the
 * listener echoes the change back into the cache.
 *
 * Call [clear] on logout to detach all listeners and drop cached data.
 */
class DirectiveResultRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val cache = mutableMapOf<String, Map<String, DirectiveResult>>()
    private val listeners = mutableMapOf<String, ListenerRegistration>()
    private val cacheReady = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val cacheLock = Any()
    private val firstSnapshotDelivered = mutableSetOf<String>()

    private fun resultsCollection(noteId: String) =
        db.collection("notes").document(noteId).collection("directiveResults")

    /**
     * Lazily attach a snapshot listener for [noteId]'s directiveResults
     * subcollection. The returned deferred completes when the first snapshot
     * (or a listener error) arrives, so callers can `await()` for fresh data.
     */
    private fun ensureListener(noteId: String): CompletableDeferred<Unit> {
        synchronized(cacheLock) {
            cacheReady[noteId]?.let { return it }
            val deferred = CompletableDeferred<Unit>()
            cacheReady[noteId] = deferred
            listeners[noteId] = resultsCollection(noteId).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    val stack = error.stackTraceToString()
                    Log.e(
                        TAG,
                        "[directiveResults listener failed] noteId=$noteId — directive " +
                            "results may be stale. Errors won't auto-recover until the " +
                            "listener reattaches (next session).\n$stack",
                        error,
                    )
                    NoteStore.raiseWarning(
                        "Directive results listener failed for note $noteId. " +
                        "Saved directive results may not refresh until you reload. " +
                        "Check logcat tag '$TAG' for full diagnostic."
                    )
                    if (!deferred.isCompleted) deferred.complete(Unit)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                // Skip local-echo snapshots after the first delivery — saveResult
                // writes fire the listener twice (pending + server-confirmed), and
                // rebuilding the cache map on the pending pass is wasted work.
                synchronized(cacheLock) {
                    if (firstSnapshotDelivered.contains(noteId) &&
                        snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                }
                val map = snapshot.documents.associate { doc ->
                    doc.id to (doc.toObject(DirectiveResult::class.java) ?: DirectiveResult())
                }
                synchronized(cacheLock) {
                    cache[noteId] = map
                    firstSnapshotDelivered.add(noteId)
                }
                if (!deferred.isCompleted) deferred.complete(Unit)
            }
            return deferred
        }
    }

    /**
     * Save a directive execution result.
     */
    suspend fun saveResult(
        noteId: String,
        directiveHash: String,
        result: DirectiveResult
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val data = hashMapOf(
                "result" to result.result,
                "executedAt" to FieldValue.serverTimestamp(),
                "error" to result.error,
                "collapsed" to result.collapsed
            )
            resultsCollection(noteId).document(directiveHash).set(data).await()
            Unit
        }
    }.onFailure { Log.e(TAG, "Error saving directive result", it) }

    /**
     * Get all directive results for a note. Served from the cached snapshot
     * once the listener has delivered its first batch; subsequent calls
     * return synchronously from memory.
     */
    suspend fun getResults(noteId: String): Result<Map<String, DirectiveResult>> = runCatching {
        val deferred = ensureListener(noteId)
        deferred.await()
        synchronized(cacheLock) { cache[noteId] ?: emptyMap() }
    }.onFailure { Log.e(TAG, "Error getting directive results", it) }

    /**
     * Get a single directive result by hash.
     */
    suspend fun getResult(noteId: String, directiveHash: String): Result<DirectiveResult?> = runCatching {
        val deferred = ensureListener(noteId)
        deferred.await()
        synchronized(cacheLock) { cache[noteId]?.get(directiveHash) }
    }.onFailure { Log.e(TAG, "Error getting directive result", it) }

    /**
     * Update the collapsed state of a directive result.
     */
    suspend fun updateCollapsed(
        noteId: String,
        directiveHash: String,
        collapsed: Boolean
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            resultsCollection(noteId).document(directiveHash)
                .update("collapsed", collapsed)
                .await()
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating collapsed state", it) }

    /**
     * Delete all directive results for a note. Reads from Firestore directly
     * (not cache) so cross-device docs the listener hasn't delivered yet are
     * still cleaned up.
     */
    suspend fun deleteAllResults(noteId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val snapshot = resultsCollection(noteId).get().await()
            val batch = db.batch()
            snapshot.documents.forEach { doc ->
                batch.delete(doc.reference)
            }
            batch.commit().await()
            Unit
        }
    }.onFailure { Log.e(TAG, "Error deleting directive results", it) }

    /**
     * Detach all listeners and drop cached data. Call on logout.
     */
    fun clear() {
        synchronized(cacheLock) {
            listeners.values.forEach { it.remove() }
            listeners.clear()
            cache.clear()
            cacheReady.clear()
            firstSnapshotDelivered.clear()
        }
    }

    companion object {
        private const val TAG = "DirectiveResultRepo"
    }
}
