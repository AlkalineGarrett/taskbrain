package org.alkaline.taskbrain.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.service.AlarmScheduler
import org.alkaline.taskbrain.service.RecurrenceScheduler

/**
 * Reschedules all pending alarms after device boot.
 * Alarms are cleared by Android when the device reboots.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Device booted, rescheduling alarms...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AlarmRepository()
                val scheduler = AlarmScheduler(context)

                val result = repository.getPendingAlarms()

                result.fold(
                    onSuccess = { alarms ->
                        Log.d(TAG, "Found ${alarms.size} pending alarms to reschedule")

                        alarms.forEach { alarm ->
                            scheduler.scheduleAlarm(alarm)
                        }

                        Log.d(TAG, "Successfully rescheduled ${alarms.size} alarms")
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Error fetching pending alarms", e)
                    }
                )

                // Bootstrap recurring alarms (create missing instances)
                try {
                    RecurrenceScheduler(context).bootstrapRecurringAlarms()
                    Log.d(TAG, "Bootstrapped recurring alarms")
                } catch (e: Exception) {
                    Log.e(TAG, "Error bootstrapping recurring alarms", e)
                }
            } catch (e: Exception) {
                // User may not be signed in yet after boot
                Log.w(TAG, "Could not reschedule alarms (user may not be signed in)", e)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
