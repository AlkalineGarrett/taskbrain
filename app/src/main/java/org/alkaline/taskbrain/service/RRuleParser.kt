package org.alkaline.taskbrain.service

import java.util.Calendar
import java.util.Date

/**
 * Lightweight RRULE parser supporting the subset needed for alarm recurrence:
 * - FREQ: DAILY, WEEKLY, MONTHLY
 * - INTERVAL: repeat every N periods (default 1)
 * - BYDAY: day-of-week list (MO, TU, WE, TH, FR, SA, SU)
 *
 * Designed to be extended to full RRULE/cron support in the future.
 */
object RRuleParser {

    private val DAY_MAP = mapOf(
        "MO" to Calendar.MONDAY,
        "TU" to Calendar.TUESDAY,
        "WE" to Calendar.WEDNESDAY,
        "TH" to Calendar.THURSDAY,
        "FR" to Calendar.FRIDAY,
        "SA" to Calendar.SATURDAY,
        "SU" to Calendar.SUNDAY
    )

    data class ParsedRule(
        val freq: Frequency,
        val interval: Int = 1,
        val byDay: List<Int>? = null
    )

    enum class Frequency { DAILY, WEEKLY, MONTHLY }

    /**
     * Parses an RRULE string into a [ParsedRule].
     * Example: "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR"
     */
    fun parse(rrule: String): ParsedRule {
        val parts = rrule.split(";").associate { part ->
            val (key, value) = part.split("=", limit = 2)
            key.uppercase() to value
        }

        val freq = Frequency.valueOf(parts["FREQ"] ?: error("RRULE missing FREQ"))
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val byDay = parts["BYDAY"]?.split(",")?.mapNotNull { DAY_MAP[it.uppercase()] }

        return ParsedRule(freq = freq, interval = interval, byDay = byDay)
    }

    /**
     * Builds an RRULE string from components.
     */
    fun build(freq: Frequency, interval: Int = 1, byDay: List<Int>? = null): String {
        val parts = mutableListOf("FREQ=${freq.name}")
        if (interval > 1) parts.add("INTERVAL=$interval")
        if (!byDay.isNullOrEmpty()) {
            val dayNames = byDay.map { calDay ->
                DAY_MAP.entries.first { (_, v) -> v == calDay }.key
            }
            parts.add("BYDAY=${dayNames.joinToString(",")}")
        }
        return parts.joinToString(";")
    }

    /**
     * Computes the next occurrence after [afterDate] based on the RRULE.
     * The time-of-day is preserved from [afterDate].
     *
     * @param rrule The RRULE string
     * @param afterDate The reference date to compute the next occurrence after
     * @return The next occurrence date, or null if no valid next date can be computed
     */
    fun nextOccurrence(rrule: String, afterDate: Date): Date? {
        val rule = parse(rrule)
        val cal = Calendar.getInstance().apply { time = afterDate }

        return when (rule.freq) {
            Frequency.DAILY -> nextDaily(cal, rule.interval)
            Frequency.WEEKLY -> nextWeekly(cal, rule.interval, rule.byDay)
            Frequency.MONTHLY -> nextMonthly(cal, rule.interval)
        }
    }

    private fun nextDaily(cal: Calendar, interval: Int): Date {
        cal.add(Calendar.DAY_OF_YEAR, interval)
        return cal.time
    }

    private fun nextWeekly(cal: Calendar, interval: Int, byDay: List<Int>?): Date {
        if (byDay.isNullOrEmpty()) {
            cal.add(Calendar.WEEK_OF_YEAR, interval)
            return cal.time
        }

        // Find the next matching day in the current or subsequent weeks
        val startDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val sortedDays = byDay.sorted()

        // Look for the next day in the current week (after today)
        val nextDayThisWeek = sortedDays.firstOrNull { it > startDayOfWeek }
        if (nextDayThisWeek != null && interval == 1) {
            cal.set(Calendar.DAY_OF_WEEK, nextDayThisWeek)
            return cal.time
        }

        // Move to the first matching day of the next interval week
        val weeksToAdd = if (nextDayThisWeek != null && interval > 1) interval else {
            // If we're past all days this week, go to next interval
            if (interval == 1) 1 else interval
        }

        // Go to the start of the target week
        cal.add(Calendar.WEEK_OF_YEAR, weeksToAdd)
        cal.set(Calendar.DAY_OF_WEEK, sortedDays.first())
        return cal.time
    }

    private fun nextMonthly(cal: Calendar, interval: Int): Date {
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        cal.add(Calendar.MONTH, interval)
        // Clamp to last day of month if original day doesn't exist
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, minOf(dayOfMonth, maxDay))
        return cal.time
    }
}
