package org.alkaline.taskbrain.ui.currentnote

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.Timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmStage
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.RecurringAlarm
import org.alkaline.taskbrain.data.RecurringAlarmRepository
import org.alkaline.taskbrain.service.AlarmScheduleResult
import org.alkaline.taskbrain.service.AlarmScheduler
import org.alkaline.taskbrain.service.AlarmStateManager
import org.alkaline.taskbrain.service.RecurrenceConfigMapper
import org.alkaline.taskbrain.service.RecurrenceTemplateManager
import org.alkaline.taskbrain.ui.currentnote.components.RecurrenceConfig
import org.alkaline.taskbrain.ui.currentnote.undo.AlarmSnapshot
import org.alkaline.taskbrain.ui.currentnote.util.SymbolOverlay
import org.alkaline.taskbrain.util.PermissionHelper

/**
 * Manages all alarm-related state and operations for the current note.
 * Owns alarm LiveData, caches, and CRUD methods. The ViewModel coordinates
 * between this manager and other concerns (save-then-create, post-save sync).
 */
class NoteAlarmManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val alarmRepository: AlarmRepository,
    private val alarmScheduler: AlarmScheduler,
    private val alarmStateManager: AlarmStateManager,
    private val recurringAlarmRepository: RecurringAlarmRepository,
    private val templateManager: RecurrenceTemplateManager,
    private val getCurrentNoteId: () -> String,
    private val getCurrentNoteLines: () -> List<NoteLine>,
    private val getNoteIdForLine: (Int) -> String
) {
    // region LiveData

    private val _alarmCreated = MutableLiveData<AlarmCreatedEvent?>()
    val alarmCreated: LiveData<AlarmCreatedEvent?> = _alarmCreated

    private val _alarmError = MutableLiveData<Throwable?>()
    val alarmError: LiveData<Throwable?> = _alarmError

    private val _alarmPermissionWarning = MutableLiveData(false)
    val alarmPermissionWarning: LiveData<Boolean> = _alarmPermissionWarning

    private val _notificationPermissionWarning = MutableLiveData(false)
    val notificationPermissionWarning: LiveData<Boolean> = _notificationPermissionWarning

    private val _isAlarmOperationPending = MutableLiveData(false)
    val isAlarmOperationPending: LiveData<Boolean> = _isAlarmOperationPending

    private val _redoRollbackWarning = MutableLiveData<RedoRollbackWarning?>()
    val redoRollbackWarning: LiveData<RedoRollbackWarning?> = _redoRollbackWarning

    private val _schedulingWarning = MutableLiveData<String?>()
    val schedulingWarning: LiveData<String?> = _schedulingWarning

    private val _recurrenceConfig = MutableLiveData<RecurrenceConfig?>()
    val recurrenceConfig: LiveData<RecurrenceConfig?> = _recurrenceConfig

    private val _recurringAlarm = MutableLiveData<RecurringAlarm?>()
    val recurringAlarm: LiveData<RecurringAlarm?> = _recurringAlarm

    // Alarm data keyed by alarm document ID, for rendering symbol overlays.
    // The LiveData values are scoped to the current note's referenced alarms.
    // The backing stores persist across note navigations so previously-fetched
    // alarm data is available immediately on revisit.
    private val _alarmCache = MutableLiveData<Map<String, Alarm>>(emptyMap())
    val alarmCache: LiveData<Map<String, Alarm>> = _alarmCache
    private val alarmCacheStore = mutableMapOf<String, Alarm>()

    // Recurring alarm cache: maps recurrence ID → current instance alarm
    private val _recurringAlarmCache = MutableLiveData<Map<String, Alarm>>(emptyMap())
    val recurringAlarmCache: LiveData<Map<String, Alarm>> = _recurringAlarmCache
    private val recurringAlarmCacheStore = mutableMapOf<String, Alarm>()

    // endregion

    // region Loading & Caching

    /**
     * Loads all alarms referenced by alarm directives in the current note's lines.
     * Handles both [alarm("id")] and [recurringAlarm("id")] directives.
     * For recurring directives, fetches the RecurringAlarm → currentAlarmId → alarm instance.
     *
     * Uses a persistent cache so previously-fetched alarm data renders immediately
     * on the first frame, then refreshes from Firestore in the background.
     */
    fun loadAlarmStates() {
        val extracted = extractAlarmIds(getCurrentNoteLines())
        if (extracted.alarmIds.isEmpty() && extracted.recurringAlarmIds.isEmpty()) {
            _alarmCache.value = emptyMap()
            _recurringAlarmCache.value = emptyMap()
            return
        }

        // Serve from persistent cache immediately so overlays render on the first frame.
        val cachedDirect = extracted.alarmIds
            .mapNotNull { id -> alarmCacheStore[id]?.let { id to it } }.toMap()
        val cachedRecurring = extracted.recurringAlarmIds
            .mapNotNull { id -> recurringAlarmCacheStore[id]?.let { id to it } }.toMap()
        if (cachedDirect.isNotEmpty()) _alarmCache.value = cachedDirect
        if (cachedRecurring.isNotEmpty()) _recurringAlarmCache.value = cachedRecurring

        scope.launch {
            coroutineScope {
                val directDeferred = async {
                    if (extracted.alarmIds.isNotEmpty()) {
                        alarmRepository.getAlarmsByIds(extracted.alarmIds).fold(
                            onSuccess = { it },
                            onFailure = { e ->
                                Log.e(TAG, "Error loading alarm states", e)
                                emptyMap()
                            }
                        )
                    } else {
                        emptyMap()
                    }
                }

                val recurringDeferred = async {
                    if (extracted.recurringAlarmIds.isNotEmpty()) {
                        val entries = extracted.recurringAlarmIds.map { recId ->
                            async { recId to alarmRepository.resolveCurrentInstance(recId) }
                        }.awaitAll()
                        entries.filter { it.second != null }.associate { it.first to it.second!! }
                    } else {
                        emptyMap()
                    }
                }

                val freshDirect = directDeferred.await()
                val freshRecurring = recurringDeferred.await()

                alarmCacheStore.putAll(freshDirect)
                recurringAlarmCacheStore.putAll(freshRecurring)

                _alarmCache.value = freshDirect
                _recurringAlarmCache.value = freshRecurring
            }
        }
    }

    // endregion

    // region Fetching

    fun fetchAlarmById(alarmId: String, onComplete: (Alarm?) -> Unit) {
        scope.launch {
            val alarm = alarmRepository.getAlarm(alarmId).getOrNull()
            onComplete(alarm)
        }
    }

    fun fetchRecurrenceConfig(alarm: Alarm?) {
        if (alarm?.recurringAlarmId == null) {
            _recurrenceConfig.value = null
            _recurringAlarm.value = null
            return
        }
        scope.launch {
            val recurring = recurringAlarmRepository.get(alarm.recurringAlarmId).getOrNull()
            _recurringAlarm.value = recurring
            _recurrenceConfig.value = recurring?.let { RecurrenceConfigMapper.toRecurrenceConfig(it) }
        }
    }

    fun fetchRecurringAlarmInstance(recurringAlarmId: String, onComplete: (Alarm?) -> Unit) {
        scope.launch {
            onComplete(alarmRepository.resolveCurrentInstance(recurringAlarmId))
        }
    }

    suspend fun getInstancesForRecurring(recurringAlarmId: String): List<Alarm> {
        return alarmRepository.getInstancesForRecurring(recurringAlarmId)
            .onFailure { _alarmError.value = it }
            .getOrDefault(emptyList())
    }

    // endregion

    // region Symbol Overlays

    /** Returns symbol overlays for each alarm directive on the given line. */
    fun getSymbolOverlays(
        lineContent: String,
        alarmCache: Map<String, Alarm>,
        recurringAlarmCache: Map<String, Alarm>
    ): List<SymbolOverlay> =
        computeSymbolOverlays(lineContent, alarmCache, recurringAlarmCache, Timestamp.now())

    // endregion

    // region Post-Save Sync

    /**
     * Syncs alarm line content with the current note content.
     * Called after saving to keep alarm display text up to date.
     */
    fun syncAlarmLineContent(trackedLines: List<NoteLine>) {
        scope.launch {
            for (line in trackedLines) {
                line.noteId?.let { noteId ->
                    alarmRepository.updateLineContentForNote(noteId, line.content)
                }
            }
        }
    }

    /**
     * Syncs alarm noteIds with line noteIds.
     * For each line containing alarm directives, updates the alarm's noteId
     * if it doesn't match the line's current noteId.
     */
    fun syncAlarmNoteIds(trackedLines: List<NoteLine>) {
        scope.launch {
            val updates = findAlarmNoteIdUpdates(trackedLines) { alarmId ->
                alarmRepository.getAlarm(alarmId).getOrNull()?.noteId
            }
            for (update in updates) {
                alarmRepository.updateAlarmNoteId(update.alarmId, update.lineNoteId)
            }
        }
    }

    // endregion

    // region Creation

    internal suspend fun createAlarmInternal(
        lineContent: String,
        lineIndex: Int?,
        dueTime: Timestamp?,
        stages: List<AlarmStage>
    ) {
        val noteId = if (lineIndex != null) getNoteIdForLine(lineIndex) else getCurrentNoteId()

        val alarm = Alarm(
            noteId = noteId,
            lineContent = lineContent,
            dueTime = dueTime,
            stages = stages
        )

        checkPermissions()

        alarmStateManager.create(alarm).fold(
            onSuccess = { (alarmId, scheduleResult) ->
                if (!scheduleResult.success) {
                    _schedulingWarning.value = scheduleResult.message
                }

                val alarmSnapshot = AlarmSnapshot(
                    id = alarmId,
                    noteId = alarm.noteId,
                    lineContent = alarm.lineContent,
                    dueTime = alarm.dueTime,
                    stages = alarm.stages
                )

                _alarmCreated.value = AlarmCreatedEvent(alarmId, lineContent, alarmSnapshot)
                loadAlarmStates()
            },
            onFailure = { e ->
                _alarmError.value = e
            }
        )
    }

    internal suspend fun createRecurringAlarmInternal(
        lineContent: String,
        lineIndex: Int?,
        dueTime: Timestamp?,
        stages: List<AlarmStage>,
        recurrenceConfig: RecurrenceConfig
    ) {
        val noteId = if (lineIndex != null) getNoteIdForLine(lineIndex) else getCurrentNoteId()

        val recurringAlarm = RecurrenceConfigMapper.toRecurringAlarm(
            noteId = noteId,
            lineContent = lineContent,
            dueTime = dueTime,
            stages = stages,
            config = recurrenceConfig
        )

        val createResult = recurringAlarmRepository.create(recurringAlarm)
        val recurringId = createResult.getOrNull()
        if (recurringId == null) {
            val cause = createResult.exceptionOrNull()
            Log.e(TAG, "Failed to create recurring alarm", cause)
            _alarmError.value = cause ?: Exception("Failed to create recurring alarm")
            return
        }

        val firstAlarm = Alarm(
            noteId = noteId,
            lineContent = lineContent,
            dueTime = dueTime,
            stages = stages,
            recurringAlarmId = recurringId
        )

        checkPermissions()

        alarmStateManager.create(firstAlarm).fold(
            onSuccess = { (alarmId, scheduleResult) ->
                recurringAlarmRepository.updateCurrentAlarmId(recurringId, alarmId, null)

                if (!scheduleResult.success) {
                    _schedulingWarning.value = scheduleResult.message
                }

                val alarmSnapshot = AlarmSnapshot(
                    id = alarmId,
                    noteId = firstAlarm.noteId,
                    lineContent = firstAlarm.lineContent,
                    dueTime = firstAlarm.dueTime,
                    stages = firstAlarm.stages
                )

                _alarmCreated.value = AlarmCreatedEvent(alarmId, lineContent, alarmSnapshot, recurringAlarmId = recurringId)
                loadAlarmStates()
            },
            onFailure = { e ->
                recurringAlarmRepository.delete(recurringId)
                _alarmError.value = e
            }
        )
    }

    /**
     * Creates a new alarm for the current line (no save).
     */
    fun createAlarm(
        lineContent: String,
        lineIndex: Int? = null,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES
    ) {
        scope.launch {
            createAlarmInternal(lineContent, lineIndex, dueTime, stages)
        }
    }

    private fun checkPermissions() {
        if (!alarmScheduler.canScheduleExactAlarms()) {
            _alarmPermissionWarning.value = true
        }
        if (!PermissionHelper.hasNotificationPermission(application)) {
            _notificationPermissionWarning.value = true
        }
    }

    // endregion

    /**
     * Runs an alarm operation in a coroutine, surfacing errors to [_alarmError]
     * and refreshing alarm caches on success.
     */
    private fun <T> launchAlarmOp(
        operation: suspend () -> Result<T>,
        onSuccess: ((T) -> Unit)? = null
    ) {
        scope.launch {
            operation().fold(
                onSuccess = { value ->
                    onSuccess?.invoke(value)
                    loadAlarmStates()
                },
                onFailure = { e -> _alarmError.value = e }
            )
        }
    }

    private fun checkScheduleResult(result: AlarmScheduleResult) {
        if (!result.success) {
            _schedulingWarning.value = result.message
        }
    }

    // region Updates

    fun updateAlarm(
        alarm: Alarm,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES
    ) = launchAlarmOp(
        operation = { alarmStateManager.update(alarm, dueTime, stages) },
        onSuccess = ::checkScheduleResult
    )

    fun updateRecurringAlarm(
        alarm: Alarm,
        dueTime: Timestamp?,
        stages: List<AlarmStage> = Alarm.DEFAULT_STAGES,
        recurrenceConfig: RecurrenceConfig
    ) {
        scope.launch {
            val recurringAlarmId = alarm.recurringAlarmId
            if (recurringAlarmId == null) {
                updateAlarm(alarm, dueTime, stages)
                return@launch
            }

            val existing = recurringAlarmRepository.get(recurringAlarmId).getOrNull()
            if (existing == null) {
                Log.e(TAG, "Recurring alarm template not found: $recurringAlarmId")
                _alarmError.value = Exception("Recurring alarm template not found")
                return@launch
            }

            val updatedTemplate = RecurrenceConfigMapper.toRecurringAlarm(
                noteId = alarm.noteId,
                lineContent = alarm.lineContent,
                dueTime = dueTime,
                stages = stages,
                config = recurrenceConfig
            ).copy(
                id = existing.id,
                userId = existing.userId,
                completionCount = existing.completionCount,
                lastCompletionDate = existing.lastCompletionDate,
                currentAlarmId = existing.currentAlarmId,
                status = if (recurrenceConfig.enabled) existing.status
                         else org.alkaline.taskbrain.data.RecurringAlarmStatus.ENDED,
                createdAt = existing.createdAt
            )

            recurringAlarmRepository.update(updatedTemplate).fold(
                onSuccess = {
                    alarmStateManager.update(alarm, dueTime, stages).fold(
                        onSuccess = { scheduleResult ->
                            checkScheduleResult(scheduleResult)
                            loadAlarmStates()
                        },
                        onFailure = { e -> _alarmError.value = e }
                    )
                },
                onFailure = { e -> _alarmError.value = e }
            )
        }
    }

    fun updateInstanceTimes(
        alarm: Alarm,
        dueTime: Timestamp?,
        stages: List<AlarmStage>,
        alsoUpdateRecurrence: Boolean
    ) = launchAlarmOp(
        operation = { templateManager.updateInstanceTimes(alarm, dueTime, stages, alsoUpdateRecurrence) },
        onSuccess = ::checkScheduleResult
    )

    fun updateRecurrenceTemplate(
        recurringAlarmId: String,
        dueTime: Timestamp?,
        stages: List<AlarmStage>,
        recurrenceConfig: RecurrenceConfig,
        alsoUpdateMatchingInstances: Boolean
    ) = launchAlarmOp<Unit>(
        operation = { templateManager.updateRecurrenceTemplate(
            recurringAlarmId, dueTime, stages, recurrenceConfig, alsoUpdateMatchingInstances
        ) }
    )

    // endregion

    // region State Changes

    fun markAlarmDone(alarmId: String) = launchAlarmOp<Unit>(
        operation = { alarmStateManager.markDone(alarmId) }
    )

    fun cancelAlarm(alarmId: String) = launchAlarmOp<Unit>(
        operation = { alarmStateManager.markCancelled(alarmId) }
    )

    fun reactivateAlarm(alarmId: String) = launchAlarmOp<AlarmScheduleResult?>(
        operation = { alarmStateManager.reactivate(alarmId) }
    )

    fun deleteAlarmPermanently(alarmId: String, onComplete: (() -> Unit)? = null) {
        _isAlarmOperationPending.value = true
        scope.launch {
            alarmStateManager.delete(alarmId).fold(
                onSuccess = {
                    onComplete?.invoke()
                    loadAlarmStates()
                },
                onFailure = { e -> _alarmError.value = e }
            )
            _isAlarmOperationPending.value = false
        }
    }

    fun recreateAlarm(
        alarmSnapshot: AlarmSnapshot,
        onAlarmCreated: (String) -> Unit,
        onFailure: ((String) -> Unit)? = null
    ) {
        _isAlarmOperationPending.value = true
        scope.launch {
            val alarm = Alarm(
                noteId = alarmSnapshot.noteId,
                lineContent = alarmSnapshot.lineContent,
                dueTime = alarmSnapshot.dueTime,
                stages = alarmSnapshot.stages
            )

            alarmStateManager.create(alarm).fold(
                onSuccess = { (newAlarmId, _) ->
                    onAlarmCreated(newAlarmId)
                },
                onFailure = { e ->
                    onFailure?.invoke(e.message ?: "Unknown error")
                }
            )
            _isAlarmOperationPending.value = false
        }
    }

    // endregion

    // region Clear Methods

    fun clearAlarmCreatedEvent() {
        _alarmCreated.value = null
    }

    fun clearAlarmError() {
        _alarmError.value = null
    }

    fun clearAlarmPermissionWarning() {
        _alarmPermissionWarning.value = false
    }

    fun clearNotificationPermissionWarning() {
        _notificationPermissionWarning.value = false
    }

    fun clearSchedulingWarning() {
        _schedulingWarning.value = null
    }

    fun showRedoRollbackWarning(rollbackSucceeded: Boolean, errorMessage: String) {
        _redoRollbackWarning.value = RedoRollbackWarning(rollbackSucceeded, errorMessage)
    }

    fun clearRedoRollbackWarning() {
        _redoRollbackWarning.value = null
    }

    // endregion

    companion object {
        private const val TAG = "NoteAlarmManager"
    }
}
