package org.alkaline.taskbrain.service

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository

/**
 * Syncs active notifications with the correct alarm type on app startup.
 * - If a notification already exists: silently updates it in place (no sound/popup).
 * - If a notification is missing (app wasn't running when stage triggered): posts with sound.
 */
class NotificationSyncer(private val context: Context) {

    private val repository = AlarmRepository()
    private val notificationHelper = NotificationHelper(context)

    suspend fun sync() {
        if (FirebaseAuth.getInstance().currentUser == null) return

        // One-shot read at startup — avoids forcing the listener to
        // attach (and pull the full collection) before the user has even
        // opened the Alarms screen. AlarmsViewModel will attach later if
        // the user navigates there.
        val alarms: List<Alarm> = repository.getPendingAlarmsFromServer()
            .onFailure { Log.e(TAG, "Failed to load alarms for notification sync", it) }
            .getOrNull() ?: return

        val nowMs = System.currentTimeMillis()

        for (alarm in alarms) {
            val currentStage = alarm.currentTriggeredStage(nowMs) ?: continue
            val stageType = currentStage.type
            val alarmType = stageType.toAlarmType()
            val silent = AlarmUtils.shouldSyncSilently(alarm.notifiedStageType, stageType) ||
                notificationHelper.isNotificationActive(alarm.id)
            notificationHelper.showNotification(alarm, alarmType, silent = silent)

            // Record notified stage so future syncs (e.g., after redeploy) stay silent
            if (alarm.notifiedStageType == null || stageType.priority > alarm.notifiedStageType.priority) {
                repository.markNotifiedStage(alarm.id, stageType)
                    .onFailure { Log.e(TAG, "Failed to record notified stage for ${alarm.id}", it) }
            }
        }
    }

    companion object {
        private const val TAG = "NotificationSyncer"
    }
}
