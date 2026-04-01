package org.alkaline.taskbrain.dsl.runtime

import org.alkaline.taskbrain.dsl.language.Assignment
import org.alkaline.taskbrain.dsl.language.CallExpr
import org.alkaline.taskbrain.dsl.language.CurrentNoteRef
import org.alkaline.taskbrain.dsl.language.Directive
import org.alkaline.taskbrain.dsl.language.DynamicCallAnalyzer
import org.alkaline.taskbrain.dsl.language.Expression
import org.alkaline.taskbrain.dsl.language.LambdaExpr
import org.alkaline.taskbrain.dsl.language.LambdaInvocation
import org.alkaline.taskbrain.dsl.language.MethodCall
import org.alkaline.taskbrain.dsl.language.NumberLiteral
import org.alkaline.taskbrain.dsl.language.OnceExpr
import org.alkaline.taskbrain.dsl.language.PatternExpr
import org.alkaline.taskbrain.dsl.language.RefreshExpr
import org.alkaline.taskbrain.dsl.language.PropertyAccess
import org.alkaline.taskbrain.dsl.language.StatementList
import org.alkaline.taskbrain.dsl.language.StringLiteral
import org.alkaline.taskbrain.dsl.language.VariableRef
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.values.LambdaVal
import org.alkaline.taskbrain.dsl.runtime.values.NoteVal
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.PatternVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal

/**
 * Evaluates Mindl expressions and produces runtime values.
 *
 * Supports parenthesized calls with named arguments.
 * Supports pattern expressions.
 * Supports current note reference and property access.
 * Supports assignment, statement lists, variables, and method calls.
 * Supports lambda expressions and invocation.
 */
class Executor {

    /**
     * Execute a directive and return its result.
     * Injects this executor into the environment for lambda invocation.
     *
     * Added executor injection.
     * Added bare temporal value validation.
     */
    fun execute(directive: Directive, env: Environment = Environment()): DslValue {
        // Create an environment with this executor available for lambda invocation
        val execEnv = createExecutionEnvironment(env)
        val result = evaluate(directive.expression, execEnv)

        // Validate that temporal values are wrapped in once[...] or refresh[...]
        validateTemporalResult(result, directive.expression, directive.startPosition)

        return result
    }

    /**
     * Validate that dynamic temporal expressions are properly wrapped.
     * Expressions containing dynamic calls (date, time, datetime) should be wrapped
     * in once[...] to capture a snapshot or refresh[...] to specify update triggers.
     *
     * Only applies to expressions that contain dynamic calls - computed temporal values
     * from static inputs (like parse_date("2026-01-15").plus(days: 1)) are allowed.
     */
    private fun validateTemporalResult(result: DslValue, expr: Expression, position: Int) {
        // Only validate if the expression contains dynamic calls
        if (!DynamicCallAnalyzer.containsDynamicCalls(expr)) {
            return
        }

        // If the result is a temporal value from a dynamic expression, it needs wrapping
        if (result is DateVal || result is TimeVal || result is DateTimeVal) {
            // Check if the expression is wrapped in once[...]
            if (!isTemporalExpressionWrapped(expr)) {
                throw ExecutionException(
                    "Bare ${result.typeName} value is not allowed. " +
                    "Use once[${result.typeName}] to capture a snapshot, " +
                    "or refresh[...] to specify when to update.",
                    position
                )
            }
        }
    }

    /**
     * Check if an expression is wrapped in a temporal execution block (once, refresh).
     *
     * Added RefreshExpr.
     */
    private fun isTemporalExpressionWrapped(expr: Expression): Boolean {
        return expr is OnceExpr || expr is RefreshExpr
    }

    /**
     * Create an execution environment with this executor available.
     * Creates a child environment of baseEnv to preserve variable bindings.
     */
    private fun createExecutionEnvironment(baseEnv: Environment): Environment {
        // If the environment already has an executor, use it as-is
        if (baseEnv.getExecutor() != null) {
            return baseEnv
        }
        // Otherwise, create a child environment with this executor
        // This preserves variable bindings from baseEnv
        return baseEnv.withExecutor(this)
    }

    /**
     * Evaluate an expression to produce a value.
     *
     * Added Assignment, StatementList, VariableRef, MethodCall.
     * Added LambdaExpr.
     * Added LambdaInvocation.
     * Added OnceExpr.
     * Added RefreshExpr.
     */
    fun evaluate(expr: Expression, env: Environment): DslValue {
        return when (expr) {
            is NumberLiteral -> NumberVal(expr.value)
            is StringLiteral -> StringVal(expr.value)
            is CallExpr -> evaluateCall(expr, env)
            is PatternExpr -> evaluatePattern(expr)
            is CurrentNoteRef -> evaluateCurrentNoteRef(expr, env)
            is PropertyAccess -> evaluatePropertyAccess(expr, env)
            is Assignment -> evaluateAssignment(expr, env)
            is StatementList -> evaluateStatementList(expr, env)
            is VariableRef -> evaluateVariableRef(expr, env)
            is MethodCall -> evaluateMethodCall(expr, env)
            is LambdaExpr -> evaluateLambda(expr, env)
            is LambdaInvocation -> evaluateLambdaInvocation(expr, env)
            is OnceExpr -> evaluateOnce(expr, env)
            is RefreshExpr -> evaluateRefresh(expr, env)
        }
    }

