package org.alkaline.taskbrain.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Repository for managing alarms under /users/{userId}/alarms/{alarmId}.
 *
 * Sync architecture (mirrors `NoteStore`; see `docs/live-cross-platform-sync.md`):
 * 1. `users/{uid}` 1-doc signal listener watching `lastAlarmChange`.
 * 2. Delta pull (`alarms WHERE updatedAt > lastSync − 5s`) on signal fire,
 *    on first attach, and after count() divergence.
 * 3. Hydration from Firestore's persistent cache (`get(Source.CACHE)`) on
 *    first attach — restores the cache for $0 across process restarts.
 *
 * Each write path bumps `UserDocSignal.Channel.ALARMS` after success so
 * other clients pull. Hard-deletes also update the in-memory cache locally
 * because vanished docs are invisible to delta pulls.
 *
 * The signal listener + cache live on the companion object so every
 * instance shares one — production has 7+ construction sites; a per-
 * instance listener would mean each pays its own attach. Receivers in
 * separate OS processes get their own JVM (and their own singleton);
 * for those, prefer the `*FromServer` one-shot reads instead of the cache.
 *
 * The first instance to call `ensureListenerAttached()` binds its
 * `db`/`auth`; subsequent instances with different references are silently
 * ignored. In production all instances share the singleton
 * `FirebaseFirestore`/`FirebaseAuth` so this is irrelevant. In tests, call
 * [clear] in `@Before` to reset the companion before injecting fresh mocks.
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

    private suspend fun ensureListenerAttached() {
        val userId = auth.currentUser?.uid
            ?: throw IllegalStateException("User not signed in")
        pullEngine.start(db, userId)
        pullEngine.ensureLoaded()
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
            signalChannel = UserDocSignal.Channel.ALARMS,
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            removeFromCacheLocally(listOf(alarmId))
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            ensureListenerAttached()
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
            removeFromCacheLocally(alarmIds)
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            ensureListenerAttached()
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
            removeFromCacheLocally(alarmIds)
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
            Log.d(TAG, "Deleted ${alarmIds.size} recurring alarm instances")
            alarmIds
        }
    }.onFailure { Log.e(TAG, "Error deleting recurring alarm instances", it) }

    /**
     * Gets a single alarm by ID, served from the listener cache.
     */
    suspend fun getAlarm(alarmId: String): Result<Alarm?> = runCatching {
        ensureListenerAttached()
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
        ensureListenerAttached()
        _cachedAlarms.value.filter { it.noteId == noteId }
    }.onFailure { Log.e(TAG, "Error getting alarms for note", it) }

    /**
     * Gets alarms by their document IDs, looked up in the cache.
     */
    suspend fun getAlarmsByIds(alarmIds: List<String>): Result<Map<String, Alarm>> = runCatching {
        if (alarmIds.isEmpty()) return@runCatching emptyMap()
        ensureListenerAttached()
        val byId = _cachedAlarms.value.associateBy { it.id }
        alarmIds.mapNotNull { id -> byId[id]?.let { id to it } }.toMap()
    }.onFailure { Log.e(TAG, "Error getting alarms by IDs", it) }

    /**
     * Gets upcoming alarms (status=PENDING, dueTime != null, ordered by dueTime asc).
     */
    suspend fun getUpcomingAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached()
        _cachedAlarms.value
            .filter { it.status == AlarmStatus.PENDING && it.dueTime != null }
            .sortedBy { it.dueTime?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting upcoming alarms", it) }

    /**
     * Gets later alarms (status=PENDING, dueTime == null, ordered by createdAt desc).
     */
    suspend fun getLaterAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached()
        _cachedAlarms.value
            .filter { it.status == AlarmStatus.PENDING && it.dueTime == null }
            .sortedByDescending { it.createdAt?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting later alarms", it) }

    /**
     * Gets completed alarms (status=DONE, ordered by updatedAt desc).
     */
    suspend fun getCompletedAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached()
        _cachedAlarms.value
            .filter { it.status == AlarmStatus.DONE }
            .sortedByDescending { it.updatedAt?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting completed alarms", it) }

    /**
     * Gets cancelled alarms (status=CANCELLED, ordered by updatedAt desc).
     */
    suspend fun getCancelledAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached()
        _cachedAlarms.value
            .filter { it.status == AlarmStatus.CANCELLED }
            .sortedByDescending { it.updatedAt?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting cancelled alarms", it) }

    /**
     * Gets all pending alarms (used after boot for scheduling).
     */
    suspend fun getPendingAlarms(): Result<List<Alarm>> = runCatching {
        ensureListenerAttached()
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
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
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
            Unit
        }
    }

    suspend fun updateLineContentForNote(noteId: String, newContent: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            ensureListenerAttached()
            val matching = _cachedAlarms.value.filter { it.noteId == noteId }
            if (matching.isEmpty()) return@withContext

            val batch = db.batch()
            for (alarm in matching) {
                batch.update(alarmRef(userId, alarm.id), mapOf("lineContent" to newContent))
            }
            batch.commit().await()
            FirestoreUsage.recordWrite("alarms.updateLineContentForNote", FirestoreUsage.WriteType.BATCH_COMMIT, matching.size)
            UserDocSignal.bump(db, userId, UserDocSignal.Channel.ALARMS)
            Unit
        }
    }

    /**
     * Gets the highest priority active alarm for the current user.
     * Used for status bar icon color.
     */
    suspend fun getHighestPriorityAlarm(): Result<AlarmPriority?> = runCatching {
        ensureListenerAttached()
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

    /**
     * Gets all alarm instances for a given recurring alarm template, ordered
     * by dueTime asc. Includes all statuses (PENDING, DONE, CANCELLED).
     */
    suspend fun getInstancesForRecurring(recurringAlarmId: String): Result<List<Alarm>> = runCatching {
        ensureListenerAttached()
        _cachedAlarms.value
            .filter { it.recurringAlarmId == recurringAlarmId }
            .sortedBy { it.dueTime?.toDate()?.time }
    }.onFailure { Log.e(TAG, "Error getting instances for recurring alarm", it) }

    /**
     * Gets all pending alarm instances for a given recurring alarm template.
     */
    suspend fun getPendingInstancesForRecurring(recurringAlarmId: String): Result<List<Alarm>> = runCatching {
        ensureListenerAttached()
        _cachedAlarms.value.filter {
            it.recurringAlarmId == recurringAlarmId && it.status == AlarmStatus.PENDING
        }
    }.onFailure { Log.e(TAG, "Error getting pending instances for recurring alarm", it) }

    /**
     * Remove alarm ids from the local cache directly. Used by hard-delete
     * paths whose vanished docs the delta pull can't observe.
     */
    private fun removeFromCacheLocally(alarmIds: Collection<String>) {
        if (alarmIds.isEmpty()) return
        synchronized(cacheLock) {
            val idSet = alarmIds.toHashSet()
            val before = _cachedAlarms.value
            val after = before.filterNot { it.id in idSet }
            if (after.size != before.size) {
                _cachedAlarms.value = after
            }
        }
    }

    companion object {
        private const val TAG = "AlarmRepository"

        // Singleton-shared cache. Every AlarmRepository instance serves
        // reads from this. Production has 7+ construction sites; per-
        // instance state would mean each pays its own attach.
        private val cacheLock = Any()
        private val _cachedAlarms = MutableStateFlow<List<Alarm>>(emptyList())
        internal val cachedAlarms: StateFlow<List<Alarm>> = _cachedAlarms.asStateFlow()

        private val alarmSink = object : DeltaPullEngine.DeltaPullSink<Alarm> {
            override fun applyFullPull(items: List<Alarm>) {
                synchronized(cacheLock) { _cachedAlarms.value = items }
            }

            override fun applyDelta(items: List<Alarm>) {
                if (items.isEmpty()) return
                synchronized(cacheLock) {
                    val byId = _cachedAlarms.value.associateByTo(LinkedHashMap()) { it.id }
                    for (alarm in items) byId[alarm.id] = alarm
                    _cachedAlarms.value = byId.values.toList()
                }
            }

            override fun localCount(): Long =
                synchronized(cacheLock) { _cachedAlarms.value.size.toLong() }

            // Route alarm sync warnings through the shared NoteStore banner
            // — alarms have always borrowed that surface for cross-subsystem
            // notifications.
            override fun raiseSyncWarning(message: String) {
                NoteStore.raiseWarning(message)
            }

            override fun clearSyncWarning() {
                NoteStore.clearError()
            }
        }

        private val pullEngine = DeltaPullEngine(
            channel = UserDocSignal.Channel.ALARMS,
            tag = "AlarmRepo",
            collectionRef = { db, uid ->
                db.collection("users").document(uid).collection("alarms")
            },
            parse = { doc -> parseAlarmSafely(doc.id, doc.data ?: emptyMap()) },
            updatedAt = { it.updatedAt },
            sink = alarmSink,
        )

        private fun parseAlarmSafely(id: String, data: Map<String, Any?>): Alarm? = try {
            mapToAlarm(id, data)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error parsing alarm $id", e)
            null
        }

        private fun mapToAlarm(id: String, data: Map<String, Any?>): Alarm = Alarm(
            id = id,
            userId = data["userId"] as? String ?: "",
            noteId = data["noteId"] as? String ?: "",
            lineContent = data["lineContent"] as? String ?: "",
            createdAt = data["createdAt"] as? Timestamp,
            updatedAt = data["updatedAt"] as? Timestamp,
            dueTime = data["dueTime"] as? Timestamp,
            stages = AlarmStage.fromMapList(data["stages"]),
            status = try {
                AlarmStatus.valueOf(data["status"] as? String ?: AlarmStatus.PENDING.name)
            } catch (e: IllegalArgumentException) {
                AlarmStatus.PENDING
            },
            snoozedUntil = data["snoozedUntil"] as? Timestamp,
            recurringAlarmId = data["recurringAlarmId"] as? String,
            notifiedStageType = (data["notifiedStageType"] as? String)?.let {
                try { AlarmStageType.valueOf(it) } catch (e: IllegalArgumentException) { null }
            },
        )

        /**
         * Tear down the engine, drop the cache. Call on sign-out so the
         * next signed-in user doesn't see the previous user's data.
         * [SignalListener.clear] is called separately at the same
         * lifecycle point.
         */
        fun clear() {
            pullEngine.clear()
            synchronized(cacheLock) {
                _cachedAlarms.value = emptyList()
            }
        }

        /** Test seam: populate the cache directly, bypassing the engine. */
        @VisibleForTesting
        internal fun injectCacheForTest(alarms: List<Alarm>) {
            synchronized(cacheLock) {
                _cachedAlarms.value = alarms
            }
            pullEngine.markLoadedForTest()
        }
    }
}
