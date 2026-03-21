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

    /** Offset from the recurrence base time to the due time. */
    val dueOffsetMs: Long = 0,

    /** Stage configuration (offsets are relative to dueTime, same as Alarm). */
    val stages: List<AlarmStage> = Alarm.DEFAULT_STAGES,

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

    /** The time-of-day for this alarm (hour/minute), used by bootstrap to preserve
     *  the correct time-of-day for FIXED recurrences when the current alarm instance
     *  can't be fetched. Unlike a full timestamp, this can't go stale. */
    val anchorTimeOfDay: TimeOfDay? = null,

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
    val displayName: String by lazy { AlarmMarkers.displayName(lineContent) }

    /**
     * Returns true if the alarm instance's time-of-day and stages match the template's
     * anchorTimeOfDay and stages. Used to detect individually-edited instances.
     */
    fun timesMatchInstance(alarm: Alarm): Boolean {
        val instanceTimeOfDay = alarm.dueTime?.toTimeOfDay()
        return instanceTimeOfDay == anchorTimeOfDay && alarm.stages == stages
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
    WEEKLY("Weekly", "FREQ=WEEKLY"),
    MONTHLY("Monthly", "FREQ=MONTHLY")
}
