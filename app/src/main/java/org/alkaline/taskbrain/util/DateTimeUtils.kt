package org.alkaline.taskbrain.util

import android.content.Context
import android.text.format.DateFormat
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One minute in milliseconds. */
const val MINUTE_MS: Long = 60 * 1000L

/** One hour in milliseconds. */
const val HOUR_MS: Long = 60 * MINUTE_MS

/**
 * Formats [date] as a time-of-day string using the device's preferred 12h/24h format.
 */
fun formatTimeOfDay(context: Context, date: Date): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

/**
 * Formats an (hour, minute) pair as a time-of-day string using the device's preferred
 * 12h/24h format. Convenience wrapper over [formatTimeOfDay] with a [Date].
 */
fun formatTimeOfDay(context: Context, hour: Int, minute: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return formatTimeOfDay(context, cal.time)
}

/**
 * Utility functions for date/time operations.
 */
object DateTimeUtils {

    /**
     * Combines a date (from Material3 DatePicker, which returns UTC millis at start of day)
     * with a time (hour and minute in local timezone).
     *
     * Material3 DatePicker returns the selected date as UTC milliseconds at midnight.
     * This function correctly extracts the year/month/day in UTC and combines them
     * with the specified hour/minute in local timezone.
     *
     * @param datePickerMillis The millis value from Material3 DatePicker (UTC at start of day)
     * @param hour Hour of day (0-23) in local timezone
     * @param minute Minute (0-59)
     * @return Timestamp representing the combined date and time in local timezone
     */
    fun combineDatePickerWithTime(datePickerMillis: Long, hour: Int, minute: Int): Timestamp {
        // Material3 DatePicker returns UTC millis at start of day.
        // We need to extract year/month/day in UTC, then combine with
        // the selected time in local timezone.
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcCalendar.timeInMillis = datePickerMillis

        val year = utcCalendar.get(Calendar.YEAR)
        val month = utcCalendar.get(Calendar.MONTH)
        val day = utcCalendar.get(Calendar.DAY_OF_MONTH)

        // Now create local calendar with the extracted date and selected time
        val localCalendar = Calendar.getInstance()
        localCalendar.set(Calendar.YEAR, year)
        localCalendar.set(Calendar.MONTH, month)
        localCalendar.set(Calendar.DAY_OF_MONTH, day)
        localCalendar.set(Calendar.HOUR_OF_DAY, hour)
        localCalendar.set(Calendar.MINUTE, minute)
        localCalendar.set(Calendar.SECOND, 0)
        localCalendar.set(Calendar.MILLISECOND, 0)

        return Timestamp(localCalendar.time)
    }

    /**
     * Gets the UTC millis at start of day for a given local date.
     * This is the inverse operation - useful for initializing DatePicker with an existing value.
     *
     * @param timestamp The timestamp to extract the date from
     * @return UTC millis at start of day for the date represented by the timestamp
     */
    fun getDatePickerMillisFromTimestamp(timestamp: Timestamp): Long {
        val localCalendar = Calendar.getInstance()
        localCalendar.time = timestamp.toDate()

        val year = localCalendar.get(Calendar.YEAR)
        val month = localCalendar.get(Calendar.MONTH)
        val day = localCalendar.get(Calendar.DAY_OF_MONTH)

        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcCalendar.set(Calendar.YEAR, year)
        utcCalendar.set(Calendar.MONTH, month)
        utcCalendar.set(Calendar.DAY_OF_MONTH, day)
        utcCalendar.set(Calendar.HOUR_OF_DAY, 0)
        utcCalendar.set(Calendar.MINUTE, 0)
        utcCalendar.set(Calendar.SECOND, 0)
        utcCalendar.set(Calendar.MILLISECOND, 0)

        return utcCalendar.timeInMillis
    }

    /**
     * Creates a Timestamp for a specific date and time in local timezone.
     * Useful for testing.
     *
     * @param year Year (e.g., 2026)
     * @param month Month (1-12, NOT 0-11)
     * @param day Day of month (1-31)
     * @param hour Hour of day (0-23)
     * @param minute Minute (0-59)
     * @return Timestamp representing the specified date/time in local timezone
     */
    fun createLocalTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Timestamp {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month - 1) // Calendar uses 0-based months
        calendar.set(Calendar.DAY_OF_MONTH, day)
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return Timestamp(calendar.time)
    }

    /**
     * Creates a UTC millis value at start of day for a specific date.
     * This is what Material3 DatePicker would return for the given date.
     *
     * @param year Year (e.g., 2026)
     * @param month Month (1-12, NOT 0-11)
     * @param day Day of month (1-31)
     * @return UTC millis at start of day (midnight UTC) for the specified date
     */
    fun createDatePickerMillis(year: Int, month: Int, day: Int): Long {
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcCalendar.set(Calendar.YEAR, year)
        utcCalendar.set(Calendar.MONTH, month - 1) // Calendar uses 0-based months
        utcCalendar.set(Calendar.DAY_OF_MONTH, day)
        utcCalendar.set(Calendar.HOUR_OF_DAY, 0)
        utcCalendar.set(Calendar.MINUTE, 0)
        utcCalendar.set(Calendar.SECOND, 0)
        utcCalendar.set(Calendar.MILLISECOND, 0)
        return utcCalendar.timeInMillis
    }
}
