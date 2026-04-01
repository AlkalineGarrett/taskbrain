package org.alkaline.taskbrain.dsl.builtins

import org.alkaline.taskbrain.dsl.runtime.Arguments
import org.alkaline.taskbrain.dsl.runtime.BuiltinFunction
import org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal

/**
 * Arithmetic builtin functions.
 *
 * add, sub, mul, div, mod
 * neg
 */
object ArithmeticFunctions {

    fun register(registry: BuiltinRegistry) {
        registry.register(addFunction)
        registry.register(subFunction)
        registry.register(mulFunction)
        registry.register(divFunction)
        registry.register(modFunction)
        registry.register(negFunction)
    }

    /**
     * add(a, b) - Returns the sum of two numbers.
     * Example: [add(1, 2)] -> 3
     */
    private val addFunction = BuiltinFunction(name = "add") { args, _ ->
        args.requireExactCount(2, "add")
        val a = args.requireNumber(0, "add", "first argument").value
        val b = args.requireNumber(1, "add", "second argument").value
        NumberVal(a + b)
    }

    /**
     * sub(a, b) - Returns the difference of two numbers (a - b).
     * Example: [sub(5, 3)] -> 2
     */
    private val subFunction = BuiltinFunction(name = "sub") { args, _ ->
        args.requireExactCount(2, "sub")
        val a = args.requireNumber(0, "sub", "first argument").value
        val b = args.requireNumber(1, "sub", "second argument").value
        NumberVal(a - b)
    }

    /**
     * mul(a, b) - Returns the product of two numbers.
     * Example: [mul(3, 4)] -> 12
     */
    private val mulFunction = BuiltinFunction(name = "mul") { args, _ ->
        args.requireExactCount(2, "mul")
        val a = args.requireNumber(0, "mul", "first argument").value
        val b = args.requireNumber(1, "mul", "second argument").value
        NumberVal(a * b)
    }

    /**
     * div(a, b) - Returns the quotient of two numbers (a / b).
     * Example: [div(10, 4)] -> 2.5
     * Throws on division by zero.
     */
    private val divFunction = BuiltinFunction(name = "div") { args, _ ->
        args.requireExactCount(2, "div")
        val a = args.requireNumber(0, "div", "first argument").value
        val b = args.requireNumber(1, "div", "second argument").value
        if (b == 0.0) {
            throw ExecutionException("Division by zero")
        }
        NumberVal(a / b)
    }

    /**
     * mod(a, b) - Returns the remainder of a divided by b.
     * Example: [mod(10, 3)] -> 1
     * Throws on modulo by zero.
     */
    private val modFunction = BuiltinFunction(name = "mod") { args, _ ->
        args.requireExactCount(2, "mod")
        val a = args.requireNumber(0, "mod", "first argument").value
        val b = args.requireNumber(1, "mod", "second argument").value
        if (b == 0.0) {
            throw ExecutionException("Modulo by zero")
        }
        NumberVal(a % b)
    }

    /**
     * neg(a) - Returns the negation of a number.
     * Example: [neg(5)] -> -5
     * Example: [neg(-3)] -> 3
     */
    private val negFunction = BuiltinFunction(name = "neg") { args, _ ->
        args.requireExactCount(1, "neg")
        val a = args.requireNumber(0, "neg", "argument").value
        NumberVal(-a)
    }
}
