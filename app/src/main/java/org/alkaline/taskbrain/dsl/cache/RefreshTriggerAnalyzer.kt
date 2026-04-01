package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.dsl.language.Assignment
import org.alkaline.taskbrain.dsl.language.CallExpr
import org.alkaline.taskbrain.dsl.language.CurrentNoteRef
import org.alkaline.taskbrain.dsl.language.Expression
import org.alkaline.taskbrain.dsl.language.LambdaExpr
import org.alkaline.taskbrain.dsl.language.LambdaInvocation
import org.alkaline.taskbrain.dsl.language.MethodCall
import org.alkaline.taskbrain.dsl.language.NumberLiteral
import org.alkaline.taskbrain.dsl.language.OnceExpr
import org.alkaline.taskbrain.dsl.language.PatternExpr
import org.alkaline.taskbrain.dsl.language.PropertyAccess
import org.alkaline.taskbrain.dsl.language.RefreshExpr
import org.alkaline.taskbrain.dsl.language.StatementList
import org.alkaline.taskbrain.dsl.language.StringLiteral
import org.alkaline.taskbrain.dsl.language.VariableRef
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.Executor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Analyzes refresh expressions to detect time-based triggers.
 *
 * Time-based refresh analysis.
 *
 * The algorithm:
 * 1. Walk AST to find time comparisons (.gt, .lt, etc. with string literals)
 * 2. Backtrace from the comparison to find the temporal input (time, date, datetime)
 * 3. Reverse any .plus() operations to compute candidate trigger times
 * 4. Verify candidates by evaluating the expression at ±1 minute
 *
 * Example:
 * ```
 * refresh[if(time.plus(minutes:10).gt("12:00"), X, Y)]
 * ```
 * - Find literal: "12:00"
 * - Backtrace path: time → .plus(minutes:10) → .gt("12:00")
 * - Reverse math: 12:00 - 10min = 11:50
 * - Verify: at 11:49 false, at 11:51 true → flip confirmed at 11:50
 */
object RefreshTriggerAnalyzer {

    /**
     * Analyze a refresh expression to find trigger times.
     *
     * @param expr The RefreshExpr to analyze
     * @param env Environment for variable resolution and verification
     * @return RefreshAnalysis containing the detected triggers
     */
    fun analyze(expr: RefreshExpr, env: Environment = Environment()): RefreshAnalysis {
        val comparisons = mutableListOf<TimeComparison>()
        val variables = mutableMapOf<String, Expression>()

        // First pass: collect variable definitions for constant propagation
        collectVariables(expr.body, variables)

        // Second pass: find all time comparisons
        findTimeComparisons(expr.body, comparisons, variables)

        if (comparisons.isEmpty()) {
            return RefreshAnalysis.error(
                "refresh[...] requires time comparisons to determine triggers. " +
                "Use once[...] for one-time evaluation instead."
            )
        }

        // Convert comparisons to candidate triggers
        val candidates = comparisons.mapNotNull { comparison ->
            try {
                createCandidateTrigger(comparison)
            } catch (e: Exception) {
                null // Skip invalid candidates
            }
        }

        // Verify candidates by evaluating at ±1 minute
        val verifiedTriggers = candidates.filter { candidate ->
            verifyTrigger(candidate, expr.body, env)
        }

        return RefreshAnalysis.success(verifiedTriggers)
    }

    /**
     * Collect variable definitions from the AST for constant propagation.
     */
    private fun collectVariables(expr: Expression, variables: MutableMap<String, Expression>) {
        when (expr) {
            is StatementList -> {
                for (stmt in expr.statements) {
                    collectVariables(stmt, variables)
                }
            }
            is Assignment -> {
                if (expr.target is VariableRef) {
                    variables[(expr.target as VariableRef).name] = expr.value
                }
            }
            else -> { /* Ignore other expressions */ }
        }
    }

