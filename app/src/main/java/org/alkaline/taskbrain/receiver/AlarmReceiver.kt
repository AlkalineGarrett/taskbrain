package org.alkaline.taskbrain.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmType
import org.alkaline.taskbrain.service.AlarmScheduler
import org.alkaline.taskbrain.service.AlarmUtils
import org.alkaline.taskbrain.service.NotificationHelper
import org.alkaline.taskbrain.service.RecurrenceScheduler
import org.alkaline.taskbrain.service.UrgentStateManager
import org.alkaline.taskbrain.ui.alarm.AlarmErrorActivity

/**
 * Receives alarm triggers from AlarmManager and shows appropriate notifications.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called with action: ${intent.action}")

        if (intent.action != AlarmScheduler.ACTION_ALARM_TRIGGERED) {
            Log.w(TAG, "Unexpected action: ${intent.action}, expected: ${AlarmScheduler.ACTION_ALARM_TRIGGERED}")
            return
        }

        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        if (alarmId == null) {
            Log.e(TAG, "No alarm ID in intent")
            showErrorDialog(context, "Alarm Error", "No alarm ID provided in the trigger intent.")
            return
        }

        val alarmTypeName = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_TYPE)
        if (alarmTypeName == null) {
            Log.e(TAG, "No alarm type in intent")
            showErrorDialog(context, "Alarm Error", "No alarm type provided in the trigger intent.")
            return
        }

        val alarmType = try {
            AlarmType.valueOf(alarmTypeName)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid alarm type: $alarmTypeName", e)
            showErrorDialog(context, "Alarm Error", "Invalid alarm type: $alarmTypeName")
            return
        }

        Log.d(TAG, "Alarm received: $alarmId ($alarmType)")

        // Use goAsync() to keep the BroadcastReceiver alive while we do async work
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AlarmRepository()
                val result = repository.getAlarm(alarmId)

                result.fold(
                    onSuccess = { alarm ->
                        if (alarm == null) {
                            Log.w(TAG, "Alarm not found in database: $alarmId")
                            showErrorDialogOnMain(context, "Alarm Not Found",
                                "The alarm ($alarmId) was not found in the database. It may have been deleted.")
                            return@fold
                        }

                        if (!AlarmUtils.shouldShowAlarm(alarm)) {
                            Log.d(TAG, "Alarm should not be shown (status=${alarm.status}, snoozedUntil=${alarm.snoozedUntil}): $alarmId")
                            // This is expected behavior, not an error - don't show dialog
                            return@fold
                        }

                        Log.d(TAG, "About to show alarm: type=$alarmType, content=${alarm.lineContent}")

                        // For FIXED recurring alarms, schedule the next instance
                        // when the alarm fires (regardless of user action)
                        if (alarm.recurringAlarmId != null) {
                            try {
                                RecurrenceScheduler(context).onFixedInstanceTriggered(alarm)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error scheduling next recurrence", e)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            showAlarmTrigger(context, alarm, alarmType)
                        }
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Error fetching alarm: $alarmId", e)
                        showErrorDialogOnMain(context, "Alarm Error",
                            "Failed to fetch alarm data: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in alarm receiver", e)
                showErrorDialogOnMain(context, "Alarm Error",
                    "Unexpected error processing alarm: ${e.message}")
            } finally {
                // Signal that we're done with async processing
                pendingResult.finish()
            }
        }
    }

    /**
     * Shows the appropriate notification for the alarm trigger.
     * Uses NotificationHelper for NOTIFY/ALARM types, UrgentStateManager for URGENT type.
     */
    private fun showAlarmTrigger(context: Context, alarm: Alarm, alarmType: AlarmType) {
        Log.d(TAG, "showAlarmTrigger called for ${alarm.id} with type $alarmType")

        val notificationHelper = NotificationHelper(context)

        // Check notification permission
        if (!notificationHelper.hasNotificationPermission()) {
            Log.w(TAG, "Notification permission not granted")
            showErrorDialog(context, "Permission Required",
                "Notification permission is not granted. Please enable notifications for TaskBrain in Settings.")
            return
        }

        // Check full-screen intent permission for urgent/alarm types
        if (alarmType == AlarmType.URGENT || alarmType == AlarmType.ALARM) {
            if (!notificationHelper.canUseFullScreenIntent()) {
                Log.w(TAG, "Full-screen intent permission NOT granted on Android 14+")
                showErrorDialog(context, "Permission Required",
                    "Full-screen intent permission is not granted.\n\n" +
                    "To see alarms over your lock screen, go to:\n" +
                    "Settings → Apps → TaskBrain → Allow display over other apps")
            }
        }

        // Show the notification/urgent state based on alarm type
        val success = when (alarmType) {
            AlarmType.URGENT -> {
                // Use UrgentStateManager for coordinated wallpaper + notification
                UrgentStateManager(context).enterUrgentState(alarm, silent = false)
            }
            else -> {
                // Use NotificationHelper directly for NOTIFY and ALARM types
                notificationHelper.showNotification(alarm, alarmType, silent = false)
            }
        }

        if (success) {
            Log.d(TAG, "Showed $alarmType notification for alarm: ${alarm.id}")
        } else {
            Log.e(TAG, "Failed to show $alarmType notification for alarm: ${alarm.id}")
        }
    }

    private fun showErrorDialog(context: Context, title: String, message: String) {
        try {
            AlarmErrorActivity.show(context, title, message)
        } catch (e: Exception) {
            Log.e(TAG, "Could not show error dialog: ${e.message}")
        }
    }

    private suspend fun showErrorDialogOnMain(context: Context, title: String, message: String) {
        withContext(Dispatchers.Main) {
            showErrorDialog(context, title, message)
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
