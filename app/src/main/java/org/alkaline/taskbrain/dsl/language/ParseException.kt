package org.alkaline.taskbrain.dsl.language

/**
 * Thrown when the parser encounters a syntax error.
 */
class ParseException(
    message: String,
    val position: Int
) : RuntimeException("Parse error at position $position: $message")