    /**
     * Find all time comparisons in the AST.
     */
    private fun findTimeComparisons(
        expr: Expression,
        comparisons: MutableList<TimeComparison>,
        variables: Map<String, Expression>
    ) {
        when (expr) {
            is MethodCall -> {
                // Check if this is a comparison method
                val operator = parseComparisonOperator(expr.methodName)
                if (operator != null && expr.args.size == 1) {
                    // Try to extract the comparison
                    val comparison = extractTimeComparison(expr.target, expr.args[0], operator, variables)
                    if (comparison != null) {
                        comparisons.add(comparison)
                    }
                }

                // Also search in target and args
                findTimeComparisons(expr.target, comparisons, variables)
                for (arg in expr.args) {
                    findTimeComparisons(arg, comparisons, variables)
                }
            }

            is CallExpr -> {
                // Check if this is a comparison function (gt, lt, etc.)
                val operator = parseComparisonOperator(expr.name)
                if (operator != null && expr.args.size >= 2) {
                    val comparison = extractTimeComparison(expr.args[0], expr.args[1], operator, variables)
                    if (comparison != null) {
                        comparisons.add(comparison)
                    }
                }

                // Search in args
                for (arg in expr.args) {
                    findTimeComparisons(arg, comparisons, variables)
                }
            }

            is StatementList -> {
                for (stmt in expr.statements) {
                    findTimeComparisons(stmt, comparisons, variables)
                }
            }

            is Assignment -> {
                findTimeComparisons(expr.value, comparisons, variables)
            }

            is LambdaExpr -> {
                findTimeComparisons(expr.body, comparisons, variables)
            }

            is LambdaInvocation -> {
                findTimeComparisons(expr.lambda.body, comparisons, variables)
                for (arg in expr.args) {
                    findTimeComparisons(arg, comparisons, variables)
                }
            }

            is PropertyAccess -> {
                findTimeComparisons(expr.target, comparisons, variables)
            }

            is OnceExpr -> {
                // Don't analyze inside once[...] - it's cached
            }

            is RefreshExpr -> {
                // Don't recurse into nested refresh
            }

            is NumberLiteral, is StringLiteral, is CurrentNoteRef,
            is VariableRef, is PatternExpr -> {
                // Terminal nodes
            }
        }
    }

    /**
     * Parse a method/function name as a comparison operator.
     */
    private fun parseComparisonOperator(name: String): ComparisonOperator? {
        return when (name) {
            "gt" -> ComparisonOperator.GT
            "lt" -> ComparisonOperator.LT
            "gte" -> ComparisonOperator.GTE
            "lte" -> ComparisonOperator.LTE
            "eq" -> ComparisonOperator.EQ
            "ne" -> ComparisonOperator.NE
            else -> null
        }
    }

    /**
     * Extract a time comparison from a comparison expression.
     * Returns null if this is not a temporal comparison.
     */
    private fun extractTimeComparison(
        left: Expression,
        right: Expression,
        operator: ComparisonOperator,
        variables: Map<String, Expression>
    ): TimeComparison? {
        // Try to get a literal from the right side (or resolve variable)
        val literal = extractLiteral(right, variables) ?: return null

        // Backtrace the left side to find the temporal type and offset
        val backtraceResult = backtraceToTemporal(left, variables)
            ?: return null

        return TimeComparison(
            temporalType = backtraceResult.type,
            literal = literal,
            offset = backtraceResult.offsetMinutes,
            operator = operator
        )
    }

