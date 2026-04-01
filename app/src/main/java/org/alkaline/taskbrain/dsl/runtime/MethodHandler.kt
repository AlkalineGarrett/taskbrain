package org.alkaline.taskbrain.dsl.runtime

import org.alkaline.taskbrain.dsl.builtins.ComparisonFunctions
import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Handles method calls on all DslValue types.
 *
 * Supports:
 * - Comparison methods (.eq, .ne, .gt, .lt, .gte, .lte) on any comparable value
 * - Logical methods (.and, .or) on BooleanVal
 * - String methods (.startsWith, .endsWith, .contains) on StringVal
 * - Temporal .plus() methods on DateVal, TimeVal, DateTimeVal
 *
 * Part of view caching plan - method-style syntax support.
 */
object MethodHandler {

    /**
     * Call a method on a DslValue.
     *
     * @param target The value to call the method on
     * @param methodName The name of the method
     * @param args The method arguments
     * @param position Source position for error messages
     * @return The result of the method call
     * @throws ExecutionException if the method is not supported
     */
    fun callMethod(
        target: DslValue,
        methodName: String,
        args: Arguments,
        position: Int
    ): DslValue {
        // Try comparison methods (available on all comparable types)
        val comparisonResult = tryComparisonMethod(target, methodName, args)
        if (comparisonResult != null) return comparisonResult

        // Try type-specific methods
        return when (target) {
            is BooleanVal -> callBooleanMethod(target, methodName, args, position)
            is StringVal -> callStringMethod(target, methodName, args, position)
            is DateVal -> callDateMethod(target, methodName, args, position)
            is TimeVal -> callTimeMethod(target, methodName, args, position)
            is DateTimeVal -> callDateTimeMethod(target, methodName, args, position)
            is NumberVal -> callNumberMethod(target, methodName, args, position)
            else -> throw ExecutionException(
                "Cannot call method '$methodName' on ${target.typeName}",
                position
            )
        }
    }

    /**
     * Try comparison methods (.eq, .ne, .gt, .lt, .gte, .lte).
     * Returns null if methodName is not a comparison method.
     */
    private fun tryComparisonMethod(
        target: DslValue,
        methodName: String,
        args: Arguments
    ): DslValue? {
        if (args.size != 1) return null

        val other = args[0] ?: return null

        return when (methodName) {
            "eq" -> BooleanVal(ComparisonFunctions.compareValues(target, other) == 0)
            "ne" -> BooleanVal(ComparisonFunctions.compareValues(target, other) != 0)
            "gt" -> BooleanVal(ComparisonFunctions.compareValues(target, other) > 0)
            "lt" -> BooleanVal(ComparisonFunctions.compareValues(target, other) < 0)
            "gte" -> BooleanVal(ComparisonFunctions.compareValues(target, other) >= 0)
            "lte" -> BooleanVal(ComparisonFunctions.compareValues(target, other) <= 0)
            else -> null
        }
    }

    /**
     * Handle methods on BooleanVal (.and, .or).
     */
    private fun callBooleanMethod(
        target: BooleanVal,
        methodName: String,
        args: Arguments,
        position: Int
    ): DslValue {
        return when (methodName) {
            "and" -> {
                args.requireExactCount(1, "and")
                val other = args.requireBoolean(0, "and", "argument")
                BooleanVal(target.value && other.value)
            }
            "or" -> {
                args.requireExactCount(1, "or")
                val other = args.requireBoolean(0, "or", "argument")
                BooleanVal(target.value || other.value)
            }
            else -> throw ExecutionException(
                "Unknown method '$methodName' on boolean",
                position
            )
        }
    }

    /**
     * Handle methods on StringVal (.startsWith, .endsWith, .contains).
     */
    private fun callStringMethod(
        target: StringVal,
        methodName: String,
        args: Arguments,
        position: Int
    ): DslValue {
        return when (methodName) {
            "startsWith" -> {
                args.requireExactCount(1, "startsWith")
                val prefix = args.requireString(0, "startsWith", "prefix")
                BooleanVal(target.value.startsWith(prefix.value))
            }
            "endsWith" -> {
                args.requireExactCount(1, "endsWith")
                val suffix = args.requireString(0, "endsWith", "suffix")
                BooleanVal(target.value.endsWith(suffix.value))
            }
            "contains" -> {
                args.requireExactCount(1, "contains")
                val substring = args.requireString(0, "contains", "substring")
                BooleanVal(target.value.contains(substring.value))
            }
            else -> throw ExecutionException(
                "Unknown method '$methodName' on string",
                position
            )
        }
    }

