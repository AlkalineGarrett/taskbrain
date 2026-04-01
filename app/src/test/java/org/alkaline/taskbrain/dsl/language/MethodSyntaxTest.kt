package org.alkaline.taskbrain.dsl.language

import org.alkaline.taskbrain.dsl.runtime.values.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.values.DateTimeVal
import org.alkaline.taskbrain.dsl.runtime.values.DateVal
import org.alkaline.taskbrain.dsl.runtime.values.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.Executor
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.alkaline.taskbrain.dsl.runtime.values.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.TimeVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Tests for Phase 0a: Method-style syntax support.
 *
 * Tests:
 * - Comparison functions (eq, ne, gt, lt, gte, lte)
 * - Comparison methods (a.eq(b), a.gt(b), etc.)
 * - Logical functions and methods (and, or, not)
 * - String methods (startsWith, endsWith, contains)
 * - Temporal .plus() methods (date, time, datetime)
 */
class MethodSyntaxTest {

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

    // region Comparison Functions (function-style)

    @Test
    fun `eq function returns true for equal numbers`() {
        val result = execute("[eq(5, 5)]")
        assertTrue(result is BooleanVal)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `eq function returns false for unequal numbers`() {
        val result = execute("[eq(5, 3)]")
        assertTrue(result is BooleanVal)
        assertFalse((result as BooleanVal).value)
    }

    @Test
    fun `ne function returns true for unequal numbers`() {
        val result = execute("[ne(5, 3)]")
        assertTrue(result is BooleanVal)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `gt function returns true when greater`() {
        val result = execute("[gt(5, 3)]")
        assertTrue(result is BooleanVal)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `gt function returns false when not greater`() {
        val result = execute("[gt(3, 5)]")
        assertTrue(result is BooleanVal)
        assertFalse((result as BooleanVal).value)
    }

    @Test
    fun `lt function returns true when less`() {
        val result = execute("[lt(3, 5)]")
        assertTrue(result is BooleanVal)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `gte function returns true when greater or equal`() {
        assertTrue((execute("[gte(5, 5)]") as BooleanVal).value)
        assertTrue((execute("[gte(6, 5)]") as BooleanVal).value)
        assertFalse((execute("[gte(4, 5)]") as BooleanVal).value)
    }

    @Test
    fun `lte function returns true when less or equal`() {
        assertTrue((execute("[lte(5, 5)]") as BooleanVal).value)
        assertTrue((execute("[lte(4, 5)]") as BooleanVal).value)
        assertFalse((execute("[lte(6, 5)]") as BooleanVal).value)
    }

    @Test
    fun `eq function works with strings`() {
        assertTrue((execute("[eq(\"hello\", \"hello\")]") as BooleanVal).value)
        assertFalse((execute("[eq(\"hello\", \"world\")]") as BooleanVal).value)
    }

    @Test
    fun `gt function works with strings`() {
        assertTrue((execute("[gt(\"b\", \"a\")]") as BooleanVal).value)
        assertFalse((execute("[gt(\"a\", \"b\")]") as BooleanVal).value)
    }

    @Test
    fun `comparison with date and string coercion`() {
        // Date compared with string
        val env = Environment()
        env.define("d", DateVal(LocalDate.of(2026, 1, 15)))

        assertTrue((execute("[gt(d, \"2026-01-14\")]", env) as BooleanVal).value)
        assertFalse((execute("[gt(d, \"2026-01-16\")]", env) as BooleanVal).value)
        assertTrue((execute("[eq(d, \"2026-01-15\")]", env) as BooleanVal).value)
    }

    @Test
    fun `comparison with time and string coercion`() {
        val env = Environment()
        env.define("t", TimeVal(LocalTime.of(14, 30)))

        assertTrue((execute("[gt(t, \"12:00\")]", env) as BooleanVal).value)
        assertFalse((execute("[gt(t, \"16:00\")]", env) as BooleanVal).value)
        assertTrue((execute("[eq(t, \"14:30\")]", env) as BooleanVal).value)
    }

    // endregion

    // region Logical Functions (function-style)

    @Test
    fun `and function works correctly`() {
        val env = Environment()
        env.define("t", BooleanVal(true))
        env.define("f", BooleanVal(false))

        assertTrue((execute("[and(t, t)]", env) as BooleanVal).value)
        assertFalse((execute("[and(t, f)]", env) as BooleanVal).value)
        assertFalse((execute("[and(f, t)]", env) as BooleanVal).value)
        assertFalse((execute("[and(f, f)]", env) as BooleanVal).value)
    }

    @Test
    fun `or function works correctly`() {
        val env = Environment()
        env.define("t", BooleanVal(true))
        env.define("f", BooleanVal(false))

        assertTrue((execute("[or(t, t)]", env) as BooleanVal).value)
        assertTrue((execute("[or(t, f)]", env) as BooleanVal).value)
        assertTrue((execute("[or(f, t)]", env) as BooleanVal).value)
        assertFalse((execute("[or(f, f)]", env) as BooleanVal).value)
    }

    @Test
    fun `not function works correctly`() {
        val env = Environment()
        env.define("t", BooleanVal(true))
        env.define("f", BooleanVal(false))

        assertFalse((execute("[not(t)]", env) as BooleanVal).value)
        assertTrue((execute("[not(f)]", env) as BooleanVal).value)
    }

    // endregion

    // region Comparison Methods (method-style)

    @Test
    fun `method eq on numbers`() {
        val env = Environment()
        env.define("x", NumberVal(5.0))

        assertTrue((execute("[x.eq(5)]", env) as BooleanVal).value)
        assertFalse((execute("[x.eq(3)]", env) as BooleanVal).value)
    }

    @Test
    fun `method gt on numbers`() {
        val env = Environment()
        env.define("x", NumberVal(5.0))

        assertTrue((execute("[x.gt(3)]", env) as BooleanVal).value)
        assertFalse((execute("[x.gt(7)]", env) as BooleanVal).value)
    }

    @Test
    fun `method lt on numbers`() {
        val env = Environment()
        env.define("x", NumberVal(5.0))

        assertTrue((execute("[x.lt(7)]", env) as BooleanVal).value)
        assertFalse((execute("[x.lt(3)]", env) as BooleanVal).value)
    }

    @Test
    fun `method gte on numbers`() {
        val env = Environment()
        env.define("x", NumberVal(5.0))

        assertTrue((execute("[x.gte(5)]", env) as BooleanVal).value)
        assertTrue((execute("[x.gte(3)]", env) as BooleanVal).value)
        assertFalse((execute("[x.gte(7)]", env) as BooleanVal).value)
    }

    @Test
    fun `method lte on numbers`() {
        val env = Environment()
        env.define("x", NumberVal(5.0))

        assertTrue((execute("[x.lte(5)]", env) as BooleanVal).value)
        assertTrue((execute("[x.lte(7)]", env) as BooleanVal).value)
        assertFalse((execute("[x.lte(3)]", env) as BooleanVal).value)
    }

    @Test
    fun `method ne on numbers`() {
        val env = Environment()
        env.define("x", NumberVal(5.0))

        assertTrue((execute("[x.ne(3)]", env) as BooleanVal).value)
        assertFalse((execute("[x.ne(5)]", env) as BooleanVal).value)
    }

    @Test
    fun `chained comparison methods`() {
        // x.gt(3).and(x.lt(7)) where x = 5 -> true
        val env = Environment()
        env.define("x", NumberVal(5.0))

        val result = execute("[x.gt(3).and(x.lt(7))]", env)
        assertTrue(result is BooleanVal)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `comparison methods on strings`() {
        val env = Environment()
        env.define("s", StringVal("hello"))

        assertTrue((execute("[s.eq(\"hello\")]", env) as BooleanVal).value)
        assertTrue((execute("[s.gt(\"abc\")]", env) as BooleanVal).value)
        assertTrue((execute("[s.lt(\"world\")]", env) as BooleanVal).value)
    }

    @Test
    fun `comparison methods on dates`() {
        val env = Environment()
        env.define("d", DateVal(LocalDate.of(2026, 1, 15)))

        assertTrue((execute("[d.gt(\"2026-01-14\")]", env) as BooleanVal).value)
        assertTrue((execute("[d.lt(\"2026-01-16\")]", env) as BooleanVal).value)
        assertTrue((execute("[d.eq(\"2026-01-15\")]", env) as BooleanVal).value)
    }

    @Test
    fun `comparison methods on times`() {
        val env = Environment()
        env.define("t", TimeVal(LocalTime.of(14, 30)))

        assertTrue((execute("[t.gt(\"12:00\")]", env) as BooleanVal).value)
        assertTrue((execute("[t.lt(\"16:00\")]", env) as BooleanVal).value)
        assertTrue((execute("[t.eq(\"14:30\")]", env) as BooleanVal).value)
    }

    // endregion

    // region Logical Methods (method-style)

    @Test
    fun `method and on booleans`() {
        val env = Environment()
        env.define("t", BooleanVal(true))
        env.define("f", BooleanVal(false))

        assertTrue((execute("[t.and(t)]", env) as BooleanVal).value)
        assertFalse((execute("[t.and(f)]", env) as BooleanVal).value)
    }

    @Test
    fun `method or on booleans`() {
        val env = Environment()
        env.define("t", BooleanVal(true))
        env.define("f", BooleanVal(false))

        assertTrue((execute("[f.or(t)]", env) as BooleanVal).value)
        assertFalse((execute("[f.or(f)]", env) as BooleanVal).value)
    }

    @Test
    fun `chained logical methods`() {
        // a.and(b).or(c) where a=true, b=false, c=true -> true
        val env = Environment()
        env.define("a", BooleanVal(true))
        env.define("b", BooleanVal(false))
        env.define("c", BooleanVal(true))

        val result = execute("[a.and(b).or(c)]", env)
        assertTrue((result as BooleanVal).value)
    }

    // endregion

    // region String Methods

    @Test
    fun `startsWith method returns true for matching prefix`() {
        val env = Environment()
        env.define("s", StringVal("hello world"))

        val result = execute("[s.startsWith(\"hello\")]", env)
        assertTrue(result is BooleanVal)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `startsWith method returns false for non-matching prefix`() {
        val env = Environment()
        env.define("s", StringVal("hello world"))

        val result = execute("[s.startsWith(\"world\")]", env)
        assertTrue(result is BooleanVal)
        assertFalse((result as BooleanVal).value)
    }

    @Test
    fun `endsWith method returns true for matching suffix`() {
        val env = Environment()
        env.define("s", StringVal("hello world"))

        val result = execute("[s.endsWith(\"world\")]", env)
        assertTrue(result is BooleanVal)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `endsWith method returns false for non-matching suffix`() {
        val env = Environment()
        env.define("s", StringVal("hello world"))

        val result = execute("[s.endsWith(\"hello\")]", env)
        assertTrue(result is BooleanVal)
        assertFalse((result as BooleanVal).value)
    }

    @Test
    fun `contains method returns true when substring present`() {
        val env = Environment()
        env.define("s", StringVal("hello world"))

        assertTrue((execute("[s.contains(\"lo wo\")]", env) as BooleanVal).value)
        assertTrue((execute("[s.contains(\"hello\")]", env) as BooleanVal).value)
        assertTrue((execute("[s.contains(\"world\")]", env) as BooleanVal).value)
    }

    @Test
    fun `contains method returns false when substring absent`() {
        val env = Environment()
        env.define("s", StringVal("hello world"))

        val result = execute("[s.contains(\"xyz\")]", env)
        assertTrue(result is BooleanVal)
        assertFalse((result as BooleanVal).value)
    }

    @Test
    fun `string methods work on literals`() {
        // Using inline string values
        val result = execute("[x: \"inbox/tasks\"; x.startsWith(\"inbox\")]")
        assertTrue((result as BooleanVal).value)
    }

    // endregion

    // region Temporal .plus() Methods

    @Test
    fun `date plus days`() {
        val env = Environment()
        env.define("d", DateVal(LocalDate.of(2026, 1, 15)))

        val result = execute("[d.plus(days: 7)]", env)
        assertTrue(result is DateVal)
        assertEquals(LocalDate.of(2026, 1, 22), (result as DateVal).value)
    }

    @Test
    fun `date plus negative days`() {
        val env = Environment()
        env.define("d", DateVal(LocalDate.of(2026, 1, 15)))

        val result = execute("[d.plus(days: neg(1))]", env)
        assertTrue(result is DateVal)
        assertEquals(LocalDate.of(2026, 1, 14), (result as DateVal).value)
    }

    @Test
    fun `time plus hours`() {
        val env = Environment()
        env.define("t", TimeVal(LocalTime.of(14, 30)))

        val result = execute("[t.plus(hours: 2)]", env)
        assertTrue(result is TimeVal)
        assertEquals(LocalTime.of(16, 30), (result as TimeVal).value)
    }

    @Test
    fun `time plus minutes`() {
        val env = Environment()
        env.define("t", TimeVal(LocalTime.of(14, 30)))

        val result = execute("[t.plus(minutes: 45)]", env)
        assertTrue(result is TimeVal)
        assertEquals(LocalTime.of(15, 15), (result as TimeVal).value)
    }

    @Test
    fun `time plus hours and minutes combined`() {
        val env = Environment()
        env.define("t", TimeVal(LocalTime.of(14, 30)))

        val result = execute("[t.plus(hours: 1, minutes: 15)]", env)
        assertTrue(result is TimeVal)
        assertEquals(LocalTime.of(15, 45), (result as TimeVal).value)
    }

    @Test
    fun `datetime plus days`() {
        val env = Environment()
        env.define("dt", DateTimeVal(LocalDateTime.of(2026, 1, 15, 14, 30)))

        val result = execute("[dt.plus(days: 1)]", env)
        assertTrue(result is DateTimeVal)
        assertEquals(LocalDateTime.of(2026, 1, 16, 14, 30), (result as DateTimeVal).value)
    }

    @Test
    fun `datetime plus hours`() {
        val env = Environment()
        env.define("dt", DateTimeVal(LocalDateTime.of(2026, 1, 15, 14, 30)))

        val result = execute("[dt.plus(hours: 3)]", env)
        assertTrue(result is DateTimeVal)
        assertEquals(LocalDateTime.of(2026, 1, 15, 17, 30), (result as DateTimeVal).value)
    }

    @Test
    fun `datetime plus all components`() {
        val env = Environment()
        env.define("dt", DateTimeVal(LocalDateTime.of(2026, 1, 15, 14, 30)))

        val result = execute("[dt.plus(days: 1, hours: 2, minutes: 15)]", env)
        assertTrue(result is DateTimeVal)
        assertEquals(LocalDateTime.of(2026, 1, 16, 16, 45), (result as DateTimeVal).value)
    }

    @Test
    fun `time plus wraps correctly`() {
        val env = Environment()
        env.define("t", TimeVal(LocalTime.of(23, 30)))

        val result = execute("[t.plus(hours: 2)]", env)
        assertTrue(result is TimeVal)
        assertEquals(LocalTime.of(1, 30), (result as TimeVal).value)
    }

    @Test
    fun `date plus requires days parameter`() {
        val env = Environment()
        env.define("d", DateVal(LocalDate.of(2026, 1, 15)))

        val exception = assertThrows(ExecutionException::class.java) {
            execute("[d.plus(hours: 2)]", env)
        }
        assertTrue(exception.message!!.contains("requires 'days' parameter"))
    }

    @Test
    fun `time plus requires hours or minutes`() {
        val env = Environment()
        env.define("t", TimeVal(LocalTime.of(14, 30)))

        val exception = assertThrows(ExecutionException::class.java) {
            execute("[t.plus(days: 1)]", env)
        }
        assertTrue(exception.message!!.contains("requires at least one of: hours, minutes"))
    }

    // endregion

    // region Complex Expressions Combining Methods

    @Test
    fun `complex expression with comparison and logical methods`() {
        // (x.gt(0)).and(x.lt(10)) where x = 5
        val env = Environment()
        env.define("x", NumberVal(5.0))

        val result = execute("[x.gt(0).and(x.lt(10))]", env)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `complex date comparison with method syntax`() {
        // d.gte("2026-01-01").and(d.lt("2026-02-01"))
        val env = Environment()
        env.define("d", DateVal(LocalDate.of(2026, 1, 15)))

        val result = execute("[d.gte(\"2026-01-01\").and(d.lt(\"2026-02-01\"))]", env)
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `date arithmetic with comparison`() {
        // d.plus(days: 7).gt("2026-01-20")
        val env = Environment()
        env.define("d", DateVal(LocalDate.of(2026, 1, 15)))

        val result = execute("[d.plus(days: 7).gt(\"2026-01-20\")]", env)
        assertTrue(result is BooleanVal)
        // 2026-01-15 + 7 = 2026-01-22, which is > 2026-01-20
        assertTrue((result as BooleanVal).value)
    }

    @Test
    fun `string method chained with comparison`() {
        val env = Environment()
        env.define("s", StringVal("inbox/task"))

        // s.startsWith("inbox").and(s.contains("task"))
        val result = execute("[s.startsWith(\"inbox\").and(s.contains(\"task\"))]", env)
        assertTrue((result as BooleanVal).value)
    }

    // endregion

    // region Error Cases

    @Test
    fun `comparison with incompatible types throws error`() {
        val env = Environment()
        env.define("n", NumberVal(5.0))
        env.define("d", DateVal(LocalDate.of(2026, 1, 15)))

        val exception = assertThrows(ExecutionException::class.java) {
            execute("[eq(n, d)]", env)
        }
        assertTrue(exception.message!!.contains("Cannot compare"))
    }

    @Test
    fun `unknown method on string throws error`() {
        val env = Environment()
        env.define("s", StringVal("hello"))

        val exception = assertThrows(ExecutionException::class.java) {
            execute("[s.unknownMethod()]", env)
        }
        assertTrue(exception.message!!.contains("Unknown method"))
    }

    @Test
    fun `method with wrong argument count throws error`() {
        val env = Environment()
        env.define("s", StringVal("hello"))

        val exception = assertThrows(ExecutionException::class.java) {
            execute("[s.startsWith()]", env)
        }
        assertTrue(exception.message!!.contains("requires 1 arguments"))
    }

    @Test
    fun `method with wrong argument type throws error`() {
        val env = Environment()
        env.define("s", StringVal("hello"))

        val exception = assertThrows(ExecutionException::class.java) {
            execute("[s.startsWith(123)]", env)
        }
        assertTrue(exception.message!!.contains("must be a string"))
    }

    // endregion
}
