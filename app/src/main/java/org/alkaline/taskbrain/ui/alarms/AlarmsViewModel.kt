package org.alkaline.taskbrain.ui.alarms

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.google.firebase.Timestamp
import java.util.Calendar
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmUpdateEvent
import org.alkaline.taskbrain.data.RecurringAlarm
import org.alkaline.taskbrain.data.RecurringAlarmRepository
import org.alkaline.taskbrain.service.AlarmStateManager
import org.alkaline.taskbrain.service.NotificationHelper
import org.alkaline.taskbrain.service.UrgentStateManager
import org.alkaline.taskbrain.service.RecurrenceConfigMapper
import org.alkaline.taskbrain.ui.currentnote.components.RecurrenceConfig

class AlarmsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AlarmRepository()
    private val recurringRepository = RecurringAlarmRepository()
    private val alarmStateManager = AlarmStateManager(application)
    private val notificationHelper = NotificationHelper(application)

    private val _pastDueAlarms = MutableLiveData<List<Alarm>>(emptyList())
    val pastDueAlarms: LiveData<List<Alarm>> = _pastDueAlarms

    private val _upcomingAlarms = MutableLiveData<List<Alarm>>(emptyList())
    val upcomingAlarms: LiveData<List<Alarm>> = _upcomingAlarms

    private val _laterAlarms = MutableLiveData<List<Alarm>>(emptyList())
    val laterAlarms: LiveData<List<Alarm>> = _laterAlarms

    private val _completedAlarms = MutableLiveData<List<Alarm>>(emptyList())
    val completedAlarms: LiveData<List<Alarm>> = _completedAlarms

    private val _cancelledAlarms = MutableLiveData<List<Alarm>>(emptyList())
    val cancelledAlarms: LiveData<List<Alarm>> = _cancelledAlarms

    private val _recurringAlarms = MutableLiveData<Map<String, RecurringAlarm>>(emptyMap())
    val recurringAlarms: LiveData<Map<String, RecurringAlarm>> = _recurringAlarms

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<Throwable?>(null)
    val error: LiveData<Throwable?> = _error

    init {
        loadAlarms()

        // Observe alarm updates from external sources (e.g., notification actions)
        AlarmUpdateEvent.updates
            .onEach { loadAlarms() }
            .launchIn(viewModelScope)
    }

    fun loadAlarms() {
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            // Load all alarm categories and recurring templates in parallel
            val pendingDeferred = async { repository.getPendingAlarms() }
            val completedDeferred = async { repository.getCompletedAlarms() }
            val cancelledDeferred = async { repository.getCancelledAlarms() }
            val recurringDeferred = async { recurringRepository.getActiveRecurringAlarms() }

            val pendingResult = pendingDeferred.await()
            val completedResult = completedDeferred.await()
            val cancelledResult = cancelledDeferred.await()
            val recurringResult = recurringDeferred.await()

            var firstError: Throwable? = null

            recurringResult.fold(
                onSuccess = { templates ->
                    _recurringAlarms.value = templates.associateBy { it.id }
                },
                onFailure = {
                    Log.e(TAG, "Error loading recurring alarms", it)
                    if (firstError == null) firstError = it
                }
            )

            pendingResult.fold(
                onSuccess = { alarms ->
                    val now = Timestamp.now()
                    val endOfToday = endOfDay(now)

                    val pastDue = mutableListOf<Alarm>()
                    val upcoming = mutableListOf<Alarm>()
                    val later = mutableListOf<Alarm>()

                    for (alarm in alarms) {
                        val earliest = alarm.earliestThresholdTime
                        when {
                            isPastDue(alarm, now) -> pastDue.add(alarm)
                            earliest != null && earliest <= endOfToday -> upcoming.add(alarm)
                            else -> later.add(alarm)
                        }
                    }

                    _pastDueAlarms.value = pastDue.sortedBy { it.latestThresholdTime?.toDate()?.time }
                    _upcomingAlarms.value = upcoming.sortedBy { it.earliestThresholdTime?.toDate()?.time }
                    _laterAlarms.value = later.sortedBy { it.earliestThresholdTime?.toDate()?.time }
                },
                onFailure = {
                    Log.e(TAG, "Error loading pending alarms", it)
                    if (firstError == null) firstError = it
                }
            )

            completedResult.fold(
                onSuccess = { _completedAlarms.value = it },
                onFailure = {
                    Log.e(TAG, "Error loading completed alarms", it)
                    if (firstError == null) firstError = it
                }
            )

            cancelledResult.fold(
                onSuccess = { _cancelledAlarms.value = it },
                onFailure = {
                    Log.e(TAG, "Error loading cancelled alarms", it)
                    if (firstError == null) firstError = it
                }
            )

            firstError?.let { _error.value = it }
            _isLoading.value = false
        }
    }

    fun markDone(alarmId: String) = executeAlarmOperation { alarmStateManager.markDone(alarmId) }

    fun markCancelled(alarmId: String) = executeAlarmOperation { alarmStateManager.markCancelled(alarmId) }

    fun deleteAlarm(alarmId: String) = executeAlarmOperation { alarmStateManager.delete(alarmId) }

    fun reactivateAlarm(alarmId: String) = executeAlarmOperation { alarmStateManager.reactivate(alarmId) }

    /**
     * Re-shows notifications for all pending alarms that have at least one stage
     * whose time has already passed but whose notification is not currently active.
     */
    fun reshowNotifications() {
        viewModelScope.launch {
            val now = Timestamp.now()
            val allPending = (_pastDueAlarms.value.orEmpty() + _upcomingAlarms.value.orEmpty())
            for (alarm in allPending) {
                val earliest = alarm.earliestThresholdTime ?: continue
                if (earliest <= now && !notificationHelper.isNotificationActive(alarm.id)) {
                    notificationHelper.showNotification(alarm)
                }
            }
        }
    }

    fun updateAlarm(
        alarm: Alarm,
        dueTime: Timestamp?,
        stages: List<org.alkaline.taskbrain.data.AlarmStage> = org.alkaline.taskbrain.data.Alarm.DEFAULT_STAGES
    ) = executeAlarmOperation {
        alarmStateManager.update(alarm, dueTime, stages).also { result ->
            result.getOrNull()?.let { scheduleResult ->
                if (!scheduleResult.success) {
                    Log.w(TAG, "Alarm scheduling warning: ${scheduleResult.message}")
                }
            }
        }
    }

    fun updateAlarmAndRecurrence(
        alarm: Alarm,
        dueTime: Timestamp?,
        stages: List<org.alkaline.taskbrain.data.AlarmStage> = org.alkaline.taskbrain.data.Alarm.DEFAULT_STAGES,
        recurrenceConfig: RecurrenceConfig
    ) = executeAlarmOperation {
        // Update the instance
        alarmStateManager.update(alarm, dueTime, stages).also { result ->
            result.getOrNull()?.let { scheduleResult ->
                if (!scheduleResult.success) {
                    Log.w(TAG, "Alarm scheduling warning: ${scheduleResult.message}")
                }
            }
        }

        // Update or create the recurring alarm template
        val recurringAlarmId = alarm.recurringAlarmId
        if (recurringAlarmId != null) {
            val existing = recurringRepository.get(recurringAlarmId).getOrNull()
            if (existing != null) {
                val updated = applyRecurrenceConfig(existing, dueTime, stages, recurrenceConfig)
                return@executeAlarmOperation recurringRepository.update(updated)
            }
        }

        // No existing template — create one and link the alarm to it
        val template = RecurrenceConfigMapper.toRecurringAlarm(
            noteId = alarm.noteId,
            lineContent = alarm.lineContent,
            dueTime = dueTime,
            stages = stages,
            config = recurrenceConfig
        )
        val createResult = recurringRepository.create(template)
        createResult.onSuccess { newRecurringId ->
            repository.linkToRecurringAlarm(alarm.id, newRecurringId)
            recurringRepository.updateCurrentAlarmId(newRecurringId, alarm.id, alarm.dueTime)
        }
        createResult.map { }
    }

    fun updateRecurrence(
        recurringAlarmId: String,
        recurrenceConfig: RecurrenceConfig
    ) = executeAlarmOperation {
        val existing = recurringRepository.get(recurringAlarmId).getOrNull()
            ?: return@executeAlarmOperation Result.failure<Unit>(
                IllegalStateException("Recurring alarm not found: $recurringAlarmId")
            )

        // Use the existing alarm's current instance to get threshold times for offset calculation
        val currentAlarm = existing.currentAlarmId?.let { repository.getAlarm(it).getOrNull() }

        val updated = applyRecurrenceConfig(
            existing,
            currentAlarm?.dueTime,
            currentAlarm?.stages ?: org.alkaline.taskbrain.data.Alarm.DEFAULT_STAGES,
            recurrenceConfig
        )
        recurringRepository.update(updated)
    }

    /**
     * Builds an updated [RecurringAlarm] by applying new recurrence config and stage configuration
     * while preserving the existing template's identity and state fields.
     */
    private fun applyRecurrenceConfig(
        existing: RecurringAlarm,
        dueTime: Timestamp?,
        stages: List<org.alkaline.taskbrain.data.AlarmStage>,
        config: RecurrenceConfig
    ): RecurringAlarm = RecurrenceConfigMapper.toRecurringAlarm(
        noteId = existing.noteId,
        lineContent = existing.lineContent,
        dueTime = dueTime,
        stages = stages,
        config = config
    ).copy(
        id = existing.id,
        userId = existing.userId,
        completionCount = existing.completionCount,
        lastCompletionDate = existing.lastCompletionDate,
        currentAlarmId = existing.currentAlarmId,
        status = existing.status,
        createdAt = existing.createdAt
    )

    private fun executeAlarmOperation(operation: suspend () -> Result<*>) {
        viewModelScope.launch {
            operation().fold(
                onSuccess = { loadAlarms() },
                onFailure = { _error.value = it }
            )
        }
    }

    /**
     * Ends a recurrence: marks the template as ENDED and cancels/deletes all future
     * pending instances. Completed/cancelled instances are preserved.
     * If [deleteTemplate] is true, hard-deletes the template and unlinks the alarm
     * (used when only one instance exists).
     */
    fun endRecurrence(recurringAlarmId: String, deleteTemplate: Boolean = false) = executeAlarmOperation {
        // Cancel all pending instances
        val pendingInstances = repository.getPendingInstancesForRecurring(recurringAlarmId)
            .getOrDefault(emptyList())
        for (instance in pendingInstances) {
            alarmStateManager.deactivate(instance.id)
            repository.markCancelled(instance.id)
        }

        if (deleteTemplate) {
            recurringRepository.delete(recurringAlarmId)
        } else {
            recurringRepository.end(recurringAlarmId)
        }
        Result.success(Unit)
    }

    fun deleteAllRecurringAlarms() = executeAlarmOperation {
        // Delete all recurring alarm instances (alarms with recurringAlarmId set)
        val instanceIds = repository.deleteRecurringAlarmInstances().getOrDefault(emptyList())

        // Deactivate each instance (cancel PendingIntents, exit urgent, dismiss notification)
        for (alarmId in instanceIds) {
            alarmStateManager.deactivate(alarmId)
        }

        // Force-clear urgent wallpaper in case zombie alarm IDs don't match
        UrgentStateManager(getApplication()).forceExitAllUrgentStates()

        // Delete all recurring alarm templates from server
        recurringRepository.deleteAll()

        Result.success(Unit)
    }

    suspend fun getInstancesForRecurring(recurringAlarmId: String): List<Alarm> {
        return repository.getInstancesForRecurring(recurringAlarmId)
            .onFailure { _error.value = it }
            .getOrDefault(emptyList())
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        private const val TAG = "AlarmsViewModel"

        /**
         * An alarm is past due when its latest configured threshold time is in the past.
         */
        internal fun isPastDue(alarm: Alarm, now: Timestamp): Boolean {
            val latest = alarm.latestThresholdTime ?: return false
            return latest < now
        }

        /**
         * Returns a Timestamp for the end of the day (23:59:59.999) of the given timestamp.
         */
        internal fun endOfDay(timestamp: Timestamp): Timestamp {
            val cal = Calendar.getInstance().apply {
                time = timestamp.toDate()
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            return Timestamp(cal.time)
        }
    }
}
