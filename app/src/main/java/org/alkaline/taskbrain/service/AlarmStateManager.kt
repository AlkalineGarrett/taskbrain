package org.alkaline.taskbrain.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository

/**
 * Centralizes all alarm state transition side effects.
 *
 * Every transition that deactivates an alarm (done, cancelled, deleted, updated)
 * must: cancel scheduled triggers, exit urgent state (restore wallpaper),
 * and dismiss notifications. This class ensures no side effect is forgotten.
 *
 * All suspend functions return Result<T> so callers can handle errors.
 */
class AlarmStateManager(
    private val repository: AlarmRepository = AlarmRepository(),
    private val scheduler: AlarmScheduler,
    private val urgentStateManager: UrgentStateManager,
    private val notificationManager: NotificationManager?,
    private val recurrenceScheduler: RecurrenceScheduler? = null
) {

    constructor(context: Context) : this(
        repository = AlarmRepository(),
        scheduler = AlarmScheduler(context),
        urgentStateManager = UrgentStateManager(context),
        notificationManager = context.getSystemService(NotificationManager::class.java),
        recurrenceScheduler = RecurrenceScheduler(context)
    )

    /**
     * Cleans up all system side effects for an alarm: cancels scheduled triggers,
     * exits urgent state (restores wallpaper if no other urgent alarms remain),
     * and dismisses any active notification.
     */
    fun deactivate(alarmId: String) {
        scheduler.cancelAlarm(alarmId)
        urgentStateManager.exitUrgentState(alarmId)
        notificationManager?.cancel(AlarmUtils.getNotificationId(alarmId))
    }

    suspend fun markDone(alarmId: String): Result<Unit> {
        deactivate(alarmId)
        return repository.markDone(alarmId).also { result ->
            result.onSuccess { scheduleNextRecurrence(alarmId, completed = true) }
            result.onFailure { Log.e(TAG, "Error marking alarm done: $alarmId", it) }
        }
    }

    suspend fun markCancelled(alarmId: String): Result<Unit> {
        deactivate(alarmId)
        return repository.markCancelled(alarmId).also { result ->
            result.onSuccess { scheduleNextRecurrence(alarmId, completed = false) }
            result.onFailure { Log.e(TAG, "Error marking alarm cancelled: $alarmId", it) }
        }
    }

    /**
     * If this alarm belongs to a recurring alarm, triggers creation of the next instance.
     */
    private suspend fun scheduleNextRecurrence(alarmId: String, completed: Boolean) {
        val scheduler = recurrenceScheduler ?: return
        val alarm = repository.getAlarm(alarmId).getOrNull() ?: return
        if (alarm.recurringAlarmId == null) return

        try {
            if (completed) {
                scheduler.onInstanceCompleted(alarm)
            } else {
                scheduler.onInstanceCancelled(alarm)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling next recurrence for $alarmId", e)
        }
    }

    suspend fun delete(alarmId: String): Result<Unit> {
        deactivate(alarmId)
        return repository.deleteAlarm(alarmId).also { result ->
            result.onFailure { Log.e(TAG, "Error deleting alarm: $alarmId", it) }
        }
    }

    /**
     * Updates an alarm's time thresholds, reschedules triggers, and clears stale
     * urgent state/notifications from the old schedule.
     *
     * @return Result containing the schedule result on success.
     */
    suspend fun update(
        alarm: Alarm,
        upcomingTime: Timestamp?,
        notifyTime: Timestamp?,
        urgentTime: Timestamp?,
        alarmTime: Timestamp?
    ): Result<AlarmScheduleResult> {
        val effectiveUpcomingTime = resolveUpcomingTime(upcomingTime, notifyTime, urgentTime, alarmTime)

        val updatedAlarm = alarm.copy(
            upcomingTime = effectiveUpcomingTime,
            notifyTime = notifyTime,
            urgentTime = urgentTime,
            alarmTime = alarmTime
        )

        return repository.updateAlarm(updatedAlarm).map {
            deactivate(alarm.id)
            scheduler.scheduleAlarm(updatedAlarm)
        }.also { result ->
            result.onFailure { Log.e(TAG, "Error updating alarm: ${alarm.id}", it) }
        }
    }

    /**
     * Reactivates a done/cancelled alarm and reschedules its triggers.
     *
     * @return Result containing the schedule result on success, or null if the alarm
     *         was not found after reactivation.
     */
    suspend fun reactivate(alarmId: String): Result<AlarmScheduleResult?> {
        return repository.reactivateAlarm(alarmId).map {
            repository.getAlarm(alarmId).getOrNull()?.let { alarm ->
                scheduler.scheduleAlarm(alarm)
            }
        }.also { result ->
            result.onFailure { Log.e(TAG, "Error reactivating alarm: $alarmId", it) }
        }
    }

    companion object {
        private const val TAG = "AlarmStateManager"

        /**
         * If the user didn't set an explicit upcoming time, use the earliest
         * of the other thresholds so the alarm appears in the Upcoming list.
         */
        fun resolveUpcomingTime(
            upcomingTime: Timestamp?,
            notifyTime: Timestamp?,
            urgentTime: Timestamp?,
            alarmTime: Timestamp?
        ): Timestamp? = upcomingTime ?: listOfNotNull(notifyTime, urgentTime, alarmTime)
            .minByOrNull { it.toDate().time }
    }
}