    /**
     * Evaluate a lambda expression to produce a LambdaVal.
     * Captures the current environment for closure semantics.
     */
    private fun evaluateLambda(expr: LambdaExpr, env: Environment): LambdaVal {
        return LambdaVal(expr.params, expr.body, env.capture())
    }

    /**
     * Evaluate a lambda invocation (immediate call of a lambda).
     * Example: [[add(i, 1)](5)] evaluates to 6
     */
    private fun evaluateLambdaInvocation(expr: LambdaInvocation, env: Environment): DslValue {
        // Evaluate the lambda
        val lambdaVal = evaluateLambda(expr.lambda, env)

        // Evaluate arguments
        val argValues = expr.args.map { evaluate(it, env) }

        // Validate argument count
        if (argValues.size != lambdaVal.params.size) {
            throw ExecutionException(
                "Lambda requires ${lambdaVal.params.size} argument(s), got ${argValues.size}",
                expr.position
            )
        }

        // Named args not supported for lambda invocation
        if (expr.namedArgs.isNotEmpty()) {
            throw ExecutionException(
                "Named arguments not supported for lambda invocation",
                expr.position
            )
        }

        // Invoke the lambda
        return invokeLambda(lambdaVal, argValues)
    }

    /**
     * Evaluate a once expression with caching.
     * The body is evaluated once and the result is cached permanently.
     * Example: [once[datetime]] captures the datetime at first evaluation
     */
    private fun evaluateOnce(expr: OnceExpr, env: Environment): DslValue {
        // Compute cache key from the expression body
        val cacheKey = computeOnceCacheKey(expr.body)

        // Get or create the once cache
        val cache = env.getOrCreateOnceCache()

        // Check if already cached
        val cached = cache.get(cacheKey)
        if (cached != null) {
            return cached
        }

        // Evaluate the body
        val result = evaluate(expr.body, env)

        // Cache and return the result
        cache.put(cacheKey, result)
        return result
    }

    /**
     * Compute a cache key for a once[...] expression.
     * Uses a simple hash of the expression's string representation.
     */
    private fun computeOnceCacheKey(expr: Expression): String {
        // Use the expression's toString() as a basis for the key
        // This captures the structure of the expression
        return "once:${expr.hashCode()}"
    }

    /**
     * Evaluate a refresh expression.
     * For now, simply evaluates the body. Time trigger analysis will be added in.
     *
     * The refresh block indicates that the expression may be re-evaluated at specific
     * trigger times based on time comparisons in the body.
     */
    private fun evaluateRefresh(expr: RefreshExpr, env: Environment): DslValue {
        return evaluate(expr.body, env)
    }

    /**
     * Invoke a lambda with the given arguments.
     * Creates a child environment with parameters bound to arguments.
     */
    fun invokeLambda(lambda: LambdaVal, args: List<DslValue>): DslValue {
        val localEnv = lambda.capturedEnv.child()
        lambda.params.zip(args).forEach { (param, arg) ->
            localEnv.define(param, arg)
        }
        return evaluate(lambda.body, localEnv)
    }

    /**
     * Evaluate a current note reference.
     * Returns the current note from the environment.
     */
    private fun evaluateCurrentNoteRef(expr: CurrentNoteRef, env: Environment): NoteVal {
        return env.getCurrentNote()
            ?: throw ExecutionException(
                "No current note in context (use [.] only within a note)",
                expr.position
            )
    }

    /**
     * Evaluate property access on an expression.
     * Example: note.path, note.created
     */
    private fun evaluatePropertyAccess(expr: PropertyAccess, env: Environment): DslValue {
        val target = evaluate(expr.target, env)
        return when (target) {
            is NoteVal -> target.getProperty(expr.property, env)
            else -> throw ExecutionException(
                "Cannot access property '${expr.property}' on ${target.typeName}",
                expr.position
            )
        }
    }

    /**
     * Evaluate an assignment expression.
     * Example: [x: 5], [.path: "foo"]
     */
    private fun evaluateAssignment(expr: Assignment, env: Environment): DslValue {
        val value = evaluate(expr.value, env)

        when (val target = expr.target) {
            is VariableRef -> {
                // Variable definition: [x: 5]
                env.define(target.name, value)
            }
            is PropertyAccess -> {
                // Property assignment: [.path: "foo"] or [note.path: "bar"]
                val targetObj = evaluate(target.target, env)
                when (targetObj) {
                    is NoteVal -> {
                        targetObj.setProperty(target.property, value, env)
                    }
                    else -> throw ExecutionException(
                        "Cannot assign to property '${target.property}' on ${targetObj.typeName}",
                        expr.position
                    )
                }
            }
            is CurrentNoteRef -> {
                // [.: value] - not really meaningful, but could be used to replace note
                throw ExecutionException(
                    "Cannot assign directly to current note. Use property assignment like [.path: value]",
                    expr.position
                )
            }
            else -> throw ExecutionException(
                "Invalid assignment target",
                expr.position
            )
        }

        return value
    }

