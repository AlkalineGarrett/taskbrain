package org.alkaline.taskbrain.ui.currentnote.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStage
import org.alkaline.taskbrain.data.RecurringAlarm
import org.alkaline.taskbrain.ui.components.DateTimePickerRow
import org.alkaline.taskbrain.ui.components.DialogTitleBar

/**
 * Dialog for configuring an alarm's due time, stages, and optional recurrence.
 *
 * For recurring alarms, supports two modes:
 * - INSTANCE: edit this alarm instance's times (optionally propagate to recurrence template)
 * - RECURRENCE: edit the recurrence template's times/pattern (optionally propagate to matching instances)
 */
@Composable
fun AlarmConfigDialog(
    lineContent: String,
    existingAlarm: Alarm?,
    existingRecurrenceConfig: RecurrenceConfig? = null,
    recurringAlarm: RecurringAlarm? = null,
    recurringInstanceCount: Int = 0,
    initialMode: AlarmDialogMode = AlarmDialogMode.INSTANCE,
    onSave: (dueTime: Timestamp?, stages: List<AlarmStage>) -> Unit,
    onSaveInstance: ((alarm: Alarm, dueTime: Timestamp?, stages: List<AlarmStage>, alsoUpdateRecurrence: Boolean) -> Unit)? = null,
    onSaveRecurrenceTemplate: ((recurringAlarmId: String, dueTime: Timestamp?, stages: List<AlarmStage>, recurrenceConfig: RecurrenceConfig, alsoUpdateMatchingInstances: Boolean) -> Unit)? = null,
    onSaveRecurring: ((dueTime: Timestamp?, stages: List<AlarmStage>, recurrenceConfig: RecurrenceConfig) -> Unit)? = null,
    onEndRecurrence: (() -> Unit)? = null,
    onMarkDone: (() -> Unit)? = null,
    onMarkCancelled: (() -> Unit)? = null,
    onReactivate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onNavigatePrevious: (() -> Unit)? = null,
    onNavigateNext: (() -> Unit)? = null,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onDismiss: () -> Unit
) {
    val state = rememberAlarmConfigState(
        existingAlarm = existingAlarm,
        existingRecurrenceConfig = existingRecurrenceConfig,
        recurringAlarm = recurringAlarm,
        initialMode = initialMode,
    )
    val showModeToggle = state.isRecurring && onSaveInstance != null && onSaveRecurrenceTemplate != null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp)
            ) {
                DialogTitleBar(title = lineContent, onClose = onDismiss)

                if (existingAlarm != null) {
                    AlarmStatusButtons(
                        status = existingAlarm.status,
                        onMarkDone = onMarkDone,
                        onMarkCancelled = onMarkCancelled,
                        onReactivate = onReactivate,
                        onNavigatePrevious = onNavigatePrevious,
                        onNavigateNext = onNavigateNext,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        onDismiss = onDismiss
                    )
                    HorizontalDivider()
                }

                if (showModeToggle) {
                    ModeToggle(
                        mode = state.mode,
                        onModeChange = { state.mode = it },
                        enabled = state.formEnabled
                    )
                }

                AlarmFormSection(
                    state = state,
                    showCrossPropagation = showModeToggle,
                    canSaveRecurring = onSaveRecurring != null,
                    recurringInstanceCount = recurringInstanceCount,
                    onEndRecurrence = onEndRecurrence,
                    onDismiss = onDismiss,
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                BottomButtons(
                    state = state,
                    onSave = onSave,
                    onSaveInstance = onSaveInstance,
                    onSaveRecurrenceTemplate = onSaveRecurrenceTemplate,
                    onSaveRecurring = onSaveRecurring,
                    onEndRecurrence = onEndRecurrence,
                    onDelete = onDelete,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun AlarmFormSection(
    state: AlarmConfigState,
    showCrossPropagation: Boolean,
    canSaveRecurring: Boolean,
    recurringInstanceCount: Int,
    onEndRecurrence: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.alpha(if (state.formEnabled) 1f else 0.4f)) {
        Spacer(modifier = Modifier.height(8.dp))

        DateTimePickerRow(
            label = stringResource(R.string.alarm_due),
            value = state.activeDueTime,
            onValueChange = state::setActiveDueTime,
            showDelete = false
        )

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(4.dp))

        state.activeStages.forEachIndexed { index, stage ->
            StageRow(
                stage = stage,
                dueTime = state.activeDueTime,
                enabled = state.formEnabled,
                onStageChange = { state.updateActiveStage(index, it) }
            )
        }

        if (showCrossPropagation) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            CrossPropagationCheckbox(
                checked = state.crossPropChecked,
                onCheckedChange = { state.setCrossPropChecked(it) },
                label = stringResource(state.crossPropLabelRes),
                enabled = state.formEnabled
            )
        }

        RecurrenceArea(
            state = state,
            canSaveRecurring = canSaveRecurring,
            recurringInstanceCount = recurringInstanceCount,
            onEndRecurrence = onEndRecurrence,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun RecurrenceArea(
    state: AlarmConfigState,
    canSaveRecurring: Boolean,
    recurringInstanceCount: Int,
    onEndRecurrence: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val isRecurring = state.isRecurring
    val mode = state.mode
    if (!isRecurring && !canSaveRecurring) return

    val instanceWithSingleRow = isRecurring && mode == AlarmDialogMode.INSTANCE && recurringInstanceCount <= 1
    val showToggle = (!isRecurring && canSaveRecurring) || instanceWithSingleRow
    val canEndRecurrence = isRecurring &&
        (mode == AlarmDialogMode.RECURRENCE ||
            (mode == AlarmDialogMode.INSTANCE && recurringInstanceCount > 1))

    Spacer(modifier = Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(8.dp))

    RecurrenceConfigSection(
        config = state.recurrenceConfig,
        onConfigChange = state::updateRecurrenceConfig,
        showToggle = showToggle,
    )
    if (canEndRecurrence && onEndRecurrence != null) {
        Spacer(modifier = Modifier.height(8.dp))
        EndRecurrenceButton(onEndRecurrence, onDismiss)
    }
}

@Composable
private fun ModeToggle(
    mode: AlarmDialogMode,
    onModeChange: (AlarmDialogMode) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModeButton(AlarmDialogMode.INSTANCE, mode, R.string.alarm_mode_instance, onModeChange, enabled)
        ModeButton(AlarmDialogMode.RECURRENCE, mode, R.string.alarm_mode_recurrence, onModeChange, enabled)
    }
}

@Composable
private fun RowScope.ModeButton(
    modeValue: AlarmDialogMode,
    currentMode: AlarmDialogMode,
    @StringRes labelRes: Int,
    onModeChange: (AlarmDialogMode) -> Unit,
    enabled: Boolean
) {
    val isSelected = currentMode == modeValue
    OutlinedButton(
        onClick = { if (enabled) onModeChange(modeValue) },
        modifier = Modifier.weight(1f),
        colors = if (isSelected) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(stringResource(labelRes), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun CrossPropagationCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            modifier = Modifier.size(36.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun BottomButtons(
    state: AlarmConfigState,
    onSave: (dueTime: Timestamp?, stages: List<AlarmStage>) -> Unit,
    onSaveInstance: ((alarm: Alarm, dueTime: Timestamp?, stages: List<AlarmStage>, alsoUpdateRecurrence: Boolean) -> Unit)?,
    onSaveRecurrenceTemplate: ((recurringAlarmId: String, dueTime: Timestamp?, stages: List<AlarmStage>, recurrenceConfig: RecurrenceConfig, alsoUpdateMatchingInstances: Boolean) -> Unit)?,
    onSaveRecurring: ((dueTime: Timestamp?, stages: List<AlarmStage>, recurrenceConfig: RecurrenceConfig) -> Unit)?,
    onEndRecurrence: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val isNewAlarm = state.isNewAlarm
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End
    ) {
        if (!isNewAlarm && onDelete != null) {
            TextButton(
                onClick = {
                    onDelete()
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colorResource(R.color.destructive_text)
                )
            ) {
                Text(stringResource(R.string.action_delete))
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        Button(
            onClick = {
                state.dispatchSave(
                    onSave = onSave,
                    onSaveInstance = onSaveInstance,
                    onSaveRecurrenceTemplate = onSaveRecurrenceTemplate,
                    onSaveRecurring = onSaveRecurring,
                    onEndRecurrence = onEndRecurrence,
                )
                onDismiss()
            },
            enabled = state.hasDueTime && state.formEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(R.color.action_button_background),
                contentColor = colorResource(R.color.action_button_text)
            )
        ) {
            Text(stringResource(if (isNewAlarm) R.string.action_create else R.string.action_update))
        }
    }
}

@Composable
private fun EndRecurrenceButton(
    onEndRecurrence: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        OutlinedButton(
            onClick = {
                onEndRecurrence()
                onDismiss()
            },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = colorResource(R.color.destructive_text)
            ),
            border = BorderStroke(1.dp, colorResource(R.color.destructive_text).copy(alpha = 0.5f))
        ) {
            Text(stringResource(R.string.recurrence_end_now))
        }
    }
}
