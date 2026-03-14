package org.alkaline.taskbrain.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmType
import org.alkaline.taskbrain.receiver.AlarmActionReceiver
import org.alkaline.taskbrain.ui.alarm.AlarmActivity

/**
 * Helper class for showing alarm notifications.
 * Extracted to allow both AlarmReceiver and AlarmScheduler to show notifications.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /**
     * Shows a notification for the given alarm.
     * @param alarm The alarm to show notification for
     * @param alarmType The type of alarm notification
     * @param silent If true, the notification will be added silently without sound or popup
     * Returns true if the notification was shown, false otherwise.
     */
    fun showNotification(alarm: Alarm, alarmType: AlarmType = AlarmType.NOTIFY, silent: Boolean = false): Boolean {
        if (!hasNotificationPermission() || notificationManager == null) {
            return false
        }

        return when (alarmType) {
            AlarmType.NOTIFY -> showReminderNotification(alarm, silent)
            AlarmType.URGENT -> showUrgentNotification(alarm, silent)
            AlarmType.ALARM -> showAlarmNotification(alarm)
        }
    }

    private fun showReminderNotification(alarm: Alarm, silent: Boolean = false): Boolean {
        val builder = NotificationCompat.Builder(context, NotificationChannels.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Reminder")
            .setContentText(alarm.displayName)
            .setAutoCancel(true)
            .setContentIntent(createContentIntent(alarm))
            .addAction(createDoneAction(alarm))
            .addAction(createCancelAction(alarm))

        if (silent) {
            builder.setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        }

        notificationManager?.notify(AlarmUtils.getNotificationId(alarm.id), builder.build())
        return true
    }

    private fun showUrgentNotification(alarm: Alarm, silent: Boolean = false): Boolean {
        // Use REMINDER channel for silent notifications (channel settings override per-notification on Android 8+)
        val channelId = if (silent) NotificationChannels.REMINDER_CHANNEL_ID else NotificationChannels.URGENT_CHANNEL_ID

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Urgent Reminder")
            .setContentText(alarm.displayName)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(createContentIntent(alarm))
            .addAction(createDoneAction(alarm))
            .addAction(createCancelAction(alarm))

        if (silent) {
            builder.setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        } else {
            // Full urgent notification with full-screen intent:
            // - If device is LOCKED: Android shows the full-screen activity
            // - If device is UNLOCKED: Android shows a heads-up notification
            val fullScreenIntent = createFullScreenIntent(alarm, AlarmType.URGENT)
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(fullScreenIntent, true)
        }

        notificationManager?.notify(AlarmUtils.getNotificationId(alarm.id), builder.build())
        return true
    }

    private fun showAlarmNotification(alarm: Alarm): Boolean {
        val fullScreenIntent = createFullScreenIntent(alarm, AlarmType.ALARM)

        val notification = NotificationCompat.Builder(context, NotificationChannels.ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Alarm")
            .setContentText(alarm.displayName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(createContentIntent(alarm))
            .addAction(createDoneAction(alarm))
            .addAction(createSnoozeAction(alarm))
            .build()

        notificationManager?.notify(AlarmUtils.getNotificationId(alarm.id), notification)
        return true
    }

    private fun createContentIntent(alarm: Alarm): PendingIntent {
        val intent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmActivity.EXTRA_ALARM_ID, alarm.id)
            putExtra(AlarmActivity.EXTRA_ALARM_TYPE, AlarmType.NOTIFY.name)
        }
        return PendingIntent.getActivity(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createFullScreenIntent(alarm: Alarm, alarmType: AlarmType): PendingIntent {
        val intent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmActivity.EXTRA_ALARM_ID, alarm.id)
            putExtra(AlarmActivity.EXTRA_ALARM_TYPE, alarmType.name)
        }
        return PendingIntent.getActivity(
            context,
            alarm.id.hashCode() + 1000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createDoneAction(alarm: Alarm): NotificationCompat.Action {
        val intent = Intent(context, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_MARK_DONE
            putExtra(AlarmActionReceiver.EXTRA_ALARM_ID, alarm.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode() + 2000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_check_circle,
            "Done",
            pendingIntent
        ).build()
    }

    private fun createSnoozeAction(alarm: Alarm): NotificationCompat.Action {
        val intent = Intent(context, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_SNOOZE
            putExtra(AlarmActionReceiver.EXTRA_ALARM_ID, alarm.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode() + 3000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_alarm,
            "Snooze",
            pendingIntent
        ).build()
    }

    private fun createCancelAction(alarm: Alarm): NotificationCompat.Action {
        val intent = Intent(context, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_CANCEL
            putExtra(AlarmActionReceiver.EXTRA_ALARM_ID, alarm.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode() + 4000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_close,
            "Cancel",
            pendingIntent
        ).build()
    }

    /**
     * Checks if the app has notification permission.
     */
    fun hasNotificationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if the app can use full-screen intents.
     * On Android 14+, this requires explicit permission.
     */
    fun canUseFullScreenIntent(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager?.canUseFullScreenIntent() ?: false
        } else {
            // Before Android 14, full-screen intent is allowed with USE_FULL_SCREEN_INTENT permission
            true
        }
    }
}
