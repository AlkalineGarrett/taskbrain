package org.alkaline.taskbrain.service

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmUpdateEvent
import org.alkaline.taskbrain.data.RecurringAlarm
import org.alkaline.taskbrain.data.RecurringAlarmRepository
import org.alkaline.taskbrain.data.RecurrenceType
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
    private val alarmScheduler: AlarmScheduler = AlarmScheduler(context)
) {

    /**
     * Called when an alarm instance is completed (marked done).
     * For both FIXED and RELATIVE types, records completion.
     * For RELATIVE type, creates the next instance.
     */
    suspend fun onInstanceCompleted(alarm: Alarm) {
        val recurringId = alarm.recurringAlarmId ?: return
        val recurring = recurringRepo.get(recurringId).getOrNull() ?: return

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

        if (recurring.recurrenceType == RecurrenceType.RELATIVE) {
            createNextInstance(updated, completionDate.toDate())
        }
    }

    /**
     * Called when an alarm instance is cancelled.
     * For RELATIVE type, creates next instance based on last completion date
     * (or creation date if never completed).
     */
    suspend fun onInstanceCancelled(alarm: Alarm) {
        val recurringId = alarm.recurringAlarmId ?: return
        val recurring = recurringRepo.get(recurringId).getOrNull() ?: return

        if (recurring.hasReachedEnd) return

        if (recurring.recurrenceType == RecurrenceType.RELATIVE) {
            val anchorDate = recurring.lastCompletionDate?.toDate()
                ?: recurring.createdAt?.toDate()
                ?: return
            createNextInstance(recurring, anchorDate)
        }
    }

    /**
     * Called when a FIXED alarm's scheduled time passes (from AlarmReceiver).
     * Creates the next instance for the next occurrence.
     */
    suspend fun onFixedInstanceTriggered(alarm: Alarm) {
        val recurringId = alarm.recurringAlarmId ?: return
        val recurring = recurringRepo.get(recurringId).getOrNull() ?: return

        if (recurring.recurrenceType != RecurrenceType.FIXED) return
        if (recurring.hasReachedEnd) return

        val baseTime = alarm.alarmTime?.toDate()
            ?: alarm.latestThresholdTime?.toDate()
            ?: return

        createNextInstance(recurring, baseTime)
    }

    /**
     * Creates the next alarm instance for a recurring alarm.
     *
     * @param recurring The recurring alarm template
     * @param afterDate The reference date to compute the next occurrence from
     */
    private suspend fun createNextInstance(recurring: RecurringAlarm, afterDate: Date) {
        val nextBaseTime = computeNextBaseTime(recurring, afterDate) ?: return

        val alarm = buildAlarmFromTemplate(recurring, nextBaseTime)

        val alarmId = alarmRepo.createAlarm(alarm).getOrNull()
        if (alarmId == null) {
            Log.e(TAG, "Failed to create next instance for recurring alarm ${recurring.id}")
            return
        }

        recurringRepo.updateCurrentAlarmId(recurring.id, alarmId)

        val createdAlarm = alarmRepo.getAlarm(alarmId).getOrNull()
        if (createdAlarm != null) {
            alarmScheduler.scheduleAlarm(createdAlarm)
        }

        AlarmUpdateEvent.notifyAlarmUpdated()
        Log.d(TAG, "Created next instance $alarmId for recurring alarm ${recurring.id}")
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
     * Applies threshold offsets to compute absolute times.
     */
    private fun buildAlarmFromTemplate(recurring: RecurringAlarm, baseTime: Date): Alarm {
        val baseMs = baseTime.time
        return Alarm(
            noteId = recurring.noteId,
            lineContent = recurring.lineContent,
            upcomingTime = recurring.upcomingOffsetMs?.let { Timestamp(Date(baseMs + it)) },
            notifyTime = recurring.notifyOffsetMs?.let { Timestamp(Date(baseMs + it)) },
            urgentTime = recurring.urgentOffsetMs?.let { Timestamp(Date(baseMs + it)) },
            alarmTime = recurring.alarmOffsetMs?.let { Timestamp(Date(baseMs + it)) },
            recurringAlarmId = recurring.id
        )
    }

    /**
     * Bootstraps recurring alarms on app launch or boot.
     * For each active recurring alarm, checks if a current instance exists
     * and is still pending. If not, creates the next one.
     */
    suspend fun bootstrapRecurringAlarms() {
        val recurringAlarms = recurringRepo.getActiveRecurringAlarms().getOrNull() ?: return

        for (recurring in recurringAlarms) {
            try {
                bootstrapSingleRecurringAlarm(recurring)
            } catch (e: Exception) {
                Log.e(TAG, "Error bootstrapping recurring alarm ${recurring.id}", e)
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
            val currentAlarm = alarmRepo.getAlarm(currentAlarmId).getOrNull()
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
        val alarmId = alarmRepo.createAlarm(alarm).getOrNull() ?: return null

        recurringRepo.updateCurrentAlarmId(recurring.id, alarmId)

        val createdAlarm = alarmRepo.getAlarm(alarmId).getOrNull()
        if (createdAlarm != null) {
            alarmScheduler.scheduleAlarm(createdAlarm)
        }

        return alarmId
    }

    companion object {
        private const val TAG = "RecurrenceScheduler"
    }
}
