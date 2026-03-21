package org.alkaline.taskbrain.data

import com.google.firebase.Timestamp
import java.util.Calendar
import java.util.Date

/**
 * A time-of-day without a date, representing user intent (e.g., "trigger at 05:00").
 * Stored as hour (0-23) and minute (0-59) to be date-independent and DST-safe.
 * When resolved to an actual trigger time, the time-of-day is combined with the
 * alarm's due date in the device's local timezone, so DST transitions are handled correctly.
 */
data class TimeOfDay(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23) { "Hour must be 0-23, got $hour" }
        require(minute in 0..59) { "Minute must be 0-59, got $minute" }
    }

    /** Resolves this time-of-day to a [Timestamp] on the same calendar date as [referenceDate]. */
    fun onSameDateAs(referenceDate: Date): Timestamp {
        val cal = Calendar.getInstance()
        cal.time = referenceDate
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return Timestamp(cal.time)
    }

    /** Returns Firestore field entries for this time-of-day (anchorTimeHour/anchorTimeMinute). */
    fun toAnchorFields(): Map<String, Int> = mapOf(
        "anchorTimeHour" to hour,
        "anchorTimeMinute" to minute
    )

    companion object {
        /** Parses a [TimeOfDay] from a Firestore-style map (absoluteTimeHour/absoluteTimeMinute). */
        fun fromMap(map: Map<*, *>): TimeOfDay? {
            val hour = (map["absoluteTimeHour"] as? Number)?.toInt()
            val minute = (map["absoluteTimeMinute"] as? Number)?.toInt()
            if (hour != null && minute != null) return TimeOfDay(hour, minute)
            return null
        }
    }
}

/** Extracts the time-of-day (hour/minute) from a [Timestamp]. */
fun Timestamp.toTimeOfDay(): TimeOfDay {
    val cal = Calendar.getInstance().apply { time = toDate() }
    return TimeOfDay(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

/**
 * A single stage of an alarm (e.g., sound alarm, lock screen, notification).
 * Times are expressed as offsets before dueTime, or optionally as a time-of-day.
 */
data class AlarmStage(
    val type: AlarmStageType,
    /** Milliseconds before dueTime (positive = before due, 0 = at due). */
    val offsetMs: Long = 0,
    val enabled: Boolean = true,
    /** If non-null, overrides offset-based time computation with a specific time-of-day. */
    val absoluteTimeOfDay: TimeOfDay? = null
) {
    /** Resolves this stage's trigger time given the alarm's dueTime. */
    fun resolveTime(dueTime: Timestamp): Timestamp {
        absoluteTimeOfDay?.let { return it.onSameDateAs(dueTime.toDate()) }
        val dueMs = dueTime.toDate().time
        return Timestamp(Date(dueMs - offsetMs))
    }

    /** Serializes this stage to a Firestore-compatible map. */
    fun toMap(): Map<String, Any?> = buildMap {
        put("type", type.name)
        put("offsetMs", offsetMs)
        put("enabled", enabled)
        if (absoluteTimeOfDay != null) {
            put("absoluteTimeHour", absoluteTimeOfDay.hour)
            put("absoluteTimeMinute", absoluteTimeOfDay.minute)
        }
    }

    companion object {
        /** Parses a list of stages from Firestore data, falling back to [Alarm.DEFAULT_STAGES]. */
        fun fromMapList(raw: Any?): List<AlarmStage> {
            val list = raw as? List<*> ?: return Alarm.DEFAULT_STAGES
            return list.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                try {
                    AlarmStage(
                        type = AlarmStageType.valueOf(map["type"] as? String ?: return@mapNotNull null),
                        offsetMs = (map["offsetMs"] as? Number)?.toLong() ?: 0,
                        enabled = map["enabled"] as? Boolean ?: true,
                        absoluteTimeOfDay = TimeOfDay.fromMap(map)
                    )
                } catch (e: Exception) { null }
            }.ifEmpty { Alarm.DEFAULT_STAGES }
        }
    }
}

enum class AlarmStageType {
    SOUND_ALARM,    // Audible alarm with snooze
    LOCK_SCREEN,    // Lock screen red tint / full-screen activity
    NOTIFICATION;   // Status bar notification

    /** Maps to the existing AlarmType used in PendingIntent extras. */
    fun toAlarmType(): AlarmType = when (this) {
        SOUND_ALARM -> AlarmType.ALARM
        LOCK_SCREEN -> AlarmType.URGENT
        NOTIFICATION -> AlarmType.NOTIFY
    }
}

/**
 * Represents an alarm/reminder for a note line.
 * Alarms have a due time and configurable stages that trigger at offsets before the due time.
 */
data class Alarm(
    val id: String = "",
    val userId: String = "",
    val noteId: String = "",
    val lineContent: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,

    val dueTime: Timestamp? = null,
    val stages: List<AlarmStage> = DEFAULT_STAGES,

    val status: AlarmStatus = AlarmStatus.PENDING,
    val snoozedUntil: Timestamp? = null,

    /** If this alarm was spawned by a recurring alarm template, its ID. */
    val recurringAlarmId: String? = null
) {
    /**
     * Display-friendly name with bullets, checkboxes, tabs, and alarm symbols removed.
     * Computed lazily and cached.
     */
    val displayName: String by lazy { AlarmMarkers.displayName(lineContent) }

    /** Enabled stages only. */
    val enabledStages: List<AlarmStage>
        get() = stages.filter { it.enabled }

    /**
     * The earliest trigger time across all enabled stages.
     */
    val earliestThresholdTime: Timestamp?
        get() {
            val due = dueTime ?: return null
            return enabledStages
                .map { it.resolveTime(due) }
                .minByOrNull { it.toDate().time }
        }

    /**
     * The latest trigger time across all enabled stages (or dueTime itself).
     */
    val latestThresholdTime: Timestamp?
        get() = dueTime

    companion object {
        val DEFAULT_STAGES = listOf(
            AlarmStage(AlarmStageType.SOUND_ALARM, offsetMs = 0, enabled = true),
            AlarmStage(AlarmStageType.LOCK_SCREEN, offsetMs = 30 * 60 * 1000L, enabled = false),
            AlarmStage(AlarmStageType.NOTIFICATION, offsetMs = 2 * 60 * 60 * 1000L, enabled = false)
        )
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
    UPCOMING,
    NOTIFY,
    URGENT,
    ALARM
}

/**
 * Type of alarm notification to show.
 */
enum class AlarmType {
    NOTIFY,     // Regular notification
    URGENT,     // Full-screen urgent notification
    ALARM       // Audible alarm with snooze options
}
