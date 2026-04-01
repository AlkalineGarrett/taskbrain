package org.alkaline.taskbrain.dsl.language

import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.InMemoryOnceCache
import org.alkaline.taskbrain.dsl.runtime.NoteContext
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for Phase 0c: once[...] execution block.
 *
 * Tests:
 * - once[...] parsing
 * - once[...] caching behavior
 * - Bare temporal value validation
 * - Static analysis for OnceExpr
 */
class OnceExprTest {

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

    // region once[...] parsing

    @Test
    fun `once keyword followed by bracket parses as OnceExpr`() {
        val directive = parse("[once[5]]")

        assertTrue(directive.expression is OnceExpr)
        val once = directive.expression as OnceExpr
        assertTrue(once.body is NumberLiteral)
    }

    @Test
    fun `once with function call parses correctly`() {
        val directive = parse("[once[datetime]]")

        assertTrue(directive.expression is OnceExpr)
        val once = directive.expression as OnceExpr
        assertTrue(once.body is CallExpr)
        assertEquals("datetime", (once.body as CallExpr).name)
    }

    @Test
    fun `once with complex expression parses correctly`() {
        val directive = parse("[once[add(1, 2)]]")

        assertTrue(directive.expression is OnceExpr)
        val once = directive.expression as OnceExpr
        assertTrue(once.body is CallExpr)
        assertEquals("add", (once.body as CallExpr).name)
    }

    @Test
    fun `once with multi-statement body parses correctly`() {
        val directive = parse("[once[x: 5; add(x, 1)]]")

        assertTrue(directive.expression is OnceExpr)
        val once = directive.expression as OnceExpr
        assertTrue(once.body is StatementList)
    }

    // endregion

    // region once[...] evaluation

    @Test
    fun `once with simple value returns the value`() {
        val result = execute("[once[5]]")

        assertTrue(result is NumberVal)
        assertEquals(5.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `once with string returns the string`() {
        val result = execute("[once[\"hello\"]]")

        assertTrue(result is StringVal)
        assertEquals("hello", (result as StringVal).value)
    }

    @Test
    fun `once with arithmetic evaluates correctly`() {
        val result = execute("[once[add(3, 4)]]")

        assertTrue(result is NumberVal)
        assertEquals(7.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `once with datetime returns DateTimeVal`() {
        val result = execute("[once[datetime]]")

        assertTrue(result is DateTimeVal)
    }

    @Test
    fun `once with date returns DateVal`() {
        val result = execute("[once[date]]")

        assertTrue(result is DateVal)
    }

    @Test
    fun `once with time returns TimeVal`() {
        val result = execute("[once[time]]")

        assertTrue(result is TimeVal)
    }

    // endregion

    // region Caching behavior

    @Test
    fun `once caches result on first evaluation`() {
        // Create environment with a shared cache
        val cache = InMemoryOnceCache()
        val env = Environment(NoteContext(onceCache = cache))

        // Execute twice with same cache
        val result1 = execute("[once[datetime]]", env)
        Thread.sleep(10) // Small delay to ensure time changes
        val result2 = execute("[once[datetime]]", env)

        // Results should be the same (cached)
        assertEquals(result1, result2)
    }

    @Test
    fun `once with different expressions use different cache keys`() {
        val cache = InMemoryOnceCache()
        val env = Environment(NoteContext(onceCache = cache))

        val result1 = execute("[once[add(1, 2)]]", env)
        val result2 = execute("[once[add(2, 3)]]", env)

        // Different expressions should have different results
        assertEquals(3.0, (result1 as NumberVal).value, 0.001)
        assertEquals(5.0, (result2 as NumberVal).value, 0.001)
    }

    @Test
    fun `once without pre-existing cache creates cache automatically`() {
        // No cache in environment
        val env = Environment()

        // Should still work (creates temporary cache)
        val result = execute("[once[5]]", env)

        assertTrue(result is NumberVal)
        assertEquals(5.0, (result as NumberVal).value, 0.001)
    }

    // endregion

    // region Bare temporal validation

    @Test
    fun `bare date without once throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[date]")
        }
        assertTrue(exception.message!!.contains("Bare date value is not allowed"))
        assertTrue(exception.message!!.contains("once[date]"))
    }

    @Test
    fun `bare time without once throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[time]")
        }
        assertTrue(exception.message!!.contains("Bare time value is not allowed"))
        assertTrue(exception.message!!.contains("once[time]"))
    }

    @Test
    fun `bare datetime without once throws error`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[datetime]")
        }
        assertTrue(exception.message!!.contains("Bare datetime value is not allowed"))
        assertTrue(exception.message!!.contains("once[datetime]"))
    }

    @Test
    fun `date wrapped in once does not throw error`() {
        val result = execute("[once[date]]")
        assertTrue(result is DateVal)
    }

    @Test
    fun `time wrapped in once does not throw error`() {
        val result = execute("[once[time]]")
        assertTrue(result is TimeVal)
    }

    @Test
    fun `datetime wrapped in once does not throw error`() {
        val result = execute("[once[datetime]]")
        assertTrue(result is DateTimeVal)
    }

    @Test
    fun `non-temporal values without once are allowed`() {
        // These should not throw errors
        execute("[5]")
        execute("[\"hello\"]")
        execute("[add(1, 2)]")
    }

    // endregion

    // region Static analysis

    @Test
    fun `once expression is NOT classified as dynamic`() {
        val directive = parse("[once[datetime]]")
        // once[...] is not dynamic because the result is cached
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `once expression is classified as idempotent`() {
        val directive = parse("[once[datetime]]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertTrue(result.isIdempotent)
    }

    @Test
    fun `once with non-idempotent body is still idempotent`() {
        // new() is non-idempotent, but once[new(...)] is idempotent
        // because it only executes once
        val directive = parse("[once[new(path: \"test\")]]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertTrue(result.isIdempotent)
    }

    // endregion

    // region once as function argument

    @Test
    fun `once can be passed as function argument`() {
        // once[...] returns a value, which can be used as an argument
        val result = execute("[add(once[3], once[4])]")

        assertTrue(result is NumberVal)
        assertEquals(7.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `once can be assigned to variable`() {
        val result = execute("[x: once[5]; add(x, 1)]")

        assertTrue(result is NumberVal)
        assertEquals(6.0, (result as NumberVal).value, 0.001)
    }

    // endregion
}
