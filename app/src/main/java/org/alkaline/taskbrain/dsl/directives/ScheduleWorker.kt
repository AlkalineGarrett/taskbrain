package org.alkaline.taskbrain.dsl.directives

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.alkaline.taskbrain.data.NoteRepository
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.Parser
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.NoteContext
import org.alkaline.taskbrain.dsl.runtime.NoteRepositoryOperations
import org.alkaline.taskbrain.dsl.runtime.values.ScheduleVal
import org.alkaline.taskbrain.dsl.runtime.values.UndefinedVal

/**
 * WorkManager worker that executes scheduled directive actions.
 *
 * This worker is scheduled periodically to check for due schedules and execute them.
 * It handles:
 * - Parsing the directive source to get the lambda action
 * - Executing the lambda with proper context (notes, current note, note operations)
 * - Recording success/failure in ScheduleRepository
 * - Detecting missed schedules (>15 minutes late) for user review
 */
class ScheduleWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val scheduleRepository = ScheduleRepository()
    private val executionRepository = ScheduleExecutionRepository()
    private val noteRepository = NoteRepository()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "ScheduleWorker starting")

        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid

        if (userId == null) {
            Log.w(TAG, "User not signed in, skipping schedule execution")
            return@withContext Result.success()
        }

        try {
            // Get all schedules that are due
            val dueSchedulesResult = scheduleRepository.getDueSchedules()
            val dueSchedules = dueSchedulesResult.getOrNull()

            if (dueSchedules.isNullOrEmpty()) {
                Log.d(TAG, "No schedules due for execution")
                return@withContext Result.success()
            }

            Log.d(TAG, "Found ${dueSchedules.size} schedules due for execution")

            val now = System.currentTimeMillis()

            // Execute each due schedule or mark as missed
            for (schedule in dueSchedules) {
                val scheduledFor = schedule.nextExecution ?: continue
                val scheduledTime = scheduledFor.toDate().time
                val delayMs = now - scheduledTime

                if (delayMs <= LATE_THRESHOLD_MS) {
                    // Within threshold - auto-execute
                    val success = executeSchedule(schedule, userId)
                    executionRepository.recordExecution(
                        scheduleId = schedule.id,
                        scheduledFor = scheduledFor,
                        success = success,
                        error = if (success) null else "Execution failed",
                        manualRun = false
                    )
                } else {
                    // Too late - mark as missed for user review
                    Log.d(TAG, "Schedule ${schedule.id} is ${delayMs / 60000} min late, marking as missed")
                    executionRepository.recordMissed(schedule.id, scheduledFor)
                    // Advance nextExecution so it doesn't keep getting picked up
                    scheduleRepository.advanceNextExecution(schedule.id)
                }
            }

            // Cleanup old missed executions (older than 30 days)
            executionRepository.cleanupOldMissedExecutions(MISSED_RETENTION_DAYS)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ScheduleWorker failed", e)
            Result.retry()
        }
    }

    /**
     * Executes a single schedule's action.
     *
     * @return true if execution was successful, false otherwise
     */
    private suspend fun executeSchedule(schedule: Schedule, userId: String): Boolean {
        Log.d(TAG, "Executing schedule: ${schedule.id} (${schedule.directiveSource})")

        return try {
            val notes = NoteStore.getNotesOrLoad(noteRepository)
            val currentNote = if (schedule.noteId.isNotBlank()) {
                NoteStore.getNoteOrLoad(schedule.noteId, noteRepository)
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
                Log.e(TAG, "Directive did not produce ScheduleVal: ${result::class.simpleName}")
                scheduleRepository.markExecuted(
                    schedule.id,
                    success = false,
                    error = "Directive did not produce a schedule value"
                )
                return false
            }

            // Execute the action lambda
            // The lambda's captured environment already has the execution context
            val action = result.action
            val actionResult = executor.invokeLambda(action, listOf(UndefinedVal))
            Log.d(TAG, "Schedule action executed successfully: ${actionResult.typeName}")

            // Mark as successfully executed
            scheduleRepository.markExecuted(schedule.id, success = true)
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute schedule: ${schedule.id}", e)
            scheduleRepository.markExecuted(
                schedule.id,
                success = false,
                error = e.message ?: "Unknown error"
            )
            false
        }
    }

    companion object {
        private const val TAG = "ScheduleWorker"

        /**
         * Unique work name for the periodic schedule check.
         */
        const val WORK_NAME = "schedule_check"

        /**
         * Maximum delay in milliseconds before a schedule is marked as missed.
         * Schedules more than 15 minutes late require user review.
         */
        private const val LATE_THRESHOLD_MS = 15 * 60 * 1000L

        /**
         * Number of days to retain missed executions before auto-cleanup.
         */
        private const val MISSED_RETENTION_DAYS = 30
    }
}
