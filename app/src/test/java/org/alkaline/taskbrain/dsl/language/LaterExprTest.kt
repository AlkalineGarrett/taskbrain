package org.alkaline.taskbrain.dsl.language

import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.values.LambdaVal
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for Phase 0e: later keyword for deferred evaluation.
 *
 * Tests:
 * - `later expr` parsing and evaluation
 * - `later[...]` redundancy handling
 * - Equivalence between `later expr` and `[expr]`
 * - Static analysis for later expressions
 */
class LaterExprTest {

    private lateinit var executor: Executor

    @Before
    fun setUp() {
        executor = Executor()
    }

    private fun execute(source: String, env: Environment = Environment()): DslValue {
        val tokens = Lexer(source).tokenize()
        val directive = Parser(tokens, source).parseDirective()
        return executor.execute(directive, env)
    }

    private fun parse(source: String): Directive {
        val tokens = Lexer(source).tokenize()
        return Parser(tokens, source).parseDirective()
    }

    // region later expr parsing

    @Test
    fun `later followed by number parses as LambdaExpr`() {
        val directive = parse("[later 5]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertEquals(listOf("i"), lambda.params)
        assertTrue(lambda.body is NumberLiteral)
    }

    @Test
    fun `later followed by string parses as LambdaExpr`() {
        val directive = parse("[later \"hello\"]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertTrue(lambda.body is StringLiteral)
    }

    @Test
    fun `later followed by identifier parses as LambdaExpr`() {
        val directive = parse("[later foo]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertTrue(lambda.body is CallExpr)
        assertEquals("foo", (lambda.body as CallExpr).name)
    }

    @Test
    fun `later followed by function call with parens parses correctly`() {
        val directive = parse("[later add(1, 2)]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertTrue(lambda.body is CallExpr)
        val call = lambda.body as CallExpr
        assertEquals("add", call.name)
        assertEquals(2, call.args.size)
    }

    // endregion

    // region later[...] redundancy

    @Test
    fun `later bracket parses same as just bracket`() {
        // later[5] should be equivalent to [5]
        val laterDirective = parse("[later[5]]")
        val bracketDirective = parse("[[5]]")

        // Both should produce LambdaExpr
        assertTrue(laterDirective.expression is LambdaExpr)
        assertTrue(bracketDirective.expression is LambdaExpr)

        // Both should have the same body structure
        val laterBody = (laterDirective.expression as LambdaExpr).body
        val bracketBody = (bracketDirective.expression as LambdaExpr).body
        assertTrue(laterBody is NumberLiteral)
        assertTrue(bracketBody is NumberLiteral)
        assertEquals(5.0, (laterBody as NumberLiteral).value, 0.001)
        assertEquals(5.0, (bracketBody as NumberLiteral).value, 0.001)
    }

    @Test
    fun `later bracket with complex expression parses correctly`() {
        val directive = parse("[later[add(1, 2)]]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertTrue(lambda.body is CallExpr)
        assertEquals("add", (lambda.body as CallExpr).name)
    }

    @Test
    fun `later bracket can reference i parameter`() {
        val directive = parse("[later[add(i, 1)]]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertEquals(listOf("i"), lambda.params)
        assertTrue(lambda.body is CallExpr)
    }

    // endregion

    // region later expr vs bracket equivalence

    @Test
    fun `later expr and bracket expr produce same value when invoked`() {
        // later 5 and [5] should both produce lambdas that return 5
        val laterResult = execute("[later 5]")
        val bracketResult = execute("[[5]]")

        assertTrue(laterResult is LambdaVal)
        assertTrue(bracketResult is LambdaVal)

        // Invoke both via assignment
        val laterInvoked = execute("[f: later 5; f(0)]")
        val bracketInvoked = execute("[f: [5]; f(0)]")

        assertTrue(laterInvoked is NumberVal)
        assertTrue(bracketInvoked is NumberVal)
        assertEquals(5.0, (laterInvoked as NumberVal).value, 0.001)
        assertEquals(5.0, (bracketInvoked as NumberVal).value, 0.001)
    }

    @Test
    fun `later expr uses i as implicit parameter`() {
        // later add(i, 1) should work like [add(i, 1)]
        val result = execute("[f: later add(i, 1); f(5)]")

        assertTrue(result is NumberVal)
        assertEquals(6.0, (result as NumberVal).value, 0.001)
    }

    // endregion

    // region later evaluation

    @Test
    fun `later returns a lambda value`() {
        val result = execute("[later 5]")

        assertTrue(result is LambdaVal)
    }

    @Test
    fun `later lambda can be invoked`() {
        val result = execute("[f: later 5; f(0)]")

        assertTrue(result is NumberVal)
        assertEquals(5.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `later lambda receives argument as i`() {
        val result = execute("[f: later i; f(42)]")

        assertTrue(result is NumberVal)
        assertEquals(42.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `later with calculation uses i`() {
        val result = execute("[f: later add(i, 10); f(5)]")

        assertTrue(result is NumberVal)
        assertEquals(15.0, (result as NumberVal).value, 0.001)
    }

    // endregion

    // region later in expressions

    @Test
    fun `later can be assigned to variable`() {
        val result = execute("[f: later add(i, 1); f(5)]")

        assertTrue(result is NumberVal)
        assertEquals(6.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `later can be used multiple times`() {
        val result = execute("[f: later add(i, 1); g: later add(i, 10); add(f(5), g(2))]")

        assertTrue(result is NumberVal)
        // f(5) = 6, g(2) = 12, sum = 18
        assertEquals(18.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `later bracket assigned to variable`() {
        val result = execute("[f: later[add(i, 1)]; f(5)]")

        assertTrue(result is NumberVal)
        assertEquals(6.0, (result as NumberVal).value, 0.001)
    }

    // endregion

    // region Static analysis

    @Test
    fun `later expression is static when body is static`() {
        val directive = parse("[later 5]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `later expression is dynamic when body contains dynamic call`() {
        val directive = parse("[later time]")
        assertTrue(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `later expression is idempotent`() {
        val directive = parse("[later add(i, 1)]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertTrue(result.isIdempotent)
    }

    // endregion

    // region Edge cases

    @Test
    fun `later with current note reference`() {
        // later . should work
        val directive = parse("[later .]")

        assertTrue(directive.expression is LambdaExpr)
    }

    @Test
    fun `nested later expressions`() {
        // later later 5 - outer later takes inner later as primary
        val directive = parse("[later later 5]")

        assertTrue(directive.expression is LambdaExpr)
        // The body should be another LambdaExpr (from inner later)
        assertTrue((directive.expression as LambdaExpr).body is LambdaExpr)
    }

    @Test
    fun `later in function argument position`() {
        // Passing a later expression as argument
        val env = Environment()
        env.define("apply", LambdaVal(
            listOf("f"),
            CallExpr("f", listOf(NumberLiteral(10.0, 0)), 0, emptyList()),
            env
        ))

        val result = execute("[apply(later add(i, 1))]", env)

        assertTrue(result is NumberVal)
        assertEquals(11.0, (result as NumberVal).value, 0.001)
    }

    // endregion
}
