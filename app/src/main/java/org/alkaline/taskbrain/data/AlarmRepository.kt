package org.alkaline.taskbrain.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Repository for managing alarms under /users/{userId}/alarms/{alarmId}.
 * Reads are served from a listener-backed [StateFlow] (lazy attach,
 * full-collection cache, in-memory filters); writes go directly to
 * Firestore. See `docs/firestore-efficiency.md` Principle 1.
 *
 * The listener + cache live on the companion object so every instance
 * shares one listener — production has 7+ construction sites (ViewModels,
 * receivers, services) and a per-instance listener would mean each pays
 * its own INITIAL_FRESH and maintains a parallel network connection.
 * Receivers running in separate OS processes still get their own JVM
 * (and thus their own companion-singleton); for those, prefer the
 * `*FromServer` one-shot reads instead of the cache.
 *
 * The first instance to call `ensureListenerAttached()` binds its
 * `db`/`auth` to the companion's listener; subsequent instances with
 * different references are silently ignored. In production all instances
 * share the singleton `FirebaseFirestore`/`FirebaseAuth` so this is
 * irrelevant. In tests, call [clear] in `@Before` to reset the
 * companion's listener+cache before injecting fresh mocks.
 */
class AlarmRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    private fun alarmsCollection(userId: String) =
        db.collection("users").document(userId).collection("alarms")

    private fun alarmRef(userId: String, alarmId: String): DocumentReference =
        alarmsCollection(userId).document(alarmId)

    private fun newAlarmRef(userId: String): DocumentReference =
        alarmsCollection(userId).document()

    private fun ensureListenerAttached(): CompletableDeferred<Unit> {
        synchronized(cacheLock) {
            if (listener != null) return loadDeferred!!
            val userId = auth.currentUser?.uid
                ?: throw IllegalStateException("User not signed in")
            val deferred = loadDeferred ?: CompletableDeferred<Unit>().also { loadDeferred = it }

            listener = alarmsCollection(userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(
                            TAG,
                            "[alarms listener failed] userId=$userId\n${error.stackTraceToString()}",
                            error,
                        )
                        NoteStore.raiseWarning(
                            "Alarm sync failed. Alarm list and triggers may be stale " +
                                "until you restart the app. Check logcat tag '$TAG'."
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
                    FirestoreUsage.recordRead("AlarmRepo.listener", type, docCount)
                    // Process local-echo snapshots too: there's no separate
                    // optimistic-update path for the alarm cache, so the
                    // echo is the only signal that a local write happened
                    // (matters offline, where server confirmation may take
                    // a while). Same trade-off as RecentTabsRepository.
                    _cachedAlarms.value = snapshot.documents.mapNotNull { doc ->
                        try {
                            mapToAlarm(doc.id, doc.data ?: emptyMap())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing alarm", e)
                            null
                        }
                    }
                    if (!deferred.isCompleted) deferred.complete(Unit)
                }
            return deferred
        }
    }

    /** Mints a fresh alarm doc ID client-side, no Firestore write. */
    fun newAlarmId(): String = newAlarmRef(requireUserId()).id

    /**
     * Builds a batch op for an alarm create. Pair with [newAlarmId] to embed
     * the ID in a directive before the doc lands.
     */
    fun buildCreateBatchOp(alarm: Alarm): NoteRepository.BatchExtraOp {
        val userId = requireUserId()
        return NoteRepository.BatchExtraOp(
            ref = alarmRef(userId, alarm.id),
            data = createAlarmData(alarm.copy(userId = userId)),
            merge = false,
        )
    }

    private fun createAlarmData(alarm: Alarm): MutableMap<String, Any?> =
        alarmToMap(alarm).toMutableMap().apply {
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }

    /**
     * Creates a new alarm.
     * Returns the ID of the created alarm.
     */
    suspend fun createAlarm(alarm: Alarm): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = newAlarmRef(userId)
            ref.set(createAlarmData(alarm.copy(id = ref.id, userId = userId))).await()
            FirestoreUsage.recordWrite("createAlarm", FirestoreUsage.WriteType.SET)
            Log.d(TAG, "Alarm created with ID: ${ref.id}")
            ref.id
        }
    }.onFailure { Log.e(TAG, "Error creating alarm", it) }

    /**
     * Updates an existing alarm.
     */
    suspend fun updateAlarm(alarm: Alarm): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val data = alarmToMap(alarm).toMutableMap()
            data["updatedAt"] = FieldValue.serverTimestamp()
            alarmRef(userId, alarm.id).set(data).await()
            FirestoreUsage.recordWrite("updateAlarm", FirestoreUsage.WriteType.SET)
            Log.d(TAG, "Alarm updated: ${alarm.id}")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating alarm", it) }

    /**
     * Records the highest stage type that has been presented with sound.
     * Sync/restart logic uses this to avoid re-sounding notifications.
     */
    suspend fun markNotifiedStage(alarmId: String, stageType: AlarmStageType): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            alarmRef(userId, alarmId).update("notifiedStageType", stageType.name).await()
            FirestoreUsage.recordWrite("markNotifiedStage", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Marked notifiedStageType=$stageType for alarm $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error marking notified stage: $alarmId/$stageType", it) }

    /**
     * Links an existing alarm to a recurring alarm template.
     */
    suspend fun linkToRecurringAlarm(alarmId: String, recurringAlarmId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            alarmRef(userId, alarmId).update(
                mapOf(
                    "recurringAlarmId" to recurringAlarmId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("linkToRecurringAlarm", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Linked alarm $alarmId to recurring alarm $recurringAlarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error linking alarm to recurring alarm", it) }

    /**
     * Deletes an alarm permanently.
     */
    suspend fun deleteAlarm(alarmId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            alarmRef(userId, alarmId).delete().await()
            FirestoreUsage.recordWrite("deleteAlarm", FirestoreUsage.WriteType.DELETE)
            Log.d(TAG, "Alarm deleted: $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error deleting alarm", it) }

    /**
     * Deletes all alarms for the current user.
     */
    suspend fun deleteAllAlarms(): Result<List<String>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            ensureListenerAttached().await()
            val alarms = _cachedAlarms.value
            if (alarms.isEmpty()) return@withContext emptyList<String>()
            val alarmIds = alarms.map { it.id }
            // Firestore batches are limited to 500 operations
            for (chunk in alarms.chunked(500)) {
                val batch = db.batch()
                for (alarm in chunk) {
                    batch.delete(alarmRef(userId, alarm.id))
                }
                batch.commit().await()
                FirestoreUsage.recordWrite("deleteAllAlarms", FirestoreUsage.WriteType.BATCH_COMMIT, chunk.size)
            }
            Log.d(TAG, "Deleted all alarms (${alarms.size} documents)")
            alarmIds
        }
    }.onFailure { Log.e(TAG, "Error deleting all alarms", it) }

    /**
     * Deletes all alarm instances that belong to a recurring alarm.
     * Returns the IDs of deleted alarms for deactivation.
     */
    suspend fun deleteRecurringAlarmInstances(): Result<List<String>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            ensureListenerAttached().await()
            val instances = _cachedAlarms.value.filter { it.recurringAlarmId != null }
            val alarmIds = instances.map { it.id }
            for (chunk in instances.chunked(500)) {
                val batch = db.batch()
                for (alarm in chunk) {
                    batch.delete(alarmRef(userId, alarm.id))
                }
                batch.commit().await()
                FirestoreUsage.recordWrite("deleteRecurringAlarmInstances", FirestoreUsage.WriteType.BATCH_COMMIT, chunk.size)
            }
            Log.d(TAG, "Deleted ${alarmIds.size} recurring alarm instances")
            alarmIds
        }
    }.onFailure { Log.e(TAG, "Error deleting recurring alarm instances", it) }

    /**
     * Gets a single alarm by ID, served from the listener cache.
     */
    suspend fun getAlarm(alarmId: String): Result<Alarm?> = runCatching {
        ensureListenerAttached().await()
        _cachedAlarms.value.firstOrNull { it.id == alarmId }
    }.onFailure { Log.e(TAG, "Error getting alarm", it) }

    /**
     * One-shot DOC_GET for cold-process callers (BroadcastReceivers,
     * Activities launched from notifications) where attaching a long-lived
     * listener is wasteful: the process is short-lived, the listener gets
     * GC'd before its first delta, and the listener's INITIAL_FRESH would
     * pull the entire alarms collection just to read one doc.
     */
    suspend fun getAlarmFromServer(alarmId: String): Result<Alarm?> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val doc = alarmRef(userId, alarmId).get().await()
            FirestoreUsage.recordRead("alarms.getAlarmFromServer", FirestoreUsage.ReadType.DOC_GET)
            if (doc.exists()) mapToAlarm(doc.id, doc.data ?: emptyMap()) else null
        }
    }.onFailure { Log.e(TAG, "Error getting alarm from server", it) }

    /**
     * Gets all alarms for a specific note.
     */
    suspend fun getAlarmsForNote(noteId: String): Result<List<Alarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedAlarms.value.filter { it.noteId == noteId }
    }.onFailure { Log.e(TAG, "Error getting alarms for note", it) }

    /**
     * Gets alarms by their document IDs, looked up in the cache.
     */
    suspend fun getAlarmsByIds(alarmIds: List<String>): Result<Map<String, Alarm>> = runCatching {
        if (alarmIds.isEmpty()) return@runCatching emptyMap()
        ensureListenerAttached().await()
        val byId = _cachedAlarms.value.associateBy { it.id }
        alarmIds.mapNotNull { id -> byId[id]?.let { id to it } }.toMap()
    }.onFailure { Log.e(TAG, "Error getting alarms by IDs", it) }

    /**
     * Gets upcoming alarms (status=PENDING, dueTime != null, ordered by dueTime asc).
     */
    suspend fun getUpcomingAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedAlarms.value
            .filter { it.status == AlarmStatus.PENDING && it.dueTime != null }
            .sortedBy { it.dueTime?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting upcoming alarms", it) }

    /**
     * Gets later alarms (status=PENDING, dueTime == null, ordered by createdAt desc).
     */
    suspend fun getLaterAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedAlarms.value
            .filter { it.status == AlarmStatus.PENDING && it.dueTime == null }
            .sortedByDescending { it.createdAt?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting later alarms", it) }

    /**
     * Gets completed alarms (status=DONE, ordered by updatedAt desc).
     */
    suspend fun getCompletedAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedAlarms.value
            .filter { it.status == AlarmStatus.DONE }
            .sortedByDescending { it.updatedAt?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting completed alarms", it) }

    /**
     * Gets cancelled alarms (status=CANCELLED, ordered by updatedAt desc).
     */
    suspend fun getCancelledAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedAlarms.value
            .filter { it.status == AlarmStatus.CANCELLED }
            .sortedByDescending { it.updatedAt?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting cancelled alarms", it) }

    /**
     * Gets all pending alarms (used after boot for scheduling).
     */
    suspend fun getPendingAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedAlarms.value.filter { it.status == AlarmStatus.PENDING }
    }.onFailure { Log.e(TAG, "Error getting pending alarms", it) }

    /**
     * One-shot GET_DOCS variant of [getPendingAlarms] for cold-process
     * callers (BootReceiver, NotificationSyncer). Same rationale as
     * [getAlarmFromServer]: avoid the long-lived listener in receivers.
     */
    suspend fun getPendingAlarmsFromServer(): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.PENDING.name)
                .get()
                .await()
            FirestoreUsage.recordRead("alarms.getPendingFromServer", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToAlarm(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting pending alarms from server", it) }

    /**
     * Marks an alarm as done.
     */
    suspend fun markDone(alarmId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            alarmRef(userId, alarmId).update(
                mapOf(
                    "status" to AlarmStatus.DONE.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("markDone", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Alarm marked done: $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error marking alarm done", it) }

    /**
     * Marks an alarm as cancelled.
     */
    suspend fun markCancelled(alarmId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            alarmRef(userId, alarmId).update(
                mapOf(
                    "status" to AlarmStatus.CANCELLED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("markCancelled", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Alarm marked cancelled: $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error marking alarm cancelled", it) }

    /**
     * Snoozes an alarm for the specified duration.
     */
    suspend fun snoozeAlarm(alarmId: String, duration: SnoozeDuration): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val snoozeUntil = Timestamp(Date(System.currentTimeMillis() + duration.minutes * 60 * 1000))
            alarmRef(userId, alarmId).update(
                mapOf(
                    "snoozedUntil" to snoozeUntil,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("snoozeAlarm", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Alarm snoozed until $snoozeUntil: $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error snoozing alarm", it) }

    /**
     * Clears the snooze state (used when snooze period ends).
     */
    suspend fun clearSnooze(alarmId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            alarmRef(userId, alarmId).update(
                mapOf(
                    "snoozedUntil" to null,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("clearSnooze", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Alarm snooze cleared: $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error clearing alarm snooze", it) }

    /**
     * Reactivates an alarm (moves from completed/cancelled back to pending).
     */
    suspend fun reactivateAlarm(alarmId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            alarmRef(userId, alarmId).update(
                mapOf(
                    "status" to AlarmStatus.PENDING.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("reactivateAlarm", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Alarm reactivated: $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error reactivating alarm", it) }

    /**
     * Updates the noteId field for a specific alarm.
     * Used when a line's noteId changes (e.g., after split/merge resolution).
     */
    suspend fun updateAlarmNoteId(alarmId: String, newNoteId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            alarmRef(userId, alarmId).update("noteId", newNoteId).await()
            FirestoreUsage.recordWrite("updateAlarmNoteId", FirestoreUsage.WriteType.UPDATE)
            Unit
        }
    }

    suspend fun updateLineContentForNote(noteId: String, newContent: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            ensureListenerAttached().await()
            val matching = _cachedAlarms.value.filter { it.noteId == noteId }
            if (matching.isEmpty()) return@withContext

            val batch = db.batch()
            for (alarm in matching) {
                batch.update(alarmRef(userId, alarm.id), mapOf("lineContent" to newContent))
            }
            batch.commit().await()
            FirestoreUsage.recordWrite("alarms.updateLineContentForNote", FirestoreUsage.WriteType.BATCH_COMMIT, matching.size)
            Unit
        }
    }

    /**
     * Gets the highest priority active alarm for the current user.
     * Used for status bar icon color.
     */
    suspend fun getHighestPriorityAlarm(): Result<AlarmPriority?> = runCatching {
        ensureListenerAttached().await()
        val now = Timestamp.now()
        val nowMs = now.toDate().time

        var highestPriority: AlarmPriority? = null

        for (alarm in _cachedAlarms.value) {
            if (alarm.status != AlarmStatus.PENDING) continue
            // Skip snoozed alarms
            if (alarm.snoozedUntil != null && alarm.snoozedUntil > now) continue
            val due = alarm.dueTime ?: continue

            val priority = alarm.enabledStages
                .filter { it.resolveTime(due).toDate().time <= nowMs }
                .maxOfOrNull { stagePriority(it.type) }
                ?: AlarmPriority.UPCOMING

            if (highestPriority == null || priority > highestPriority) {
                highestPriority = priority
            }

            // If we found the highest possible priority, we can stop
            if (highestPriority == AlarmPriority.ALARM) break
        }

        highestPriority
    }.onFailure { Log.e(TAG, "Error getting highest priority alarm", it) }

    private fun stagePriority(type: AlarmStageType): AlarmPriority = when (type) {
        AlarmStageType.SOUND_ALARM -> AlarmPriority.ALARM
        AlarmStageType.LOCK_SCREEN -> AlarmPriority.URGENT
        AlarmStageType.NOTIFICATION -> AlarmPriority.NOTIFY
    }

    private fun alarmToMap(alarm: Alarm): Map<String, Any?> = mapOf(
        "userId" to alarm.userId,
        "noteId" to alarm.noteId,
        "lineContent" to alarm.lineContent,
        "createdAt" to alarm.createdAt,
        "updatedAt" to alarm.updatedAt,
        "dueTime" to alarm.dueTime,
        "stages" to alarm.stages.map { it.toMap() },
        "status" to alarm.status.name,
        "snoozedUntil" to alarm.snoozedUntil,
        "recurringAlarmId" to alarm.recurringAlarmId,
        "notifiedStageType" to alarm.notifiedStageType?.name
    )

    private fun mapToAlarm(id: String, data: Map<String, Any?>): Alarm = Alarm(
        id = id,
        userId = data["userId"] as? String ?: "",
        noteId = data["noteId"] as? String ?: "",
        lineContent = data["lineContent"] as? String ?: "",
        createdAt = data["createdAt"] as? Timestamp,
        updatedAt = data["updatedAt"] as? Timestamp,
        dueTime = data["dueTime"] as? Timestamp,
        stages = parseStages(data["stages"]),
        status = try {
            AlarmStatus.valueOf(data["status"] as? String ?: AlarmStatus.PENDING.name)
        } catch (e: IllegalArgumentException) {
            AlarmStatus.PENDING
        },
        snoozedUntil = data["snoozedUntil"] as? Timestamp,
        recurringAlarmId = data["recurringAlarmId"] as? String,
        notifiedStageType = (data["notifiedStageType"] as? String)?.let {
            try { AlarmStageType.valueOf(it) } catch (e: IllegalArgumentException) { null }
        }
    )

    private fun parseStages(raw: Any?): List<AlarmStage> = AlarmStage.fromMapList(raw)

    /**
     * Gets all alarm instances for a given recurring alarm template, ordered
     * by dueTime asc. Includes all statuses (PENDING, DONE, CANCELLED).
     */
    suspend fun getInstancesForRecurring(recurringAlarmId: String): Result<List<Alarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedAlarms.value
            .filter { it.recurringAlarmId == recurringAlarmId }
            .sortedBy { it.dueTime?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting instances for recurring alarm", it) }

    /**
     * Gets all pending alarm instances for a given recurring alarm template.
     */
    suspend fun getPendingInstancesForRecurring(recurringAlarmId: String): Result<List<Alarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedAlarms.value.filter {
            it.recurringAlarmId == recurringAlarmId && it.status == AlarmStatus.PENDING
        }
    }.onFailure { Log.e(TAG, "Error getting pending instances for recurring alarm", it) }

    companion object {
        private const val TAG = "AlarmRepository"

        // Singleton-shared listener + cache. Every AlarmRepository instance
        // serves reads from this. Production has 7+ construction sites
        // (ViewModels, services); without this sharing they'd each attach
        // their own listener and pay parallel INITIAL_FRESH on attach.
        private val cacheLock = Any()
        private val _cachedAlarms = MutableStateFlow<List<Alarm>>(emptyList())
        internal val cachedAlarms: StateFlow<List<Alarm>> = _cachedAlarms.asStateFlow()
        private var listener: ListenerRegistration? = null
        private var loadDeferred: CompletableDeferred<Unit>? = null

        /**
         * Detach the listener and drop cached alarms. Call on sign-out so
         * the next signed-in user doesn't see the previous user's data,
         * and the listener doesn't keep firing under stale credentials.
         */
        fun clear() {
            synchronized(cacheLock) {
                listener?.remove()
                listener = null
                loadDeferred = null
                _cachedAlarms.value = emptyList()
            }
        }

        /** Test seam: populate the cache directly, bypassing the listener. */
        @VisibleForTesting
        internal fun injectCacheForTest(alarms: List<Alarm>) {
            synchronized(cacheLock) {
                // Sentinel listener so subsequent reads' `ensureListenerAttached`
                // short-circuits — without it, the next read would attach a real
                // Firestore listener over the (mocked) collection.
                if (listener == null) listener = ListenerRegistration { /* test seam */ }
                val deferred = loadDeferred ?: CompletableDeferred<Unit>().also { loadDeferred = it }
                _cachedAlarms.value = alarms
                if (!deferred.isCompleted) deferred.complete(Unit)
            }
        }
    }
}
