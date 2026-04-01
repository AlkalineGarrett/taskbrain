package org.alkaline.taskbrain.dsl.builtins

import org.alkaline.taskbrain.dsl.runtime.BuiltinFunction
import org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Date and time builtin functions.
 *
 * date, datetime, time
 */
object DateFunctions {

    fun register(registry: BuiltinRegistry) {
        registry.register(dateFunction)
        registry.register(datetimeFunction)
        registry.register(timeFunction)
        registry.register(parseDateFunction)
    }

    /**
     * date - Returns the current date (no time component).
     * Dynamic: returns a different value each day.
     * Example: [date] -> 2026-01-25
     *
     * Uses mocked time if available for trigger verification.
     */
    private val dateFunction = BuiltinFunction(
        name = "date",
        isDynamic = true
    ) { args, env ->
        args.requireNoArgs("date")
        val mockedTime = env.getMockedTime()
        DateVal(mockedTime?.toLocalDate() ?: LocalDate.now())
    }

    /**
     * datetime - Returns the current date and time.
     * Dynamic: returns a different value each call.
     * Example: [datetime] -> 2026-01-25, 14:30:00
     *
     * Uses mocked time if available for trigger verification.
     */
    private val datetimeFunction = BuiltinFunction(
        name = "datetime",
        isDynamic = true
    ) { args, env ->
        args.requireNoArgs("datetime")
        val mockedTime = env.getMockedTime()
        DateTimeVal(mockedTime ?: LocalDateTime.now())
    }

    /**
     * time - Returns the current time (no date component).
     * Dynamic: returns a different value each call.
     * Example: [time] -> 14:30:00
     *
     * Uses mocked time if available for trigger verification.
     */
    private val timeFunction = BuiltinFunction(
        name = "time",
        isDynamic = true
    ) { args, env ->
        args.requireNoArgs("time")
        val mockedTime = env.getMockedTime()
        TimeVal(mockedTime?.toLocalTime() ?: LocalTime.now())
    }

    /**
     * parse_date - Parses a string into a date value.
     * Not dynamic: pure function, same input always produces same output.
     * Example: [parse_date "2026-01-15"] -> DateVal(2026-01-15)
     */
    private val parseDateFunction = BuiltinFunction(
        name = "parse_date",
        isDynamic = false
    ) { args, _ ->
        args.requireExactCount(1, "parse_date")
        val str = args[0] as? StringVal
            ?: throw ExecutionException("'parse_date' argument must be a string, got ${args[0]?.typeName}")
        try {
            DateVal(LocalDate.parse(str.value))
        } catch (e: DateTimeParseException) {
            throw ExecutionException("'parse_date' failed to parse date: '${str.value}'")
        }
    }
}
