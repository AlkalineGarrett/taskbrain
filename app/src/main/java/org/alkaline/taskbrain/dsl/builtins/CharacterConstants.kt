package org.alkaline.taskbrain.dsl.builtins

import org.alkaline.taskbrain.dsl.runtime.BuiltinFunction
import org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.values.StringVal

/**
 * Character constant functions for mobile-friendly string building.
 *
 * Since Mindl has no escape sequences in strings (to make typing on mobile
 * easier), special characters are inserted using these constant functions.
 *
 * qt, nl, tab, ret
 */
object CharacterConstants {

    fun register(registry: BuiltinRegistry) {
        charConstants.forEach { registry.register(it) }
    }

    /** Creates a zero-arg function that returns a constant string value. */
    private fun charConstant(name: String, value: String) = BuiltinFunction(name) { args, _ ->
        if (args.hasPositional()) {
            throw ExecutionException("'$name' takes no arguments, got ${args.size}")
        }
        StringVal(value)
    }

    private val charConstants = listOf(
        charConstant("qt", "\""),   // Double quote
        charConstant("nl", "\n"),   // Newline
        charConstant("tab", "\t"),  // Tab
        charConstant("ret", "\r")   // Carriage return
    )
}
