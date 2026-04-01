package org.alkaline.taskbrain.dsl.directives

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.NoteContext
import org.alkaline.taskbrain.dsl.runtime.NoteRepositoryOperations
import org.alkaline.taskbrain.dsl.runtime.values.ScheduleVal
import org.alkaline.taskbrain.dsl.runtime.values.UndefinedVal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.receiver.ScheduleAlarmReceiver
import java.util.concurrent.TimeUnit

/**
 * Manages directive schedules: registration, WorkManager coordination, and lifecycle.
 *
 * Responsibilities:
 * - Scanning directives for schedule() calls and registering them
 * - Initializing and managing periodic WorkManager jobs
 * - Handling schedule cancellation on note deletion
 *
 * Usage:
 * ```kotlin
 * // Initialize on app startup
 * ScheduleManager.initialize(context)
 *
 * // Register schedules from a note when it's saved
 * ScheduleManager.registerSchedulesFromNote(note, directiveSource)
 *
 * // Cancel schedules when a note is deleted
 * ScheduleManager.cancelSchedulesForNote(noteId)
 * ```
 */
object ScheduleManager {

    private const val TAG = "ScheduleManager"

    /**
     * Default check interval for the schedule worker.
     * Schedules are checked every 15 minutes (minimum allowed by WorkManager).
     */
    private const val CHECK_INTERVAL_MINUTES = 15L

    private val scheduleRepository = ScheduleRepository()
    private val executionRepository = ScheduleExecutionRepository()
    private val noteRepository = NoteRepository()

    private var appContext: Context? = null

    /**
     * Initialize the schedule manager and start periodic work.
     *
     * Should be called once on app startup (e.g., in Application.onCreate).
     */
    fun initialize(context: Context) {
        Log.d(TAG, "Initializing ScheduleManager")
        appContext = context.applicationContext
        startPeriodicWork(context)
        // Schedule alarms for existing precise schedules
        schedulePreciseAlarmsOnStartup()
    }

