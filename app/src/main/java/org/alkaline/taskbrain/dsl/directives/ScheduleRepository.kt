package org.alkaline.taskbrain.dsl.directives

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
import org.alkaline.taskbrain.data.FirestoreUsage
import org.alkaline.taskbrain.dsl.cache.DailyTimeTrigger
import org.alkaline.taskbrain.dsl.runtime.values.ScheduleFrequency
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/**
 * Repository for managing scheduled directive actions in Firestore.
 *
 * Schedules are stored under: users/{userId}/schedules/{scheduleId}
 *
 * This repository handles:
 * - Creating/updating schedules when directives with schedule() are saved
 * - Querying schedules that are due for execution
 * - Recording execution results and failures
 * - Pausing/cancelling schedules
 */
class ScheduleRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    private fun schedulesCollection(userId: String) =
        db.collection("users").document(userId).collection("schedules")

    private fun scheduleRef(userId: String, scheduleId: String): DocumentReference =
        schedulesCollection(userId).document(scheduleId)

    private fun newScheduleRef(userId: String): DocumentReference =
        schedulesCollection(userId).document()

    /**
     * Creates or updates a schedule.
     *
     * If a schedule with the same directiveHash already exists for the note,
     * it will be updated. Otherwise, a new schedule is created.
     *
     * @param noteId The note containing the schedule directive
     * @param notePath The note's path for display purposes
     * @param directiveHash Hash of the directive source text
     * @param directiveSource The full directive source text (for re-parsing)
     * @param frequency The schedule frequency
     * @param atTime Optional specific time for daily/weekly schedules
     * @param precise Whether to use exact timing (AlarmManager) vs approximate (WorkManager)
     * @return The schedule ID
     */
    suspend fun upsertSchedule(
        noteId: String,
        notePath: String,
        directiveHash: String,
        directiveSource: String,
        frequency: ScheduleFrequency,
        atTime: String? = null,
        precise: Boolean = false
    ): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()

            // Check if schedule already exists for this directive
            val existing = findByHash(directiveHash).getOrNull()

            if (existing != null) {
                // Update existing schedule
                val updates = mapOf(
                    "notePath" to notePath,
                    "directiveSource" to directiveSource,
                    "frequency" to frequency.identifier,
                    "atTime" to atTime,
                    "precise" to precise,
                    "nextExecution" to calculateNextExecution(frequency, atTime),
                    "status" to ScheduleStatus.ACTIVE.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                scheduleRef(userId, existing.id).update(updates).await()
                FirestoreUsage.recordWrite("upsertSchedule", FirestoreUsage.WriteType.UPDATE)
                Log.d(TAG, "Schedule updated: ${existing.id}")
                existing.id
            } else {
                // Create new schedule
                val ref = newScheduleRef(userId)
                val data = mapOf(
                    "userId" to userId,
                    "noteId" to noteId,
                    "notePath" to notePath,
                    "directiveHash" to directiveHash,
                    "directiveSource" to directiveSource,
                    "frequency" to frequency.identifier,
                    "atTime" to atTime,
                    "precise" to precise,
                    "nextExecution" to calculateNextExecution(frequency, atTime),
                    "status" to ScheduleStatus.ACTIVE.name,
                    "lastExecution" to null,
                    "lastError" to null,
                    "failureCount" to 0,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.set(data).await()
                FirestoreUsage.recordWrite("upsertSchedule", FirestoreUsage.WriteType.SET)
                Log.d(TAG, "Schedule created: ${ref.id}")
                ref.id
            }
        }
    }.onFailure { Log.e(TAG, "Error upserting schedule", it) }

    /**
     * Finds a schedule by its directive hash.
     */
    suspend fun findByHash(directiveHash: String): Result<Schedule?> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = schedulesCollection(userId)
                .whereEqualTo("directiveHash", directiveHash)
                .limit(1)
                .get()
                .await()
            FirestoreUsage.recordRead("findScheduleByHash", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.firstOrNull()?.let { doc ->
                mapToSchedule(doc.id, doc.data ?: emptyMap())
            }
        }
    }.onFailure { Log.e(TAG, "Error finding schedule by hash", it) }

    /**
     * Gets all schedules for a specific note.
     */
    suspend fun getSchedulesForNote(noteId: String): Result<List<Schedule>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = schedulesCollection(userId)
                .whereEqualTo("noteId", noteId)
                .get()
                .await()
            FirestoreUsage.recordRead("getSchedulesForNote", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToSchedule(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing schedule", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting schedules for note", it) }

    /**
     * Gets all active schedules for the current user.
     */
    suspend fun getAllActiveSchedules(): Result<List<Schedule>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = schedulesCollection(userId)
                .whereEqualTo("status", ScheduleStatus.ACTIVE.name)
                .get()
                .await()
            FirestoreUsage.recordRead("getAllActiveSchedules", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToSchedule(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing schedule", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting active schedules", it) }

    /**
     * Gets schedules that are due for execution.
     *
     * @param beforeTime Get schedules with nextExecution before this time
     */
    suspend fun getDueSchedules(beforeTime: Timestamp = Timestamp.now()): Result<List<Schedule>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = schedulesCollection(userId)
                .whereEqualTo("status", ScheduleStatus.ACTIVE.name)
                .whereLessThanOrEqualTo("nextExecution", beforeTime)
                .orderBy("nextExecution", Query.Direction.ASCENDING)
                .get()
                .await()
            FirestoreUsage.recordRead("getDueSchedules", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)
            result.documents.mapNotNull { doc ->
                try {
                    mapToSchedule(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing schedule", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting due schedules", it) }

    /**
     * Marks a schedule as successfully executed and schedules the next execution.
     */
    suspend fun markExecuted(scheduleId: String, success: Boolean, error: String? = null): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = scheduleRef(userId, scheduleId)
            val doc = ref.get().await()
            FirestoreUsage.recordRead("markScheduleExecuted", FirestoreUsage.ReadType.DOC_GET)

            if (!doc.exists()) {
                throw IllegalStateException("Schedule not found: $scheduleId")
            }

            val schedule = mapToSchedule(doc.id, doc.data ?: emptyMap())

            val updates = if (success) {
                mapOf(
                    "lastExecution" to FieldValue.serverTimestamp(),
                    "lastError" to null,
                    "failureCount" to 0,
                    "nextExecution" to calculateNextExecution(schedule.frequency, schedule.atTime),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            } else {
                val newFailureCount = schedule.failureCount + 1
                val newStatus = if (newFailureCount >= Schedule.MAX_FAILURES) {
                    ScheduleStatus.FAILED.name
                } else {
                    ScheduleStatus.ACTIVE.name
                }
                mapOf(
                    "lastExecution" to FieldValue.serverTimestamp(),
                    "lastError" to error,
                    "failureCount" to newFailureCount,
                    "status" to newStatus,
                    "nextExecution" to calculateNextExecution(schedule.frequency, schedule.atTime),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            }

            ref.update(updates).await()
            FirestoreUsage.recordWrite("markScheduleExecuted", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Schedule marked executed (success=$success): $scheduleId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error marking schedule executed", it) }

    /**
     * Pauses a schedule.
     */
    suspend fun pauseSchedule(scheduleId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            scheduleRef(userId, scheduleId).update(
                mapOf(
                    "status" to ScheduleStatus.PAUSED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("pauseSchedule", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Schedule paused: $scheduleId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error pausing schedule", it) }

    /**
     * Resumes a paused schedule.
     */
    suspend fun resumeSchedule(scheduleId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = scheduleRef(userId, scheduleId)
            val doc = ref.get().await()
            FirestoreUsage.recordRead("resumeSchedule", FirestoreUsage.ReadType.DOC_GET)

            if (!doc.exists()) {
                throw IllegalStateException("Schedule not found: $scheduleId")
            }

            val schedule = mapToSchedule(doc.id, doc.data ?: emptyMap())

            ref.update(
                mapOf(
                    "status" to ScheduleStatus.ACTIVE.name,
                    "failureCount" to 0,
                    "lastError" to null,
                    "nextExecution" to calculateNextExecution(schedule.frequency, schedule.atTime),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("resumeSchedule", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Schedule resumed: $scheduleId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error resuming schedule", it) }

    /**
     * Cancels a schedule (typically when the note is deleted).
     */
    suspend fun cancelSchedule(scheduleId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            scheduleRef(userId, scheduleId).update(
                mapOf(
                    "status" to ScheduleStatus.CANCELLED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("cancelSchedule", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Schedule cancelled: $scheduleId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error cancelling schedule", it) }

    /**
     * Deletes a schedule permanently.
     */
    suspend fun deleteSchedule(scheduleId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            scheduleRef(userId, scheduleId).delete().await()
            FirestoreUsage.recordWrite("deleteSchedule", FirestoreUsage.WriteType.DELETE)
            Log.d(TAG, "Schedule deleted: $scheduleId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error deleting schedule", it) }

    /**
     * Cancels all schedules for a note (called when note is deleted).
     */
    suspend fun cancelSchedulesForNote(noteId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val result = schedulesCollection(userId)
                .whereEqualTo("noteId", noteId)
                .get()
                .await()
            FirestoreUsage.recordRead("cancelSchedulesForNote", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)

            val batch = db.batch()
            for (doc in result.documents) {
                batch.update(
                    doc.reference,
                    mapOf(
                        "status" to ScheduleStatus.CANCELLED.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
            FirestoreUsage.recordWrite("cancelSchedulesForNote", FirestoreUsage.WriteType.BATCH_COMMIT, result.documents.size)
            Log.d(TAG, "Cancelled ${result.documents.size} schedules for note: $noteId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error cancelling schedules for note", it) }

    /**
     * Gets a single schedule by ID.
     */
    suspend fun getSchedule(scheduleId: String): Result<Schedule?> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val doc = scheduleRef(userId, scheduleId).get().await()
            FirestoreUsage.recordRead("getSchedule", FirestoreUsage.ReadType.DOC_GET)
            if (doc.exists()) {
                mapToSchedule(doc.id, doc.data ?: emptyMap())
            } else {
                null
            }
        }
    }.onFailure { Log.e(TAG, "Error getting schedule", it) }

    /**
     * Gets all active schedules that are due within the next 24 hours.
     */
    suspend fun getSchedulesForNext24Hours(): Result<List<Schedule>> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val now = Timestamp.now()
            val twentyFourHoursFromNow = Timestamp(
                Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000)
            )

            val result = schedulesCollection(userId)
                .whereEqualTo("status", ScheduleStatus.ACTIVE.name)
                .whereGreaterThan("nextExecution", now)
                .whereLessThanOrEqualTo("nextExecution", twentyFourHoursFromNow)
                .orderBy("nextExecution", Query.Direction.ASCENDING)
                .get()
                .await()
            FirestoreUsage.recordRead("getSchedulesForNext24Hours", FirestoreUsage.ReadType.GET_DOCS, result.documents.size)

            result.documents.mapNotNull { doc ->
                try {
                    mapToSchedule(doc.id, doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing schedule", e)
                    null
                }
            }
        }
    }.onFailure { Log.e(TAG, "Error getting schedules for next 24 hours", it) }

    /**
     * Advances the nextExecution time for a schedule without marking it as executed.
     *
     * Used when a schedule is too late to auto-execute and is marked as missed.
     */
    suspend fun advanceNextExecution(scheduleId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val userId = requireUserId()
            val ref = scheduleRef(userId, scheduleId)
            val doc = ref.get().await()
            FirestoreUsage.recordRead("advanceNextExecution", FirestoreUsage.ReadType.DOC_GET)

            if (!doc.exists()) {
                throw IllegalStateException("Schedule not found: $scheduleId")
            }

            val schedule = mapToSchedule(doc.id, doc.data ?: emptyMap())

            ref.update(
                mapOf(
                    "nextExecution" to calculateNextExecution(schedule.frequency, schedule.atTime),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            FirestoreUsage.recordWrite("advanceNextExecution", FirestoreUsage.WriteType.UPDATE)
            Log.d(TAG, "Schedule nextExecution advanced: $scheduleId")
            Unit
        }
    }.onFailure { Log.e(TAG, "Error advancing schedule nextExecution", it) }

    /**
     * Calculates the next execution time based on frequency.
     *
     * Uses DailyTimeTrigger for daily schedules with specific times,
     * consistent with the view caching refresh trigger infrastructure.
     */
    private fun calculateNextExecution(frequency: ScheduleFrequency, atTime: String?): Timestamp {
        val now = LocalDateTime.now()

        val nextTime = when (frequency) {
            ScheduleFrequency.HOURLY -> {
                // Next hour, at the start of the hour
                now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
            }
            ScheduleFrequency.DAILY -> {
                if (atTime != null) {
                    // Use DailyTimeTrigger for time-specific daily schedules
                    val triggerTime = LocalTime.parse(atTime)
                    DailyTimeTrigger(triggerTime).nextTriggerAfter(now)
                } else {
                    // No specific time, schedule for same time tomorrow
                    now.plusDays(1)
                }
            }
            ScheduleFrequency.WEEKLY -> {
                // Weekly at the same day/time, or at specified time if given
                val targetTime = if (atTime != null) {
                    LocalTime.parse(atTime)
                } else {
                    now.toLocalTime()
                }
                // Schedule for same day next week at the target time
                now.plusWeeks(1).with(targetTime)
            }
        }

        return Timestamp(Date.from(nextTime.atZone(ZoneId.systemDefault()).toInstant()))
    }

    private fun scheduleToMap(schedule: Schedule): Map<String, Any?> = mapOf(
        "userId" to schedule.userId,
        "noteId" to schedule.noteId,
        "notePath" to schedule.notePath,
        "directiveHash" to schedule.directiveHash,
        "directiveSource" to schedule.directiveSource,
        "frequency" to schedule.frequency.identifier,
        "atTime" to schedule.atTime,
        "precise" to schedule.precise,
        "nextExecution" to schedule.nextExecution,
        "status" to schedule.status.name,
        "lastExecution" to schedule.lastExecution,
        "lastError" to schedule.lastError,
        "failureCount" to schedule.failureCount,
        "createdAt" to schedule.createdAt,
        "updatedAt" to schedule.updatedAt
    )

    private fun mapToSchedule(id: String, data: Map<String, Any?>): Schedule = Schedule(
        id = id,
        userId = data["userId"] as? String ?: "",
        noteId = data["noteId"] as? String ?: "",
        notePath = data["notePath"] as? String ?: "",
        directiveHash = data["directiveHash"] as? String ?: "",
        directiveSource = data["directiveSource"] as? String ?: "",
        frequency = ScheduleFrequency.fromIdentifier(data["frequency"] as? String ?: "daily")
            ?: ScheduleFrequency.DAILY,
        atTime = data["atTime"] as? String,
        precise = data["precise"] as? Boolean ?: false,
        nextExecution = data["nextExecution"] as? Timestamp,
        status = try {
            ScheduleStatus.valueOf(data["status"] as? String ?: ScheduleStatus.ACTIVE.name)
        } catch (e: IllegalArgumentException) {
            ScheduleStatus.ACTIVE
        },
        lastExecution = data["lastExecution"] as? Timestamp,
        lastError = data["lastError"] as? String,
        failureCount = (data["failureCount"] as? Long)?.toInt() ?: 0,
        createdAt = data["createdAt"] as? Timestamp,
        updatedAt = data["updatedAt"] as? Timestamp
    )

    companion object {
        private const val TAG = "ScheduleRepository"
    }
}
