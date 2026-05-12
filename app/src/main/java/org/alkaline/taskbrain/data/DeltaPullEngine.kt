package org.alkaline.taskbrain.data

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Generic signal+pull engine shared by per-channel subsystems (NoteStore,
 * AlarmRepository, ...). Owns every part of the sync protocol that doesn't
 * depend on the record type: SignalListener subscription, hydrate from
 * Firestore's persistent cache, watermarked delta pull, count() divergence
 * detection, foreground observer, scope+mutex lifecycle.
 *
 * Subsystems implement [DeltaPullSink] for the per-collection bits: how to
 * apply records to their cache, how to read the local count, how to route
 * sync warnings to the UI.
 *
 * See `docs/live-cross-platform-sync.md`.
 */
class DeltaPullEngine<T>(
    private val channel: UserDocSignal.Channel,
    /** Used as a prefix on `FirestoreUsage` operation names and log tags. */
    private val tag: String,
    /** Builds the Firestore query for the channel's collection. */
    private val collectionRef: (FirebaseFirestore, String) -> Query,
    /** Parses one DocumentSnapshot to T, or null on parse failure. */
    private val parse: (DocumentSnapshot) -> T?,
    /** Reads the `updatedAt` Timestamp off a parsed record. */
    private val updatedAt: (T) -> Timestamp?,
    private val sink: DeltaPullSink<T>,
) {
    interface DeltaPullSink<T> {
        /** Replace local cache wholesale with [items] (full pull / repair). */
        fun applyFullPull(items: List<T>)

        /** Merge [items] into local cache (incremental delta). */
        fun applyDelta(items: List<T>)

        /** Count of items currently in the local cache. */
        fun localCount(): Long

        /** Surface a sync warning to the user (count mismatch, pull failure). */
        fun raiseSyncWarning(message: String)

        /** Clear a previously-raised sync warning after self-healing. */
        fun clearSyncWarning()
    }

    private var db: FirebaseFirestore? = null
    private var userId: String? = null
    private var signalUnsubscribe: (() -> Unit)? = null
    private var foregroundObserver: LifecycleEventObserver? = null
    private var pullScope: CoroutineScope = newPullScope()
    private val pullMutex = Mutex()
    private var detectionRanThisSession = false
    private var loadDeferred: CompletableDeferred<Unit>? = null

    /** Idempotent. Attaches the channel's signal subscription, launches the
     *  initial hydrate+pull+detect chain, and registers a foreground observer
     *  for ON_START re-pulls. */
    fun start(db: FirebaseFirestore, userId: String) {
        if (signalUnsubscribe != null) return
        this.db = db
        this.userId = userId
        if (loadDeferred == null) loadDeferred = CompletableDeferred()

        SignalListener.attach(db, userId)
        signalUnsubscribe = SignalListener.subscribe(channel) {
            pullScope.launch { pullDelta("signal") }
        }

        pullScope.launch {
            hydrateFromCache()
            pullDelta("attach")
            runDetectionOnce()
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                pullScope.launch {
                    pullDelta("foreground")
                    runDetectionOnce()
                }
            }
        }
        foregroundObserver = observer
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        } catch (e: Throwable) {
            // ProcessLifecycleOwner.get() throws outside an Android runtime
            // (JVM unit tests). Pulls still fire via signal + attach; only
            // foreground re-triggers are missing in that environment.
            Log.w(tag, "ProcessLifecycleOwner observer add failed", e)
            foregroundObserver = null
        }
    }

    /** Tear down the subscription, cancel in-flight pulls, drop state.
     *  Call on logout. */
    fun clear() {
        signalUnsubscribe?.invoke()
        signalUnsubscribe = null
        foregroundObserver?.let { obs ->
            try {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
            } catch (e: Throwable) {
                // Unit-test environment; observer was never registered.
            }
        }
        foregroundObserver = null
        pullScope.cancel()
        pullScope = newPullScope()
        detectionRanThisSession = false
        loadDeferred = null
        db = null
        userId = null
    }

    /** Suspends until the initial hydrate+pull has completed (whether with
     *  data or an empty result). */
    suspend fun ensureLoaded() {
        val deferred = loadDeferred
            ?: CompletableDeferred<Unit>().also { loadDeferred = it }
        deferred.await()
    }

    /** Whether the initial pull has completed. */
    fun isLoaded(): Boolean = loadDeferred?.isCompleted == true

    /** Test seam: mark the engine as already-started + already-loaded so
     *  [start] is a no-op and [ensureLoaded] returns immediately. Used by
     *  unit tests that pre-populate the sink and don't want the engine to
     *  try a real Firestore pull. */
    @androidx.annotation.VisibleForTesting
    internal fun markLoadedForTest() {
        if (signalUnsubscribe == null) signalUnsubscribe = { /* test seam */ }
        val deferred = loadDeferred
            ?: CompletableDeferred<Unit>().also { loadDeferred = it }
        if (!deferred.isCompleted) deferred.complete(Unit)
    }

    /** Read whatever's in Firestore's persistent cache into the sink before
     *  the first server pull. The SDK fills this cache whenever a server
     *  `get()` runs; on cold start with an existing cache it restores the
     *  sink for $0. Empty/unavailable cache falls through to the pull. */
    private suspend fun hydrateFromCache() {
        val db = this.db ?: return
        val uid = this.userId ?: return
        if (sink.localCount() > 0L) return
        val snapshot = try {
            collectionRef(db, uid).get(Source.CACHE).await()
        } catch (e: Exception) {
            Log.d(tag, "hydrateFromCache skipped: ${e.message}")
            return
        }
        if (snapshot.isEmpty) return
        val items = snapshot.documents.mapNotNull { parse(it) }
        FirestoreUsage.recordRead(
            "$tag.hydrate",
            FirestoreUsage.ReadType.HYDRATE_CACHED,
            items.size,
        )
        sink.applyFullPull(items)
    }

    /** Serialized through [pullMutex] so attach + signal don't double-pull. */
    private suspend fun pullDelta(reason: String) = pullMutex.withLock {
        val db = this.db ?: return@withLock
        val uid = this.userId ?: return@withLock
        val localEmpty = sink.localCount() == 0L
        val watermark = LastSyncStorage.read(channel, uid)
        val watermarkUnset = watermark.seconds == 0L && watermark.nanoseconds == 0
        // Cold tab/process refresh: localStorage retains the watermark but
        // the in-memory sink is empty. Treat as a full pull so the delta
        // query doesn't return a near-empty result that leaves the cache
        // stale; the count() detection would catch it anyway, but at the
        // cost of an extra round-trip and warning flash.
        val isFullPull = watermarkUnset || localEmpty
        val query: Query = if (isFullPull) {
            collectionRef(db, uid)
        } else {
            collectionRef(db, uid)
                .whereGreaterThan("updatedAt", overlapBuffered(watermark))
                .orderBy("updatedAt")
        }
        val snapshot = try {
            query.get().await()
        } catch (e: Exception) {
            Log.e(tag, "pullDelta(reason=$reason, full=$isFullPull) failed", e)
            sink.raiseSyncWarning(e.message ?: "Sync failed")
            markLoaded()
            return@withLock
        }
        FirestoreUsage.recordRead(
            "$tag.pull.$reason",
            if (isFullPull) FirestoreUsage.ReadType.PULL_FULL_REPAIR
            else FirestoreUsage.ReadType.PULL_DELTA,
            snapshot.size(),
        )
        val items = snapshot.documents.mapNotNull { parse(it) }
        if (isFullPull) sink.applyFullPull(items) else sink.applyDelta(items)
        // Advance lastSync to max(updatedAt) of returned docs. Persist only
        // after a successful apply so a transient failure can't push the
        // watermark past data we haven't actually persisted.
        maxUpdatedAt(items)?.let { LastSyncStorage.write(channel, uid, it) }
        markLoaded()
    }

    private fun markLoaded() {
        loadDeferred?.let { if (!it.isCompleted) it.complete(Unit) }
    }

    /** Once-per-session count() vs local size; full-repair pull on mismatch. */
    private suspend fun runDetectionOnce() {
        if (detectionRanThisSession) return
        if (!isLoaded()) return
        detectionRanThisSession = true
        val db = this.db ?: return
        val uid = this.userId ?: return
        val serverCount = try {
            collectionRef(db, uid).count().get(AggregateSource.SERVER).await().count
        } catch (e: Exception) {
            Log.e(tag, "detection count() failed", e)
            detectionRanThisSession = false // allow retry next foreground
            return
        }
        FirestoreUsage.recordRead(
            "$tag.detection",
            FirestoreUsage.ReadType.COUNT_AGGREGATION,
            1,
        )
        val localCount = sink.localCount()
        if (serverCount == localCount) return
        Log.w(
            tag,
            "detection: count mismatch (local=$localCount, server=$serverCount); " +
                "triggering full repair pull",
        )
        sink.raiseSyncWarning(
            "Sync inconsistency: local has $localCount, server has $serverCount. Re-syncing.",
        )
        LastSyncStorage.clear(channel, uid)
        pullDelta("repair")
        sink.clearSyncWarning()
    }

    private fun overlapBuffered(watermark: Timestamp): Timestamp {
        val seconds = watermark.seconds - PULL_OVERLAP_SECONDS
        return if (seconds < 0) Timestamp(0, 0) else Timestamp(seconds, watermark.nanoseconds)
    }

    private fun maxUpdatedAt(items: List<T>): Timestamp? =
        items.maxByOrNull { item ->
            updatedAt(item)?.let { ts -> ts.seconds * 1_000_000_000L + ts.nanoseconds } ?: 0L
        }?.let { updatedAt(it) }

    private fun newPullScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        /** 5-second overlap on the watermark — covers clock skew and ties. */
        const val PULL_OVERLAP_SECONDS = 5L
    }
}
