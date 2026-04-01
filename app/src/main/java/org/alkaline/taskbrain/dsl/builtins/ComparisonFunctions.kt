package org.alkaline.taskbrain.dsl.builtins

import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.BuiltinFunction
import org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Comparison and logical builtin functions.
 *
 * Supports comparing numbers, strings, dates, times, and datetimes.
 * Also provides logical operators for boolean operations.
 *
 * Part of view caching plan - method-style syntax support.
 */
object ComparisonFunctions {

    fun register(registry: BuiltinRegistry) {
        registry.register(ifFunction)
        registry.register(eqFunction)
        registry.register(neFunction)
        registry.register(gtFunction)
        registry.register(ltFunction)
        registry.register(gteFunction)
        registry.register(lteFunction)
        registry.register(andFunction)
        registry.register(orFunction)
        registry.register(notFunction)
    }

    /**
     * if(condition, thenValue, elseValue) - Returns thenValue if condition is true, else elseValue.
     *
     * Note: This is an eager `if` - both branches are evaluated before selecting one.
     * This is suitable for pure expressions but not for side-effectful code.
     *
     * Added for time-based refresh analysis.
     */
    private val ifFunction = BuiltinFunction(name = "if") { args, _ ->
        args.requireExactCount(3, "if")
        val condition = args.requireBoolean(0, "if", "condition")
        val thenValue = args.require(1, "then value")
        val elseValue = args.require(2, "else value")
        if (condition.value) thenValue else elseValue
    }

    /**
     * eq(a, b) - Returns true if a equals b.
     * Works with numbers, strings, booleans, dates, times, and datetimes.
     */
    private val eqFunction = BuiltinFunction(name = "eq") { args, _ ->
        args.requireExactCount(2, "eq")
        val a = args.require(0, "first argument")
        val b = args.require(1, "second argument")
        BooleanVal(compareValues(a, b) == 0)
    }

    /**
     * ne(a, b) - Returns true if a does not equal b.
     */
    private val neFunction = BuiltinFunction(name = "ne") { args, _ ->
        args.requireExactCount(2, "ne")
        val a = args.require(0, "first argument")
        val b = args.require(1, "second argument")
        BooleanVal(compareValues(a, b) != 0)
    }

    /**
     * gt(a, b) - Returns true if a is greater than b.
     */
    private val gtFunction = BuiltinFunction(name = "gt") { args, _ ->
        args.requireExactCount(2, "gt")
        val a = args.require(0, "first argument")
        val b = args.require(1, "second argument")
        BooleanVal(compareValues(a, b) > 0)
    }

    /**
     * lt(a, b) - Returns true if a is less than b.
     */
    private val ltFunction = BuiltinFunction(name = "lt") { args, _ ->
        args.requireExactCount(2, "lt")
        val a = args.require(0, "first argument")
        val b = args.require(1, "second argument")
        BooleanVal(compareValues(a, b) < 0)
    }

    /**
     * gte(a, b) - Returns true if a is greater than or equal to b.
     */
    private val gteFunction = BuiltinFunction(name = "gte") { args, _ ->
        args.requireExactCount(2, "gte")
        val a = args.require(0, "first argument")
        val b = args.require(1, "second argument")
        BooleanVal(compareValues(a, b) >= 0)
    }

    /**
     * lte(a, b) - Returns true if a is less than or equal to b.
     */
    private val lteFunction = BuiltinFunction(name = "lte") { args, _ ->
        args.requireExactCount(2, "lte")
        val a = args.require(0, "first argument")
        val b = args.require(1, "second argument")
        BooleanVal(compareValues(a, b) <= 0)
    }

    /**
     * and(a, b) - Returns true if both a and b are truthy.
     */
    private val andFunction = BuiltinFunction(name = "and") { args, _ ->
        args.requireExactCount(2, "and")
        val a = args.requireBoolean(0, "and", "first argument")
        val b = args.requireBoolean(1, "and", "second argument")
        BooleanVal(a.value && b.value)
    }

    /**
     * or(a, b) - Returns true if either a or b is truthy.
     */
    private val orFunction = BuiltinFunction(name = "or") { args, _ ->
        args.requireExactCount(2, "or")
        val a = args.requireBoolean(0, "and", "first argument")
        val b = args.requireBoolean(1, "and", "second argument")
        BooleanVal(a.value || b.value)
    }

    /**
     * not(a) - Returns the logical negation of a.
     */
    private val notFunction = BuiltinFunction(name = "not") { args, _ ->
        args.requireExactCount(1, "not")
        val a = args.requireBoolean(0, "not", "argument")
        BooleanVal(!a.value)
    }

    /**
     * Compare two DslValues and return:
     * - negative if a < b
     * - zero if a == b
     * - positive if a > b
     *
     * Handles mixed types by coercing strings to temporal types when appropriate.
     */
    fun compareValues(a: DslValue, b: DslValue): Int {
        // Handle string coercion to temporal types
        val (normA, normB) = normalizeForComparison(a, b)

        return when {
            normA is NumberVal && normB is NumberVal ->
                normA.value.compareTo(normB.value)

            normA is StringVal && normB is StringVal ->
                normA.value.compareTo(normB.value)

            normA is BooleanVal && normB is BooleanVal ->
                normA.value.compareTo(normB.value)

            normA is DateVal && normB is DateVal ->
                normA.value.compareTo(normB.value)

            normA is TimeVal && normB is TimeVal ->
                normA.value.compareTo(normB.value)

            normA is DateTimeVal && normB is DateTimeVal ->
                normA.value.compareTo(normB.value)

            else -> throw ExecutionException(
                "Cannot compare ${a.typeName} with ${b.typeName}"
            )
        }
    }

    /**
     * Normalize values for comparison, coercing strings to temporal types when needed.
     * For example, comparing a DateVal with "2026-01-15" will parse the string as a date.
     */
    private fun normalizeForComparison(a: DslValue, b: DslValue): Pair<DslValue, DslValue> {
        return when {
            // Date comparison with string
            a is DateVal && b is StringVal -> a to parseAsDate(b.value)
            a is StringVal && b is DateVal -> parseAsDate(a.value) to b

            // Time comparison with string
            a is TimeVal && b is StringVal -> a to parseAsTime(b.value)
            a is StringVal && b is TimeVal -> parseAsTime(a.value) to b

            // DateTime comparison with string
            a is DateTimeVal && b is StringVal -> a to parseAsDateTime(b.value)
            a is StringVal && b is DateTimeVal -> parseAsDateTime(a.value) to b

            // No normalization needed
            else -> a to b
        }
    }

    private fun parseAsDate(str: String): DateVal {
        return try {
            DateVal(LocalDate.parse(str))
        } catch (e: Exception) {
            throw ExecutionException("Cannot parse '$str' as a date")
        }
    }

    private fun parseAsTime(str: String): TimeVal {
        return try {
            TimeVal(LocalTime.parse(str))
        } catch (e: Exception) {
            throw ExecutionException("Cannot parse '$str' as a time")
        }
    }

    private fun parseAsDateTime(str: String): DateTimeVal {
        return try {
            DateTimeVal(LocalDateTime.parse(str))
        } catch (e: Exception) {
            throw ExecutionException("Cannot parse '$str' as a datetime")
        }
    }
}
