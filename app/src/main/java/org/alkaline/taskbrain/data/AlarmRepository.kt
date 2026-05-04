package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Repository for managing alarms in Firestore.
 * Alarms are stored under /users/{userId}/alarms/{alarmId}
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
            val result = alarmsCollection(userId).get().await()
            FirestoreUsage.recordRead("deleteAllAlarms", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            val alarmIds = result.documents.map { it.id }
            // Firestore batches are limited to 500 operations
            for (chunk in result.documents.chunked(500)) {
                val batch = db.batch()
                for (doc in chunk) {
                    batch.delete(doc.reference)
                }
                batch.commit().await()
                FirestoreUsage.recordWrite("deleteAllAlarms", FirestoreUsage.WriteType.BATCH_COMMIT, chunk.size)
            }
            Log.d(TAG, "Deleted all alarms (${result.documents.size} documents)")
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
            val result = alarmsCollection(userId)
                .whereNotEqualTo("recurringAlarmId", null)
                .get()
                .await()
            FirestoreUsage.recordRead("deleteRecurringAlarmInstances", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            val alarmIds = result.documents.map { it.id }
            for (chunk in result.documents.chunked(500)) {
                val batch = db.batch()
                for (doc in chunk) {
                    batch.delete(doc.reference)
                }
                batch.commit().await()
                FirestoreUsage.recordWrite("deleteRecurringAlarmInstances", FirestoreUsage.WriteType.BATCH_COMMIT, chunk.size)
            }
            Log.d(TAG, "Deleted ${alarmIds.size} recurring alarm instances")
            alarmIds
        }
    }.onFailure { Log.e(TAG, "Error deleting recurring alarm instances", it) }

    /**
     * Gets a single alarm by ID.
     */
    suspend fun getAlarm(alarmId: String): Result<Alarm?> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val doc = alarmRef(userId, alarmId).get().await()
            FirestoreUsage.recordRead("getAlarm", FirestoreUsage.ReadType.DOC_GET)
            if (doc.exists()) {
                mapToAlarm(doc.id, doc.data ?: emptyMap())
            } else {
                null
            }
        }
    }.onFailure { Log.e(TAG, "Error getting alarm", it) }

    /**
     * Gets all alarms for a specific note.
     */
    suspend fun getAlarmsForNote(noteId: String): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("noteId", noteId)
                .get()
                .await()
            FirestoreUsage.recordRead("getAlarmsForNote", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToAlarm(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting alarms for note", it) }

    /**
     * Gets alarms by their document IDs.
     * Fetches each alarm individually via getAlarm() and collects results.
     * Firestore doesn't support whereIn on __name__ across subcollections,
     * so we fetch each document directly.
     */
    suspend fun getAlarmsByIds(alarmIds: List<String>): Result<Map<String, Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            if (alarmIds.isEmpty()) return@withContext emptyMap()
            val userId = requireUserId()
            alarmIds.mapNotNull { alarmId ->
                try {
                    val doc = alarmRef(userId, alarmId).get().await()
                    FirestoreUsage.recordRead("getAlarmsByIds", FirestoreUsage.ReadType.DOC_GET)
                    if (doc.exists()) {
                        alarmId to mapToAlarm(doc.id, doc.data ?: emptyMap())
                    } else null
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching alarm $alarmId", e)
                    null
                }
            }.toMap()
        }
    }.onFailure { Log.e(TAG, "Error getting alarms by IDs", it) }

    /**
     * Gets upcoming alarms (status=PENDING, dueTime != null, ordered by dueTime).
     */
    suspend fun getUpcomingAlarms(): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.PENDING.name)
                .orderBy("dueTime", Query.Direction.ASCENDING)
                .get()
                .await()
            FirestoreUsage.recordRead("getUpcomingAlarms", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    val alarm = mapToAlarm(doc.id, doc.data ?: emptyMap())
                    if (alarm.dueTime != null) alarm else null
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting upcoming alarms", it) }

    /**
     * Gets later alarms (status=PENDING, dueTime == null).
     */
    suspend fun getLaterAlarms(): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.PENDING.name)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .await()
            FirestoreUsage.recordRead("getLaterAlarms", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    val alarm = mapToAlarm(doc.id, doc.data ?: emptyMap())
                    if (alarm.dueTime == null) alarm else null
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting later alarms", it) }

    /**
     * Gets completed alarms (status=DONE, ordered by updatedAt desc).
     */
    suspend fun getCompletedAlarms(): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.DONE.name)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .get()
                .await()
            FirestoreUsage.recordRead("getCompletedAlarms", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToAlarm(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting completed alarms", it) }

    /**
     * Gets cancelled alarms (status=CANCELLED, ordered by updatedAt desc).
     */
    suspend fun getCancelledAlarms(): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.CANCELLED.name)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .get()
                .await()
            FirestoreUsage.recordRead("getCancelledAlarms", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToAlarm(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting cancelled alarms", it) }

    /**
     * Gets all pending alarms for scheduling (used after boot).
     */
    suspend fun getPendingAlarms(): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.PENDING.name)
                .get()
                .await()
            FirestoreUsage.recordRead("getPendingAlarms", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToAlarm(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting pending alarms", it) }

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
            val result = alarmsCollection(userId)
                .whereEqualTo("noteId", noteId)
                .get()
                .await()
            FirestoreUsage.recordRead("alarms.updateLineContentForNote", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)

            val batch = db.batch()
            for (doc in result.documents) {
                batch.update(doc.reference, mapOf(
                    "lineContent" to newContent
                ))
            }
            batch.commit().await()
            FirestoreUsage.recordWrite("alarms.updateLineContentForNote", FirestoreUsage.WriteType.BATCH_COMMIT, result.documents.size)
            Unit
        }
    }

    /**
     * Gets the highest priority active alarm for the current user.
     * Used for status bar icon color.
     */
    suspend fun getHighestPriorityAlarm(): Result<AlarmPriority?> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val now = Timestamp.now()
            val nowMs = now.toDate().time

            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.PENDING.name)
                .get()
                .await()
            FirestoreUsage.recordRead("getHighestPriorityAlarm", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)

            var highestPriority: AlarmPriority? = null

            for (doc in result.documents) {
                val alarm = try {
                    mapToAlarm(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    continue
                }

                // Skip snoozed alarms
                if (alarm.snoozedUntil != null && alarm.snoozedUntil > now) continue
                val due = alarm.dueTime ?: continue

                val priority = alarm.enabledStages
                    .filter { it.resolveTime(due).toDate().time <= nowMs }
                    .maxOfOrNull { stagePriority(it.type) }
                    ?: if (alarm.dueTime != null) AlarmPriority.UPCOMING else null

                if (priority != null && (highestPriority == null || priority > highestPriority)) {
                    highestPriority = priority
                }

                // If we found the highest possible priority, we can stop
                if (highestPriority == AlarmPriority.ALARM) break
            }

            highestPriority
        }
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
     * Gets all alarm instances for a given recurring alarm template, ordered by dueTime.
     * Includes all statuses (PENDING, DONE, CANCELLED).
     */
    suspend fun getInstancesForRecurring(recurringAlarmId: String): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("recurringAlarmId", recurringAlarmId)
                .orderBy("dueTime", Query.Direction.ASCENDING)
                .get()
                .await()
            FirestoreUsage.recordRead("getInstancesForRecurring", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToAlarm(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting instances for recurring alarm", it) }

    /**
     * Gets all pending alarm instances for a given recurring alarm template.
     */
    suspend fun getPendingInstancesForRecurring(recurringAlarmId: String): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("recurringAlarmId", recurringAlarmId)
                .whereEqualTo("status", AlarmStatus.PENDING.name)
                .get()
                .await()
            FirestoreUsage.recordRead("getPendingInstancesForRecurring", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToAlarm(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting pending instances for recurring alarm", it) }

    companion object {
        private const val TAG = "AlarmRepository"
    }
}
