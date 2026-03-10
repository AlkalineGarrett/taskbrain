package org.alkaline.taskbrain.ui.alarms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.ui.components.ErrorDialog
import org.alkaline.taskbrain.ui.currentnote.components.AlarmConfigDialog
import org.alkaline.taskbrain.util.PermissionHelper
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AlarmsScreen(
    alarmsViewModel: AlarmsViewModel = viewModel()
) {
    val context = LocalContext.current
    val pastDueAlarms by alarmsViewModel.pastDueAlarms.observeAsState(emptyList())
    val upcomingAlarms by alarmsViewModel.upcomingAlarms.observeAsState(emptyList())
    val laterAlarms by alarmsViewModel.laterAlarms.observeAsState(emptyList())
    val completedAlarms by alarmsViewModel.completedAlarms.observeAsState(emptyList())
    val cancelledAlarms by alarmsViewModel.cancelledAlarms.observeAsState(emptyList())
    val isLoading by alarmsViewModel.isLoading.observeAsState(false)
    val error by alarmsViewModel.error.observeAsState()

    // Alarm edit dialog state
    var selectedAlarm by remember { mutableStateOf<Alarm?>(null) }

    // Check permission status
    var permissionStatus by remember { mutableStateOf(PermissionHelper.getAlarmPermissionStatus(context)) }

    // Refresh alarms when screen becomes visible (ON_RESUME).
    // This ensures external changes (e.g., alarm cancelled/done via notification action)
    // are reflected in the UI when the user returns to the app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                alarmsViewModel.loadAlarms()
                permissionStatus = PermissionHelper.getAlarmPermissionStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    error?.let { throwable ->
        ErrorDialog(
            title = "Error",
            throwable = throwable,
            onDismiss = { alarmsViewModel.clearError() }
        )
    }

    selectedAlarm?.let { alarm ->
        AlarmConfigDialog(
            lineContent = alarm.displayName.ifEmpty { "Untitled alarm" },
            existingAlarm = alarm,
            onSave = { upcomingTime, notifyTime, urgentTime, alarmTime ->
                alarmsViewModel.updateAlarm(alarm, upcomingTime, notifyTime, urgentTime, alarmTime)
            },
            onMarkDone = { alarmsViewModel.markDone(alarm.id) },
            onCancel = { alarmsViewModel.markCancelled(alarm.id) },
            onDelete = { alarmsViewModel.deleteAlarm(alarm.id) },
            onDismiss = { selectedAlarm = null }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Permission warnings banner
        if (!permissionStatus.hasNotificationPermission || !permissionStatus.canScheduleExactAlarms || !permissionStatus.canUseFullScreenIntent) {
            PermissionWarningBanner(permissionStatus)
        }

        Box(
            modifier = Modifier.weight(1f)
        ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            val hasNoAlarms = pastDueAlarms.isEmpty() && upcomingAlarms.isEmpty() &&
                    laterAlarms.isEmpty() && completedAlarms.isEmpty() && cancelledAlarms.isEmpty()

            if (hasNoAlarms) {
                Text(
                    text = "No alarms",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Past Due section
                    if (pastDueAlarms.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Past Due",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        items(pastDueAlarms) { alarm ->
                            AlarmItem(
                                alarm = alarm,
                                onTap = { selectedAlarm = alarm },
                                onMarkDone = { alarmsViewModel.markDone(alarm.id) },
                                onCancel = { alarmsViewModel.markCancelled(alarm.id) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // Upcoming section
                    if (upcomingAlarms.isNotEmpty()) {
                        item {
                            SectionHeader("Upcoming")
                        }
                        items(upcomingAlarms) { alarm ->
                            AlarmItem(
                                alarm = alarm,
                                onTap = { selectedAlarm = alarm },
                                onMarkDone = { alarmsViewModel.markDone(alarm.id) },
                                onCancel = { alarmsViewModel.markCancelled(alarm.id) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // Later section
                    if (laterAlarms.isNotEmpty()) {
                        item {
                            SectionHeader("Later")
                        }
                        items(laterAlarms) { alarm ->
                            AlarmItem(
                                alarm = alarm,
                                onTap = { selectedAlarm = alarm },
                                onMarkDone = { alarmsViewModel.markDone(alarm.id) },
                                onCancel = { alarmsViewModel.markCancelled(alarm.id) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // Completed section
                    if (completedAlarms.isNotEmpty()) {
                        item {
                            SectionHeader("Completed")
                        }
                        items(completedAlarms) { alarm ->
                            CompletedAlarmItem(
                                alarm = alarm,
                                onTap = { selectedAlarm = alarm },
                                onReactivate = { alarmsViewModel.reactivateAlarm(alarm.id) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // Cancelled section
                    if (cancelledAlarms.isNotEmpty()) {
                        item {
                            SectionHeader("Cancelled")
                        }
                        items(cancelledAlarms) { alarm ->
                            CompletedAlarmItem(
                                alarm = alarm,
                                onTap = { selectedAlarm = alarm },
                                onReactivate = { alarmsViewModel.reactivateAlarm(alarm.id) }
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PermissionWarningBanner(permissionStatus: PermissionHelper.AlarmPermissionStatus) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Permission Issues",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        if (!permissionStatus.hasNotificationPermission) {
            Text(
                text = "• Notifications disabled: Go to Settings → Apps → TaskBrain → Notifications",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        if (!permissionStatus.canScheduleExactAlarms) {
            Text(
                text = "• Exact alarms disabled: Go to Settings → Apps → TaskBrain → Alarms & reminders",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        if (!permissionStatus.canUseFullScreenIntent) {
            Text(
                text = "• Full-screen alarms disabled: Go to Settings → Apps → TaskBrain → App info → Allow display over other apps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun AlarmItem(
    alarm: Alarm,
    onTap: () -> Unit,
    onMarkDone: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alarm.displayName.ifEmpty { "Untitled alarm" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                alarm.upcomingTime?.let { timestamp ->
                    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    Text(
                        text = dateFormat.format(timestamp.toDate()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Show threshold times if set
                val thresholds = buildList {
                    alarm.notifyTime?.let { add("Notify: ${formatTime(it)}") }
                    alarm.urgentTime?.let { add("Urgent: ${formatTime(it)}") }
                    alarm.alarmTime?.let { add("Alarm: ${formatTime(it)}") }
                }
                if (thresholds.isNotEmpty()) {
                    Text(
                        text = thresholds.joinToString(" | "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onMarkDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Mark done",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CompletedAlarmItem(
    alarm: Alarm,
    onTap: () -> Unit,
    onReactivate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alarm.displayName.ifEmpty { "Untitled alarm" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                alarm.updatedAt?.let { timestamp ->
                    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    Text(
                        text = "Completed: ${dateFormat.format(timestamp.toDate())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            IconButton(onClick = onReactivate) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reactivate",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun formatTime(timestamp: com.google.firebase.Timestamp): String {
    val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    return dateFormat.format(timestamp.toDate())
}
