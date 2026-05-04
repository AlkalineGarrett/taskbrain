package org.alkaline.taskbrain.service

import android.util.Log
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmStageType
import org.alkaline.taskbrain.data.AlarmType

/**
 * Outcome of handling an alarm trigger.
 */
sealed class TriggerResult {
    data class Shown(val alarm: Alarm, val alarmType: AlarmType) : TriggerResult()
    data class Suppressed(val reason: String) : TriggerResult()
    data class NotFound(val alarmId: String) : TriggerResult()
    data class Error(val message: String, val cause: Throwable? = null) : TriggerResult()
}

/**
 * Orchestrates the logic when a scheduled alarm trigger fires:
 * fetch → check status → process recurrence → re-check status → present.
 *
 * Extracted from AlarmReceiver so the decision logic is testable
 * without Android BroadcastReceiver infrastructure.
 */
class AlarmTriggerHandler(
    private val alarmRepository: AlarmRepository,
    private val recurrenceScheduler: RecurrenceScheduler,
    private val presenter: AlarmPresenter
) {

    /**
     * Handles a fired alarm trigger. Returns the outcome.
     */
    suspend fun handle(alarmId: String, alarmType: AlarmType): TriggerResult {
        val alarm = try {
            alarmRepository.getAlarmFromServer(alarmId).getOrThrow()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching alarm: $alarmId", e)
            return TriggerResult.Error("Failed to fetch alarm data: ${e.message}", e)
        }

        if (alarm == null) {
            Log.w(TAG, "Alarm not found in database: $alarmId")
            return TriggerResult.NotFound(alarmId)
        }

        if (!AlarmUtils.shouldShowAlarm(alarm)) {
            Log.d(TAG, "Alarm should not be shown (status=${alarm.status}, snoozedUntil=${alarm.snoozedUntil}): $alarmId")
            return TriggerResult.Suppressed("status=${alarm.status}, snoozedUntil=${alarm.snoozedUntil}")
        }

        // For recurring alarms, schedule the next instance
        if (alarm.recurringAlarmId != null) {
            try {
                recurrenceScheduler.onFixedInstanceTriggered(alarm)
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling next recurrence", e)
                return TriggerResult.Error(
                    "Failed to schedule next recurring instance for alarm ${alarm.displayName}: ${e.message}", e
                )
            }
        }

        // Re-check: recurrence processing may have changed this alarm's status
        val freshAlarm = if (alarm.recurringAlarmId != null) {
            try {
                alarmRepository.getAlarmFromServer(alarmId).getOrNull() ?: alarm
            } catch (e: Exception) {
                alarm // Fall back to stale copy if re-fetch fails
            }
        } else {
            alarm
        }

        if (freshAlarm !== alarm && !AlarmUtils.shouldShowAlarm(freshAlarm)) {
            Log.d(TAG, "Alarm no longer showable after recurrence processing (status=${freshAlarm.status}): $alarmId")
            return TriggerResult.Suppressed("cancelled during recurrence processing")
        }

        presenter.present(freshAlarm, alarmType)

        // Record that this stage has been presented with sound, so sync/restart
        // can post silently instead of re-sounding.
        val stageType = alarmType.toStageType()
        val currentNotified = freshAlarm.notifiedStageType
        if (currentNotified == null || stageType.priority > currentNotified.priority) {
            alarmRepository.markNotifiedStage(freshAlarm.id, stageType)
                .onFailure { Log.e(TAG, "Failed to record notified stage for ${freshAlarm.id}", it) }
        }

        return TriggerResult.Shown(freshAlarm, alarmType)
    }

    companion object {
        private const val TAG = "AlarmTriggerHandler"
    }
}

/**
 * Abstraction for showing an alarm to the user (notification, urgent state, etc.).
 * Implemented by the receiver layer where Android context is available.
 */
interface AlarmPresenter {
    fun present(alarm: Alarm, alarmType: AlarmType)
}
