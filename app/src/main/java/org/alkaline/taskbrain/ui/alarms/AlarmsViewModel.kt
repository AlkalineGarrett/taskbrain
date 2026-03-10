package org.alkaline.taskbrain.ui.alarms

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmUpdateEvent
import org.alkaline.taskbrain.service.AlarmStateManager

class AlarmsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AlarmRepository()
    private val alarmStateManager = AlarmStateManager(application)

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
            // Load all alarm categories in parallel
            val upcomingResult = repository.getUpcomingAlarms()
            val laterResult = repository.getLaterAlarms()
            val completedResult = repository.getCompletedAlarms()
            val cancelledResult = repository.getCancelledAlarms()

            // Track first error encountered to show to user
            var firstError: Throwable? = null

            upcomingResult.fold(
                onSuccess = { alarms ->
                    val now = Timestamp.now()
                    val (pastDue, upcoming) = alarms.partition { alarm ->
                        isPastDue(alarm, now)
                    }
                    _pastDueAlarms.value = pastDue
                    _upcomingAlarms.value = upcoming
                },
                onFailure = {
                    Log.e(TAG, "Error loading upcoming alarms", it)
                    if (firstError == null) firstError = it
                }
            )

            laterResult.fold(
                onSuccess = { _laterAlarms.value = it },
                onFailure = {
                    Log.e(TAG, "Error loading later alarms", it)
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

            // Show error dialog if any load failed
            firstError?.let { _error.value = it }

            _isLoading.value = false
        }
    }

    fun markDone(alarmId: String) {
        viewModelScope.launch {
            alarmStateManager.markDone(alarmId).fold(
                onSuccess = { loadAlarms() },
                onFailure = { _error.value = it }
            )
        }
    }

    fun markCancelled(alarmId: String) {
        viewModelScope.launch {
            alarmStateManager.markCancelled(alarmId).fold(
                onSuccess = { loadAlarms() },
                onFailure = { _error.value = it }
            )
        }
    }

    fun updateAlarm(
        alarm: Alarm,
        upcomingTime: com.google.firebase.Timestamp?,
        notifyTime: com.google.firebase.Timestamp?,
        urgentTime: com.google.firebase.Timestamp?,
        alarmTime: com.google.firebase.Timestamp?
    ) {
        viewModelScope.launch {
            alarmStateManager.update(alarm, upcomingTime, notifyTime, urgentTime, alarmTime).fold(
                onSuccess = { scheduleResult ->
                    if (!scheduleResult.success) {
                        Log.w(TAG, "Alarm scheduling warning: ${scheduleResult.message}")
                    }
                    loadAlarms()
                },
                onFailure = { _error.value = it }
            )
        }
    }

    fun deleteAlarm(alarmId: String) {
        viewModelScope.launch {
            alarmStateManager.delete(alarmId).fold(
                onSuccess = { loadAlarms() },
                onFailure = { _error.value = it }
            )
        }
    }

    fun reactivateAlarm(alarmId: String) {
        viewModelScope.launch {
            alarmStateManager.reactivate(alarmId).fold(
                onSuccess = { loadAlarms() },
                onFailure = { _error.value = it }
            )
        }
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
    }
}
