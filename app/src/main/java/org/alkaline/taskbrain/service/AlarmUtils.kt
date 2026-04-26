package org.alkaline.taskbrain.service

import android.content.Context
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStageType
import org.alkaline.taskbrain.data.AlarmType
import org.alkaline.taskbrain.util.MINUTE_MS
import org.alkaline.taskbrain.util.formatTimeOfDay

/**
 * Utility functions for alarm-related logic.
 * Extracted for testability.
 */
object AlarmUtils {

    /**
     * Generates a unique request code for a PendingIntent.
     * Combines alarm ID hash with alarm type ordinal to ensure uniqueness.
     */
    fun generateRequestCode(alarmId: String, alarmType: AlarmType): Int {
        return alarmId.hashCode() * 10 + alarmType.ordinal
    }

    /**
     * Determines which alarm type to use when snoozing.
     * Returns the highest priority enabled stage type.
     */
    fun determineAlarmTypeForSnooze(alarm: Alarm): AlarmType {
        return alarm.enabledStages
            .maxByOrNull { stagePriority(it.type) }
            ?.type?.toAlarmType()
            ?: AlarmType.ALARM
    }

    fun stagePriority(type: AlarmStageType): Int = type.priority

    /**
     * Returns true if notification sync should be silent for the given alarm state.
     * Silent when the notified stage is at or above the current stage priority
     * (i.e., the user has already been alerted with sound for this stage or a higher one).
     */
    fun shouldSyncSilently(
        notifiedStageType: AlarmStageType?,
        currentStageType: AlarmStageType
    ): Boolean {
        if (notifiedStageType == null) return false
        return notifiedStageType.priority >= currentStageType.priority
    }

    /**
     * Gets all triggers that should be scheduled for an alarm.
     * Returns a list of (triggerTimeMillis, alarmType) pairs.
     * Only includes enabled stages with a valid dueTime.
     */
    fun getTriggersToSchedule(alarm: Alarm): List<Pair<Long, AlarmType>> {
        val due = alarm.dueTime ?: return emptyList()
        return alarm.enabledStages.map { stage ->
            stage.resolveTime(due).toDate().time to stage.type.toAlarmType()
        }
    }

    /**
     * Filters triggers to only include those in the future.
     */
    fun filterFutureTriggers(
        triggers: List<Pair<Long, AlarmType>>,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): List<Pair<Long, AlarmType>> {
        return triggers.filter { (time, _) -> time > currentTimeMillis }
    }

    /**
     * Calculates the snooze end time.
     */
    fun calculateSnoozeEndTime(
        durationMinutes: Int,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): Long {
        return currentTimeMillis + durationMinutes * MINUTE_MS
    }

    /**
     * Checks if an alarm should be shown based on its status and snooze state.
     */
    fun shouldShowAlarm(
        alarm: Alarm,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): Boolean {
        // Not pending - don't show
        if (alarm.status != org.alkaline.taskbrain.data.AlarmStatus.PENDING) {
            return false
        }

        // Snoozed and snooze hasn't expired - don't show
        if (alarm.snoozedUntil != null &&
            alarm.snoozedUntil.toDate().time > currentTimeMillis) {
            return false
        }

        return true
    }

    /**
     * Generates the notification ID for an alarm.
     * Used consistently across AlarmReceiver (to show) and AlarmActivity/AlarmActionReceiver (to dismiss).
     */
    fun getNotificationId(alarmId: String): Int {
        return alarmId.hashCode()
    }

    /**
     * Formats display text for wallpaper/notifications.
     * Includes alarm name and due time if available.
     *
     * @param context Context for accessing DateFormat preferences
     * @param alarm The alarm to format text for
     * @return Formatted display text (e.g., "Task name: due 2:30 PM")
     */
    fun formatDisplayText(context: Context, alarm: Alarm): String {
        val dueTime = alarm.dueTime?.toDate()
        return if (dueTime != null) {
            "${alarm.displayName}: due ${formatTimeOfDay(context, dueTime)}"
        } else {
            alarm.displayName
        }
    }
}
