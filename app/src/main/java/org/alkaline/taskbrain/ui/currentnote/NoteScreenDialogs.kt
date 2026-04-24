package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStage
import org.alkaline.taskbrain.data.RecurringAlarm
import org.alkaline.taskbrain.ui.components.ErrorDialog
import org.alkaline.taskbrain.ui.components.WarningDialog
import org.alkaline.taskbrain.ui.currentnote.components.AlarmConfigDialog
import org.alkaline.taskbrain.ui.currentnote.components.AlarmDialogMode
import org.alkaline.taskbrain.ui.currentnote.components.RecurrenceConfig

/**
 * Renders all conditional dialogs for the note screen:
 * error dialogs, permission warnings, scheduling warnings, redo rollback, and alarm config.
 */
@Composable
fun NoteScreenDialogs(
    saveStatus: UnifiedSaveStatus?,
    loadStatus: LoadStatus?,
    tabsError: TabsError?,
    alarmError: Throwable?,
    alarmPermissionWarning: Boolean,
    notificationPermissionWarning: Boolean,
    schedulingWarning: String?,
    redoRollbackWarning: RedoRollbackWarning?,
    saveWarning: String?,
    onClearSaveWarning: () -> Unit,
    onClearSaveError: () -> Unit,
    onClearLoadError: () -> Unit,
    onClearTabsError: () -> Unit,
    onClearAlarmError: () -> Unit,
    onClearAlarmPermissionWarning: () -> Unit,
    onClearNotificationPermissionWarning: () -> Unit,
    onClearSchedulingWarning: () -> Unit,
    onClearRedoRollbackWarning: () -> Unit,
    showAlarmDialog: Boolean,
    alarmDialogLineContent: String,
    alarmDialogExistingAlarm: Alarm?,
    alarmDialogInitialMode: AlarmDialogMode = AlarmDialogMode.INSTANCE,
    alarmDialogRecurrenceConfig: RecurrenceConfig?,
    alarmDialogRecurringAlarm: RecurringAlarm? = null,
    alarmDialogInstanceCount: Int = 0,
    onAlarmSave: (dueTime: Timestamp?, stages: List<AlarmStage>) -> Unit,
    onAlarmSaveRecurring: (dueTime: Timestamp?, stages: List<AlarmStage>, recurrenceConfig: RecurrenceConfig) -> Unit,
    onAlarmSaveInstance: ((alarm: Alarm, dueTime: Timestamp?, stages: List<AlarmStage>, alsoUpdateRecurrence: Boolean) -> Unit)? = null,
    onAlarmSaveRecurrenceTemplate: ((recurringAlarmId: String, dueTime: Timestamp?, stages: List<AlarmStage>, recurrenceConfig: RecurrenceConfig, alsoUpdateMatchingInstances: Boolean) -> Unit)? = null,
    onAlarmMarkDone: (() -> Unit)?,
    onAlarmMarkCancelled: (() -> Unit)?,
    onAlarmReactivate: (() -> Unit)?,
    onAlarmDelete: (() -> Unit)?,
    onAlarmNavigatePrevious: (() -> Unit)?,
    onAlarmNavigateNext: (() -> Unit)?,
    alarmHasPrevious: Boolean,
    alarmHasNext: Boolean,
    onAlarmDismiss: () -> Unit,
    onFetchRecurrenceConfig: (Alarm?) -> Unit,
) {
    // Error dialogs
    if (saveStatus is UnifiedSaveStatus.PartialError) {
        ErrorDialog(
            title = stringResource(R.string.error_save),
            throwable = saveStatus.error,
            onDismiss = onClearSaveError
        )
    }

    if (loadStatus is LoadStatus.Error) {
        ErrorDialog(
            title = stringResource(R.string.error_load),
            throwable = loadStatus.throwable,
            onDismiss = onClearLoadError
        )
    }

    tabsError?.let { error ->
        ErrorDialog(
            title = stringResource(R.string.error_tabs),
            throwable = error.cause,
            onDismiss = onClearTabsError
        )
    }

    alarmError?.let { throwable ->
        ErrorDialog(
            title = stringResource(R.string.error_alarm),
            throwable = throwable,
            onDismiss = onClearAlarmError
        )
    }

    saveWarning?.let { warning ->
        WarningDialog(
            title = stringResource(R.string.warning_save_failed_title),
            message = warning,
            selectable = true,
            onDismiss = onClearSaveWarning
        )
    }

    // Permission and scheduling warning dialogs
    if (alarmPermissionWarning) {
        WarningDialog(
            title = stringResource(R.string.dialog_exact_alarms_title),
            message = stringResource(R.string.dialog_exact_alarms_message),
            onDismiss = onClearAlarmPermissionWarning
        )
    }

    if (notificationPermissionWarning) {
        WarningDialog(
            title = stringResource(R.string.dialog_notifications_title),
            message = stringResource(R.string.dialog_notifications_message),
            onDismiss = onClearNotificationPermissionWarning
        )
    }

    schedulingWarning?.let { warning ->
        WarningDialog(
            title = stringResource(R.string.dialog_scheduling_title),
            message = "$warning\n\n${stringResource(R.string.dialog_scheduling_message)}",
            selectable = true,
            onDismiss = onClearSchedulingWarning
        )
    }

    redoRollbackWarning?.let { warning ->
        val title = if (warning.rollbackSucceeded) {
            stringResource(R.string.redo_failed)
        } else {
            stringResource(R.string.redo_error)
        }
        val message = if (warning.rollbackSucceeded) {
            stringResource(R.string.redo_alarm_error_prefix) + warning.errorMessage + "\n\n" +
                stringResource(R.string.redo_rollback_message)
        } else {
            stringResource(R.string.redo_alarm_error_prefix) + warning.errorMessage + "\n\n" +
                stringResource(R.string.redo_inconsistent_warning)
        }
        WarningDialog(
            title = title,
            message = message,
            selectable = true,
            onDismiss = onClearRedoRollbackWarning
        )
    }

    // Alarm configuration dialog
    if (showAlarmDialog) {
        LaunchedEffect(alarmDialogExistingAlarm?.id) {
            onFetchRecurrenceConfig(alarmDialogExistingAlarm)
        }

        AlarmConfigDialog(
            lineContent = alarmDialogLineContent,
            existingAlarm = alarmDialogExistingAlarm,
            initialMode = alarmDialogInitialMode,
            existingRecurrenceConfig = alarmDialogRecurrenceConfig,
            recurringAlarm = alarmDialogRecurringAlarm,
            recurringInstanceCount = alarmDialogInstanceCount,
            onSave = onAlarmSave,
            onSaveRecurring = onAlarmSaveRecurring,
            onSaveInstance = onAlarmSaveInstance,
            onSaveRecurrenceTemplate = onAlarmSaveRecurrenceTemplate,
            onMarkDone = onAlarmMarkDone,
            onMarkCancelled = onAlarmMarkCancelled,
            onReactivate = onAlarmReactivate,
            onDelete = onAlarmDelete,
            onNavigatePrevious = onAlarmNavigatePrevious,
            onNavigateNext = onAlarmNavigateNext,
            hasPrevious = alarmHasPrevious,
            hasNext = alarmHasNext,
            onDismiss = onAlarmDismiss
        )
    }
}
