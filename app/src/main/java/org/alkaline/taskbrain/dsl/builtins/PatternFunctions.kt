package org.alkaline.taskbrain.dsl.builtins

import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.BuiltinFunction
import org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry

/**
 * Pattern matching builtin functions.
 *
 * matches(string, pattern)
 */
object PatternFunctions {

    fun register(registry: BuiltinRegistry) {
        registry.register(matchesFunction)
    }

    /**
     * matches(string, pattern) - Check if a string matches a pattern.
     *
     * Example: [matches("2026-01-15", pattern(digit*4 "-" digit*2 "-" digit*2))] -> true
     *
     * @param string The string to test
     * @param pattern The pattern to match against
     * @return BooleanVal(true) if the string matches the pattern, BooleanVal(false) otherwise
     */
    private val matchesFunction = BuiltinFunction(name = "matches") { args, _ ->
        args.requireExactCount(2, "matches")
        val stringArg = args.requireString(0, "matches", "first argument")
        val patternArg = args.requirePattern(1, "matches", "second argument")
        BooleanVal(patternArg.matches(stringArg.value))
    }
}
