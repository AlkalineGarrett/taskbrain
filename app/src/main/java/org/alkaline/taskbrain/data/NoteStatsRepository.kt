package org.alkaline.taskbrain.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-user view tracking. Lives apart from the note doc so writes don't echo through
 * the notes collection listener and trigger the directive cache invalidation path.
 *
 * Listener-backed cache, singleton-on-companion. See [AlarmRepository] for
 * the pattern (lazy attach, first-instance binding, [clear] required in
 * test `@Before`).
 */
class NoteStatsRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    private fun statsCollection(userId: String) =
        db.collection("users").document(userId).collection("noteStats")

    private fun statsRef(userId: String, noteId: String) =
        statsCollection(userId).document(noteId)

    private fun ensureListenerAttached(): CompletableDeferred<Unit> {
        synchronized(cacheLock) {
            if (listener != null) return loadDeferred!!
            val userId = auth.currentUser?.uid
                ?: throw IllegalStateException("User not signed in")
            val deferred = loadDeferred ?: CompletableDeferred<Unit>().also { loadDeferred = it }

            listener = statsCollection(userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(
                            TAG,
                            "[noteStats listener failed] userId=$userId\n${error.stackTraceToString()}",
                            error,
                        )
                        NoteStore.raiseWarning(
                            "Note view-stats sync failed. Sort orders that depend on view " +
                                "history (Recent, Decayed, Consistency) may be stale until you " +
                                "restart the app. Check logcat tag '$TAG'."
                        )
                        if (!deferred.isCompleted) deferred.complete(Unit)
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    val firstAlreadyDelivered = deferred.isCompleted
                    val isLocalEcho = firstAlreadyDelivered && snapshot.metadata.hasPendingWrites()
                    val fromCache = snapshot.metadata.isFromCache
                    val type = when {
                        !firstAlreadyDelivered && fromCache -> FirestoreUsage.ReadType.LISTENER_INITIAL_CACHED
                        !firstAlreadyDelivered -> FirestoreUsage.ReadType.LISTENER_INITIAL_FRESH
                        isLocalEcho -> FirestoreUsage.ReadType.LISTENER_LOCAL_ECHO
                        fromCache -> FirestoreUsage.ReadType.LISTENER_UPDATE_CACHED
                        else -> FirestoreUsage.ReadType.LISTENER_UPDATE_FRESH
                    }
                    val docCount = if (firstAlreadyDelivered) snapshot.documentChanges.size else snapshot.size()
                    FirestoreUsage.recordRead("NoteStatsRepo.listener", type, docCount)
                    _cachedStats.value = snapshot.documents.associate { doc ->
                        doc.id to parseStats(doc.data ?: emptyMap())
                    }
                    if (!deferred.isCompleted) deferred.complete(Unit)
                }
            return deferred
        }
    }

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
            FirestoreUsage.recordWrite("recordView", FirestoreUsage.WriteType.SET)
            Unit
        }
    }.onFailure { Log.e(TAG, "Error recording view", it) }

    suspend fun loadAllNoteStats(): Result<Map<String, NoteStats>> = runCatching {
        ensureListenerAttached().await()
        _cachedStats.value
    }.onFailure { Log.e(TAG, "Error loading note stats", it) }

    @Suppress("UNCHECKED_CAST")
    private fun parseStats(data: Map<String, Any?>): NoteStats = NoteStats(
        lastAccessedAt = data["lastAccessedAt"] as? Timestamp,
        viewCount = (data["viewCount"] as? Long ?: 0).toInt(),
        viewedDays = (data["viewedDays"] as? Map<String, Boolean>) ?: emptyMap(),
    )

    private fun todayLocalDate(): String =
        LocalDate.ofInstant(Instant.now(), ZoneId.systemDefault()).toString()

    companion object {
        private const val TAG = "NoteStatsRepository"

        private val cacheLock = Any()
        private val _cachedStats = MutableStateFlow<Map<String, NoteStats>>(emptyMap())
        private var listener: ListenerRegistration? = null
        private var loadDeferred: CompletableDeferred<Unit>? = null

        /**
         * Detach the listener and drop the cache. Call on sign-out so the
         * next signed-in user doesn't see the previous user's stats.
         */
        fun clear() {
            synchronized(cacheLock) {
                listener?.remove()
                listener = null
                loadDeferred = null
                _cachedStats.value = emptyMap()
            }
        }

        /** Test seam: populate the cache directly, bypassing the listener. */
        @VisibleForTesting
        internal fun injectCacheForTest(stats: Map<String, NoteStats>) {
            synchronized(cacheLock) {
                if (listener == null) listener = ListenerRegistration { /* test seam */ }
                val deferred = loadDeferred ?: CompletableDeferred<Unit>().also { loadDeferred = it }
                _cachedStats.value = stats
                if (!deferred.isCompleted) deferred.complete(Unit)
            }
        }
    }
}
