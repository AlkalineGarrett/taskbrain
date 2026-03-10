package org.alkaline.taskbrain.ui.alarm

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmType
import org.alkaline.taskbrain.data.SnoozeDuration
import org.alkaline.taskbrain.receiver.AlarmActionReceiver
import org.alkaline.taskbrain.service.AlarmStateManager

/**
 * Full-screen activity shown when an alarm fires.
 * Displays over the lock screen for urgent and alarm types.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // Keep screen on while alarm is showing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: run {
            finish()
            return
        }
        val alarmTypeName = intent.getStringExtra(EXTRA_ALARM_TYPE) ?: AlarmType.NOTIFY.name
        val alarmType = try {
            AlarmType.valueOf(alarmTypeName)
        } catch (e: IllegalArgumentException) {
            AlarmType.NOTIFY
        }

        setContent {
            AlarmScreen(
                alarmId = alarmId,
                alarmType = alarmType,
                onDismiss = {
                    dismissNotification(alarmId)
                    finish()
                },
                onSnooze = { duration ->
                    snoozeAlarm(alarmId, duration)
                    finish()
                },
                onMarkDone = {
                    markDone(alarmId)
                    finish()
                }
            )
        }
    }

    private fun snoozeAlarm(alarmId: String, duration: SnoozeDuration) {
        AlarmActionReceiver().handleSnoozeWithDuration(this, alarmId, duration)
    }

    private fun markDone(alarmId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                AlarmStateManager(this@AlarmActivity).markDone(alarmId)
            }
        }
    }

    private fun dismissNotification(alarmId: String) {
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager?.cancel(org.alkaline.taskbrain.service.AlarmUtils.getNotificationId(alarmId))
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_TYPE = "alarm_type"
    }
}

@Composable
fun AlarmScreen(
    alarmId: String,
    alarmType: AlarmType,
    onDismiss: () -> Unit,
    onSnooze: (SnoozeDuration) -> Unit,
    onMarkDone: () -> Unit
) {
    var alarm by remember { mutableStateOf<Alarm?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Load alarm data
    LaunchedEffect(alarmId) {
        withContext(Dispatchers.IO) {
            val result = AlarmRepository().getAlarm(alarmId)
            result.fold(
                onSuccess = { alarm = it },
                onFailure = { /* ignore */ }
            )
            isLoading = false
        }
    }

    // Background color based on alarm type
    val backgroundColor = when (alarmType) {
        AlarmType.URGENT -> Color(0xFFB71C1C)  // Dark red for urgent
        AlarmType.ALARM -> Color(0xFF1A1A1A)   // Dark for alarm
        AlarmType.NOTIFY -> Color(0xFF1A1A1A)  // Dark for notify
    }

    val contentColor = Color.White

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Animated alarm icon for ALARM type
            if (alarmType == AlarmType.ALARM) {
                PulsingAlarmIcon(tint = contentColor)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_alarm),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(96.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // Alarm type label
            Text(
                text = when (alarmType) {
                    AlarmType.ALARM -> "Alarm"
                    AlarmType.URGENT -> "Urgent"
                    AlarmType.NOTIFY -> "Reminder"
                },
                style = MaterialTheme.typography.titleLarge,
                color = contentColor.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(16.dp))

            // Line content
            Text(
                text = alarm?.displayName ?: "Loading...",
                style = MaterialTheme.typography.headlineMedium,
                color = contentColor,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(48.dp))

            // Snooze buttons (only for ALARM type)
            if (alarmType == AlarmType.ALARM) {
                Text(
                    text = "Snooze for:",
                    color = contentColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SnoozeButton("2 min", contentColor) { onSnooze(SnoozeDuration.TWO_MINUTES) }
                    SnoozeButton("10 min", contentColor) { onSnooze(SnoozeDuration.TEN_MINUTES) }
                    SnoozeButton("1 hour", contentColor) { onSnooze(SnoozeDuration.ONE_HOUR) }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = contentColor
                    )
                ) {
                    Text("Dismiss")
                }
                Button(
                    onClick = onMarkDone,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = backgroundColor
                    )
                ) {
                    Text("Mark Done")
                }
            }
        }
    }
}

@Composable
private fun PulsingAlarmIcon(tint: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Icon(
        painter = painterResource(R.drawable.ic_alarm),
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(96.dp)
            .scale(scale)
    )
}

@Composable
private fun SnoozeButton(
    text: String,
    contentColor: Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor
        )
    ) {
        Text(text)
    }
}
