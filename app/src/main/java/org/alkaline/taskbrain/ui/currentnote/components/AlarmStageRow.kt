package org.alkaline.taskbrain.ui.currentnote.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.AlarmStage
import org.alkaline.taskbrain.data.AlarmStageType
import org.alkaline.taskbrain.data.TimeOfDay
import org.alkaline.taskbrain.ui.components.TimePickerDialog
import org.alkaline.taskbrain.ui.components.ValueChip
import org.alkaline.taskbrain.util.HOUR_MS
import org.alkaline.taskbrain.util.MINUTE_MS
import org.alkaline.taskbrain.util.formatTimeOfDay
import java.util.Calendar

private val OFFSET_PRESETS = listOf(
    0L,
    5 * MINUTE_MS,
    10 * MINUTE_MS,
    15 * MINUTE_MS,
    30 * MINUTE_MS,
    1 * HOUR_MS,
    2 * HOUR_MS,
    3 * HOUR_MS
)

@Composable
internal fun StageRow(
    stage: AlarmStage,
    dueTime: Timestamp?,
    enabled: Boolean,
    onStageChange: (AlarmStage) -> Unit
) {
    var showOffsetMenu by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val is24Hour = remember { DateFormat.is24HourFormat(context) }

    val stageLabel = stageTypeLabel(stage.type)
    val chipText = formatStageTime(stage)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = stage.enabled,
            onCheckedChange = { if (enabled) onStageChange(stage.copy(enabled = it)) },
            enabled = enabled,
            modifier = Modifier.size(36.dp)
        )

        Text(
            text = stageLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = if (stage.enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Box {
            ValueChip(
                text = chipText,
                isSet = stage.enabled,
                onClick = { if (enabled) showOffsetMenu = true }
            )

            DropdownMenu(
                expanded = showOffsetMenu,
                onDismissRequest = { showOffsetMenu = false }
            ) {
                OFFSET_PRESETS.forEach { offsetMs ->
                    DropdownMenuItem(
                        text = { Text(formatOffset(offsetMs)) },
                        onClick = {
                            onStageChange(stage.copy(offsetMs = offsetMs, absoluteTimeOfDay = null))
                            showOffsetMenu = false
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.datetime_select_time)) },
                    onClick = {
                        showOffsetMenu = false
                        showTimePicker = true
                    }
                )
            }
        }
    }

    if (showTimePicker) {
        StageTimePicker(
            stage = stage,
            dueTime = dueTime,
            is24Hour = is24Hour,
            onConfirm = { timeOfDay ->
                onStageChange(stage.copy(absoluteTimeOfDay = timeOfDay))
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }
}

@Composable
private fun StageTimePicker(
    stage: AlarmStage,
    dueTime: Timestamp?,
    is24Hour: Boolean,
    onConfirm: (TimeOfDay) -> Unit,
    onDismiss: () -> Unit
) {
    val initialHour: Int
    val initialMinute: Int
    if (stage.absoluteTimeOfDay != null) {
        initialHour = stage.absoluteTimeOfDay.hour
        initialMinute = stage.absoluteTimeOfDay.minute
    } else {
        val calendar = Calendar.getInstance()
        if (dueTime != null) {
            calendar.time = java.util.Date(dueTime.toDate().time - stage.offsetMs)
        }
        initialHour = calendar.get(Calendar.HOUR_OF_DAY)
        initialMinute = calendar.get(Calendar.MINUTE)
    }

    TimePickerDialog(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = is24Hour,
        onDismiss = onDismiss,
        onConfirm = { hour, minute -> onConfirm(TimeOfDay(hour, minute)) },
    )
}

@Composable
private fun stageTypeLabel(type: AlarmStageType): String = when (type) {
    AlarmStageType.SOUND_ALARM -> stringResource(R.string.alarm_sound)
    AlarmStageType.LOCK_SCREEN -> stringResource(R.string.alarm_urgent)
    AlarmStageType.NOTIFICATION -> stringResource(R.string.alarm_lock_screen)
}

@Composable
private fun formatStageTime(stage: AlarmStage): String {
    if (stage.absoluteTimeOfDay != null) {
        return formatTimeOfDay(LocalContext.current, stage.absoluteTimeOfDay.hour, stage.absoluteTimeOfDay.minute)
    }
    return formatOffset(stage.offsetMs)
}

@Composable
private fun formatOffset(offsetMs: Long): String {
    if (offsetMs == 0L) return stringResource(R.string.alarm_at_due_time)
    val totalMinutes = offsetMs / MINUTE_MS
    return if (totalMinutes >= 60 && totalMinutes % 60 == 0L) {
        stringResource(R.string.alarm_hours_before, (totalMinutes / 60).toInt())
    } else {
        stringResource(R.string.alarm_minutes_before, totalMinutes.toInt())
    }
}
