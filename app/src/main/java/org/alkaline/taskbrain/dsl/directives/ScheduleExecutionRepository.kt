package org.alkaline.taskbrain.dsl.directives

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.alkaline.taskbrain.data.FirestoreUsage
import java.util.Date

/**
 * Repository for managing schedule execution records in Firestore.
 *
 * Executions are stored under: users/{userId}/scheduleExecutions/{executionId}
 *
 * This repository handles:
 * - Recording successful executions
 * - Recording missed executions (for user review)
 * - Querying execution history
 * - Marking missed executions as manually executed
 */
class ScheduleExecutionRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    private fun executionsCollection(userId: String) =
        db.collection("users").document(userId).collection("scheduleExecutions")

    private fun executionRef(userId: String, executionId: String): DocumentReference =
        executionsCollection(userId).document(executionId)

    private fun newExecutionRef(userId: String): DocumentReference =
        executionsCollection(userId).document()

    /**
     * Records a successful or failed execution of a schedule.
     */
    suspend fun recordExecution(
        scheduleId: String,
        scheduledFor: Timestamp,
        success: Boolean,
        error: String? = null,
        manualRun: Boolean = false
    ): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = newExecutionRef(userId)
            val data = mapOf(
                "scheduleId" to scheduleId,
                "userId" to userId,
                "scheduledFor" to scheduledFor,
                "executedAt" to FieldValue.serverTimestamp(),
                "success" to success,
                "error" to error,
                "manualRun" to manualRun,
                "createdAt" to FieldValue.serverTimestamp()
            )
            ref.set(data).await()
            FirestoreUsage.recordWrite("recordExecution", FirestoreUsage.WriteType.SET)
            Log.d(TAG, "Execution recorded: ${ref.id} (success=$success, manual=$manualRun)")
            ref.id
        }
    }.onFailure { Log.e(TAG, "Error recording execution", it) }

    /**
     * Records a missed execution (one that was too late to auto-execute).
     * Creates a record with executedAt=null for user review.
     */
    suspend fun recordMissed(
        scheduleId: String,
        scheduledFor: Timestamp
    ): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = newExecutionRef(userId)
            val data = mapOf(
                "scheduleId" to scheduleId,
                "userId" to userId,
                "scheduledFor" to scheduledFor,
                "executedAt" to null,
                "success" to false,
                "error" to null,
                "manualRun" to false,
                "createdAt" to FieldValue.serverTimestamp()
            )
            ref.set(data).await()
            FirestoreUsage.recordWrite("recordMissedExecution", FirestoreUsage.WriteType.SET)
            Log.d(TAG, "Missed execution recorded: ${ref.id}")
            ref.id
        }
    }.onFailure { Log.e(TAG, "Error recording missed execution", it) }

    /**
     * Gets executions from the last 24 hours (for history tab).
     * Only returns completed executions (where executedAt is not null).
     */
    suspend fun getExecutionsLast24Hours(): Result<List<ScheduleExecution>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val twentyFourHoursAgo = Timestamp(Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000))

            val result = executionsCollection(userId)
                .whereGreaterThanOrEqualTo("executedAt", twentyFourHoursAgo)
                .orderBy("executedAt", Query.Direction.DESCENDING)
                .get()
                .await()
            FirestoreUsage.recordRead("getExecutionsLast24Hours", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)

            result.documents.mapNotNull { doc ->
                try {
                    mapToExecution(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing execution", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting executions last 24 hours", it) }

    /**
     * Gets all pending missed executions (where executedAt is null).
     */
    suspend fun getPendingMissedExecutions(): Result<List<ScheduleExecution>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()

            val result = executionsCollection(userId)
                .whereEqualTo("executedAt", null)
                .orderBy("scheduledFor", Query.Direction.ASCENDING)
                .get()
                .await()
            FirestoreUsage.recordRead("getPendingMissedExecutions", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)

            result.documents.mapNotNull { doc ->
                try {
                    mapToExecution(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing execution", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting pending missed executions", it) }

    /**
     * Gets the count of pending missed executions.
     */
    suspend fun getMissedCount(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()

            val result = executionsCollection(userId)
                .whereEqualTo("executedAt", null)
                .get()
                .await()
            FirestoreUsage.recordRead("getMissedCount", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)

            result.size()
        }
    }.onFailure { Log.e(TAG, "Error getting missed count", it) }

    /**
     * Observes the count of pending missed executions as a Flow.
     */
    fun observeMissedCount(): Flow<Int> = callbackFlow {
        val userId = try {
            requireUserId()
        } catch (e: Exception) {
            trySend(0)
            close()
            return@callbackFlow
        }

        var firstDelivered = false
        val listener = executionsCollection(userId)
            .whereEqualTo("executedAt", null)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing missed count", error)
                    trySend(0)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val isLocalEcho = firstDelivered && snapshot.metadata.hasPendingWrites()
                val fromCache = snapshot.metadata.isFromCache
                val type = when {
                    !firstDelivered && fromCache -> FirestoreUsage.ReadType.LISTENER_INITIAL_CACHED
                    !firstDelivered -> FirestoreUsage.ReadType.LISTENER_INITIAL_FRESH
                    isLocalEcho -> FirestoreUsage.ReadType.LISTENER_LOCAL_ECHO
                    fromCache -> FirestoreUsage.ReadType.LISTENER_UPDATE_CACHED
                    else -> FirestoreUsage.ReadType.LISTENER_UPDATE_FRESH
                }
                val docCount = if (firstDelivered) snapshot.documentChanges.size else snapshot.size()
                FirestoreUsage.recordRead("ScheduleExecutionRepo.listener", type, docCount)
                firstDelivered = true
                trySend(snapshot.size())
            }

        awaitClose { listener.remove() }
    }.distinctUntilChanged()

    /**
     * Marks a missed execution as executed after manual run.
     */
    suspend fun markExecuted(
        executionId: String,
        success: Boolean,
        error: String? = null
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            executionRef(userId, executionId).update(
                mapOf(
                    "executedAt" to FieldValue.serverTimestamp(),
                    "success" to success,
                    "error" to error,
                    "manualRun" to true
                )
            ).await()
            FirestoreUsage.recordWrite("markExecutionExecuted", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Execution marked as executed: $executionId (success=$success)")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error marking execution as executed", it) }

    /**
     * Deletes a missed execution record (if user dismisses it without running).
     */
    suspend fun deleteExecution(executionId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            executionRef(userId, executionId).delete().await()
            FirestoreUsage.recordWrite("deleteExecution", FirestoreUsage.WriteType.DELETE)
            Log.d(TAG, "Execution deleted: $executionId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error deleting execution", it) }

    /**
     * Gets a single execution by ID.
     */
    suspend fun getExecution(executionId: String): Result<ScheduleExecution?> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val doc = executionRef(userId, executionId).get().await()
            FirestoreUsage.recordRead("getExecution", FirestoreUsage.ReadType.DOC_GET)
            if (doc.exists()) {
                mapToExecution(doc.id, doc.data ?: emptyMap())
            } else {
                null
            }
        }
    }.onFailure { Log.e(TAG, "Error getting execution", it) }

    private fun mapToExecution(id: String, data: Map<String, Any?>): ScheduleExecution = ScheduleExecution(
        id = id,
        scheduleId = data["scheduleId"] as? String ?: "",
        userId = data["userId"] as? String ?: "",
        scheduledFor = data["scheduledFor"] as? Timestamp,
        executedAt = data["executedAt"] as? Timestamp,
        success = data["success"] as? Boolean ?: false,
        error = data["error"] as? String,
        manualRun = data["manualRun"] as? Boolean ?: false,
        createdAt = data["createdAt"] as? Timestamp
    )

    /**
     * Deletes missed executions older than the specified number of days.
     * Called during ScheduleWorker runs to prevent unbounded accumulation.
     */
    suspend fun cleanupOldMissedExecutions(olderThanDays: Int = 30): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val cutoffTime = Timestamp(Date(System.currentTimeMillis() - olderThanDays.toLong() * 24 * 60 * 60 * 1000))

            // Find old missed executions (executedAt == null AND createdAt < cutoff)
            val result = executionsCollection(userId)
                .whereEqualTo("executedAt", null)
                .whereLessThan("createdAt", cutoffTime)
                .get()
                .await()
            FirestoreUsage.recordRead("cleanupOldMissedExecutions", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)

            if (result.isEmpty) {
                return@withContext 0
            }

            val batch = db.batch()
            for (doc in result.documents) {
                batch.delete(doc.reference)
            }
            batch.commit().await()
            FirestoreUsage.recordWrite("cleanupOldMissedExecutions", FirestoreUsage.WriteType.BATCH_COMMIT, result.documents.size)

            val count = result.size()
            Log.d(TAG, "Cleaned up $count old missed executions (older than $olderThanDays days)")
            count
        }
    }.onFailure { Log.e(TAG, "Error cleaning up old missed executions", it) }

    companion object {
        private const val TAG = "ScheduleExecRepo"
    }
}
