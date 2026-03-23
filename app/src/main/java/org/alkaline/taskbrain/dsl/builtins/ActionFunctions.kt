package org.alkaline.taskbrain.dsl.builtins

import org.alkaline.taskbrain.dsl.language.StringLiteral
import org.alkaline.taskbrain.dsl.runtime.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.BuiltinFunction
import org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry
import org.alkaline.taskbrain.dsl.runtime.ButtonVal
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.LambdaVal
import org.alkaline.taskbrain.dsl.runtime.ScheduleFrequency
import org.alkaline.taskbrain.dsl.runtime.ScheduleVal
import org.alkaline.taskbrain.dsl.runtime.AlarmVal
import org.alkaline.taskbrain.dsl.runtime.StringVal

/**
 * Action-related builtin functions: button, schedule, and alarm.
 *
 * Phase 0f: Part of view caching plan - button and schedule support.
 * Alarm identity: alarm() function for stable alarm references.
 */
object ActionFunctions {

    fun register(registry: BuiltinRegistry) {
        registry.register(buttonFunction)
        registry.register(scheduleFunction)
        registry.register(alarmFunction)
        registry.register(recurringAlarmFunction)

        // Register schedule frequency constants
        ScheduleConstants.register(registry)
    }

    /**
     * button(label, action) - Creates a button that executes action when clicked.
     *
     * Usage:
     * - button("Create Note", [new(path: "inbox")])
     * - button("Done", [.append(" done")])
     *
     * @param label String label for the button
     * @param action Lambda/deferred block to execute on click
     */
    private val buttonFunction = BuiltinFunction(name = "button") { args, _ ->
        val label = args.requireString(0, "button", "label")
        val action = args.requireLambda(1, "button", "action")
        ButtonVal(label.value, action)
    }

    /**
     * alarm(id) - Creates an alarm reference that renders as ⏰ in the editor.
     *
     * Each alarm directive encodes the Firestore alarm document ID, providing
     * stable identity that survives text editing and eliminates positional
     * mapping bugs.
     *
     * Usage: [alarm("f3GxY2abc")]
     *
     * @param id The Firestore alarm document ID
     */
    private val alarmFunction = BuiltinFunction(name = "alarm") { args, _ ->
        val id = args.requireString(0, "alarm", "id")
        AlarmVal(id.value)
    }

    /**
     * recurringAlarm(id) - Creates a recurring alarm reference that renders as ⏰ in the editor.
     *
     * Like alarm(), but encodes the recurring alarm template ID instead of a specific
     * instance ID. At display time, resolves to the current instance's status.
     *
     * Usage: [recurringAlarm("recId123")]
     *
     * @param id The Firestore recurring alarm document ID
     */
    private val recurringAlarmFunction = BuiltinFunction(name = "recurringAlarm") { args, _ ->
        val id = args.requireString(0, "recurringAlarm", "id")
        AlarmVal(id.value)
    }

    /**
     * schedule(frequency, action) - Creates a scheduled action that runs at specified intervals.
     */
    private val scheduleFunction = BuiltinFunction(name = "schedule") { args, _ ->
        val frequencyArg = args.require(0, "frequency")
        val action = args.requireLambda(1, "schedule", "action")

        val (frequency, atTime, precise) = when (frequencyArg) {
            is ScheduleVal -> Triple(frequencyArg.frequency, frequencyArg.atTime, frequencyArg.precise)
            is StringVal -> {
                val freq = ScheduleFrequency.fromIdentifier(frequencyArg.value)
                    ?: throw ExecutionException(
                        "Unknown schedule frequency '${frequencyArg.value}'. " +
                            "Valid options: ${ScheduleFrequency.entries.joinToString { it.identifier }}, " +
                            "or use daily_at(\"HH:mm\"), weekly_at(\"HH:mm\")"
                    )
                Triple(freq, null, false)
            }
            else -> throw ExecutionException(
                "schedule() frequency must be a schedule identifier (daily, hourly, weekly) " +
                    "or time-specific (daily_at, weekly_at), got ${frequencyArg.typeName}"
            )
        }

        ScheduleVal(frequency, action, atTime, precise)
    }
}

