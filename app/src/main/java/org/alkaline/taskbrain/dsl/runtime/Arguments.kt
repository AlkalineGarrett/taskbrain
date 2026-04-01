package org.alkaline.taskbrain.dsl.runtime

import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.values.LambdaVal
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.PatternVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal

/**
 * Container for function arguments, supporting both positional and named arguments.
 *
 * @property positional List of positional arguments in order
 * @property named Map of named argument name to value
 */
data class Arguments(
    val positional: List<DslValue>,
    val named: Map<String, DslValue> = emptyMap()
) {
    /** Get a positional argument by index, or null if not present. */
    operator fun get(index: Int): DslValue? = positional.getOrNull(index)

    /** Get a named argument by name, or null if not present. */
    operator fun get(name: String): DslValue? = named[name]

    /** Get a positional argument, throwing if not present. */
    fun require(index: Int, paramName: String = "argument $index"): DslValue =
        positional.getOrNull(index)
            ?: throw ExecutionException("Missing required $paramName")

    /** Get a named argument, throwing if not present. */
    fun requireNamed(name: String): DslValue =
        named[name] ?: throw ExecutionException("Missing required argument '$name'")

    /** Total number of positional arguments. */
    val size: Int get() = positional.size

    /** Check if any positional arguments were provided. */
    fun hasPositional(): Boolean = positional.isNotEmpty()

    /** Check if any named arguments were provided. */
    fun hasNamed(): Boolean = named.isNotEmpty()

    /** Check if a specific named argument was provided. */
    fun hasNamed(name: String): Boolean = named.containsKey(name)

    // --- Validation utilities ---

    /**
     * Require exactly [count] positional arguments.
     * @param funcName Function name for error messages
     * @throws ExecutionException if count doesn't match
     */
    fun requireExactCount(count: Int, funcName: String) {
        if (size != count) {
            throw ExecutionException("'$funcName' requires $count arguments, got $size")
        }
    }

    /**
     * Require no positional arguments.
     * @param funcName Function name for error messages
     * @throws ExecutionException if any positional arguments provided
     */
    fun requireNoArgs(funcName: String) {
        if (hasPositional()) {
            throw ExecutionException("'$funcName' takes no arguments, got $size")
        }
    }

    /**
     * Get a positional argument as NumberVal, throwing with descriptive error if not present or wrong type.
     * @param index Argument index
     * @param funcName Function name for error messages
     * @param paramName Parameter name for error messages
     * @return The NumberVal
     * @throws ExecutionException if argument is missing or not a number
     */
    fun requireNumber(index: Int, funcName: String, paramName: String = "argument ${index + 1}"): NumberVal {
        val arg = positional.getOrNull(index)
            ?: throw ExecutionException("'$funcName' missing $paramName")
        return arg as? NumberVal
            ?: throw ExecutionException("'$funcName' $paramName must be a number, got ${arg.typeName}")
    }

    /**
     * Get a positional argument as StringVal, throwing with descriptive error if not present or wrong type.
     * @param index Argument index
     * @param funcName Function name for error messages
     * @param paramName Parameter name for error messages
     * @return The StringVal
     * @throws ExecutionException if argument is missing or not a string
     */
    fun requireString(index: Int, funcName: String, paramName: String = "argument ${index + 1}"): StringVal {
        val arg = positional.getOrNull(index)
            ?: throw ExecutionException("'$funcName' missing $paramName")
        return arg as? StringVal
            ?: throw ExecutionException("'$funcName' $paramName must be a string, got ${arg.typeName}")
    }

    /**
     * Get a positional argument as PatternVal, throwing with descriptive error if not present or wrong type.
     * @param index Argument index
     * @param funcName Function name for error messages
     * @param paramName Parameter name for error messages
     * @return The PatternVal
     * @throws ExecutionException if argument is missing or not a pattern
     */
    fun requirePattern(index: Int, funcName: String, paramName: String = "argument ${index + 1}"): PatternVal {
        val arg = positional.getOrNull(index)
            ?: throw ExecutionException("'$funcName' missing $paramName")
        return arg as? PatternVal
            ?: throw ExecutionException("'$funcName' $paramName must be a pattern, got ${arg.typeName}")
    }

    /**
     * Get a named argument as LambdaVal, or null if not present.
     */
    fun getLambda(name: String): LambdaVal? = named[name] as? LambdaVal

    /**
     * Get a positional argument as BooleanVal, throwing with descriptive error if not present or wrong type.
     * @param index Argument index
     * @param funcName Function name for error messages
     * @param paramName Parameter name for error messages
     * @return The BooleanVal
     * @throws ExecutionException if argument is missing or not a boolean
     *
     * Added for comparison/logical functions.
     */
    fun requireBoolean(index: Int, funcName: String, paramName: String = "argument ${index + 1}"): BooleanVal {
        val arg = positional.getOrNull(index)
            ?: throw ExecutionException("'$funcName' missing $paramName")
        return arg as? BooleanVal
            ?: throw ExecutionException("'$funcName' $paramName must be a boolean, got ${arg.typeName}")
    }

    /**
     * Get a positional argument as LambdaVal, throwing with descriptive error if not present or wrong type.
     * @param index Argument index
     * @param funcName Function name for error messages
     * @param paramName Parameter name for error messages
     * @return The LambdaVal
     * @throws ExecutionException if argument is missing or not a lambda
     *
     * Added for button/schedule functions.
     */
    fun requireLambda(index: Int, funcName: String, paramName: String = "argument ${index + 1}"): LambdaVal {
        val arg = positional.getOrNull(index)
            ?: throw ExecutionException("'$funcName' missing $paramName")
        return arg as? LambdaVal
            ?: throw ExecutionException("'$funcName' $paramName must be a lambda/deferred block, got ${arg.typeName}")
    }
}
