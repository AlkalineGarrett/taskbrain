package org.alkaline.taskbrain.data

import com.google.firebase.Timestamp

/**
 * Represents an alarm/reminder for a note line.
 * Alarms can have multiple time thresholds for different notification types.
 */
data class Alarm(
    val id: String = "",
    val userId: String = "",
    val noteId: String = "",                   // Associated note/line ID (from NoteLineTracker)
    val lineContent: String = "",              // Snapshot of line text (for display, updated on save)
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,

    // Four time thresholds (nullable = not set)
    val upcomingTime: Timestamp? = null,       // When to show in "Upcoming" list
    val notifyTime: Timestamp? = null,         // Lock screen notification + status bar icon
    val urgentTime: Timestamp? = null,         // Lock screen red tint (full-screen red activity)
    val alarmTime: Timestamp? = null,          // Audible alarm with snooze

    val status: AlarmStatus = AlarmStatus.PENDING,
    val snoozedUntil: Timestamp? = null        // If snoozed, when to fire again
) {
    /**
     * Display-friendly name with bullets, checkboxes, tabs, and alarm symbols removed.
     * Computed lazily and cached.
     */
    val displayName: String by lazy {
        var result = lineContent

        // Remove leading tabs
        result = result.trimStart('\t')

        // Remove bullet/checkbox prefixes
        DISPLAY_PREFIXES.forEach { prefix ->
            if (result.startsWith(prefix)) {
                result = result.removePrefix(prefix)
            }
        }

        // Trim leading whitespace after prefix removal
        result = result.trimStart()

        // Remove trailing alarm symbol and space before it
        if (result.endsWith(ALARM_SYMBOL)) {
            result = result.dropLast(ALARM_SYMBOL.length)
            if (result.endsWith(" ")) {
                result = result.dropLast(1)
            }
        }

        result.trim()
    }

    /**
     * The latest configured threshold time across all four thresholds.
     * An alarm is past due when this time is in the past.
     */
    val latestThresholdTime: Timestamp?
        get() = listOfNotNull(upcomingTime, notifyTime, urgentTime, alarmTime)
            .maxByOrNull { it.toDate().time }

    companion object {
        private const val ALARM_SYMBOL = "⏰"
        private val DISPLAY_PREFIXES = listOf("• ", "☐ ", "☑ ")
    }
}

enum class AlarmStatus {
    PENDING,    // Active alarm
    DONE,       // User marked as done
    CANCELLED   // User cancelled (not done)
}

enum class SnoozeDuration(val minutes: Int) {
    TWO_MINUTES(2),
    TEN_MINUTES(10),
    ONE_HOUR(60)
}

/**
 * Priority level for alarms, used for status bar icon color.
 * Ordered from lowest to highest priority.
 */
enum class AlarmPriority {
    UPCOMING,  // Green - upcomingTime is set
    NOTIFY,    // Yellow - notifyTime has passed or is imminent
    URGENT,    // Orange - urgentTime has passed or is imminent
    ALARM      // Red - alarmTime has passed or is imminent
}

/**
 * Type of alarm notification to show.
 */
enum class AlarmType {
    NOTIFY,     // Regular notification
    URGENT,     // Full-screen urgent notification
    ALARM       // Audible alarm with snooze options
}
