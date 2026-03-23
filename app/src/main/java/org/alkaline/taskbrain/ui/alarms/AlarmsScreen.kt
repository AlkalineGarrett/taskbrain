package org.alkaline.taskbrain.ui.alarms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.RecurringAlarm
import org.alkaline.taskbrain.service.RecurrenceConfigMapper
import org.alkaline.taskbrain.ui.components.ErrorDialog
import org.alkaline.taskbrain.ui.currentnote.components.AlarmConfigDialog
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.ui.currentnote.components.AlarmDialogMode
import org.alkaline.taskbrain.ui.currentnote.selectCurrentInstance
import org.alkaline.taskbrain.ui.currentnote.components.RecurrenceConfig
import org.alkaline.taskbrain.util.PermissionHelper
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AlarmsScreen(
    alarmsViewModel: AlarmsViewModel = viewModel(),
    openAlarmId: String? = null,
    onOpenAlarmConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val pastDueAlarms by alarmsViewModel.pastDueAlarms.observeAsState(emptyList())
    val upcomingAlarms by alarmsViewModel.upcomingAlarms.observeAsState(emptyList())
    val laterAlarms by alarmsViewModel.laterAlarms.observeAsState(emptyList())
    val completedAlarms by alarmsViewModel.completedAlarms.observeAsState(emptyList())
    val cancelledAlarms by alarmsViewModel.cancelledAlarms.observeAsState(emptyList())
    val recurringAlarms by alarmsViewModel.recurringAlarms.observeAsState(emptyMap())
    val isLoading by alarmsViewModel.isLoading.observeAsState(false)
    val error by alarmsViewModel.error.observeAsState()

    val coroutineScope = rememberCoroutineScope()

    // Instance edit dialog state
    var selectedAlarm by remember { mutableStateOf<Alarm?>(null) }
    var dialogInitialMode by remember { mutableStateOf(AlarmDialogMode.INSTANCE) }

    // Auto-open dialog when navigated from a notification tap
    LaunchedEffect(openAlarmId, isLoading) {
        val alarmId = openAlarmId ?: return@LaunchedEffect
        if (isLoading) return@LaunchedEffect
        val allAlarms = pastDueAlarms + upcomingAlarms + laterAlarms + completedAlarms + cancelledAlarms
        val alarm = allAlarms.find { it.id == alarmId }
        if (alarm != null) {
            selectedAlarm = alarm
            dialogInitialMode = AlarmDialogMode.INSTANCE
            onOpenAlarmConsumed()
        }
    }

    // Delete all confirmation dialog state
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    // Check permission status
    var permissionStatus by remember { mutableStateOf(PermissionHelper.getAlarmPermissionStatus(context)) }

    // Refresh alarms when screen becomes visible (ON_RESUME).
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
            title = stringResource(R.string.error_title),
            throwable = throwable,
            onDismiss = { alarmsViewModel.clearError() }
        )
    }

    // Instance edit dialog (with recurrence section for recurring alarms)
    // Sibling instances for recurring alarm navigation
    var siblingInstances by remember { mutableStateOf<List<Alarm>>(emptyList()) }
    val selectedRecurringId = selectedAlarm?.recurringAlarmId

    LaunchedEffect(selectedRecurringId) {
        siblingInstances = if (selectedRecurringId != null) {
            alarmsViewModel.getInstancesForRecurring(selectedRecurringId)
        } else {
            emptyList()
        }
    }

    selectedAlarm?.let { alarm ->
        val recurringAlarm = alarm.recurringAlarmId?.let { recurringAlarms[it] }
        val recurrenceConfig = recurringAlarm?.let { RecurrenceConfigMapper.toRecurrenceConfig(it) }
        val currentIndex = siblingInstances.indexOfFirst { it.id == alarm.id }
        val hasPrevious = currentIndex > 0
        val hasNext = currentIndex >= 0 && currentIndex < siblingInstances.lastIndex

        val isSingleInstance = recurringAlarm != null && siblingInstances.size <= 1
        AlarmConfigDialog(
            lineContent = alarm.displayName.ifEmpty { stringResource(R.string.alarm_untitled) },
            existingAlarm = alarm,
            existingRecurrenceConfig = recurrenceConfig,
            recurringAlarm = recurringAlarm,
            recurringInstanceCount = siblingInstances.size,
            initialMode = dialogInitialMode,
            onSave = { dueTime, stages ->
                alarmsViewModel.updateAlarm(alarm, dueTime, stages)
            },
            onSaveInstance = if (recurringAlarm != null) { { a, dueTime, stages, alsoUpdateRecurrence ->
                alarmsViewModel.updateInstanceTimes(a, dueTime, stages, alsoUpdateRecurrence)
            } } else null,
            onSaveRecurrenceTemplate = if (recurringAlarm != null) { { recId, dueTime, stages, config, alsoUpdateInstances ->
                alarmsViewModel.updateRecurrenceTemplate(recId, dueTime, stages, config, alsoUpdateInstances)
            } } else null,
            onSaveRecurring = { dueTime, stages, config ->
                alarmsViewModel.updateAlarmAndRecurrence(
                    alarm, dueTime, stages, recurrenceConfig = config
                )
            },
            onEndRecurrence = recurringAlarm?.let { ra ->
                {
                    alarmsViewModel.endRecurrence(
                        ra.id,
                        deleteTemplate = isSingleInstance
                    )
                }
            },
            onMarkDone = { alarmsViewModel.markDone(alarm.id) },
            onMarkCancelled = { alarmsViewModel.markCancelled(alarm.id) },
            onReactivate = { alarmsViewModel.reactivateAlarm(alarm.id) },
            onDelete = { alarmsViewModel.deleteAlarm(alarm.id) },
            onNavigatePrevious = if (recurringAlarm != null) {{
                if (hasPrevious) {
                    selectedAlarm = siblingInstances[currentIndex - 1]
                    dialogInitialMode = AlarmDialogMode.INSTANCE
                }
            }} else null,
            onNavigateNext = if (recurringAlarm != null) {{
                if (hasNext) {
                    selectedAlarm = siblingInstances[currentIndex + 1]
                    dialogInitialMode = AlarmDialogMode.INSTANCE
                }
            }} else null,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onDismiss = {
                selectedAlarm = null
                dialogInitialMode = AlarmDialogMode.INSTANCE
            }
        )
    }

    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.alarm_delete_all_recurring)) },
            text = { Text(stringResource(R.string.alarm_delete_all_recurring_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        alarmsViewModel.deleteAllRecurringAlarms()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // (RecurrenceEditDialog removed — recurrence editing is now handled via
    // AlarmConfigDialog with initialMode = RECURRENCE)

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
                    text = stringResource(R.string.no_alarms),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (pastDueAlarms.isNotEmpty() || upcomingAlarms.isNotEmpty()) {
                        item {
                            OutlinedButton(
                                onClick = { alarmsViewModel.refreshAlarms() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.alarm_refresh))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    fun openRecurrenceEditor(alarm: Alarm, recurring: RecurringAlarm) {
                        dialogInitialMode = AlarmDialogMode.RECURRENCE
                        // Resolve the current instance (today's) rather than using whatever
                        // instance was tapped — the tapped one may be a past or future instance.
                        // Pre-populate siblingInstances so the LaunchedEffect doesn't re-fetch.
                        coroutineScope.launch {
                            val instances = alarmsViewModel.getInstancesForRecurring(recurring.id)
                            siblingInstances = instances
                            selectedAlarm = selectCurrentInstance(instances) ?: alarm
                        }
                    }

                    // Past Due section
                    if (pastDueAlarms.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = stringResource(R.string.section_past_due),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        items(pastDueAlarms) { alarm ->
                            AlarmItem(
                                alarm = alarm,
                                recurringAlarm = alarm.recurringAlarmId?.let { recurringAlarms[it] },
                                onTap = { selectedAlarm = alarm },
                                onMarkDone = { alarmsViewModel.markDone(alarm.id) },
                                onCancel = { alarmsViewModel.markCancelled(alarm.id) },
                                onEditRecurrence = { openRecurrenceEditor(alarm, it) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // Upcoming section
                    if (upcomingAlarms.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.section_upcoming))
                        }
                        items(upcomingAlarms) { alarm ->
                            AlarmItem(
                                alarm = alarm,
                                recurringAlarm = alarm.recurringAlarmId?.let { recurringAlarms[it] },
                                onTap = { selectedAlarm = alarm },
                                onMarkDone = { alarmsViewModel.markDone(alarm.id) },
                                onCancel = { alarmsViewModel.markCancelled(alarm.id) },
                                onEditRecurrence = { openRecurrenceEditor(alarm, it) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // Later section
                    if (laterAlarms.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.section_later))
                        }
                        items(laterAlarms) { alarm ->
                            AlarmItem(
                                alarm = alarm,
                                recurringAlarm = alarm.recurringAlarmId?.let { recurringAlarms[it] },
                                onTap = { selectedAlarm = alarm },
                                onMarkDone = { alarmsViewModel.markDone(alarm.id) },
                                onCancel = { alarmsViewModel.markCancelled(alarm.id) },
                                onEditRecurrence = { openRecurrenceEditor(alarm, it) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // Completed section
                    if (completedAlarms.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.section_completed))
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
                            SectionHeader(stringResource(R.string.section_cancelled))
                        }
                        items(cancelledAlarms) { alarm ->
                            CompletedAlarmItem(
                                alarm = alarm,
                                onTap = { selectedAlarm = alarm },
                                onReactivate = { alarmsViewModel.reactivateAlarm(alarm.id) }
                            )
                        }
                    }

                    // Delete all alarms button
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { showDeleteAllDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.alarm_delete_all_recurring))
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
                text = stringResource(R.string.permission_issues),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        if (!permissionStatus.hasNotificationPermission) {
            Text(
                text = stringResource(R.string.permission_notifications_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        if (!permissionStatus.canScheduleExactAlarms) {
            Text(
                text = stringResource(R.string.permission_exact_alarms_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        if (!permissionStatus.canUseFullScreenIntent) {
            Text(
                text = stringResource(R.string.permission_fullscreen_disabled),
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
    recurringAlarm: RecurringAlarm?,
    onTap: () -> Unit,
    onMarkDone: () -> Unit,
    onCancel: () -> Unit,
    onEditRecurrence: (RecurringAlarm) -> Unit
) {
    val context = LocalContext.current

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
                    text = alarm.displayName.ifEmpty { stringResource(R.string.alarm_untitled) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                alarm.dueTime?.let { timestamp ->
                    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    Text(
                        text = "Due: ${dateFormat.format(timestamp.toDate())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Recurrence description
                if (recurringAlarm != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { onEditRecurrence(recurringAlarm) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.recurrence_edit),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = RecurrenceDescriber.describe(context, recurringAlarm),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(
                onClick = onCancel,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(stringResource(R.string.alarm_skipped), fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = onMarkDone,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.action_button_background),
                    contentColor = colorResource(R.color.action_button_text)
                )
            ) {
                Text(stringResource(R.string.alarm_done), fontSize = 11.sp)
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
                    text = alarm.displayName.ifEmpty { stringResource(R.string.alarm_untitled) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                alarm.updatedAt?.let { timestamp ->
                    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    Text(
                        text = stringResource(R.string.alarm_completed_fmt, dateFormat.format(timestamp.toDate())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(
                onClick = onReactivate,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(stringResource(R.string.alarm_reopen), fontSize = 11.sp)
            }
        }
    }
}

