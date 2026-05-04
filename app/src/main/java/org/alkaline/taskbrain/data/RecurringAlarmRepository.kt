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

/**
 * Repository for managing recurring alarm templates under
 * /users/{userId}/recurringAlarms/{id}. Same listener-backed cache shape
 * as [AlarmRepository]; see `docs/firestore-efficiency.md` Principle 1.
 */
class RecurringAlarmRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    private fun collection(userId: String) =
        db.collection("users").document(userId).collection("recurringAlarms")

    private fun docRef(userId: String, id: String): DocumentReference =
        collection(userId).document(id)

    private fun newDocRef(userId: String): DocumentReference =
        collection(userId).document()

    private fun ensureListenerAttached(): CompletableDeferred<Unit> {
        synchronized(cacheLock) {
            if (listener != null) return loadDeferred!!
            val userId = auth.currentUser?.uid
                ?: throw IllegalStateException("User not signed in")
            val deferred = loadDeferred ?: CompletableDeferred<Unit>().also { loadDeferred = it }

            listener = collection(userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(
                            TAG,
                            "[recurringAlarms listener failed] userId=$userId\n${error.stackTraceToString()}",
                            error,
                        )
                        NoteStore.raiseWarning(
                            "Recurring-alarm sync failed. Recurrence templates may be stale " +
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
                    FirestoreUsage.recordRead("RecurringAlarmRepo.listener", type, docCount)
                    val recurring = snapshot.documents.mapNotNull { doc ->
                        try {
                            fromMap(doc.id, doc.data ?: emptyMap())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing recurring alarm", e)
                            null
                        }
                    }
                    _cachedRecurringAlarms.value = recurring
                    if (!deferred.isCompleted) deferred.complete(Unit)
                }
            return deferred
        }
    }

    /** Mints a fresh recurringAlarm doc ID client-side, no Firestore write. */
    fun newRecurringAlarmId(): String = newDocRef(requireUserId()).id

    /**
     * Builds a batch op for a recurringAlarm create. Pair with
     * [AlarmRepository.buildCreateBatchOp] for the first instance.
     */
    fun buildCreateBatchOp(recurringAlarm: RecurringAlarm): NoteRepository.BatchExtraOp {
        val userId = requireUserId()
        return NoteRepository.BatchExtraOp(
            ref = docRef(userId, recurringAlarm.id),
            data = createData(recurringAlarm.copy(userId = userId)),
            merge = false,
        )
    }

    private fun createData(ra: RecurringAlarm): MutableMap<String, Any?> =
        toMap(ra).toMutableMap().apply {
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }

    suspend fun create(recurringAlarm: RecurringAlarm): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = newDocRef(userId)
            ref.set(createData(recurringAlarm.copy(id = ref.id, userId = userId))).await()
            FirestoreUsage.recordWrite("createRecurringAlarm", FirestoreUsage.WriteType.SET)
            Log.d(TAG, "RecurringAlarm created: ${ref.id}")
            ref.id
        }
    }.onFailure { Log.e(TAG, "Error creating recurring alarm", it) }

    suspend fun update(recurringAlarm: RecurringAlarm): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val data = toMap(recurringAlarm).toMutableMap()
            data["updatedAt"] = FieldValue.serverTimestamp()
            docRef(userId, recurringAlarm.id).set(data).await()
            FirestoreUsage.recordWrite("updateRecurringAlarm", FirestoreUsage.WriteType.SET)
            Log.d(TAG, "RecurringAlarm updated: ${recurringAlarm.id}")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating recurring alarm", it) }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            docRef(userId, id).delete().await()
            FirestoreUsage.recordWrite("deleteRecurringAlarm", FirestoreUsage.WriteType.DELETE)
            Log.d(TAG, "RecurringAlarm deleted: $id")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error deleting recurring alarm", it) }

    /**
     * Deletes all recurring alarms for the current user.
     */
    suspend fun deleteAll(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            ensureListenerAttached().await()
            val items = _cachedRecurringAlarms.value
            if (items.isEmpty()) return@withContext
            val batch = db.batch()
            for (item in items) {
                batch.delete(docRef(userId, item.id))
            }
            batch.commit().await()
            FirestoreUsage.recordWrite("deleteAllRecurringAlarms", FirestoreUsage.WriteType.BATCH_COMMIT, items.size)
            Log.d(TAG, "Deleted all recurring alarms (${items.size} documents)")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error deleting all recurring alarms", it) }

    suspend fun get(id: String): Result<RecurringAlarm?> = runCatching {
        ensureListenerAttached().await()
        _cachedRecurringAlarms.value.firstOrNull { it.id == id }
    }.onFailure { Log.e(TAG, "Error getting recurring alarm", it) }

    /**
     * One-shot DOC_GET variant of [get] for cold-process callers
     * (BroadcastReceivers, Activities launched from notifications) where
     * a long-lived listener would be GC'd before delivering deltas. Same
     * rationale as `AlarmRepository.getAlarmFromServer`.
     */
    suspend fun getFromServer(id: String): Result<RecurringAlarm?> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val doc = docRef(userId, id).get().await()
            FirestoreUsage.recordRead("recurring.getFromServer", FirestoreUsage.ReadType.DOC_GET)
            if (doc.exists()) fromMap(doc.id, doc.data ?: emptyMap()) else null
        }
    }.onFailure { Log.e(TAG, "Error getting recurring alarm from server", it) }

    suspend fun getActiveRecurringAlarms(): Result<List<RecurringAlarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedRecurringAlarms.value.filter { it.status == RecurringAlarmStatus.ACTIVE }
    }.onFailure { Log.e(TAG, "Error getting active recurring alarms", it) }

    suspend fun getForNote(noteId: String): Result<List<RecurringAlarm>> = runCatching {
        ensureListenerAttached().await()
        _cachedRecurringAlarms.value.filter { it.noteId == noteId }
    }.onFailure { Log.e(TAG, "Error getting recurring alarms for note", it) }

    suspend fun recordCompletion(id: String, completionDate: Timestamp): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            docRef(userId, id).update(
                mapOf(
                    "completionCount" to FieldValue.increment(1),
                    "lastCompletionDate" to completionDate,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("recordRecurringCompletion", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Recorded completion for recurring alarm: $id")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error recording completion", it) }

    suspend fun updateCurrentAlarmId(
        id: String,
        alarmId: String?,
        anchorTimeOfDay: TimeOfDay?
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val data = mutableMapOf<String, Any?>(
                "currentAlarmId" to alarmId,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (anchorTimeOfDay != null) {
                data.putAll(anchorTimeOfDay.toAnchorFields())
            }
            docRef(userId, id).update(data).await()
            FirestoreUsage.recordWrite("updateCurrentAlarmId", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Updated currentAlarmId for $id to $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating currentAlarmId", it) }

    suspend fun end(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            docRef(userId, id).update(
                mapOf(
                    "status" to RecurringAlarmStatus.ENDED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("endRecurringAlarm", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Recurring alarm ended: $id")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error ending recurring alarm", it) }

    /**
     * Updates the time-of-day anchor and stages on a recurring alarm template.
     */
    suspend fun updateTimes(
        id: String,
        anchorTimeOfDay: TimeOfDay?,
        stages: List<AlarmStage>
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val data = mutableMapOf<String, Any?>(
                "stages" to stages.map { it.toMap() },
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (anchorTimeOfDay != null) {
                data.putAll(anchorTimeOfDay.toAnchorFields())
            }
            docRef(userId, id).update(data).await()
            FirestoreUsage.recordWrite("updateRecurringTimes", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Updated times for recurring alarm: $id")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating times for recurring alarm", it) }

    /**
     * Finds all PENDING alarm instances for a recurring alarm whose times match
     * the given anchor time-of-day and stages (i.e., haven't been individually edited).
     */
    suspend fun getMatchingPendingInstances(
        recurringAlarmId: String,
        anchorTimeOfDay: TimeOfDay?,
        stages: List<AlarmStage>,
        alarmRepo: AlarmRepository
    ): List<Alarm> {
        val pending = alarmRepo.getPendingInstancesForRecurring(recurringAlarmId)
            .getOrDefault(emptyList())
        return pending.filter { alarm ->
            alarm.dueTime?.toTimeOfDay() == anchorTimeOfDay && alarm.stages == stages
        }
    }

    suspend fun updateLineContentForNote(noteId: String, newContent: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            ensureListenerAttached().await()
            val matching = _cachedRecurringAlarms.value.filter { it.noteId == noteId }
            if (matching.isEmpty()) return@withContext
            val batch = db.batch()
            for (item in matching) {
                batch.update(docRef(userId, item.id), mapOf("lineContent" to newContent))
            }
            batch.commit().await()
            FirestoreUsage.recordWrite("recurring.updateLineContentForNote", FirestoreUsage.WriteType.BATCH_COMMIT, matching.size)
        }
    }

    private fun toMap(ra: RecurringAlarm): Map<String, Any?> = mapOf(
        "userId" to ra.userId,
        "noteId" to ra.noteId,
        "lineContent" to ra.lineContent,
        "recurrenceType" to ra.recurrenceType.name,
        "rrule" to ra.rrule,
        "relativeIntervalMs" to ra.relativeIntervalMs,
        "dueOffsetMs" to ra.dueOffsetMs,
        "stages" to ra.stages.map { it.toMap() },
        "endDate" to ra.endDate,
        "repeatCount" to ra.repeatCount,
        "completionCount" to ra.completionCount,
        "lastCompletionDate" to ra.lastCompletionDate,
        "currentAlarmId" to ra.currentAlarmId,
        "anchorTimeHour" to ra.anchorTimeOfDay?.hour,
        "anchorTimeMinute" to ra.anchorTimeOfDay?.minute,
        "status" to ra.status.name,
        "createdAt" to ra.createdAt,
        "updatedAt" to ra.updatedAt
    )

    private fun fromMap(id: String, data: Map<String, Any?>): RecurringAlarm = RecurringAlarm(
        id = id,
        userId = data["userId"] as? String ?: "",
        noteId = data["noteId"] as? String ?: "",
        lineContent = data["lineContent"] as? String ?: "",
        recurrenceType = try {
            RecurrenceType.valueOf(data["recurrenceType"] as? String ?: RecurrenceType.FIXED.name)
        } catch (e: IllegalArgumentException) { RecurrenceType.FIXED },
        rrule = data["rrule"] as? String,
        relativeIntervalMs = (data["relativeIntervalMs"] as? Number)?.toLong(),
        dueOffsetMs = (data["dueOffsetMs"] as? Number)?.toLong() ?: 0,
        stages = parseStages(data["stages"]),
        endDate = data["endDate"] as? Timestamp,
        repeatCount = (data["repeatCount"] as? Number)?.toInt(),
        completionCount = (data["completionCount"] as? Number)?.toInt() ?: 0,
        lastCompletionDate = data["lastCompletionDate"] as? Timestamp,
        currentAlarmId = data["currentAlarmId"] as? String,
        anchorTimeOfDay = run {
            val hour = (data["anchorTimeHour"] as? Number)?.toInt()
            val minute = (data["anchorTimeMinute"] as? Number)?.toInt()
            if (hour != null && minute != null) TimeOfDay(hour, minute) else null
        },
        status = try {
            RecurringAlarmStatus.valueOf(data["status"] as? String ?: RecurringAlarmStatus.ACTIVE.name)
        } catch (e: IllegalArgumentException) { RecurringAlarmStatus.ACTIVE },
        createdAt = data["createdAt"] as? Timestamp,
        updatedAt = data["updatedAt"] as? Timestamp
    )

    private fun parseStages(raw: Any?): List<AlarmStage> = AlarmStage.fromMapList(raw)

    companion object {
        private const val TAG = "RecurringAlarmRepo"

        // Singleton-shared listener + cache. See AlarmRepository for rationale.
        private val cacheLock = Any()
        private val _cachedRecurringAlarms = MutableStateFlow<List<RecurringAlarm>>(emptyList())
        internal val cachedRecurringAlarms: StateFlow<List<RecurringAlarm>> =
            _cachedRecurringAlarms.asStateFlow()
        private var listener: ListenerRegistration? = null
        private var loadDeferred: CompletableDeferred<Unit>? = null

        fun clear() {
            synchronized(cacheLock) {
                listener?.remove()
                listener = null
                loadDeferred = null
                _cachedRecurringAlarms.value = emptyList()
            }
        }

        @VisibleForTesting
        internal fun injectCacheForTest(items: List<RecurringAlarm>) {
            synchronized(cacheLock) {
                if (listener == null) listener = ListenerRegistration { /* test seam */ }
                val deferred = loadDeferred ?: CompletableDeferred<Unit>().also { loadDeferred = it }
                _cachedRecurringAlarms.value = items
                if (!deferred.isCompleted) deferred.complete(Unit)
            }
        }
    }
}
