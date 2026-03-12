package org.alkaline.taskbrain.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Repository for managing recurring alarm templates in Firestore.
 * Stored under /users/{userId}/recurringAlarms/{recurringAlarmId}
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

    suspend fun create(recurringAlarm: RecurringAlarm): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = newDocRef(userId)
            val data = toMap(recurringAlarm.copy(id = ref.id, userId = userId)).toMutableMap()
            data["createdAt"] = FieldValue.serverTimestamp()
            data["updatedAt"] = FieldValue.serverTimestamp()
            ref.set(data).await()
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
            Log.d(TAG, "RecurringAlarm updated: ${recurringAlarm.id}")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error updating recurring alarm", it) }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            docRef(userId, id).delete().await()
            Log.d(TAG, "RecurringAlarm deleted: $id")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error deleting recurring alarm", it) }

    suspend fun get(id: String): Result<RecurringAlarm?> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val doc = docRef(userId, id).get().await()
            if (doc.exists()) fromMap(doc.id, doc.data ?: emptyMap()) else null
        }
    }.onFailure { Log.e(TAG, "Error getting recurring alarm", it) }

    suspend fun getActiveRecurringAlarms(): Result<List<RecurringAlarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = collection(userId)
                .whereEqualTo("status", RecurringAlarmStatus.ACTIVE.name)
                .get()
                .await()
            result.documents.mapNotNull { doc ->
                try {
                    fromMap(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing recurring alarm", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting active recurring alarms", it) }

    suspend fun getForNote(noteId: String): Result<List<RecurringAlarm>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = collection(userId)
                .whereEqualTo("noteId", noteId)
                .get()
                .await()
            result.documents.mapNotNull { doc ->
                try {
                    fromMap(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing recurring alarm", e)
                    null
                }
            }
        }
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
            Log.d(TAG, "Recorded completion for recurring alarm: $id")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error recording completion", it) }

    suspend fun updateCurrentAlarmId(id: String, alarmId: String?): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            docRef(userId, id).update(
                mapOf(
                    "currentAlarmId" to alarmId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
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
            Log.d(TAG, "Recurring alarm ended: $id")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error ending recurring alarm", it) }

    suspend fun updateLineContentForNote(noteId: String, newContent: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = collection(userId)
                .whereEqualTo("noteId", noteId)
                .get()
                .await()
            val batch = db.batch()
            for (doc in result.documents) {
                batch.update(doc.reference, mapOf("lineContent" to newContent))
            }
            batch.commit().await()
        }
    }

    private fun toMap(ra: RecurringAlarm): Map<String, Any?> = mapOf(
        "userId" to ra.userId,
        "noteId" to ra.noteId,
        "lineContent" to ra.lineContent,
        "recurrenceType" to ra.recurrenceType.name,
        "rrule" to ra.rrule,
        "relativeIntervalMs" to ra.relativeIntervalMs,
        "upcomingOffsetMs" to ra.upcomingOffsetMs,
        "notifyOffsetMs" to ra.notifyOffsetMs,
        "urgentOffsetMs" to ra.urgentOffsetMs,
        "alarmOffsetMs" to ra.alarmOffsetMs,
        "endDate" to ra.endDate,
        "repeatCount" to ra.repeatCount,
        "completionCount" to ra.completionCount,
        "lastCompletionDate" to ra.lastCompletionDate,
        "currentAlarmId" to ra.currentAlarmId,
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
        upcomingOffsetMs = (data["upcomingOffsetMs"] as? Number)?.toLong(),
        notifyOffsetMs = (data["notifyOffsetMs"] as? Number)?.toLong(),
        urgentOffsetMs = (data["urgentOffsetMs"] as? Number)?.toLong(),
        alarmOffsetMs = (data["alarmOffsetMs"] as? Number)?.toLong(),
        endDate = data["endDate"] as? Timestamp,
        repeatCount = (data["repeatCount"] as? Number)?.toInt(),
        completionCount = (data["completionCount"] as? Number)?.toInt() ?: 0,
        lastCompletionDate = data["lastCompletionDate"] as? Timestamp,
        currentAlarmId = data["currentAlarmId"] as? String,
        status = try {
            RecurringAlarmStatus.valueOf(data["status"] as? String ?: RecurringAlarmStatus.ACTIVE.name)
        } catch (e: IllegalArgumentException) { RecurringAlarmStatus.ACTIVE },
        createdAt = data["createdAt"] as? Timestamp,
        updatedAt = data["updatedAt"] as? Timestamp
    )

    companion object {
        private const val TAG = "RecurringAlarmRepo"
    }
}