    /**
     * Handle methods on DateVal (.plus).
     * Supports: .plus(days: n)
     */
    private fun callDateMethod(
        target: DateVal,
        methodName: String,
        args: Arguments,
        position: Int
    ): DslValue {
        return when (methodName) {
            "plus" -> {
                val days = args["days"]
                if (days == null) {
                    throw ExecutionException(
                        "date.plus() requires 'days' parameter",
                        position
                    )
                }
                val daysNum = (days as? NumberVal)?.value?.toLong()
                    ?: throw ExecutionException(
                        "date.plus() 'days' must be a number",
                        position
                    )
                DateVal(target.value.plusDays(daysNum))
            }
            else -> throw ExecutionException(
                "Unknown method '$methodName' on date",
                position
            )
        }
    }

    /**
     * Handle methods on TimeVal (.plus).
     * Supports: .plus(hours: n), .plus(minutes: n)
     */
    private fun callTimeMethod(
        target: TimeVal,
        methodName: String,
        args: Arguments,
        position: Int
    ): DslValue {
        return when (methodName) {
            "plus" -> {
                var result = target.value

                val hours = args["hours"]
                if (hours != null) {
                    val hoursNum = (hours as? NumberVal)?.value?.toLong()
                        ?: throw ExecutionException(
                            "time.plus() 'hours' must be a number",
                            position
                        )
                    result = result.plusHours(hoursNum)
                }

                val minutes = args["minutes"]
                if (minutes != null) {
                    val minutesNum = (minutes as? NumberVal)?.value?.toLong()
                        ?: throw ExecutionException(
                            "time.plus() 'minutes' must be a number",
                            position
                        )
                    result = result.plusMinutes(minutesNum)
                }

                if (hours == null && minutes == null) {
                    throw ExecutionException(
                        "time.plus() requires at least one of: hours, minutes",
                        position
                    )
                }

                TimeVal(result)
            }
            else -> throw ExecutionException(
                "Unknown method '$methodName' on time",
                position
            )
        }
    }

    /**
     * Handle methods on DateTimeVal (.plus).
     * Supports: .plus(days: n, hours: m, minutes: p)
     */
    private fun callDateTimeMethod(
        target: DateTimeVal,
        methodName: String,
        args: Arguments,
        position: Int
    ): DslValue {
        return when (methodName) {
            "plus" -> {
                var result = target.value

                val days = args["days"]
                if (days != null) {
                    val daysNum = (days as? NumberVal)?.value?.toLong()
                        ?: throw ExecutionException(
                            "datetime.plus() 'days' must be a number",
                            position
                        )
                    result = result.plusDays(daysNum)
                }

                val hours = args["hours"]
                if (hours != null) {
                    val hoursNum = (hours as? NumberVal)?.value?.toLong()
                        ?: throw ExecutionException(
                            "datetime.plus() 'hours' must be a number",
                            position
                        )
                    result = result.plusHours(hoursNum)
                }

                val minutes = args["minutes"]
                if (minutes != null) {
                    val minutesNum = (minutes as? NumberVal)?.value?.toLong()
                        ?: throw ExecutionException(
                            "datetime.plus() 'minutes' must be a number",
                            position
                        )
                    result = result.plusMinutes(minutesNum)
                }

                if (days == null && hours == null && minutes == null) {
                    throw ExecutionException(
                        "datetime.plus() requires at least one of: days, hours, minutes",
                        position
                    )
                }

                DateTimeVal(result)
            }
            else -> throw ExecutionException(
                "Unknown method '$methodName' on datetime",
                position
            )
        }
    }

    /**
     * Handle methods on NumberVal.
     * Currently no specific number methods, but comparison methods work via tryComparisonMethod.
     */
    private fun callNumberMethod(
        target: NumberVal,
        methodName: String,
        args: Arguments,
        position: Int
    ): DslValue {
        throw ExecutionException(
            "Unknown method '$methodName' on number",
            position
        )
    }
}
