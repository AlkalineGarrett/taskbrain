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

    /** Creates an alarm directive string: [alarm("abc123")] */
    fun alarmDirective(alarmId: String): String = "[alarm(\"$alarmId\")]"

    /** Strips all alarm directives and plain alarm symbols from text. */
    fun stripAlarmMarkers(text: String): String {
        var result = ALARM_DIRECTIVE_REGEX.replace(text, "")
        result = result.replace(ALARM_SYMBOL, "")
        return result
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
