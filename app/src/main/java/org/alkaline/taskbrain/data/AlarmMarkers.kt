package org.alkaline.taskbrain.data

/**
 * Shared constants and pure text functions for alarm markers (symbols and directives).
 * Lives in the data layer so both data models and UI utilities can reference it
 * without a circular dependency.
 */
object AlarmMarkers {
    const val ALARM_SYMBOL = "⏰"

    /** Regex matching alarm directives like [alarm("abc123")] */
    val ALARM_DIRECTIVE_REGEX = Regex("""\[alarm\("([^"]+)"\)]""")

    /** Regex matching recurring alarm directives like [recurringAlarm("abc123")] */
    val RECURRING_ALARM_DIRECTIVE_REGEX = Regex("""\[recurringAlarm\("([^"]+)"\)]""")

    /** Creates an alarm directive string: [alarm("abc123")] */
    fun alarmDirective(alarmId: String): String = "[alarm(\"$alarmId\")]"

    /** Creates a recurring alarm directive string: [recurringAlarm("abc123")] */
    fun recurringAlarmDirective(recurringAlarmId: String): String = "[recurringAlarm(\"$recurringAlarmId\")]"

    /** Strips all alarm directives, recurring alarm directives, and plain alarm symbols from text. */
    fun stripAlarmMarkers(text: String): String {
        var result = ALARM_DIRECTIVE_REGEX.replace(text, "")
        result = RECURRING_ALARM_DIRECTIVE_REGEX.replace(result, "")
        result = result.replace(ALARM_SYMBOL, "")
        return result
    }

    /** A directive occurrence found in text. */
    data class DirectiveOccurrence(val startIndex: Int, val id: String, val isRecurring: Boolean)

    /**
     * Finds all alarm and recurring alarm directive occurrences in text, in source order.
     */
    fun findDirectiveOccurrences(text: String): List<DirectiveOccurrence> {
        val matches = mutableListOf<DirectiveOccurrence>()
        ALARM_DIRECTIVE_REGEX.findAll(text).forEach {
            matches.add(DirectiveOccurrence(it.range.first, it.groupValues[1], isRecurring = false))
        }
        RECURRING_ALARM_DIRECTIVE_REGEX.findAll(text).forEach {
            matches.add(DirectiveOccurrence(it.range.first, it.groupValues[1], isRecurring = true))
        }
        matches.sortBy { it.startIndex }
        return matches
    }

    private val DISPLAY_PREFIXES = listOf("• ", "☐ ", "☑ ")

    /**
     * Computes a display-friendly name from line content by stripping
     * tabs, bullet/checkbox prefixes, and alarm markers.
     */
    fun displayName(lineContent: String): String {
        var result = lineContent.trimStart('\t')
        DISPLAY_PREFIXES.forEach { prefix ->
            if (result.startsWith(prefix)) {
                result = result.removePrefix(prefix)
            }
        }
        result = result.trimStart()
        result = stripAlarmMarkers(result)
        return result.trim()
    }
}
