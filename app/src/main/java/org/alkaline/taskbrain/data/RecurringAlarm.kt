package org.alkaline.taskbrain.data

import com.google.firebase.Timestamp

/**
 * Represents a recurring alarm template that spawns individual [Alarm] instances.
 *
 * Two recurrence modes:
 * - [RecurrenceType.FIXED]: Calendar-anchored (RRULE-based). Next instance is created
 *   when the scheduled time passes, regardless of user action.
 * - [RecurrenceType.RELATIVE]: Completion-anchored. Next instance is created after the
 *   user completes or cancels the current one (completion/cancel date + interval).
 */
data class RecurringAlarm(
    val id: String = "",
    val userId: String = "",
    val noteId: String = "",
    val lineContent: String = "",

    val recurrenceType: RecurrenceType = RecurrenceType.FIXED,

    /** RRULE string for FIXED type (e.g., "FREQ=WEEKLY;BYDAY=MO,WE,FR"). */
    val rrule: String? = null,

    /** Interval in milliseconds for RELATIVE type. */
    val relativeIntervalMs: Long? = null,

    /**
     * Threshold offsets in milliseconds relative to the base alarm time.
     * Negative = before, zero = at, positive = after.
     * Null = threshold not used.
     */
    val upcomingOffsetMs: Long? = null,
    val notifyOffsetMs: Long? = null,
    val urgentOffsetMs: Long? = null,
    val alarmOffsetMs: Long? = null,

    /** Optional end date — no more instances after this. */
    val endDate: Timestamp? = null,

    /** Optional repeat count — stop after this many completions. */
    val repeatCount: Int? = null,

    /** Number of instances completed so far (for tracking against repeatCount). */
    val completionCount: Int = 0,

    /** Last completion date (anchor for RELATIVE type's next instance). */
    val lastCompletionDate: Timestamp? = null,

    /** The ID of the currently active alarm instance (if any). */
    val currentAlarmId: String? = null,

    val status: RecurringAlarmStatus = RecurringAlarmStatus.ACTIVE,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    /** Whether more instances should be created based on end conditions. */
    val hasReachedEnd: Boolean
        get() {
            if (status == RecurringAlarmStatus.ENDED) return true
            if (repeatCount != null && completionCount >= repeatCount) return true
            if (endDate != null) {
                val now = Timestamp.now()
                if (now >= endDate) return true
            }
            return false
        }

    /** Display-friendly name derived from line content. */
    val displayName: String by lazy {
        var result = lineContent
        result = result.trimStart('\t')
        DISPLAY_PREFIXES.forEach { prefix ->
            if (result.startsWith(prefix)) {
                result = result.removePrefix(prefix)
            }
        }
        result = result.trimStart()
        if (result.endsWith(ALARM_SYMBOL)) {
            result = result.dropLast(ALARM_SYMBOL.length)
            if (result.endsWith(" ")) result = result.dropLast(1)
        }
        result.trim()
    }

    companion object {
        private const val ALARM_SYMBOL = "⏰"
        private val DISPLAY_PREFIXES = listOf("• ", "☐ ", "☑ ")
    }
}

enum class RecurrenceType {
    /** Calendar-anchored recurrence using RRULE. */
    FIXED,
    /** Completion-anchored recurrence using a time interval. */
    RELATIVE
}

enum class RecurringAlarmStatus {
    /** Actively creating instances. */
    ACTIVE,
    /** Temporarily paused by user. */
    PAUSED,
    /** Permanently ended (end condition met or user stopped). */
    ENDED
}

/**
 * Preset recurrence patterns for quick selection.
 * Each maps to an RRULE string.
 */
enum class RecurrencePreset(val label: String, val rrule: String) {
    DAILY("Daily", "FREQ=DAILY"),
    WEEKDAYS("Weekdays", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"),
    WEEKLY("Weekly", "FREQ=WEEKLY"),
    MONTHLY("Monthly", "FREQ=MONTHLY")
}