    /**
     * Schedule alarms for all active precise schedules on app startup.
     */
    private fun schedulePreciseAlarmsOnStartup() {
        val context = appContext ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val activeSchedules = scheduleRepository.getAllActiveSchedules().getOrNull() ?: return@launch
                for (schedule in activeSchedules) {
                    if (schedule.precise && schedule.nextExecution != null) {
                        schedulePreciseAlarm(context, schedule)
                    }
                }
                Log.d(TAG, "Scheduled alarms for ${activeSchedules.count { it.precise }} precise schedules")
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling precise alarms on startup", e)
            }
        }
    }

    /**
     * Schedule a precise alarm for a schedule using AlarmManager.
     */
    fun schedulePreciseAlarm(context: Context, schedule: Schedule) {
        if (!schedule.precise || schedule.nextExecution == null) {
            Log.d(TAG, "Skipping alarm for non-precise or no-nextExecution schedule: ${schedule.id}")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_SCHEDULE_ALARM
            putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, schedule.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = schedule.nextExecution.toDate().time

        // Only schedule if the trigger time is in the future
        if (triggerTime <= System.currentTimeMillis()) {
            Log.d(TAG, "Schedule ${schedule.id} nextExecution is in the past, not scheduling alarm")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Scheduled precise alarm for ${schedule.id} at $triggerTime")
                } else {
                    Log.w(TAG, "Cannot schedule exact alarms - permission not granted")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled precise alarm for ${schedule.id} at $triggerTime")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling precise alarm for ${schedule.id}", e)
        }
    }

    /**
     * Cancel a precise alarm for a schedule.
     */
    fun cancelPreciseAlarm(context: Context, scheduleId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_SCHEDULE_ALARM
            putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled precise alarm for $scheduleId")
    }

    /**
     * Start the periodic WorkManager job to check for due schedules.
     */
    private fun startPeriodicWork(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ScheduleWorker>(
            CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ScheduleWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        Log.d(TAG, "Periodic schedule check work enqueued")
    }

    /**
     * Stop the periodic WorkManager job.
     *
     * Call this if you need to temporarily disable schedule execution.
     */
    fun stopPeriodicWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ScheduleWorker.WORK_NAME)
        Log.d(TAG, "Periodic schedule check work cancelled")
    }

    /**
     * Scan a directive source for schedule() calls and register them.
     *
     * This should be called when a note is saved to register any new schedules
     * or update existing ones.
     *
     * @param note The note containing the directive
     * @param directiveSource The full directive source text (e.g., "[schedule(daily, [new(...)])]")
     * @return The schedule ID if a schedule was registered, null otherwise
     */
    suspend fun registerScheduleFromDirective(
        note: Note,
        directiveSource: String
    ): String? {
        Log.d(TAG, "Checking directive for schedule: $directiveSource")

        try {
            // Parse and execute to check if it produces a ScheduleVal
            val tokens = Lexer(directiveSource).tokenize()
            val directive = Parser(tokens, directiveSource).parseDirective()

            // Create a minimal environment for analysis
            val env = Environment(NoteContext())
            val executor = Executor()
            val result = executor.execute(directive, env)

            if (result !is ScheduleVal) {
                Log.d(TAG, "Directive does not produce a schedule: ${result::class.simpleName}")
                return null
            }

            // Register the schedule
            val hash = DirectiveResult.hashDirective(directiveSource)
            val scheduleId = scheduleRepository.upsertSchedule(
                noteId = note.id,
                notePath = note.path,
                directiveHash = hash,
                directiveSource = directiveSource,
                frequency = result.frequency,
                atTime = result.atTime,
                precise = result.precise
            ).getOrThrow()

            Log.d(TAG, "Registered schedule: $scheduleId (precise=${result.precise})")

            // If precise, schedule the alarm
            if (result.precise) {
                val context = appContext
                if (context != null) {
                    val schedule = scheduleRepository.getSchedule(scheduleId).getOrNull()
                    if (schedule != null) {
                        schedulePreciseAlarm(context, schedule)
                    }
                }
            }

            return scheduleId

        } catch (e: Exception) {
            Log.e(TAG, "Error registering schedule from directive", e)
            return null
        }
    }

    /**
     * Scan all directives in a note's content and register any schedules found.
     *
     * @param note The note to scan
     * @return List of registered schedule IDs
     */
    suspend fun registerSchedulesFromNote(note: Note): List<String> {
        val directives = DirectiveFinder.findDirectives(note.content)
        val registeredIds = mutableListOf<String>()

        for (directive in directives) {
            val scheduleId = registerScheduleFromDirective(note, directive.sourceText)
            if (scheduleId != null) {
                registeredIds.add(scheduleId)
            }
        }

        return registeredIds
    }

    /**
     * Cancel all schedules associated with a note.
     *
     * Call this when a note is deleted.
     */
    suspend fun cancelSchedulesForNote(noteId: String) {
        Log.d(TAG, "Cancelling schedules for note: $noteId")
        scheduleRepository.cancelSchedulesForNote(noteId)
    }

    /**
     * Get all active schedules for display in UI.
     */
    suspend fun getActiveSchedules(): List<Schedule> {
        return scheduleRepository.getAllActiveSchedules().getOrNull() ?: emptyList()
    }

    /**
     * Get schedules for a specific note.
     */
    suspend fun getSchedulesForNote(noteId: String): List<Schedule> {
        return scheduleRepository.getSchedulesForNote(noteId).getOrNull() ?: emptyList()
    }

    /**
     * Pause a schedule.
     */
    suspend fun pauseSchedule(scheduleId: String) {
        scheduleRepository.pauseSchedule(scheduleId)
    }

    /**
     * Resume a paused schedule.
     */
    suspend fun resumeSchedule(scheduleId: String) {
        scheduleRepository.resumeSchedule(scheduleId)
    }

    /**
     * Delete a schedule permanently.
     */
    suspend fun deleteSchedule(scheduleId: String) {
        scheduleRepository.deleteSchedule(scheduleId)
    }

    /**
     * Manually execute a missed schedule from the Schedules screen.
     *
     * @param executionId The ID of the ScheduleExecution record
     * @return Result indicating success or failure with error message
     */
    suspend fun executeScheduleNow(executionId: String): Result<Unit> = runCatching {
        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid
            ?: throw IllegalStateException("User not signed in")

        // Get the execution record
        val execution = executionRepository.getExecution(executionId).getOrNull()
            ?: throw IllegalStateException("Execution not found: $executionId")

        // Get the schedule
        val schedule = scheduleRepository.getSchedule(execution.scheduleId).getOrNull()
            ?: throw IllegalStateException("Schedule not found: ${execution.scheduleId}")

        Log.d(TAG, "Manually executing schedule: ${schedule.id}")

        try {
            // Execute the schedule action
            executeScheduleAction(schedule, userId)

            // Mark the execution as completed
            executionRepository.markExecuted(executionId, success = true)
            scheduleRepository.markExecuted(schedule.id, success = true)

            Log.d(TAG, "Manual execution completed successfully: $executionId")
        } catch (e: Exception) {
            Log.e(TAG, "Manual execution failed: $executionId", e)
            executionRepository.markExecuted(executionId, success = false, error = e.message)
            throw e
        }
        Unit
    }.onFailure { Log.e(TAG, "Error in executeScheduleNow", it) }

    /**
     * Execute the action associated with a schedule.
     */
    private suspend fun executeScheduleAction(schedule: Schedule, userId: String) {
        // Load notes for the execution context
        val notesResult = noteRepository.loadAllUserNotes()
        val notes = notesResult.getOrNull() ?: emptyList()

        // Load the note containing this schedule for currentNote context
        val currentNote = if (schedule.noteId.isNotBlank()) {
            noteRepository.loadNoteById(schedule.noteId).getOrNull()
        } else {
            null
        }

        // Create note operations for mutations
        val db = FirebaseFirestore.getInstance()
        val noteOperations = NoteRepositoryOperations(db, userId)

        // Parse the directive to get the ScheduleVal
        val tokens = Lexer(schedule.directiveSource).tokenize()
        val directive = Parser(tokens, schedule.directiveSource).parseDirective()

        // Create execution environment
        val context = NoteContext(
            notes = notes,
            currentNote = currentNote,
            noteOperations = noteOperations
        )
        val env = Environment(context)

        // Execute the directive to get the ScheduleVal
        val executor = Executor()
        val result = executor.execute(directive, env)

        if (result !is ScheduleVal) {
            throw IllegalStateException("Directive did not produce ScheduleVal: ${result::class.simpleName}")
        }

        // Execute the action lambda
        val action = result.action
        val actionResult = executor.invokeLambda(action, listOf(UndefinedVal))
        Log.d(TAG, "Schedule action executed: ${actionResult.typeName}")
    }

    /**
     * Dismiss a missed execution without running it.
     * Deletes the ScheduleExecution record.
     *
     * @param executionId The ID of the ScheduleExecution record to dismiss
     */
    suspend fun dismissMissedExecution(executionId: String): Result<Unit> {
        Log.d(TAG, "Dismissing missed execution: $executionId")
        return executionRepository.deleteExecution(executionId)
    }

    /**
     * Get the count of pending missed executions.
     */
    suspend fun getMissedCount(): Int {
        return executionRepository.getMissedCount().getOrNull() ?: 0
    }

    /**
     * Get pending missed executions for the Schedules screen.
     */
    suspend fun getPendingMissedExecutions(): List<ScheduleExecution> {
        return executionRepository.getPendingMissedExecutions().getOrNull() ?: emptyList()
    }

    /**
     * Get pending missed executions enriched with Schedule data.
     */
    suspend fun getEnrichedMissedExecutions(): List<EnrichedExecution> {
        return enrichExecutions(getPendingMissedExecutions())
    }

    /**
     * Get executions from the last 24 hours for the Schedules screen.
     */
    suspend fun getExecutionsLast24Hours(): List<ScheduleExecution> {
        return executionRepository.getExecutionsLast24Hours().getOrNull() ?: emptyList()
    }

    /**
     * Get executions from the last 24 hours enriched with Schedule data.
     */
    suspend fun getEnrichedExecutionsLast24Hours(): List<EnrichedExecution> {
        return enrichExecutions(getExecutionsLast24Hours())
    }

    /**
     * Enriches executions with data from their associated Schedules.
     */
    private suspend fun enrichExecutions(executions: List<ScheduleExecution>): List<EnrichedExecution> {
        if (executions.isEmpty()) return emptyList()

        // Get unique schedule IDs
        val scheduleIds = executions.map { it.scheduleId }.distinct()

        // Batch fetch schedules
        val scheduleMap = mutableMapOf<String, Schedule>()
        for (scheduleId in scheduleIds) {
            scheduleRepository.getSchedule(scheduleId).getOrNull()?.let {
                scheduleMap[scheduleId] = it
            }
        }

        // Get unique note IDs for note name lookup
        val noteIds = scheduleMap.values.map { it.noteId }.distinct().filter { it.isNotBlank() }
        val noteNameMap = mutableMapOf<String, String>()
        for (noteId in noteIds) {
            noteRepository.loadNoteById(noteId).getOrNull()?.let { note ->
                // First line is the note name
                val firstName = note.content.lines().firstOrNull()?.trim() ?: ""
                noteNameMap[noteId] = firstName
            }
        }

        // Build enriched executions
        return executions.map { execution ->
            val schedule = scheduleMap[execution.scheduleId]
            EnrichedExecution(
                execution = execution,
                notePath = schedule?.notePath ?: "",
                noteName = schedule?.let { noteNameMap[it.noteId] } ?: "",
                directiveSource = schedule?.directiveSource ?: ""
            )
        }
    }

    /**
     * Get schedules for the next 24 hours for the Schedules screen.
     */
    suspend fun getSchedulesForNext24Hours(): List<Schedule> {
        return scheduleRepository.getSchedulesForNext24Hours().getOrNull() ?: emptyList()
    }

    /**
     * Trigger an immediate check for due schedules.
     *
     * Useful for testing or when you need schedules to run immediately.
     */
    fun triggerImmediateCheck(context: Context) {
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<ScheduleWorker>()
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "Triggered immediate schedule check")
    }
}
