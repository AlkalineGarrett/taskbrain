package org.alkaline.taskbrain.dsl.builtins

import org.alkaline.taskbrain.dsl.runtime.Arguments
import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.BuiltinFunction
import org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.values.LambdaVal
import org.alkaline.taskbrain.dsl.runtime.values.ListVal
import org.alkaline.taskbrain.dsl.runtime.values.NoteVal
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import org.alkaline.taskbrain.dsl.runtime.values.UndefinedVal

/**
 * List-related builtin functions.
 *
 * list(), sort(), first()
 */
object ListFunctions {

    fun register(registry: BuiltinRegistry) {
        registry.register(listFunction)
        registry.register(sortFunction)
        registry.register(firstFunction)
    }

    /**
     * list(a, b, c, ...) - Create a list from arguments.
     *
     * Parameters:
     * - Any number of positional arguments (including zero)
     *
     * Returns: A ListVal containing all the arguments
     *
     * Examples:
     *   [list()]              - Empty list
     *   [list(1, 2, 3)]       - List of numbers
     *   [list("a", "b")]      - List of strings
     *   [list(1, "mixed", date)]  - Mixed types allowed
     */
    private val listFunction = BuiltinFunction(
        name = "list",
        isDynamic = false
    ) { args, _ ->
        ListVal(args.positional)
    }

    /**
     * sort(list, key: lambda, order: ascending|descending) - Sort a list of values.
     *
     * Parameters:
     * - First positional argument: The list to sort
     * - key: Optional lambda to extract sort key from each item
     * - order: Optional `ascending` (default) or `descending` for sort direction
     *
     * Returns: A new sorted ListVal
     *
     * Comparison rules:
     * - Numbers: numerically
     * - Strings: lexicographically
     * - Dates/Times/DateTimes: chronologically
     * - Booleans: false < true
     * - Notes: by path, then by name, then by id
     * - Other types: by type name then display string
     *
     * Examples:
     *   [sort(find())]                                      - Sort notes by default (path/name/id)
     *   [sort(find(), key: [i.path])]                 - Sort by path
     *   [sort(find(), key: [i.path], order: descending)]  - Sort by path descending
     *   [sort(list(3, 1, 4), order: descending)]            - Sort numbers descending
     */
    private val sortFunction = BuiltinFunction(
        name = "sort",
        isDynamic = false  // Results are deterministic based on input
    ) { args, env ->
        // Require exactly one positional argument (the list)
        if (args.size != 1) {
            throw ExecutionException("'sort' requires exactly 1 positional argument (list), got ${args.size}")
        }

        val listArg = args[0]
            ?: throw ExecutionException("'sort' requires a list argument")
        val list = listArg as? ListVal
            ?: throw ExecutionException("'sort' first argument must be a list, got ${listArg.typeName}")

        val keyLambda = args.getLambda("key")
        val orderArg = args["order"]
        val isDescending = when (orderArg) {
            null -> false  // Default: ascending
            is StringVal -> {
                val value = orderArg.value.lowercase()
                when (value) {
                    SortConstants.ASCENDING -> false
                    SortConstants.DESCENDING -> true
                    else -> throw ExecutionException(
                        "'sort' order must be 'ascending' or 'descending', got '$value'"
                    )
                }
            }
            else -> throw ExecutionException(
                "'sort' order must be 'ascending' or 'descending', got ${orderArg.typeName}"
            )
        }

        val sorted = sortList(list.items, keyLambda, env)
        ListVal(if (isDescending) sorted.reversed() else sorted)
    }

    /**
     * Sort a list of DslValues using optional key lambda.
     */
    private fun sortList(
        items: List<DslValue>,
        keyLambda: LambdaVal?,
        env: Environment
    ): List<DslValue> {
        if (items.isEmpty()) return items

        return if (keyLambda != null) {
            val executor = env.getExecutor()
                ?: throw ExecutionException("'sort' key: requires an executor in the environment")

            // Extract keys once and sort by them
            val itemsWithKeys = items.map { item ->
                item to executor.invokeLambda(keyLambda, listOf(item))
            }
            itemsWithKeys.sortedWith { (_, key1), (_, key2) ->
                compareValues(key1, key2)
            }.map { it.first }
        } else {
            // Sort by the items themselves
            items.sortedWith { a, b -> compareValues(a, b) }
        }
    }

