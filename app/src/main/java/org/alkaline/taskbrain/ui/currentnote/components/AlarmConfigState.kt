package org.alkaline.taskbrain.ui.currentnote.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStage
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.RecurringAlarm

enum class AlarmDialogMode { INSTANCE, RECURRENCE }

/**
 * State holder for [AlarmConfigDialog]. Owns the mode toggle plus parallel
 * instance-mode and recurrence-mode form data. The original dialog kept eight
 * `mutableStateOf` vars side-by-side; this collapses the read/write paths into
 * `activeDueTime`/`activeStages`/`setActiveDueTime`/`updateActiveStage` while
 * preserving the original keying semantics — instance state survives async
 * arrival of the recurrence template.
 */
@Stable
class AlarmConfigState internal constructor(
    private val modeState: MutableState<AlarmDialogMode>,
    private val instanceDueTimeState: MutableState<Timestamp?>,
    private val instanceStagesState: MutableState<List<AlarmStage>>,
    private val recurrenceDueTimeState: MutableState<Timestamp?>,
    private val recurrenceStagesState: MutableState<List<AlarmStage>>,
    private val recurrenceConfigState: MutableState<RecurrenceConfig>,
    private val alsoUpdateRecurrenceState: MutableState<Boolean>,
    private val alsoUpdateInstancesState: MutableState<Boolean>,
    private val existingAlarm: Alarm?,
    private val recurringAlarm: RecurringAlarm?,
) {
    // Active mode is the only freely-mutable property — everything else is routed
    // through the active-mode helpers (setActiveDueTime / updateActiveStage /
    // setCrossPropChecked) so writes always land on the correct half.
    var mode: AlarmDialogMode by modeState
    private var instanceDueTime: Timestamp? by instanceDueTimeState
    private var instanceStages: List<AlarmStage> by instanceStagesState
    private var recurrenceDueTime: Timestamp? by recurrenceDueTimeState
    private var recurrenceStages: List<AlarmStage> by recurrenceStagesState
    var recurrenceConfig: RecurrenceConfig by recurrenceConfigState
        private set
    private var alsoUpdateRecurrence: Boolean by alsoUpdateRecurrenceState
    private var alsoUpdateInstances: Boolean by alsoUpdateInstancesState

    val isRecurring: Boolean get() = recurringAlarm != null
    val isNewAlarm: Boolean get() = existingAlarm == null
    private val isPending: Boolean get() = existingAlarm?.status == AlarmStatus.PENDING
    val formEnabled: Boolean get() = isNewAlarm || isPending

    val activeDueTime: Timestamp?
        get() = if (mode == AlarmDialogMode.RECURRENCE) recurrenceDueTime else instanceDueTime
    val activeStages: List<AlarmStage>
        get() = if (mode == AlarmDialogMode.RECURRENCE) recurrenceStages else instanceStages
    val hasDueTime: Boolean get() = activeDueTime != null

    fun setActiveDueTime(value: Timestamp?) {
        if (!formEnabled) return
        if (mode == AlarmDialogMode.RECURRENCE) recurrenceDueTime = value else instanceDueTime = value
    }

    fun updateActiveStage(index: Int, updated: AlarmStage) {
        if (!formEnabled) return
        if (mode == AlarmDialogMode.RECURRENCE) {
            recurrenceStages = recurrenceStages.toMutableList().also { it[index] = updated }
        } else {
            instanceStages = instanceStages.toMutableList().also { it[index] = updated }
        }
    }

    fun updateRecurrenceConfig(value: RecurrenceConfig) {
        if (!formEnabled) return
        recurrenceConfig = value
    }

    /**
     * Mode-dependent cross-propagation checkbox: in INSTANCE mode this controls
     * whether instance edits also update the recurrence template; in RECURRENCE
     * mode it controls whether template edits propagate to matching instances.
     */
    val crossPropChecked: Boolean
        get() = if (mode == AlarmDialogMode.INSTANCE) alsoUpdateRecurrence else alsoUpdateInstances

    @get:StringRes
    val crossPropLabelRes: Int
        get() = if (mode == AlarmDialogMode.INSTANCE) {
            R.string.alarm_also_update_recurrence
        } else {
            R.string.alarm_also_update_next
        }

    fun setCrossPropChecked(value: Boolean) {
        if (mode == AlarmDialogMode.INSTANCE) alsoUpdateRecurrence = value else alsoUpdateInstances = value
    }

    /** Routes a save click to the right callback based on mode + recurrence presence. */
    fun dispatchSave(
        onSave: (Timestamp?, List<AlarmStage>) -> Unit,
        onSaveInstance: ((Alarm, Timestamp?, List<AlarmStage>, Boolean) -> Unit)?,
        onSaveRecurrenceTemplate: ((String, Timestamp?, List<AlarmStage>, RecurrenceConfig, Boolean) -> Unit)?,
        onSaveRecurring: ((Timestamp?, List<AlarmStage>, RecurrenceConfig) -> Unit)?,
        onEndRecurrence: (() -> Unit)?,
    ) {
        when {
            isRecurring && mode == AlarmDialogMode.INSTANCE &&
                existingAlarm != null && onSaveInstance != null ->
                onSaveInstance(existingAlarm, instanceDueTime, instanceStages, alsoUpdateRecurrence)

            isRecurring && mode == AlarmDialogMode.RECURRENCE &&
                recurringAlarm != null && onSaveRecurrenceTemplate != null ->
                onSaveRecurrenceTemplate(
                    recurringAlarm.id, recurrenceDueTime, recurrenceStages,
                    recurrenceConfig, alsoUpdateInstances,
                )

            recurrenceConfig.enabled && onSaveRecurring != null ->
                onSaveRecurring(instanceDueTime, instanceStages, recurrenceConfig)

            else -> {
                onSave(instanceDueTime, instanceStages)
                if (!recurrenceConfig.enabled) onEndRecurrence?.invoke()
            }
        }
    }
}

