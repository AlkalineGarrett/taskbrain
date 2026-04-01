package org.alkaline.taskbrain.dsl.language

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
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
import java.time.LocalDate
import java.util.Date

/**
 * Tests for lambda expressions and parse_date function.
 *
 * Milestone 8.
 */
class LambdaTest {

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

    // region parse_date tests

    @Test
    fun `parse_date parses valid ISO date`() {
        val result = execute("[parse_date \"2026-01-15\"]")

        assertTrue(result is DateVal)
        assertEquals(LocalDate.of(2026, 1, 15), (result as DateVal).value)
    }

    @Test
    fun `parse_date parses various dates`() {
        assertEquals(
            LocalDate.of(2000, 12, 31),
            (execute("[parse_date \"2000-12-31\"]") as DateVal).value
        )
        assertEquals(
            LocalDate.of(1999, 1, 1),
            (execute("[parse_date \"1999-01-01\"]") as DateVal).value
        )
    }

    @Test
    fun `parse_date throws on invalid date format`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[parse_date \"not-a-date\"]")
        }
        assertTrue(exception.message!!.contains("failed to parse date"))
    }

    @Test
    fun `parse_date throws on wrong argument type`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[parse_date 123]")
        }
        assertTrue(exception.message!!.contains("must be a string"))
    }

    @Test
    fun `parse_date throws on missing argument`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[parse_date]")
        }
        assertTrue(exception.message!!.contains("requires 1 arguments"))
    }

    @Test
    fun `parse_date is classified as static`() {
        assertFalse(org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry.isDynamic("parse_date"))
    }

    // endregion

    // region Lambda parsing tests

    @Test
    fun `parses simple lambda`() {
        val directive = parse("[[i]]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertEquals(listOf("i"), lambda.params)
        assertTrue(lambda.body is CallExpr)  // 'i' parses as CallExpr initially
    }

    @Test
    fun `parses lambda with property access`() {
        val directive = parse("[[i.path]]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertTrue(lambda.body is PropertyAccess)
    }

    @Test
    fun `parses lambda with function call`() {
        val directive = parse("[[parse_date i.path]]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertTrue(lambda.body is CallExpr)
        assertEquals("parse_date", (lambda.body as CallExpr).name)
    }

    @Test
    fun `parses lambda with matches call`() {
        val directive = parse("[[matches(i.path, pattern(digit*4))]]")

        assertTrue(directive.expression is LambdaExpr)
        val lambda = directive.expression as LambdaExpr
        assertTrue(lambda.body is CallExpr)
        assertEquals("matches", (lambda.body as CallExpr).name)
    }

    @Test
    fun `rejects lambda without closing bracket`() {
        val exception = assertThrows(ParseException::class.java) {
            parse("[[i.path")
        }
        assertTrue(exception.message!!.contains("Expected ']'"))
    }

    // endregion

    // region Lambda evaluation tests

    @Test
    fun `evaluates lambda to LambdaVal`() {
        val result = execute("[[i]]")

        assertTrue(result is LambdaVal)
        assertEquals(listOf("i"), (result as LambdaVal).params)
    }

    @Test
    fun `lambda displays correctly`() {
        val result = execute("[[i.path]]") as LambdaVal

        assertEquals("<lambda(i)>", result.toDisplayString())
    }

    @Test
    fun `lambda cannot be serialized`() {
        val result = execute("[[i]]") as LambdaVal

        assertThrows(UnsupportedOperationException::class.java) {
            result.serialize()
        }
    }

    // endregion

    // region Lambda invocation tests

    @Test
    fun `lambda invocation binds parameter correctly`() {
        val lambdaVal = execute("[[i]]") as LambdaVal

        val result = executor.invokeLambda(lambdaVal, listOf(NumberVal(42.0)))

        assertTrue(result is NumberVal)
        assertEquals(42.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `lambda can access property on bound parameter`() {
        val note = Note(id = "1", path = "test/path", content = "Content")
        val noteVal = NoteVal(note)

        val lambdaVal = execute("[[i.path]]") as LambdaVal
        val result = executor.invokeLambda(lambdaVal, listOf(noteVal))

        assertTrue(result is StringVal)
        assertEquals("test/path", (result as StringVal).value)
    }

    @Test
    fun `lambda captures outer scope variables`() {
        // Define a variable in the environment, then create a lambda that uses it
        val env = Environment()
        env.define("x", NumberVal(10.0))

        val lambdaVal = execute("[[add(i, x)]]", env) as LambdaVal
        val result = executor.invokeLambda(lambdaVal, listOf(NumberVal(5.0)))

        assertTrue(result is NumberVal)
        assertEquals(15.0, (result as NumberVal).value, 0.001)
    }

    // endregion

    // region Direct lambda invocation (Milestone 8)

    @Test
    fun `direct lambda invocation with variable`() {
        val result = execute("[f: [add(i, 1)]; f(5)]")

        assertTrue(result is NumberVal)
        assertEquals(6.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `direct lambda invocation with property access`() {
        val note = Note(id = "1", path = "test/path", content = "Content")
        val env = Environment()
        env.define("n", NoteVal(note))

        val result = execute("[f: [i.path]; f(n)]", env)

        assertTrue(result is StringVal)
        assertEquals("test/path", (result as StringVal).value)
    }

    @Test
    fun `chained lambda calls`() {
        val result = execute("[f: [mul(i, 2)]; g: [add(i, 10)]; f(g(5))]")

        assertTrue(result is NumberVal)
        // g(5) = 5 + 10 = 15, f(15) = 15 * 2 = 30
        assertEquals(30.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `lambda invocation with zero args errors`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[f: [add(i, 1)]; f]")
        }
        assertTrue(exception.message!!.contains("requires 1 argument"))
    }

    @Test
    fun `lambda invocation with too many args errors`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[f: [add(i, 1)]; f(1, 2)]")
        }
        assertTrue(exception.message!!.contains("requires 1 argument"))
    }

    @Test
    fun `calling non-lambda variable errors`() {
        val exception = assertThrows(ExecutionException::class.java) {
            execute("[x: 5; x(10)]")
        }
        assertTrue(exception.message!!.contains("Cannot call number as a function"))
    }

    @Test
    fun `lambda that ignores parameter works`() {
        val result = execute("[f: [42]; f(999)]")

        assertTrue(result is NumberVal)
        assertEquals(42.0, (result as NumberVal).value, 0.001)
    }

    @Test
    fun `inline lambda invocation`() {
        // Can't do [...](arg) directly due to parser, but can assign and call
        val result = execute("[f: [mul(i, i)]; f(7)]")

        assertTrue(result is NumberVal)
        assertEquals(49.0, (result as NumberVal).value, 0.001)
    }

    // endregion

    // region find with where: lambda tests

    private val testNotes = listOf(
        Note(
            id = "note1",
            userId = "user1",
            path = "2026-01-15",
            content = "Journal entry for Jan 15",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note2",
            userId = "user1",
            path = "2026-01-16",
            content = "Journal entry for Jan 16",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note3",
            userId = "user1",
            path = "journal/2026-01-17",
            content = "Meeting notes",
            createdAt = Timestamp(Date())
        ),
        Note(
            id = "note4",
            userId = "user1",
            path = "tasks/inbox",
            content = "Task inbox",
            createdAt = Timestamp(Date())
        )
    )

    @Test
    fun `find with where lambda filters by path pattern`() {
        val env = Environment.withNotes(testNotes)
        val result = execute(
            "[find(where: [matches(i.path, pattern(digit*4 \"-\" digit*2 \"-\" digit*2))])]",
            env
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(2, list.size)

        val paths = list.items.map { (it as NoteVal).note.path }.toSet()
        assertTrue(paths.contains("2026-01-15"))
        assertTrue(paths.contains("2026-01-16"))
    }

    @Test
    fun `find with where lambda filters by name pattern`() {
        val env = Environment.withNotes(testNotes)
        val result = execute(
            "[find(where: [matches(i.name, pattern(\"Journal\" any*(0..)))])]",
            env
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(2, list.size)

        val ids = list.items.map { (it as NoteVal).note.id }.toSet()
        assertTrue(ids.contains("note1"))
        assertTrue(ids.contains("note2"))
    }

    @Test
    fun `find with where lambda returns empty when no matches`() {
        val env = Environment.withNotes(testNotes)
        val result = execute(
            "[find(where: [matches(i.path, pattern(\"archive/\" any*(1..)))])]",
            env
        )

        assertTrue(result is ListVal)
        assertTrue((result as ListVal).isEmpty())
    }

    @Test
    fun `find with where lambda and path filter combined`() {
        val env = Environment.withNotes(testNotes)
        // Path starts with "2026" AND where lambda checks name contains "15"
        val result = execute(
            "[find(path: pattern(\"2026\" any*(0..)), where: [matches(i.name, pattern(any*(0..) \"15\" any*(0..)))])]",
            env
        )

        assertTrue(result is ListVal)
        val list = result as ListVal
        assertEquals(1, list.size)
        assertEquals("note1", (list[0] as NoteVal).note.id)
    }

    @Test
    fun `find where lambda must return boolean`() {
        val env = Environment.withNotes(testNotes)
        val exception = assertThrows(ExecutionException::class.java) {
            // Lambda returns a string, not boolean
            execute("[find(where: [i.path])]", env)
        }
        assertTrue(exception.message!!.contains("must return a boolean"))
    }

    // endregion

    // region Dynamic and idempotency analysis for lambdas

    @Test
    fun `lambda is classified as static when body is static`() {
        val directive = parse("[[i.path]]")
        assertFalse(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `lambda is classified as dynamic when body is dynamic`() {
        val directive = parse("[[date]]")
        assertTrue(DynamicCallAnalyzer.containsDynamicCalls(directive.expression))
    }

    @Test
    fun `lambda is idempotent when body is idempotent`() {
        val directive = parse("[[i.path]]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertTrue(result.isIdempotent)
    }

    @Test
    fun `lambda is non-idempotent when body is non-idempotent`() {
        val directive = parse("[[new(path: \"test\")]]")
        val result = IdempotencyAnalyzer.analyze(directive.expression)
        assertFalse(result.isIdempotent)
    }

    // endregion
}
