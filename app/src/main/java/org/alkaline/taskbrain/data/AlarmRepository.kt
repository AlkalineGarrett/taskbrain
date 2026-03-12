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

    /**
     * Creates a new alarm.
     * Returns the ID of the created alarm.
     */
    suspend fun createAlarm(alarm: Alarm): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = newAlarmRef(userId)
            val data = alarmToMap(alarm.copy(id = ref.id, userId = userId)).toMutableMap()
            data["createdAt"] = FieldValue.serverTimestamp()
            data["updatedAt"] = FieldValue.serverTimestamp()
            ref.set(data).await()
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
            Log.d(TAG, "Alarm updated: ${alarm.id}")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating alarm", it) }

    /**
     * Deletes an alarm permanently.
     */
    suspend fun deleteAlarm(alarmId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            alarmRef(userId, alarmId).delete().await()
            Log.d(TAG, "Alarm deleted: $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error deleting alarm", it) }

    /**
     * Gets a single alarm by ID.
     */
    suspend fun getAlarm(alarmId: String): Result<Alarm?> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val doc = alarmRef(userId, alarmId).get().await()
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
     * Gets all alarms for multiple note IDs in a single query.
     * Firestore whereIn supports up to 30 values; for larger sets,
     * batches the queries automatically.
     */
    suspend fun getAlarmsForNotes(noteIds: List<String>): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            if (noteIds.isEmpty()) return@withContext emptyList()
            val userId = requireUserId()
            noteIds.chunked(30).flatMap { chunk ->
                val result = alarmsCollection(userId)
                    .whereIn("noteId", chunk)
                    .get()
                    .await()
                result.documents.mapNotNull { doc ->
                    try {
                        mapToAlarm(doc.id, doc.data ?: emptyMap())
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing alarm", e)
                        null
                    }
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting alarms for notes", it) }

    /**
     * Gets upcoming alarms (status=PENDING, upcomingTime != null, ordered by upcomingTime).
     */
    suspend fun getUpcomingAlarms(): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.PENDING.name)
                .orderBy("upcomingTime", Query.Direction.ASCENDING)
                .get()
                .await()
            result.documents.mapNotNull { doc ->
                try {
                    val alarm = mapToAlarm(doc.id, doc.data ?: emptyMap())
                    // Filter to only those with upcomingTime set
                    if (alarm.upcomingTime != null) alarm else null
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting upcoming alarms", it) }

    /**
     * Gets later alarms (status=PENDING, upcomingTime == null).
     */
    suspend fun getLaterAlarms(): Result<List<Alarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.PENDING.name)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .await()
            result.documents.mapNotNull { doc ->
                try {
                    val alarm = mapToAlarm(doc.id, doc.data ?: emptyMap())
                    // Filter to only those without upcomingTime
                    if (alarm.upcomingTime == null) alarm else null
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
            Log.d(TAG, "Alarm reactivated: $alarmId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error reactivating alarm", it) }

    /**
     * Updates the line content for all alarms associated with a note.
     * Called when a note is saved to keep alarm display text in sync.
     * Note: Does NOT update updatedAt to preserve completion/cancellation timestamps.
     */
    suspend fun updateLineContentForNote(noteId: String, newContent: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = alarmsCollection(userId)
                .whereEqualTo("noteId", noteId)
                .get()
                .await()

            val batch = db.batch()
            for (doc in result.documents) {
                batch.update(doc.reference, mapOf(
                    "lineContent" to newContent
                ))
            }
            batch.commit().await()
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

            val result = alarmsCollection(userId)
                .whereEqualTo("status", AlarmStatus.PENDING.name)
                .get()
                .await()

            var highestPriority: AlarmPriority? = null

            for (doc in result.documents) {
                val alarm = try {
                    mapToAlarm(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    continue
                }

                // Skip snoozed alarms
                if (alarm.snoozedUntil != null && alarm.snoozedUntil > now) {
                    continue
                }

                // Check each threshold from highest to lowest priority
                val priority = when {
                    alarm.alarmTime != null && alarm.alarmTime <= now -> AlarmPriority.ALARM
                    alarm.urgentTime != null && alarm.urgentTime <= now -> AlarmPriority.URGENT
                    alarm.notifyTime != null && alarm.notifyTime <= now -> AlarmPriority.NOTIFY
                    alarm.upcomingTime != null -> AlarmPriority.UPCOMING
                    else -> null
                }

                if (priority != null && (highestPriority == null || priority > highestPriority)) {
                    highestPriority = priority
                }

                // If we found the highest possible priority, we can stop
                if (highestPriority == AlarmPriority.ALARM) {
                    break
                }
            }

            highestPriority
        }
    }.onFailure { Log.e(TAG, "Error getting highest priority alarm", it) }

    private fun alarmToMap(alarm: Alarm): Map<String, Any?> = mapOf(
        "userId" to alarm.userId,
        "noteId" to alarm.noteId,
        "lineContent" to alarm.lineContent,
        "createdAt" to alarm.createdAt,
        "updatedAt" to alarm.updatedAt,
        "upcomingTime" to alarm.upcomingTime,
        "notifyTime" to alarm.notifyTime,
        "urgentTime" to alarm.urgentTime,
        "alarmTime" to alarm.alarmTime,
        "status" to alarm.status.name,
        "snoozedUntil" to alarm.snoozedUntil,
        "recurringAlarmId" to alarm.recurringAlarmId
    )

    private fun mapToAlarm(id: String, data: Map<String, Any?>): Alarm = Alarm(
        id = id,
        userId = data["userId"] as? String ?: "",
        noteId = data["noteId"] as? String ?: "",
        lineContent = data["lineContent"] as? String ?: "",
        createdAt = data["createdAt"] as? Timestamp,
        updatedAt = data["updatedAt"] as? Timestamp,
        upcomingTime = data["upcomingTime"] as? Timestamp,
        notifyTime = data["notifyTime"] as? Timestamp,
        urgentTime = data["urgentTime"] as? Timestamp,
        alarmTime = data["alarmTime"] as? Timestamp,
        status = try {
            AlarmStatus.valueOf(data["status"] as? String ?: AlarmStatus.PENDING.name)
        } catch (e: IllegalArgumentException) {
            AlarmStatus.PENDING
        },
        snoozedUntil = data["snoozedUntil"] as? Timestamp,
        recurringAlarmId = data["recurringAlarmId"] as? String
    )

    companion object {
        private const val TAG = "AlarmRepository"
    }
}
