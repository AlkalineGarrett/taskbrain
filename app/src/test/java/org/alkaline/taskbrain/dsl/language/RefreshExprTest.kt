package org.alkaline.taskbrain.dsl.language

import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for Phase 0d: refresh[...] execution block.
 *
 * Tests:
 * - refresh[...] parsing
 * - refresh[...] basic evaluation
 * - refresh[...] allows temporal values (no bare time error)
 * - Static analysis for RefreshExpr
 *
 * Note: Time trigger analysis will be tested in Phase 3.
 */
class RefreshExprTest {

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

    // region refresh[...] parsing

    @Test
    fun `refresh keyword followed by bracket parses as RefreshExpr`() {
        val directive = parse("[refresh[5]]")

        assertTrue(directive.expression is RefreshExpr)
        val refresh = directive.expression as RefreshExpr
        assertTrue(refresh.body is NumberLiteral)
    }

    @Test
    fun `refresh with function call parses correctly`() {
        val directive = parse("[refresh[datetime]]")

        assertTrue(directive.expression is RefreshExpr)
        val refresh = directive.expression as RefreshExpr
        assertTrue(refresh.body is CallExpr)
        assertEquals("datetime", (refresh.body as CallExpr).name)
    }

    @Test
    fun `refresh with time comparison parses correctly`() {
        val directive = parse("[refresh[time.gt(\"12:00\")]]")

        assertTrue(directive.expression is RefreshExpr)
        val refresh = directive.expression as RefreshExpr
        assertTrue(refresh.body is MethodCall)
    }

    @Test
    fun `refresh with if expression parses correctly`() {
        val directive = parse("[refresh[if(time.gt(\"12:00\"), \"afternoon\", \"morning\")]]")

        assertTrue(directive.expression is RefreshExpr)
        val refresh = directive.expression as RefreshExpr
        assertTrue(refresh.body is CallExpr)
        assertEquals("if", (refresh.body as CallExpr).name)
    }

    @Test
    fun `refresh with multi-statement body parses correctly`() {
        val directive = parse("[refresh[x: \"12:00\"; time.gt(x)]]")

        assertTrue(directive.expression is RefreshExpr)
        val refresh = directive.expression as RefreshExpr
        assertTrue(refresh.body is StatementList)
    }

    // endregion

    // region refresh[...] evaluation

    @Test
    fun `refresh with simple value returns the value`() {
        val result = execute("[refresh[5]]")

        assertTrue(result is NumberVal)
        assertEquals(5.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `refresh with string returns the string`() {
        val result = execute("[refresh[\"hello\"]]")

        assertTrue(result is StringVal)
        assertEquals("hello", (result as StringVal).value)
    }

    @Test
    fun `refresh with arithmetic evaluates correctly`() {
        val result = execute("[refresh[add(3, 4)]]")

        assertTrue(result is NumberVal)
        assertEquals(7.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `refresh with datetime returns DateTimeVal`() {
        val result = execute("[refresh[datetime]]")

        assertTrue(result is DateTimeVal)
    }

    @Test
    fun `refresh with date returns DateVal`() {
        val result = execute("[refresh[date]]")

        assertTrue(result is DateVal)
    }

    @Test
    fun `refresh with time returns TimeVal`() {
        val result = execute("[refresh[time]]")

        assertTrue(result is TimeVal)
    }

    @Test
    fun `refresh with time comparison evaluates`() {
        // This will evaluate to true or false based on current time
        val result = execute("[refresh[time.gt(\"00:00\")]]")

        assertTrue(result is BooleanVal)
        // At any normal time, time > 00:00 should be true
        assertTrue((result as BooleanVal).value)
    }

    // endregion

    // region refresh[...] allows temporal values

    @Test
    fun `refresh with date does not throw bare time error`() {
        // This should NOT throw an error - refresh is a valid wrapper
        val result = execute("[refresh[date]]")
        assertTrue(result is DateVal)
    }

    @Test
    fun `refresh with time does not throw bare time error`() {
        val result = execute("[refresh[time]]")
        assertTrue(result is TimeVal)
    }

    @Test
    fun `refresh with datetime does not throw bare time error`() {
        val result = execute("[refresh[datetime]]")
        assertTrue(result is DateTimeVal)
    }

    // endregion

    // region Static analysis

    @Test
    fun `refresh expression IS classified as dynamic`() {
        val directive = parse("[refresh[time.gt(\"12:00\")]]")
        // refresh[...] is dynamic because it re-evaluates at trigger times
        assertTrue(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `refresh with static body is still dynamic`() {
        val directive = parse("[refresh[5]]")
        // Even with a static body, the refresh wrapper makes it dynamic
        assertTrue(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `refresh expression is classified as idempotent`() {
        val directive = parse("[refresh[time.gt(\"12:00\")]]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        // refresh is idempotent - same time input = same result
        assertTrue(result.isIdempotent)
    }

    // endregion

    // region refresh as function argument

    @Test
    fun `refresh can be used in expressions`() {
        // refresh returns a value that can be used
        val env = Environment()
        env.define("x", NumberVal(5.0))

        val result = execute("[add(refresh[3], x)]", env)

        assertTrue(result is NumberVal)
        assertEquals(8.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `refresh can be assigned to variable`() {
        val result = execute("[x: refresh[5]; add(x, 1)]")

        assertTrue(result is NumberVal)
        assertEquals(6.0, (result as NumberVal).value, 0.001)
    }

    // endregion

    // region refresh vs once comparison

    @Test
    fun `refresh and once are both valid temporal wrappers`() {
        // Both should work without throwing errors
        execute("[once[date]]")
        execute("[refresh[date]]")
    }

    @Test
    fun `refresh re-evaluates while once caches`() {
        // This test demonstrates the conceptual difference
        // For now, without trigger analysis, both just evaluate the body
        // The difference will be more apparent in Phase 3

        val onceResult = execute("[once[5]]")
        val refreshResult = execute("[refresh[5]]")

        assertEquals(5.0, (onceResult as NumberVal).value, 0.001)
        assertEquals(5.0, (refreshResult as NumberVal).value, 0.001)
    }

    // endregion
}
