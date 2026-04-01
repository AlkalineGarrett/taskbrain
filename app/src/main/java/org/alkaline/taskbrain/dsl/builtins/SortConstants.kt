package org.alkaline.taskbrain.dsl.builtins

import org.alkaline.taskbrain.dsl.runtime.BuiltinFunction
import org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.values.StringVal

/**
 * Sort order constants for mobile-friendly sorting.
 *
 * Full words like "ascending" and "descending" are preferred over abbreviations
 * because mobile keyboards with autocorrect make full words easier to type than
 * abbreviations or quoted strings.
 */
object SortConstants {

    /** The string value used for ascending sort order. */
    const val ASCENDING = "ascending"

    /** The string value used for descending sort order. */
    const val DESCENDING = "descending"

    fun register(registry: BuiltinRegistry) {
        registry.register(ascendingConstant)
        registry.register(descendingConstant)
    }

    private val ascendingConstant = BuiltinFunction("ascending") { args, _ ->
        if (args.hasPositional()) {
            throw ExecutionException("'ascending' takes no arguments, got ${args.size}")
        }
        StringVal(ASCENDING)
    }

    private val descendingConstant = BuiltinFunction("descending") { args, _ ->
        if (args.hasPositional()) {
            throw ExecutionException("'descending' takes no arguments, got ${args.size}")
        }
        StringVal(DESCENDING)
    }
}