    /**
     * Compare two DslValues for sorting.
     *
     * Comparison rules:
     * 1. Same type: compare by value semantics
     * 2. Different types: compare by type precedence
     *
     * Type precedence (lowest to highest):
     * undefined < boolean < number < string < date < time < datetime < note < list < other
     */
    internal fun compareValues(a: DslValue, b: DslValue): Int {
        // Handle undefined - always sorts first
        if (a is UndefinedVal && b is UndefinedVal) return 0
        if (a is UndefinedVal) return -1
        if (b is UndefinedVal) return 1

        // Same type comparison
        return when {
            a is NumberVal && b is NumberVal -> a.value.compareTo(b.value)
            a is StringVal && b is StringVal -> a.value.compareTo(b.value)
            a is BooleanVal && b is BooleanVal -> a.value.compareTo(b.value)
            a is DateVal && b is DateVal -> a.value.compareTo(b.value)
            a is TimeVal && b is TimeVal -> a.value.compareTo(b.value)
            a is DateTimeVal && b is DateTimeVal -> a.value.compareTo(b.value)
            a is NoteVal && b is NoteVal -> compareNotes(a, b)
            a is ListVal && b is ListVal -> compareLists(a, b)

            // Different types: compare by type precedence
            else -> typePrecedence(a).compareTo(typePrecedence(b))
        }
    }

    /**
     * Compare two notes for sorting.
     * Order: by path, then by name (first line), then by id.
     */
    private fun compareNotes(a: NoteVal, b: NoteVal): Int {
        // First compare by path
        val pathCompare = a.note.path.compareTo(b.note.path)
        if (pathCompare != 0) return pathCompare

        // Then by name (first line of content)
        val nameA = a.note.content.lines().firstOrNull() ?: ""
        val nameB = b.note.content.lines().firstOrNull() ?: ""
        val nameCompare = nameA.compareTo(nameB)
        if (nameCompare != 0) return nameCompare

        // Finally by id
        return a.note.id.compareTo(b.note.id)
    }

    /**
     * Compare two lists for sorting.
     * Order: by size first, then element-by-element.
     */
    private fun compareLists(a: ListVal, b: ListVal): Int {
        // First compare by size
        val sizeCompare = a.size.compareTo(b.size)
        if (sizeCompare != 0) return sizeCompare

        // Then compare element by element
        for (i in 0 until a.size) {
            val elemCompare = compareValues(a.items[i], b.items[i])
            if (elemCompare != 0) return elemCompare
        }

        return 0
    }

    /**
     * Get type precedence for cross-type comparison.
     */
    private fun typePrecedence(value: DslValue): Int = when (value) {
        is UndefinedVal -> 0
        is BooleanVal -> 1
        is NumberVal -> 2
        is StringVal -> 3
        is DateVal -> 4
        is TimeVal -> 5
        is DateTimeVal -> 6
        is NoteVal -> 7
        is ListVal -> 8
        else -> 9  // Lambda, Pattern, etc.
    }

    /**
     * first(list) - Get the first item from a list.
     *
     * Returns: The first item, or UndefinedVal if the list is empty.
     *
     * Following the Mindl design principle: graceful undefined access.
     * Use `maybe(first(...))` to convert undefined to empty if needed.
     *
     * Examples:
     *   [first(find(path: "inbox"))]  - Get the inbox note if it exists
     *   [first(sort(find(), order: desc))]  - Get the most recent note
     */
    private val firstFunction = BuiltinFunction(
        name = "first",
        isDynamic = false
    ) { args, _ ->
        if (args.size != 1) {
            throw ExecutionException("'first' requires exactly 1 argument (list), got ${args.size}")
        }

        val listArg = args[0]
            ?: throw ExecutionException("'first' requires a list argument")
        val list = listArg as? ListVal
            ?: throw ExecutionException("'first' argument must be a list, got ${listArg.typeName}")

        // Return first item or undefined if empty (graceful undefined access)
        list.items.firstOrNull() ?: UndefinedVal
    }
}
