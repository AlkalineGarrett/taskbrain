package org.alkaline.taskbrain.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmUpdateEvent
import org.alkaline.taskbrain.data.RecurringAlarm
import org.alkaline.taskbrain.data.RecurringAlarmRepository
import org.alkaline.taskbrain.data.RecurrenceType
import org.alkaline.taskbrain.ui.alarm.AlarmErrorActivity
import java.util.Date

/**
 * Manages the lifecycle of recurring alarm instances.
 *
 * For FIXED recurrence: creates next instance when the scheduled time passes.
 * For RELATIVE recurrence: creates next instance when the current one is completed or cancelled.
 */
class RecurrenceScheduler(
    private val context: Context,
    private val recurringRepo: RecurringAlarmRepository = RecurringAlarmRepository(),
    private val alarmRepo: AlarmRepository = AlarmRepository(),
    private val alarmScheduler: AlarmScheduler = AlarmScheduler(context),
    private val urgentStateManager: UrgentStateManager = UrgentStateManager(context),
    private val notificationManager: NotificationManager? = context.getSystemService(NotificationManager::class.java)
) {

    /**
     * Called when an alarm instance is completed (marked done).
     * For both FIXED and RELATIVE types, records completion.
     * For RELATIVE type, creates the next instance.
     */
    suspend fun onInstanceCompleted(alarm: Alarm) {
        val recurringId = alarm.recurringAlarmId ?: return
        val recurring = recurringRepo.get(recurringId)
            .onFailure { showError("Failed to fetch recurring alarm template $recurringId: ${it.message}") }
            .getOrNull() ?: return

        val completionDate = Timestamp.now()
        recurringRepo.recordCompletion(recurringId, completionDate)

        val updated = recurring.copy(
            completionCount = recurring.completionCount + 1,
            lastCompletionDate = completionDate
        )

        if (updated.hasReachedEnd) {
            recurringRepo.end(recurringId)
            Log.d(TAG, "Recurring alarm $recurringId reached end condition")
            return
        }

        when (recurring.recurrenceType) {
            RecurrenceType.RELATIVE -> createNextInstance(updated, completionDate.toDate())
            RecurrenceType.FIXED -> createNextIfNeeded(updated, alarm)
        }
    }

    /**
     * Called when an alarm instance is cancelled/skipped.
     * Creates the next instance so the recurring schedule continues.
     */
    suspend fun onInstanceCancelled(alarm: Alarm) {
        val recurringId = alarm.recurringAlarmId ?: return
        val recurring = recurringRepo.get(recurringId)
            .onFailure { showError("Failed to fetch recurring alarm template $recurringId: ${it.message}") }
            .getOrNull() ?: return

        if (recurring.hasReachedEnd) return

        when (recurring.recurrenceType) {
            RecurrenceType.RELATIVE -> {
                val anchorDate = recurring.lastCompletionDate?.toDate()
                    ?: recurring.createdAt?.toDate()
                    ?: return
                createNextInstance(recurring, anchorDate)
            }
            RecurrenceType.FIXED -> createNextIfNeeded(recurring, alarm)
        }
    }

    /**
     * For FIXED recurrences, creates the next instance only if one hasn't already
     * been created (e.g., by [onFixedInstanceTriggered] at alarm trigger time).
     */
    private suspend fun createNextIfNeeded(recurring: RecurringAlarm, completedAlarm: Alarm) {
        // If currentAlarmId differs from the completed alarm, a next instance already exists
        if (recurring.currentAlarmId != null && recurring.currentAlarmId != completedAlarm.id) {
            Log.d(TAG, "Next instance already exists for recurring alarm ${recurring.id}")
            return
        }
        val baseTime = completedAlarm.dueTime?.toDate() ?: return
        createNextInstance(recurring, baseTime)
    }

    /**
     * Called when a FIXED alarm's scheduled time passes (from AlarmReceiver).
     * Creates the next instance for the next occurrence.
     */
    suspend fun onFixedInstanceTriggered(alarm: Alarm) {
        val recurringId = alarm.recurringAlarmId ?: return
        val recurring = recurringRepo.get(recurringId)
            .onFailure { showError("Failed to fetch recurring alarm template $recurringId: ${it.message}") }
            .getOrNull() ?: return

        if (recurring.recurrenceType != RecurrenceType.FIXED) return
        if (recurring.hasReachedEnd) return

        // Guard: if currentAlarmId differs from the triggered alarm, a next instance
        // was already created by a previous stage trigger — don't create a duplicate.
        if (recurring.currentAlarmId != null && recurring.currentAlarmId != alarm.id) {
            Log.d(TAG, "Next instance already exists for recurring alarm ${recurring.id}")
            return
        }

        val baseTime = alarm.dueTime?.toDate() ?: return

        // Pass triggering alarm ID so cleanup doesn't cancel it — it's still active
        // (a pre-due stage like notification may have triggered this)
        createNextInstance(recurring, baseTime, triggeringAlarmId = alarm.id)
    }

    /**
     * Creates the next alarm instance for a recurring alarm.
     *
     * @param recurring The recurring alarm template
     * @param afterDate The reference date to compute the next occurrence from
     * @param triggeringAlarmId Optional ID of the alarm that triggered this creation
     *   (e.g., when a pre-due stage fires). This alarm won't be cleaned up as orphaned.
     */
    private suspend fun createNextInstance(
        recurring: RecurringAlarm,
        afterDate: Date,
        triggeringAlarmId: String? = null
    ) {
        val nextBaseTime = computeNextBaseTime(recurring, afterDate) ?: return

        val alarm = buildAlarmFromTemplate(recurring, nextBaseTime)

        val alarmId = alarmRepo.createAlarm(alarm)
            .onFailure { showError("Failed to create next instance for recurring alarm ${recurring.id}: ${it.message}") }
            .getOrNull() ?: return

        recurringRepo.updateCurrentAlarmId(recurring.id, alarmId)
            .onFailure { showError("Failed to update currentAlarmId for recurring alarm ${recurring.id}: ${it.message}") }

        val createdAlarm = alarmRepo.getAlarm(alarmId)
            .onFailure { showError("Created alarm $alarmId but failed to fetch it for scheduling: ${it.message}") }
            .getOrNull()
        if (createdAlarm != null) {
            alarmScheduler.scheduleAlarm(createdAlarm)
        } else {
            // Fetch failed — schedule with the local copy as fallback
            alarmScheduler.scheduleAlarm(alarm.copy(id = alarmId))
        }

        // Clean up orphaned instances: cancel PendingIntents and mark as cancelled
        // for any other PENDING instances of this recurring alarm.
        val protectedIds = buildSet {
            add(alarmId)
            triggeringAlarmId?.let { add(it) }
        }
        cleanUpOrphanedInstances(recurring.id, protectedIds)

        AlarmUpdateEvent.notifyAlarmUpdated()
        Log.d(TAG, "Created next instance $alarmId for recurring alarm ${recurring.id}")
    }

    /**
     * Finds and deactivates orphaned PENDING instances for a recurring alarm.
     * Orphans can occur if multiple stages fire for the same alarm, each triggering
     * [onFixedInstanceTriggered] before the deduplication guard was added.
     *
     * @param protectedAlarmIds IDs to skip — includes the newly created instance and
     *   optionally the triggering alarm (which may still be active if a pre-due stage fired).
     */
    private suspend fun cleanUpOrphanedInstances(recurringAlarmId: String, protectedAlarmIds: Set<String>) {
        val pendingInstances = alarmRepo.getPendingInstancesForRecurring(recurringAlarmId)
            .onFailure { showError("Failed to query orphaned instances for recurring alarm $recurringAlarmId: ${it.message}") }
            .getOrNull() ?: return

        for (instance in pendingInstances) {
            if (instance.id in protectedAlarmIds) continue
            Log.w(TAG, "Cleaning up orphaned instance ${instance.id} for recurring alarm $recurringAlarmId")
            alarmScheduler.cancelAlarm(instance.id)
            urgentStateManager.exitUrgentState(instance.id)
            notificationManager?.cancel(AlarmUtils.getNotificationId(instance.id))
            alarmRepo.markCancelled(instance.id)
        }
    }

    /**
     * Computes the next base time (the anchor point from which threshold offsets are applied).
     */
    private fun computeNextBaseTime(recurring: RecurringAlarm, afterDate: Date): Date? {
        return when (recurring.recurrenceType) {
            RecurrenceType.FIXED -> {
                val rrule = recurring.rrule ?: return null
                RRuleParser.nextOccurrence(rrule, afterDate)
            }
            RecurrenceType.RELATIVE -> {
                val intervalMs = recurring.relativeIntervalMs ?: return null
                Date(afterDate.time + intervalMs)
            }
        }
    }

    /**
     * Builds an [Alarm] instance from a recurring alarm template and a base time.
     * Computes the due time from the base time + dueOffsetMs and carries over stages.
     */
    private fun buildAlarmFromTemplate(recurring: RecurringAlarm, baseTime: Date): Alarm {
        val baseMs = baseTime.time
        return Alarm(
            noteId = recurring.noteId,
            lineContent = recurring.lineContent,
            dueTime = Timestamp(Date(baseMs + recurring.dueOffsetMs)),
            stages = recurring.stages,
            recurringAlarmId = recurring.id
        )
    }

    /**
     * Bootstraps recurring alarms on app launch or boot.
     * For each active recurring alarm, checks if a current instance exists
     * and is still pending. If not, creates the next one.
     */
    suspend fun bootstrapRecurringAlarms() {
        val recurringAlarms = recurringRepo.getActiveRecurringAlarms()
            .onFailure { showError("Failed to fetch active recurring alarms: ${it.message}") }
            .getOrNull() ?: return

        for (recurring in recurringAlarms) {
            try {
                bootstrapSingleRecurringAlarm(recurring)
            } catch (e: Exception) {
                Log.e(TAG, "Error bootstrapping recurring alarm ${recurring.id}", e)
                showError("Error bootstrapping recurring alarm '${recurring.displayName}': ${e.message}")
            }
        }
    }

    private suspend fun bootstrapSingleRecurringAlarm(recurring: RecurringAlarm) {
        if (recurring.hasReachedEnd) {
            recurringRepo.end(recurring.id)
            return
        }

        val currentAlarmId = recurring.currentAlarmId
        if (currentAlarmId != null) {
            val currentAlarm = alarmRepo.getAlarm(currentAlarmId)
                .onFailure { showError("Failed to fetch current alarm $currentAlarmId for recurring alarm ${recurring.id}: ${it.message}") }
                .getOrNull()
            if (currentAlarm != null) {
                when (currentAlarm.status) {
                    org.alkaline.taskbrain.data.AlarmStatus.PENDING -> {
                        // Still pending — just reschedule it (may have been lost on reboot)
                        alarmScheduler.scheduleAlarm(currentAlarm)
                        return
                    }
                    org.alkaline.taskbrain.data.AlarmStatus.DONE,
                    org.alkaline.taskbrain.data.AlarmStatus.CANCELLED -> {
                        // Instance completed/cancelled but next wasn't created (crash?)
                        // Fall through to create next
                    }
                }
            }
        }

        // No valid current instance — create the next one
        val afterDate = recurring.lastCompletionDate?.toDate()
            ?: recurring.createdAt?.toDate()
            ?: return
        createNextInstance(recurring, afterDate)
    }

    /**
     * Creates the first alarm instance for a newly created recurring alarm.
     */
    suspend fun createFirstInstance(recurring: RecurringAlarm, firstBaseTime: Date): String? {
        val alarm = buildAlarmFromTemplate(recurring, firstBaseTime)
        val alarmId = alarmRepo.createAlarm(alarm)
            .onFailure { showError("Failed to create first instance for recurring alarm ${recurring.id}: ${it.message}") }
            .getOrNull() ?: return null

        recurringRepo.updateCurrentAlarmId(recurring.id, alarmId)
            .onFailure { showError("Failed to update currentAlarmId for recurring alarm ${recurring.id}: ${it.message}") }

        val createdAlarm = alarmRepo.getAlarm(alarmId)
            .onFailure { showError("Created alarm $alarmId but failed to fetch it for scheduling: ${it.message}") }
            .getOrNull()
        if (createdAlarm != null) {
            alarmScheduler.scheduleAlarm(createdAlarm)
        } else {
            alarmScheduler.scheduleAlarm(alarm.copy(id = alarmId))
        }

        return alarmId
    }

    private fun showError(message: String) {
        Log.e(TAG, message)
        try {
            AlarmErrorActivity.show(context, "Recurring Alarm Error", message)
        } catch (e: Exception) {
            Log.e(TAG, "Could not show error dialog: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RecurrenceScheduler"
    }
}
