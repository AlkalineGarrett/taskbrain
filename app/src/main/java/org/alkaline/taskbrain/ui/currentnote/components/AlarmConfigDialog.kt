package org.alkaline.taskbrain.ui.currentnote.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.ui.components.DateTimePickerRow

/**
 * Dialog for configuring an alarm's time thresholds and optional recurrence.
 * Shows the line content at the top and four date/time pickers.
 */
@Composable
fun AlarmConfigDialog(
    lineContent: String,
    existingAlarm: Alarm?,
    existingRecurrenceConfig: RecurrenceConfig? = null,
    onSave: (upcomingTime: Timestamp?, notifyTime: Timestamp?, urgentTime: Timestamp?, alarmTime: Timestamp?) -> Unit,
    onSaveRecurring: ((upcomingTime: Timestamp?, notifyTime: Timestamp?, urgentTime: Timestamp?, alarmTime: Timestamp?, recurrenceConfig: RecurrenceConfig) -> Unit)? = null,
    onMarkDone: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var upcomingTime by remember { mutableStateOf(existingAlarm?.upcomingTime) }
    var notifyTime by remember { mutableStateOf(existingAlarm?.notifyTime) }
    var urgentTime by remember { mutableStateOf(existingAlarm?.urgentTime) }
    var alarmTime by remember { mutableStateOf(existingAlarm?.alarmTime) }
    var recurrenceConfig by remember(existingRecurrenceConfig) {
        mutableStateOf(existingRecurrenceConfig ?: RecurrenceConfig())
    }

    val hasAnyThreshold = upcomingTime != null || notifyTime != null || urgentTime != null || alarmTime != null
    val isNewAlarm = existingAlarm == null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp)
            ) {
                // Line content preview
                Text(
                    text = lineContent.ifEmpty { "(empty line)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Four date/time pickers
                DateTimePickerRow(
                    label = "Show in Upcoming list",
                    value = upcomingTime,
                    onValueChange = { upcomingTime = it }
                )

                DateTimePickerRow(
                    label = "Lock screen notification",
                    value = notifyTime,
                    onValueChange = { notifyTime = it }
                )

                DateTimePickerRow(
                    label = "Urgent (red tint)",
                    value = urgentTime,
                    onValueChange = { urgentTime = it }
                )

                DateTimePickerRow(
                    label = "Sound alarm",
                    value = alarmTime,
                    onValueChange = { alarmTime = it }
                )

                // Recurrence config (for new alarms or existing recurring alarms)
                if (onSaveRecurring != null && (isNewAlarm || existingRecurrenceConfig != null)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    RecurrenceConfigSection(
                        config = recurrenceConfig,
                        onConfigChange = { recurrenceConfig = it }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // Status change buttons for existing alarms
                    if (existingAlarm != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onMarkDone != null) {
                                OutlinedButton(
                                    onClick = {
                                        onMarkDone()
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Done")
                                }
                            }
                            if (onCancel != null) {
                                OutlinedButton(
                                    onClick = {
                                        onCancel()
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFFB71C1C)
                                    )
                                ) {
                                    Text("Cancel Alarm")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Save/dismiss buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (existingAlarm != null && onDelete != null) {
                            TextButton(
                                onClick = {
                                    onDelete()
                                    onDismiss()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = Color(0xFFB71C1C)
                                )
                            ) {
                                Text("Delete")
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }

                        Button(
                            onClick = {
                                if (recurrenceConfig.enabled && onSaveRecurring != null) {
                                    onSaveRecurring(upcomingTime, notifyTime, urgentTime, alarmTime, recurrenceConfig)
                                } else {
                                    onSave(upcomingTime, notifyTime, urgentTime, alarmTime)
                                }
                                onDismiss()
                            },
                            enabled = hasAnyThreshold
                        ) {
                            Text(if (existingAlarm != null) "Update" else "Save")
                        }
                    }
                }
            }
        }
    }
}
