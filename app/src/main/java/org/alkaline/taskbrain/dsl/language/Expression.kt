package org.alkaline.taskbrain.dsl.language

/**
 * Base class for all AST expression nodes.
 * Each subclass represents a different syntactic construct.
 *
 * Adds support for parenthesized calls with named arguments.
 * Adds Assignment and StatementList for note mutation.
 */
sealed class Expression {
    /** The position in source where this expression starts */
    abstract val position: Int
}

/**
 * A numeric literal (integer or decimal).
 * Example: 42, 3.14
 */
data class NumberLiteral(
    val value: Double,
    override val position: Int
) : Expression()

/**
 * A string literal.
 * Example: "hello world"
 */
data class StringLiteral(
    val value: String,
    override val position: Int
) : Expression()

/**
 * A named argument in a function call.
 * Example: path: "foo" in find(path: "foo")
 */
data class NamedArg(
    val name: String,
    val value: Expression,
    val position: Int
)

/**
 * A function call expression.
 * Example: date, iso8601 date, add(1, 2), find(path: "foo")
 *
 * Supports two calling styles:
 * 1. Space-separated: identifiers nest right-to-left
 *    - [a b c] parses as CallExpr("a", [CallExpr("b", [CallExpr("c", [])])])
 * 2. Parenthesized: explicit argument lists with optional named args
 *    - [add(1, 2)] parses as CallExpr("add", [NumberLiteral(1), NumberLiteral(2)])
 *    - [foo(bar: "baz")] parses with named arg
 *
 * Named arguments come after positional arguments in the namedArgs list.
 */
data class CallExpr(
    val name: String,
    val args: List<Expression>,
    override val position: Int,
    val namedArgs: List<NamedArg> = emptyList()
) : Expression()

/**
 * A complete directive enclosed in brackets.
 * Example: [42], ["hello"], [date]
 */
data class Directive(
    val expression: Expression,
    val sourceText: String,
    val startPosition: Int
)

// ============================================================================
// Note Property AST Nodes
// ============================================================================

/**
 * Reference to the current note.
 * Example: [.] refers to the note containing the directive
 */
data class CurrentNoteRef(override val position: Int) : Expression()

/**
 * Property access on an expression result.
 * Example: [.path] (property on current note), [find(...).path] (property on result)
 */
data class PropertyAccess(
    val target: Expression,
    val property: String,
    override val position: Int
) : Expression()

// ============================================================================
// Note Mutation AST Nodes
// ============================================================================

/**
 * An assignment expression.
 * Used for variable definitions and property assignments.
 * Example: [x: 5], [.path: "foo"], [note.path: "bar"]
 */
data class Assignment(
    val target: Expression,
    val value: Expression,
    override val position: Int
) : Expression()

/**
 * A list of statements separated by semicolons.
 * Returns the value of the last statement.
 * Example: [x: 5; y: 10; add(x, y)]
 */
data class StatementList(
    val statements: List<Expression>,
    override val position: Int
) : Expression()

/**
 * A variable reference.
 * Example: [x] where x was previously defined as [x: 5]
 */
data class VariableRef(
    val name: String,
    override val position: Int
) : Expression()

/**
 * A method call on an expression.
 * Example: [.append("text")], [note.append("text")]
 */
data class MethodCall(
    val target: Expression,
    val methodName: String,
    val args: List<Expression>,
    val namedArgs: List<NamedArg>,
    override val position: Int
) : Expression()

// ============================================================================
// Pattern AST Nodes
// ============================================================================

/**
 * Character class types for pattern matching.
 */
enum class CharClassType {
    DIGIT,   // Matches 0-9
    LETTER,  // Matches a-z, A-Z
    SPACE,   // Matches whitespace
    PUNCT,   // Matches punctuation
    ANY      // Matches any character
}

/**
 * Quantifier for pattern elements.
 */
sealed class Quantifier {
    /** Matches exactly n times. Example: *4 */
    data class Exact(val n: Int) : Quantifier()

    /** Matches between min and max times. max=null means unbounded. Example: *(0..5), *(1..) */
    data class Range(val min: Int, val max: Int?) : Quantifier()

    /** Matches any number of times (0+). Equivalent to Range(0, null). Example: *any */
    data object Any : Quantifier()
}

/**
 * A single element in a pattern.
 */
sealed class PatternElement {
    /** The position in source where this element starts */
    abstract val position: Int
}

/**
 * A character class in a pattern.
 * Example: digit, letter, space, punct, any
 */
data class CharClass(
    val type: CharClassType,
    override val position: Int
) : PatternElement()

/**
 * A literal string in a pattern.
 * Example: "-" in pattern(digit*4 "-" digit*2)
 */
data class PatternLiteral(
    val value: String,
    override val position: Int
) : PatternElement()

/**
 * A quantified pattern element.
 * Example: digit*4, letter*any, any*(1..)
 */
data class Quantified(
    val element: PatternElement,
    val quantifier: Quantifier,
    override val position: Int
) : PatternElement()

/**
 * A complete pattern expression.
 * Example: pattern(digit*4 "-" digit*2 "-" digit*2)
 */
data class PatternExpr(
    val elements: List<PatternElement>,
    override val position: Int
) : Expression()

// ============================================================================
// Lambda AST Node
// ============================================================================

/**
 * A lambda expression with implicit parameter binding.
 * Example: [[i.path]] creates a lambda with param "i" and body "i.path"
 * Example: [[add(i, 1)]] creates a lambda with param "i"
 */
data class LambdaExpr(
    val params: List<String>,  // Parameter names (e.g., ["i"] for implicit form)
    val body: Expression,
    override val position: Int
) : Expression()

/**
 * Immediate invocation of a lambda expression.
 * Example: [[i.path](.)] invokes the lambda with the current note
 * Example: [[add(i, 1)](5)] invokes the lambda with argument 5
 */
data class LambdaInvocation(
    val lambda: LambdaExpr,
    val args: List<Expression>,
    val namedArgs: List<NamedArg>,
    override val position: Int
) : Expression()

// ============================================================================
// Execution Block AST Nodes
// ============================================================================

/**
 * A once execution block that evaluates its body once and caches the result.
 * The cached result persists until the directive text changes.
 * Example: [once[datetime]] - captures the datetime at first evaluation
 */
data class OnceExpr(
    val body: Expression,
    override val position: Int
) : Expression()

/**
 * A refresh execution block that re-evaluates at analyzed trigger times.
 * The trigger times are determined by analyzing time comparisons in the body.
 * Example: [refresh[if(time.gt("12:00"), X, Y)]] - re-evaluates at 12:00
 */
data class RefreshExpr(
    val body: Expression,
    override val position: Int
) : Expression()