    /**
     * Extract a string literal from an expression, resolving variables if needed.
     * Note: In Mindl, bare identifiers are parsed as CallExpr, not VariableRef,
     * so we need to handle zero-arg CallExpr as potential variable references.
     */
    private fun extractLiteral(expr: Expression, variables: Map<String, Expression>): String? {
        return when (expr) {
            is StringLiteral -> expr.value
            is VariableRef -> {
                val resolved = variables[expr.name]
                if (resolved != null) extractLiteral(resolved, variables) else null
            }
            // In Mindl, bare identifiers become CallExpr with no args
            // These could be variable references
            is CallExpr -> {
                if (expr.args.isEmpty()) {
                    val resolved = variables[expr.name]
                    if (resolved != null) extractLiteral(resolved, variables) else null
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * Result of backtracing to a temporal expression.
     */
    private data class BacktraceResult(
        val type: TemporalType,
        val offsetMinutes: Long
    )

    /**
     * Backtrace from an expression to find the temporal source and accumulated offset.
     */
    private fun backtraceToTemporal(
        expr: Expression,
        variables: Map<String, Expression>
    ): BacktraceResult? {
        return when (expr) {
            is CallExpr -> {
                // Check if this is a temporal function (time, date, datetime)
                when (expr.name) {
                    "time" -> BacktraceResult(TemporalType.TIME, 0)
                    "date" -> BacktraceResult(TemporalType.DATE, 0)
                    "datetime" -> BacktraceResult(TemporalType.DATETIME, 0)
                    else -> {
                        // In Mindl, bare identifiers become zero-arg CallExpr
                        // These could be variable references
                        if (expr.args.isEmpty()) {
                            val resolved = variables[expr.name]
                            if (resolved != null) backtraceToTemporal(resolved, variables) else null
                        } else {
                            null
                        }
                    }
                }
            }

            is MethodCall -> {
                if (expr.methodName == "plus") {
                    // Extract offset and continue backtracing
                    val offset = extractPlusOffset(expr)
                    val innerResult = backtraceToTemporal(expr.target, variables)
                    if (innerResult != null) {
                        BacktraceResult(innerResult.type, innerResult.offsetMinutes + offset)
                    } else null
                } else {
                    // Try backtracing through the target
                    backtraceToTemporal(expr.target, variables)
                }
            }

            is VariableRef -> {
                val resolved = variables[expr.name]
                if (resolved != null) backtraceToTemporal(resolved, variables) else null
            }

            is PropertyAccess -> {
                backtraceToTemporal(expr.target, variables)
            }

            else -> null
        }
    }

    /**
     * Extract the offset in minutes from a .plus() method call.
     */
    private fun extractPlusOffset(expr: MethodCall): Long {
        var totalMinutes = 0L

        // Check for minutes: parameter
        val minutesArg = expr.namedArgs.find { it.name == "minutes" }
        if (minutesArg != null && minutesArg.value is NumberLiteral) {
            totalMinutes += (minutesArg.value as NumberLiteral).value.toLong()
        }

        // Check for hours: parameter (convert to minutes)
        val hoursArg = expr.namedArgs.find { it.name == "hours" }
        if (hoursArg != null && hoursArg.value is NumberLiteral) {
            totalMinutes += (hoursArg.value as NumberLiteral).value.toLong() * 60
        }

        // Check for days: parameter (convert to minutes)
        val daysArg = expr.namedArgs.find { it.name == "days" }
        if (daysArg != null && daysArg.value is NumberLiteral) {
            totalMinutes += (daysArg.value as NumberLiteral).value.toLong() * 24 * 60
        }

        return totalMinutes
    }

    /**
     * Create a candidate trigger from a time comparison.
     * Reverses any offset to find when the comparison flips.
     */
    private fun createCandidateTrigger(comparison: TimeComparison): TimeTrigger? {
        return when (comparison.temporalType) {
            TemporalType.TIME -> {
                val literalTime = try {
                    LocalTime.parse(comparison.literal)
                } catch (e: DateTimeParseException) {
                    return null
                }
                // Reverse the offset: if time.plus(10).gt("12:00"), trigger is 12:00 - 10 = 11:50
                val triggerTime = literalTime.minusMinutes(comparison.offset)
                DailyTimeTrigger(triggerTime)
            }

            TemporalType.DATE -> {
                val literalDate = try {
                    LocalDate.parse(comparison.literal)
                } catch (e: DateTimeParseException) {
                    return null
                }
                // Reverse the offset (days)
                val triggerDate = literalDate.minusDays(comparison.offset / (24 * 60))
                DateTrigger(triggerDate)
            }

            TemporalType.DATETIME -> {
                val literalDateTime = try {
                    LocalDateTime.parse(comparison.literal)
                } catch (e: DateTimeParseException) {
                    return null
                }
                // Reverse the offset
                val triggerDateTime = literalDateTime.minusMinutes(comparison.offset)
                DateTimeTrigger(triggerDateTime)
            }
        }
    }

    /**
     * Verify a candidate trigger by evaluating the expression at multiple points.
     * A trigger is valid if the result changes across the trigger time.
     *
     * For equality comparisons, we also check at the exact trigger time since
     * the condition may only be true at that precise moment.
     */
    private fun verifyTrigger(
        trigger: TimeTrigger,
        body: Expression,
        env: Environment
    ): Boolean {
        val triggerTime = when (trigger) {
            is DailyTimeTrigger -> LocalDateTime.now().with(trigger.triggerTime)
            is DateTrigger -> trigger.triggerDate.atStartOfDay()
            is DateTimeTrigger -> trigger.triggerDateTime
        }

        // Evaluate at the trigger time and at ±1 minute
        val beforeTime = triggerTime.minusMinutes(1)
        val afterTime = triggerTime.plusMinutes(1)

        return try {
            val beforeResult = evaluateAt(body, beforeTime, env)
            val atResult = evaluateAt(body, triggerTime, env)
            val afterResult = evaluateAt(body, afterTime, env)

            // Trigger is valid if:
            // 1. Result changes from before to after (gt/lt comparisons), OR
            // 2. Result at trigger time differs from before OR after (eq comparisons)
            (beforeResult != afterResult) ||
                (atResult != beforeResult) ||
                (atResult != afterResult)
        } catch (e: Exception) {
            // If evaluation fails, assume trigger is valid
            true
        }
    }

    /**
     * Evaluate an expression at a specific time.
     * Creates a mock environment that returns the given time for temporal functions.
     * Returns null if evaluation fails.
     */
    private fun evaluateAt(body: Expression, atTime: LocalDateTime, env: Environment): DslValue? {
        // Create an executor that returns the given time
        val mockEnv = env.withMockedTime(atTime)
        val executor = Executor()

        return try {
            executor.evaluate(body, mockEnv)
        } catch (e: Exception) {
            null
        }
    }
}
