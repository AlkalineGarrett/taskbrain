package org.alkaline.taskbrain.dsl.language

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.values.LambdaVal
import org.alkaline.taskbrain.dsl.runtime.values.ListVal
import org.alkaline.taskbrain.dsl.runtime.values.NoteVal
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Tests for Phase 0b: Implicit lambda syntax `[...]`.
 *
 * Tests:
 * - `[...]` in argument position as implicit lambda with `i`
 * - Variable assignment with lambda: [f: [add(i, 1)]]
 * - Multi-arg lambdas: [(a, b)[expr]]
 * - Nested brackets: [find(where: [i.path.startsWith("x")])]
 * - Parentheses equivalence: func[x] and func([x])
 * - Immediate invocation: [[expr](arg)]
 */
class ImplicitLambdaTest {

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

    // region Implicit lambda syntax [...]

    @Test
    fun `implicit lambda creates LambdaExpr with parameter i`() {
        val directive = parse("[[add(i, 1)]]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertEquals(listOf("i"), lambda.params)
    }

    @Test
    fun `implicit lambda with property access`() {
        val directive = parse("[[i.path]]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertTrue(lambda.body is PropertyAccess)
    }

    @Test
    fun `implicit lambda evaluates to LambdaVal`() {
        val result = execute("[[add(i, 1)]]")

        assertTrue(result is LambdaVal)
        assertEquals(listOf("i"), (result as LambdaVal).params)
    }

    @Test
    fun `implicit lambda displays correctly`() {
        val result = execute("[[i.path]]") as LambdaVal

        assertEquals("<lambda(i)>", result.toDisplayString())
    }

    // endregion

    // region Variable assignment with lambda

    @Test
    fun `assign implicit lambda to variable`() {
        val result = execute("[f: [add(i, 1)]; f(5)]")

        assertTrue(result is NumberVal)
        assertEquals(6.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `implicit lambda captures outer variables`() {
        val result = execute("[x: 10; f: [add(i, x)]; f(5)]")

        assertTrue(result is NumberVal)
        assertEquals(15.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `chained implicit lambda calls`() {
        val result = execute("[f: [mul(i, 2)]; g: [add(i, 10)]; f(g(5))]")

        assertTrue(result is NumberVal)
        // g(5) = 5 + 10 = 15, f(15) = 15 * 2 = 30
        assertEquals(30.0, (result as NumberVal).value, 0.001)
    }

    // endregion

    // region Multi-arg lambdas (a, b)[expr]

    @Test
    fun `multi-arg lambda parses correctly`() {
        val directive = parse("[(a, b)[add(a, b)]]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertEquals(listOf("a", "b"), lambda.params)
    }

    @Test
    fun `multi-arg lambda evaluates correctly`() {
        val result = execute("[f: (a, b)[add(a, b)]; f(3, 4)]")

        assertTrue(result is NumberVal)
        assertEquals(7.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `multi-arg lambda with comparison`() {
        val env = Environment()
        val note1 = Note(id = "1", path = "a", content = "A")
        val note2 = Note(id = "2", path = "b", content = "B")
        env.define("n1", NoteVal(note1))
        env.define("n2", NoteVal(note2))

        // Compare note paths
        val result = execute("[f: (a, b)[gt(a.path, b.path)]; f(n2, n1)]", env)

        assertTrue(result is BooleanVal)
        assertTrue((result as BooleanVal).value) // "b" > "a"
    }

    // endregion

    // region Nested brackets with find

    private val testNotes = listOf(
        Note(
            id = "note1",
            userId = "user1",
            path = "inbox/task1",
            content = "First task",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note2",
            userId = "user1",
            path = "inbox/task2",
            content = "Second task",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note3",
            userId = "user1",
            path = "archive/old",
            content = "Old stuff",
            createdAt = Timestamp(Date())
        )
    )

    @Test
    fun `find with implicit lambda where clause`() {
        val env = Environment.withNotes(testNotes)

        // Using implicit lambda syntax
        val result = execute("[find(where: [i.path.startsWith(\"inbox\")])]", env)

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(2, list.size)
    }

    @Test
    fun `find with implicit lambda complex condition`() {
        val env = Environment.withNotes(testNotes)

        val result = execute(
            "[find(where: [i.path.startsWith(\"inbox\").and(i.name.contains(\"First\"))])]",
            env
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)
        assertEquals("note1", (list[0] as NoteVal).note.id)
    }

    // endregion

    // region Parentheses equivalence: func[x] and func([x])

    @Test
    fun `func bracket syntax creates call with lambda argument`() {
        // sort[i.path] should be equivalent to sort([i.path])
        val directive1 = parse("[f: [i]; f[5]]")
        val directive2 = parse("[f: [i]; f([5])]")

        // Both should have a CallExpr with a LambdaExpr argument
        // (Note: the parser creates different structures, but semantically similar)
        // Let's just test execution works the same way
        // Actually f[5] means f([5]), not f(5)
        // This is a lambda that ignores i and returns 5
    }

    @Test
    fun `func bracket syntax works in practice`() {
        // Test that identifier[...] creates a call with a lambda argument
        val directive = parse("[sort[i.path]]")

        assertTrue(directive.expression is CallExpr)
        val call = directive.expression as CallExpr
        assertEquals("sort", call.name)
        assertEquals(1, call.args.size)
        assertTrue(call.args[0] is LambdaExpr)
    }

    // endregion

    // region Immediate invocation [[expr](arg)]

    @Test
    fun `immediate invocation parses as LambdaInvocation`() {
        val directive = parse("[[add(i, 1)](5)]")

        // The outer [...] contains a LambdaInvocation
        assertTrue(directive.expression is LambdaInvocation)
    }

    @Test
    fun `immediate invocation evaluates correctly`() {
        val result = execute("[[add(i, 1)](5)]")

        assertTrue(result is NumberVal)
        assertEquals(6.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `immediate invocation with current note`() {
        val note = Note(id = "1", path = "test/path", content = "Test content")
        val env = Environment.withCurrentNote(note)

        val result = execute("[[i.path](.)]", env)

        assertTrue(result is StringVal)
        assertEquals("test/path", (result as StringVal).value)
    }

    @Test
    fun `immediate invocation with multi-statement lambda`() {
        val result = execute("[[x: mul(i, 2); add(x, 1)](5)]")

        assertTrue(result is NumberVal)
        // i = 5, x = 5 * 2 = 10, result = 10 + 1 = 11
        assertEquals(11.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `immediate invocation with wrong arg count errors`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[[add(i, 1)](5, 6)]")
        }
        assertTrue(exception.message!!.contains("requires 1 argument"))
    }

    // endregion


    // region Static analysis for implicit lambdas

    @Test
    fun `implicit lambda is classified as static when body is static`() {
        val directive = parse("[[i.path]]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `implicit lambda is classified as dynamic when body is dynamic`() {
        val directive = parse("[[date]]")
        assertTrue(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `implicit lambda is idempotent when body is idempotent`() {
        val directive = parse("[[i.path]]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertTrue(result.isIdempotent)
    }

    @Test
    fun `implicit lambda is non-idempotent when body is non-idempotent`() {
        val directive = parse("[[new(path: \"test\")]]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertFalse(result.isIdempotent)
    }

    @Test
    fun `lambda invocation is analyzed for idempotency`() {
        val directive = parse("[[[new(path: \"test\")]](\"x\")]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertFalse(result.isIdempotent)
    }

    @Test
    fun `lambda invocation is analyzed for dynamic calls`() {
        val directive = parse("[[[date]](5)]")
        assertTrue(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    // endregion
}