/**
 * Builds an [AlarmConfigState] with the keying behaviour the original dialog
 * relied on:
 *   - `mode` and instance fields re-init only on alarm change
 *   - recurrence fields re-init when the recurring template arrives async
 *   - `recurrenceConfig` re-inits when the fetched config arrives async
 */
@Composable
fun rememberAlarmConfigState(
    existingAlarm: Alarm?,
    existingRecurrenceConfig: RecurrenceConfig?,
    recurringAlarm: RecurringAlarm?,
    initialMode: AlarmDialogMode,
): AlarmConfigState {
    val alarmKey = existingAlarm?.id
    val recurringKey = recurringAlarm?.id
    val timesMatchPreEdit = remember(alarmKey, recurringKey) {
        recurringAlarm != null && existingAlarm != null &&
            recurringAlarm.timesMatchInstance(existingAlarm)
    }

    val modeState = remember(alarmKey) { mutableStateOf(initialMode) }
    val instanceDueTimeState = remember(alarmKey) { mutableStateOf(existingAlarm?.dueTime) }
    val instanceStagesState = remember(alarmKey) {
        mutableStateOf(existingAlarm?.stages ?: Alarm.DEFAULT_STAGES)
    }

    val recurrenceDueTimeState = remember(alarmKey, recurringKey) {
        mutableStateOf(
            recurringAlarm?.anchorTimeOfDay?.let { anchor ->
                existingAlarm?.dueTime?.let { instanceDue ->
                    anchor.onSameDateAs(instanceDue.toDate())
                }
            } ?: existingAlarm?.dueTime
        )
    }
    val recurrenceStagesState = remember(alarmKey, recurringKey) {
        mutableStateOf(recurringAlarm?.stages ?: existingAlarm?.stages ?: Alarm.DEFAULT_STAGES)
    }
    val recurrenceConfigState = remember(alarmKey, existingRecurrenceConfig) {
        mutableStateOf(existingRecurrenceConfig ?: RecurrenceConfig())
    }
    val alsoUpdateRecurrenceState = remember(alarmKey, recurringKey) {
        mutableStateOf(timesMatchPreEdit)
    }
    val alsoUpdateInstancesState = remember(alarmKey, recurringKey) { mutableStateOf(true) }

    return remember(
        modeState, instanceDueTimeState, instanceStagesState,
        recurrenceDueTimeState, recurrenceStagesState, recurrenceConfigState,
        alsoUpdateRecurrenceState, alsoUpdateInstancesState,
        existingAlarm, recurringAlarm,
    ) {
        AlarmConfigState(
            modeState = modeState,
            instanceDueTimeState = instanceDueTimeState,
            instanceStagesState = instanceStagesState,
            recurrenceDueTimeState = recurrenceDueTimeState,
            recurrenceStagesState = recurrenceStagesState,
            recurrenceConfigState = recurrenceConfigState,
            alsoUpdateRecurrenceState = alsoUpdateRecurrenceState,
            alsoUpdateInstancesState = alsoUpdateInstancesState,
            existingAlarm = existingAlarm,
            recurringAlarm = recurringAlarm,
        )
    }
}