    /**
     * Evaluate a statement list, returning the value of the last statement.
     * Example: [x: 5; y: 10; add(x, y)]
     */
    private fun evaluateStatementList(expr: StatementList, env: Environment): DslValue {
        var result: DslValue = StringVal("")  // Default if empty
        for (statement in expr.statements) {
            result = evaluate(statement, env)
        }
        return result
    }

    /**
     * Evaluate a variable reference.
     * Example: [x] where x was previously defined
     */
    private fun evaluateVariableRef(expr: VariableRef, env: Environment): DslValue {
        return env.get(expr.name)
            ?: throw ExecutionException(
                "Undefined variable '${expr.name}'",
                expr.position
            )
    }

    /**
     * Evaluate a method call on an expression.
     * Example: [.append("text")], [note.append("text")], [date.plus(days: 7)]
     *
     * Note method calls.
     * Extended to support methods on all value types via MethodHandler.
     */
    private fun evaluateMethodCall(expr: MethodCall, env: Environment): DslValue {
        val target = evaluate(expr.target, env)

        // Evaluate arguments
        val positionalArgs = expr.args.map { evaluate(it, env) }
        val namedArgs = expr.namedArgs.associate { it.name to evaluate(it.value, env) }
        val args = Arguments(positionalArgs, namedArgs)

        // NoteVal has its own method handling (with environment for mutations)
        // Other types use the generic MethodHandler
        return when (target) {
            is NoteVal -> target.callMethod(expr.methodName, args, env, expr.position)
            else -> MethodHandler.callMethod(target, expr.methodName, args, expr.position)
        }
    }

    /**
     * Evaluate a pattern expression to produce a PatternVal.
     */
    private fun evaluatePattern(expr: PatternExpr): PatternVal {
        return PatternVal.compile(expr.elements)
    }

    /**
     * Evaluate a function call expression.
     *
     * For zero-arg calls, first check if it's a variable reference.
     * Support direct lambda invocation with f(arg) syntax.
     *
     * Semantics: Bare identifier invokes (like builtins). Use `later f` to get reference.
     * - [f] where f is lambda → tries to call with 0 args → error (lambda requires 1 arg)
     * - [f(x)] → calls lambda with i=x
     * - [later f] → returns the lambda without calling
     */
    private fun evaluateCall(expr: CallExpr, env: Environment): DslValue {
        // Check if this is a variable
        val variableValue = env.get(expr.name)

        if (variableValue != null) {
            // Variable exists - handle based on type
            return when (variableValue) {
                is LambdaVal -> {
                    // Lambda invocation: evaluate args and call
                    val argValues = expr.args.map { evaluate(it, env) }

                    // Validate argument count (lambdas have exactly 1 param: i)
                    if (argValues.size != variableValue.params.size) {
                        throw ExecutionException(
                            "Lambda requires ${variableValue.params.size} argument(s), got ${argValues.size}",
                            expr.position
                        )
                    }

                    // Named args not supported for lambda invocation yet
                    if (expr.namedArgs.isNotEmpty()) {
                        throw ExecutionException(
                            "Named arguments not supported for lambda invocation",
                            expr.position
                        )
                    }

                    invokeLambda(variableValue, argValues)
                }
                else -> {
                    // Non-lambda variable
                    if (expr.args.isEmpty() && expr.namedArgs.isEmpty()) {
                        // Zero-arg: return the value
                        variableValue
                    } else {
                        // Has args: error - can't call a non-lambda
                        throw ExecutionException(
                            "Cannot call ${variableValue.typeName} as a function",
                            expr.position
                        )
                    }
                }
            }
        }

        // Not a variable - look up builtin function
        val function = BuiltinRegistry.get(expr.name)
            ?: throw ExecutionException(
                "Unknown function or variable '${expr.name}'",
                expr.position
            )

        // Evaluate positional arguments
        val positionalValues = expr.args.map { evaluate(it, env) }

        // Evaluate named arguments
        val namedValues = expr.namedArgs.associate { namedArg ->
            namedArg.name to evaluate(namedArg.value, env)
        }

        // Build Arguments container
        val args = Arguments(positionalValues, namedValues)

        // Call the function
        return try {
            function.call(args, env)
        } catch (e: ExecutionException) {
            throw e
        } catch (e: Exception) {
            throw ExecutionException(
                "Error in '${expr.name}': ${e.message}",
                expr.position
            )
        }
    }
}