/**
 * Schedule frequency constants and time-specific functions.
 *
 * Simple frequencies: daily, hourly, weekly (zero-arg, return identifier)
 * Time-specific: daily_at("HH:mm"), weekly_at("HH:mm") (return ScheduleVal with time)
 */
object ScheduleConstants {

    fun register(registry: BuiltinRegistry) {
        // Simple frequency constants
        registry.register(dailyConstant)
        registry.register(hourlyConstant)
        registry.register(weeklyConstant)

        // Time-specific frequency functions
        registry.register(dailyAtFunction)
        registry.register(weeklyAtFunction)
    }

    private val dailyConstant = BuiltinFunction(name = "daily") { args, _ ->
        args.requireNoArgs("daily")
        StringVal(ScheduleFrequency.DAILY.identifier)
    }

    private val hourlyConstant = BuiltinFunction(name = "hourly") { args, _ ->
        args.requireNoArgs("hourly")
        StringVal(ScheduleFrequency.HOURLY.identifier)
    }

    private val weeklyConstant = BuiltinFunction(name = "weekly") { args, _ ->
        args.requireNoArgs("weekly")
        StringVal(ScheduleFrequency.WEEKLY.identifier)
    }

    /**
     * daily_at("HH:mm", precise: true/false) - Daily schedule at a specific time.
     *
     * Usage:
     * - schedule(daily_at("09:00"), [action])           - approximate timing
     * - schedule(daily_at("09:00", precise: true), [action]) - exact timing via AlarmManager
     *
     * @param time Time in HH:mm format (24-hour)
     * @param precise (named, optional) Whether to use exact timing (default: false)
     */
    private val dailyAtFunction = BuiltinFunction(name = "daily_at") { args, _ ->
        val timeStr = args.requireString(0, "daily_at", "time")
        validateTimeFormat(timeStr.value, "daily_at")

        // Check for optional 'precise' named parameter
        val precise = (args["precise"] as? BooleanVal)?.value ?: false

        // Return a ScheduleVal with frequency, time, and precision (placeholder lambda)
        val placeholderLambda = createPlaceholderLambda()
        ScheduleVal(ScheduleFrequency.DAILY, placeholderLambda, timeStr.value, precise)
    }

    /**
     * weekly_at("HH:mm", precise: true/false) - Weekly schedule at a specific time.
     *
     * Usage:
     * - schedule(weekly_at("09:00"), [action])           - approximate timing
     * - schedule(weekly_at("09:00", precise: true), [action]) - exact timing via AlarmManager
     *
     * Note: Currently schedules for same day of week. Future enhancement
     * could add day parameter: weekly_at("Monday", "09:00")
     *
     * @param time Time in HH:mm format (24-hour)
     * @param precise (named, optional) Whether to use exact timing (default: false)
     */
    private val weeklyAtFunction = BuiltinFunction(name = "weekly_at") { args, _ ->
        val timeStr = args.requireString(0, "weekly_at", "time")
        validateTimeFormat(timeStr.value, "weekly_at")

        // Check for optional 'precise' named parameter
        val precise = (args["precise"] as? BooleanVal)?.value ?: false

        val placeholderLambda = createPlaceholderLambda()
        ScheduleVal(ScheduleFrequency.WEEKLY, placeholderLambda, timeStr.value, precise)
    }

    /**
     * Validate time format is HH:mm (24-hour).
     */
    private fun validateTimeFormat(time: String, funcName: String) {
        val regex = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")
        if (!regex.matches(time)) {
            throw ExecutionException(
                "$funcName() time must be in HH:mm format (24-hour), got \"$time\". " +
                    "Examples: \"09:00\", \"14:30\", \"00:00\""
            )
        }
    }

    /**
     * Create a placeholder lambda for ScheduleVal returned by frequency functions.
     * The actual action lambda is provided to schedule() separately.
     */
    private fun createPlaceholderLambda(): LambdaVal {
        return LambdaVal(
            params = emptyList(),
            body = StringLiteral("placeholder", 0),
            capturedEnv = Environment()
        )
    }
}
